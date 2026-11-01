package com.pharmacy.pos.security;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-0071: JavaFX Desktop POS Tenant-Specific Medicine Configuration Client.
 * Manages store-level brand aliases, internal SKUs, custom retail MRPs,
 * shelf locations, reorder thresholds, and visibility overrides while
 * strictly preventing cashier unauthorized mutations and ensuring DGDA compliance.
 */
public final class PosTenantMedicineConfigClient {

    private static final Pattern PRODUCT_ID_PATTERN = Pattern.compile("^[a-z0-9_-]{3,64}$");
    private static final Pattern CONFIG_ID_PATTERN = Pattern.compile("^tmc-[a-z0-9_-]{8,32}$");

    private static final Set<String> ALLOWED_MUTATION_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR",
            "PHARMACY_OWNER",
            "BRANCH_MANAGER",
            "STAFF_PHARMACIST",
            "OWNER",
            "MANAGER",
            "PHARMACIST"
    );

    private PosTenantMedicineConfigClient() {
        // Utility client
    }

    /**
     * RBAC Invariant: Cashiers cannot mutate tenant configurations.
     */
    public static boolean canRoleModifyTenantConfig(String role) {
        if (role == null) {
            return false;
        }
        return ALLOWED_MUTATION_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Resolves effective brand name for POS cashier display.
     */
    public static String resolveEffectiveBrandName(String localBrandName, String masterBrandName) {
        if (localBrandName != null && !localBrandName.trim().isEmpty()) {
            return localBrandName.trim();
        }
        return masterBrandName != null ? masterBrandName.trim() : "";
    }

    /**
     * Resolves effective retail MRP per base unit.
     */
    public static String resolveEffectivePrice(String customMrp, String masterMrp) {
        if (customMrp != null && !customMrp.trim().isEmpty()) {
            return customMrp.trim();
        }
        return masterMrp != null ? masterMrp.trim() : "0.00";
    }

    /**
     * Regulatory Invariant: Tenant can enforce stricter prescription rules,
     * but strictly CANNOT relax master POM, narcotic, or antibiotic classifications.
     */
    public static boolean isPrescriptionMandatory(
            boolean masterPom,
            boolean masterNarcotic,
            boolean masterAntibiotic,
            Boolean tenantOverride
    ) {
        if (masterPom || masterNarcotic || masterAntibiotic) {
            return true;
        }
        return tenantOverride != null && tenantOverride;
    }

    /**
     * Validates custom MRP decimal value (must be >= 0.01 BDT).
     */
    public static boolean isValidCustomMrp(String customMrp) {
        if (customMrp == null || customMrp.trim().isEmpty()) {
            return true; // null/empty means fall back to master MRP
        }
        try {
            BigDecimal val = new BigDecimal(customMrp.trim());
            return val.compareTo(new BigDecimal("0.01")) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Builds endpoint URL for querying tenant configurations.
     */
    public static String buildConfigListUrl(int page, int pageSize, String search, Boolean isVisible) {
        int p = page < 1 ? 1 : page;
        int ps = pageSize < 1 ? 20 : Math.min(100, pageSize);
        StringBuilder sb = new StringBuilder("/api/v2/tenants/medicine-configs/?page=")
                .append(p)
                .append("&page_size=")
                .append(ps);

        if (search != null && !search.trim().isEmpty()) {
            sb.append("&search=").append(search.trim());
        }
        if (isVisible != null) {
            sb.append("&is_visible=").append(isVisible);
        }
        return sb.toString();
    }

    /**
     * Builds endpoint URL for querying config by master product ID.
     */
    public static String buildConfigByProductUrl(String productId) {
        if (productId == null || !PRODUCT_ID_PATTERN.matcher(productId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid product identifier: '" + productId + "'");
        }
        return "/api/v2/tenants/medicine-configs/by-product/" + productId.trim() + "/";
    }

    /**
     * Builds endpoint URL for updating or deleting a specific config.
     */
    public static String buildConfigDetailUrl(String configId) {
        if (configId == null || !CONFIG_ID_PATTERN.matcher(configId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid config identifier: '" + configId + "'");
        }
        return "/api/v2/tenants/medicine-configs/" + configId.trim() + "/";
    }
}

