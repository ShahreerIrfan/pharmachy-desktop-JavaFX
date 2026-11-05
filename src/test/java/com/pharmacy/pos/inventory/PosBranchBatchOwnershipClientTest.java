package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosBranchBatchOwnershipClientTest
 * Architecture Decision: ADR-0175 (P175).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 */
public class PosBranchBatchOwnershipClientTest {

    private Map<String, PosBranchBatchOwnershipClient.BranchBatchDto> sharedStore;
    private PosBranchBatchOwnershipClient cashierBranch1;
    private PosBranchBatchOwnershipClient ownerClient;

    @BeforeEach
    void setUp() {
        sharedStore = new HashMap<>();

        // Batch 1: In Branch 1 (Banani) - 50 units
        var b1 = new PosBranchBatchOwnershipClient.BranchBatchDto(
                "bat-banani-01",
                "tenant-dhaka",
                "branch-banani",
                "Banani Branch",
                "prod-napa",
                "Napa Extra 500mg",
                "BAT-2026-X",
                50,
                5,
                LocalDate.now().plusMonths(6),
                new BigDecimal("2.50"),
                "ACTIVE",
                true
        );
        sharedStore.put(b1.batchId, b1);

        // Batch 2: In Branch 2 (Dhanmondi) - 80 units (Same manufacturer batch number, different branch!)
        var b2 = new PosBranchBatchOwnershipClient.BranchBatchDto(
                "bat-dhanmondi-01",
                "tenant-dhaka",
                "branch-dhanmondi",
                "Dhanmondi Branch",
                "prod-napa",
                "Napa Extra 500mg",
                "BAT-2026-X",
                80,
                0,
                LocalDate.now().plusMonths(6),
                new BigDecimal("2.50"),
                "ACTIVE",
                true
        );
        sharedStore.put(b2.batchId, b2);

        cashierBranch1 = new PosBranchBatchOwnershipClient(
                "tenant-dhaka", "branch-banani", "Banani Branch", "CASHIER", "cashier_banani", false, sharedStore
        );

        ownerClient = new PosBranchBatchOwnershipClient(
                "tenant-dhaka", "branch-banani", "Banani Branch", "PHARMACY_OWNER", "owner_dhaka", false, sharedStore
        );
    }

    @Test
    @DisplayName("Test 1: Cashier is restricted to active terminal branch and cross-branch access is blocked")
    void testCashierBranchIsolationAndCrossBranchAccessBlocked() {
        // Querying active branch succeeds
        var activeRes = cashierBranch1.listBatchesForActiveTerminal(null);
        assertTrue(activeRes.success);
        assertEquals(1, activeRes.data.size());
        assertEquals("bat-banani-01", activeRes.data.get(0).batchId);

        // Cashier attempting to query Dhanmondi branch is blocked
        var crossBranchRes = cashierBranch1.listBatchesForActiveTerminal("branch-dhanmondi");
        assertFalse(crossBranchRes.success);
        assertEquals("ERR_CROSS_BRANCH_ACCESS", crossBranchRes.errorCode);
    }

    @Test
    @DisplayName("Test 2: Validate checkout dispense cart in active branch")
    void testValidateDispenseCartSuccessInActiveBranch() {
        var items = List.of(new PosBranchBatchOwnershipClient.DispenseItem("bat-banani-01", 10));
        var res = cashierBranch1.validateDispenseCart(items);

        assertTrue(res.success);
        assertEquals(1, res.data.size());
        assertEquals("bat-banani-01", res.data.get(0).batchId);
    }

    @Test
    @DisplayName("Test 3: Cross-branch stock mixing in checkout is strictly barred")
    void testCrossBranchDispenseBlockedInActiveBranch() {
        // Cashier in Banani branch attempts to checkout Dhanmondi branch's batch
        var items = List.of(new PosBranchBatchOwnershipClient.DispenseItem("bat-dhanmondi-01", 5));
        var res = cashierBranch1.validateDispenseCart(items);

        assertFalse(res.success);
        assertEquals("ERR_CROSS_BRANCH_DISPENSE_BLOCKED", res.errorCode);
        assertTrue(res.messageEn.contains("Cross-branch stock mixing is strictly barred"));
    }

    @Test
    @DisplayName("Test 4: Pharmacy Owner views multi-branch segregated inventory breakdown")
    void testOwnerMultiBranchSegregatedSummary() {
        var res = ownerClient.getMedicineMultiBranchSummary(
                "prod-napa", List.of("branch-banani", "branch-dhanmondi")
        );

        assertTrue(res.success);
        assertEquals(2, res.data.size());

        // Branch 1: Banani
        var bananiSummary = res.data.get(0);
        assertEquals("branch-banani", bananiSummary.branchId);
        assertEquals(50, bananiSummary.quantityOnHand);
        assertEquals(5, bananiSummary.quantityReserved);
        assertEquals(45, bananiSummary.quantityAvailable);
        assertEquals(1, bananiSummary.batches.size());

        // Branch 2: Dhanmondi
        var dhanmondiSummary = res.data.get(1);
        assertEquals("branch-dhanmondi", dhanmondiSummary.branchId);
        assertEquals(80, dhanmondiSummary.quantityOnHand);
        assertEquals(0, dhanmondiSummary.quantityReserved);
        assertEquals(80, dhanmondiSummary.quantityAvailable);
        assertEquals(1, dhanmondiSummary.batches.size());

        // Non-owner (cashier) is blocked from multi-branch summary
        var cashierSummaryRes = cashierBranch1.getMedicineMultiBranchSummary(
                "prod-napa", List.of("branch-banani", "branch-dhanmondi")
        );
        assertFalse(cashierSummaryRes.success);
        assertEquals("ERR_PERMISSION_DENIED", cashierSummaryRes.errorCode);
    }
}

