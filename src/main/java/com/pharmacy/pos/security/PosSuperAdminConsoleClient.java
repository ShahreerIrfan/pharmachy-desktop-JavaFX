package com.pharmacy.pos.security;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-0063: Platform Super Admin Console Client & Boundary Enforcer.
 * Guarantees that platform administration controls and APIs are never exposed
 * inside tenant POS and that privilege boundaries are strictly maintained.
 */
public final class PosSuperAdminConsoleClient {

    private static final Set<String> AUTHORIZED_PLATFORM_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR"
    );

    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            "BROADCAST_ANNOUNCEMENT",
            "SET_MAINTENANCE_MODE",
            "TRIGGER_SYSTEM_HEALTH_CHECK"
    );

    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[a-z0-9_-]{3,64}$");

    private PosSuperAdminConsoleClient() {
        // Utility / client boundary class
    }

    /**
     * Verifies if a user role is authorized to access platform administration controls.
     */
    public static boolean canAccessPlatformConsole(String role) {
        if (role == null) {
            return false;
        }
        return AUTHORIZED_PLATFORM_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Architectural invariant: Platform administration widgets are strictly omitted
     * and never visible within the tenant POS interface.
     */
    public static boolean isPlatformControlVisibleInTenantUI() {
        return false;
    }

    /**
     * Resolves the platform metrics endpoint URL with strict role checking.
     */
    public static String getPlatformMetricsUrl(String role) {
        if (!canAccessPlatformConsole(role)) {
            throw new SecurityException("Access denied: Role '" + role + "' cannot access platform metrics.");
        }
        return "/api/v2/platform/console/metrics/";
    }

    /**
     * Resolves the tenant directory endpoint URL with strict role checking.
     */
    public static String getTenantDirectoryUrl(String role) {
        if (!canAccessPlatformConsole(role)) {
            throw new SecurityException("Access denied: Role '" + role + "' cannot access tenant directory.");
        }
        return "/api/v2/platform/console/tenants/";
    }

    /**
     * Resolves the deep tenant inspection URL with validation.
     */
    public static String getTenantInspectionUrl(String role, String tenantId) {
        if (!canAccessPlatformConsole(role)) {
            throw new SecurityException("Access denied: Role '" + role + "' cannot inspect platform tenants.");
        }
        if (tenantId == null || !TENANT_ID_PATTERN.matcher(tenantId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid tenant identifier: '" + tenantId + "'");
        }
        return "/api/v2/platform/console/tenants/" + tenantId.trim() + "/";
    }

    /**
     * Validates a platform action payload before dispatch.
     */
    public static void validatePlatformAction(String role, String actionType, String reason) {
        if (!canAccessPlatformConsole(role)) {
            throw new SecurityException("Access denied: Role '" + role + "' cannot execute platform actions.");
        }
        Objects.requireNonNull(actionType, "actionType cannot be null");
        String cleanAction = actionType.toUpperCase().trim();
        if (!ALLOWED_ACTIONS.contains(cleanAction)) {
            throw new IllegalArgumentException("Unsupported platform action: '" + actionType + "'");
        }
        if (reason == null || reason.trim().length() < 5) {
            throw new IllegalArgumentException("Reason must be at least 5 characters for platform audit compliance.");
        }
    }
}

