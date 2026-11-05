package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosBatchMergeClientTest
 * Architecture Decision: ADR-0178 (P178).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 */
public class PosBatchMergeClientTest {

    private Map<String, PosBatchMergeClient.BatchMergeRecordDto> sharedStore;
    private PosBatchMergeClient managerClient;
    private PosBatchMergeClient cashierClient;

    private PosBatchMergeClient.BatchDto sourceTwin;
    private PosBatchMergeClient.BatchDto targetTwin;

    @BeforeEach
    void setUp() {
        sharedStore = new HashMap<>();

        managerClient = new PosBatchMergeClient(
                "tenant-dhaka", "branch-banani", "BRANCH_MANAGER", "mgr_001", false, sharedStore
        );

        cashierClient = new PosBatchMergeClient(
                "tenant-dhaka", "branch-banani", "CASHIER", "csh_001", false, sharedStore
        );

        LocalDate commonExpiry = LocalDate.now().plusMonths(12);

        // Source Twin: 40 units @ 10.00 BDT, Batch "BN-2026-X"
        sourceTwin = new PosBatchMergeClient.BatchDto(
                "bat-src-01",
                "tenant-dhaka",
                "branch-banani",
                "prod-napa",
                "Napa Extra 500mg",
                "BN-2026-X",
                commonExpiry,
                40,
                0,
                new BigDecimal("10.0000"),
                "ACTIVE",
                false
        );

        // Target Twin: 60 units @ 12.00 BDT, Batch "BN-2026-X"
        // Expected weighted cost = (40*10 + 60*12) / 100 = (400 + 720) / 100 = 11.2000 BDT
        targetTwin = new PosBatchMergeClient.BatchDto(
                "bat-tgt-01",
                "tenant-dhaka",
                "branch-banani",
                "prod-napa",
                "Napa Extra 500mg",
                "BN-2026-X",
                commonExpiry,
                60,
                0,
                new BigDecimal("12.0000"),
                "ACTIVE",
                false
        );
    }

    @Test
    @DisplayName("Test 1: Safe batch merge succeeds for identical manufacturer lot & expiry, with weighted cost")
    void testSafeBatchMergeSuccess() {
        var preFlight = managerClient.validateMergeCompatibility(sourceTwin, targetTwin);
        assertTrue(preFlight.canMerge);
        assertEquals(0, preFlight.blockers.size());
        assertEquals(100, preFlight.projectedTargetQuantity);
        assertEquals(new BigDecimal("11.2000"), preFlight.projectedTargetCostPerUnit);

        var res = managerClient.executeMerge(sourceTwin, targetTwin, "Consolidating split delivery invoice shipments");
        assertTrue(res.success);
        assertNotNull(res.data);
        assertEquals(40, res.data.mergedQuantityBaseUnits);
        assertEquals(new BigDecimal("11.2000"), res.data.targetCostPerUnitAfter);

        // Verify batch state updates
        assertEquals(100, targetTwin.quantityBaseUnits);
        assertEquals(new BigDecimal("11.2000"), targetTwin.costPerUnit);
        assertEquals(0, sourceTwin.quantityBaseUnits);
        assertEquals("DEPLETED", sourceTwin.status);
    }

    @Test
    @DisplayName("Test 2: Mismatched manufacturer batch reference is strictly blocked from merging")
    void testMismatchedManufacturerLotBlocked() {
        // Different manufacturer batch number
        var differentLotSource = new PosBatchMergeClient.BatchDto(
                "bat-diff-01",
                "tenant-dhaka",
                "branch-banani",
                "prod-napa",
                "Napa Extra 500mg",
                "BN-2026-Y", // Differing lot reference!
                sourceTwin.expiryDate,
                40,
                0,
                new BigDecimal("10.0000"),
                "ACTIVE",
                false
        );

        var preFlight = managerClient.validateMergeCompatibility(differentLotSource, targetTwin);
        assertFalse(preFlight.canMerge);
        assertTrue(preFlight.blockers.contains("ERR_MANUFACTURER_BATCH_MISMATCH"));

        var execRes = managerClient.executeMerge(differentLotSource, targetTwin, "Attempting unsafe lot merge");
        assertFalse(execRes.success);
        assertEquals("ERR_MANUFACTURER_BATCH_MISMATCH", execRes.errorCode);
    }

    @Test
    @DisplayName("Test 3: Mismatched expiry dates corrupt FEFO and are strictly blocked")
    void testMismatchedExpiryDateBlocked() {
        // Different expiry date
        var differentExpirySource = new PosBatchMergeClient.BatchDto(
                "bat-diff-exp",
                "tenant-dhaka",
                "branch-banani",
                "prod-napa",
                "Napa Extra 500mg",
                "BN-2026-X",
                sourceTwin.expiryDate.minusMonths(2), // 2 months earlier!
                40,
                0,
                new BigDecimal("10.0000"),
                "ACTIVE",
                false
        );

        var preFlight = managerClient.validateMergeCompatibility(differentExpirySource, targetTwin);
        assertFalse(preFlight.canMerge);
        assertTrue(preFlight.blockers.contains("ERR_EXPIRY_DATE_MISMATCH"));

        var execRes = managerClient.executeMerge(differentExpirySource, targetTwin, "Attempting differing expiry merge");
        assertFalse(execRes.success);
        assertEquals("ERR_EXPIRY_DATE_MISMATCH", execRes.errorCode);
    }

    @Test
    @DisplayName("Test 4: Recalled/quarantined batches are blocked, cashier is denied, and manager can reverse")
    void testQuarantineOrRecallBlockedAndReversal() {
        // Recalled source batch
        sourceTwin.status = "RECALLED";
        var recalledRes = managerClient.executeMerge(sourceTwin, targetTwin, "Merging recalled stock");
        assertFalse(recalledRes.success);
        assertEquals("ERR_BATCH_RECALLED", recalledRes.errorCode);
        sourceTwin.status = "ACTIVE";

        // Cashier role denied
        var cashierRes = cashierClient.executeMerge(sourceTwin, targetTwin, "Cashier consolidation");
        assertFalse(cashierRes.success);
        assertEquals("ERR_PERMISSION_DENIED", cashierRes.errorCode);

        // Manager executes valid merge
        var validMerge = managerClient.executeMerge(sourceTwin, targetTwin, "Valid shipment consolidation");
        assertTrue(validMerge.success);
        String mergeId = validMerge.data.mergeId;

        // Manager reverses merge
        var revRes = managerClient.reverseMerge(mergeId, sourceTwin, targetTwin, "Accidental merge consolidation");
        assertTrue(revRes.success);
        assertTrue(revRes.data.isReversed);

        // Verify quantities restored
        assertEquals(40, sourceTwin.quantityBaseUnits);
        assertEquals("ACTIVE", sourceTwin.status);
        assertEquals(60, targetTwin.quantityBaseUnits);
        assertEquals(new BigDecimal("12.0000"), targetTwin.costPerUnit);

        // Double reversal blocked
        var doubleRev = managerClient.reverseMerge(mergeId, sourceTwin, targetTwin, "Second reversal");
        assertFalse(doubleRev.success);
        assertEquals("ERR_ALREADY_REVERSED", doubleRev.errorCode);
    }
}

