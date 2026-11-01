package com.pharmacy.pos.security;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-0075: JavaFX Desktop POS Cross-Tenant Metrics & Benchmark Client.
 * Governs privacy-preserving aggregate metrics inspection, k-anonymity verification,
 * and tenant-isolated comparative benchmarking.
 */
public final class PosCrossTenantMetricsClient {

    private static final Pattern SNAPSHOT_ID_PATTERN = Pattern.compile("^ctm-[a-z0-9_-]{8,64}$");
    public static final int DEFAULT_MIN_COHORT_THRESHOLD = 3;

    private static final Set<String> PLATFORM_ADMIN_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR"
    );

    private static final Set<String> BENCHMARK_AUTHORIZED_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR",
            "PHARMACY_OWNER",
            "OWNER",
            "BRANCH_MANAGER",
            "STORE_MANAGER",
            "ADMIN"
    );

    private static final Set<String> FORBIDDEN_PHI_FIELDS = Set.of(
            "patient_name",
            "patient_phone",
            "patient_mobile",
            "nid",
            "doctor_name",
            "doctor_license",
            "prescription_image"
    );

    private PosCrossTenantMetricsClient() {
        // Utility class
    }

    /**
     * RBAC Invariant: Only platform super administrators can manage macro-telemetry.
     */
    public static boolean canRoleManagePlatformMetrics(String role) {
        if (role == null) {
            return false;
        }
        return PLATFORM_ADMIN_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * RBAC Invariant: Owners, Managers, and Platform Admins can view safe benchmarks.
     * Cashiers and Staff Pharmacists are strictly forbidden.
     */
    public static boolean canRoleViewBenchmarks(String role) {
        if (role == null) {
            return false;
        }
        return BENCHMARK_AUTHORIZED_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Validates snapshot ID syntax according to ADR-0075 contract.
     */
    public static boolean isValidSnapshotId(String snapshotId) {
        if (snapshotId == null) {
            return false;
        }
        return SNAPSHOT_ID_PATTERN.matcher(snapshotId.trim().toLowerCase()).matches();
    }

    /**
     * k-Anonymity Guardrail: Returns true if cohort size is below minimum threshold and must be suppressed.
     */
    public static boolean isCohortSuppressed(int contributingTenants, int minThreshold) {
        return contributingTenants < Math.max(2, minThreshold);
    }

    /**
     * Verifies that the JSON string contains zero patient health information (PHI) fields.
     */
    public static boolean validateZeroPhiGuaranteed(String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            return true;
        }
        String lower = jsonPayload.toLowerCase();
        for (String phiField : FORBIDDEN_PHI_FIELDS) {
            if (lower.contains("\"" + phiField + "\"") || lower.contains(phiField + ":")) {
                return false;
            }
        }
        return true;
    }

    /**
     * State machine validator for CrossTenantMetricsSnapshot lifecycle.
     */
    public static boolean isValidStateTransition(String fromStatus, String toStatus) {
        if (fromStatus == null || toStatus == null) {
            return false;
        }
        String from = fromStatus.toUpperCase().trim();
        String to = toStatus.toUpperCase().trim();

        if (Objects.equals(from, to)) {
            return true;
        }

        return switch (from) {
            case "DRAFT" -> Set.of("PUBLISHED", "ARCHIVED", "SUPPRESSED").contains(to);
            case "PUBLISHED" -> Set.of("ARCHIVED", "SUPPRESSED").contains(to);
            case "SUPPRESSED" -> Set.of("ARCHIVED", "PUBLISHED").contains(to);
            default -> false; // ARCHIVED is terminal
        };
    }

    /**
     * Builds platform cross-tenant metrics REST URL.
     */
    public static String buildPlatformMetricsUrl(String baseUrl, String snapshotId) {
        String cleanBase = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        if (snapshotId == null || snapshotId.isBlank()) {
            return cleanBase + "/api/v2/platform/cross-tenant-metrics/";
        }
        if (!isValidSnapshotId(snapshotId)) {
            throw new IllegalArgumentException("Invalid snapshotId: " + snapshotId);
        }
        return cleanBase + "/api/v2/platform/cross-tenant-metrics/" + snapshotId.trim() + "/";
    }

    /**
     * Builds tenant comparative benchmarks REST URL.
     */
    public static String buildTenantBenchmarkUrl(String baseUrl, String tenantId) {
        String cleanBase = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        if (tenantId == null || tenantId.isBlank()) {
            return cleanBase + "/api/v2/analytics/platform-benchmarks/";
        }
        return cleanBase + "/api/v2/analytics/platform-benchmarks/?tenant_id=" + tenantId.trim();
    }

    /**
     * Localized suppression explanation for UI display when cohort < k.
     */
    public static String getLocalizedSuppressionMessage(boolean isBangla) {
        return isBangla
                ? "গোপনীয়তা রক্ষা: অংশগ্রহণকারী ফার্মেসির সংখ্যা নূন্যতম সীমার নিচে হওয়ায় ডেটা গোপন রাখা হয়েছে।"
                : "Privacy Guardrail: Data suppressed as the reporting cohort is below the minimum k-anonymity threshold.";
    }
}
