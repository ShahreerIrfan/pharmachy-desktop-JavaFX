package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosSupplierComparisonTest
 * Architecture Decision: ADR-0195 (P195).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 */
public class PosSupplierComparisonTest {

    private Map<String, PosSupplierComparisonClient.ComparisonEvaluationDto> evaluationStore;
    private Map<String, PosPurchaseOrderClient.PurchaseOrderDto> poStore;
    private PosSupplierComparisonClient managerClient;
    private PosSupplierComparisonClient cashierClient;

    @BeforeEach
    void setUp() {
        evaluationStore = new HashMap<>();
        poStore = new HashMap<>();

        managerClient = new PosSupplierComparisonClient(
                "tenant-dhaka-001",
                "branch-dhanmondi-01",
                "BRANCH_MANAGER",
                "usr-manager-1",
                false,
                evaluationStore,
                poStore
        );

        cashierClient = new PosSupplierComparisonClient(
                "tenant-dhaka-001",
                "branch-dhanmondi-01",
                "CASHIER",
                "usr-cashier-1",
                false,
                evaluationStore,
                poStore
        );
    }

    private PosSupplierComparisonClient.ComparisonLineDto createLine(
            String lineId, String supId, String supName, BigDecimal unitCost,
            BigDecimal discount, int moq, int leadTime, int termsDays,
            BigDecimal relScore, boolean proofOrigin) {
        BigDecimal discFactor = BigDecimal.ONE.subtract(discount.divide(new BigDecimal("100"), 4, java.math.RoundingMode.HALF_UP));
        BigDecimal netCost = unitCost.multiply(discFactor);
        return new PosSupplierComparisonClient.ComparisonLineDto(
                lineId, supId, supName, "BOX_100", 100,
                unitCost.multiply(new BigDecimal("100")), unitCost, new BigDecimal("2.5000"),
                discount, netCost, moq, leadTime, termsDays, relScore, proofOrigin,
                0, BigDecimal.ZERO, false, ""
        );
    }

    @Test
    void test01_rankAndScoreSuppliers() {
        var l1 = createLine("l-1", "sup-sq", "Square", new BigDecimal("1.8000"), new BigDecimal("5.00"), 50, 1, 30, new BigDecimal("100.00"), true);
        var l2 = createLine("l-2", "sup-bx", "Beximco", new BigDecimal("1.8500"), new BigDecimal("10.00"), 100, 3, 45, new BigDecimal("50.00"), true);

        var ranked = managerClient.rankAndScoreLines(List.of(l1, l2));

        assertEquals(2, ranked.size());
        // Square has 100% reliability and 1 day lead time vs Beximco 50% reliability
        var top = ranked.get(0);
        assertEquals("sup-sq", top.supplierId);
        assertTrue(top.isRecommended);
        assertEquals(1, top.compositeRank);
    }

    @Test
    void test02_createComparisonEvaluationSnapshot() {
        var l1 = createLine("l-1", "sup-sq", "Square", new BigDecimal("1.8000"), new BigDecimal("5.00"), 50, 1, 30, new BigDecimal("100.00"), true);
        var l2 = createLine("l-2", "sup-bx", "Beximco", new BigDecimal("1.8500"), new BigDecimal("10.00"), 100, 3, 45, new BigDecimal("50.00"), true);

        var eval = managerClient.createComparisonEvaluation("prod-napa", "Napa 500mg", 100, List.of(l1, l2), "Committee check");

        assertNotNull(eval);
        assertEquals("DRAFT", eval.status);
        assertEquals(2, eval.lines.size());
        assertTrue(eval.evaluationNumber.startsWith("SCE-"));
    }

    @Test
    void test03_selectSupplierCreatesPurchaseOrder() {
        var l1 = createLine("l-1", "sup-sq", "Square", new BigDecimal("1.8000"), new BigDecimal("5.00"), 50, 1, 30, new BigDecimal("100.00"), true);
        var eval = managerClient.createComparisonEvaluation("prod-napa", "Napa 500mg", 100, List.of(l1), "Committee check");

        var po = managerClient.selectSupplierAndCreatePo(eval.evaluationId, "sup-sq", "Highest score", 100);

        assertNotNull(po);
        assertEquals("sup-sq", po.supplierId);
        assertEquals(new BigDecimal("171.0000"), po.totalAmount); // 180 - 5% = 171
        assertEquals("ORDER_PLACED", eval.status);
        assertEquals(po.poId, eval.createdPoId);
    }

    @Test
    void test04_moqViolationThrows() {
        var l1 = createLine("l-1", "sup-sq", "Square", new BigDecimal("1.8000"), new BigDecimal("5.00"), 50, 1, 30, new BigDecimal("100.00"), true);
        var eval = managerClient.createComparisonEvaluation("prod-napa", "Napa 500mg", 100, List.of(l1), "Committee check");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                managerClient.selectSupplierAndCreatePo(eval.evaluationId, "sup-sq", "MOQ test", 20)
        );
        assertTrue(ex.getMessage().contains("ERR_BELOW_MINIMUM_ORDER_QTY"));
    }

    @Test
    void test05_duplicateOrderPlacementThrows() {
        var l1 = createLine("l-1", "sup-sq", "Square", new BigDecimal("1.8000"), new BigDecimal("5.00"), 50, 1, 30, new BigDecimal("100.00"), true);
        var eval = managerClient.createComparisonEvaluation("prod-napa", "Napa 500mg", 100, List.of(l1), "Committee check");
        managerClient.selectSupplierAndCreatePo(eval.evaluationId, "sup-sq", "First order", 100);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                managerClient.selectSupplierAndCreatePo(eval.evaluationId, "sup-sq", "Second order", 100)
        );
        assertTrue(ex.getMessage().contains("ERR_ORDER_ALREADY_PLACED"));
    }

    @Test
    void test06_cashierRoleDenied() {
        SecurityException ex = assertThrows(SecurityException.class, () ->
                cashierClient.createComparisonEvaluation("prod-napa", "Napa 500mg", 100, List.of(), "Cashier attempt")
        );
        assertTrue(ex.getMessage().contains("ERR_STAFF_ROLE_DENIED"));
    }
}
