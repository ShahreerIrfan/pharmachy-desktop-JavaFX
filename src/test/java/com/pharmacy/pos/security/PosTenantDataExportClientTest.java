package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0073: JavaFX Desktop POS Tenant Data Export Client Tests")
class PosTenantDataExportClientTest {

    @Test
    @DisplayName("Role RBAC: Pharmacy Owner and SuperAdmin can request; Cashier and Pharmacist blocked")
    void testCanRoleRequestExport() {
        assertTrue(PosTenantDataExportClient.canRoleRequestExport("PHARMACY_OWNER"));
        assertTrue(PosTenantDataExportClient.canRoleRequestExport("OWNER"));
        assertTrue(PosTenantDataExportClient.canRoleRequestExport("SUPERADMIN"));
        assertTrue(PosTenantDataExportClient.canRoleRequestExport("PLATFORM_OPERATOR"));

        assertFalse(PosTenantDataExportClient.canRoleRequestExport("BRANCH_MANAGER"));
        assertFalse(PosTenantDataExportClient.canRoleRequestExport("STAFF_PHARMACIST"));
        assertFalse(PosTenantDataExportClient.canRoleRequestExport("CASHIER"));
        assertFalse(PosTenantDataExportClient.canRoleRequestExport(null));
    }

    @Test
    @DisplayName("Role RBAC: Only SuperAdmin and Platform Operator can approve/reject exports")
    void testCanRoleApproveExport() {
        assertTrue(PosTenantDataExportClient.canRoleApproveExport("SUPERADMIN"));
        assertTrue(PosTenantDataExportClient.canRoleApproveExport("SUPER_ADMIN"));
        assertTrue(PosTenantDataExportClient.canRoleApproveExport("PLATFORM_OPERATOR"));

        assertFalse(PosTenantDataExportClient.canRoleApproveExport("PHARMACY_OWNER"));
        assertFalse(PosTenantDataExportClient.canRoleApproveExport("BRANCH_MANAGER"));
        assertFalse(PosTenantDataExportClient.canRoleApproveExport("CASHIER"));
        assertFalse(PosTenantDataExportClient.canRoleApproveExport(null));
    }

    @Test
    @DisplayName("Export ID validation: Must start with tde- followed by 8-64 alphanumeric chars")
    void testIsValidExportId() {
        assertTrue(PosTenantDataExportClient.isValidExportId("tde-a1b2c3d4e5f6"));
        assertTrue(PosTenantDataExportClient.isValidExportId("tde-tenant_export_2026"));

        assertFalse(PosTenantDataExportClient.isValidExportId("invalid-no-prefix"));
        assertFalse(PosTenantDataExportClient.isValidExportId("tde-short"));
        assertFalse(PosTenantDataExportClient.isValidExportId("tde-invalid with spaces"));
        assertFalse(PosTenantDataExportClient.isValidExportId(null));
    }

    @Test
    @DisplayName("Format validation: Only JSON and CSV are supported")
    void testIsValidFormat() {
        assertTrue(PosTenantDataExportClient.isValidFormat("JSON"));
        assertTrue(PosTenantDataExportClient.isValidFormat("json"));
        assertTrue(PosTenantDataExportClient.isValidFormat("CSV"));
        assertTrue(PosTenantDataExportClient.isValidFormat("csv"));

        assertFalse(PosTenantDataExportClient.isValidFormat("XML"));
        assertFalse(PosTenantDataExportClient.isValidFormat("SQL"));
        assertFalse(PosTenantDataExportClient.isValidFormat(null));
    }

    @Test
    @DisplayName("Lifecycle State Machine: Validates strict state transitions")
    void testCanTransition() {
        assertTrue(PosTenantDataExportClient.canTransition("REQUESTED", "APPROVED"));
        assertTrue(PosTenantDataExportClient.canTransition("REQUESTED", "REJECTED"));
        assertTrue(PosTenantDataExportClient.canTransition("APPROVED", "PROCESSING"));
        assertTrue(PosTenantDataExportClient.canTransition("PROCESSING", "COMPLETED"));
        assertTrue(PosTenantDataExportClient.canTransition("COMPLETED", "EXPIRED"));

        assertFalse(PosTenantDataExportClient.canTransition("REQUESTED", "COMPLETED"));
        assertFalse(PosTenantDataExportClient.canTransition("REJECTED", "COMPLETED"));
        assertFalse(PosTenantDataExportClient.canTransition("EXPIRED", "REQUESTED"));
        assertFalse(PosTenantDataExportClient.canTransition(null, "APPROVED"));
    }

    @Test
    @DisplayName("Builds endpoint URLs correctly with validation")
    void testBuildUrls() {
        assertEquals("/api/v2/tenants/data-exports/", PosTenantDataExportClient.buildTenantExportListUrl());
        assertEquals("/api/v2/tenants/data-exports/tde-12345678/", PosTenantDataExportClient.buildTenantExportDetailUrl("tde-12345678"));
        assertEquals(
                "/api/v2/tenants/data-exports/tde-12345678/download/?token=sec-token-999",
                PosTenantDataExportClient.buildDownloadUrl("tde-12345678", "sec-token-999")
        );

        assertEquals("/api/v2/platform/data-exports/", PosTenantDataExportClient.buildPlatformExportListUrl());
        assertEquals("/api/v2/platform/data-exports/tde-12345678/approve/", PosTenantDataExportClient.buildPlatformApproveUrl("tde-12345678"));
        assertEquals("/api/v2/platform/data-exports/tde-12345678/reject/", PosTenantDataExportClient.buildPlatformRejectUrl("tde-12345678"));
        assertEquals("/api/v2/platform/data-exports/tde-12345678/revoke/", PosTenantDataExportClient.buildPlatformRevokeUrl("tde-12345678"));

        assertThrows(IllegalArgumentException.class, () -> PosTenantDataExportClient.buildTenantExportDetailUrl("invalid-id"));
        assertThrows(IllegalArgumentException.class, () -> PosTenantDataExportClient.buildDownloadUrl("tde-12345678", ""));
    }
}
