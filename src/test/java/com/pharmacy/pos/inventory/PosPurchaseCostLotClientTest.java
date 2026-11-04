package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosPurchaseCostLotClientTest
 * Architecture Decision: ADR-0167 (P167).
 * Unit tests verifying purchase cost lot separation, FIFO COGS derivation, and lot reversals.
 */
public class PosPurchaseCostLotClientTest {

    @Test
    @DisplayName("Should allocate COGS from a single lot when remaining quantity is sufficient")
    void testAllocateCogs_SingleLotSufficient_Success() {
        PosPurchaseCostLotClient.CostLotRecord lot = new PosPurchaseCostLotClient.CostLotRecord(
                "pcl-001", "ten-1", "br-1", "prod-1", "Napa Extra", "bat-001", "B-101",
                "sup-1", "Beximco Dist", "PO-01", "GRN-01",
                LocalDate.of(2026, 1, 10), new BigDecimal("2.1000"), 100, 100, "ACTIVE", true
        );

        PosPurchaseCostLotClient.CogsAllocationResult res = PosPurchaseCostLotClient.allocateCogs(
                Collections.singletonList(lot), 40
        );

        assertTrue(res.isSuccessful);
        assertNull(res.errorCode);
        assertEquals(40, res.totalUnits);
        assertEquals(new BigDecimal("84.0000"), res.totalCogs);
        assertEquals(new BigDecimal("2.1000"), res.effectiveUnitCogs);
        assertEquals(60, lot.remainingQuantity);
        assertEquals("ACTIVE", lot.status);
    }

    @Test
    @DisplayName("Should allocate multi-tier FIFO across distinct cost lots of identical batch with exact unblended COGS")
    void testAllocateCogs_MultiLotFifoAllocation_CalculatesExactCogs() {
        // Lot 1 received on Jan 10 @ 2.1000 BDT (100 units)
        PosPurchaseCostLotClient.CostLotRecord lot1 = new PosPurchaseCostLotClient.CostLotRecord(
                "pcl-001", "ten-1", "br-1", "prod-1", "Napa Extra", "bat-001", "B-101",
                "sup-1", "Beximco Dist", "PO-01", "GRN-01",
                LocalDate.of(2026, 1, 10), new BigDecimal("2.1000"), 100, 100, "ACTIVE", true
        );

        // Lot 2 received on Jan 25 @ 2.3500 BDT (50 units) - same batch B-101!
        PosPurchaseCostLotClient.CostLotRecord lot2 = new PosPurchaseCostLotClient.CostLotRecord(
                "pcl-002", "ten-1", "br-1", "prod-1", "Napa Extra", "bat-001", "B-101",
                "sup-2", "Square Dist", "PO-02", "GRN-02",
                LocalDate.of(2026, 1, 25), new BigDecimal("2.3500"), 50, 50, "ACTIVE", true
        );

        List<PosPurchaseCostLotClient.CostLotRecord> lots = Arrays.asList(lot2, lot1); // Unsorted input

        // Request 120 units: 100 from Lot 1 (210.00 BDT) + 20 from Lot 2 (47.00 BDT) = 257.00 BDT
        PosPurchaseCostLotClient.CogsAllocationResult res = PosPurchaseCostLotClient.allocateCogs(lots, 120);

        assertTrue(res.isSuccessful);
        assertEquals(120, res.totalUnits);
        assertEquals(new BigDecimal("257.0000"), res.totalCogs);
        assertEquals(new BigDecimal("2.1417"), res.effectiveUnitCogs);

        assertEquals(0, lot1.remainingQuantity);
        assertEquals("DEPLETED", lot1.status);
        assertEquals(30, lot2.remainingQuantity);
        assertEquals("ACTIVE", lot2.status);

        assertEquals(2, res.allocations.size());
        assertEquals(100, res.allocations.get(0).unitsAllocated);
        assertEquals(new BigDecimal("210.0000"), res.allocations.get(0).lotCogs);
        assertEquals(20, res.allocations.get(1).unitsAllocated);
        assertEquals(new BigDecimal("47.0000"), res.allocations.get(1).lotCogs);
    }

    @Test
    @DisplayName("Should fail when total remaining quantity in active lots is insufficient")
    void testAllocateCogs_InsufficientStock_Fails() {
        PosPurchaseCostLotClient.CostLotRecord lot = new PosPurchaseCostLotClient.CostLotRecord(
                "pcl-001", "ten-1", "br-1", "prod-1", "Napa Extra", "bat-001", "B-101",
                "sup-1", "Beximco Dist", "PO-01", "GRN-01",
                LocalDate.of(2026, 1, 10), new BigDecimal("2.1000"), 50, 20, "ACTIVE", true
        );

        PosPurchaseCostLotClient.CogsAllocationResult res = PosPurchaseCostLotClient.allocateCogs(
                Collections.singletonList(lot), 25
        );

        assertFalse(res.isSuccessful);
        assertEquals("ERR_INSUFFICIENT_COST_LOT_STOCK", res.errorCode);
        assertTrue(res.messageBn.contains("পর্যাপ্ত স্টক নেই"));
        assertEquals(20, lot.remainingQuantity); // Unchanged
    }

