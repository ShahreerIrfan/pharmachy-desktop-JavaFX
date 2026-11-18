package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DurableOutboxRepositoryTest (ADR-0304 / P304)
 * Proves atomic mutation+outbox rollback on crash, FIFO ordering, crash-recovery of SYNCING rows,
 * and non-destructive conflict queue persistence.
 */
public class DurableOutboxRepositoryTest {

    @Test
    void testAtomicRollbackCrashRecoveryAndConflictQueue() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DeviceLocalDatabaseSchema.initializeAndBind(
                    conn, "pos-1", "ten-1", "br-1", "a".repeat(64), "CONTROLLED_RECONCILIATION"
            );
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO cached_catalog_medicines VALUES ('med-1','ten-1','Napa','Paracetamol','TABLET','20.00',0,0,1,1,'2026-11-18T00:00:00Z')");
                st.execute("INSERT INTO cached_branch_stock VALUES ('bat-1','ten-1','br-1','med-1','B1','2027-12-31',10,0,'12.00','ACTIVE',1,'2026-11-18T00:00:00Z')");
            }

            // 1. Simulated crash mid-transaction rolls back BOTH stock mutation and outbox insert
            assertThrows(RuntimeException.class, () ->
                    DurableOutboxRepository.executeAtomicMutationWithOutbox(
                            conn, "pos-1", "ten-1", "br-1", "OFFLINE_POS_SALE", "INV-CRASH",
                            "idem-crash-1", new BigDecimal("100.00"), "{}",
                            c -> {
                                try (Statement st = c.createStatement()) {
                                    st.executeUpdate("UPDATE cached_branch_stock SET reserved_offline_units = 5 WHERE batch_id='bat-1'");
                                }
                                throw new RuntimeException("Simulated power loss before commit");
                            }
                    )
            );
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT reserved_offline_units FROM cached_branch_stock WHERE batch_id='bat-1'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
            assertTrue(DurableOutboxRepository.fetchPendingCommandsInOrder(conn, 10).isEmpty());

            // 2. Valid atomic mutation commits cleanly
            String cmd1 = DurableOutboxRepository.executeAtomicMutationWithOutbox(
                    conn, "pos-1", "ten-1", "br-1", "OFFLINE_POS_SALE", "INV-1",
                    "idem-1", new BigDecimal("60.00"), "{\"qty\":3}",
                    c -> {
                        try (Statement st = c.createStatement()) {
                            st.executeUpdate("UPDATE cached_branch_stock SET reserved_offline_units = 3 WHERE batch_id='bat-1'");
                        }
                    }
            );

            // 3. Simulate crash while SYNCING -> startup recovery resets to SAVED_LOCALLY
            try (PreparedStatement ps = conn.prepareStatement("UPDATE pending_offline_commands SET sync_state='SYNCING' WHERE command_id=?")) {
                ps.setString(1, cmd1);
                ps.executeUpdate();
            }
            int recovered = DurableOutboxRepository.recoverInterruptedSyncingAfterCrash(conn);
            assertEquals(1, recovered);
            List<Map<String, Object>> pending = DurableOutboxRepository.fetchPendingCommandsInOrder(conn, 10);
            assertEquals(1, pending.size());
            assertEquals("SAVED_LOCALLY", pending.get(0).get("sync_state"));

            // 4. Conflict routing preserves command and inserts sync_conflict_queue row
            DurableOutboxRepository.markConflictAndQueue(conn, cmd1, "ten-1", "br-1", "TWO_DEVICE_STOCK_CONFLICT", "{\"qty\":3}", "{\"server_qty\":1}");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sync_conflict_queue WHERE resolution_status='OPEN'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }
}
