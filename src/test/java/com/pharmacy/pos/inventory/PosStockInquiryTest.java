package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStockInquiryTest
 * Architecture Decision: ADR-0203 (P203).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Tests for Stock Inquiry Client:
 * 1. Multi-batch product rollup and FEFO batch sorting.
 * 2. Decimal valuation calculation.
 * 3. Cashier branch access restriction.
 * 4. Cross-tenant isolation.
 * 5. Bangla keyword search and expiry filtering.
 */
class PosStockInquiryTest {

    private PosStockInquiryClient client;
    private final String tenantA = "ten-dhanmondi-01";
    private final String branchA1 = "br-main";
    private final String branchA2 = "br-mirpur";
    private final String tenantB = "ten-square-02";

    @BeforeEach
    void setUp() {
        client = new PosStockInquiryClient();
    }

    @Test
    @DisplayName("Test 1: Full stock rollup, available math, packaging breakdown and FEFO order")
    void testStockRollupAndFefo() {
        LocalDate today = LocalDate.now();

        // Batch 1: Active 100 units, 20 reserved -> 80 available, expires in 250 days
        client.addBatch(new PosStockInquiryClient.MockBatchRecord(
                "bat-01", tenantA, branchA1, "prod-napa-500", "Napa 500mg", "নাপা ৫০০", "Paracetamol", "TABLET",
                "BAT-ACT-01", 100, 20, today.plusDays(250), false, false,
                new BigDecimal("1.20"), new BigDecimal("1.50"), "SH-A1"
        ));

        // Batch 2: Near expiry 40 units, expires in 40 days
        client.addBatch(new PosStockInquiryClient.MockBatchRecord(
                "bat-02", tenantA, branchA1, "prod-napa-500", "Napa 500mg", "নাপা ৫০০", "Paracetamol", "TABLET",
                "BAT-NEAR-02", 40, 0, today.plusDays(40), false, false,
                new BigDecimal("1.20"), new BigDecimal("1.50"), "SH-A2"
        ));

        // Batch 3: Expired 30 units, expired 5 days ago
        client.addBatch(new PosStockInquiryClient.MockBatchRecord(
                "bat-03", tenantA, branchA1, "prod-napa-500", "Napa 500mg", "নাপা ৫০০", "Paracetamol", "TABLET",
                "BAT-EXP-03", 30, 0, today.minusDays(5), false, false,
                new BigDecimal("1.20"), new BigDecimal("1.50"), "DISP"
        ));

        // Batch 4: Quarantined 25 units
        client.addBatch(new PosStockInquiryClient.MockBatchRecord(
                "bat-04", tenantA, branchA1, "prod-napa-500", "Napa 500mg", "নাপা ৫০০", "Paracetamol", "TABLET",
                "BAT-QUAR-04", 25, 0, today.plusDays(150), true, false,
                new BigDecimal("1.20"), new BigDecimal("1.50"), "QUAR-Z1"
        ));

        PosStockInquiryClient.StockInquiryResponseDto res = client.queryStock(
                tenantA, branchA1, null, "prod-napa-500", "ALL", "BRANCH_MANAGER", branchA1
        );

        assertEquals(1, res.productsCount);
        PosStockInquiryClient.ProductStockInquiryDto prod = res.results.get(0);

        // Total = 100 + 40 + 30 + 25 = 195
        assertEquals(195, prod.totalQuantityBaseUnits);
        // Available = (100 - 20) + 40 = 120
        assertEquals(120, prod.availableQuantityBaseUnits);
        assertEquals(20, prod.reservedQuantityBaseUnits);
        assertEquals(30, prod.expiredQuantityBaseUnits);
        assertEquals(25, prod.quarantinedQuantityBaseUnits);
        assertEquals(40, prod.nearExpiryQuantityBaseUnits);

        // Packaging: 120 tablets = 1 Box, 2 Strips, 0 Pieces
        assertEquals(1, prod.packagingSummary.boxes);
        assertEquals(2, prod.packagingSummary.strips);
        assertEquals(0, prod.packagingSummary.pieces);

        // FEFO order: BAT-EXP-03 first, then BAT-NEAR-02
        assertEquals(4, prod.batches.size());
        assertEquals("BAT-EXP-03", prod.batches.get(0).batchNumber);
        assertEquals("BAT-NEAR-02", prod.batches.get(1).batchNumber);
    }

