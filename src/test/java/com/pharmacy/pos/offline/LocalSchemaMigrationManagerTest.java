package com.pharmacy.pos.offline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalSchemaMigrationManagerTest {

    private File tempDbFile;
    private File tempBackupDir;
    private Connection conn;
    private LocalSchemaMigrationManager migrationManager;

    private static final String FP = "aabbccdd".repeat(8);

    @BeforeEach
    void setUp() throws Exception {
        tempDbFile = Files.createTempFile("pharmacy_test_pos_", ".db").toFile();
        tempBackupDir = Files.createTempDirectory("pharmacy_backup_").toFile();

        conn = DriverManager.getConnection("jdbc:sqlite:" + tempDbFile.getAbsolutePath());
        DeviceLocalDatabaseSchema.initializeAndBind(
                conn,
                "POS-TEST-001",
                "ten-test-a",
                "br-test-a",
                FP,
                "STRICT_OFFLINE"
        );

        migrationManager = new LocalSchemaMigrationManager();

        // Enqueue a pending outbox command
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pending_offline_commands (" +
                        "command_id, idempotency_key, device_id, tenant_id, branch_id, client_sequence, " +
                        "command_type, aggregate_ref, payload_json, monetary_total_bdt, schema_version, " +
                        "sync_state, retry_count, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "CMD-OUTBOX-001");
            ps.setString(2, "idem-" + UUID.randomUUID());
            ps.setString(3, "POS-TEST-001");
            ps.setString(4, "ten-test-a");
            ps.setString(5, "br-test-a");
            ps.setLong(6, 1L);
            ps.setString(7, "OFFLINE_POS_SALE");
            ps.setString(8, "INV-001");
            ps.setString(9, "{\"total\": 150.00}");
            ps.setString(10, "150.00");
            ps.setInt(11, 1);
            ps.setString(12, "SAVED_LOCALLY");
            ps.setInt(13, 0);
            ps.setString(14, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
        if (tempDbFile != null && tempDbFile.exists()) {
            tempDbFile.delete();
        }
        if (tempBackupDir != null && tempBackupDir.exists()) {
            for (File f : tempBackupDir.listFiles()) f.delete();
            tempBackupDir.delete();
        }
    }

    @Test
    @DisplayName("Successfully applies forward migration v1 -> v2 while preserving pending outbox events")
    void testForwardMigrationPreservesOutbox() throws Exception {
        assertEquals(1, migrationManager.getCurrentSchemaVersion(conn));
        assertEquals(1, migrationManager.getPendingOutboxCount(conn));

        String forwardSql = "ALTER TABLE device_metadata ADD COLUMN maintenance_mode INTEGER DEFAULT 0;";
        LocalSchemaMigrationManager.MigrationResult result = migrationManager.applyMigration(
                conn, 2, forwardSql, "sha256-mock-backup"
        );

        assertTrue(result.isSuccess());
        assertEquals(2, result.getToVersion());
        assertEquals(1, result.getOutboxEventsPreserved());
        assertEquals(2, migrationManager.getCurrentSchemaVersion(conn));
        assertEquals(1, migrationManager.getPendingOutboxCount(conn));
    }

    @Test
    @DisplayName("Rolls back cleanly to v1 while keeping outbox events intact")
    void testRollbackMigration() throws Exception {
        // Upgrade to v2
        migrationManager.applyMigration(conn, 2, "ALTER TABLE device_metadata ADD COLUMN maintenance_mode INTEGER DEFAULT 0;", "hash");
        assertEquals(2, migrationManager.getCurrentSchemaVersion(conn));

        // Rollback to v1
        LocalSchemaMigrationManager.MigrationResult result = migrationManager.rollbackMigration(
                conn, 1, "ALTER TABLE device_metadata DROP COLUMN maintenance_mode;"
        );

        assertTrue(result.isSuccess());
        assertEquals(1, result.getToVersion());
        assertEquals(1, migrationManager.getCurrentSchemaVersion(conn));
        assertEquals(1, migrationManager.getPendingOutboxCount(conn));
    }

    @Test
    @DisplayName("Rolls back transaction automatically when migration SQL is invalid")
    void testFaultyMigrationFailsSafely() throws Exception {
        String badSql = "THIS IS NOT VALID SQL SYNTAX;";
        LocalSchemaMigrationManager.MigrationResult result = migrationManager.applyMigration(
                conn, 2, badSql, "hash"
        );

        assertFalse(result.isSuccess());
        // Schema version must remain at 1
        assertEquals(1, migrationManager.getCurrentSchemaVersion(conn));
        // Outbox event must remain intact
        assertEquals(1, migrationManager.getPendingOutboxCount(conn));
    }

    @Test
    @DisplayName("Aborts migration and rolls back if an outbox event is dropped")
    void testOutboxLossDetectionAbortsMigration() throws Exception {
        // SQL that attempts to truncate or delete pending outbox events
        String maliciousSql = "DELETE FROM pending_offline_commands;";
        LocalSchemaMigrationManager.MigrationResult result = migrationManager.applyMigration(
                conn, 2, maliciousSql, "hash"
        );

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("ERR_OUTBOX_EVENT_LOSS_DETECTED"));
        // Transaction was rolled back: outbox event is still present!
        assertEquals(1, migrationManager.getPendingOutboxCount(conn));
        assertEquals(1, migrationManager.getCurrentSchemaVersion(conn));
    }

    @Test
    @DisplayName("Creates database backup snapshot and computes valid SHA-256")
    void testDatabaseBackupSnapshot() throws Exception {
        File backup = migrationManager.createDatabaseBackup(tempDbFile, tempBackupDir);
        assertTrue(backup.exists());
        assertTrue(backup.length() > 0);

        String hash = migrationManager.computeFileSha256(backup);
        assertNotNull(hash);
        assertEquals(64, hash.length());
    }
}
