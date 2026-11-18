package com.pharmacy.pos.offline;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * OfflineCacheSeeder
 * Architecture Decision: ADR-0303 (P303).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Applies a cloud-issued versioned bootstrap snapshot atomically into the local SQLite database
 * created by DeviceLocalDatabaseSchema (P301). Enforces strict tenant/branch scope checks on
 * every row before commit and rolls back completely if any row violates tenant/branch bounds.
 */
public class OfflineCacheSeeder {

    public static int applySeedAtomically(
            Connection conn,
            String expectedTenantId,
            String expectedBranchId,
            int snapshotVersion,
            String consistencyToken,
            List<Map<String, Object>> catalogRows,
            List<Map<String, Object>> stockRows
    ) throws SQLException {
        if (consistencyToken == null || consistencyToken.length() != 64) {
            throw new IllegalArgumentException("ERR_INVALID_CONSISTENCY_TOKEN: 64-char SHA-256 token required.");
        }
        boolean origAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        String now = Instant.now().toString();
        int appliedCount = 0;

        try {
            // 1. Upsert catalog medicines
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO cached_catalog_medicines " +
                            "(product_id, tenant_id, brand_name, generic_name, dosage_form, mrp_bdt, rx_required, is_antimicrobial, is_active, server_version, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)")) {
                for (Map<String, Object> m : catalogRows) {
                    if (!expectedTenantId.equals(String.valueOf(m.get("tenant_id")))) {
                        throw new SecurityException("ERR_CROSS_TENANT_SEED_ROW: Foreign tenant catalog row rejected.");
                    }
                    ps.setString(1, String.valueOf(m.get("product_id")));
                    ps.setString(2, expectedTenantId);
                    ps.setString(3, String.valueOf(m.get("brand_name")));
                    ps.setString(4, String.valueOf(m.get("generic_name")));
                    ps.setString(5, String.valueOf(m.getOrDefault("dosage_form", "TABLET")));
                    ps.setString(6, String.valueOf(m.getOrDefault("mrp_bdt", "0.00")));
                    ps.setInt(7, Boolean.TRUE.equals(m.get("rx_required")) ? 1 : 0);
                    ps.setInt(8, Boolean.TRUE.equals(m.get("is_antimicrobial")) ? 1 : 0);
                    ps.setInt(9, snapshotVersion);
                    ps.setString(10, now);
                    ps.executeUpdate();
                    appliedCount++;
                }
            }

            // 2. Upsert branch-scoped stock (strictly matching expectedTenantId & expectedBranchId)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO cached_branch_stock " +
                            "(batch_id, tenant_id, branch_id, product_id, batch_number, expiry_date, quantity_base_units, reserved_offline_units, cost_per_unit_bdt, status, server_version, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)")) {
                for (Map<String, Object> b : stockRows) {
                    if (!expectedTenantId.equals(String.valueOf(b.get("tenant_id")))
                            || !expectedBranchId.equals(String.valueOf(b.get("branch_id")))) {
                        throw new SecurityException("ERR_CROSS_BRANCH_OR_TENANT_SEED_ROW: Unauthorized stock row rejected.");
                    }
                    ps.setString(1, String.valueOf(b.get("batch_id")));
                    ps.setString(2, expectedTenantId);
                    ps.setString(3, expectedBranchId);
                    ps.setString(4, String.valueOf(b.get("product_id")));
                    ps.setString(5, String.valueOf(b.get("batch_number")));
                    ps.setString(6, String.valueOf(b.get("expiry_date")));
                    ps.setInt(7, ((Number) b.get("quantity_base_units")).intValue());
                    ps.setString(8, String.valueOf(b.getOrDefault("cost_per_unit_bdt", "0.00")));
                    ps.setString(9, String.valueOf(b.getOrDefault("status", "ACTIVE")));
                    ps.setInt(10, snapshotVersion);
                    ps.setString(11, now);
                    ps.executeUpdate();
                    appliedCount++;
                }
            }

            // 3. Advance device_metadata sync cursor atomically
            String cursor = "seed-v" + snapshotVersion + ":" + consistencyToken.substring(0, 16);
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE device_metadata SET last_sync_cursor = ?, updated_at = ? WHERE singleton_id = 1")) {
                upd.setString(1, cursor);
                upd.setString(2, now);
                upd.executeUpdate();
            }

            conn.commit();
            return appliedCount;
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
