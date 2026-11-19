package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SyncConflictClassifierTest (ADR-0313 / P313)
 * Tests client-side classification of all 6 conflict categories, bilingual diagnostic strings,
 * non-destructive SQLite queue routing, and manager resolution transitions.
 */
public class SyncConflictClassifierTest {

    @Test
    void testAllSixConflictCategoriesClassificationAndBilingualStrings() {
        // 1. Stale Stock
        var stockRes = SyncConflictClassifier.classify("ERR_TWO_DEVICE_STOCK_CONFLICT: Batch depleted");
        assertEquals(SyncConflictClassifier.SyncConflictCategory.STALE_STOCK, stockRes.category);
        assertEquals(SyncConflictClassifier.SyncConflictErrorCode.CONFLICT_STALE_STOCK, stockRes.errorCode);
        assertTrue(stockRes.englishDescription.contains("Stock is depleted"));
        assertTrue(stockRes.bengaliDescription.contains("স্টক শেষ"));

        // 2. Stale Price
        var priceRes = SyncConflictClassifier.classify("CONFLICT_STALE_PRICE: MRP variance exceeded");
        assertEquals(SyncConflictClassifier.SyncConflictCategory.STALE_PRICE, priceRes.category);
        assertEquals(SyncConflictClassifier.SyncConflictErrorCode.CONFLICT_STALE_PRICE, priceRes.errorCode);
        assertTrue(priceRes.englishDescription.contains("outdated price/MRP"));
        assertTrue(priceRes.bengaliDescription.contains("পুরনো মূল্যে"));

        // 3. Revoked Permission
        var permRes = SyncConflictClassifier.classify("ERR_REVOKED_PERMISSION_CASHIER");
        assertEquals(SyncConflictClassifier.SyncConflictCategory.REVOKED_PERMISSION, permRes.category);
        assertEquals(SyncConflictClassifier.SyncConflictErrorCode.CONFLICT_REVOKED_PERMISSION, permRes.errorCode);
        assertTrue(permRes.englishDescription.contains("Cashier or staff credentials"));
        assertTrue(permRes.bengaliDescription.contains("ক্যাশিয়ার"));

        // 4. Expired Subscription
        var subRes = SyncConflictClassifier.classify("CONFLICT_EXPIRED_SUBSCRIPTION: Lease expired");
        assertEquals(SyncConflictClassifier.SyncConflictCategory.EXPIRED_SUBSCRIPTION, subRes.category);
        assertEquals(SyncConflictClassifier.SyncConflictErrorCode.CONFLICT_EXPIRED_SUBSCRIPTION, subRes.errorCode);
        assertTrue(subRes.englishDescription.contains("Subscription tier or offline device lease"));
        assertTrue(subRes.bengaliDescription.contains("সাবস্ক্রিপশন"));

        // 5. Unverified Prescription
        var rxRes = SyncConflictClassifier.classify("CONFLICT_UNVERIFIED_PRESCRIPTION: Token revoked");
        assertEquals(SyncConflictClassifier.SyncConflictCategory.UNVERIFIED_PRESCRIPTION, rxRes.category);
        assertEquals(SyncConflictClassifier.SyncConflictErrorCode.CONFLICT_UNVERIFIED_PRESCRIPTION, rxRes.errorCode);
        assertTrue(rxRes.englishDescription.contains("Prescription token invalid"));
        assertTrue(rxRes.bengaliDescription.contains("প্রেসক্রিপশন"));

        // 6. Duplicate Command Tampered
        var tamperRes = SyncConflictClassifier.classify("ERR_IDEMPOTENCY_PAYLOAD_MISMATCH: Duplicate sequence");
        assertEquals(SyncConflictClassifier.SyncConflictCategory.DUPLICATE_COMMAND_TAMPERED, tamperRes.category);
        assertEquals(SyncConflictClassifier.SyncConflictErrorCode.CONFLICT_DUPLICATE_COMMAND_TAMPERED, tamperRes.errorCode);
        assertTrue(tamperRes.englishDescription.contains("Duplicate command payload"));
        assertTrue(tamperRes.bengaliDescription.contains("ডুপ্লিকেট"));
    }

    @Test
    void testNonDestructiveSqliteQueueRoutingAndResolution() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DeviceLocalDatabaseSchema.initializeAndBind(
                    conn, "pos-conf-1", "ten-conf-1", "br-conf-1", "b".repeat(64), "CONTROLLED_RECONCILIATION"
            );
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO cached_catalog_medicines VALUES ('med-c1','ten-conf-1','Ace Plus','Paracetamol','TABLET','25.00',0,0,1,1,'2026-11-19T00:00:00Z')");
                st.execute("INSERT INTO cached_branch_stock VALUES ('bat-c1','ten-conf-1','br-conf-1','med-c1','BC1','2028-01-01',50,0,'15.00','ACTIVE',1,'2026-11-19T00:00:00Z')");
            }

            String cmdId = DeviceLocalDatabaseSchema.enqueueOfflineSaleAtomically(
                    conn, "pos-conf-1", "ten-conf-1", "br-conf-1", "bat-c1", 10,
                    new BigDecimal("250.00"), "idem-conf-1", "OFF-CONF-1", "{\"qty\":10}"
            );

            // Record conflict ticket into sync_conflict_queue
            String conflictId = "conf-ticket-1";
            SyncConflictClassifier.recordConflictTicket(
                    conn, conflictId, cmdId, "ten-conf-1", "br-conf-1",
                    "CONFLICT_STALE_STOCK: Server batch depleted",
                    "{\"sale_id\":\"OFF-CONF-1\"}",
                    "{\"server_batch_qty\":0}"
            );

            // Assert pending command sync_state is CONFLICT_NEEDS_REVIEW
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT sync_state, error_detail FROM pending_offline_commands WHERE command_id='" + cmdId + "'")) {
                assertTrue(rs.next());
                assertEquals("CONFLICT_NEEDS_REVIEW", rs.getString(1));
                assertTrue(rs.getString(2).contains("CONFLICT_STALE_STOCK"));
            }

            // Assert open conflicts count
            assertEquals(1, SyncConflictClassifier.countOpenConflicts(conn, "ten-conf-1"));

            // Manager resolves ticket
            boolean resolved = SyncConflictClassifier.resolveConflictInSqlite(
                    conn, conflictId, "RESOLVED_KEEP_SERVER", "mgr-user-1"
            );
            assertTrue(resolved);

            // Assert open conflicts count is now 0
            assertEquals(0, SyncConflictClassifier.countOpenConflicts(conn, "ten-conf-1"));

            // Verify resolution status in queue
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT resolution_status, resolved_by FROM sync_conflict_queue WHERE conflict_id='" + conflictId + "'")) {
                assertTrue(rs.next());
                assertEquals("RESOLVED_KEEP_SERVER", rs.getString(1));
                assertEquals("mgr-user-1", rs.getString(2));
            }
        }
    }
}
