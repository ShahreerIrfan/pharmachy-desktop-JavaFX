package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosBarcodeInventoryTest
 * Architecture Decision: ADR-0218 (P218).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 */
public class PosBarcodeInventoryTest {

    private PosBarcodeInventoryClient client;

    @BeforeEach
    void setUp() {
        client = new PosBarcodeInventoryClient();
    }

    @Test
    void testReceiveWorkflowPrompts() {
        var ambient = client.receive("Napa Extra 500mg", "89411001", false);
        assertTrue(ambient.isSuccess());
        assertTrue(ambient.getOperatorPromptEn().contains("Scan destination storage"));
        assertTrue(ambient.getOperatorPromptBn().contains("স্টোরেজ"));

        var coldChain = client.receive("Mixtard 30 100IU", "89422001", true);
        assertTrue(coldChain.isSuccess());
        assertTrue(coldChain.getOperatorPromptEn().contains("Cold-Chain"));
        assertTrue(coldChain.getOperatorPromptBn().contains("রেফ্রিজারেটরে"));
    }

    @Test
    void testPutAwayStoragePolicyEnforcement() {
        // Cold chain placed in ambient shelf -> rejected
        var failResult = client.putAway("INS-001", "SHELF-A1", true, true);
        assertFalse(failResult.isSuccess());
        assertEquals("ERR_STORAGE_POLICY_VIOLATION", failResult.getErrorCode());
        assertTrue(failResult.getOperatorPromptEn().contains("Storage policy violation"));

        // Cold chain placed in fridge -> accepted
        var okResult = client.putAway("INS-001", "LOC-FRIDGE-01", true, true);
        assertTrue(okResult.isSuccess());
        assertEquals("LOC-FRIDGE-01", okResult.getDestinationLocation());
    }

    @Test
    void testMoveAndCycleCount() {
        // Move
        var moveResult = client.move("NAP-001", "SHELF-A1", "SHELF-B2");
        assertTrue(moveResult.isSuccess());
        assertEquals("SHELF-B2", moveResult.getDestinationLocation());

        // Count with 0 discrepancy
        var countMatch = client.count("NAP-001", "SHELF-B2", 50, 50);
        assertTrue(countMatch.isSuccess());
        assertEquals(0, countMatch.getDiscrepancy());
        assertTrue(countMatch.getOperatorPromptEn().contains("Zero discrepancy"));

        // Count with discrepancy
        var countDiff = client.count("NAP-001", "SHELF-B2", 45, 50);
        assertTrue(countDiff.isSuccess());
        assertEquals(-5, countDiff.getDiscrepancy());
        assertTrue(countDiff.getOperatorPromptEn().contains("Discrepancy detected"));
    }

    @Test
    void testVerifyAlerts() {
        // Normal
        var normal = client.verify("Napa Extra 500mg", "NAP-001", 50, false, false);
        assertTrue(normal.isSuccess());
        assertTrue(normal.getOperatorPromptEn().contains("Verified"));

        // Expired
        var expired = client.verify("Napa Extra 500mg", "NAP-EXP", 10, true, false);
        assertTrue(expired.getOperatorPromptEn().contains("EXPIRED"));

        // Quarantined
        var quarantined = client.verify("Mixtard 30", "INS-QUR", 20, false, true);
        assertTrue(quarantined.getOperatorPromptEn().contains("QUARANTINE"));
    }
}
