package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PosSaleLotAllocationClientTest {

    private PosSaleLotAllocationClient.LotAllocationItem alloc1;
    private PosSaleLotAllocationClient.LotAllocationItem alloc2;

    @BeforeEach
    void setUp() {
        // Allocation 1: 50 units sold at 3.00, purchase cost 2.00
        alloc1 = new PosSaleLotAllocationClient.LotAllocationItem(
                "sall-001",
                "inv-101",
                "INV-2026-001",
                "Rahim Ahmed",
                "+8801711111111",
                "prod-napa-extra",
                "Napa Extra 500mg",
                "bat-001",
                "BX-2026-A",
                "pcl-001",
                50,
                0,
                new BigDecimal("3.00"),
                new BigDecimal("2.00"),
                "COMMITTED");

        // Allocation 2: 20 units sold at 3.00, purchase cost 2.20
        alloc2 = new PosSaleLotAllocationClient.LotAllocationItem(
                "sall-002",
                "inv-102",
                "INV-2026-002",
                "Karim Uddin",
                "+8801722222222",
                "prod-napa-extra",
                "Napa Extra 500mg",
                "bat-002",
                "BX-2026-B",
                "pcl-002",
                20,
                0,
                new BigDecimal("3.00"),
                new BigDecimal("2.20"),
                "COMMITTED");
    }

    @Test
    @DisplayName("1. Derives exact unblended gross profit and margin percentage per lot allocation")
    void shouldDeriveExactUnblendedGrossProfitAndMargin() {
        // Alloc 1: Revenue = 50*3.00 = 150.00, COGS = 50*2.00 = 100.00, Profit = 50.00, Margin = 33.33%
        assertEquals(new BigDecimal("150.00"), alloc1.totalSellingPrice);
        assertEquals(new BigDecimal("100.00"), alloc1.totalCost);
        assertEquals(new BigDecimal("50.00"), alloc1.grossProfit);
        assertEquals(new BigDecimal("33.33"), alloc1.profitMarginPct);

        // Alloc 2: Revenue = 20*3.00 = 60.00, COGS = 20*2.20 = 44.00, Profit = 16.00, Margin = 26.67%
        assertEquals(new BigDecimal("60.00"), alloc2.totalSellingPrice);
        assertEquals(new BigDecimal("44.00"), alloc2.totalCost);
        assertEquals(new BigDecimal("16.00"), alloc2.grossProfit);
        assertEquals(new BigDecimal("26.67"), alloc2.profitMarginPct);
    }

    @Test
    @DisplayName("2. Processes partial and full returns, strictly preventing excess returns")
    void shouldProcessReturnsAndBlockExcessReturns() {
        // 1. Partial return of 20 units out of 50
        PosSaleLotAllocationClient.ReturnResult partialRes =
                PosSaleLotAllocationClient.processReturn(alloc1, 20);

        assertTrue(partialRes.isSuccessful);
        assertEquals(20, partialRes.returnedUnits);
        assertEquals(new BigDecimal("60.00"), partialRes.refundAmount); // 20 * 3.00
        assertEquals(new BigDecimal("40.00"), partialRes.refundedCogs); // 20 * 2.00
        assertEquals(20, alloc1.returnedQuantityBaseUnits);
        assertEquals("PARTIALLY_RETURNED", alloc1.status);
        assertEquals(30, alloc1.getNetRetainedUnits());

        // 2. Excess return rejection: trying to return 35 when only 30 remain
        PosSaleLotAllocationClient.ReturnResult excessRes =
                PosSaleLotAllocationClient.processReturn(alloc1, 35);

        assertFalse(excessRes.isSuccessful);
        assertEquals("ERR_RETURN_EXCEEDS_DISPENSED", excessRes.errorCode);
        assertEquals(20, alloc1.returnedQuantityBaseUnits);

        // 3. Final return of remaining 30 units -> becomes FULLY_RETURNED
        PosSaleLotAllocationClient.ReturnResult fullRes =
                PosSaleLotAllocationClient.processReturn(alloc1, 30);

        assertTrue(fullRes.isSuccessful);
        assertEquals(50, alloc1.returnedQuantityBaseUnits);
        assertEquals(0, alloc1.getNetRetainedUnits());
        assertEquals("FULLY_RETURNED", alloc1.status);
    }

    @Test
    @DisplayName("3. Traces statutory DGDA recall patient exposure across invoices and customers")
    void shouldTraceRecallExposureAcrossInvoicesAndCustomers() {
        // Another allocation with the same batch bat-001 sold to another patient
        PosSaleLotAllocationClient.LotAllocationItem alloc3 = new PosSaleLotAllocationClient.LotAllocationItem(
                "sall-003",
                "inv-103",
                "INV-2026-003",
                "Nasima Begum",
                "+8801733333333",
                "prod-napa-extra",
                "Napa Extra 500mg",
                "bat-001", // Same batch
                "BX-2026-A",
                "pcl-001",
                30,
                0,
                new BigDecimal("3.00"),
                new BigDecimal("2.00"),
                "COMMITTED");

        List<PosSaleLotAllocationClient.LotAllocationItem> allAllocations = List.of(alloc1, alloc2, alloc3);

        PosSaleLotAllocationClient.RecallImpactResult recallTrace =
                PosSaleLotAllocationClient.traceRecallImpact(allAllocations, "bat-001");

        assertEquals("bat-001", recallTrace.batchId);
        assertEquals(80, recallTrace.totalDispensedUnits); // 50 + 30
        assertEquals(0, recallTrace.totalReturnedUnits);
        assertEquals(80, recallTrace.netPatientExposureUnits);
        assertEquals(2, recallTrace.affectedInvoicesCount); // inv-101 and inv-103
        assertEquals(2, recallTrace.affectedCustomers.size());
        assertEquals("Rahim Ahmed", recallTrace.affectedCustomers.get(0).customerName);
        assertEquals("Nasima Begum", recallTrace.affectedCustomers.get(1).customerName);
    }

    @Test
    @DisplayName("4. Aggregates profit analytics across multiple allocations")
    void shouldAggregateProfitAnalyticsAcrossAllocations() {
        List<PosSaleLotAllocationClient.LotAllocationItem> list = List.of(alloc1, alloc2);

        PosSaleLotAllocationClient.ProfitAnalyticsResult analytics =
                PosSaleLotAllocationClient.computeProfitAnalytics(list);

        // Revenue = 150 + 60 = 210.00
        assertEquals(new BigDecimal("210.00"), analytics.totalRevenue);
        // COGS = 100 + 44 = 144.00
        assertEquals(new BigDecimal("144.00"), analytics.totalCogs);
        // Profit = 210 - 144 = 66.00
        assertEquals(new BigDecimal("66.00"), analytics.grossProfit);
        // Margin = 66 / 210 * 100 = 31.43%
        assertEquals(new BigDecimal("31.43"), analytics.profitMarginPct);
        assertEquals(70, analytics.totalUnitsSold);
        assertEquals(2, analytics.allocationsCount);
    }
}

