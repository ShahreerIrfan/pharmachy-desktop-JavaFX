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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChaosSyncTest {

    private File tempDbFile;
    private Connection conn;
    private ChaosSyncHarness harness;

    private static final String FP = "aabbccdd".repeat(8);

    @BeforeEach
    void setUp() throws Exception {
        tempDbFile = Files.createTempFile("pharmacy_chaos_test_", ".db").toFile();
        conn = DriverManager.getConnection("jdbc:sqlite:" + tempDbFile.getAbsolutePath());
        DeviceLocalDatabaseSchema.initializeAndBind(
                conn,
                "POS-CHAOS-001",
                "ten-test-a",
                "br-test-a",
                FP,
                "STRICT_OFFLINE"
        );

        harness = new ChaosSyncHarness();

        // Enqueue a baseline command
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pending_offline_commands (" +
                        "command_id, idempotency_key, device_id, tenant_id, branch_id, client_sequence, " +
                        "command_type, aggregate_ref, payload_json, monetary_total_bdt, schema_version, " +
                        "sync_state, retry_count, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "CMD-BASE-001");
            ps.setString(2, "idem-base-001");
            ps.setString(3, "POS-CHAOS-001");
            ps.setString(4, "ten-test-a");
            ps.setString(5, "br-test-a");
            ps.setLong(6, 1L);
            ps.setString(7, "OFFLINE_POS_SALE");
            ps.setString(8, "INV-001");
            ps.setString(9, "{\"cart\": [\"MED-1\"]}");
            ps.setString(10, "200.00");
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
    @DisplayName("Drill 1: App crash during uncommitted write rolls back safely")
    void testAppCrashDrill() {
        ChaosSyncHarness.ChaosDrillResult res = harness.executeAppCrashDrill(conn);
        assertTrue(res.isPassed());
        assertEquals("DRILL_APP_CRASH", res.getDrillName());
    }

    @Test
    @DisplayName("Drill 2: Network partition transitions outbox to RETRY_SCHEDULED")
    void testNetworkPartitionDrill() {
        ChaosSyncHarness.ChaosDrillResult res = harness.executeNetworkPartitionDrill(conn, "CMD-BASE-001");
        assertTrue(res.isPassed());
        assertEquals("DRILL_NETWORK_PARTITION", res.getDrillName());
    }

    @Test
    @DisplayName("Drill 3: Duplicate push replay blocked by unique idempotency constraint")
    void testDuplicatePushDrill() {
        ChaosSyncHarness.ChaosDrillResult res = harness.executeDuplicatePushDrill(conn, "idem-base-001");
        assertTrue(res.isPassed());
        assertEquals("DRILL_DUPLICATE_PUSH", res.getDrillName());
    }

    @Test
    @DisplayName("Drill 4: Lost ACK reconciled upon server ACK arrival")
    void testLostAckReconciliationDrill() {
        ChaosSyncHarness.ChaosDrillResult res = harness.executeLostAckReconciliationDrill(
                conn, "CMD-BASE-001", "ACK-SRV-9999"
        );
        assertTrue(res.isPassed());
        assertEquals("DRILL_LOST_ACK", res.getDrillName());
    }

    @Test
    @DisplayName("Drill 5: Two offline counters stock conflict quarantined in conflict queue")
    void testTwoDeviceStockConflictDrill() {
        ChaosSyncHarness.ChaosDrillResult res = harness.executeTwoDeviceStockConflictDrill(
                conn, "CONF-001", "CMD-BASE-001", "ten-test-a", "br-test-a"
        );
        assertTrue(res.isPassed());
        assertEquals("DRILL_MULTI_COUNTER_STOCK_CONFLICT", res.getDrillName());
    }

    @Test
    @DisplayName("Drill 6: Expired subscription lease correctly blocks protected sales")
    void testLeaseExpiryDrill() {
        ChaosSyncHarness.ChaosDrillResult res = harness.executeLeaseExpiryDrill(conn);
        assertTrue(res.isPassed());
        assertEquals("DRILL_LEASE_EXPIRY", res.getDrillName());
    }
}
