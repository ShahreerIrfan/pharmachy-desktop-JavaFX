package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0065: Desktop Platform Super Admin Tenant Search and Filter Tests")
class PosTenantSearchFilterClientTest {

    @Test
    @DisplayName("Tenant search is strictly restricted to SUPERADMIN and PLATFORM_OPERATOR")
    void testTenantSearchRoleAuthorization() {
        assertTrue(PosTenantSearchFilterClient.canSearchTenants("SUPERADMIN"));
        assertTrue(PosTenantSearchFilterClient.canSearchTenants("SUPER_ADMIN"));
        assertTrue(PosTenantSearchFilterClient.canSearchTenants("PLATFORM_OPERATOR"));

        // Store-level roles must never be allowed
        assertFalse(PosTenantSearchFilterClient.canSearchTenants("TENANT_OWNER"));
        assertFalse(PosTenantSearchFilterClient.canSearchTenants("BRANCH_MANAGER"));
        assertFalse(PosTenantSearchFilterClient.canSearchTenants("PHARMACIST"));
        assertFalse(PosTenantSearchFilterClient.canSearchTenants("CASHIER"));
        assertFalse(PosTenantSearchFilterClient.canSearchTenants(null));
    }

    @Test
    @DisplayName("Tenant search is never visible in regular store POS UI")
    void testTenantSearchOmittedInStoreUI() {
        assertFalse(PosTenantSearchFilterClient.isTenantSearchVisibleInStoreUI());
    }

    @Test
    @DisplayName("Guarantees patient-level records are detected and excluded from tenant search")
    void testPatientLevelDataDetection() {
        Map<String, Object> safeTenant = Map.of(
                "tenant_id", "tenant-101",
                "name", "Green Life Pharma",
                "subdomain", "greenlife",
                "status", "ACTIVE"
        );
        assertFalse(PosTenantSearchFilterClient.containsPatientLevelData(safeTenant));

        Map<String, Object> contaminatedTenant = new HashMap<>(safeTenant);
        contaminatedTenant.put("patient_name", "Kazi Nazrul");
        assertTrue(PosTenantSearchFilterClient.containsPatientLevelData(contaminatedTenant));

        Map<String, Object> prescriptionContaminated = new HashMap<>(safeTenant);
        prescriptionContaminated.put("prescriptions", List.of("Rx-999"));
        assertTrue(PosTenantSearchFilterClient.containsPatientLevelData(prescriptionContaminated));
    }

    @Test
    @DisplayName("Maps lifecycle status chips and approved actions accurately")
    void testStatusChipsAndApprovedActions() {
        assertEquals("success", PosTenantSearchFilterClient.getStatusChipTone("ACTIVE"));
        assertEquals("warning", PosTenantSearchFilterClient.getStatusChipTone("TRIAL"));
        assertEquals("danger", PosTenantSearchFilterClient.getStatusChipTone("SUSPENDED"));
        assertEquals("neutral", PosTenantSearchFilterClient.getStatusChipTone("ARCHIVED"));

        List<String> activeActions = PosTenantSearchFilterClient.getApprovedActionsForStatus("ACTIVE");
        assertTrue(activeActions.contains("SUSPEND"));
        assertTrue(activeActions.contains("ARCHIVE"));

        List<String> suspendedActions = PosTenantSearchFilterClient.getApprovedActionsForStatus("SUSPENDED");
        assertTrue(suspendedActions.contains("REINSTATE"));
        assertFalse(suspendedActions.contains("UPDATE_PLAN"));
    }

    @Test
    @DisplayName("Search URL builder enforces role checks, page bounds, and filter validation")
    void testBuildSearchUrlValidation() {
        String url = PosTenantSearchFilterClient.buildSearchUrl(
                "SUPERADMIN", "pharma", "ACTIVE", "STANDARD", 1, 25
        );
        assertEquals("/api/v2/platform/console/tenants/?page=1&page_size=25&search=pharma&status=ACTIVE&plan_tier=STANDARD", url);

        assertThrows(SecurityException.class, () ->
                PosTenantSearchFilterClient.buildSearchUrl("TENANT_OWNER", null, null, null, 1, 20));
        assertThrows(IllegalArgumentException.class, () ->
                PosTenantSearchFilterClient.buildSearchUrl("SUPERADMIN", null, null, null, 0, 20));
        assertThrows(IllegalArgumentException.class, () ->
                PosTenantSearchFilterClient.buildSearchUrl("SUPERADMIN", null, null, null, 1, 150));
        assertThrows(IllegalArgumentException.class, () ->
                PosTenantSearchFilterClient.buildSearchUrl("SUPERADMIN", null, "INVALID_STATUS", null, 1, 20));
    }
}
