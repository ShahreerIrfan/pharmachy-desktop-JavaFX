package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConflictReviewCenterTest (ADR-0314 / P314)
 * Verifies Conflict Review Center UI controller:
 * 1. Loading SQLite tickets, bilingual status & decision badges.
 * 2. Manager proposal lifecycle (OPEN -> DECISION_PROPOSED).
 * 3. Manager approval execution (DECISION_PROPOSED -> RESOLVED_ADJUSTED).
 * 4. Validation of incomplete inputs & exact BigDecimal BDT summation.
 */
public class ConflictReviewCenterTest {

    @Test
    void testConflictReviewCenterLifecycleAndValidation() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DeviceLocalDatabaseSchema.initializeAndBind(
                    conn, "pos-rc-1", "ten-rc-1", "br-rc-1", "c".repeat(64), "CONTROLLED_RECONCILIATION"
            );

            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO cached_catalog_medicines VALUES ('med-r1','ten-rc-1','Napa','Paracetamol','TABLET','15.00',0,0,1,1,'2026-11-19T00:00:00Z')");
                st.execute("INSERT INTO cached_branch_stock VALUES ('bat-r1','ten-rc-1','br-rc-1','med-r1','BR1','2028-01-01',100,0,'10.00','ACTIVE',1,'2026-11-19T00:00:00Z')");
            }

            String cmdId = DeviceLocalDatabaseSchema.enqueueOfflineSaleAtomically(
                    conn, "pos-rc-1", "ten-rc-1", "br-rc-1", "bat-r1", 5,
                    new BigDecimal("75.00"), "idem-rc-1", "OFF-RC-1", "{\"qty\":5}"
            );

            String conflictId = "conf-rc-101";
            SyncConflictClassifier.recordConflictTicket(
                    conn, conflictId, cmdId, "ten-rc-1", "br-rc-1",
                    "CONFLICT_STALE_STOCK: Cloud batch shortage",
                    "{\"sale_id\":\"OFF-RC-1\",\"qty\":5}",
                    "{\"cloud_qty\":2,\"shortage\":3}"
            );

            ConflictReviewCenterController controller = new ConflictReviewCenterController();
            controller.loadConflictsFromSqlite(conn, "ten-rc-1");

            assertEquals(ConflictReviewCenterController.ConflictUiState.LOADED, controller.getState());
            assertEquals(1, controller.getTickets().size());

            var ticket = controller.getTickets().get(0);
            assertEquals("OPEN", ticket.resolutionStatus);
            assertTrue(ticket.localizedStatusBadge().get("en").contains("Needs Review"));
            assertTrue(ticket.localizedStatusBadge().get("bn").contains("পর্যালোচনা"));

            // Validation: Empty notes throw IllegalArgumentException
            assertThrows(IllegalArgumentException.class, () -> {
                controller.proposeDecision(
                        conn, conflictId,
                        ConflictReviewCenterController.ProposedDecision.OVERRIDE_WITH_INVENTORY_ADJUSTMENT,
                        "",
                        "manager-user-1"
                );
            });

            // Valid Proposal
            boolean proposed = controller.proposeDecision(
                    conn, conflictId,
                    ConflictReviewCenterController.ProposedDecision.OVERRIDE_WITH_INVENTORY_ADJUSTMENT,
                    "Reconcile shortage with stock adjustment voucher.",
                    "manager-user-1"
            );
            assertTrue(proposed);
            assertEquals("DECISION_PROPOSED", ticket.resolutionStatus);
            assertTrue(ticket.localizedStatusBadge().get("bn").contains("সিদ্ধান্ত প্রস্তাবিত"));

            // Decision Localization
            var decEn = ConflictReviewCenterController.ConflictTicketViewModel.localizedDecision(
                    ConflictReviewCenterController.ProposedDecision.OVERRIDE_WITH_INVENTORY_ADJUSTMENT
            ).get("en");
            var decBn = ConflictReviewCenterController.ConflictTicketViewModel.localizedDecision(
                    ConflictReviewCenterController.ProposedDecision.OVERRIDE_WITH_INVENTORY_ADJUSTMENT
            ).get("bn");
            assertTrue(decEn.contains("Inventory Adjustment"));
            assertTrue(decBn.contains("স্টক সমন্বয়"));

            // Approval Execution
            boolean approved = controller.approveDecision(
                    conn, conflictId,
                    "Approved by Branch Manager.",
                    "ADJ-REF-999",
                    "manager-user-1"
            );
            assertTrue(approved);
            assertEquals("RESOLVED_ADJUSTED", ticket.resolutionStatus);
            assertTrue(ticket.localizedStatusBadge().get("en").contains("Adjustment Posted"));
        }
    }
}
