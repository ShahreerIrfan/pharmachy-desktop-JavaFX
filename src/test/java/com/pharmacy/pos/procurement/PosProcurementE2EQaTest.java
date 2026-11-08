package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosProcurementE2EQaTest
 * Architecture Decision: ADR-0200 (P200).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain Capstone.
 *
 * Desktop POS procurement E2E and conflict QA tests:
 * 1. Full cycle: PO -> GRN -> Lot -> AP settlement.
 * 2. Duplicate receipt conflict detection.
 * 3. Purchase return debit note reversal.
 * 4. Invalid quantity rejection.
 * 5. Cashier RBAC rejection.
 * 6. Decimal precision and SHA-256 verification.
 */
class PosProcurementE2EQaTest {

    private PosProcurementE2EQaClient managerClient;
    private PosProcurementE2EQaClient cashierClient;

    @BeforeEach
    void setUp() {
        managerClient = new PosProcurementE2EQaClient(
                "tenant-dhaka-01",
                "branch-main",
                "BRANCH_MANAGER",
                "usr-manager",
                false
        );
        cashierClient = new PosProcurementE2EQaClient(
                "tenant-dhaka-01",
                "branch-main",
                "CASHIER",
                "usr-cashier",
                false
        );
    }

    @Test
    @DisplayName("Test 1: Full procurement cycle executes and conserves balances")
    void testFullProcurementCycle() {
        PosProcurementE2EQaClient.ProcurementCycleResultDto res = managerClient.executeFullProcurementCycle(
                "sup-square-01",
                "prod-napa-500",
                100,
                new BigDecimal("25.0000")
        );

        assertNotNull(res);
        assertEquals("PASSED", res.status);
        assertEquals(100, res.orderedQuantity);
        assertEquals(100, res.receivedQuantity);
        assertEquals(new BigDecimal("2500.0000"), res.totalCost);
        assertEquals(BigDecimal.ZERO, res.finalSupplierBalance);
        assertEquals(64, res.verificationHash.length());
    }

    @Test
    @DisplayName("Test 2: Duplicate receipt conflict is blocked")
    void testDuplicateReceiptConflict() {
        PosProcurementE2EQaClient.ProcurementCycleResultDto res = managerClient.executeFullProcurementCycle(
                "sup-square-01",
                "prod-napa-500",
                50,
                new BigDecimal("10.0000")
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                managerClient.verifyDuplicateConflict(res.poNumber)
        );
        assertTrue(ex.getMessage().contains("ERR_DUPLICATE_RECEIPT_CONFLICT"));
    }

    @Test
    @DisplayName("Test 3: Purchase return reversal decrements lot and reduces payable")
    void testPurchaseReturnReversal() {
        PosProcurementE2EQaClient.PurchaseReturnResultDto ret = managerClient.verifyPurchaseReturnReversal(
                "lot-test-101",
                20,
                new BigDecimal("15.0000"),
                "sup-square-01"
        );

        assertNotNull(ret);
        assertEquals(80, ret.remainingLotQuantity);
        assertEquals(new BigDecimal("300.0000"), ret.refundAmount);
        assertEquals(new BigDecimal("-300.0000"), ret.supplierBalanceAfter);
    }

    @Test
    @DisplayName("Test 4: Zero or negative ordered quantity is rejected")
    void testZeroQuantityRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                managerClient.executeFullProcurementCycle(
                        "sup-square-01",
                        "prod-napa-500",
                        0,
                        new BigDecimal("25.0000")
                )
        );
        assertTrue(ex.getMessage().contains("ERR_INVALID_QUANTITY"));
    }

    @Test
    @DisplayName("Test 5: Cashier role is rejected with SecurityException")
    void testCashierRoleRejected() {
        SecurityException ex = assertThrows(SecurityException.class, () ->
                cashierClient.executeFullProcurementCycle(
                        "sup-square-01",
                        "prod-napa-500",
                        10,
                        new BigDecimal("25.0000")
                )
        );
        assertTrue(ex.getMessage().contains("ERR_STAFF_ROLE_DENIED"));
    }

    @Test
    @DisplayName("Test 6: Over return beyond lot capacity is rejected")
    void testOverReturnRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                managerClient.verifyPurchaseReturnReversal(
                        "lot-small-01",
                        150,
                        new BigDecimal("10.0000"),
                        "sup-square-01"
                )
        );
        assertTrue(ex.getMessage().contains("ERR_OVER_RETURN"));
    }
}

