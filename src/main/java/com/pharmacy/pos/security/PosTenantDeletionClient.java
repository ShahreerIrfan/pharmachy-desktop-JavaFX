package com.pharmacy.pos.security;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-0074: JavaFX Desktop POS Tenant Deletion Assessment & Purge Client.
 * Governs tenant offboarding requests, statutory retention hold evaluations,
 * and irreversible physical purge confirmation guardrails.
 */
public final class PosTenantDeletionClient {

    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^tdr-[a-z0-9_-]{8,64}$");
    public static final String MANDATORY_CONFIRMATION_TEXT = "CONFIRM_PERMANENT_DELETION";

    private static final Set<String> ALLOWED_REQUEST_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR",
            "PHARMACY_OWNER",
            "OWNER"
    );

    private static final Set<String> ALLOWED_ASSESSMENT_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR"
    );

    private PosTenantDeletionClient() {
        // Utility class
    }

    /**
     * RBAC Invariant: Only Pharmacy Owners and SuperAdmins can initiate deletion requests.
     */
    public static boolean canRoleRequestDeletion(String role) {
        if (role == null) {
            return false;
        }
        return ALLOWED_REQUEST_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * RBAC Invariant: Only Platform SuperAdmins can assess retention holds, reject requests, or execute purges.
     */
    public static boolean canRoleAssessOrPurge(String role) {
        if (role == null) {
            return false;
        }
        return ALLOWED_ASSESSMENT_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Validates deletion request identifier syntax.
     */
    public static boolean isValidRequestId(String requestId) {
        if (requestId == null) {
            return false;
        }
        return REQUEST_ID_PATTERN.matcher(requestId.trim()).matches();
    }

    /**
     * Statutory Retention Guardrail: Physical purge is blocked if active clinical or tax holds exist.
     */
    public static boolean isPhysicalPurgeBlocked(boolean hasActiveHolds) {
        return hasActiveHolds;
    }

    /**
     * Validates explicit multi-step confirmation phrase.
     */
    public static boolean isConfirmationTextValid(String confirmationText) {
        if (confirmationText == null) {
            return false;
        }
        return MANDATORY_CONFIRMATION_TEXT.equalsIgnoreCase(confirmationText.trim());
    }

    /**
     * Verifies state machine transition validity under ADR-0074.
     */
    public static boolean canTransition(String currentStatus, String targetStatus) {
        if (currentStatus == null || targetStatus == null) {
            return false;
        }
        String cur = currentStatus.toUpperCase().trim();
        String tgt = targetStatus.toUpperCase().trim();

        return switch (cur) {
            case "SUBMITTED" -> Set.of("UNDER_REVIEW", "HOLD_ACTIVE", "ELIGIBLE_FOR_PURGE", "CANCELLED").contains(tgt);
            case "UNDER_REVIEW" -> Set.of("HOLD_ACTIVE", "ELIGIBLE_FOR_PURGE", "REJECTED", "CANCELLED").contains(tgt);
            case "HOLD_ACTIVE" -> Set.of("ELIGIBLE_FOR_PURGE", "REJECTED", "CANCELLED").contains(tgt);
            case "ELIGIBLE_FOR_PURGE" -> Set.of("CONFIRMED", "PURGED", "REJECTED", "CANCELLED").contains(tgt);
            case "CONFIRMED" -> Set.of("PURGED").contains(tgt);
            default -> false;
        };
    }

    /**
     * Builds tenant-scoped deletion request list endpoint.
     */
    public static String buildTenantDeletionListUrl() {
        return "/api/v2/tenants/deletion-requests/";
    }

    /**
     * Builds tenant-scoped deletion request detail endpoint.
     */
    public static String buildTenantDeletionDetailUrl(String requestId) {
        String clean = requestId != null ? requestId.trim() : "";
        if (!isValidRequestId(clean)) {
            throw new IllegalArgumentException("Invalid request ID: '" + requestId + "'");
        }
        return "/api/v2/tenants/deletion-requests/" + clean + "/";
    }

    /**
     * Builds tenant-scoped cancellation endpoint.
     */
    public static String buildTenantCancelUrl(String requestId) {
        String clean = requestId != null ? requestId.trim() : "";
        if (!isValidRequestId(clean)) {
            throw new IllegalArgumentException("Invalid request ID: '" + requestId + "'");
        }
        return "/api/v2/tenants/deletion-requests/" + clean + "/cancel/";
    }

    /**
     * Builds platform-wide deletion assessment list endpoint.
     */
    public static String buildPlatformAssessmentListUrl() {
        return "/api/v2/platform/deletion-assessments/";
    }

    /**
     * Builds platform retention hold evaluation endpoint.
     */
    public static String buildPlatformAssessUrl(String requestId) {
        String clean = requestId != null ? requestId.trim() : "";
        if (!isValidRequestId(clean)) {
            throw new IllegalArgumentException("Invalid request ID: '" + requestId + "'");
        }
        return "/api/v2/platform/deletion-assessments/" + clean + "/assess/";
    }

    /**
     * Builds platform rejection endpoint.
     */
    public static String buildPlatformRejectUrl(String requestId) {
        String clean = requestId != null ? requestId.trim() : "";
        if (!isValidRequestId(clean)) {
            throw new IllegalArgumentException("Invalid request ID: '" + requestId + "'");
        }
        return "/api/v2/platform/deletion-assessments/" + clean + "/reject/";
    }

    /**
     * Builds platform irreversible purge execution endpoint.
     */
    public static String buildPlatformPurgeUrl(String requestId) {
        String clean = requestId != null ? requestId.trim() : "";
        if (!isValidRequestId(clean)) {
            throw new IllegalArgumentException("Invalid request ID: '" + requestId + "'");
        }
        return "/api/v2/platform/deletion-assessments/" + clean + "/purge/";
    }
}
