package com.pharmacy.pos.security;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-0073: JavaFX Desktop POS Tenant Data Export Administration Client.
 * Governs tenant data export requests, administrative approvals,
 * PII redaction choices, and expiring signed download token validation.
 */
public final class PosTenantDataExportClient {

    private static final Pattern EXPORT_ID_PATTERN = Pattern.compile("^tde-[a-z0-9_-]{8,64}$");

    private static final Set<String> ALLOWED_REQUEST_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR",
            "PHARMACY_OWNER",
            "OWNER"
    );

    private static final Set<String> ALLOWED_APPROVAL_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR"
    );

    private static final Set<String> VALID_FORMATS = Set.of(
            "JSON",
            "CSV"
    );

    private PosTenantDataExportClient() {
        // Utility class
    }

    /**
     * RBAC Invariant: Only Pharmacy Owners and SuperAdmins can initiate export requests.
     * Cashiers, dispensary staff, and inventory clerks are blocked.
     */
    public static boolean canRoleRequestExport(String role) {
        if (role == null) {
            return false;
        }
        return ALLOWED_REQUEST_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * RBAC Invariant: Only Platform SuperAdmins and Operators can approve/reject export requests.
     */
    public static boolean canRoleApproveExport(String role) {
        if (role == null) {
            return false;
        }
        return ALLOWED_APPROVAL_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Validates export format.
     */
    public static boolean isValidFormat(String format) {
        if (format == null) {
            return false;
        }
        return VALID_FORMATS.contains(format.toUpperCase().trim());
    }

    /**
     * Validates export identifier syntax.
     */
    public static boolean isValidExportId(String exportId) {
        if (exportId == null) {
            return false;
        }
        return EXPORT_ID_PATTERN.matcher(exportId.trim()).matches();
    }

    /**
     * Verifies lifecycle transition validity.
     */
    public static boolean canTransition(String currentStatus, String targetStatus) {
        if (currentStatus == null || targetStatus == null) {
            return false;
        }
        String cur = currentStatus.toUpperCase().trim();
        String tgt = targetStatus.toUpperCase().trim();

        return switch (cur) {
            case "REQUESTED" -> Set.of("APPROVED", "REJECTED").contains(tgt);
            case "APPROVED" -> Set.of("PROCESSING", "FAILED").contains(tgt);
            case "PROCESSING" -> Set.of("COMPLETED", "FAILED").contains(tgt);
            case "COMPLETED" -> Set.of("EXPIRED").contains(tgt);
            default -> false;
        };
    }

    /**
     * Builds tenant-scoped export list endpoint.
     */
    public static String buildTenantExportListUrl() {
        return "/api/v2/tenants/data-exports/";
    }

    /**
     * Builds tenant-scoped export detail endpoint.
     */
    public static String buildTenantExportDetailUrl(String exportId) {
        String clean = exportId != null ? exportId.trim() : "";
        if (!isValidExportId(clean)) {
            throw new IllegalArgumentException("Invalid export ID: '" + exportId + "'");
        }
        return "/api/v2/tenants/data-exports/" + clean + "/";
    }

    /**
     * Builds token-authenticated download URL.
     */
    public static String buildDownloadUrl(String exportId, String token) {
        String cleanId = exportId != null ? exportId.trim() : "";
        if (!isValidExportId(cleanId)) {
            throw new IllegalArgumentException("Invalid export ID: '" + exportId + "'");
        }
        String cleanToken = token != null ? token.trim() : "";
        if (cleanToken.isEmpty()) {
            throw new IllegalArgumentException("Download token must not be empty");
        }
        return "/api/v2/tenants/data-exports/" + cleanId + "/download/?token=" + cleanToken;
    }

    /**
     * Builds platform-wide export admin list endpoint.
     */
    public static String buildPlatformExportListUrl() {
        return "/api/v2/platform/data-exports/";
    }

    /**
     * Builds platform export approval endpoint.
     */
    public static String buildPlatformApproveUrl(String exportId) {
        String clean = exportId != null ? exportId.trim() : "";
        if (!isValidExportId(clean)) {
            throw new IllegalArgumentException("Invalid export ID: '" + exportId + "'");
        }
        return "/api/v2/platform/data-exports/" + clean + "/approve/";
    }

    /**
     * Builds platform export rejection endpoint.
     */
    public static String buildPlatformRejectUrl(String exportId) {
        String clean = exportId != null ? exportId.trim() : "";
        if (!isValidExportId(clean)) {
            throw new IllegalArgumentException("Invalid export ID: '" + exportId + "'");
        }
        return "/api/v2/platform/data-exports/" + clean + "/reject/";
    }

    /**
     * Builds platform export revocation endpoint.
     */
    public static String buildPlatformRevokeUrl(String exportId) {
        String clean = exportId != null ? exportId.trim() : "";
        if (!isValidExportId(clean)) {
            throw new IllegalArgumentException("Invalid export ID: '" + exportId + "'");
        }
        return "/api/v2/platform/data-exports/" + clean + "/revoke/";
    }
}
