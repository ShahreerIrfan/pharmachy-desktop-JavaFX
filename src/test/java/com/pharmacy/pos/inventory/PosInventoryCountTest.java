package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosInventoryCountTest
 * Architecture Decision: ADR-0204 (P204).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Tests for Inventory Count Sessions:
 * 1. Cycle count session workflow, discrepancies, and posting.
 * 2. Maker-checker anti-self-approval rule.
 * 3. Invalid inputs (negative quantity, empty session).
 * 4. Cashier RBAC rejection.
 * 5. Already posted guardrail.
 */
class PosInventoryCountTest {

    private PosInventoryCountClient client;
    private final String tenantId = "ten-lake-01";
    private final String branchId = "br-banani-01";

    @BeforeEach
    void setUp() {
        client = new PosInventoryCountClient();
    }

    @Test
    @DisplayName("Test 1: Full count cycle, discrepancy calculations and posting")
    void testFullCountCycle() {
        // 1. Pharmacist (Maker) creates session
        PosInventoryCountClient.CountSessionDto session = client.createSession(
                tenantId, branchId, PosInventoryCountClient.SessionType.CYCLE_COUNT,
                "STAFF_PHARMACIST", "usr-maker-01"
        );
        assertEquals(PosInventoryCountClient.SessionStatus.IN_PROGRESS, session.status);

        // 2. Scan Batch 1 (Surplus: 100 system, 110 counted -> +10)
        PosInventoryCountClient.CountItemDto item1 = client.recordScanItem(
                session.sessionId, "BAT-01", 100, 110, new BigDecimal("2.50"), "STAFF_PHARMACIST"
        );
        assertEquals(10, item1.discrepancyQuantity);
        assertEquals(PosInventoryCountClient.DiscrepancyType.SURPLUS, item1.discrepancyType);
        assertEquals(new BigDecimal("25.00"), item1.costDiscrepancyBdt);

        // 3. Scan Batch 2 (Shortage: 50 system, 45 counted -> -5)
        PosInventoryCountClient.CountItemDto item2 = client.recordScanItem(
                session.sessionId, "BAT-02", 50, 45, new BigDecimal("2.50"), "STAFF_PHARMACIST"
        );
        assertEquals(-5, item2.discrepancyQuantity);
        assertEquals(PosInventoryCountClient.DiscrepancyType.SHORTAGE, item2.discrepancyType);
        assertEquals(new BigDecimal("-12.50"), item2.costDiscrepancyBdt);

        // Session Totals
        assertEquals(2, session.items.size());
        assertEquals(5, session.totalDiscrepancyUnits); // +10 - 5 = +5
        assertEquals(new BigDecimal("12.50"), session.totalCostDiscrepancyBdt);
        assertEquals(1, session.surplusCount);
        assertEquals(1, session.shortageCount);

        // 4. Maker submits
        client.submitForReview(session.sessionId, "STAFF_PHARMACIST", "usr-maker-01");
        assertEquals(PosInventoryCountClient.SessionStatus.SUBMITTED_FOR_REVIEW, session.status);

        // 5. Manager (Checker) approves
        client.reviewSession(session.sessionId, true, "BRANCH_MANAGER", "usr-checker-02", "Looks good");
        assertEquals(PosInventoryCountClient.SessionStatus.APPROVED, session.status);

        // 6. Post adjustments
        Map<String, Object> postRes = client.postAdjustments(session.sessionId, "BRANCH_MANAGER");
        assertEquals("POSTED", postRes.get("status"));
        assertEquals(2, postRes.get("postedAdjustments"));
        assertNotNull(item1.adjustmentLedgerEntryId);
        assertNotNull(item2.adjustmentLedgerEntryId);
    }

    @Test
    @DisplayName("Test 2: Maker-Checker separation of duties (Anti-Self-Approval)")
    void testAntiSelfApproval() {
        PosInventoryCountClient.CountSessionDto session = client.createSession(
                tenantId, branchId, PosInventoryCountClient.SessionType.SPOT_CHECK,
                "BRANCH_MANAGER", "usr-manager-01"
        );

        client.recordScanItem(session.sessionId, "BAT-01", 10, 12, new BigDecimal("1.00"), "BRANCH_MANAGER");
        client.submitForReview(session.sessionId, "BRANCH_MANAGER", "usr-manager-01");

        // Same user attempting to approve own session fails
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                client.reviewSession(session.sessionId, true, "BRANCH_MANAGER", "usr-manager-01", "Self check")
        );
        assertTrue(ex.getMessage().contains("ERR_MAKER_CHECKER_SELF_APPROVAL"));
    }

    @Test
    @DisplayName("Test 3: Reject negative quantity and empty session submission")
    void testInvalidInputs() {
        PosInventoryCountClient.CountSessionDto session = client.createSession(
                tenantId, branchId, PosInventoryCountClient.SessionType.CYCLE_COUNT,
                "STAFF_PHARMACIST", "usr-maker-01"
        );

        // Negative counted quantity
        assertThrows(IllegalArgumentException.class, () ->
                client.recordScanItem(session.sessionId, "B1", 10, -1, new BigDecimal("1.00"), "STAFF_PHARMACIST")
        );

        // Empty session submission
        assertThrows(IllegalArgumentException.class, () ->
                client.submitForReview(session.sessionId, "STAFF_PHARMACIST", "usr-maker-01")
        );
    }

    @Test
    @DisplayName("Test 4: Cashier RBAC rejection")
    void testCashierRbac() {
        assertThrows(SecurityException.class, () ->
                client.createSession(tenantId, branchId, PosInventoryCountClient.SessionType.CYCLE_COUNT, "CASHIER", "usr-c1")
        );
    }

    @Test
    @DisplayName("Test 5: Re-posting guardrail prevents duplicate adjustments")
    void testRePostingGuard() {
        PosInventoryCountClient.CountSessionDto session = client.createSession(
                tenantId, branchId, PosInventoryCountClient.SessionType.CYCLE_COUNT,
                "STAFF_PHARMACIST", "usr-m1"
        );
        client.recordScanItem(session.sessionId, "B1", 10, 15, new BigDecimal("1.00"), "STAFF_PHARMACIST");
        client.submitForReview(session.sessionId, "STAFF_PHARMACIST", "usr-m1");
        client.reviewSession(session.sessionId, true, "BRANCH_MANAGER", "usr-c2", "Approved");
        client.postAdjustments(session.sessionId, "BRANCH_MANAGER");

        // Second post fails
        assertThrows(IllegalStateException.class, () ->
                client.postAdjustments(session.sessionId, "BRANCH_MANAGER")
        );
    }
}
