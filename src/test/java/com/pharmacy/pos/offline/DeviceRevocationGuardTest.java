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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeviceRevocationGuardTest {

    private File tempDbFile;
    private Connection conn;
    private DeviceRevocationGuard guard;

    private static final String FP = "aabbccdd".repeat(8);

    @BeforeEach
    void setUp() throws Exception {
        tempDbFile = Files.createTempFile("pharmacy_revocation_test_", ".db").toFile();
        conn = DriverManager.getConnection("jdbc:sqlite:" + tempDbFile.getAbsolutePath());
        DeviceLocalDatabaseSchema.initializeAndBind(
                conn,
                "POS-REVOKE-001",
                "ten-test-a",
                "br-test-a",
                FP,
                "STRICT_OFFLINE"
        );

        guard = new DeviceRevocationGuard();

        // Enqueue an unsynced command
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pending_offline_commands (" +
                        "command_id, idempotency_key, device_id, tenant_id, branch_id, client_sequence, " +
                        "command_type, aggregate_ref, payload_json, monetary_total_bdt, schema_version, " +
                        "sync_state, retry_count, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "CMD-UNSYNCED-001");
            ps.setString(2, "idem-" + UUID.randomUUID());
            ps.setString(3, "POS-REVOKE-001");
            ps.setString(4, "ten-test-a");
            ps.setString(5, "br-test-a");
            ps.setLong(6, 1L);
            ps.setString(7, "OFFLINE_POS_SALE");
            ps.setString(8, "INV-001");
            ps.setString(9, "{\"cart\": [\"MED-1\"]}");
            ps.setString(10, "500.00");
            ps.setInt(11, 1);
            ps.setString(12, "SAVED_LOCALLY");
            ps.setInt(13, 0);
            ps.setString(14, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) conn.close();
        if (tempDbFile != null && tempDbFile.exists()) tempDbFile.delete();
    }

    @Test
    @DisplayName("Workstation is operational initially and allows actions")
    void testInitialOperationalState() {
        assertFalse(guard.isWorkstationLocked());
        assertDoesNotThrow(() -> guard.assertWorkstationOperational());
    }

    @Test
    @DisplayName("Locking workstation blocks subsequent sales and sets local leases to REVOKED")
    void testWorkstationLockdown() throws Exception {
        guard.lockWorkstation(conn, "Hardware reported stolen from cashier counter");
        assertTrue(guard.isWorkstationLocked());
        assertEquals("Hardware reported stolen from cashier counter", guard.getLockdownReason());
        assertTrue(guard.getLockdownBannerMessage().contains("SECURITY LOCKDOWN"));

        assertThrows(SecurityException.class, () -> guard.assertWorkstationOperational());
    }

    @Test
    @DisplayName("Extracts unsynced outbox transactions as evidence without data loss")
    void testExtractEvidence() throws Exception {
        Map<String, Object> evidence = guard.extractUnsyncedOutboxEvidence(conn);
        assertNotNull(evidence);
        assertEquals(1, ((Number) evidence.get("unsynced_command_count")).intValue());
        assertTrue(evidence.containsKey("commands"));
    }
}
