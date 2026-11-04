package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PosFefoAllocationClientTest {

    private LocalDate today;
    private PosFefoAllocationClient.BatchRecord batchExpiringSoon;
    private PosFefoAllocationClient.BatchRecord batchExpiringLater;
    private PosFefoAllocationClient.BatchRecord batchExpired;
    private PosFefoAllocationClient.BatchRecord batchQuarantined;
    private PosFefoAllocationClient.BatchRecord batchRecalled;

    @BeforeEach
    void setUp() {
        today = LocalDate.of(2026, 11, 4);

        batchExpiringSoon = new PosFefoAllocationClient.BatchRecord(
                "bat-001",
                "BX-2026-EARLY",
                today.plusDays(30),
                today.minusDays(10),
                100,
                0,
                new BigDecimal("1.20"),
                false,
                false,
                true);

        batchExpiringLater = new PosFefoAllocationClient.BatchRecord(
                "bat-002",
                "BX-2026-LATER",
                today.plusDays(120),
                today.minusDays(5),
                200,
                0,
                new BigDecimal("1.25"),
                false,
                false,
                true);

        batchExpired = new PosFefoAllocationClient.BatchRecord(
                "bat-003",
                "BX-2026-EXPIRED",
                today.minusDays(2),
                today.minusDays(100),
                500,
                0,
                new BigDecimal("1.00"),
                false,
                false,
                true);

        batchQuarantined = new PosFefoAllocationClient.BatchRecord(
                "bat-004",
                "BX-2026-QUARANTINED",
                today.plusDays(60),
                today.minusDays(20),
                300,
                0,
                new BigDecimal("1.10"),
                true,
                false,
                true);

        batchRecalled = new PosFefoAllocationClient.BatchRecord(
                "bat-005",
                "BX-2026-RECALLED",
                today.plusDays(90),
                today.minusDays(15),
                400,
                0,
                new BigDecimal("1.15"),
                false,
                true,
                true);
    }

    @Test
    @DisplayName("1. Allocates from earliest expiring batch first (FEFO principle)")
    void shouldAllocateFromEarliestExpiringBatchFirst() {
        List<PosFefoAllocationClient.BatchRecord> batches = List.of(batchExpiringLater, batchExpiringSoon);

        // Request 60 units (batchExpiringSoon has 100 units)
        PosFefoAllocationClient.AllocationResult res =
                PosFefoAllocationClient.allocateFefo("prod-napa-500", batches, 60, today, false);

        assertTrue(res.isSuccessful);
        assertEquals(1, res.allocations.size());
        assertEquals("bat-001", res.allocations.get(0).batchId);
        assertEquals(60, res.allocations.get(0).allocatedUnits);
        assertEquals(new BigDecimal("72.00"), res.totalCogs);
    }

    @Test
    @DisplayName("2. Splits allocation across multiple batches when first batch capacity is exceeded")
    void shouldSplitAllocationAcrossMultipleBatches() {
        List<PosFefoAllocationClient.BatchRecord> batches = List.of(batchExpiringLater, batchExpiringSoon);

        // Request 150 units (batchExpiringSoon has 100 units, batchExpiringLater has 200 units)
        PosFefoAllocationClient.AllocationResult res =
                PosFefoAllocationClient.allocateFefo("prod-napa-500", batches, 150, today, false);

        assertTrue(res.isSuccessful);
        assertEquals(2, res.allocations.size());
        // First allocation from earliest batch
        assertEquals("bat-001", res.allocations.get(0).batchId);
        assertEquals(100, res.allocations.get(0).allocatedUnits);
        // Remainder from second batch
        assertEquals("bat-002", res.allocations.get(1).batchId);
        assertEquals(50, res.allocations.get(1).allocatedUnits);
        // COGS = 100*1.20 + 50*1.25 = 120 + 62.50 = 182.50
        assertEquals(new BigDecimal("182.50"), res.totalCogs);
    }

    @Test
    @DisplayName("3. Applies deterministic tie-breaker cascade when expiry dates are identical")
    void shouldApplyTieBreakerCascadeForIdenticalExpiryDates() {
        // Two batches with exact same expiry date but different purchase dates
        PosFefoAllocationClient.BatchRecord batchSameExpOlderPurchase = new PosFefoAllocationClient.BatchRecord(
                "bat-tie-1",
                "BX-TIE-B",
                today.plusDays(60),
                today.minusDays(20), // Earlier purchase date
                50,
                0,
                new BigDecimal("1.20"),
                false,
                false,
                true);

        PosFefoAllocationClient.BatchRecord batchSameExpNewerPurchase = new PosFefoAllocationClient.BatchRecord(
                "bat-tie-2",
                "BX-TIE-A",
                today.plusDays(60),
                today.minusDays(5), // Later purchase date
                50,
                0,
                new BigDecimal("1.20"),
                false,
                false,
                true);

        List<PosFefoAllocationClient.BatchRecord> batches = List.of(batchSameExpNewerPurchase, batchSameExpOlderPurchase);

        PosFefoAllocationClient.AllocationResult res =
                PosFefoAllocationClient.allocateFefo("prod-napa-500", batches, 30, today, false);

        assertTrue(res.isSuccessful);
        assertEquals(1, res.allocations.size());
        // Must pick older purchase date first (FIFO tie-breaker)
        assertEquals("bat-tie-1", res.allocations.get(0).batchId);
    }

    @Test
    @DisplayName("4. Strictly excludes expired, quarantined, and recalled batches")
    void shouldStrictlyExcludeExpiredQuarantinedAndRecalledBatches() {
        List<PosFefoAllocationClient.BatchRecord> batches = List.of(batchExpired, batchQuarantined, batchRecalled);

        PosFefoAllocationClient.AllocationResult res =
                PosFefoAllocationClient.allocateFefo("prod-napa-500", batches, 10, today, false);

        assertFalse(res.isSuccessful);
        assertEquals("ERR_NO_ELIGIBLE_BATCHES", res.errorCode);
        assertEquals(0, res.allocations.size());
    }

    @Test
    @DisplayName("5. Active reservations reduce available stock and prevent overselling")
    void shouldDeductActiveReservationsFromAvailableStock() {
        // batchExpiringSoon has 100 total units, but 80 are already reserved
        batchExpiringSoon.reservedQuantityBaseUnits = 80;
        assertEquals(20, batchExpiringSoon.getAvailableQuantity());

        List<PosFefoAllocationClient.BatchRecord> batches = List.of(batchExpiringSoon);

        // Request 30 units (20 available <= 30 requested)
        PosFefoAllocationClient.AllocationResult res =
                PosFefoAllocationClient.allocateFefo("prod-napa-500", batches, 30, today, false);

        assertFalse(res.isSuccessful);
        assertEquals("ERR_INSUFFICIENT_STOCK", res.errorCode);
        assertEquals(20, res.allocatedUnits); // Reported available units
    }

    @Test
    @DisplayName("6. Atomic reservation lifecycle: reserve, commit, and release")
    void shouldSupportReservationCommitAndReleaseLifecycle() {
        List<PosFefoAllocationClient.BatchRecord> batches = new ArrayList<>(List.of(batchExpiringSoon));

        // 1. Allocate with reservation
        PosFefoAllocationClient.AllocationResult res =
                PosFefoAllocationClient.allocateFefo("prod-napa-500", batches, 40, today, true);

        assertTrue(res.isSuccessful);
        assertEquals(40, batchExpiringSoon.reservedQuantityBaseUnits);
        assertEquals(60, batchExpiringSoon.getAvailableQuantity());
        assertNotNull(res.allocations.get(0).reservationId);

        // 2. Commit reservation on POS sale checkout
        boolean committed = PosFefoAllocationClient.commitReservation(batchExpiringSoon, 40);
        assertTrue(committed);
        assertEquals(60, batchExpiringSoon.totalQuantityBaseUnits);
        assertEquals(0, batchExpiringSoon.reservedQuantityBaseUnits);
        assertEquals(60, batchExpiringSoon.getAvailableQuantity());

        // 3. New reservation and release
        PosFefoAllocationClient.allocateFefo("prod-napa-500", batches, 20, today, true);
        assertEquals(20, batchExpiringSoon.reservedQuantityBaseUnits);

        boolean released = PosFefoAllocationClient.releaseReservation(batchExpiringSoon, 20);
        assertTrue(released);
        assertEquals(0, batchExpiringSoon.reservedQuantityBaseUnits);
        assertEquals(60, batchExpiringSoon.getAvailableQuantity());
    }
}

