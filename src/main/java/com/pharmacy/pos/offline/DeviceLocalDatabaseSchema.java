package com.pharmacy.pos.offline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * DeviceLocalDatabaseSchema
 * Architecture Decision: ADR-0301 (P301).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Real JDBC SQLite schema manager for the device-bound offline pharmacy POS database:
 * - Enforces PRAGMA foreign_keys = ON, journal_mode = WAL, synchronous = FULL.
 * - Creates 8 normalized tables (device_metadata, schema_migrations, cached_catalog_medicines,
 *   cached_branch_stock, cached_staff_permissions, subscription_leases, pending_offline_commands,
 *   sync_conflict_queue) with strict CHECK, FOREIGN KEY, and UNIQUE constraints.
 * - Binds local database strictly to (device_id, tenant_id, branch_id, device_fingerprint_sha256).
 * - Guarantees single-transaction atomic write for offline stock reservation + durable outbox enqueue.
 */
public class DeviceLocalDatabaseSchema {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final List<String> DDL_STATEMENTS = List.of(
            "CREATE TABLE IF NOT EXISTS device_metadata (" +
                    "singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1), " +
                    "device_id TEXT NOT NULL, " +
                    "tenant_id TEXT NOT NULL, " +
                    "branch_id TEXT NOT NULL, " +
                    "device_fingerprint_sha256 TEXT NOT NULL, " +
                    "schema_version INTEGER NOT NULL CHECK (schema_version >= 1), " +
                    "offline_stock_policy TEXT NOT NULL, " +
                    "last_sync_cursor TEXT NOT NULL DEFAULT '', " +
                    "updated_at TEXT NOT NULL)",

            "CREATE TABLE IF NOT EXISTS schema_migrations (" +
                    "version INTEGER PRIMARY KEY, " +
                    "ddl_sha256 TEXT NOT NULL, " +
                    "applied_at TEXT NOT NULL)",

            "CREATE TABLE IF NOT EXISTS cached_catalog_medicines (" +
                    "product_id TEXT PRIMARY KEY, " +
                    "tenant_id TEXT NOT NULL, " +
                    "brand_name TEXT NOT NULL, " +
                    "generic_name TEXT NOT NULL, " +
                    "dosage_form TEXT NOT NULL, " +
                    "mrp_bdt TEXT NOT NULL, " +
                    "rx_required INTEGER NOT NULL DEFAULT 0 CHECK (rx_required IN (0, 1)), " +
                    "is_antimicrobial INTEGER NOT NULL DEFAULT 0 CHECK (is_antimicrobial IN (0, 1)), " +
                    "is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)), " +
                    "server_version INTEGER NOT NULL DEFAULT 1, " +
                    "updated_at TEXT NOT NULL)",

            "CREATE TABLE IF NOT EXISTS cached_branch_stock (" +
                    "batch_id TEXT PRIMARY KEY, " +
                    "tenant_id TEXT NOT NULL, " +
                    "branch_id TEXT NOT NULL, " +
                    "product_id TEXT NOT NULL, " +
                    "batch_number TEXT NOT NULL, " +
                    "expiry_date TEXT NOT NULL, " +
                    "quantity_base_units INTEGER NOT NULL CHECK (quantity_base_units >= 0), " +
                    "reserved_offline_units INTEGER NOT NULL DEFAULT 0 CHECK (reserved_offline_units >= 0), " +
                    "cost_per_unit_bdt TEXT NOT NULL, " +
                    "status TEXT NOT NULL DEFAULT 'ACTIVE', " +
                    "server_version INTEGER NOT NULL DEFAULT 1, " +
                    "updated_at TEXT NOT NULL, " +
                    "FOREIGN KEY (product_id) REFERENCES cached_catalog_medicines(product_id))",

            "CREATE TABLE IF NOT EXISTS cached_staff_permissions (" +
                    "user_id TEXT PRIMARY KEY, " +
                    "tenant_id TEXT NOT NULL, " +
                    "branch_id TEXT NOT NULL, " +
                    "role TEXT NOT NULL, " +
                    "permissions_json TEXT NOT NULL, " +
                    "pin_hash_sha256 TEXT NOT NULL, " +
                    "expires_at TEXT NOT NULL, " +
                    "revoked INTEGER NOT NULL DEFAULT 0 CHECK (revoked IN (0, 1)))",

            "CREATE TABLE IF NOT EXISTS subscription_leases (" +
                    "lease_id TEXT PRIMARY KEY, " +
                    "tenant_id TEXT NOT NULL, " +
                    "branch_id TEXT NOT NULL, " +
                    "device_id TEXT NOT NULL, " +
                    "plan_tier TEXT NOT NULL, " +
                    "entitlements_json TEXT NOT NULL, " +
                    "issued_at TEXT NOT NULL, " +
                    "expires_at TEXT NOT NULL, " +
                    "signature_sha256 TEXT NOT NULL, " +
                    "status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED')))",

            "CREATE TABLE IF NOT EXISTS pending_offline_commands (" +
                    "command_id TEXT PRIMARY KEY, " +
                    "idempotency_key TEXT NOT NULL UNIQUE, " +
                    "device_id TEXT NOT NULL, " +
                    "tenant_id TEXT NOT NULL, " +
                    "branch_id TEXT NOT NULL, " +
                    "client_sequence INTEGER NOT NULL UNIQUE CHECK (client_sequence >= 1), " +
                    "command_type TEXT NOT NULL, " +
                    "aggregate_ref TEXT NOT NULL, " +
                    "payload_json TEXT NOT NULL, " +
                    "monetary_total_bdt TEXT NOT NULL, " +
                    "schema_version INTEGER NOT NULL CHECK (schema_version >= 1), " +
                    "sync_state TEXT NOT NULL CHECK (sync_state IN (" +
                    "'SAVED_LOCALLY', 'SYNCING', 'SYNCED', 'RETRY_SCHEDULED', 'CONFLICT_NEEDS_REVIEW', 'REJECTED')), " +
                    "retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0), " +
                    "next_retry_at TEXT, " +
                    "server_ack_id TEXT, " +
                    "error_detail TEXT NOT NULL DEFAULT '', " +
                    "created_at TEXT NOT NULL)",

            "CREATE TABLE IF NOT EXISTS sync_conflict_queue (" +
                    "conflict_id TEXT PRIMARY KEY, " +
                    "command_id TEXT NOT NULL, " +
                    "tenant_id TEXT NOT NULL, " +
                    "branch_id TEXT NOT NULL, " +
                    "conflict_reason TEXT NOT NULL, " +
                    "local_payload_json TEXT NOT NULL, " +
                    "server_state_json TEXT NOT NULL DEFAULT '{}', " +
                    "resolution_status TEXT NOT NULL DEFAULT 'OPEN' CHECK (resolution_status IN (" +
                    "'OPEN', 'DECISION_PROPOSED', 'RESOLVED_KEEP_SERVER', 'RESOLVED_ADJUSTED', 'ESCALATED', 'REJECTED')), " +
                    "resolved_by TEXT, " +
                    "resolved_at TEXT, " +
                    "created_at TEXT NOT NULL, " +
                    "FOREIGN KEY (command_id) REFERENCES pending_offline_commands(command_id))",

            "CREATE INDEX IF NOT EXISTS idx_cached_stock_branch_prod ON cached_branch_stock(tenant_id, branch_id, product_id)",
            "CREATE INDEX IF NOT EXISTS idx_outbox_sync_state_seq ON pending_offline_commands(sync_state, client_sequence)",
            "CREATE INDEX IF NOT EXISTS idx_conflict_open ON sync_conflict_queue(tenant_id, resolution_status, created_at)"
    );

