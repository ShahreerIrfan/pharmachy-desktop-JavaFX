package com.pharmacy.pos.pricing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosConditionalPricingClientTest
 * Architecture Decision: ADR-0153 (P153).
 * Multi-Tier Packaging Formulation and Unit-Aware Pricing Test Suite.
 */
public class PosConditionalPricingClientTest {

    private PosConditionalPricingClient client;

    @BeforeEach
    public void setUp() {
        client = new PosConditionalPricingClient();
    }

    @Test
    @DisplayName("P153-DESK-01: Valid price calculation across pack, strip, and piece")
    public void testCalculateDerivedPricingSuccess() {
        PosConditionalPricingClient.PricingCalculationResult result = client.calculateDerivedPricing(
                10,
                10,
                100,
                new BigDecimal("2.0000"),
                new BigDecimal("3.0000"),
                true,
                true
        );

        assertTrue(result.isValid);
        assertNull(result.errorCode);
        assertEquals(100, result.totalBaseUnitsPerPack);
        assertEquals(new BigDecimal("20.0000"), result.stripCostBdt);
        assertEquals(new BigDecimal("30.0000"), result.stripMrpBdt);
        assertEquals(new BigDecimal("200.0000"), result.packCostBdt);
        assertEquals(new BigDecimal("300.0000"), result.packMrpBdt);
    }

    @Test
    @DisplayName("P153-DESK-02: Dimension mismatch triggers ERR_DIMENSION_MISMATCH")
    public void testDimensionMismatchFailure() {
        PosConditionalPricingClient.PricingCalculationResult result = client.calculateDerivedPricing(
                5,
                10,
                60, // 5 * 10 = 50 != 60
                new BigDecimal("2.0000"),
                new BigDecimal("3.0000"),
                true,
                true
        );

        assertFalse(result.isValid);
        assertEquals("ERR_DIMENSION_MISMATCH", result.errorCode);
    }

    @Test
    @DisplayName("P153-DESK-03: Zero or negative multiplier triggers ERR_INVALID_MULTIPLIER")
    public void testZeroOrNegativeMultiplierFailure() {
        PosConditionalPricingClient.PricingCalculationResult result = client.calculateDerivedPricing(
                0,
                10,
                null,
                new BigDecimal("2.0000"),
                new BigDecimal("3.0000"),
                true,
                true
        );

        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_MULTIPLIER", result.errorCode);
    }

    @Test
    @DisplayName("P153-DESK-04: Non-positive piece price triggers ERR_INVALID_PRICE")
    public void testNonPositivePriceFailure() {
        PosConditionalPricingClient.PricingCalculationResult result = client.calculateDerivedPricing(
                10,
                10,
                100,
                BigDecimal.ZERO,
                new BigDecimal("3.0000"),
                true,
                true
        );

        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_PRICE", result.errorCode);
    }

    @Test
    @DisplayName("P153-DESK-05: Multi-unit packaging prohibiting break bulk triggers ERR_INVALID_BREAK_BULK_POLICY")
    public void testBreakBulkRestrictionFailure() {
        PosConditionalPricingClient.PricingCalculationResult result = client.calculateDerivedPricing(
                10,
                10,
                100,
                new BigDecimal("2.0000"),
                new BigDecimal("3.0000"),
                false, // allowStripSale = false
                false  // allowPieceSale = false
        );

        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_BREAK_BULK_POLICY", result.errorCode);
    }

    @Test
    @DisplayName("P153-DESK-06: Single unit product allows pack-only sales without break bulk")
    public void testBreakBulkSingleUnitAllowed() {
        PosConditionalPricingClient.PricingCalculationResult result = client.calculateDerivedPricing(
                1,
                1,
                1,
                new BigDecimal("50.0000"),
                new BigDecimal("60.0000"),
                false,
                false
        );

        assertTrue(result.isValid);
        assertEquals(1, result.totalBaseUnitsPerPack);
        assertEquals(new BigDecimal("60.0000"), result.packMrpBdt);
    }

    @Test
    @DisplayName("P153-DESK-07: Cashier is strictly restricted from editing pricing")
    public void testCanEditPricingRolePermissions() {
        assertTrue(client.canEditPricing("STAFF_PHARMACIST"));
        assertTrue(client.canEditPricing("PHARMACY_OWNER"));
        assertTrue(client.canEditPricing("SUPERADMIN"));
        assertFalse(client.canEditPricing("CASHIER"));
        assertFalse(client.canEditPricing(null));
    }

    @Test
    @DisplayName("P153-DESK-08: Unit display returns correct Bengali and English localized text")
    public void testUnitDisplayTranslations() {
        assertEquals("ট্যাবলেট", client.getUnitDisplay("TABLET", "bn"));
        assertEquals("Tablet", client.getUnitDisplay("TABLET", "en"));
        assertEquals("ক্যাপসুল", client.getUnitDisplay("CAPSULE", "bn"));
        assertEquals("Capsule", client.getUnitDisplay("CAPSULE", "en"));
        assertEquals("UNKNOWN_UNIT", client.getUnitDisplay("UNKNOWN_UNIT", "bn"));
    }
}

