package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStockAdjustmentTest
 * Architecture Decision: ADR-0205 (P205).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Unit and verification tests for Desktop POS stock adjustment approval flows.
 */
public class PosStockAdjustmentTest {

    private PosStockAdjustmentClient client;

    @BeforeEach
    void setUp() {
        client = new PosStockAdjustmentClient();
        client.setBatchStock("BATCH-MOX-2026-001", 50);
    }

    @Test
    @DisplayName("Nontrivial proposal, manager approval, difference events and stock updates")
    void testNontrivialAdjustmentProposalAndApproval() {
        var adj = client.proposeAdjustment(
                "ten-001",
                "br-001",
                "prod-mox",
                "Moxacil 500mg Capsule",
                "BATCH-MOX-2026-001",
                -15,
                new BigDecimal("45.00"),
                PosStockAdjustmentClient.StockAdjustmentReasonCode.EXPIRY_SPOILAGE,
                "Expired ampoules identified during quarterly stock check.",
                "DOC-EXP-2026-01",
                "user-pharmacist-1",
                "STAFF_PHARMACIST",
                false,
                false,
                false
        );

        assertTrue(adj.isNontrivial);
        assertEquals(PosStockAdjustmentClient.StockAdjustmentStatus.PENDING, adj.status);
        assertEquals(50, client.getBatchStock("BATCH-MOX-2026-001"), "Stock should not change before approval");

        var approved = client.approveAdjustment(
                adj.adjustmentId,
                "user-manager-2",
                "BRANCH_MANAGER",
                "Verified physical expiry and approved write-off."
        );

        assertEquals(PosStockAdjustmentClient.StockAdjustmentStatus.APPROVED, approved.status);
        assertEquals(35, client.getBatchStock("BATCH-MOX-2026-001"));
        assertNotNull(approved.ledgerEntryId);

        List<PosStockAdjustmentClient.StockAdjustmentDifferenceEventDto> events = client.getDifferenceEvents(adj.adjustmentId);
        assertEquals(1, events.size());
        var event = events.get(0);
        assertEquals(50, event.preAdjustmentQuantity);
        assertEquals(35, event.postAdjustmentQuantity);
        assertEquals(-15, event.quantityDelta);
        assertNotNull(event.immutableEventHash);
        assertFalse(event.immutableEventHash.isEmpty());
    }

    @Test
    @DisplayName("Maker-Checker anti-self-approval rule prevents self-approval of nontrivial adjustments")
    void testMakerCheckerAntiSelfApproval() {
        var adj = client.proposeAdjustment(
                "ten-001",
                "br-001",
                "prod-mox",
                "Moxacil 500mg Capsule",
                "BATCH-MOX-2026-001",
                -15,
                new BigDecimal("45.00"),
                PosStockAdjustmentClient.StockAdjustmentReasonCode.THEFT_PILFERAGE,
                "Unaccounted loss noted during night shift inspection.",
                null,
                "user-manager-1",
                "BRANCH_MANAGER",
                false,
                false,
                false
        );

        assertTrue(adj.isNontrivial);

        // Same manager tries to approve
        var ex = assertThrows(IllegalStateException.class, () ->
                client.approveAdjustment(adj.adjustmentId, "user-manager-1", "BRANCH_MANAGER", "Self-approval")
        );
        assertTrue(ex.getMessage().contains("ERR_MAKER_CHECKER_SELF_APPROVAL"));
    }

    @Test
    @DisplayName("Mandatory reason length, non-zero delta, and stock boundary validation")
    void testMandatoryReasonAndDeltaValidation() {
        // Reason too short (< 10 chars)
        var exShort = assertThrows(IllegalArgumentException.class, () ->
                client.proposeAdjustment(
                        "ten-001", "br-001", "prod-mox", "Moxacil", "BATCH-MOX-2026-001",
                        -5, new BigDecimal("10.00"),
                        PosStockAdjustmentClient.StockAdjustmentReasonCode.BREAKAGE_SPILLAGE,
                        "broken", null, "user-1", "STAFF_PHARMACIST", false, false, false
                )
        );
        assertTrue(exShort.getMessage().contains("ERR_MANDATORY_REASON_REQUIRED"));

        // Zero delta
        var exZero = assertThrows(IllegalArgumentException.class, () ->
                client.proposeAdjustment(
                        "ten-001", "br-001", "prod-mox", "Moxacil", "BATCH-MOX-2026-001",
                        0, new BigDecimal("10.00"),
                        PosStockAdjustmentClient.StockAdjustmentReasonCode.BREAKAGE_SPILLAGE,
                        "Proper explanation exceeding ten chars", null, "user-1", "STAFF_PHARMACIST", false, false, false
                )
        );
        assertTrue(exZero.getMessage().contains("ERR_ZERO_DELTA"));

        // Exceeding available stock (-100 when only 50 available)
        var exStock = assertThrows(IllegalArgumentException.class, () ->
                client.proposeAdjustment(
                        "ten-001", "br-001", "prod-mox", "Moxacil", "BATCH-MOX-2026-001",
                        -100, new BigDecimal("10.00"),
                        PosStockAdjustmentClient.StockAdjustmentReasonCode.BREAKAGE_SPILLAGE,
                        "Proper explanation exceeding ten chars", null, "user-1", "STAFF_PHARMACIST", false, false, false
                )
        );
        assertTrue(exStock.getMessage().contains("ERR_INSUFFICIENT_STOCK"));
    }

