package com.pharmacy.pos.offline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * ChangeFeedPullClient
 * Architecture Decision: ADR-0307 (P307).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Atomically applies incremental cloud change-feed events (UPSERT, TOMBSTONE, REVERSAL)
 * into the local SQLite POS cache while:
 * 1. Enforcing strict tenant & branch ownership before mutating any local row.
 * 2. Preserving local unsynced `reserved_offline_units` during server stock upserts (zero local data loss).
 * 3. Routing a non-destructive conflict ticket into `sync_conflict_queue` whenever a server stock update
 *    drops below locally reserved offline units (two-device stock conflict drill).
 * 4. Advancing `device_metadata.last_sync_cursor` ONLY in the same atomic SQLite transaction so a mid-batch
 *    crash rolls back completely and allows clean idempotent resumption from the prior cursor.
 */
public class ChangeFeedPullClient {

    public static final class ChangeFeedEventRecord {
        public final long sequenceNo;
        public final String tenantId;
        public final String branchId; // null for tenant-wide events
        public final String entityType;
        public final String entityId;
        public final String operation; // UPSERT, TOMBSTONE, REVERSAL
        public final int entityVersion;
        public final Map<String, String> fields;

        public ChangeFeedEventRecord(
                long sequenceNo,
                String tenantId,
                String branchId,
                String entityType,
                String entityId,
                String operation,
                int entityVersion,
                Map<String, String> fields
        ) {
            this.sequenceNo = sequenceNo;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.entityType = entityType;
            this.entityId = entityId;
            this.operation = operation;
            this.entityVersion = entityVersion;
            this.fields = fields != null ? fields : Collections.emptyMap();
        }
    }

    public static final class ApplyResult {
        public final int appliedCount;
        public final int skippedStaleCount;
        public final int tombstoneCount;
        public final int conflictCount;
        public final long committedCursorSequence;

        public ApplyResult(int appliedCount, int skippedStaleCount, int tombstoneCount, int conflictCount, long committedCursorSequence) {
            this.appliedCount = appliedCount;
            this.skippedStaleCount = skippedStaleCount;
            this.tombstoneCount = tombstoneCount;
            this.conflictCount = conflictCount;
            this.committedCursorSequence = committedCursorSequence;
        }
    }

