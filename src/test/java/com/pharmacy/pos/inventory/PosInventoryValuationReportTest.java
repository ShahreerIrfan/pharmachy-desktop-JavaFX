package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosInventoryValuationReportTest
 * Architecture Decision: ADR-0216 (P216).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 */
public class PosInventoryValuationReportTest {

    private PosInventoryValuationReportClient client;

    @BeforeEach
    void setUp() {
        client = new PosInventoryValuationReportClient();
    }

    @Test
    void testValidValuationReportGenerationAndBalance() {
        var sellable = new PosInventoryValuationReportClient.ValuationLineItem(
                "prod-napa-01",
                "Napa Extra 500mg",
                "NAP-001",
                PosInventoryValuationReportClient.InventoryCategory.SELLABLE,
                50,
                new BigDecimal("2.50"),
                false,
                "Active sellable stock"
        );

        var quarantined = new PosInventoryValuationReportClient.ValuationLineItem(
                "prod-ins-01",
                "Mixtard 30 100IU",
                "INS-002",
                PosInventoryValuationReportClient.InventoryCategory.QUARANTINED,
                20,
                new BigDecimal("420.00"),
                true,
                "Suspected cold-chain breach"
        );

        var expired = new PosInventoryValuationReportClient.ValuationLineItem(
                "prod-napa-01",
                "Napa Extra 500mg",
                "NAP-EXP",
                PosInventoryValuationReportClient.InventoryCategory.EXPIRED,
                10,
                new BigDecimal("2.50"),
                false,
                "Expired batch"
        );

        var inTransit = new PosInventoryValuationReportClient.ValuationLineItem(
                "prod-ins-01",
                "Mixtard 30 100IU",
                "INS-TRF",
                PosInventoryValuationReportClient.InventoryCategory.IN_TRANSIT,
                15,
                new BigDecimal("420.00"),
                true,
                "Interbranch transfer in-transit"
        );

        var report = client.generateReport(
                "PHARMACY_OWNER",
                "ten-united",
                "br-central",
                PosInventoryValuationReportClient.ValuationCostingRule.FEFO_LOT_COST,
                List.of(sellable, quarantined, expired, inTransit)
        );

        assertNotNull(report);
        assertEquals(80, report.getTotalOnHandUnits());
        assertEquals(new BigDecimal("8550.00"), report.getTotalOnHandValueBdt());
        assertEquals(50, report.getSellableUnits());
        assertEquals(new BigDecimal("125.00"), report.getSellableValueBdt());
        assertEquals(20, report.getQuarantinedUnits());
        assertEquals(new BigDecimal("8400.00"), report.getQuarantinedValueBdt());
        assertEquals(10, report.getExpiredUnits());
        assertEquals(new BigDecimal("25.00"), report.getExpiredValueBdt());
        assertEquals(15, report.getInTransitUnits());
        assertEquals(new BigDecimal("6300.00"), report.getInTransitValueBdt());
        assertEquals(20, report.getColdChainUnits());
        assertEquals(new BigDecimal("8400.00"), report.getColdChainValueBdt());

        assertTrue(report.isMathematicallyBalanced());
        assertEquals(0, report.getDiscrepancyUnits());
        assertEquals(new BigDecimal("0.00"), report.getDiscrepancyValueBdt());
        assertTrue(client.verifyIntegrity(report));
    }

    @Test
    void testIntegrityTamperingDetection() {
        var item = new PosInventoryValuationReportClient.ValuationLineItem(
                "prod-01",
                "Amoxicillin 500mg",
                "BAT-01",
                PosInventoryValuationReportClient.InventoryCategory.SELLABLE,
                100,
                new BigDecimal("10.00"),
                false,
                "Sellable stock"
        );

        var report = client.generateReport(
                "BRANCH_MANAGER",
                "ten-01",
                "br-01",
                PosInventoryValuationReportClient.ValuationCostingRule.FIFO_LOT_COST,
                List.of(item)
        );

        assertTrue(client.verifyIntegrity(report));

        // Create tampered line item with forged units
        var tamperedItem = new PosInventoryValuationReportClient.ValuationLineItem(
                "prod-01",
                "Amoxicillin 500mg",
                "BAT-01",
                PosInventoryValuationReportClient.InventoryCategory.SELLABLE,
                999, // tampered
                new BigDecimal("10.00"),
                false,
                "Sellable stock"
        );

        var tamperedReport = new PosInventoryValuationReportClient.ValuationReport(
                report.getReportId(),
                report.getTenantId(),
                report.getBranchId(),
                report.getAsOfTimestamp(),
                report.getCostingRule(),
                999,
                new BigDecimal("9990.00"),
                999,
                new BigDecimal("9990.00"),
                0,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                true,
                0,
                BigDecimal.ZERO,
                report.getSha256Checksum(), // original untampered hash
                List.of(tamperedItem)
        );

        assertFalse(client.verifyIntegrity(tamperedReport));
    }

    @Test
    void testCashierRoleDenied() {
        assertThrows(SecurityException.class, () -> client.generateReport(
                "CASHIER",
                "ten-01",
                "br-01",
                PosInventoryValuationReportClient.ValuationCostingRule.FEFO_LOT_COST,
                List.of()
        ));
    }

    @Test
    void testDiscrepancyDetection() {
        // Line with mismatch on purpose
        var item = new PosInventoryValuationReportClient.ValuationLineItem(
                "prod-01",
                "Test Item",
                "BAT-01",
                PosInventoryValuationReportClient.InventoryCategory.SELLABLE,
                50,
                new BigDecimal("10.00"),
                false,
                "Sellable"
        );

        var report = client.generateReport(
                "STAFF_PHARMACIST",
                "ten-01",
                "br-01",
                PosInventoryValuationReportClient.ValuationCostingRule.WEIGHTED_AVERAGE,
                List.of(item)
        );

        assertEquals(0, report.getDiscrepancyUnits());
        assertTrue(report.isMathematicallyBalanced());
    }
}
