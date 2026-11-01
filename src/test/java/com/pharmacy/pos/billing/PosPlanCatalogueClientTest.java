package com.pharmacy.pos.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P081: JavaFX Desktop POS Plan Catalogue Client Tests")
public class PosPlanCatalogueClientTest {

    @Test
    @DisplayName("Invariant INV-SAFETY-01: Clinical safety features are never gated across all plans")
    public void testClinicalSafetyFeaturesAlwaysEntitled() {
        String[] plans = {"BASIC", "STANDARD", "PRO", null, "UNKNOWN_TIER"};
        String[] clinicalFeatures = {
            "ANTIBIOTIC_REGISTER",
            "BATCH_EXPIRY_BLOCK",
            "RECALL_QUARANTINE",
            "BANNED_DRUG_RESTRICTION",
            "RX_VERIFICATION"
        };

        for (String plan : plans) {
            for (String feature : clinicalFeatures) {
                assertTrue(
                    PosPlanCatalogueClient.isFeatureEntitled(plan, feature),
                    "Feature " + feature + " must be entitled on plan " + plan
                );
            }
        }
    }

    @Test
    @DisplayName("Commercial feature tiering correctly discriminates Standard and Pro")
    public void testCommercialFeatureTiering() {
        assertFalse(PosPlanCatalogueClient.isFeatureEntitled("BASIC", "CONSOLIDATED_REPORTING"));
        assertFalse(PosPlanCatalogueClient.isFeatureEntitled("STANDARD", "CONSOLIDATED_REPORTING"));
        assertTrue(PosPlanCatalogueClient.isFeatureEntitled("PRO", "CONSOLIDATED_REPORTING"));

        assertFalse(PosPlanCatalogueClient.isFeatureEntitled("BASIC", "PURCHASE_ORDERS"));
        assertTrue(PosPlanCatalogueClient.isFeatureEntitled("STANDARD", "PURCHASE_ORDERS"));
        assertTrue(PosPlanCatalogueClient.isFeatureEntitled("PRO", "PURCHASE_ORDERS"));
    }

    @Test
    @DisplayName("Pricing and quota resolutions adhere to ADR-0015 & ADR-0081")
    public void testPricingAndQuotas() {
        assertEquals(new BigDecimal("1500.00"), PosPlanCatalogueClient.getMonthlyPriceBdt("BASIC"));
        assertEquals(new BigDecimal("3500.00"), PosPlanCatalogueClient.getMonthlyPriceBdt("STANDARD"));
        assertEquals(new BigDecimal("7500.00"), PosPlanCatalogueClient.getMonthlyPriceBdt("PRO"));

        assertEquals(new BigDecimal("15000.00"), PosPlanCatalogueClient.getAnnualPriceBdt("BASIC"));
        assertEquals(new BigDecimal("35000.00"), PosPlanCatalogueClient.getAnnualPriceBdt("STANDARD"));
        assertEquals(new BigDecimal("75000.00"), PosPlanCatalogueClient.getAnnualPriceBdt("PRO"));

        assertEquals(1, PosPlanCatalogueClient.getBranchQuota("BASIC"));
        assertEquals(3, PosPlanCatalogueClient.getBranchQuota("STANDARD"));
        assertEquals(50, PosPlanCatalogueClient.getBranchQuota("PRO"));
    }

    @Test
    @DisplayName("Endpoint resolution handles list, retrieve, entitlement, and apply routes")
    public void testEndpointResolution() {
        assertEquals("/api/v2/billing/plans/", PosPlanCatalogueClient.resolveEndpoint("list", null));
        assertEquals("/api/v2/billing/plans/PLAN_BASIC/", PosPlanCatalogueClient.resolveEndpoint("retrieve", "PLAN_BASIC"));
        assertEquals("/api/v2/billing/entitlements/t-123/", PosPlanCatalogueClient.resolveEndpoint("entitlement", "t-123"));
        assertEquals("/api/v2/billing/entitlements/t-123/apply/", PosPlanCatalogueClient.resolveEndpoint("apply", "t-123"));
    }

    @Test
    @DisplayName("Validation rejects invalid plan code or unsupported billing cycle")
    public void testValidationRejections() {
        assertDoesNotThrow(() -> PosPlanCatalogueClient.validateApplyRequest("PLAN_STANDARD", "MONTHLY"));
        assertDoesNotThrow(() -> PosPlanCatalogueClient.validateApplyRequest("PLAN_PRO", "ANNUAL"));

        assertThrows(IllegalArgumentException.class, () -> PosPlanCatalogueClient.validateApplyRequest("INVALID_PLAN", "MONTHLY"));
        assertThrows(IllegalArgumentException.class, () -> PosPlanCatalogueClient.validateApplyRequest("PLAN_BASIC", "QUARTERLY"));
        assertThrows(IllegalArgumentException.class, () -> PosPlanCatalogueClient.validateApplyRequest(null, "MONTHLY"));
    }
}