    public static long readLocalCursorSequence(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT last_sync_cursor FROM device_metadata WHERE singleton_id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return 0L;
            }
            String raw = rs.getString(1);
            if (raw == null || raw.isBlank()) {
                return 0L;
            }
            return Long.parseLong(raw.trim());
        }
    }

    public static ApplyResult applyChangeFeedBatchAtomically(
            Connection conn,
            String localTenantId,
            String localBranchId,
            List<ChangeFeedEventRecord> events,
            int failAtIndexForCrashTest
    ) throws SQLException {
        boolean origAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            long currentCursor = readLocalCursorSequence(conn);
            long highestSeq = currentCursor;
            int applied = 0;
            int skippedStale = 0;
            int tombstones = 0;
            int conflicts = 0;

            for (int i = 0; i < events.size(); i++) {
                if (failAtIndexForCrashTest >= 0 && i == failAtIndexForCrashTest) {
                    throw new SQLException("SIMULATED_POWER_LOSS_MID_BATCH");
                }
                ChangeFeedEventRecord ev = events.get(i);

                // Strict tenant and branch isolation guardrail
                if (!Objects.equals(localTenantId, ev.tenantId)) {
                    throw new SecurityException("Cross-tenant change feed event rejected: " + ev.tenantId);
                }
                if (ev.branchId != null && !ev.branchId.isBlank() && !Objects.equals(localBranchId, ev.branchId)) {
                    throw new SecurityException("Cross-branch change feed event rejected: " + ev.branchId);
                }

                // Replay protection: skip already-applied sequence numbers
                if (ev.sequenceNo <= currentCursor) {
                    skippedStale++;
                    continue;
                }

                String op = ev.operation != null ? ev.operation.toUpperCase(Locale.ROOT) : "UPSERT";
                String type = ev.entityType != null ? ev.entityType.toUpperCase(Locale.ROOT) : "";

                if ("MEDICINE_CATALOG".equals(type)) {
                    if ("TOMBSTONE".equals(op)) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "UPDATE cached_catalog_medicines SET is_active = 0, server_version = ?, updated_at = ? " +
                                        "WHERE product_id = ? AND tenant_id = ? AND server_version <= ?")) {
                            ps.setInt(1, ev.entityVersion);
                            ps.setString(2, Instant.now().toString());
                            ps.setString(3, ev.entityId);
                            ps.setString(4, localTenantId);
                            ps.setInt(5, ev.entityVersion);
                            ps.executeUpdate();
                        }
                        tombstones++;
                        applied++;
                    } else {
                        String mrp = new BigDecimal(ev.fields.getOrDefault("mrp_bdt", "0.00"))
                                .setScale(2, RoundingMode.HALF_UP).toPlainString();
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO cached_catalog_medicines " +
                                        "(product_id, tenant_id, brand_name, generic_name, dosage_form, mrp_bdt, " +
                                        "rx_required, is_antimicrobial, is_active, server_version, updated_at) " +
                                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?) " +
                                        "ON CONFLICT(product_id) DO UPDATE SET " +
                                        "brand_name = excluded.brand_name, generic_name = excluded.generic_name, " +
                                        "mrp_bdt = excluded.mrp_bdt, is_active = 1, " +
                                        "server_version = excluded.server_version, updated_at = excluded.updated_at " +
                                        "WHERE excluded.server_version >= cached_catalog_medicines.server_version")) {
                            ps.setString(1, ev.entityId);
                            ps.setString(2, localTenantId);
                            ps.setString(3, ev.fields.getOrDefault("brand_name", "Medicine"));
                            ps.setString(4, ev.fields.getOrDefault("generic_name", "Generic"));
                            ps.setString(5, ev.fields.getOrDefault("dosage_form", "TABLET"));
                            ps.setString(6, mrp);
                            ps.setInt(7, Integer.parseInt(ev.fields.getOrDefault("rx_required", "0")));
                            ps.setInt(8, Integer.parseInt(ev.fields.getOrDefault("is_antimicrobial", "0")));
                            ps.setInt(9, ev.entityVersion);
                            ps.setString(10, Instant.now().toString());
                            ps.executeUpdate();
                        }
                        applied++;
                    }
                } else if ("BATCH_STOCK".equals(type)) {
                    if ("TOMBSTONE".equals(op)) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "UPDATE cached_branch_stock SET status = 'TOMBSTONED', quantity_base_units = 0, " +
                                        "server_version = ?, updated_at = ? " +
                                        "WHERE batch_id = ? AND tenant_id = ? AND branch_id = ?")) {
                            ps.setInt(1, ev.entityVersion);
                            ps.setString(2, Instant.now().toString());
                            ps.setString(3, ev.entityId);
                            ps.setString(4, localTenantId);
                            ps.setString(5, localBranchId);
                            ps.executeUpdate();
                        }
                        tombstones++;
                        applied++;
                    } else {
                        int serverQty = Integer.parseInt(ev.fields.getOrDefault("quantity_base_units", "0"));
                        int localReserved = 0;
                        try (PreparedStatement chk = conn.prepareStatement(
                                "SELECT reserved_offline_units FROM cached_branch_stock WHERE batch_id = ? AND tenant_id = ? AND branch_id = ?")) {
                            chk.setString(1, ev.entityId);
                            chk.setString(2, localTenantId);
                            chk.setString(3, localBranchId);
                            try (ResultSet rs = chk.executeQuery()) {
                                if (rs.next()) {
                                    localReserved = rs.getInt(1);
                                }
                            }
                        }

                        // Preserve local reserved_offline_units; flag conflict if server qty dropped below local offline reservation
                        if (localReserved > 0 && serverQty < localReserved) {
                            String pendingCmdId = null;
                            try (PreparedStatement fCmd = conn.prepareStatement(
                                    "SELECT command_id FROM pending_offline_commands " +
                                            "WHERE tenant_id = ? AND branch_id = ? AND sync_state != 'SYNCED' " +
                                            "ORDER BY client_sequence ASC LIMIT 1")) {
                                fCmd.setString(1, localTenantId);
                                fCmd.setString(2, localBranchId);
                                try (ResultSet rs = fCmd.executeQuery()) {
                                    if (rs.next()) {
                                        pendingCmdId = rs.getString(1);
                                    }
                                }
                            }
                            if (pendingCmdId != null) {
                                try (PreparedStatement cIns = conn.prepareStatement(
                                        "INSERT INTO sync_conflict_queue (conflict_id, command_id, tenant_id, branch_id, " +
                                                "conflict_reason, local_payload_json, server_state_json, resolution_status, created_at) " +
                                                "VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)")) {
                                    cIns.setString(1, "cfconf-" + UUID.randomUUID());
                                    cIns.setString(2, pendingCmdId);
                                    cIns.setString(3, localTenantId);
                                    cIns.setString(4, localBranchId);
                                    cIns.setString(5, "TWO_DEVICE_STOCK_UNDERRUN");
                                    cIns.setString(6, "{\"batch_id\":\"" + ev.entityId + "\",\"reserved_offline_units\":" + localReserved + "}");
                                    cIns.setString(7, "{\"server_quantity_base_units\":" + serverQty + "}");
                                    cIns.setString(8, Instant.now().toString());
                                    cIns.executeUpdate();
                                }
                            }
                            conflicts++;
                        }

                        String costStr = new BigDecimal(ev.fields.getOrDefault("cost_per_unit_bdt", "1.00"))
                                .setScale(2, RoundingMode.HALF_UP).toPlainString();
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO cached_branch_stock " +
                                        "(batch_id, tenant_id, branch_id, product_id, batch_number, expiry_date, " +
                                        "quantity_base_units, reserved_offline_units, cost_per_unit_bdt, status, server_version, updated_at) " +
                                        "VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, 'ACTIVE', ?, ?) " +
                                        "ON CONFLICT(batch_id) DO UPDATE SET " +
                                        "quantity_base_units = excluded.quantity_base_units, " +
                                        "cost_per_unit_bdt = excluded.cost_per_unit_bdt, " +
                                        "server_version = excluded.server_version, updated_at = excluded.updated_at " +
                                        "WHERE excluded.server_version >= cached_branch_stock.server_version")) {
                            ps.setString(1, ev.entityId);
                            ps.setString(2, localTenantId);
                            ps.setString(3, localBranchId);
                            ps.setString(4, ev.fields.getOrDefault("product_id", "med-unknown"));
                            ps.setString(5, ev.fields.getOrDefault("batch_number", "B-01"));
                            ps.setString(6, ev.fields.getOrDefault("expiry_date", "2028-12-31"));
                            ps.setInt(7, Math.max(0, serverQty));
                            ps.setString(8, costStr);
                            ps.setInt(9, ev.entityVersion);
                            ps.setString(10, Instant.now().toString());
                            ps.executeUpdate();
                        }
                        applied++;
                    }
                } else {
                    // Audited REVERSAL or auxiliary entity events still advance watermark cleanly
                    applied++;
                }

                if (ev.sequenceNo > highestSeq) {
                    highestSeq = ev.sequenceNo;
                }
            }

            // Atomically commit new cursor watermark in the exact same SQLite transaction
            try (PreparedStatement cUpd = conn.prepareStatement(
                    "UPDATE device_metadata SET last_sync_cursor = ?, updated_at = ? WHERE singleton_id = 1")) {
                cUpd.setString(1, String.valueOf(highestSeq));
                cUpd.setString(2, Instant.now().toString());
                cUpd.executeUpdate();
            }

            conn.commit();
            return new ApplyResult(applied, skippedStale, tombstones, conflicts, highestSeq);
        } catch (Exception ex) {
            conn.rollback();
            if (ex instanceof SQLException) throw (SQLException) ex;
            if (ex instanceof RuntimeException) throw (RuntimeException) ex;
            throw new SQLException(ex);
        } finally {
            conn.setAutoCommit(origAutoCommit);
        }
    }
}