    @Test
    @DisplayName("Should skip depleted or inactive purchase lots")
    void testAllocateCogs_SkipsDepletedOrInactiveLots() {
        PosPurchaseCostLotClient.CostLotRecord depletedLot = new PosPurchaseCostLotClient.CostLotRecord(
                "pcl-old", "ten-1", "br-1", "prod-1", "Napa Extra", "bat-001", "B-101",
                "sup-1", "Beximco Dist", "PO-00", "GRN-00",
                LocalDate.of(2025, 12, 1), new BigDecimal("1.9000"), 50, 0, "DEPLETED", true
        );

        PosPurchaseCostLotClient.CostLotRecord activeLot = new PosPurchaseCostLotClient.CostLotRecord(
                "pcl-act", "ten-1", "br-1", "prod-1", "Napa Extra", "bat-001", "B-101",
                "sup-1", "Beximco Dist", "PO-01", "GRN-01",
                LocalDate.of(2026, 1, 10), new BigDecimal("2.1000"), 50, 50, "ACTIVE", true
        );

        PosPurchaseCostLotClient.CogsAllocationResult res = PosPurchaseCostLotClient.allocateCogs(
                Arrays.asList(depletedLot, activeLot), 10
        );

        assertTrue(res.isSuccessful);
        assertEquals(1, res.allocations.size());
        assertEquals("pcl-act", res.allocations.get(0).lotId);
        assertEquals(40, activeLot.remainingQuantity);
    }

    @Test
    @DisplayName("Should accurately reverse allocations, restore remaining units, and re-activate depleted lot")
    void testReverseAllocation_RestoresLotStockAndReactivatesDepleted() {
        PosPurchaseCostLotClient.CostLotRecord lot = new PosPurchaseCostLotClient.CostLotRecord(
                "pcl-rev", "ten-1", "br-1", "prod-1", "Napa Extra", "bat-001", "B-101",
                "sup-1", "Beximco Dist", "PO-01", "GRN-01",
                LocalDate.of(2026, 1, 10), new BigDecimal("2.1000"), 50, 0, "DEPLETED", true
        );

        PosPurchaseCostLotClient.LotAllocation alloc = new PosPurchaseCostLotClient.LotAllocation(
                "pcl-rev", "bat-001", "B-101", 15, new BigDecimal("2.1000"), new BigDecimal("31.5000")
        );

        PosPurchaseCostLotClient.LotReversalResult res = PosPurchaseCostLotClient.reverseAllocation(
                Collections.singletonList(lot), Collections.singletonList(alloc), "Customer Return"
        );

        assertTrue(res.isSuccessful);
        assertEquals(15, res.totalRestoredUnits);
        assertEquals(new BigDecimal("31.5000"), res.totalRestoredCogs);
        assertEquals(15, lot.remainingQuantity);
        assertEquals("ACTIVE", lot.status);
    }

    @Test
    @DisplayName("Should accurately calculate total inventory asset valuation")
    void testComputeInventoryValuation_SumsExactCostTimesRemaining() {
        PosPurchaseCostLotClient.CostLotRecord lot1 = new PosPurchaseCostLotClient.CostLotRecord(
                "pcl-v1", "ten-1", "br-1", "prod-1", "Napa Extra", "bat-001", "B-101",
                "sup-1", "Beximco", "PO-01", "GRN-01",
                LocalDate.of(2026, 1, 10), new BigDecimal("2.0000"), 100, 50, "ACTIVE", true
        ); // 50 * 2.0000 = 100.0000

        PosPurchaseCostLotClient.CostLotRecord lot2 = new PosPurchaseCostLotClient.CostLotRecord(
                "pcl-v2", "ten-1", "br-1", "prod-1", "Napa Extra", "bat-001", "B-101",
                "sup-2", "Square", "PO-02", "GRN-02",
                LocalDate.of(2026, 1, 20), new BigDecimal("2.5000"), 100, 40, "ACTIVE", true
        ); // 40 * 2.5000 = 100.0000

        BigDecimal valuation = PosPurchaseCostLotClient.computeInventoryValuation(Arrays.asList(lot1, lot2));
        assertEquals(new BigDecimal("200.0000"), valuation);
    }
}

