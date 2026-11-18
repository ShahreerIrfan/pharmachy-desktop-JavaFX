package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChangeFeedPullClientTest (P307 / ADR-0307)
 * Verifies atomic SQLite change-feed pull application, tombstone deactivation,
 * crash-recovery rollback & idempotent replay, cross-tenant rejection, and
 * two-device stock conflict detection without losing local offline reservations.
 */
class ChangeFeedPullClientTest {

    @Test
    void testAtomicPullTombstoneCrashRecoveryAndTwoDeviceConflict(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("pos_p307_feed.db");
        String fp = "f".repeat(64);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            DeviceLocalDatabaseSchema.initializeAndBind(
                    conn, "DEV-CF-01", "ten-cf-1", "br-cf-1", fp, "CONTROLLED_RECONCILIATION");

            // Seed base catalog & stock with 10 units locally reserved for an unsynced offline sale
            List<ChangeFeedPullClient.ChangeFeedEventRecord> initial = List.of(
                    new ChangeFeedPullClient.ChangeFeedEventRecord(
                            1L, "ten-cf-1", null, "MEDICINE_CATALOG", "med-1", "UPSERT", 1,
                            Map.of("brand_name", "Seclo 20", "generic_name", "Omeprazole", "mrp_bdt", "6.00")
                    ),
                    new ChangeFeedPullClient.ChangeFeedEventRecord(
                            2L, "ten-cf-1", "br-cf-1", "BATCH_STOCK", "bat-1", "UPSERT", 1,
                            Map.of("product_id", "med-1", "batch_number", "SC-01", "quantity_base_units", "20", "cost_per_unit_bdt", "4.00")
                    )
            );
            ChangeFeedPullClient.ApplyResult r0 = ChangeFeedPullClient.applyChangeFeedBatchAtomically(
                    conn, "ten-cf-1", "br-cf-1", initial, -1);
            assertEquals(2, r0.appliedCount);
            assertEquals(2L, r0.committedCursorSequence);

            // Simulate real unsynced offline POS sale reserving 10 units on bat-1 in outbox + cached_branch_stock
            DeviceLocalDatabaseSchema.enqueueOfflineSaleAtomically(
                    conn, "DEV-CF-01", "ten-cf-1", "br-cf-1", "bat-1", 10,
                    new java.math.BigDecimal("60.00"), "idem-cf-off-1", "OFF-INV-CF1", "{\"qty\":10}");

            // Prepare incremental delta: seq 3 (price update), seq 4 (two-device stock drop to 4 < 10 reserved), seq 5 (tombstone)
            List<ChangeFeedPullClient.ChangeFeedEventRecord> delta = List.of(
                    new ChangeFeedPullClient.ChangeFeedEventRecord(
                            3L, "ten-cf-1", null, "MEDICINE_CATALOG", "med-1", "UPSERT", 2,
                            Map.of("brand_name", "Seclo 20", "generic_name", "Omeprazole", "mrp_bdt", "6.50")
                    ),
                    new ChangeFeedPullClient.ChangeFeedEventRecord(
                            4L, "ten-cf-1", "br-cf-1", "BATCH_STOCK", "bat-1", "UPSERT", 2,
                            Map.of("product_id", "med-1", "batch_number", "SC-01", "quantity_base_units", "4", "cost_per_unit_bdt", "4.00")
                    ),
                    new ChangeFeedPullClient.ChangeFeedEventRecord(
                            5L, "ten-cf-1", "br-cf-1", "BATCH_STOCK", "bat-1", "TOMBSTONE", 3,
                            Map.of("tombstone_reason", "RECALLED")
                    )
            );

            // 1. CRASH-RECOVERY DRILL: Simulate power loss on index 1 -> full transaction rollback, cursor stays at 2!
            assertThrows(SQLException.class, () ->
                    ChangeFeedPullClient.applyChangeFeedBatchAtomically(conn, "ten-cf-1", "br-cf-1", delta, 1));
            assertEquals(2L, ChangeFeedPullClient.readLocalCursorSequence(conn));

            // 2. REPLAY AFTER RECOVERY: Re-apply cleanly from cursor 2
            ChangeFeedPullClient.ApplyResult r1 = ChangeFeedPullClient.applyChangeFeedBatchAtomically(
                    conn, "ten-cf-1", "br-cf-1", delta, -1);
            assertEquals(3, r1.appliedCount);
            assertEquals(1, r1.tombstoneCount);
            assertEquals(1, r1.conflictCount);
            assertEquals(5L, r1.committedCursorSequence);

            // Verify reserved_offline_units preserved AND conflict queued AND batch tombstoned
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, reserved_offline_units FROM cached_branch_stock WHERE batch_id = 'bat-1'");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("TOMBSTONED", rs.getString(1));
                assertEquals(10, rs.getInt(2));
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM sync_conflict_queue WHERE conflict_reason = 'TWO_DEVICE_STOCK_UNDERRUN'");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }

            // 3. IDEMPOTENT REPLAY: Re-delivering sequences <= 5 skips all 3 cleanly
            ChangeFeedPullClient.ApplyResult rDup = ChangeFeedPullClient.applyChangeFeedBatchAtomically(
                    conn, "ten-cf-1", "br-cf-1", delta, -1);
            assertEquals(0, rDup.appliedCount);
            assertEquals(3, rDup.skippedStaleCount);
            assertEquals(5L, rDup.committedCursorSequence);

            // 4. SECURITY DRILL: Cross-tenant event rejected with SecurityException
            List<ChangeFeedPullClient.ChangeFeedEventRecord> foreign = List.of(
                    new ChangeFeedPullClient.ChangeFeedEventRecord(
                            6L, "ten-foreign", null, "MEDICINE_CATALOG", "med-x", "UPSERT", 1, Map.of())
            );
            assertThrows(SecurityException.class, () ->
                    ChangeFeedPullClient.applyChangeFeedBatchAtomically(conn, "ten-cf-1", "br-cf-1", foreign, -1));
            assertEquals(5L, ChangeFeedPullClient.readLocalCursorSequence(conn));
        }
    }
}