    @Test
    @DisplayName("Test 2: Decimal valuation accuracy")
    void testValuationAccuracy() {
        LocalDate today = LocalDate.now();
        client.addBatch(new PosStockInquiryClient.MockBatchRecord(
                "bat-val", tenantA, branchA1, "prod-napa", "Napa", "নাপা", "Paracetamol", "TABLET",
                "BAT-V1", 200, 0, today.plusDays(100), false, false,
                new BigDecimal("1.25"), new BigDecimal("1.50"), "SH-01"
        ));

        PosStockInquiryClient.StockInquiryResponseDto res = client.queryStock(
                tenantA, branchA1, null, "prod-napa", "ALL", "PHARMACY_OWNER", branchA1
        );

        PosStockInquiryClient.ProductStockInquiryDto prod = res.results.get(0);
        // Cost: 200 * 1.25 = 250.00
        assertEquals(0, new BigDecimal("250.00").compareTo(prod.totalCostValuationBdt));
        // MRP: 200 * 1.50 = 300.00
        assertEquals(0, new BigDecimal("300.00").compareTo(prod.totalMrpValuationBdt));
    }

    @Test
    @DisplayName("Test 3: Cashier branch access restriction")
    void testCashierBranchRestriction() {
        assertThrows(SecurityException.class, () ->
                client.queryStock(tenantA, branchA2, null, null, "ALL", "CASHIER", branchA1)
        );
    }

    @Test
    @DisplayName("Test 4: Cross-tenant isolation")
    void testCrossTenantIsolation() {
        LocalDate today = LocalDate.now();
        client.addBatch(new PosStockInquiryClient.MockBatchRecord(
                "bat-iso", tenantA, branchA1, "prod-napa", "Napa", "নাপা", "Paracetamol", "TABLET",
                "BAT-ISO-1", 50, 0, today.plusDays(100), false, false,
                new BigDecimal("1.20"), new BigDecimal("1.50"), "SH-01"
        ));

        // Tenant B queries Tenant A's branch
        PosStockInquiryClient.StockInquiryResponseDto res = client.queryStock(
                tenantB, branchA1, null, null, "ALL", "BRANCH_MANAGER", branchA1
        );
        assertEquals(0, res.productsCount);
    }

    @Test
    @DisplayName("Test 5: Bangla keyword search and expiry filtering")
    void testSearchAndFilters() {
        LocalDate today = LocalDate.now();
        client.addBatch(new PosStockInquiryClient.MockBatchRecord(
                "bat-01", tenantA, branchA1, "p1", "Napa Extra", "নাপা এক্সট্রা", "Paracetamol", "TABLET",
                "B1", 50, 0, today.plusDays(120), false, false,
                new BigDecimal("2.00"), new BigDecimal("2.50"), "SH-1"
        ));
        client.addBatch(new PosStockInquiryClient.MockBatchRecord(
                "bat-02", tenantA, branchA1, "p2", "Ace Plus", "এইস প্লাস", "Paracetamol", "TABLET",
                "B2", 30, 0, today.minusDays(2), false, false,
                new BigDecimal("2.00"), new BigDecimal("2.50"), "SH-2"
        ));

        // Search "এক্সট্রা"
        PosStockInquiryClient.StockInquiryResponseDto resSearch = client.queryStock(
                tenantA, branchA1, "এক্সট্রা", null, "ALL", "BRANCH_MANAGER", branchA1
        );
        assertEquals(1, resSearch.productsCount);
        assertEquals("Napa Extra", resSearch.results.get(0).brandName);

        // Filter EXPIRED
        PosStockInquiryClient.StockInquiryResponseDto resExp = client.queryStock(
                tenantA, branchA1, null, null, "EXPIRED", "BRANCH_MANAGER", branchA1
        );
        assertEquals(1, resExp.productsCount);
        assertEquals("Ace Plus", resExp.results.get(0).brandName);
    }
}
