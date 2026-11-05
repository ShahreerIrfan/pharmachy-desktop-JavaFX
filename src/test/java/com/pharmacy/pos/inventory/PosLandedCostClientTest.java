package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosLandedCostClientTest
 * Architecture Decision: ADR-0176 (P176).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 */
public class PosLandedCostClientTest {

    private Map<String, PosLandedCostClient.LandedCostExpenseDto> sharedStore;
    private PosLandedCostClient managerClient;
    private PosLandedCostClient cashierClient;

    private List<PosLandedCostClient.CostLotInput> sampleLots;

    @BeforeEach
    void setUp() {
        sharedStore = new HashMap<>();

        managerClient = new PosLandedCostClient(
                "tenant-dhaka", "branch-banani", "STORE_MANAGER", "mgr_001", false, sharedStore
        );

        cashierClient = new PosLandedCostClient(
                "tenant-dhaka", "branch-banani", "CASHIER", "csh_001", false, sharedStore
        );

        // Lot 1: 100 units @ 10.00 BDT -> Line Value = 1,000.00 BDT
        // Lot 2: 200 units @ 15.00 BDT -> Line Value = 3,000.00 BDT
        // Lot 3: 50 units  @ 20.00 BDT -> Line Value = 1,000.00 BDT
        // Total Purchase Value = 5,000.00 BDT
        // Total Quantity = 350 units
        sampleLots = List.of(
                new PosLandedCostClient.CostLotInput("lot-001", "bat-001", "prod-001", "Paracetamol 500mg", new BigDecimal("10.00"), 100),
                new PosLandedCostClient.CostLotInput("lot-002", "bat-002", "prod-002", "Amoxicillin 250mg", new BigDecimal("15.00"), 200),
                new PosLandedCostClient.CostLotInput("lot-003", "bat-003", "prod-003", "Omeprazole 20mg", new BigDecimal("20.00"), 50)
        );
    }

    @Test
    @DisplayName("Test 1: Pro-rata by value allocation preserves exact penny conservation without rounding loss")
    void testProRataByValueAllocationAndPennyConservation() {
        BigDecimal totalExpense = new BigDecimal("750.35"); // Odd amount testing penny conservation

        var result = managerClient.previewAllocations(
                totalExpense,
                PosLandedCostClient.AllocationMethod.BY_VALUE,
                sampleLots
        );

        assertTrue(result.success);
        assertNotNull(result.data);
        assertEquals(3, result.data.size());

        BigDecimal sumAllocated = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        for (PosLandedCostClient.LandedCostAllocationLineDto line : result.data) {
            sumAllocated = sumAllocated.add(line.allocatedExpenseAmount);
            // Landed unit cost must be higher than base purchase unit cost
            assertTrue(line.landedUnitCost.compareTo(line.basePurchaseUnitCost) > 0);
        }

        // Strict penny conservation invariant: sum of allocated must EQUAL total expense down to 0.0001
        assertEquals(totalExpense.setScale(4, RoundingMode.HALF_UP), sumAllocated);
    }

    @Test
    @DisplayName("Test 2: Pro-rata by quantity allocation distributes based on base units")
    void testProRataByQuantityAllocation() {
        BigDecimal totalExpense = new BigDecimal("350.00");

        var result = managerClient.previewAllocations(
                totalExpense,
                PosLandedCostClient.AllocationMethod.BY_QUANTITY,
                sampleLots
        );

        assertTrue(result.success);
        assertEquals(3, result.data.size());

        // Total qty = 350. Total expense = 350.00 BDT -> Exactly 1.00 BDT per unit
        // Lot 1 (100 units) should get ~100.00 BDT
        // Lot 2 (200 units) should get ~200.00 BDT
        // Lot 3 (50 units) should get ~50.00 BDT
        var line1 = result.data.get(0);
        var line2 = result.data.get(1);
        var line3 = result.data.get(2);

        assertEquals(new BigDecimal("100.0000"), line1.allocatedExpenseAmount);
        assertEquals(new BigDecimal("200.0000"), line2.allocatedExpenseAmount);
        assertEquals(new BigDecimal("50.0000"), line3.allocatedExpenseAmount);

        // Landed unit costs:
        // Line 1: 10.00 + 100/100 = 11.00
        // Line 2: 15.00 + 200/200 = 16.00
        // Line 3: 20.00 + 50/50   = 21.00
        assertEquals(new BigDecimal("11.0000"), line1.landedUnitCost);
        assertEquals(new BigDecimal("16.0000"), line2.landedUnitCost);
        assertEquals(new BigDecimal("21.0000"), line3.landedUnitCost);
    }

