package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0066: Desktop Platform Super Admin Tenant Detail and Timeline Tests")
class PosTenantDetailTimelineClientTest {

    @Test
    @DisplayName("Tenant detail and timeline is strictly restricted to SUPERADMIN and PLATFORM_OPERATOR")
    void testTenantDetailRoleAuthorization() {
        assertTrue(PosTenantDetailTimelineClient.canAccessTenantDetail("SUPERADMIN"));
        assertTrue(PosTenantDetailTimelineClient.canAccessTenantDetail("SUPER_ADMIN"));
        assertTrue(PosTenantDetailTimelineClient.canAccessTenantDetail("PLATFORM_OPERATOR"));

        // Store-level personas must never be authorized
        assertFalse(PosTenantDetailTimelineClient.canAccessTenantDetail("TENANT_OWNER"));
        assertFalse(PosTenantDetailTimelineClient.canAccessTenantDetail("BRANCH_MANAGER"));
        assertFalse(PosTenantDetailTimelineClient.canAccessTenantDetail("PHARMACIST"));
        assertFalse(PosTenantDetailTimelineClient.canAccessTenantDetail("CASHIER"));
        assertFalse(PosTenantDetailTimelineClient.canAccessTenantDetail(null));
    }

    @Test
    @DisplayName("Tenant detail view is never visible inside store POS UI")
    void testTenantDetailOmittedFromStoreUI() {
        assertFalse(PosTenantDetailTimelineClient.isTenantDetailVisibleInStoreUI());
    }

    @Test
    @DisplayName("Guarantees patient clinical data is strictly prohibited and detected")
    void testPatientDataExclusionAudit() {
        Map<String, Object> cleanDetail = Map.of(
                "tenant_id", "tenant-alpha",
                "name", "Delta Care Pharma",
                "status", "ACTIVE"
        );
        assertFalse(PosTenantDetailTimelineClient.containsPatientClinicalData(cleanDetail));

        Map<String, Object> contaminated = new HashMap<>(cleanDetail);
        contaminated.put("patient_name", "Test Patient");
        assertTrue(PosTenantDetailTimelineClient.containsPatientClinicalData(contaminated));

        Map<String, Object> contaminatedRx = new HashMap<>(cleanDetail);
        contaminatedRx.put("prescriptions", List.of("Rx-001"));
        assertTrue(PosTenantDetailTimelineClient.containsPatientClinicalData(contaminatedRx));
    }

    @Test
    @DisplayName("URL builder enforces Super Admin role and valid tenant ID format")
    void testBuildTenantDetailUrl() {
        assertEquals(
                "/api/v2/platform/console/tenants/tenant-alpha/detail/",
                PosTenantDetailTimelineClient.buildTenantDetailUrl("SUPERADMIN", "tenant-alpha")
        );

        assertThrows(SecurityException.class, () ->
                PosTenantDetailTimelineClient.buildTenantDetailUrl("TENANT_OWNER", "tenant-alpha"));
        assertThrows(IllegalArgumentException.class, () ->
                PosTenantDetailTimelineClient.buildTenantDetailUrl("SUPERADMIN", ""));
        assertThrows(IllegalArgumentException.class, () ->
                PosTenantDetailTimelineClient.buildTenantDetailUrl("SUPERADMIN", "ab"));
        assertThrows(IllegalArgumentException.class, () ->
                PosTenantDetailTimelineClient.buildTenantDetailUrl("SUPERADMIN", "bad tenant!"));
    }

    @Test
    @DisplayName("Validates timeline entry schema structure")
    void testTimelineEntryValidation() {
        Map<String, Object> validEntry = Map.of(
                "action", "TENANT_PROVISIONED",
                "status", "SUCCESS",
                "timestamp", "2026-10-31T12:00:00Z"
        );
        assertTrue(PosTenantDetailTimelineClient.isValidTimelineEntry(validEntry));

        Map<String, Object> invalidEntry = Map.of(
                "action", "TENANT_PROVISIONED"
                // Missing status and timestamp
        );
        assertFalse(PosTenantDetailTimelineClient.isValidTimelineEntry(invalidEntry));
    }
}