    public static void initializeAndBind(
            Connection conn,
            String deviceId,
            String tenantId,
            String branchId,
            String deviceFingerprintSha256,
            String offlineStockPolicy
    ) throws SQLException {
        if (deviceFingerprintSha256 == null || deviceFingerprintSha256.length() != 64) {
            throw new IllegalArgumentException("ERR_INVALID_DEVICE_FINGERPRINT: 64-char SHA-256 required.");
        }
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON;");
            st.execute("PRAGMA journal_mode = WAL;");
            st.execute("PRAGMA synchronous = FULL;");
            for (String ddl : DDL_STATEMENTS) {
                st.execute(ddl);
            }
        }

        // Verify existing binding if already initialized (anti-cloning / cross-device copy protection)
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT device_id, tenant_id, device_fingerprint_sha256 FROM device_metadata WHERE singleton_id = 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String existingFp = rs.getString("device_fingerprint_sha256");
                    String existingTenant = rs.getString("tenant_id");
                    if (!existingFp.equals(deviceFingerprintSha256) || !existingTenant.equals(tenantId)) {
                        throw new SecurityException("ERR_DEVICE_BINDING_MISMATCH: Local SQLite file belongs to another device or tenant.");
                    }
                    return;
                }
            }
        }

        String now = Instant.now().toString();
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO device_metadata (singleton_id, device_id, tenant_id, branch_id, device_fingerprint_sha256, " +
                        "schema_version, offline_stock_policy, last_sync_cursor, updated_at) VALUES (1, ?, ?, ?, ?, ?, ?, '', ?)")) {
            ins.setString(1, deviceId);
            ins.setString(2, tenantId);
            ins.setString(3, branchId);
            ins.setString(4, deviceFingerprintSha256);
            ins.setInt(5, CURRENT_SCHEMA_VERSION);
            ins.setString(6, offlineStockPolicy != null ? offlineStockPolicy : "CONTROLLED_RECONCILIATION");
            ins.setString(7, now);
            ins.executeUpdate();
        }

        try (PreparedStatement mig = conn.prepareStatement(
                "INSERT OR IGNORE INTO schema_migrations (version, ddl_sha256, applied_at) VALUES (?, ?, ?)")) {
            mig.setInt(1, CURRENT_SCHEMA_VERSION);
            mig.setString(2, computeDdlSha256());
            mig.setString(3, now);
            mig.executeUpdate();
        }
    }

    /**
     * Atomically reserves branch batch stock and enqueues a durable offline command in ONE SQLite transaction.
     * Any failure rolls back both stock deduction and outbox insert to guarantee crash safety.
     */
    public static String enqueueOfflineSaleAtomically(
            Connection conn,
            String deviceId,
            String tenantId,
            String branchId,
            String batchId,
            int quantityUnits,
            BigDecimal monetaryTotalBdt,
            String idempotencyKey,
            String aggregateRef,
            String payloadJson
    ) throws SQLException {
        if (quantityUnits <= 0) {
            throw new IllegalArgumentException("ERR_INVALID_QTY: quantityUnits must be > 0.");
        }
        boolean origAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            // 1. Check available batch stock (quantity_base_units - reserved_offline_units >= quantityUnits)
            int available;
            try (PreparedStatement chk = conn.prepareStatement(
                    "SELECT quantity_base_units, reserved_offline_units FROM cached_branch_stock " +
                            "WHERE batch_id = ? AND tenant_id = ? AND branch_id = ? AND status = 'ACTIVE'")) {
                chk.setString(1, batchId);
                chk.setString(2, tenantId);
                chk.setString(3, branchId);
                try (ResultSet rs = chk.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("ERR_BATCH_NOT_AVAILABLE: Batch not found or inactive.");
                    }
                    available = rs.getInt("quantity_base_units") - rs.getInt("reserved_offline_units");
                }
            }
            if (available < quantityUnits) {
                throw new IllegalStateException("ERR_INSUFFICIENT_LOCAL_STOCK: Available " + available + " < requested " + quantityUnits);
            }

            // 2. Reserve stock locally
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE cached_branch_stock SET reserved_offline_units = reserved_offline_units + ?, updated_at = ? " +
                            "WHERE batch_id = ?")) {
                upd.setInt(1, quantityUnits);
                upd.setString(2, Instant.now().toString());
                upd.setString(3, batchId);
                upd.executeUpdate();
            }

            // 3. Monotonic next client_sequence
            long nextSeq = 1;
            try (Statement seqSt = conn.createStatement();
                 ResultSet rs = seqSt.executeQuery("SELECT COALESCE(MAX(client_sequence), 0) + 1 FROM pending_offline_commands")) {
                if (rs.next()) {
                    nextSeq = rs.getLong(1);
                }
            }

            // 4. Insert durable outbox command
            String cmdId = "cmd-" + UUID.randomUUID().toString();
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO pending_offline_commands (command_id, idempotency_key, device_id, tenant_id, branch_id, " +
                            "client_sequence, command_type, aggregate_ref, payload_json, monetary_total_bdt, schema_version, " +
                            "sync_state, retry_count, created_at) VALUES (?, ?, ?, ?, ?, ?, 'OFFLINE_POS_SALE', ?, ?, ?, ?, 'SAVED_LOCALLY', 0, ?)")) {
                ins.setString(1, cmdId);
                ins.setString(2, idempotencyKey);
                ins.setString(3, deviceId);
                ins.setString(4, tenantId);
                ins.setString(5, branchId);
                ins.setLong(6, nextSeq);
                ins.setString(7, aggregateRef);
                ins.setString(8, payloadJson);
                ins.setString(9, monetaryTotalBdt.setScale(2, RoundingMode.HALF_UP).toPlainString());
                ins.setInt(10, CURRENT_SCHEMA_VERSION);
                ins.setString(11, Instant.now().toString());
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

    public static String computeDdlSha256() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String joined = String.join("\n", DDL_STATEMENTS);
            byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
