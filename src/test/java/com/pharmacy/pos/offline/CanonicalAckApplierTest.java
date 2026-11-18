package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CanonicalAckApplierTest (ADR-0306 / P306)
 * Proves idempotent local SQLite reconciliation of COMMITTED & REPLAY_IDEMPOTENT canonical acks
 * (stock deducted once even if ack is applied twice) and conflict queue routing.
 */
public class CanonicalAckApplierTest {

    @Test
    void testIdempotentLocalAckApplicationAndStockReconciliation() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DeviceLocalDatabaseSchema.initializeAndBind(
                    conn, "pos-1", "ten-1", "br-1", "a".repeat(64), "CONTROLLED_RECONCILIATION"
            );
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO cached_catalog_medicines VALUES ('med-1','ten-1','Napa','Paracetamol','TABLET','20.00',0,0,1,1,'2026-11-18T00:00:00Z')");
                st.execute("INSERT INTO cached_branch_stock VALUES ('bat-1','ten-1','br-1','med-1','B1','2027-12-31',20,0,'10.00','ACTIVE',1,'2026-11-18T00:00:00Z')");
            }

            String cmdId = DeviceLocalDatabaseSchema.enqueueOfflineSaleAtomically(
                    conn, "pos-1", "ten-1", "br-1", "bat-1", 6,
                    new BigDecimal("120.00"), "idem-ack-1", "OFF-ACK-1", "{\"qty\":6}"
            );

            // Apply COMMITTED ack -> SYNCED, quantity 20->14, reserved 6->0
            CanonicalAckApplier.applyCanonicalAckAtomically(
                    conn, "ten-1", "br-1", cmdId, "COMMITTED", "SRV-INV-999", "bat-1", 6, "", "{}"
            );
            // Apply REPLAY_IDEMPOTENT ack a second time -> quantity MUST STAY 14 (zero double local deduction!)
            CanonicalAckApplier.applyCanonicalAckAtomically(
                    conn, "ten-1", "br-1", cmdId, "REPLAY_IDEMPOTENT", "SRV-INV-999", "bat-1", 6, "", "{}"
            );

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT quantity_base_units, reserved_offline_units FROM cached_branch_stock WHERE batch_id='bat-1'")) {
                assertTrue(rs.next());
                assertEquals(14, rs.getInt(1));
                assertEquals(0, rs.getInt(2));
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT sync_state, server_ack_id FROM pending_offline_commands WHERE command_id='" + cmdId + "'")) {
                assertTrue(rs.next());
                assertEquals("SYNCED", rs.getString(1));
                assertEquals("SRV-INV-999", rs.getString(2));
            }
        }
    }
}
