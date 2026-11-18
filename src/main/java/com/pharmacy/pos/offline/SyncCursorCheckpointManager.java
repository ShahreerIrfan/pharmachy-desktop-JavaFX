package com.pharmacy.pos.offline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.util.List;

/**
 * SyncCursorCheckpointManager
 * Architecture Decision: ADR-0308 (P308).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Maintains a dedicated `sync_watermarks` singleton table in the device SQLite DB so that:
 * 1. Pull cursor progress (`pull_applied_sequence_no`, `pull_apply_digest_sha256`) advances
 *    ONLY inside the same atomic SQLite commit that durably applies the pulled change-feed batch.
 * 2. Independent Push Acknowledgment watermark (`push_acked_sequence_no`, `push_last_ack_id`)
 *    persists in its own decoupled column/transaction — surviving pull crashes, network drops,
 *    or owner-initiated pull cursor rewinds with zero loss of confirmed push acks.
 */
public class SyncCursorCheckpointManager {

    public static final class WatermarkSnapshot {
        public final long pullAppliedSequenceNo;
        public final String pullApplyDigestSha256;
        public final long pushAckedSequenceNo;
        public final String pushLastAckId;
        public final int recoveryEpoch;

        public WatermarkSnapshot(
                long pullAppliedSequenceNo,
                String pullApplyDigestSha256,
                long pushAckedSequenceNo,
                String pushLastAckId,
                int recoveryEpoch
        ) {
            this.pullAppliedSequenceNo = pullAppliedSequenceNo;
            this.pullApplyDigestSha256 = pullApplyDigestSha256;
            this.pushAckedSequenceNo = pushAckedSequenceNo;
            this.pushLastAckId = pushLastAckId;
            this.recoveryEpoch = recoveryEpoch;
        }
    }

