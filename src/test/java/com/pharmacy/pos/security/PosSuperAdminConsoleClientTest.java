package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0063: Desktop Platform Super Admin Console Client Tests")
class PosSuperAdminConsoleClientTest {

    @Test
    @DisplayName("Platform console is strictly restricted to SUPERADMIN and PLATFORM_OPERATOR")
    void testPlatformConsoleRoleAuthorization() {
        assertTrue(PosSuperAdminConsoleClient.canAccessPlatformConsole("SUPERADMIN"));
        assertTrue(PosSuperAdminConsoleClient.canAccessPlatformConsole("SUPER_ADMIN"));
        assertTrue(PosSuperAdminConsoleClient.canAccessPlatformConsole("PLATFORM_OPERATOR"));

        // Tenant roles must always be denied
        assertFalse(PosSuperAdminConsoleClient.canAccessPlatformConsole("TENANT_OWNER"));
        assertFalse(PosSuperAdminConsoleClient.canAccessPlatformConsole("BRANCH_MANAGER"));
        assertFalse(PosSuperAdminConsoleClient.canAccessPlatformConsole("PHARMACIST"));
        assertFalse(PosSuperAdminConsoleClient.canAccessPlatformConsole("CASHIER"));
        assertFalse(PosSuperAdminConsoleClient.canAccessPlatformConsole("ACCOUNTANT"));
        assertFalse(PosSuperAdminConsoleClient.canAccessPlatformConsole(null));
    }

    @Test
    @DisplayName("Platform controls are never visible inside tenant POS UI")
    void testPlatformControlsNeverVisibleInTenantUI() {
        assertFalse(PosSuperAdminConsoleClient.isPlatformControlVisibleInTenantUI());
    }

    @Test
    @DisplayName("Endpoint resolution grants Super Admin and rejects Tenant Owner with SecurityException")
    void testEndpointResolutionPrivilegeSeparation() {
        assertEquals("/api/v2/platform/console/metrics/", PosSuperAdminConsoleClient.getPlatformMetricsUrl("SUPERADMIN"));
        assertEquals("/api/v2/platform/console/tenants/", PosSuperAdminConsoleClient.getTenantDirectoryUrl("SUPERADMIN"));
        assertEquals("/api/v2/platform/console/tenants/tenant-123/", PosSuperAdminConsoleClient.getTenantInspectionUrl("SUPERADMIN", "tenant-123"));

        assertThrows(SecurityException.class, () -> PosSuperAdminConsoleClient.getPlatformMetricsUrl("TENANT_OWNER"));
        assertThrows(SecurityException.class, () -> PosSuperAdminConsoleClient.getTenantDirectoryUrl("CASHIER"));
        assertThrows(SecurityException.class, () -> PosSuperAdminConsoleClient.getTenantInspectionUrl("TENANT_OWNER", "tenant-123"));
    }

    @Test
    @DisplayName("Tenant inspection enforces valid tenant identifier format")
    void testTenantInspectionValidation() {
        assertThrows(IllegalArgumentException.class, () -> PosSuperAdminConsoleClient.getTenantInspectionUrl("SUPERADMIN", ""));
        assertThrows(IllegalArgumentException.class, () -> PosSuperAdminConsoleClient.getTenantInspectionUrl("SUPERADMIN", "ab")); // Too short
        assertThrows(IllegalArgumentException.class, () -> PosSuperAdminConsoleClient.getTenantInspectionUrl("SUPERADMIN", "bad tenant!"));
    }

    @Test
    @DisplayName("Platform action validation accepts valid actions and rejects invalid or short reasons")
    void testPlatformActionValidation() {
        assertDoesNotThrow(() -> PosSuperAdminConsoleClient.validatePlatformAction(
                "SUPERADMIN", "BROADCAST_ANNOUNCEMENT", "Scheduled network maintenance at 02:00 AM"));

        assertThrows(IllegalArgumentException.class, () -> PosSuperAdminConsoleClient.validatePlatformAction(
                "SUPERADMIN", "UNKNOWN_ACTION", "Valid reason"));
        assertThrows(IllegalArgumentException.class, () -> PosSuperAdminConsoleClient.validatePlatformAction(
                "SUPERADMIN", "SET_MAINTENANCE_MODE", "bad")); // Less than 5 chars
        assertThrows(SecurityException.class, () -> PosSuperAdminConsoleClient.validatePlatformAction(
                "TENANT_OWNER", "BROADCAST_ANNOUNCEMENT", "Valid reason here"));
    }
}

