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
 * PosReturnInspectionClientTest
 * Architecture Decision: ADR-0177 (P177).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 */
public class PosReturnInspectionClientTest {

    private Map<String, PosReturnInspectionClient.ReturnSafetyInspectionDto> sharedStore;
    private PosReturnInspectionClient pharmacistClient;
    private PosReturnInspectionClient cashierClient;
    private PosReturnInspectionClient managerClient;

    private PosReturnInspectionClient.SaleAllocationLookupDto validAlloc;
    private PosReturnInspectionClient.SaleAllocationLookupDto expiredAlloc;

    @BeforeEach
    void setUp() {
        sharedStore = new HashMap<>();

        pharmacistClient = new PosReturnInspectionClient(
                "tenant-dhaka", "branch-banani", "STAFF_PHARMACIST", "pharm_001", false, sharedStore
        );

        cashierClient = new PosReturnInspectionClient(
                "tenant-dhaka", "branch-banani", "CASHIER", "cashier_001", false, sharedStore
        );

        managerClient = new PosReturnInspectionClient(
                "tenant-dhaka", "branch-banani", "BRANCH_MANAGER", "mgr_001", false, sharedStore
        );

        // Valid allocation: 10 units sold @ 10.00 BDT, cost 7.50 BDT, expiry in 6 months
        validAlloc = new PosReturnInspectionClient.SaleAllocationLookupDto(
                "sall-001",
                "tenant-dhaka",
                "branch-banani",
                "inv-2026-001",
                "prod-napa",
                "Napa Extra 500mg",
                "bat-001",
                "BAT-2026-A",
                "lot-001",
                10,
                0,
                new BigDecimal("10.0000"),
                new BigDecimal("7.5000"),
                LocalDate.now().plusMonths(6),
                false
        );

        // Expired allocation: batch expired yesterday
        expiredAlloc = new PosReturnInspectionClient.SaleAllocationLookupDto(
                "sall-002",
                "tenant-dhaka",
                "branch-banani",
                "inv-2026-002",
                "prod-seclo",
                "Seclo 20mg",
                "bat-002",
                "BAT-2026-EXP",
                "lot-002",
                5,
                0,
                new BigDecimal("7.0000"),
                new BigDecimal("4.5000"),
                LocalDate.now().minusDays(1),
                false
        );
    }

    @Test
    @DisplayName("Test 1: Pharmacist verifies intact seal & expiry, successfully restocks to original batch & lot")
    void testPharmacistInspectAndRestockSuccess() {
        var res = pharmacistClient.inspectAndProcessReturn(
                validAlloc,
                4, // Returning 4 units
                true, // seal intact
                true, // pack undamaged
                true, // storage condition verified
                true, // unexpired
                null,
                PosReturnInspectionClient.Disposition.RESTOCK_APPROVED,
                "Patient purchased extra strip",
                "Seal and blister intact, verified visually"
        );

        assertTrue(res.success);
        assertNotNull(res.data);
        assertEquals(PosReturnInspectionClient.Disposition.RESTOCK_APPROVED, res.data.disposition);
        assertEquals(4, res.data.returnedQuantityBaseUnits);
        assertEquals(new BigDecimal("40.0000"), res.data.refundAmount); // 4 * 10.00
        assertEquals(new BigDecimal("30.0000"), res.data.restockedCogs); // 4 * 7.50
        assertEquals(4, validAlloc.returnedQuantityBaseUnits);
        assertEquals(6, validAlloc.getMaxReturnableUnits());
    }

