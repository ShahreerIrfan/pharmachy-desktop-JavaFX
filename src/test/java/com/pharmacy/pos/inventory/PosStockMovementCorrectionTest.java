package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStockMovementCorrectionTest
 * Architecture Decision: ADR-0217 (P217).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 */
public class PosStockMovementCorrectionTest {

    private PosStockMovementCorrectionClient client;

    @BeforeEach
    void setUp() {
        client = new PosStockMovementCorrectionClient();
    }

    @Test
    void testValidCompensatingReversal() {
        // Erroneous dispense of 10 units (-10), batch stock currently at 40
        var correction = client.executeCorrection(
                "PHARMACY_OWNER",
                "ten-united",
                "br-central",
                "stkld-orig-001",
                -10, // original delta
                40,  // current batch stock
                new BigDecimal("2.50"),
                "prod-napa-01",
                "NAP-001",
                PosStockMovementCorrectionClient.CorrectionReason.MISTYPED_QUANTITY,
                "Dispense entry mistyped. Reversing erroneous dispense."
        );

        assertNotNull(correction);
        assertEquals("stkld-orig-001", correction.getOriginalEntryId());
        assertEquals(10, correction.getReversedQuantityDelta());
        assertEquals(new BigDecimal("2.50"), correction.getUnitCostBdt());
        assertEquals(new BigDecimal("25.00"), correction.getTotalCostImpactBdt());
        assertEquals(PosStockMovementCorrectionClient.CorrectionStatus.COMPLETED, correction.getStatus());
        assertTrue(client.isReversed("stkld-orig-001"));
    }

    @Test
    void testDoubleReversalPrevention() {
        client.executeCorrection(
                "BRANCH_MANAGER",
                "ten-01",
                "br-01",
                "stkld-orig-002",
                -5,
                50,
                new BigDecimal("10.00"),
                "prod-01",
                "BAT-01",
                PosStockMovementCorrectionClient.CorrectionReason.MISTYPED_QUANTITY,
                "Legitimate correction first time."
        );

        // Attempting second reversal of same entry
        assertThrows(IllegalStateException.class, () -> client.executeCorrection(
                "BRANCH_MANAGER",
                "ten-01",
                "br-01",
                "stkld-orig-002",
                -5,
                55,
                new BigDecimal("10.00"),
                "prod-01",
                "BAT-01",
                PosStockMovementCorrectionClient.CorrectionReason.MISTYPED_QUANTITY,
                "Second duplicate correction."
        ));
    }

    @Test
    void testNegativeStockGuard() {
        // Original delta was +50 (inward receipt). Reversal will be -50.
        // Current batch stock is only 20. 20 - 50 = -30 (negative).
        assertThrows(IllegalStateException.class, () -> client.executeCorrection(
                "STAFF_PHARMACIST",
                "ten-01",
                "br-01",
                "stkld-in-001",
                50, // original delta was +50
                20, // current batch stock is only 20
                new BigDecimal("5.00"),
                "prod-01",
                "BAT-01",
                PosStockMovementCorrectionClient.CorrectionReason.DUPLICATE_POSTING,
                "Attempting reversal which exceeds physical stock."
        ));
    }

    @Test
    void testCashierRoleDenied() {
        assertThrows(SecurityException.class, () -> client.executeCorrection(
                "CASHIER",
                "ten-01",
                "br-01",
                "stkld-orig-003",
                -1,
                10,
                new BigDecimal("10.00"),
                "prod-01",
                "BAT-01",
                PosStockMovementCorrectionClient.CorrectionReason.MISTYPED_QUANTITY,
                "Cashier unauthorized correction attempt."
        ));
    }
}
