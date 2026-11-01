package com.pharmacy.pos.security;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Architecture Decision: ADR-0077
 * JavaFX Desktop POS Client for Platform API Access Policies, MFA Verification,
 * Least Privilege Role Checking, and Zero Implicit Tenant Access Enforcement.
 */
public final class PosPlatformAccessClient {

    private static final Set<String> PLATFORM_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR",
            "PLATFORM_AUDITOR"
    );

    private static final Set<String> POLICY_MUTATION_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN"
    );

    private static final Pattern POLICY_ID_PATTERN = Pattern.compile("^pol-[a-z0-9_-]{4,64}$");
    private static final Pattern TOTP_CODE_PATTERN = Pattern.compile("^\\d{6}$");

    private PosPlatformAccessClient() {
        // Prevent instantiation
    }

    /**
     * Checks if a given user role belongs to the authorized platform administrative roles.
     */
    public static boolean canAccessPlatformApis(String role) {
        if (role == null) {
            return false;
        }
        return PLATFORM_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Enforces least privilege: only SuperAdmins may create or modify platform access policies.
     */
    public static boolean canMutateSecurityPolicies(String role) {
        if (role == null) {
            return false;
        }
        return POLICY_MUTATION_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Invariant: Platform administrative controls and policy managers are NEVER visible
     * in the cashier / store POS interface.
     */
    public static boolean isPlatformPolicyManagerVisibleInPos() {
        return false;
    }

    /**
     * Validates policy ID format conforming to canonical contract.
     */
    public static boolean isValidPolicyId(String policyId) {
        if (policyId == null) {
            return false;
        }
        return POLICY_ID_PATTERN.matcher(policyId.trim()).matches();
    }

    /**
     * Validates a 6-digit TOTP code format for step-up verification.
     */
    public static boolean isValidTotpCode(String code) {
        if (code == null) {
            return false;
        }
        return TOTP_CODE_PATTERN.matcher(code.trim()).matches();
    }

    /**
     * Enforces Zero Implicit Tenant Access Guardrail:
     * Platform administrators attempting to access tenant operational data must present
     * a valid, approved delegated support grant token.
     */
    public static boolean enforceZeroImplicitTenantAccess(String role, String targetPath, String supportGrantToken) {
        Objects.requireNonNull(role, "Role cannot be null");
        Objects.requireNonNull(targetPath, "Target path cannot be null");

        boolean isPlatform = canAccessPlatformApis(role);
        boolean isTenantPrivatePath = targetPath.startsWith("/api/v2/tenants/") && !targetPath.contains("/announcements");

        if (isPlatform && isTenantPrivatePath) {
            if (supportGrantToken == null || supportGrantToken.trim().isEmpty()) {
                throw new SecurityException(
                        "Zero Implicit Tenant Access Denied: Platform administrators require an approved delegated support grant token to access tenant private operational data."
                );
            }
        }
        return true;
    }

    /**
     * Builds request headers for platform administrative requests.
     */
    public static Map<String, String> buildPlatformRequestHeaders(
            String role,
            String mfaSessionToken,
            String stepUpCode,
            String supportGrantToken
    ) {
        if (!canAccessPlatformApis(role)) {
            throw new SecurityException("Role '" + role + "' is not authorized for platform administration.");
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Platform-Role", role.toUpperCase().trim());

        if (mfaSessionToken != null && !mfaSessionToken.trim().isEmpty()) {
            headers.put("X-MFA-Session-Token", mfaSessionToken.trim());
            headers.put("X-MFA-Verified", "true");
        }

        if (stepUpCode != null && !stepUpCode.trim().isEmpty()) {
            if (!isValidTotpCode(stepUpCode)) {
                throw new IllegalArgumentException("Step-up MFA code must be exactly 6 digits.");
            }
            headers.put("X-MFA-Code", stepUpCode.trim());
        }

        if (supportGrantToken != null && !supportGrantToken.trim().isEmpty()) {
            headers.put("X-Support-Grant-Token", supportGrantToken.trim());
        }

        return Collections.unmodifiableMap(headers);
    }

    public static String getPoliciesUrl() {
        return "/api/v2/platform/access-policies/";
    }

    public static String getPolicyDetailUrl(String policyId) {
        if (!isValidPolicyId(policyId)) {
            throw new IllegalArgumentException("Invalid policy ID format: " + policyId);
        }
        return "/api/v2/platform/access-policies/" + policyId.trim() + "/";
    }

    public static String getEvaluateUrl() {
        return "/api/v2/platform/access-policies/evaluate/";
    }

    public static String getVerifyStepUpUrl() {
        return "/api/v2/platform/access-policies/verify-step-up/";
    }
}
