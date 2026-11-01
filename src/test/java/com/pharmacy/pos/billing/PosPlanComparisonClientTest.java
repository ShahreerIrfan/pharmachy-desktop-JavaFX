package com.pharmacy.pos.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P083: JavaFX Desktop POS Plan Comparison Client Tests")
public class PosPlanComparisonClientTest {

    @Test
    @DisplayName("Canonical comparison cards define Basic, Standard, and Pro with correct pricing and annual savings")
    public void testCanonicalComparisonCardsHaveAllThreePlans() {
        List<PosPlanComparisonClient.PlanComparisonCard> cards = PosPlanComparisonClient.getCanonicalComparisonCards();
        assertEquals(3, cards.size());

        PosPlanComparisonClient.PlanComparisonCard basic = cards.get(0);
        assertEquals("PLAN_BASIC", basic.getPlanCode());
        assertEquals("BASIC", basic.getTier());
        assertEquals(new BigDecimal("1500.00"), basic.getMonthlyPriceBdt());
        assertEquals(new BigDecimal("15000.00"), basic.getAnnualPriceBdt());
        assertEquals(new BigDecimal("1250.00"), basic.getEffectiveMonthlyBdt());
        assertEquals(new BigDecimal("3000.00"), basic.getAnnualSavingsBdt());
        assertEquals("16.67%", basic.getSavingsPercentage());
        assertEquals(1, basic.getMaxBranches());

        PosPlanComparisonClient.PlanComparisonCard standard = cards.get(1);
        assertEquals("PLAN_STANDARD", standard.getPlanCode());
        assertEquals("STANDARD", standard.getTier());
        assertEquals(new BigDecimal("3500.00"), standard.getMonthlyPriceBdt());
        assertEquals(new BigDecimal("35000.00"), standard.getAnnualPriceBdt());
        assertEquals(new BigDecimal("2916.67"), standard.getEffectiveMonthlyBdt());
        assertEquals(new BigDecimal("7000.00"), standard.getAnnualSavingsBdt());
        assertEquals("16.67%", standard.getSavingsPercentage());
        assertEquals(3, standard.getMaxBranches());

        PosPlanComparisonClient.PlanComparisonCard pro = cards.get(2);
        assertEquals("PLAN_PRO", pro.getPlanCode());
        assertEquals("PRO", pro.getTier());
        assertEquals(new BigDecimal("7500.00"), pro.getMonthlyPriceBdt());
        assertEquals(new BigDecimal("75000.00"), pro.getAnnualPriceBdt());
        assertEquals(new BigDecimal("6250.00"), pro.getEffectiveMonthlyBdt());
        assertEquals(new BigDecimal("15000.00"), pro.getAnnualSavingsBdt());
        assertEquals("16.67%", pro.getSavingsPercentage());
        assertEquals(50, pro.getMaxBranches());
    }

    @Test
    @DisplayName("Invariant INV-SAFETY-01: Universal clinical safety guarantees are non-gated across all plans")
    public void testClinicalSafetyGuaranteesAreUniversal() {
        List<PosPlanComparisonClient.ClinicalSafetyGuarantee> guarantees = PosPlanComparisonClient.getClinicalSafetyGuarantees();
        assertEquals(5, guarantees.size());

        for (PosPlanComparisonClient.ClinicalSafetyGuarantee g : guarantees) {
            assertTrue(g.isGuaranteed(), "Safety control " + g.getKey() + " must be guaranteed");
            assertTrue(PosPlanComparisonClient.isClinicallyGuaranteed(g.getKey()));
        }

        assertFalse(PosPlanComparisonClient.isClinicallyGuaranteed("CONSOLIDATED_REPORTING"));
        assertFalse(PosPlanComparisonClient.isClinicallyGuaranteed("PURCHASE_ORDERS"));
    }

    @Test
    @DisplayName("Quote calculation properly discounts annual billing and preserves renewal periods")
    public void testQuoteCalculationAnnualDiscountAndSavings() {
        String tenantId = "tenant-pos-p083";

        // Annual Quote for Standard Plan
        PosPlanComparisonClient.QuoteResult annualQuote = PosPlanComparisonClient.calculateQuote(
            tenantId, "PLAN_STANDARD", "ANNUAL", 1, 3
        );
        assertEquals(new BigDecimal("42000.00"), annualQuote.getSubtotalBdt());
        assertEquals(new BigDecimal("7000.00"), annualQuote.getDiscountBdt());
        assertEquals(new BigDecimal("35000.00"), annualQuote.getTotalBdt());
        assertEquals(new BigDecimal("2916.67"), annualQuote.getEffectiveMonthlyBdt());
        assertEquals("16.67%", annualQuote.getSavingsPercentage());
        assertEquals(365, annualQuote.getRenewalPeriodDays());
        assertTrue(annualQuote.isSafetyFeaturesGuaranteed());
        assertTrue(annualQuote.isCompliant());

        // Monthly Quote for Standard Plan
        PosPlanComparisonClient.QuoteResult monthlyQuote = PosPlanComparisonClient.calculateQuote(
            tenantId, "PLAN_STANDARD", "MONTHLY", 1, 3
        );
        assertEquals(new BigDecimal("3500.00"), monthlyQuote.getSubtotalBdt());
        assertEquals(new BigDecimal("0.00"), monthlyQuote.getDiscountBdt());
        assertEquals(new BigDecimal("3500.00"), monthlyQuote.getTotalBdt());
        assertEquals(new BigDecimal("3500.00"), monthlyQuote.getEffectiveMonthlyBdt());
        assertEquals("0.00%", monthlyQuote.getSavingsPercentage());
        assertEquals(30, monthlyQuote.getRenewalPeriodDays());
        assertTrue(monthlyQuote.isSafetyFeaturesGuaranteed());
        assertTrue(monthlyQuote.isCompliant());
    }

    @Test
    @DisplayName("Quote validation checks invalid arguments and quota compliance boundaries")
    public void testQuoteValidationAndQuotaCompliance() {
        String tenantId = "tenant-pos-p083";

        // Invalid inputs
        assertThrows(IllegalArgumentException.class, () ->
            PosPlanComparisonClient.calculateQuote("", "PLAN_BASIC", "MONTHLY", 1, 1)
        );
        assertThrows(IllegalArgumentException.class, () ->
            PosPlanComparisonClient.calculateQuote(tenantId, "INVALID_PLAN", "MONTHLY", 1, 1)
        );
        assertThrows(IllegalArgumentException.class, () ->
            PosPlanComparisonClient.calculateQuote(tenantId, "PLAN_BASIC", "QUARTERLY", 1, 1)
        );

        // Downgrade to Basic with 2 active branches -> violation
        PosPlanComparisonClient.QuoteResult overQuotaQuote = PosPlanComparisonClient.calculateQuote(
            tenantId, "PLAN_BASIC", "MONTHLY", 2, 2
        );
        assertFalse(overQuotaQuote.isCompliant());
        assertEquals(1, overQuotaQuote.getViolations().size());
        assertTrue(overQuotaQuote.getViolations().get(0).contains("exceed plan limit"));
    }
}
