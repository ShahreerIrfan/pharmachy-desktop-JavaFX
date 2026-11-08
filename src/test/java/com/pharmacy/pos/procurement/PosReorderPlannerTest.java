package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosReorderPlannerTest
 * Architecture Decision: ADR-0194 (P194).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 */
public class PosReorderPlannerTest {

    private Map<String, PosReorderPlannerClient.ReorderProposalDto> proposalStore;
    private Map<String, PosPurchaseOrderClient.PurchaseOrderDto> poStore;
    private PosReorderPlannerClient managerClient;
    private PosReorderPlannerClient cashierClient;

    @BeforeEach
    void setUp() {
        proposalStore = new HashMap<>();
        poStore = new HashMap<>();

        managerClient = new PosReorderPlannerClient(
                "tenant-dhaka-001",
                "branch-dhanmondi-01",
                "BRANCH_MANAGER",
                "usr-manager-1",
                false,
                proposalStore,
                poStore
        );

        cashierClient = new PosReorderPlannerClient(
                "tenant-dhaka-001",
                "branch-dhanmondi-01",
                "CASHIER",
                "usr-cashier-1",
                false,
                proposalStore,
                poStore
        );
    }

    private PosReorderPlannerClient.ReorderItemDto createSampleItem(
            String itemId, String prodId, String prodName, String supId, String supName,
            int curStock, int pendIn, int expSoon, int effStock, int rawRec, BigDecimal unitCost) {
        BigDecimal lineCost = unitCost.multiply(BigDecimal.valueOf(rawRec));
        return new PosReorderPlannerClient.ReorderItemDto(
                itemId, prodId, prodName, supId, supName,
                curStock, pendIn, expSoon, effStock,
                new BigDecimal("5.00"), 2, 5, 15,
                rawRec, rawRec, "BOX_100",
                unitCost, lineCost, "HIGH",
                new BigDecimal("1.5"), null, "FEFO risk and low stock"
        );
    }

    @Test
    void test01_generateReorderPlanCalculatesMetrics() {
        List<PosReorderPlannerClient.ReorderItemDto> items = List.of(
                createSampleItem("itm-1", "prod-napa", "Napa Extra", "sup-sq", "Square", 5, 0, 2, 3, 40, new BigDecimal("2.5000")),
                createSampleItem("itm-2", "prod-ace", "Ace Plus", "sup-sq", "Square", 8, 0, 0, 8, 30, new BigDecimal("3.0000"))
        );

        var plan = managerClient.generateReorderPlan(14, items);

        assertNotNull(plan);
        assertEquals("DRAFT_PROPOSAL", plan.status);
        assertEquals(2, plan.totalRecommendedSkus);
        assertEquals(new BigDecimal("190.0000"), plan.totalEstimatedCost);
        assertEquals(2, plan.items.size());
    }

    @Test
    void test02_neverAutoOrderSilentlyThrowsWhenUnapproved() {
        List<PosReorderPlannerClient.ReorderItemDto> items = List.of(
                createSampleItem("itm-1", "prod-napa", "Napa Extra", "sup-sq", "Square", 5, 0, 2, 3, 40, new BigDecimal("2.5000"))
        );
        var plan = managerClient.generateReorderPlan(14, items);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                managerClient.convertToPurchaseOrders(plan.proposalId)
        );
        assertTrue(ex.getMessage().contains("ERR_PLAN_NOT_APPROVED"));
    }

    @Test
    void test03_managerialApprovalWithItemAdjustments() {
        List<PosReorderPlannerClient.ReorderItemDto> items = List.of(
                createSampleItem("itm-1", "prod-napa", "Napa Extra", "sup-sq", "Square", 5, 0, 0, 5, 50, new BigDecimal("10.0000"))
        );
        var plan = managerClient.generateReorderPlan(14, items);

        Map<String, Integer> adjustments = Map.of("itm-1", 60);
        var approved = managerClient.approveReorderPlan(plan.proposalId, "Increased quantity for Eid holiday demand", adjustments);

        assertEquals("APPROVED", approved.status);
        assertEquals("usr-manager-1", approved.reviewedBy);
        assertEquals(60, approved.items.get(0).approvedOrderUnits);
        assertEquals(new BigDecimal("600.0000"), approved.totalEstimatedCost);
    }

    @Test
    void test04_convertToPurchaseOrdersCreatesGroupedOrders() {
        List<PosReorderPlannerClient.ReorderItemDto> items = List.of(
                createSampleItem("itm-1", "prod-napa", "Napa Extra", "sup-sq", "Square Pharma", 5, 0, 0, 5, 20, new BigDecimal("10.0000")),
                createSampleItem("itm-2", "prod-bex", "Napa Extend", "sup-bx", "Beximco Pharma", 2, 0, 0, 2, 15, new BigDecimal("20.0000"))
        );
        var plan = managerClient.generateReorderPlan(14, items);
        managerClient.approveReorderPlan(plan.proposalId, "Approved both suppliers", null);

        List<String> poIds = managerClient.convertToPurchaseOrders(plan.proposalId);

        assertEquals(2, poIds.size());
        assertEquals("CONVERTED_TO_PO", plan.status);
        assertEquals(2, poStore.size());
    }

    @Test
    void test05_dismissReorderPlan() {
        List<PosReorderPlannerClient.ReorderItemDto> items = List.of(
                createSampleItem("itm-1", "prod-napa", "Napa Extra", "sup-sq", "Square", 5, 0, 0, 5, 20, new BigDecimal("10.0000"))
        );
        var plan = managerClient.generateReorderPlan(14, items);

        var dismissed = managerClient.dismissReorderPlan(plan.proposalId, "Overstocked in central depot");
        assertEquals("DISMISSED", dismissed.status);
        assertTrue(dismissed.reviewNotes.contains("Overstocked in central depot"));
    }

    @Test
    void test06_cannotDismissConvertedPlan() {
        List<PosReorderPlannerClient.ReorderItemDto> items = List.of(
                createSampleItem("itm-1", "prod-napa", "Napa Extra", "sup-sq", "Square", 5, 0, 0, 5, 20, new BigDecimal("10.0000"))
        );
        var plan = managerClient.generateReorderPlan(14, items);
        managerClient.approveReorderPlan(plan.proposalId, "Approved", null);
        managerClient.convertToPurchaseOrders(plan.proposalId);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                managerClient.dismissReorderPlan(plan.proposalId, "Try dismiss")
        );
        assertTrue(ex.getMessage().contains("ERR_CANNOT_DISMISS_CONVERTED"));
    }

    @Test
    void test07_cashierRoleDenied() {
        SecurityException ex = assertThrows(SecurityException.class, () ->
                cashierClient.generateReorderPlan(14, List.of())
        );
        assertTrue(ex.getMessage().contains("ERR_STAFF_ROLE_DENIED"));
    }
}
