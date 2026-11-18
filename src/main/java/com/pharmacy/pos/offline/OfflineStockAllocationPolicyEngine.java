package com.pharmacy.pos.offline;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * OfflineStockAllocationPolicyEngine
 * Architecture Decision: ADR-0310 (P310).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Enforces branch offline stock reservation policies in local SQLite POS storage:
 * - Unconditionally blocks dispensing expired, quarantined, or tombstoned batches offline.
 * - Enforces configurable `maxOfflineReservePct` cap under `STRICT_LOCAL_ONLY` or flags honest
 *   cross-device oversell warnings under `SOFT_RESERVE_FLAG_OVERSELL` / `CONTROLLED_RECONCILIATION`.
 * - Routes oversell disputes non-destructively to `sync_conflict_queue` without silently altering
 *   finalized local offline invoices.
 */
public class OfflineStockAllocationPolicyEngine {

    public enum PolicyMode {
        STRICT_LOCAL_ONLY,
        SOFT_RESERVE_FLAG_OVERSELL,
        CONTROLLED_RECONCILIATION
    }

    public static final class AllocationDecision {
        public final boolean allowed;
        public final boolean oversellWarningFlagged;
        public final String commandId;
        public final String reasonCode;
        public final String certaintyLabelEn;
        public final String certaintyLabelBn;

        public AllocationDecision(
                boolean allowed,
                boolean oversellWarningFlagged,
                String commandId,
                String reasonCode,
                String certaintyLabelEn,
                String certaintyLabelBn
        ) {
            this.allowed = allowed;
            this.oversellWarningFlagged = oversellWarningFlagged;
            this.commandId = commandId;
            this.reasonCode = reasonCode;
            this.certaintyLabelEn = certaintyLabelEn;
            this.certaintyLabelBn = certaintyLabelBn;
        }
    }

