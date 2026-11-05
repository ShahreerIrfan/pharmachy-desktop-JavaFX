package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStockValuationClientTest
 * Architecture Decision: ADR-0179 (P179).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 */
public class PosStockValuationClientTest {

    private Map<String, PosStockValuationClient.ValuationSnapshotDto> snapshotStore;
    private PosStockValuationClient managerClient;
    private PosStockValuationClient cashierClient;

    private List<PosStockValuationClient.CostLotItem> testLots;
    private List<PosStockValuationClient.SaleAllocationItem> testAllocations;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        snapshotStore = new HashMap<>();
        today = LocalDate.now();

        managerClient = new PosStockValuationClient(
                "tenant-dhaka", "branch-banani", "BRANCH_MANAGER", "mgr_001", false, snapshotStore
        );
        cashierClient = new PosStockValuationClient(
                "tenant-dhaka", "branch-banani", "CASHIER", "csh_001", false, snapshotStore
        );

        testLots = new ArrayList<>();

        // Lot 1: Napa Extra, 10 days old (Bucket 0-30), 100 units @ 2.0000 BDT, MRP 3.0000 BDT
        testLots.add(new PosStockValuationClient.CostLotItem(
                "lot-01", "tenant-dhaka", "branch-banani", "prod-napa",
                "Napa Extra 500mg", "Paracetamol", "Tablet",
                today.minusDays(10), 100, new BigDecimal("2.0000"), new BigDecimal("3.0000")
        ));

        // Lot 2: Napa Extra, 45 days old (Bucket 31-60), 50 units @ 2.2000 BDT, MRP 3.0000 BDT
        testLots.add(new PosStockValuationClient.CostLotItem(
                "lot-02", "tenant-dhaka", "branch-banani", "prod-napa",
                "Napa Extra 500mg", "Paracetamol", "Tablet",
                today.minusDays(45), 50, new BigDecimal("2.2000"), new BigDecimal("3.0000")
        ));

        // Lot 3: Seclo 20mg, 100 days old (Bucket 91-180), 40 units @ 5.0000 BDT, MRP 7.0000 BDT
        testLots.add(new PosStockValuationClient.CostLotItem(
                "lot-03", "tenant-dhaka", "branch-banani", "prod-seclo",
                "Seclo 20mg", "Omeprazole", "Capsule",
                today.minusDays(100), 40, new BigDecimal("5.0000"), new BigDecimal("7.0000")
        ));

        // Sale Allocations
        testAllocations = new ArrayList<>();
        // Sale 1: 20 units of Napa sold for 60.00 BDT, cost 40.00 BDT, gross profit 20.00 BDT
        testAllocations.add(new PosStockValuationClient.SaleAllocationItem(
                "alloc-01", "tenant-dhaka", "branch-banani", "prod-napa",
                today.minusDays(2), 20, new BigDecimal("60.0000"), new BigDecimal("40.0000"), new BigDecimal("20.0000")
        ));
    }

    @Test
    @DisplayName("Test 1: Manager calculates valid FIFO actual valuation, MRP retail valuation, and aging buckets")
    void testCalculateValuationAndAgingSuccess() {
        var res = managerClient.calculateValuation(testLots, testAllocations, today, "FIFO_ACTUAL");
        assertTrue(res.success);
        assertNotNull(res.data);

        var data = res.data;
        // Total units = 100 + 50 + 40 = 190 units
        assertEquals(190, data.totalQuantityBaseUnits);
        assertEquals(2, data.totalProductsCount);

        // Cost Valuation:
        // Lot 1: 100 * 2.0000 = 200.0000
        // Lot 2: 50 * 2.2000  = 110.0000
        // Lot 3: 40 * 5.0000  = 200.0000
        // Total Cost = 510.0000 BDT
        assertEquals(new BigDecimal("510.0000"), data.totalCostValuationBdt);

        // Retail Valuation:
        // Napa: 150 * 3.0000 = 450.0000
        // Seclo: 40 * 7.0000 = 280.0000
        // Total Retail = 730.0000 BDT
        assertEquals(new BigDecimal("730.0000"), data.totalRetailValuationBdt);

        // Potential Gross Margin = 730 - 510 = 220.0000 BDT
        // Margin % = (220 / 730) * 100 = 30.14%
        assertEquals(new BigDecimal("220.0000"), data.potentialGrossMarginBdt);
        assertEquals(new BigDecimal("30.14"), data.potentialMarginPct);

        // Verify Aging Buckets
        var aging = data.agingSummary;
        // Bucket 0-30: Lot 1 (100 units, 200.0000 BDT)
        assertEquals(100, aging.get("days_0_to_30").quantityBaseUnits);
        assertEquals(new BigDecimal("200.0000"), aging.get("days_0_to_30").costValuationBdt);

        // Bucket 31-60: Lot 2 (50 units, 110.0000 BDT)
        assertEquals(50, aging.get("days_31_to_60").quantityBaseUnits);
        assertEquals(new BigDecimal("110.0000"), aging.get("days_31_to_60").costValuationBdt);

        // Bucket 91-180: Lot 3 (40 units, 200.0000 BDT)
        assertEquals(40, aging.get("days_91_to_180").quantityBaseUnits);
        assertEquals(new BigDecimal("200.0000"), aging.get("days_91_to_180").costValuationBdt);
    }

    @Test
    @DisplayName("Test 2: Realized margin is derived accurately from immutable lot allocations")
    void testRealizedMarginDerivation() {
        var res = managerClient.calculateValuation(testLots, testAllocations, today, "FIFO_ACTUAL");
        assertTrue(res.success);

        var realized = res.data.realizedMargin;
        assertEquals(1, realized.allocationsCount);
        assertEquals(new BigDecimal("60.0000"), realized.totalRevenueBdt);
        assertEquals(new BigDecimal("40.0000"), realized.totalCogsBdt);
        assertEquals(new BigDecimal("20.0000"), realized.realizedGrossProfitBdt);
        // Realized Margin % = (20 / 60) * 100 = 33.33%
        assertEquals(new BigDecimal("33.33"), realized.realizedMarginPct);
    }

    @Test
    @DisplayName("Test 3: Cashier role is strictly denied access to valuation metrics")
    void testCashierAccessDenied() {
        var res = cashierClient.calculateValuation(testLots, testAllocations, today, "FIFO_ACTUAL");
        assertFalse(res.success);
        assertEquals("ERR_PERMISSION_DENIED", res.errorCode);
        assertNotNull(res.messageEn);
        assertNotNull(res.messageBn);
    }

    @Test
    @DisplayName("Test 4: Point-in-time valuation snapshot creation and audit persistence")
    void testCreateValuationSnapshot() {
        var res = managerClient.createSnapshot(
                testLots, testAllocations, today, "FIFO_ACTUAL", "End-of-month financial audit"
        );
        assertTrue(res.success);
        assertNotNull(res.data);

        var snap = res.data;
        assertTrue(snap.snapshotId.startsWith("valsnap-"));
        assertEquals("branch-banani", snap.branchId);
        assertEquals(190, snap.totalQuantityBaseUnits);
        assertEquals(new BigDecimal("510.0000"), snap.totalCostValuationBdt);
        assertEquals(new BigDecimal("730.0000"), snap.totalRetailValuationBdt);
        assertEquals("End-of-month financial audit", snap.notes);

        // Verify stored in snapshotStore
        assertTrue(snapshotStore.containsKey(snap.snapshotId));
    }
}

