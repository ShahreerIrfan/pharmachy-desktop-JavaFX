package com.pharmacy.pos.security;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-0068: Desktop POS Support Access Governance and Impersonation Prevention Client.
 * Strictly bans unsafe impersonation ("ghost logins"), evaluates active in-app notice banners,
 * enforces clinical dispensing prohibition, and provides instant emergency kill-switch URLs.
 */
public final class PosSupportAccessGovernanceClient {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-z0-9_-]{3,64}$");

    private static final Set<String> VALID_SUPPORT_SCOPES = Set.of(
            "DIAGNOSTIC_READ",
            "BILLING_READ",
            "BREAK_GLASS_REPAIR"
    );

    private PosSupportAccessGovernanceClient() {
        // Utility / boundary class
    }

    /**
     * Architectural Invariant: Direct user impersonation ("Login As") is strictly forbidden.
     */
    public static boolean isDirectUserImpersonationPermitted() {
        return false;
    }

    /**
     * Invariant: Support engineers under ANY scope are strictly barred from clinical dispensing.
     */
    public static boolean isClinicalDispensingPermitted(String scope) {
        return false;
    }

    /**
     * Checks if a scope grants diagnostic system inspection.
     */
    public static boolean isDiagnosticInspectionPermitted(String scope) {
        if (scope == null) {
            return false;
        }
        String clean = scope.toUpperCase().trim();
        return "DIAGNOSTIC_READ".equals(clean) || "BREAK_GLASS_REPAIR".equals(clean);
    }

    /**
     * Determines whether an in-app notice banner should be displayed in the POS header.
     */
    public static boolean shouldDisplayNoticeBanner(Map<String, Object> noticeData) {
        if (noticeData == null) {
            return false;
        }
        Object showBanner = noticeData.get("show_banner");
        if (showBanner instanceof Boolean) {
            return (Boolean) showBanner;
        }
        return false;
    }

    /**
     * Resolves the alert tone for the notice banner (warning for standard, danger for break-glass).
     */
    public static String resolveNoticeTone(Map<String, Object> noticeData) {
        if (noticeData == null) {
            return "neutral";
        }
        Object tone = noticeData.get("tone");
        if (tone != null) {
            String clean = tone.toString().toLowerCase().trim();
            if ("danger".equals(clean) || "warning".equals(clean)) {
                return clean;
            }
        }
        return "neutral";
    }

    /**
     * Builds the tenant-side endpoint URL for retrieving real-time active support notice.
     */
    public static String buildActiveNoticeUrl(String tenantId) {
        if (tenantId == null || !IDENTIFIER_PATTERN.matcher(tenantId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid tenant identifier: '" + tenantId + "'");
        }
        return "/api/v2/tenants/support-access/" + tenantId.trim() + "/active/";
    }

    /**
     * Builds the tenant-side endpoint URL for immediate support revocation ("Kill Switch").
     */
    public static String buildRevokeUrl(String tenantId, String grantId) {
        if (tenantId == null || !IDENTIFIER_PATTERN.matcher(tenantId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid tenant identifier: '" + tenantId + "'");
        }
        if (grantId == null || !IDENTIFIER_PATTERN.matcher(grantId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid grant identifier: '" + grantId + "'");
        }
        return "/api/v2/tenants/support-access/" + tenantId.trim() + "/" + grantId.trim() + "/revoke/";
    }

    /**
     * Validates if a scope string is recognized under ADR-0068.
     */
    public static boolean isValidScope(String scope) {
        if (scope == null) {
            return false;
        }
        return VALID_SUPPORT_SCOPES.contains(scope.toUpperCase().trim());
    }
}
