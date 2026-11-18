package com.pharmacy.pos.offline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * DurableOutboxRepository
 * Architecture Decision: ADR-0304 (P304).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Guarantees that every local offline POS state mutation and its durable outbox command
 * commit inside ONE atomic SQLite transaction, provides crash recovery on startup
 * (resetting interrupted SYNCING rows -> SAVED_LOCALLY), ordered FIFO replay by client_sequence,
 * and non-destructive conflict queue routing.
 */
public class DurableOutboxRepository {

    @FunctionalInterface
    public interface LocalMutationStep {
        void execute(Connection conn) throws Exception;
    }

    public static String executeAtomicMutationWithOutbox(
            Connection conn,
            String deviceId,
            String tenantId,
            String branchId,
            String commandType,
            String aggregateRef,
            String idempotencyKey,
            BigDecimal monetaryTotalBdt,
            String payloadJson,
            LocalMutationStep localMutation
    ) throws SQLException {
        boolean origAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            // 1. Run local SQLite state change (e.g. stock reservation / local invoice insert)
            if (localMutation != null) {
                localMutation.execute(conn);
            }

            // 2. Monotonic client_sequence
            long nextSeq = 1;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(client_sequence), 0) + 1 FROM pending_offline_commands")) {
                if (rs.next()) nextSeq = rs.getLong(1);
            }

            // 3. Persist durable outbox command in SAME transaction
            String cmdId = "cmd-" + UUID.randomUUID();
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO pending_offline_commands (command_id, idempotency_key, device_id, tenant_id, branch_id, " +
                            "client_sequence, command_type, aggregate_ref, payload_json, monetary_total_bdt, schema_version, " +
                            "sync_state, retry_count, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SAVED_LOCALLY', 0, ?)")) {
                ins.setString(1, cmdId);
                ins.setString(2, idempotencyKey);
                ins.setString(3, deviceId);
                ins.setString(4, tenantId);
                ins.setString(5, branchId);
                ins.setLong(6, nextSeq);
                ins.setString(7, commandType);
                ins.setString(8, aggregateRef);
                ins.setString(9, payloadJson);
                ins.setString(10, monetaryTotalBdt.setScale(2, RoundingMode.HALF_UP).toPlainString());
                ins.setInt(11, DeviceLocalDatabaseSchema.CURRENT_SCHEMA_VERSION);
                ins.setString(12, Instant.now().toString());
                ins.executeUpdate();
            }

            conn.commit();
            return cmdId;
        } catch (Exception ex) {
            conn.rollback();
            if (ex instanceof SQLException) throw (SQLException) ex;
            if (ex instanceof RuntimeException) throw (RuntimeException) ex;
            throw new SQLException(ex);
        } finally {
            conn.setAutoCommit(origAutoCommit);
        }
    }

    /**
     * Startup crash-recovery routine: resets any commands left in SYNCING state by a sudden power loss
     * back to SAVED_LOCALLY so they are safely replayed with the original idempotency_key.
     */
    public static int recoverInterruptedSyncingAfterCrash(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE pending_offline_commands SET sync_state = 'SAVED_LOCALLY' WHERE sync_state = 'SYNCING'")) {
            return ps.executeUpdate();
        }
    }

    public static List<Map<String, Object>> fetchPendingCommandsInOrder(Connection conn, int limit) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT command_id, idempotency_key, client_sequence, command_type, aggregate_ref, payload_json, monetary_total_bdt, sync_state " +
                        "FROM pending_offline_commands WHERE sync_state IN ('SAVED_LOCALLY', 'RETRY_SCHEDULED') " +
                        "ORDER BY client_sequence ASC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("command_id", rs.getString("command_id"));
                    row.put("idempotency_key", rs.getString("idempotency_key"));
                    row.put("client_sequence", rs.getLong("client_sequence"));
                    row.put("command_type", rs.getString("command_type"));
                    row.put("aggregate_ref", rs.getString("aggregate_ref"));
                    row.put("payload_json", rs.getString("payload_json"));
                    row.put("monetary_total_bdt", rs.getString("monetary_total_bdt"));
                    row.put("sync_state", rs.getString("sync_state"));
                    list.add(row);
                }
            }
        }
        return list;
    }

    public static void markConflictAndQueue(
            Connection conn,
            String commandId,
            String tenantId,
            String branchId,
            String conflictReason,
            String localPayloadJson,
            String serverStateJson
    ) throws SQLException {
        boolean origAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE pending_offline_commands SET sync_state = 'CONFLICT_NEEDS_REVIEW', error_detail = ? WHERE command_id = ?")) {
                upd.setString(1, conflictReason);
                upd.setString(2, commandId);
                upd.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO sync_conflict_queue (conflict_id, command_id, tenant_id, branch_id, conflict_reason, " +
                            "local_payload_json, server_state_json, resolution_status, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)")) {
                ins.setString(1, "conf-" + UUID.randomUUID());
                ins.setString(2, commandId);
                ins.setString(3, tenantId);
                ins.setString(4, branchId);
                ins.setString(5, conflictReason);
                ins.setString(6, localPayloadJson);
                ins.setString(7, serverStateJson != null ? serverStateJson : "{}");
                ins.setString(8, Instant.now().toString());
                ins.executeUpdate();
            }
            conn.commit();
        } catch (Exception ex) {
            conn.rollback();
            if (ex instanceof SQLException) throw (SQLException) ex;
            throw new SQLException(ex);
        } finally {
            conn.setAutoCommit(origAutoCommit);
        }
    }
}
