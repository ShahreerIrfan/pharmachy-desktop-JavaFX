package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosPackagingConcurrencyClientTest
 * Architecture Decision: ADR-0180 (P180).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 */
public class PosPackagingConcurrencyClientTest {

    private Map<String, PosPackagingConcurrencyClient.ConcurrencyAuditResultDto> auditStore;
    private PosPackagingConcurrencyClient managerClient;
    private PosPackagingConcurrencyClient cashierClient;

    private List<PosPackagingConcurrencyClient.BatchStockItem> testBatches;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        auditStore = new HashMap<>();
        today = LocalDate.now();

        managerClient = new PosPackagingConcurrencyClient(
                "tenant-dhaka", "branch-banani", "BRANCH_MANAGER", "mgr_001", false, auditStore
        );
        cashierClient = new PosPackagingConcurrencyClient(
                "tenant-dhaka", "branch-banani", "CASHIER", "csh_001", false, auditStore
        );

        testBatches = new ArrayList<>();

        // Batch A: Napa Extra, Expiring in 2 months (FEFO First), 40 units
        testBatches.add(new PosPackagingConcurrencyClient.BatchStockItem(
                "bat-01", "BN-NAPA-EARLY",
                today.plusMonths(2), today.minusDays(30), 40, 0, new BigDecimal("2.0000")
        ));

        // Batch B: Napa Extra, Expiring in 12 months (FEFO Later), 60 units
        testBatches.add(new PosPackagingConcurrencyClient.BatchStockItem(
                "bat-02", "BN-NAPA-LATE",
                today.plusMonths(12), today.minusDays(10), 60, 0, new BigDecimal("2.2000")
        ));
    }

    @Test
    @DisplayName("Test 1: Multi-threaded concurrent checkouts enforce exact stock conservation and zero overselling")
    void testConcurrentCheckoutStockConservation() {
        // Total stock = 40 + 60 = 100 units.
        // Simulate 20 concurrent requests @ 4 units each = 80 units demanded.
        var res = managerClient.runConcurrentCheckoutSimulation(
                testBatches, "prod-napa", 10, 2, 4
        );

        assertTrue(res.success);
        assertNotNull(res.data);

        var audit = res.data;
        assertEquals(20, audit.totalRequests);
        assertEquals(20, audit.successfulTransactions);
        assertEquals(0, audit.rejectedOversells);
        assertEquals(100, audit.initialStockBaseUnits);
        assertEquals(20, audit.finalStockBaseUnits);
        assertEquals(80, audit.totalAllocatedBaseUnits);
        assertTrue(audit.stockConserved);
        assertTrue(audit.fefoOrderVerified);
        assertTrue(audit.integerUnitsVerified);
    }

    @Test
    @DisplayName("Test 2: Excess demand under high concurrency cleanly rejects oversells while preserving stock conservation")
    void testExcessDemandRejectionAndZeroOversell() {
        // Total stock = 100 units.
        // Simulate 30 concurrent requests @ 5 units each = 150 units demanded (exceeds 100 units).
        var res = managerClient.runConcurrentCheckoutSimulation(
                testBatches, "prod-napa", 15, 2, 5
        );

        assertTrue(res.success);
        var audit = res.data;

        assertEquals(30, audit.totalRequests);
        // Only 20 checkouts can succeed (20 * 5 = 100 units)
        assertEquals(20, audit.successfulTransactions);
        // 10 checkouts must be cleanly rejected due to stock exhaustion
        assertEquals(10, audit.rejectedOversells);
        assertEquals(100, audit.initialStockBaseUnits);
        assertEquals(0, audit.finalStockBaseUnits);
        assertEquals(100, audit.totalAllocatedBaseUnits);
        assertTrue(audit.stockConserved);
    }

    @Test
    @DisplayName("Test 3: FEFO batch consumption strictly depletes earlier expiry first under concurrent checkout")
    void testFefoEarliestExpiryDepletedFirst() {
        // Total stock: Batch A (40 units @ 2 months), Batch B (60 units @ 12 months)
        // Request 10 checkouts @ 4 units = 40 units
        var res = managerClient.runConcurrentCheckoutSimulation(
                testBatches, "prod-napa", 10, 1, 4
        );

        assertTrue(res.success);
        // Batch A (earliest expiry) should be completely depleted (0 units remaining)
        assertEquals(0, testBatches.get(0).quantityBaseUnits);
        // Batch B (later expiry) should retain all 60 units intact
        assertEquals(60, testBatches.get(1).quantityBaseUnits);
    }

    @Test
    @DisplayName("Test 4: Cashier role is strictly denied access to concurrency diagnostic runs")
    void testCashierAccessDenied() {
        var res = cashierClient.runConcurrentCheckoutSimulation(
                testBatches, "prod-napa", 10, 1, 4
        );

        assertFalse(res.success);
        assertEquals("ERR_PERMISSION_DENIED", res.errorCode);
        assertNotNull(res.messageEn);
        assertNotNull(res.messageBn);
    }
}

