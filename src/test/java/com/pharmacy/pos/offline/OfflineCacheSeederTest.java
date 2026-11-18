package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OfflineCacheSeederTest (ADR-0303 / P303)
 * Verifies atomic SQLite cache seeding and full rollback when a cross-branch row is injected.
 */
public class OfflineCacheSeederTest {

    @Test
    void testAtomicSeedAndCrossBranchRollback() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DeviceLocalDatabaseSchema.initializeAndBind(
                    conn, "pos-1", "ten-1", "br-1", "a".repeat(64), "CONTROLLED_RECONCILIATION"
            );

            List<Map<String, Object>> catalog = List.of(
                    Map.of("product_id", "med-1", "tenant_id", "ten-1", "brand_name", "Napa", "generic_name", "Paracetamol", "mrp_bdt", "20.00")
            );
            List<Map<String, Object>> validStock = List.of(
                    Map.of("batch_id", "bat-1", "tenant_id", "ten-1", "branch_id", "br-1", "product_id", "med-1", "batch_number", "B1", "expiry_date", "2027-12-31", "quantity_base_units", 50, "cost_per_unit_bdt", "12.50")
            );

            int applied = OfflineCacheSeeder.applySeedAtomically(conn, "ten-1", "br-1", 1, "b".repeat(64), catalog, validStock);
            assertEquals(2, applied);

            // Attempt injecting a foreign branch stock row -> must throw SecurityException and rollback!
            List<Map<String, Object>> poisonedStock = List.of(
                    Map.of("batch_id", "bat-2", "tenant_id", "ten-1", "branch_id", "br-OTHER", "product_id", "med-1", "batch_number", "B2", "expiry_date", "2027-12-31", "quantity_base_units", 10, "cost_per_unit_bdt", "10.00")
            );
            assertThrows(SecurityException.class, () ->
                    OfflineCacheSeeder.applySeedAtomically(conn, "ten-1", "br-1", 2, "c".repeat(64), catalog, poisonedStock)
            );

            // Verify cursor stayed at v1 (no partial write)
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT last_sync_cursor FROM device_metadata WHERE singleton_id=1")) {
                assertTrue(rs.next());
                assertTrue(rs.getString(1).startsWith("seed-v1:"));
            }
        }
    }
}