    public static void ensureWatermarkTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                    "CREATE TABLE IF NOT EXISTS sync_watermarks (" +
                            "singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1), " +
                            "pull_applied_sequence_no INTEGER NOT NULL DEFAULT 0 CHECK (pull_applied_sequence_no >= 0), " +
                            "pull_apply_digest_sha256 TEXT NOT NULL DEFAULT '', " +
                            "push_acked_sequence_no INTEGER NOT NULL DEFAULT 0 CHECK (push_acked_sequence_no >= 0), " +
                            "push_last_ack_id TEXT NOT NULL DEFAULT '', " +
                            "recovery_epoch INTEGER NOT NULL DEFAULT 1 CHECK (recovery_epoch >= 1), " +
                            "updated_at TEXT NOT NULL)"
            );
            st.execute(
                    "INSERT OR IGNORE INTO sync_watermarks " +
                            "(singleton_id, pull_applied_sequence_no, pull_apply_digest_sha256, " +
                            "push_acked_sequence_no, push_last_ack_id, recovery_epoch, updated_at) " +
                            "VALUES (1, 0, '', 0, '', 1, '" + Instant.now() + "')"
            );
        }
    }

    public static WatermarkSnapshot readWatermarks(Connection conn) throws SQLException {
        ensureWatermarkTable(conn);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT pull_applied_sequence_no, pull_apply_digest_sha256, push_acked_sequence_no, " +
                        "push_last_ack_id, recovery_epoch FROM sync_watermarks WHERE singleton_id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return new WatermarkSnapshot(0L, "", 0L, "", 1);
            }
            return new WatermarkSnapshot(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getLong(3),
                    rs.getString(4),
                    rs.getInt(5)
            );
        }
    }

    public static String computeBatchDigestSha256(List<ChangeFeedPullClient.ChangeFeedEventRecord> events, long committedSeq) {
        StringBuilder sb = new StringBuilder("pull-apply|").append(committedSeq);
        for (ChangeFeedPullClient.ChangeFeedEventRecord e : events) {
            sb.append('|').append(e.sequenceNo).append(':').append(e.entityType).append(':')
                    .append(e.entityId).append(':').append(e.operation).append(':').append(e.entityVersion);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Applies a change-feed batch via P307 ChangeFeedPullClient and updates the durable pull watermark
     * + SHA-256 proof digest ONLY after SQLite commit succeeds. NEVER touches push_acked_sequence_no!
     */
    public static WatermarkSnapshot applyPullAndCheckpoint(
            Connection conn,
            String tenantId,
            String branchId,
            List<ChangeFeedPullClient.ChangeFeedEventRecord> events,
            int failAtIndexForCrashTest
    ) throws SQLException {
        ensureWatermarkTable(conn);
        ChangeFeedPullClient.ApplyResult applyRes = ChangeFeedPullClient.applyChangeFeedBatchAtomically(
                conn, tenantId, branchId, events, failAtIndexForCrashTest);
        String digest = computeBatchDigestSha256(events, applyRes.committedCursorSequence);

        try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE sync_watermarks SET pull_applied_sequence_no = ?, pull_apply_digest_sha256 = ?, updated_at = ? " +
                        "WHERE singleton_id = 1 AND ? >= pull_applied_sequence_no")) {
            upd.setLong(1, applyRes.committedCursorSequence);
            upd.setString(2, digest);
            upd.setString(3, Instant.now().toString());
            upd.setLong(4, applyRes.committedCursorSequence);
            upd.executeUpdate();
        }
        return readWatermarks(conn);
    }

    /**
     * Independently applies a P306 canonical push acknowledgment and advances push_acked_sequence_no.
     * NEVER modifies pull_applied_sequence_no!
     */
    public static WatermarkSnapshot applyPushAckIndependently(
            Connection conn,
            String tenantId,
            String branchId,
            String commandId,
            long clientSequence,
            String ackId,
            String disposition,
            String canonicalEntityId,
            String batchIdToReconcile,
            int syncedQtyUnits
    ) throws SQLException {
        ensureWatermarkTable(conn);
        CanonicalAckApplier.applyCanonicalAckAtomically(
                conn, tenantId, branchId, commandId, disposition, canonicalEntityId,
                batchIdToReconcile, syncedQtyUnits, "", "{}");

        try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE sync_watermarks SET push_acked_sequence_no = MAX(push_acked_sequence_no, ?), " +
                        "push_last_ack_id = ?, updated_at = ? WHERE singleton_id = 1")) {
            upd.setLong(1, clientSequence);
            upd.setString(2, ackId != null ? ackId : "");
            upd.setString(3, Instant.now().toString());
            upd.executeUpdate();
        }
        return readWatermarks(conn);
    }

    /**
     * Applies an audited pull-cursor rewind (incrementing recovery_epoch) while strictly preserving
     * independent push_acked_sequence_no and push_last_ack_id.
     */
    public static WatermarkSnapshot applyAuditedPullRewind(Connection conn, long targetPullSequenceNo, int newEpoch)
            throws SQLException {
        ensureWatermarkTable(conn);
        boolean origAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE sync_watermarks SET pull_applied_sequence_no = ?, pull_apply_digest_sha256 = '', " +
                            "recovery_epoch = ?, updated_at = ? WHERE singleton_id = 1")) {
                upd.setLong(1, Math.max(0L, targetPullSequenceNo));
                upd.setInt(2, Math.max(1, newEpoch));
                upd.setString(3, Instant.now().toString());
                upd.executeUpdate();
            }
            try (PreparedStatement meta = conn.prepareStatement(
                    "UPDATE device_metadata SET last_sync_cursor = ?, updated_at = ? WHERE singleton_id = 1")) {
                meta.setString(1, String.valueOf(Math.max(0L, targetPullSequenceNo)));
                meta.setString(2, Instant.now().toString());
                meta.executeUpdate();
            }
            conn.commit();
            return readWatermarks(conn);
        } catch (Exception ex) {
            conn.rollback();
            if (ex instanceof SQLException) throw (SQLException) ex;
            throw new SQLException(ex);
        } finally {
            conn.setAutoCommit(origAutoCommit);
        }
    }
}
