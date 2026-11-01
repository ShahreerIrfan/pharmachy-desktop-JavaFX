package com.pharmacy.pos.billing;

import java.math.BigDecimal;
import java.util.*;

/**
 * PosPlanCatalogueClient
 * Architecture Decision: ADR-0015 & ADR-0081
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P081)
 *
 * JavaFX Desktop POS Plan Catalogue & Applied Entitlements Client:
 * - Localized plan catalog specifications (Basic, Standard, Pro) with BDT pricing
 * - Cardinal safety invariant: Clinical features are NEVER gated behind paid tiers (INV-SAFETY-01)
 * - Quota boundaries (branch, device, staff limits)
 * - API endpoint resolution and validation for plan changes
 */
public final class PosPlanCatalogueClient {

    public static final Set<String> CLINICAL_SAFETY_FEATURES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            "ANTIBIOTIC_REGISTER",
            "BATCH_EXPIRY_BLOCK",
            "RECALL_QUARANTINE",
            "BANNED_DRUG_RESTRICTION",
            "RX_VERIFICATION"
        ))
    );

    public static final Set<String> VALID_BILLING_CYCLES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("MONTHLY", "ANNUAL"))
    );

    public static final Set<String> VALID_PLAN_CODES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("PLAN_BASIC", "PLAN_STANDARD", "PLAN_PRO"))
    );

    private PosPlanCatalogueClient() {}

    /**
     * Invariant INV-SAFETY-01: Clinical safety controls are enabled across all tiers.
     */
    public static boolean isFeatureEntitled(String planTier, String featureKey) {
        if (featureKey == null || featureKey.trim().isEmpty()) {
            return false;
        }
        String cleanFeature = featureKey.toUpperCase().trim();
        if (CLINICAL_SAFETY_FEATURES.contains(cleanFeature)) {
            return true;
        }

        if (planTier == null) return false;
        String cleanTier = planTier.toUpperCase().trim();

        switch (cleanTier) {
            case "PRO":
                return true; // Pro has access to all commercial features
            case "STANDARD":
                return Arrays.asList(
                    "OFFLINE_SYNC",
                    "THERMAL_RECEIPT_PRINT",
                    "MEDICINE_CATALOG",
                    "BASIC_INVENTORY",
                    "PURCHASE_ORDERS",
                    "SUPPLIER_DUES",
                    "CUSTOMER_LEDGER",
                    "FLUTTER_OWNER_APP",
                    "DAILY_BACKUP"
                ).contains(cleanFeature);
            case "BASIC":
                return Arrays.asList(
                    "OFFLINE_SYNC",
                    "THERMAL_RECEIPT_PRINT",
                    "MEDICINE_CATALOG",
                    "BASIC_INVENTORY"
                ).contains(cleanFeature);
            default:
                return false;
        }
    }

    /**
     * Resolves monthly pricing in BDT by tier.
     */
    public static BigDecimal getMonthlyPriceBdt(String planTier) {
        if (planTier == null) return new BigDecimal("1500.00");
        switch (planTier.toUpperCase().trim()) {
            case "PRO": return new BigDecimal("7500.00");
            case "STANDARD": return new BigDecimal("3500.00");
            case "BASIC":
            default: return new BigDecimal("1500.00");
        }
    }

    /**
     * Resolves annual pricing in BDT by tier.
     */
    public static BigDecimal getAnnualPriceBdt(String planTier) {
        if (planTier == null) return new BigDecimal("15000.00");
        switch (planTier.toUpperCase().trim()) {
            case "PRO": return new BigDecimal("75000.00");
            case "STANDARD": return new BigDecimal("35000.00");
            case "BASIC":
            default: return new BigDecimal("15000.00");
        }
    }

    /**
     * Resolves branch quota limit by tier.
     */
    public static int getBranchQuota(String planTier) {
        if (planTier == null) return 1;
        switch (planTier.toUpperCase().trim()) {
            case "PRO": return 50;
            case "STANDARD": return 3;
            case "BASIC":
            default: return 1;
        }
    }

    /**
     * Resolves total POS devices limit by tier.
     */
    public static int getDeviceQuota(String planTier) {
        if (planTier == null) return 1;
        switch (planTier.toUpperCase().trim()) {
            case "PRO": return 250;
            case "STANDARD": return 15;
            case "BASIC":
            default: return 1;
        }
    }

    /**
     * Resolves API endpoint for catalogue operations.
     */
    public static String resolveEndpoint(String action, String identifier) {
        if (action == null) {
            throw new IllegalArgumentException("Action cannot be null");
        }
        String cleanAction = action.toLowerCase().trim();
        switch (cleanAction) {
            case "list":
                return "/api/v2/billing/plans/";
            case "retrieve":
                if (identifier == null || identifier.trim().isEmpty()) {
                    throw new IllegalArgumentException("Plan code required for retrieve action");
                }
                return "/api/v2/billing/plans/" + identifier.trim() + "/";
            case "entitlement":
                if (identifier == null || identifier.trim().isEmpty()) {
                    throw new IllegalArgumentException("Tenant ID required for entitlement action");
                }
                return "/api/v2/billing/entitlements/" + identifier.trim() + "/";
            case "apply":
                if (identifier == null || identifier.trim().isEmpty()) {
                    throw new IllegalArgumentException("Tenant ID required for apply action");
                }
                return "/api/v2/billing/entitlements/" + identifier.trim() + "/apply/";
            case "verify_feature":
                if (identifier == null || identifier.trim().isEmpty()) {
                    throw new IllegalArgumentException("Tenant ID required for verify_feature action");
                }
                return "/api/v2/billing/entitlements/" + identifier.trim() + "/verify-feature/";
            default:
                throw new IllegalArgumentException("Unknown plan catalogue action: " + action);
        }
    }

    /**
     * Validates plan application payload parameters.
     */
    public static void validateApplyRequest(String planCode, String billingCycle) {
        if (planCode == null || planCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Plan code is required");
        }
        if (!VALID_PLAN_CODES.contains(planCode.toUpperCase().trim())) {
            throw new IllegalArgumentException("Unsupported plan code: " + planCode);
        }
        if (billingCycle == null || billingCycle.trim().isEmpty()) {
            throw new IllegalArgumentException("Billing cycle is required");
        }
        if (!VALID_BILLING_CYCLES.contains(billingCycle.toUpperCase().trim())) {
            throw new IllegalArgumentException("Unsupported billing cycle: " + billingCycle);
        }
    }
}