    @Test
    @DisplayName("Reversal workflow restores batch stock and creates offsetting difference event")
    void testReversalWorkflow() {
        var adj = client.proposeAdjustment(
                "ten-001", "br-001", "prod-mox", "Moxacil", "BATCH-MOX-2026-001",
                -10, new BigDecimal("45.00"),
                PosStockAdjustmentClient.StockAdjustmentReasonCode.DISPENSING_ERROR_REVERSAL,
                "Dispensing correction due to cashier system mix-up.",
                null, "user-1", "STAFF_PHARMACIST", false, false, false
        );
        client.approveAdjustment(adj.adjustmentId, "user-mgr-2", "BRANCH_MANAGER", "Approved");
        assertEquals(40, client.getBatchStock("BATCH-MOX-2026-001"));

        var rev = client.reverseAdjustment(
                adj.adjustmentId,
                "user-mgr-2",
                "BRANCH_MANAGER",
                "Reversal due to customer returning unopened medicine."
        );

        assertTrue(adj.isReversed);
        assertEquals(10, rev.quantityDelta);
        assertEquals(50, client.getBatchStock("BATCH-MOX-2026-001"), "Batch stock should be restored to 50");

        var revEvents = client.getDifferenceEvents(rev.adjustmentId);
        assertEquals(1, revEvents.size());
        assertEquals(10, revEvents.get(0).quantityDelta);
        assertEquals(50, revEvents.get(0).postAdjustmentQuantity);

        // Cannot reverse twice
        var ex = assertThrows(IllegalStateException.class, () ->
                client.reverseAdjustment(adj.adjustmentId, "user-mgr-2", "BRANCH_MANAGER", "Attempting double reversal")
        );
        assertTrue(ex.getMessage().contains("ERR_ALREADY_REVERSED"));
    }

    @Test
    @DisplayName("Cashier role is strictly denied from proposing adjustments")
    void testCashierRoleDenied() {
        var ex = assertThrows(SecurityException.class, () ->
                client.proposeAdjustment(
                        "ten-001", "br-001", "prod-mox", "Moxacil", "BATCH-MOX-2026-001",
                        -5, new BigDecimal("10.00"),
                        PosStockAdjustmentClient.StockAdjustmentReasonCode.BREAKAGE_SPILLAGE,
                        "Accidental drop during cashier packing.",
                        null, "user-cashier-1", "CASHIER", false, false, false
                )
        );
        assertTrue(ex.getMessage().contains("ERR_STAFF_ROLE_DENIED"));
    }

    @Test
    @DisplayName("Nontrivial flag triggered by threshold or cold-chain/narcotics/quarantine attributes")
    void testControlledColdChainNontrivialFlag() {
        // Small delta (-2 units, 20 BDT) normal item -> trivial
        assertFalse(PosStockAdjustmentClient.isNontrivialAdjustment(-2, new BigDecimal("20.00"), false, false, false));

        // Small delta (-2 units, 20 BDT) but narcotic -> nontrivial
        assertTrue(PosStockAdjustmentClient.isNontrivialAdjustment(-2, new BigDecimal("20.00"), true, false, false));

        // Small delta (-1 unit, 10 BDT) but cold-chain -> nontrivial
        assertTrue(PosStockAdjustmentClient.isNontrivialAdjustment(-1, new BigDecimal("10.00"), false, true, false));

        // Small delta (-1 unit, 10 BDT) but quarantined batch -> nontrivial
        assertTrue(PosStockAdjustmentClient.isNontrivialAdjustment(-1, new BigDecimal("10.00"), false, false, true));

        // Quantity >= 10 -> nontrivial
        assertTrue(PosStockAdjustmentClient.isNontrivialAdjustment(-10, new BigDecimal("20.00"), false, false, false));

        // Cost >= 500 -> nontrivial
        assertTrue(PosStockAdjustmentClient.isNontrivialAdjustment(-2, new BigDecimal("550.00"), false, false, false));
    }
}

