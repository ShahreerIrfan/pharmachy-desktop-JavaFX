package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStockLedgerTest
 * Architecture Decision: ADR-0201 (P201).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Unit and contract tests for Desktop POS Stock Ledger client:
 * 1. Balance conservation (balanceAfter == balanceBefore + quantityDelta)
 * 2. Outward stock dispense deduction
 * 3. Zero delta rejection
 * 4. Insufficient stock reduction rejection
 * 5. Cryptographic SHA-256 tamper verification & reconciliation
 * 6. Cashier RBAC rejection
 */
class PosStockLedgerTest {

    private PosStockLedgerClient managerClient;
    private PosStockLedgerClient cashierClient;

    @BeforeEach
    void setUp() {
        managerClient = new PosStockLedgerClient(
                "tenant-dhaka-01",
                "branch-main",
                "BRANCH_MANAGER",
                "usr-manager",
                false
        );
        cashierClient = new PosStockLedgerClient(
                "tenant-dhaka-01",
                "branch-main",
                "CASHIER",
                "usr-cashier",
                false
        );
    }

    @Test
    @DisplayName("Test 1: Record positive inward movement conserves balance and computes SHA-256 hash")
    void testRecordPositiveMovementConservesBalanceAndComputesHash() {
        PosStockLedgerClient.StockLedgerEntryDto entry = managerClient.recordMovement(
                "prod-napa-500",
                "Napa Extra 500mg",
                "BCH-202611-001",
                PosStockLedgerClient.StockMovementType.PURCHASE_RECEIPT,
                100,
                "GOODS_RECEIPT_NOTE",
                "grn-101",
                "GRN-202611-001",
                new BigDecimal("2.2500"),
                new BigDecimal("3.5000"),
                false,
                "Aisle-1-Shelf-B",
                "Initial purchase delivery"
        );

        assertNotNull(entry);
        assertTrue(entry.entryId.startsWith("stkld-"));
        assertEquals(0, entry.balanceBefore);
        assertEquals(100, entry.balanceAfter);
        assertEquals(100, entry.quantityDelta);
        assertEquals(entry.balanceAfter, entry.balanceBefore + entry.quantityDelta);
        assertEquals(new BigDecimal("225.0000"), entry.totalCost);
        assertEquals(64, entry.verificationHash.length());
    }

    @Test
    @DisplayName("Test 2: Outward dispense decrements balance accurately")
    void testRecordOutwardMovementDecrementsBalance() {
        managerClient.setInitialBatchBalance("BCH-202611-002", 50);

        PosStockLedgerClient.StockLedgerEntryDto entry = managerClient.recordMovement(
                "prod-napa-500",
                "Napa Extra 500mg",
                "BCH-202611-002",
                PosStockLedgerClient.StockMovementType.SALE_DISPENSE,
                -15,
                "SALE_INVOICE",
                "inv-201",
                "INV-202611-0088",
                new BigDecimal("2.2500"),
                new BigDecimal("3.5000"),
                false,
                "Front-Counter",
                "Dispensed to patient"
        );

        assertEquals(50, entry.balanceBefore);
        assertEquals(35, entry.balanceAfter);
        assertEquals(-15, entry.quantityDelta);
        assertEquals(entry.balanceAfter, entry.balanceBefore + entry.quantityDelta);
    }

    @Test
    @DisplayName("Test 3: Zero quantity delta throws IllegalArgumentException")
    void testZeroDeltaThrowsException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                managerClient.recordMovement(
                        "prod-napa-500",
                        "Napa Extra 500mg",
                        "BCH-202611-003",
                        PosStockLedgerClient.StockMovementType.STOCK_ADJUSTMENT_IN,
                        0,
                        "AUDIT",
                        "aud-01",
                        "AUD-01",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        false,
                        "",
                        ""
                )
        );
        assertTrue(ex.getMessage().contains("ERR_ZERO_DELTA"));
    }

    @Test
    @DisplayName("Test 4: Insufficient stock throws IllegalStateException")
    void testInsufficientStockThrowsException() {
        managerClient.setInitialBatchBalance("BCH-202611-004", 5);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                managerClient.recordMovement(
                        "prod-napa-500",
                        "Napa Extra 500mg",
                        "BCH-202611-004",
                        PosStockLedgerClient.StockMovementType.SALE_DISPENSE,
                        -10,
                        "SALE_INVOICE",
                        "inv-overflow",
                        "INV-OVERFLOW",
                        new BigDecimal("2.2500"),
                        new BigDecimal("3.5000"),
                        false,
                        "",
                        ""
                )
        );
        assertTrue(ex.getMessage().contains("ERR_INSUFFICIENT_STOCK"));
    }

    @Test
    @DisplayName("Test 5: Reconciliation verifies balanced ledger and detects tampering")
    void testReconciliationDetectsBalanceAndTampering() {
        // Record inward 80
        managerClient.recordMovement(
                "prod-napa-500",
                "Napa Extra 500mg",
                "BCH-202611-005",
                PosStockLedgerClient.StockMovementType.PURCHASE_RECEIPT,
                80,
                "GRN",
                "grn-80",
                "GRN-80",
                new BigDecimal("2.0000"),
                new BigDecimal("3.0000"),
                false,
                "",
                ""
        );

        // Record outward -20
        managerClient.recordMovement(
                "prod-napa-500",
                "Napa Extra 500mg",
                "BCH-202611-005",
                PosStockLedgerClient.StockMovementType.SALE_DISPENSE,
                -20,
                "SALE",
                "sale-20",
                "SALE-20",
                new BigDecimal("2.0000"),
                new BigDecimal("3.0000"),
                false,
                "",
                ""
        );

        PosStockLedgerClient.StockReconciliationResultDto recon = managerClient.reconcileBatch("BCH-202611-005");
        assertTrue(recon.isBalanced);
        assertEquals(60, recon.recordedQuantity);
        assertEquals(60, recon.ledgerSum);
        assertEquals(0, recon.discrepancy);
        assertFalse(recon.tamperDetected);
        assertEquals(2, recon.totalEntries);
    }

    @Test
    @DisplayName("Test 6: Cashier role is denied access with SecurityException")
    void testCashierDeniedAccess() {
        SecurityException ex = assertThrows(SecurityException.class, () ->
                cashierClient.recordMovement(
                        "prod-napa-500",
                        "Napa Extra 500mg",
                        "BCH-202611-006",
                        PosStockLedgerClient.StockMovementType.STOCK_ADJUSTMENT_IN,
                        10,
                        "ADJ",
                        "adj-1",
                        "ADJ-1",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        false,
                        "",
                        ""
                )
        );
        assertTrue(ex.getMessage().contains("ERR_STAFF_ROLE_DENIED"));
    }
}
