package com.pharmacy.pos.security;

import java.math.BigDecimal;
import java.util.*;

/**
 * PosTenantLifecycleEndToEndClient
 * Architecture Decision: ADR-0080
 * JavaFX Desktop POS Client for Tenant Lifecycle End-to-End Orchestration:
 *   - Enforces POS checkout lockout when tenant is in SUSPENDED or ARCHIVED status
 *   - Validates commercial plan tier quotas and BDT pricing
 *   - Governs FSM state machine transitions (TRIAL -> ACTIVE -> SUSPENDED -> RESTORED)
 *   - Verifies Platform Super Admin RBAC for administrative lifecycle mutations
 */
public final class PosTenantLifecycleEndToEndClient {

    private static final Set<String> SUPERADMIN_ROLES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("SUPERADMIN", "SUPER_ADMIN", "PLATFORM_OPERATOR"))
    );

    private static final Set<String> CHECKOUT_ALLOWED_STATUSES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("TRIAL", "ACTIVE"))
    );

    private PosTenantLifecycleEndToEndClient() {}

    /**
     * Invariant: POS cashier checkouts are allowed ONLY when tenant is in TRIAL or ACTIVE state.
     */
    public static boolean isCheckoutPermitted(String tenantStatus) {
        if (tenantStatus == null) return false;
        return CHECKOUT_ALLOWED_STATUSES.contains(tenantStatus.toUpperCase().trim());
    }

    /**
     * Checks if operator role is authorized to perform administrative lifecycle transitions.
     */
    public static boolean isLifecycleAdminAuthorized(String role) {
        if (role == null) return false;
        return SUPERADMIN_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Resolves licensed branch quota by plan tier.
     */
    public static int getBranchQuota(String planTier) {
        if (planTier == null) return 1;
        switch (planTier.toUpperCase().trim()) {
            case "PRO": return 25;
            case "STANDARD": return 5;
            case "BASIC":
            default: return 1;
        }
    }

    /**
     * Resolves licensed device quota by plan tier.
     */
    public static int getDeviceQuota(String planTier) {
        if (planTier == null) return 2;
        switch (planTier.toUpperCase().trim()) {
            case "PRO": return 50;
            case "STANDARD": return 10;
            case "BASIC":
            default: return 2;
        }
    }

    /**
     * Resolves standard monthly fee in BDT.
     */
    public static BigDecimal getMonthlyAmountBdt(String planTier) {
        if (planTier == null) return new BigDecimal("2500.00");
        switch (planTier.toUpperCase().trim()) {
            case "PRO": return new BigDecimal("15000.00");
            case "STANDARD": return new BigDecimal("6500.00");
            case "BASIC":
            default: return new BigDecimal("2500.00");
        }
    }

    /**
     * Validates deterministic FSM lifecycle transition paths.
     */
    public static boolean isValidTransition(String fromStatus, String toStatus) {
        if (fromStatus == null || toStatus == null) return false;
        String from = fromStatus.toUpperCase().trim();
        String to = toStatus.toUpperCase().trim();
        if (from.equals(to)) return true;

        switch (from) {
            case "PROVISIONING":
                return "TRIAL".equals(to) || "ACTIVE".equals(to);
            case "TRIAL":
                return "ACTIVE".equals(to) || "SUSPENDED".equals(to) || "ARCHIVED".equals(to);
            case "ACTIVE":
                return "SUSPENDED".equals(to) || "PAST_DUE".equals(to) || "ARCHIVED".equals(to);
            case "PAST_DUE":
                return "ACTIVE".equals(to) || "SUSPENDED".equals(to) || "ARCHIVED".equals(to);
            case "SUSPENDED":
                return "ACTIVE".equals(to) || "ARCHIVED".equals(to);
            case "ARCHIVED":
                return false; // Terminal state
            default:
                return false;
        }
    }

    /**
     * Resolves API endpoint for lifecycle action on a specific tenant.
     */
    public static String resolveEndpoint(String action, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required for lifecycle actions.");
        }
        String cleanId = tenantId.trim();
        String cleanAction = action.toLowerCase().trim();

        switch (cleanAction) {
            case "activate-subscription":
                return "/api/v2/tenants/" + cleanId + "/activate-subscription/";
            case "change-plan":
                return "/api/v2/tenants/" + cleanId + "/change-plan/";
            case "suspend":
                return "/api/v2/tenants/" + cleanId + "/suspend/";
            case "restore":
                return "/api/v2/tenants/" + cleanId + "/restore/";
            case "timeline":
                return "/api/v2/tenants/" + cleanId + "/lifecycle-timeline/";
            default:
                return "/api/v2/tenants/" + cleanId + "/";
        }
    }
}
