package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStockCorrectionClientTest
 * Architecture Decision: ADR-0174 (P174).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 */
public class PosStockCorrectionClientTest {

    @Test
    @DisplayName("Test 1: Propose adjustment creates PENDING record with validations")
    void testProposeAdjustment() {
        PosStockCorrectionClient client = new PosStockCorrectionClient(
                "tenant-dhaka", "branch-banani", "CASHIER", "cashier1", false
        );

        // Zero delta should fail
        var zeroRes = client.proposeAdjustment(
                "med-napa", "batch-b1", null,
                PosStockCorrectionClient.AdjustmentType.DAMAGE,
                0, new BigDecimal("2.50"), "Valid reason text", ""
        );
        assertFalse(zeroRes.success);
        assertEquals("ERR_ZERO_QUANTITY_DELTA", zeroRes.errorCode);

        // Short reason (< 10 chars) should fail
        var shortReasonRes = client.proposeAdjustment(
                "med-napa", "batch-b1", null,
                PosStockCorrectionClient.AdjustmentType.DAMAGE,
                -5, new BigDecimal("2.50"), "broken", ""
        );
        assertFalse(shortReasonRes.success);
        assertEquals("ERR_AUDIT_REASON_REQUIRED", shortReasonRes.errorCode);

        // Valid proposal
        var validRes = client.proposeAdjustment(
                "med-napa", "batch-b1", "lot-1",
                PosStockCorrectionClient.AdjustmentType.DAMAGE,
                -10, new BigDecimal("2.50"), "10 strips damaged in water leak", "DOC-INCIDENT-99"
        );
        assertTrue(validRes.success);
        assertNotNull(validRes.data);
        assertEquals(PosStockCorrectionClient.CorrectionStatus.PENDING, validRes.data.status);
        assertEquals(-10, validRes.data.quantityDelta);
        assertEquals(new BigDecimal("25.00"), validRes.data.totalCostImpact);
        assertTrue(validRes.data.adjustmentId.startsWith("stkadj-"));
    }

    @Test
    @DisplayName("Test 2: Dual authorization and reservation safety on approval")
    void testApprovalAuthorizationAndReservationSafety() {
        PosStockCorrectionClient cashierClient = new PosStockCorrectionClient(
                "tenant-dhaka", "branch-banani", "CASHIER", "cashier1", false
        );
        var propRes = cashierClient.proposeAdjustment(
                "med-napa", "batch-b1", null,
                PosStockCorrectionClient.AdjustmentType.EXPIRY_REMOVAL,
                -20, new BigDecimal("3.00"), "Batch expired and removed from shelf", ""
        );
        assertTrue(propRes.success);
        String adjId = propRes.data.adjustmentId;

        // Cashier cannot approve
        var cashierApproveRes = cashierClient.approveAdjustment(adjId, 100, 10);
        assertFalse(cashierApproveRes.success);
        assertEquals("ERR_PERMISSION_DENIED", cashierApproveRes.errorCode);

        // Manager approval with reservation conflict: physical 25, reserved 15 -> unreserved 10 < abs(-20)
        PosStockCorrectionClient managerClient = new PosStockCorrectionClient(
                "tenant-dhaka", "branch-banani", "BRANCH_MANAGER", "manager1", false,
                cashierClient.getLedgerStore()
        );
        var conflictRes = managerClient.approveAdjustment(adjId, 25, 15);
        assertFalse(conflictRes.success);
        assertEquals("ERR_INSUFFICIENT_UNRESERVED_STOCK", conflictRes.errorCode);

        // Manager approval with sufficient unreserved stock: physical 100, reserved 10 -> unreserved 90 >= 20
        var approvedRes = managerClient.approveAdjustment(adjId, 100, 10);
        assertTrue(approvedRes.success);
        assertEquals(PosStockCorrectionClient.CorrectionStatus.APPROVED, approvedRes.data.status);
        assertEquals("manager1", approvedRes.data.approvedBy);
        assertNotNull(approvedRes.data.approvedAt);
    }

    @Test
    @DisplayName("Test 3: Reject pending adjustment with reasoned audit explanation")
    void testRejectPendingAdjustment() {
        PosStockCorrectionClient client = new PosStockCorrectionClient(
                "tenant-dhaka", "branch-banani", "BRANCH_MANAGER", "manager1", false
        );
        var propRes = client.proposeAdjustment(
                "med-napa", "batch-b1", null,
                PosStockCorrectionClient.AdjustmentType.COUNT_CORRECTION,
                15, new BigDecimal("2.50"), "Found 15 units during cycle count", ""
        );
        assertTrue(propRes.success);
        String adjId = propRes.data.adjustmentId;

        // Short rejection reason
        var failReject = client.rejectAdjustment(adjId, "no way");
        assertFalse(failReject.success);
        assertEquals("ERR_AUDIT_REASON_REQUIRED", failReject.errorCode);

        // Valid rejection
        var okReject = client.rejectAdjustment(adjId, "Physical recount verified count is correct; surplus not found.");
        assertTrue(okReject.success);
        assertEquals(PosStockCorrectionClient.CorrectionStatus.REJECTED, okReject.data.status);
    }

    @Test
    @DisplayName("Test 4: Reversing approved adjustment creates symmetrical compensating entry")
    void testReversalCreatesCompensatingLedgerEntry() {
        PosStockCorrectionClient managerClient = new PosStockCorrectionClient(
                "tenant-dhaka", "branch-banani", "PHARMACY_OWNER", "owner1", false
        );
        var propRes = managerClient.proposeAdjustment(
                "med-napa", "batch-b1", null,
                PosStockCorrectionClient.AdjustmentType.THEFT_LOSS,
                -8, new BigDecimal("5.00"), "Discrepancy observed at night shift handover", ""
        );
        assertTrue(propRes.success);
        String adjId = propRes.data.adjustmentId;

        managerClient.approveAdjustment(adjId, 50, 0);

        // Reversal
        var revRes = managerClient.reverseAdjustment(
                adjId, "Handover error resolved: stock located in dispensary B", 42, 0
        );
        assertTrue(revRes.success);
        assertNotNull(revRes.data);
        assertEquals(PosStockCorrectionClient.CorrectionStatus.APPROVED, revRes.data.status);
        assertEquals(8, revRes.data.quantityDelta); // Symmetrical opposite (+8)
        assertEquals(adjId, revRes.data.reversalOfId);

        // Verify original is marked reversed
        var origOpt = managerClient.getAdjustment(adjId);
        assertTrue(origOpt.isPresent());
        assertTrue(origOpt.get().isReversed);
        assertEquals(PosStockCorrectionClient.CorrectionStatus.REVERSED, origOpt.get().status);

        // Double reversal is barred
        var doubleRevRes = managerClient.reverseAdjustment(
                adjId, "Attempting second reversal", 50, 0
        );
        assertFalse(doubleRevRes.success);
        assertEquals("ERR_ALREADY_REVERSED", doubleRevRes.errorCode);
    }
}
