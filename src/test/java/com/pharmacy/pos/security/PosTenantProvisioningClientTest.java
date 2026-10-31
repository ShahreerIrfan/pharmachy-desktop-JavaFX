package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0062: Desktop Tenant Provisioning Client Tests")
class PosTenantProvisioningClientTest {

    @Test
    @DisplayName("Subdomain validation accepts valid slugs and rejects reserved names")
    void testSubdomainValidation() {
        assertTrue(PosTenantProvisioningClient.isSubdomainValid("greenlife-pharma"));
        assertTrue(PosTenantProvisioningClient.isSubdomainValid("care-plus-01"));

        // Reserved subdomains must be rejected
        assertFalse(PosTenantProvisioningClient.isSubdomainValid("admin"));
        assertFalse(PosTenantProvisioningClient.isSubdomainValid("api"));
        assertFalse(PosTenantProvisioningClient.isSubdomainValid("system"));
        assertFalse(PosTenantProvisioningClient.isSubdomainValid("superadmin"));

        // Malformed characters
        assertFalse(PosTenantProvisioningClient.isSubdomainValid("invalid_name"));
        assertFalse(PosTenantProvisioningClient.isSubdomainValid("-starting-hyphen"));
        assertFalse(PosTenantProvisioningClient.isSubdomainValid("ab")); // too short
    }

    @Test
    @DisplayName("Owner phone validation enforces Bangladesh E.164 standard")
    void testOwnerPhoneValidation() {
        assertTrue(PosTenantProvisioningClient.isOwnerPhoneValid("+8801712345678"));
        assertTrue(PosTenantProvisioningClient.isOwnerPhoneValid("+8801999887766"));

        assertFalse(PosTenantProvisioningClient.isOwnerPhoneValid("01712345678")); // missing +88
        assertFalse(PosTenantProvisioningClient.isOwnerPhoneValid("+8801112345678")); // invalid operator 011
        assertFalse(PosTenantProvisioningClient.isOwnerPhoneValid("+8801712345")); // too short
    }

    @Test
    @DisplayName("Only Super Admin or Platform Operator can provision tenants")
    void testSuperAdminAuthorization() {
        assertTrue(PosTenantProvisioningClient.isProvisioningAuthorized("SUPERADMIN"));
        assertTrue(PosTenantProvisioningClient.isProvisioningAuthorized("PLATFORM_OPERATOR"));

        assertFalse(PosTenantProvisioningClient.isProvisioningAuthorized("PHARMACY_OWNER"));
        assertFalse(PosTenantProvisioningClient.isProvisioningAuthorized("BRANCH_MANAGER"));
        assertFalse(PosTenantProvisioningClient.isProvisioningAuthorized("CASHIER"));
    }

    @Test
    @DisplayName("Password complexity requires at least 8 characters")
    void testPasswordComplexity() {
        assertTrue(PosTenantProvisioningClient.isPasswordSufficient("StrongPassword123"));
        assertFalse(PosTenantProvisioningClient.isPasswordSufficient("short"));
        assertFalse(PosTenantProvisioningClient.isPasswordSufficient(null));
    }
}
