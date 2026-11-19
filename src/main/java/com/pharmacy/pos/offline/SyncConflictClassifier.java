package com.pharmacy.pos.offline;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * SyncConflictClassifier
 * Architecture Decision: ADR-0313 (P313).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Client-side conflict classification engine for JavaFX Desktop POS:
 * 1. Categorizes server sync rejections and conflict acknowledgments into 6 canonical categories:
 *    - STALE_STOCK (CONFLICT_STALE_STOCK)
 *    - STALE_PRICE (CONFLICT_STALE_PRICE)
 *    - REVOKED_PERMISSION (CONFLICT_REVOKED_PERMISSION)
 *    - EXPIRED_SUBSCRIPTION (CONFLICT_EXPIRED_SUBSCRIPTION)
 *    - UNVERIFIED_PRESCRIPTION (CONFLICT_UNVERIFIED_PRESCRIPTION)
 *    - DUPLICATE_COMMAND_TAMPERED (CONFLICT_DUPLICATE_COMMAND_TAMPERED)
 * 2. Provides bilingual (EN / BN) explanations for cashiers and branch managers.
 * 3. Safely records and resolves conflict tickets in local SQLite sync_conflict_queue without
 *    silently altering or discarding finalized offline sales.
 */
public class SyncConflictClassifier {

    public enum SyncConflictCategory {
        STALE_STOCK,
        STALE_PRICE,
        REVOKED_PERMISSION,
        EXPIRED_SUBSCRIPTION,
        UNVERIFIED_PRESCRIPTION,
        DUPLICATE_COMMAND_TAMPERED
    }

    public enum SyncConflictErrorCode {
        CONFLICT_STALE_STOCK,
        CONFLICT_STALE_PRICE,
        CONFLICT_REVOKED_PERMISSION,
        CONFLICT_EXPIRED_SUBSCRIPTION,
        CONFLICT_UNVERIFIED_PRESCRIPTION,
        CONFLICT_DUPLICATE_COMMAND_TAMPERED
    }

    public static final class ConflictClassificationResult {
        public final SyncConflictCategory category;
        public final SyncConflictErrorCode errorCode;
        public final String englishDescription;
        public final String bengaliDescription;
        public final boolean isTerminalConflict;

        public ConflictClassificationResult(
                SyncConflictCategory category,
                SyncConflictErrorCode errorCode,
                String englishDescription,
                String bengaliDescription,
                boolean isTerminalConflict
        ) {
            this.category = Objects.requireNonNull(category);
            this.errorCode = Objects.requireNonNull(errorCode);
            this.englishDescription = Objects.requireNonNull(englishDescription);
            this.bengaliDescription = Objects.requireNonNull(bengaliDescription);
            this.isTerminalConflict = isTerminalConflict;
        }
    }