    @Test
    @DisplayName("Test 2: Failed safety inspection blocks restock, successfully routes to quarantine hold")
    void testFailedSafetyInspectionBlocksRestock() {
        // Customer returned open/damaged box; pharmacist marks seal NOT intact
        var failedRestock = pharmacistClient.inspectAndProcessReturn(
                validAlloc,
                2,
                false, // seal broken!
                true,
                true,
                true,
                null,
                PosReturnInspectionClient.Disposition.RESTOCK_APPROVED,
                "Customer opened packaging",
                "Seal broken"
        );
        assertFalse(failedRestock.success);
        assertEquals("ERR_FAILED_SAFETY_INSPECTION_CANNOT_RESTOCK", failedRestock.errorCode);

        // Divert to QUARANTINE_HOLD succeeds
        var quarantineRes = pharmacistClient.inspectAndProcessReturn(
                validAlloc,
                2,
                false, // seal broken
                true,
                true,
                true,
                null,
                PosReturnInspectionClient.Disposition.QUARANTINE_HOLD,
                "Customer opened packaging",
                "Diverted to quarantine hold for clinical assessment"
        );
        assertTrue(quarantineRes.success);
        assertEquals(PosReturnInspectionClient.Disposition.QUARANTINE_HOLD, quarantineRes.data.disposition);
        assertEquals(new BigDecimal("20.0000"), quarantineRes.data.refundAmount);
        assertEquals(BigDecimal.ZERO.setScale(4), quarantineRes.data.restockedCogs); // Quarantined stock has no restocked COGS
    }

    @Test
    @DisplayName("Test 3: Attempting to restock an expired batch is blocked with clinical error")
    void testExpiredBatchRestockBlocked() {
        var res = pharmacistClient.inspectAndProcessReturn(
                expiredAlloc,
                2,
                true,
                true,
                true,
                true,
                null,
                PosReturnInspectionClient.Disposition.RESTOCK_APPROVED,
                "Customer return",
                "Visual pack is fine"
        );
        assertFalse(res.success);
        assertEquals("ERR_BATCH_EXPIRED_NO_RESTOCK", res.errorCode);

        // However, routing expired return to DAMAGED_DESTROY is allowed
        var destroyRes = pharmacistClient.inspectAndProcessReturn(
                expiredAlloc,
                2,
                true,
                true,
                true,
                true,
                null,
                PosReturnInspectionClient.Disposition.DAMAGED_DESTROY,
                "Expired medicine return",
                "Disposed per DGDA waste directives"
        );
        assertTrue(destroyRes.success);
        assertEquals(PosReturnInspectionClient.Disposition.DAMAGED_DESTROY, destroyRes.data.disposition);
    }

    @Test
    @DisplayName("Test 4: Cashier permission is denied, and manager can reverse inspection with audit")
    void testCashierDeniedAndManagerReversal() {
        // Cashier attempts to approve restock -> ERR_PERMISSION_DENIED
        var cashierRes = cashierClient.inspectAndProcessReturn(
                validAlloc,
                2,
                true,
                true,
                true,
                true,
                null,
                PosReturnInspectionClient.Disposition.RESTOCK_APPROVED,
                "Cashier quick return",
                "Notes"
        );
        assertFalse(cashierRes.success);
        assertEquals("ERR_PERMISSION_DENIED", cashierRes.errorCode);

        // Pharmacist performs inspection
        var pharmRes = pharmacistClient.inspectAndProcessReturn(
                validAlloc,
                2,
                true,
                true,
                true,
                true,
                null,
                PosReturnInspectionClient.Disposition.RESTOCK_APPROVED,
                "Valid return",
                "All good"
        );
        assertTrue(pharmRes.success);
        String inspId = pharmRes.data.inspectionId;

        // Cashier attempts reversal -> ERR_PERMISSION_DENIED
        var cashierRev = cashierClient.reverseInspection(inspId, "Mistake made");
        assertFalse(cashierRev.success);
        assertEquals("ERR_PERMISSION_DENIED", cashierRev.errorCode);

        // Manager successfully reverses
        var mgrRev = managerClient.reverseInspection(inspId, "Blister was found punctured after secondary inspection");
        assertTrue(mgrRev.success);
        assertTrue(mgrRev.data.isReversed);
        assertEquals("Blister was found punctured after secondary inspection", mgrRev.data.reversalReason);

        // Double reversal blocked
        var doubleRev = managerClient.reverseInspection(inspId, "Second reversal attempt");
        assertFalse(doubleRev.success);
        assertEquals("ERR_ALREADY_REVERSED", doubleRev.errorCode);
    }
}

