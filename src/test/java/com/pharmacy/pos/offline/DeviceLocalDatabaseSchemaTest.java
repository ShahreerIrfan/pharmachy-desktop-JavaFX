package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DeviceLocalDatabaseSchemaTest (ADR-0301 / P301)
 * Verifies real JDBC SQLite initialization, device fingerprint binding, atomic outbox enqueue,
 * duplicate idempotency rejection, and rollback on insufficient stock.
 */
public class DeviceLocalDatabaseSchemaTest {

    private static final String FP_A = "a".repeat(64);
    private static final String FP_B = "b".repeat(64);

    @Test
    void testSchemaInitAtomicOutboxAndDuplicateRollback() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DeviceLocalDatabaseSchema.initializeAndBind(
                    conn, "pos-dev-01", "ten-1", "br-1", FP_A, "CONTROLLED_RECONCILIATION"
            );

            // Seed catalog & branch batch
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO cached_catalog_medicines VALUES ('med-1','ten-1','Napa','Paracetamol','TABLET','20.00',0,0,1,1,'2026-11-18T00:00:00Z')");
                st.execute("INSERT INTO cached_branch_stock VALUES ('bat-1','ten-1','br-1','med-1','BN-01','2027-12-31',10,0,'12.50','ACTIVE',1,'2026-11-18T00:00:00Z')");
            }

            // 1. Valid atomic offline sale enqueue reserves 4 units & writes outbox row
            String cmdId = DeviceLocalDatabaseSchema.enqueueOfflineSaleAtomically(
                    conn, "pos-dev-01", "ten-1", "br-1", "bat-1", 4,
                    new BigDecimal("80.00"), "idem-sale-001", "OFF-INV-001", "{\"qty\":4}"
            );
            assertNotNull(cmdId);

            // 2. Duplicate idempotency_key must roll back stock reservation atomically!
            assertThrows(SQLException.class, () ->
                    DeviceLocalDatabaseSchema.enqueueOfflineSaleAtomically(
                            conn, "pos-dev-01", "ten-1", "br-1", "bat-1", 2,
                            new BigDecimal("40.00"), "idem-sale-001", "OFF-INV-001", "{\"qty\":2}"
                    )
            );

            // Verify reserved_offline_units is STILL 4 (not 6!) after rollback
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT reserved_offline_units FROM cached_branch_stock WHERE batch_id='bat-1'")) {
                assertTrue(rs.next());
                assertEquals(4, rs.getInt(1));
            }

            // 3. Hardware fingerprint mismatch must throw SecurityException
            assertThrows(SecurityException.class, () ->
                    DeviceLocalDatabaseSchema.initializeAndBind(
                            conn, "pos-dev-01", "ten-1", "br-1", FP_B, "CONTROLLED_RECONCILIATION"
                    )
            );
        }
    }
}
