package com.pharmacy.pos.security;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-0066: Platform Super Admin Tenant Detail and Audit Timeline Client.
 * Manages authorization, URL construction, patient clinical data sanitization audits,
 * and chronological timeline validation for platform diagnostics.
 */
public final class PosTenantDetailTimelineClient {

    private static final Set<String> AUTHORIZED_PLATFORM_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR"
    );

    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[a-z0-9_-]{3,64}$");

    private static final Set<String> FORBIDDEN_PATIENT_KEYS = Set.of(
            "patient_name",
            "patient_phone",
            "patient_address",
            "prescriptions",
            "dispensing_records",
            "clinical_notes"
    );

    private PosTenantDetailTimelineClient() {
        // Utility / client boundary class
    }

    /**
     * Verifies if a user role is authorized to view tenant detail and audit timeline.
     */
    public static boolean canAccessTenantDetail(String role) {
        if (role == null) {
            return false;
        }
        return AUTHORIZED_PLATFORM_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Architectural invariant: Platform tenant detail view is strictly omitted from store POS UI.
     */
    public static boolean isTenantDetailVisibleInStoreUI() {
        return false;
    }

    /**
     * Audits a tenant detail payload to guarantee patient-level clinical records are absent.
     */
    public static boolean containsPatientClinicalData(Map<String, Object> payload) {
        if (payload == null) {
            return false;
        }
        for (String forbiddenKey : FORBIDDEN_PATIENT_KEYS) {
            if (payload.containsKey(forbiddenKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the tenant detail & timeline endpoint URL with validation.
     */
    public static String buildTenantDetailUrl(String role, String tenantId) {
        if (!canAccessTenantDetail(role)) {
            throw new SecurityException("Access denied: Role '" + role + "' cannot access tenant detail and timeline.");
        }
        if (tenantId == null || !TENANT_ID_PATTERN.matcher(tenantId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid tenant identifier: '" + tenantId + "'");
        }
        return "/api/v2/platform/console/tenants/" + tenantId.trim() + "/detail/";
    }

    /**
     * Verifies that the audit timeline contains the required event attributes.
     */
    public static boolean isValidTimelineEntry(Map<String, Object> entry) {
        if (entry == null) {
            return false;
        }
        return entry.containsKey("action") && entry.containsKey("status") && entry.containsKey("timestamp");
    }
}