    public static AllocationDecision reserveOfflineBatch(
            Connection conn,
            String deviceId,
            String tenantId,
            String branchId,
            String batchId,
            int requestedUnits,
            BigDecimal monetaryTotalBdt,
            String idempotencyKey,
            String aggregateRef,
            PolicyMode policyMode,
            int maxOfflineReservePct,
            LocalDate businessDate
    ) throws SQLException {
        if (requestedUnits <= 0) {
            throw new IllegalArgumentException("requestedUnits must be > 0");
        }
        int pct = Math.max(1, Math.min(100, maxOfflineReservePct));

        int cachedQty;
        int alreadyReserved;
        String expiryStr;
        String batchStatus;

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT quantity_base_units, reserved_offline_units, expiry_date, status " +
                        "FROM cached_branch_stock WHERE batch_id = ? AND tenant_id = ? AND branch_id = ?")) {
            ps.setString(1, batchId);
            ps.setString(2, tenantId);
            ps.setString(3, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new AllocationDecision(
                            false, false, "", "ERR_BATCH_NOT_IN_LOCAL_CACHE",
                            "Batch not available in local offline cache",
                            "ব্যাচটি লোকাল অফলাইন ক্যাশে পাওয়া যায়নি"
                    );
                }
                cachedQty = rs.getInt(1);
                alreadyReserved = rs.getInt(2);
                expiryStr = rs.getString(3);
                batchStatus = rs.getString(4);
            }
        }

        // Hard pharmacy safety rule: NEVER sell expired, quarantined, or tombstoned stock offline!
        LocalDate expiryDate = LocalDate.parse(expiryStr);
        if (!"ACTIVE".equalsIgnoreCase(batchStatus) || !expiryDate.isAfter(businessDate)) {
            return new AllocationDecision(
                    false, false, "", "ERR_BATCH_EXPIRED_OR_QUARANTINED",
                    "Blocked: Expired, quarantined, or recalled batch",
                    "ব্লকড: মেয়াদোত্তীর্ণ বা কোয়ারেন্টাইনকৃত ব্যাচ"
            );
        }

        int capUnits = (int) Math.floor((cachedQty * (double) pct) / 100.0);
        int projectedReserved = alreadyReserved + requestedUnits;
        int netAvailable = cachedQty - alreadyReserved;

        if (policyMode == PolicyMode.STRICT_LOCAL_ONLY && (projectedReserved > capUnits || requestedUnits > netAvailable)) {
            return new AllocationDecision(
                    false, true, "", "ERR_STRICT_OFFLINE_CAP_EXCEEDED",
                    "Blocked by Strict Local Offline Cap (" + pct + "% max)",
                    "স্ট্রিক্ট অফলাইন লিমিট (" + pct + "%) অতিক্রম করায় ব্লকড"
            );
        }

        if (requestedUnits > netAvailable) {
            return new AllocationDecision(
                    false, true, "", "ERR_INSUFFICIENT_LOCAL_CACHE",
                    "Insufficient local cached stock — reconcile online",
                    "লোকাল ক্যাশে পর্যাপ্ত স্টক নেই — অনলাইনে সিঙ্ক করুন"
            );
        }

        boolean flagWarning = projectedReserved > capUnits;
        String cmdId = DeviceLocalDatabaseSchema.enqueueOfflineSaleAtomically(
                conn, deviceId, tenantId, branchId, batchId, requestedUnits,
                monetaryTotalBdt, idempotencyKey, aggregateRef,
                "{\"batch_id\":\"" + batchId + "\",\"qty\":" + requestedUnits + ",\"oversell_warning\":" + flagWarning + "}"
        );

        return new AllocationDecision(
                true,
                flagWarning,
                cmdId,
                flagWarning ? "WARN_SOFT_RESERVE_CAP_EXCEEDED" : "OK_LOCAL_RESERVED",
                "Local Estimate — Pending Cloud Reconciliation",
                "লোকাল আনুমানিক স্টক — ক্লাউড সমন্বয়ের অপেক্ষায়"
        );
    }

    /**
     * Records a server-reported cross-device oversell dispute into `sync_conflict_queue`
     * without silently deleting or altering the finalized local offline invoice record.
     */
    public static String recordNonDestructiveOversellDispute(
            Connection conn,
            String tenantId,
            String branchId,
            String commandId,
            String batchId,
            int availableAtServer,
            int requestedUnits,
            BigDecimal disputedCostBdt
    ) throws SQLException {
        String conflictId = "osconf-" + UUID.randomUUID();
        boolean origAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE pending_offline_commands SET sync_state = 'CONFLICT_NEEDS_REVIEW', " +
                            "error_detail = 'CROSS_DEVICE_OVERSELL_DISPUTE' WHERE command_id = ?")) {
                upd.setString(1, commandId);
                upd.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO sync_conflict_queue (conflict_id, command_id, tenant_id, branch_id, " +
                            "conflict_reason, local_payload_json, server_state_json, resolution_status, created_at) " +
                            "VALUES (?, ?, ?, ?, 'CROSS_DEVICE_OVERSELL_DISPUTE', ?, ?, 'OPEN', ?)")) {
                ins.setString(1, conflictId);
                ins.setString(2, commandId);
                ins.setString(3, tenantId);
                ins.setString(4, branchId);
                ins.setString(5, "{\"batch_id\":\"" + batchId + "\",\"requested_units\":" + requestedUnits + ",\"disputed_cost_bdt\":\"" + disputedCostBdt.toPlainString() + "\"}");
                ins.setString(6, "{\"available_units_at_reconcile\":" + availableAtServer + ",\"posted_records_preserved\":true}");
                ins.setString(7, Instant.now().toString());
                ins.executeUpdate();
            }
            conn.commit();
            return conflictId;
        } catch (Exception ex) {
            conn.rollback();
            if (ex instanceof SQLException) throw (SQLException) ex;
            throw new SQLException(ex);
        } finally {
            conn.setAutoCommit(origAutoCommit);
        }
    }
}
