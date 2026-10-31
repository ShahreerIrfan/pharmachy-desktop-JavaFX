package com.pharmacy.pos.security;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-0070: JavaFX Desktop POS Global Medicine Master Catalogue Client.
 * Enforces central catalog ownership, bars store-level master mutations,
 * detects DGDA product recalls, and enforces prescription dispensing restrictions.
 */
public final class PosGlobalMedicineCatalogClient {

    private static final Pattern PRODUCT_ID_PATTERN = Pattern.compile("^[a-z0-9_-]{3,64}$");
    private static final Pattern DAR_PATTERN = Pattern.compile("^DAR-[A-Za-z0-9_-]{3,32}$");

    private static final Set<String> PLATFORM_CURATION_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR"
    );

    private PosGlobalMedicineCatalogClient() {
        // Utility / boundary client class
    }

    /**
     * Invariant: Store personas can NEVER mutate, delete, or author global master medicine records.
     */
    public static boolean canRoleModifyGlobalCatalog(String role) {
        if (role == null) {
            return false;
        }
        return PLATFORM_CURATION_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Safety Invariant: Recalled products are strictly barred from inventory sale and POS billing.
     */
    public static boolean isProductRecalled(String curationStatus) {
        if (curationStatus == null) {
            return false;
        }
        return "RECALLED".equalsIgnoreCase(curationStatus.trim());
    }

    /**
     * Regulatory Invariant: Checks if a product requires human pharmacist verification & prescription record.
     */
    public static boolean isPrescriptionMandatory(boolean isPrescriptionOnly, boolean isNarcotic, boolean isAntibiotic) {
        return isPrescriptionOnly || isNarcotic || isAntibiotic;
    }

    /**
     * Resolves status badge tone conforming to Apple-inspired restrained design.
     */
    public static String resolveStatusTone(String curationStatus) {
        if (curationStatus == null) {
            return "neutral";
        }
        return switch (curationStatus.toUpperCase().trim()) {
            case "APPROVED" -> "success";
            case "UNDER_REVIEW" -> "warning";
            case "RECALLED" -> "danger";
            case "DRAFT" -> "info";
            case "DEPRECATED" -> "neutral";
            default -> "neutral";
        };
    }

    /**
     * Validates DGDA Drug Administration Registration (DAR/DLR) number format.
     */
    public static boolean isValidDarNumber(String darNumber) {
        if (darNumber == null || darNumber.trim().isEmpty()) {
            return false;
        }
        return DAR_PATTERN.matcher(darNumber.trim()).matches();
    }

    /**
     * Builds master catalog search and browse URL.
     */
    public static String buildCatalogQueryUrl(int page, int pageSize, String search) {
        int p = page < 1 ? 1 : page;
        int ps = pageSize < 1 ? 20 : Math.min(100, pageSize);
        StringBuilder sb = new StringBuilder("/api/v2/products/?page=").append(p).append("&page_size=").append(ps);
        if (search != null && !search.trim().isEmpty()) {
            sb.append("&search=").append(search.trim());
        }
        return sb.toString();
    }

    /**
     * Builds product detail endpoint URL.
     */
    public static String buildProductDetailUrl(String productId) {
        if (productId == null || !PRODUCT_ID_PATTERN.matcher(productId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid product identifier: '" + productId + "'");
        }
        return "/api/v2/products/" + productId.trim() + "/";
    }
}