    public static ConflictClassificationResult classify(String rawCodeOrReason) {
        String input = (rawCodeOrReason == null) ? "" : rawCodeOrReason.trim().toUpperCase();

        if (input.contains("PRICE") || input.contains("MRP")) {
            return new ConflictClassificationResult(
                    SyncConflictCategory.STALE_PRICE,
                    SyncConflictErrorCode.CONFLICT_STALE_PRICE,
                    "Item was sold at outdated price/MRP exceeding allowable tolerance. Preserved for manager review.",
                    "অনুমোদিত সীমার চেয়ে পুরনো মূল্যে বিক্রয় হয়েছে। ম্যানেজার পর্যালোচনার জন্য সংরক্ষিত।",
                    true
            );
        } else if (input.contains("REVOKED_PERMISSION") || input.contains("CASHIER") || input.contains("STAFF_DEACTIVATED") || input.contains("UNAUTHORIZED")) {
            return new ConflictClassificationResult(
                    SyncConflictCategory.REVOKED_PERMISSION,
                    SyncConflictErrorCode.CONFLICT_REVOKED_PERMISSION,
                    "Cashier or staff credentials were revoked prior to sync. Preserved for manager review.",
                    "সিঙ্ক করার আগেই ক্যাশিয়ার বা স্টাফের অনুমতি বাতিল করা হয়েছে। ম্যানেজার পর্যালোচনার জন্য সংরক্ষিত।",
                    true
            );
        } else if (input.contains("SUBSCRIPTION") || input.contains("LEASE") || input.contains("EXPIRED_SUBSCRIPTION")) {
            return new ConflictClassificationResult(
                    SyncConflictCategory.EXPIRED_SUBSCRIPTION,
                    SyncConflictErrorCode.CONFLICT_EXPIRED_SUBSCRIPTION,
                    "Subscription tier or offline device lease expired prior to transaction. Preserved for manager review.",
                    "লেনদেনের আগেই সাবস্ক্রিপশন বা অফলাইন ডিভাইসের মেয়াদ শেষ হয়েছে। ম্যানেজার পর্যালোচনার জন্য সংরক্ষিত।",
                    true
            );
        } else if (input.contains("PRESCRIPTION") || input.contains("RX") || input.contains("UNVERIFIED_PRESCRIPTION")) {
            return new ConflictClassificationResult(
                    SyncConflictCategory.UNVERIFIED_PRESCRIPTION,
                    SyncConflictErrorCode.CONFLICT_UNVERIFIED_PRESCRIPTION,
                    "Prescription token invalid, expired, revoked, or unverified. Preserved for manager review.",
                    "প্রেসক্রিপশন টোকেন অবৈধ, মেয়াদোত্তীর্ণ বা অসত্যায়িত। ম্যানেজার পর্যালোচনার জন্য সংরক্ষিত।",
                    true
            );
        } else if (input.contains("IDEMPOTENCY") || input.contains("TAMPER") || input.contains("SEQUENCE_CONFLICT") || input.contains("DUPLICATE_COMMAND")) {
            return new ConflictClassificationResult(
                    SyncConflictCategory.DUPLICATE_COMMAND_TAMPERED,
                    SyncConflictErrorCode.CONFLICT_DUPLICATE_COMMAND_TAMPERED,
                    "Duplicate command payload mismatch or client sequence collision. Preserved for manager review.",
                    "ডুপ্লিকেট কমান্ডের তথ্যে অমিল বা সিকোয়েন্স সংঘর্ষ। ম্যানেজার পর্যালোচনার জন্য সংরক্ষিত।",
                    true
            );
        } else {
            // Default: Stale Stock / Two-device stock conflict
            return new ConflictClassificationResult(
                    SyncConflictCategory.STALE_STOCK,
                    SyncConflictErrorCode.CONFLICT_STALE_STOCK,
                    "Stock is depleted, recalled, or insufficient on the cloud server. Preserved for manager review.",
                    "ক্লাউড সার্ভারে স্টক শেষ, প্রত্যাহারকৃত বা অপর্যাপ্ত। ম্যানেজার পর্যালোচনার জন্য সংরক্ষিত।",
                    true
            );
        }
    }

    public static void recordConflictTicket(
            Connection conn,
            String conflictId,
            String commandId,
            String tenantId,
            String branchId,
            String conflictReason,
            String localPayloadJson,
            String serverStateJson
    ) throws SQLException {
        conn.setAutoCommit(false);
        try {
            String insertSql = "INSERT OR REPLACE INTO sync_conflict_queue (" +
                    "conflict_id, command_id, tenant_id, branch_id, conflict_reason, " +
                    "local_payload_json, server_state_json, resolution_status, created_at" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)";

            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, conflictId);
                ps.setString(2, commandId);
                ps.setString(3, tenantId);
                ps.setString(4, branchId);
                ps.setString(5, conflictReason);
                ps.setString(6, localPayloadJson != null ? localPayloadJson : "{}");
                ps.setString(7, serverStateJson != null ? serverStateJson : "{}");
                ps.setString(8, Instant.now().toString());
                ps.executeUpdate();
            }

            String updateOutboxSql = "UPDATE pending_offline_commands SET sync_state = 'CONFLICT_NEEDS_REVIEW', " +
                    "error_detail = ? WHERE command_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateOutboxSql)) {
                ps.setString(1, conflictReason);
                ps.setString(2, commandId);
                ps.executeUpdate();
            }

            conn.commit();
        } catch (SQLException ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public static boolean resolveConflictInSqlite(
            Connection conn,
            String conflictId,
            String resolutionStatus,
            String resolvedBy
    ) throws SQLException {
        String sql = "UPDATE sync_conflict_queue SET resolution_status = ?, resolved_by = ?, " +
                "resolved_at = ? WHERE conflict_id = ? AND resolution_status = 'OPEN'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, resolutionStatus);
            ps.setString(2, resolvedBy);
            ps.setString(3, Instant.now().toString());
            ps.setString(4, conflictId);
            int rows = ps.executeUpdate();
            return rows > 0;
        }
    }

    public static int countOpenConflicts(Connection conn, String tenantId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM sync_conflict_queue WHERE tenant_id = ? AND resolution_status = 'OPEN'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }
}
