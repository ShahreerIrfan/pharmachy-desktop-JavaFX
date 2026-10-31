package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0064: Desktop Platform Super Admin KPI Cards Client Tests")
class PosSuperAdminKpiCardsClientTest {

    @Test
    @DisplayName("Platform KPI cards are strictly restricted to SUPERADMIN and PLATFORM_OPERATOR")
    void testPlatformKpiRoleAuthorization() {
        assertTrue(PosSuperAdminKpiCardsClient.canAccessPlatformKpis("SUPERADMIN"));
        assertTrue(PosSuperAdminKpiCardsClient.canAccessPlatformKpis("SUPER_ADMIN"));
        assertTrue(PosSuperAdminKpiCardsClient.canAccessPlatformKpis("PLATFORM_OPERATOR"));

        // Tenant store roles must never have access to platform KPI cards
        assertFalse(PosSuperAdminKpiCardsClient.canAccessPlatformKpis("TENANT_OWNER"));
        assertFalse(PosSuperAdminKpiCardsClient.canAccessPlatformKpis("BRANCH_MANAGER"));
        assertFalse(PosSuperAdminKpiCardsClient.canAccessPlatformKpis("PHARMACIST"));
        assertFalse(PosSuperAdminKpiCardsClient.canAccessPlatformKpis("CASHIER"));
        assertFalse(PosSuperAdminKpiCardsClient.canAccessPlatformKpis(null));
    }

    @Test
    @DisplayName("Platform KPI cards are never rendered in tenant POS UI")
    void testPlatformKpiCardsOmittedFromTenantUI() {
        assertFalse(PosSuperAdminKpiCardsClient.isKpiCardVisibleInTenantUI());
    }

    @Test
    @DisplayName("URL generation validates Super Admin and rejects non-BDT or invalid period")
    void testUrlGenerationAndValidation() {
        assertEquals(
                "/api/v2/platform/console/kpis/?currency=BDT&period=current",
                PosSuperAdminKpiCardsClient.getKpiCardsUrl("SUPERADMIN", "BDT", "current")
        );
        assertEquals(
                "/api/v2/platform/console/kpis/?currency=BDT&period=30d",
                PosSuperAdminKpiCardsClient.getKpiCardsUrl("SUPERADMIN", "bdt", "30d")
        );

        assertThrows(SecurityException.class, () ->
                PosSuperAdminKpiCardsClient.getKpiCardsUrl("TENANT_OWNER", "BDT", "current"));
        assertThrows(IllegalArgumentException.class, () ->
                PosSuperAdminKpiCardsClient.getKpiCardsUrl("SUPERADMIN", "USD", "current"));
        assertThrows(IllegalArgumentException.class, () ->
                PosSuperAdminKpiCardsClient.getKpiCardsUrl("SUPERADMIN", "BDT", "invalid_period"));
    }

    @Test
    @DisplayName("Monetary revenue formatting formats Decimal BDT strings safely")
    void testRevenueFormatting() {
        assertEquals("৳ 125000.00", PosSuperAdminKpiCardsClient.formatBdtRevenue("125000.00"));
        assertEquals("৳ 5000.50", PosSuperAdminKpiCardsClient.formatBdtRevenue("5000.5"));
        assertEquals("৳ 0.00", PosSuperAdminKpiCardsClient.formatBdtRevenue(null));

        assertThrows(IllegalArgumentException.class, () ->
                PosSuperAdminKpiCardsClient.formatBdtRevenue("not-a-number"));
    }
}
