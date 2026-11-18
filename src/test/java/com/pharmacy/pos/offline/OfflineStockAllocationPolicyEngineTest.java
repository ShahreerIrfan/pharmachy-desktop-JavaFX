package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OfflineStockAllocationPolicyEngineTest (P310 / ADR-0310)
 * Proves expired/quarantined batch blocking, strict vs controlled cap enforcement,
 * honest local estimate labeling, and non-destructive two-device oversell conflict queuing.
 */
class OfflineStockAllocationPolicyEngineTest {

    @Test
    void testExpiredBlockStrictCapSoftWarningAndNonDestructiveOversellDispute(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("pos_p310_alloc.db");
        String fp = "5".repeat(64);
        LocalDate today = LocalDate.of(2026, 11, 18);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            DeviceLocalDatabaseSchema.initializeAndBind(
                    conn, "DEV-OA-01", "ten-oa-1", "br-oa-1", fp, "CONTROLLED_RECONCILIATION");
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO cached_catalog_medicines VALUES ('med-1','ten-oa-1','Sergel 20','Esomeprazole','CAPSULE','7.00',0,0,1,1,'2026-11-18T00:00:00Z')");
                st.execute("INSERT INTO cached_branch_stock VALUES ('bat-ok','ten-oa-1','br-oa-1','med-1','B1','2027-12-31',10,0,'4.50','ACTIVE',1,'2026-11-18T00:00:00Z')");
                st.execute("INSERT INTO cached_branch_stock VALUES ('bat-exp','ten-oa-1','br-oa-1','med-1','B-EXP','2026-11-01',10,0,'4.50','ACTIVE',1,'2026-11-18T00:00:00Z')");
            }

            // 1. Expired batch MUST be blocked unconditionally
            var expDec = OfflineStockAllocationPolicyEngine.reserveOfflineBatch(
                    conn, "DEV-OA-01", "ten-oa-1", "br-oa-1", "bat-exp", 2,
                    new BigDecimal("14.00"), "idem-exp", "OFF-EXP",
                    OfflineStockAllocationPolicyEngine.PolicyMode.CONTROLLED_RECONCILIATION, 80, today);
            assertFalse(expDec.allowed);
            assertEquals("ERR_BATCH_EXPIRED_OR_QUARANTINED", expDec.reasonCode);

            // 2. STRICT_LOCAL_ONLY with 80% cap (8 of 10) blocks requesting 9 units
            var strictBlock = OfflineStockAllocationPolicyEngine.reserveOfflineBatch(
                    conn, "DEV-OA-01", "ten-oa-1", "br-oa-1", "bat-ok", 9,
                    new BigDecimal("63.00"), "idem-strict-9", "OFF-S9",
                    OfflineStockAllocationPolicyEngine.PolicyMode.STRICT_LOCAL_ONLY, 80, today);
            assertFalse(strictBlock.allowed);
            assertEquals("ERR_STRICT_OFFLINE_CAP_EXCEEDED", strictBlock.reasonCode);

            // 3. CONTROLLED_RECONCILIATION allows 9 units with honest oversell warning & local estimate label
            var softOk = OfflineStockAllocationPolicyEngine.reserveOfflineBatch(
                    conn, "DEV-OA-01", "ten-oa-1", "br-oa-1", "bat-ok", 9,
                    new BigDecimal("63.00"), "idem-soft-9", "OFF-C9",
                    OfflineStockAllocationPolicyEngine.PolicyMode.CONTROLLED_RECONCILIATION, 80, today);
            assertTrue(softOk.allowed);
            assertTrue(softOk.oversellWarningFlagged);
            assertEquals("WARN_SOFT_RESERVE_CAP_EXCEEDED", softOk.reasonCode);
            assertTrue(softOk.certaintyLabelBn.contains("লোকাল আনুমানিক"));

            // 4. Non-destructive oversell dispute recording keeps original outbox invoice intact
            String confId = OfflineStockAllocationPolicyEngine.recordNonDestructiveOversellDispute(
                    conn, "ten-oa-1", "br-oa-1", softOk.commandId, "bat-ok", 3, 9, new BigDecimal("27.00"));
            assertTrue(confId.startsWith("osconf-"));

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT sync_state, monetary_total_bdt FROM pending_offline_commands WHERE command_id = ?")) {
                ps.setString(1, softOk.commandId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("CONFLICT_NEEDS_REVIEW", rs.getString(1));
                    assertEquals("63.00", rs.getString(2)); // Finalized local invoice amount preserved untouched!
                }
            }
        }
    }
}
