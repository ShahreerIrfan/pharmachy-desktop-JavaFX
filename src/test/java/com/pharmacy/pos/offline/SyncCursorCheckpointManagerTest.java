package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SyncCursorCheckpointManagerTest (P308 / ADR-0308)
 * Proves that pull cursor checkpoints advance ONLY after durable local SQLite apply,
 * and that independent push acknowledgments survive pull crashes and audited pull rewinds.
 */
class SyncCursorCheckpointManagerTest {

    @Test
    void testDecoupledPullCheckpointAndIndependentPushAckSurviveCrashAndRewind(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("pos_p308_checkpoint.db");
        String fp = "9".repeat(64);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            DeviceLocalDatabaseSchema.initializeAndBind(
                    conn, "DEV-CP-01", "ten-cp-1", "br-cp-1", fp, "CONTROLLED_RECONCILIATION");

            // 1. Apply initial pull batch (seq 1, 2) -> pull watermark = 2, push watermark = 0
            List<ChangeFeedPullClient.ChangeFeedEventRecord> batch1 = List.of(
                    new ChangeFeedPullClient.ChangeFeedEventRecord(
                            1L, "ten-cp-1", null, "MEDICINE_CATALOG", "med-1", "UPSERT", 1,
                            Map.of("brand_name", "Maxpro 20", "generic_name", "Esomeprazole", "mrp_bdt", "7.50")
                    ),
                    new ChangeFeedPullClient.ChangeFeedEventRecord(
                            2L, "ten-cp-1", "br-cp-1", "BATCH_STOCK", "bat-1", "UPSERT", 1,
                            Map.of("product_id", "med-1", "batch_number", "CP-1", "quantity_base_units", "30", "cost_per_unit_bdt", "4.25")
                    )
            );
            SyncCursorCheckpointManager.WatermarkSnapshot wm1 = SyncCursorCheckpointManager.applyPullAndCheckpoint(
                    conn, "ten-cp-1", "br-cp-1", batch1, -1);
            assertEquals(2L, wm1.pullAppliedSequenceNo);
            assertEquals(64, wm1.pullApplyDigestSha256.length());
            assertEquals(0L, wm1.pushAckedSequenceNo);

            // 2. Enqueue local offline sale and apply independent push ack -> push watermark = 1, pull STAYS 2!
            String cmdId = DeviceLocalDatabaseSchema.enqueueOfflineSaleAtomically(
                    conn, "DEV-CP-01", "ten-cp-1", "br-cp-1", "bat-1", 4,
                    new BigDecimal("30.00"), "idem-cp-sale-1", "OFF-CP-1", "{\"qty\":4}");
            SyncCursorCheckpointManager.WatermarkSnapshot wm2 = SyncCursorCheckpointManager.applyPushAckIndependently(
                    conn, "ten-cp-1", "br-cp-1", cmdId, 1L, "cack-101", "COMMITTED", "SRV-INV-101", "bat-1", 4);
            assertEquals(2L, wm2.pullAppliedSequenceNo);
            assertEquals(1L, wm2.pushAckedSequenceNo);
            assertEquals("cack-101", wm2.pushLastAckId);

            // 3. CRASH DRILL during next pull batch (seq 3, 4) -> pull rolls back to 2, push watermark STAYS 1!
            List<ChangeFeedPullClient.ChangeFeedEventRecord> batch2 = List.of(
                    new ChangeFeedPullClient.ChangeFeedEventRecord(
                            3L, "ten-cp-1", null, "MEDICINE_CATALOG", "med-1", "UPSERT", 2,
                            Map.of("brand_name", "Maxpro 20", "generic_name", "Esomeprazole", "mrp_bdt", "8.00")
                    ),
                    new ChangeFeedPullClient.ChangeFeedEventRecord(
                            4L, "ten-cp-1", "br-cp-1", "BATCH_STOCK", "bat-1", "UPSERT", 2,
                            Map.of("product_id", "med-1", "batch_number", "CP-1", "quantity_base_units", "26", "cost_per_unit_bdt", "4.25")
                    )
            );
            assertThrows(SQLException.class, () ->
                    SyncCursorCheckpointManager.applyPullAndCheckpoint(conn, "ten-cp-1", "br-cp-1", batch2, 1));

            SyncCursorCheckpointManager.WatermarkSnapshot afterCrash = SyncCursorCheckpointManager.readWatermarks(conn);
            assertEquals(2L, afterCrash.pullAppliedSequenceNo);
            assertEquals(1L, afterCrash.pushAckedSequenceNo);
            assertEquals("cack-101", afterCrash.pushLastAckId);

            // 4. RECOVERY REPLAY: Clean apply advances pull -> 4 while push stays 1
            SyncCursorCheckpointManager.WatermarkSnapshot afterRec = SyncCursorCheckpointManager.applyPullAndCheckpoint(
                    conn, "ten-cp-1", "br-cp-1", batch2, -1);
            assertEquals(4L, afterRec.pullAppliedSequenceNo);
            assertEquals(1L, afterRec.pushAckedSequenceNo);

            // 5. AUDITED PULL REWIND: Pull rewinds 4 -> 0 (epoch 2), push ack watermark 1 SURVIVES intact!
            SyncCursorCheckpointManager.WatermarkSnapshot afterRewind = SyncCursorCheckpointManager.applyAuditedPullRewind(
                    conn, 0L, 2);
            assertEquals(0L, afterRewind.pullAppliedSequenceNo);
            assertEquals(2, afterRewind.recoveryEpoch);
            assertEquals(1L, afterRewind.pushAckedSequenceNo);
            assertEquals("cack-101", afterRewind.pushLastAckId);
        }
    }
}
