package com.pharmacy.pos.offline;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * CanonicalAckApplier
 * Architecture Decision: ADR-0306 (P306).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Atomically reconciles server-issued canonical acknowledgments into local SQLite outbox
 * (`pending_offline_commands`), `cached_branch_stock` (releasing reserved_offline_units upon sync),
 * and `sync_conflict_queue` (non-destructively routing terminal conflicts).
 */
public class CanonicalAckApplier {

    public static void applyCanonicalAckAtomically(
            Connection conn,
            String tenantId,
            String branchId,
            String commandId,
            String disposition,
            String canonicalEntityId,
            String batchIdToReconcile,
            int syncedQtyUnits,
            String errorDetail,
            String localPayloadJson
    ) throws SQLException {
        boolean origAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            String disp = disposition != null ? disposition.toUpperCase() : "RETRYABLE_TRANSIENT";

            if ("COMMITTED".equals(disp) || "REPLAY_IDEMPOTENT".equals(disp)) {
                // Only reconcile stock once if not already marked SYNCED (idempotent local ack application!)
                boolean alreadySynced = false;
                try (PreparedStatement chk = conn.prepareStatement(
                        "SELECT sync_state FROM pending_offline_commands WHERE command_id = ?")) {
                    chk.setString(1, commandId);
                    try (ResultSet rs = chk.executeQuery()) {
                        if (rs.next() && "SYNCED".equals(rs.getString(1))) {
                            alreadySynced = true;
                        }
                    }
                }

                try (PreparedStatement upd = conn.prepareStatement(
                        "UPDATE pending_offline_commands SET sync_state = 'SYNCED', server_ack_id = ?, error_detail = '' WHERE command_id = ?")) {
                    upd.setString(1, canonicalEntityId);
                    upd.setString(2, commandId);
                    upd.executeUpdate();
                }

                if (!alreadySynced && batchIdToReconcile != null && !batchIdToReconcile.isEmpty() && syncedQtyUnits > 0) {
                    try (PreparedStatement stUpd = conn.prepareStatement(
                            "UPDATE cached_branch_stock SET quantity_base_units = MAX(0, quantity_base_units - ?), " +
                                    "reserved_offline_units = MAX(0, reserved_offline_units - ?), updated_at = ? " +
                                    "WHERE batch_id = ? AND tenant_id = ? AND branch_id = ?")) {
                        stUpd.setInt(1, syncedQtyUnits);
                        stUpd.setInt(2, syncedQtyUnits);
                        stUpd.setString(3, Instant.now().toString());
                        stUpd.setString(4, batchIdToReconcile);
                        stUpd.setString(5, tenantId);
                        stUpd.setString(6, branchId);
                        stUpd.executeUpdate();
                    }
                }
            } else if ("TERMINAL_CONFLICT".equals(disp)) {
                try (PreparedStatement upd = conn.prepareStatement(
                        "UPDATE pending_offline_commands SET sync_state = 'CONFLICT_NEEDS_REVIEW', error_detail = ? WHERE command_id = ?")) {
                    upd.setString(1, errorDetail != null ? errorDetail : "Sync conflict");
                    upd.setString(2, commandId);
                    upd.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO sync_conflict_queue (conflict_id, command_id, tenant_id, branch_id, conflict_reason, " +
                                "local_payload_json, server_state_json, resolution_status, created_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, '{}', 'OPEN', ?)")) {
                    ins.setString(1, "conf-" + UUID.randomUUID());
                    ins.setString(2, commandId);
                    ins.setString(3, tenantId);
                    ins.setString(4, branchId);
                    ins.setString(5, errorDetail != null ? errorDetail : "TERMINAL_CONFLICT");
                    ins.setString(6, localPayloadJson != null ? localPayloadJson : "{}");
                    ins.setString(7, Instant.now().toString());
                    ins.executeUpdate();
                }
            } else {
                // RETRYABLE_TRANSIENT -> increment retry_count and keep in RETRY_SCHEDULED
                try (PreparedStatement upd = conn.prepareStatement(
                        "UPDATE pending_offline_commands SET sync_state = 'RETRY_SCHEDULED', retry_count = retry_count + 1, " +
                                "error_detail = ? WHERE command_id = ?")) {
                    upd.setString(1, errorDetail != null ? errorDetail : "Transient retry");
                    upd.setString(2, commandId);
                    upd.executeUpdate();
                }
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