    @Test
    @DisplayName("Test 3: Store manager successfully posts landed cost expense and capitalizes lots")
    void testCreateAndPostLandedCostSuccess() {
        BigDecimal freightAmount = new BigDecimal("500.00");

        var postResult = managerClient.createAndPostLandedCost(
                "grn-2026-001",
                PosLandedCostClient.ExpenseType.TRANSPORT_FREIGHT,
                PosLandedCostClient.AllocationMethod.BY_VALUE,
                freightAmount,
                "TRUCK-INV-9988",
                "Sundarban Courier",
                "Transport for cold chain and general medicines",
                sampleLots
        );

        assertTrue(postResult.success);
        assertNotNull(postResult.data);
        assertEquals(PosLandedCostClient.LandedCostStatus.POSTED, postResult.data.status);
        assertEquals(3, postResult.data.lines.size());
        assertEquals("Sundarban Courier", postResult.data.carrierOrVendor);

        // Listed expenses contains this expense
        var listResult = managerClient.listExpenses();
        assertTrue(listResult.success);
        assertEquals(1, listResult.data.size());
    }

    @Test
    @DisplayName("Test 4: Cashier permission is denied, and manager can reverse posted landed cost")
    void testCashierPermissionDeniedAndReversal() {
        // Cashier attempts to post landed cost -> ERR_PERMISSION_DENIED
        var cashierPost = cashierClient.createAndPostLandedCost(
                "grn-2026-002",
                PosLandedCostClient.ExpenseType.HANDLING_LABOR,
                PosLandedCostClient.AllocationMethod.BY_VALUE,
                new BigDecimal("200.00"),
                "LABOR-01",
                "Local Labor",
                "Unloading charges",
                sampleLots
        );
        assertFalse(cashierPost.success);
        assertEquals("ERR_PERMISSION_DENIED", cashierPost.errorCode);

        // Manager posts landed cost
        var mgrPost = managerClient.createAndPostLandedCost(
                "grn-2026-002",
                PosLandedCostClient.ExpenseType.HANDLING_LABOR,
                PosLandedCostClient.AllocationMethod.BY_VALUE,
                new BigDecimal("200.00"),
                "LABOR-01",
                "Local Labor",
                "Unloading charges",
                sampleLots
        );
        assertTrue(mgrPost.success);
        String expenseId = mgrPost.data.expenseId;

        // Cashier attempts to reverse -> ERR_PERMISSION_DENIED
        var cashierRev = cashierClient.reverseLandedCost(expenseId, "Mistaken entry");
        assertFalse(cashierRev.success);
        assertEquals("ERR_PERMISSION_DENIED", cashierRev.errorCode);

        // Manager successfully reverses
        var mgrRev = managerClient.reverseLandedCost(expenseId, "Supplier reimbursed the handling charge");
        assertTrue(mgrRev.success);
        assertEquals(PosLandedCostClient.LandedCostStatus.REVERSED, mgrRev.data.status);
        assertEquals("Supplier reimbursed the handling charge", mgrRev.data.reversalReason);

        // Double reversal is rejected
        var doubleRev = managerClient.reverseLandedCost(expenseId, "Another reversal");
        assertFalse(doubleRev.success);
        assertEquals("ERR_ALREADY_REVERSED", doubleRev.errorCode);
    }
}

