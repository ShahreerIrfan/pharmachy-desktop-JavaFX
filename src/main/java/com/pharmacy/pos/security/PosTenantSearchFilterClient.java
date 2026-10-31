package com.pharmacy.pos.security;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ADR-0065: Platform Super Admin Tenant Search, Filter, and Status Chip Client.
 * Enforces role isolation, validates search/filter bounds, maps UI status chips,
 * and guarantees zero exposure of patient-level clinical records.
 */
public final class PosTenantSearchFilterClient {

    private static final Set<String> AUTHORIZED_PLATFORM_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR"
    );

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "ACTIVE",
            "TRIAL",
            "SUSPENDED",
            "ARCHIVED",
            "GRACE_PERIOD"
    );

    private static final Set<String> ALLOWED_PLAN_TIERS = Set.of("BASIC", "STANDARD", "PRO");

    private static final Set<String> FORBIDDEN_PATIENT_KEYS = Set.of(
            "patient_name",
            "patient_phone",
            "prescriptions",
            "dispensing_history",
            "clinical_notes"
    );

    private PosTenantSearchFilterClient() {
        // Utility / client boundary class
    }

    /**
     * Verifies if a user role is authorized to search and filter platform tenants.
     */
    public static boolean canSearchTenants(String role) {
        if (role == null) {
            return false;
        }
        return AUTHORIZED_PLATFORM_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Architectural invariant: Platform tenant directory search is never available in store POS UI.
     */
    public static boolean isTenantSearchVisibleInStoreUI() {
        return false;
    }

    /**
     * Audits a tenant search result item to guarantee patient-level clinical data is absent.
     */
    public static boolean containsPatientLevelData(Map<String, Object> tenantItem) {
        if (tenantItem == null) {
            return false;
        }
        for (String forbiddenKey : FORBIDDEN_PATIENT_KEYS) {
            if (tenantItem.containsKey(forbiddenKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Maps lifecycle status to UI badge color tone conforming to Apple-inspired design restraint.
     */
    public static String getStatusChipTone(String status) {
        if (status == null) {
            return "neutral";
        }
        return switch (status.toUpperCase().trim()) {
            case "ACTIVE" -> "success";
            case "TRIAL", "GRACE_PERIOD" -> "warning";
            case "SUSPENDED" -> "danger";
            case "ARCHIVED" -> "neutral";
            default -> "neutral";
        };
    }

    /**
     * Maps lifecycle status to approved platform administrative actions.
     */
    public static List<String> getApprovedActionsForStatus(String status) {
        if (status == null) {
            return List.of();
        }
        return switch (status.toUpperCase().trim()) {
            case "ACTIVE" -> List.of("SUSPEND", "ARCHIVE", "UPDATE_PLAN", "PROVISION_BRANCH");
            case "TRIAL" -> List.of("ACTIVATE_SUBSCRIPTION", "EXTEND_TRIAL", "SUSPEND", "ARCHIVE");
            case "SUSPENDED" -> List.of("REINSTATE", "ARCHIVE");
            case "GRACE_PERIOD" -> List.of("RENEW_SUBSCRIPTION", "SUSPEND", "ARCHIVE");
            case "ARCHIVED" -> List.of("RESTORE_METADATA");
            default -> List.of();
        };
    }

    /**
     * Builds the tenant search URL with bounds and query parameter validation.
     */
    public static String buildSearchUrl(
            String role,
            String search,
            String status,
            String planTier,
            int page,
            int pageSize
    ) {
        if (!canSearchTenants(role)) {
            throw new SecurityException("Access denied: Role '" + role + "' cannot search platform tenants.");
        }
        if (page < 1) {
            throw new IllegalArgumentException("Page must be >= 1.");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100.");
        }

        StringBuilder url = new StringBuilder("/api/v2/platform/console/tenants/?page=" + page + "&page_size=" + pageSize);

        if (search != null && !search.trim().isEmpty()) {
            url.append("&search=").append(search.trim());
        }
        if (status != null && !status.trim().isEmpty()) {
            String cleanStatus = status.toUpperCase().trim();
            if (!ALLOWED_STATUSES.contains(cleanStatus)) {
                throw new IllegalArgumentException("Invalid status filter: '" + status + "'");
            }
            url.append("&status=").append(cleanStatus);
        }
        if (planTier != null && !planTier.trim().isEmpty()) {
            String cleanPlan = planTier.toUpperCase().trim();
            if (!ALLOWED_PLAN_TIERS.contains(cleanPlan)) {
                throw new IllegalArgumentException("Invalid plan tier filter: '" + planTier + "'");
            }
            url.append("&plan_tier=").append(cleanPlan);
        }

        return url.toString();
    }
}
