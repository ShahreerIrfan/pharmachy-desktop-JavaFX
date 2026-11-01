package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0077: JavaFX Desktop POS Platform Access Policies Client Tests")
class PosPlatformAccessClientTest {

    @Test
    @DisplayName("Role RBAC: Platform Roles Allowed, Tenant Roles Strictly Denied")
    void testRoleAccess() {
        assertTrue(PosPlatformAccessClient.canAccessPlatformApis("SUPERADMIN"));
        assertTrue(PosPlatformAccessClient.canAccessPlatformApis("SUPER_ADMIN"));
        assertTrue(PosPlatformAccessClient.canAccessPlatformApis("PLATFORM_OPERATOR"));
        assertTrue(PosPlatformAccessClient.canAccessPlatformApis("PLATFORM_AUDITOR"));

        assertFalse(PosPlatformAccessClient.canAccessPlatformApis("PHARMACY_OWNER"));
        assertFalse(PosPlatformAccessClient.canAccessPlatformApis("BRANCH_MANAGER"));
        assertFalse(PosPlatformAccessClient.canAccessPlatformApis("STAFF_PHARMACIST"));
        assertFalse(PosPlatformAccessClient.canAccessPlatformApis("CASHIER"));
        assertFalse(PosPlatformAccessClient.canAccessPlatformApis(null));
        assertFalse(PosPlatformAccessClient.canAccessPlatformApis(""));
    }

    @Test
    @DisplayName("Least Privilege: Only SuperAdmin can mutate security policies")
    void testLeastPrivilegeMutation() {
        assertTrue(PosPlatformAccessClient.canMutateSecurityPolicies("SUPERADMIN"));
        assertTrue(PosPlatformAccessClient.canMutateSecurityPolicies("SUPER_ADMIN"));

        assertFalse(PosPlatformAccessClient.canMutateSecurityPolicies("PLATFORM_OPERATOR"));
        assertFalse(PosPlatformAccessClient.canMutateSecurityPolicies("PLATFORM_AUDITOR"));
        assertFalse(PosPlatformAccessClient.canMutateSecurityPolicies("CASHIER"));
    }

    @Test
    @DisplayName("Zero Implicit Tenant Access: Platform Admin Blocked without Support Grant")
    void testZeroImplicitTenantAccess() {
        // Without grant token: Throws SecurityException
        assertThrows(SecurityException.class, () ->
                PosPlatformAccessClient.enforceZeroImplicitTenantAccess(
                        "SUPERADMIN",
                        "/api/v2/tenants/t-dhanmondi/orders/",
                        null
                )
        );

        // With valid grant token: Permitted
        assertTrue(PosPlatformAccessClient.enforceZeroImplicitTenantAccess(
                "SUPERADMIN",
                "/api/v2/tenants/t-dhanmondi/orders/",
                "grant-token-12345"
        ));

        // Public/announcement tenant route: Allowed without grant
        assertTrue(PosPlatformAccessClient.enforceZeroImplicitTenantAccess(
                "SUPERADMIN",
                "/api/v2/tenants/announcements/",
                null
        ));
    }

    @Test
    @DisplayName("Policy ID and TOTP Code Syntax Validation")
    void testSyntaxValidation() {
        assertTrue(PosPlatformAccessClient.isValidPolicyId("pol-console-read"));
        assertTrue(PosPlatformAccessClient.isValidPolicyId("pol-12345678"));
        assertFalse(PosPlatformAccessClient.isValidPolicyId("invalid-policy"));
        assertFalse(PosPlatformAccessClient.isValidPolicyId(null));

        assertTrue(PosPlatformAccessClient.isValidTotpCode("123456"));
        assertTrue(PosPlatformAccessClient.isValidTotpCode("000000"));
        assertFalse(PosPlatformAccessClient.isValidTotpCode("12345"));
        assertFalse(PosPlatformAccessClient.isValidTotpCode("1234567"));
        assertFalse(PosPlatformAccessClient.isValidTotpCode("abcdef"));
        assertFalse(PosPlatformAccessClient.isValidTotpCode(null));
    }

    @Test
    @DisplayName("Header Builder: Builds headers with MFA and step-up codes")
    void testHeaderBuilder() {
        Map<String, String> headers = PosPlatformAccessClient.buildPlatformRequestHeaders(
                "SUPERADMIN",
                "mfa-session-xyz",
                "654321",
                "support-grant-abc"
        );

        assertEquals("SUPERADMIN", headers.get("X-Platform-Role"));
        assertEquals("mfa-session-xyz", headers.get("X-MFA-Session-Token"));
        assertEquals("true", headers.get("X-MFA-Verified"));
        assertEquals("654321", headers.get("X-MFA-Code"));
        assertEquals("support-grant-abc", headers.get("X-Support-Grant-Token"));

        // Unauthorized role throws
        assertThrows(SecurityException.class, () ->
                PosPlatformAccessClient.buildPlatformRequestHeaders(
                        "CASHIER",
                        "token",
                        null,
                        null
                )
        );
    }

    @Test
    @DisplayName("UI Boundary Invariant: Platform policy controls never visible in POS")
    void testUiBoundaryInvariant() {
        assertFalse(PosPlatformAccessClient.isPlatformPolicyManagerVisibleInPos());
    }
}
