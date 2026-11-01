package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0075: JavaFX Desktop POS Cross-Tenant Metrics Client Tests")
class PosCrossTenantMetricsClientTest {

    @Test
    @DisplayName("Role RBAC: Only SuperAdmin and Platform Operator can manage platform metrics")
    void testPlatformAdminRbac() {
        assertTrue(PosCrossTenantMetricsClient.canRoleManagePlatformMetrics("SUPERADMIN"));
        assertTrue(PosCrossTenantMetricsClient.canRoleManagePlatformMetrics("SUPER_ADMIN"));
        assertTrue(PosCrossTenantMetricsClient.canRoleManagePlatformMetrics("PLATFORM_OPERATOR"));

        assertFalse(PosCrossTenantMetricsClient.canRoleManagePlatformMetrics("PHARMACY_OWNER"));
        assertFalse(PosCrossTenantMetricsClient.canRoleManagePlatformMetrics("BRANCH_MANAGER"));
        assertFalse(PosCrossTenantMetricsClient.canRoleManagePlatformMetrics("STAFF_PHARMACIST"));
        assertFalse(PosCrossTenantMetricsClient.canRoleManagePlatformMetrics("CASHIER"));
        assertFalse(PosCrossTenantMetricsClient.canRoleManagePlatformMetrics(null));
    }

    @Test
    @DisplayName("Role RBAC: Pharmacy Owner and Managers can view benchmarks; Cashier and Pharmacist blocked")
    void testBenchmarkRbac() {
        assertTrue(PosCrossTenantMetricsClient.canRoleViewBenchmarks("SUPERADMIN"));
        assertTrue(PosCrossTenantMetricsClient.canRoleViewBenchmarks("PHARMACY_OWNER"));
        assertTrue(PosCrossTenantMetricsClient.canRoleViewBenchmarks("BRANCH_MANAGER"));
        assertTrue(PosCrossTenantMetricsClient.canRoleViewBenchmarks("STORE_MANAGER"));
        assertTrue(PosCrossTenantMetricsClient.canRoleViewBenchmarks("ADMIN"));

        assertFalse(PosCrossTenantMetricsClient.canRoleViewBenchmarks("CASHIER"));
        assertFalse(PosCrossTenantMetricsClient.canRoleViewBenchmarks("STAFF_PHARMACIST"));
        assertFalse(PosCrossTenantMetricsClient.canRoleViewBenchmarks(null));
    }

    @Test
    @DisplayName("Snapshot ID syntax: Must match ctm- prefix and 8-64 alphanumeric chars")
    void testSnapshotIdValidation() {
        assertTrue(PosCrossTenantMetricsClient.isValidSnapshotId("ctm-a1b2c3d4e5f6"));
        assertTrue(PosCrossTenantMetricsClient.isValidSnapshotId("ctm-20261101-october-metrics"));

        assertFalse(PosCrossTenantMetricsClient.isValidSnapshotId("invalid-prefix-12345"));
        assertFalse(PosCrossTenantMetricsClient.isValidSnapshotId("ctm-short"));
        assertFalse(PosCrossTenantMetricsClient.isValidSnapshotId(null));
        assertFalse(PosCrossTenantMetricsClient.isValidSnapshotId(""));
    }

    @Test
    @DisplayName("k-Anonymity: Cohorts with fewer tenants than threshold must be suppressed")
    void testCohortSuppressionKAnonymity() {
        // Threshold = 3
        assertTrue(PosCrossTenantMetricsClient.isCohortSuppressed(1, 3));
        assertTrue(PosCrossTenantMetricsClient.isCohortSuppressed(2, 3));
        assertFalse(PosCrossTenantMetricsClient.isCohortSuppressed(3, 3));
        assertFalse(PosCrossTenantMetricsClient.isCohortSuppressed(10, 3));

        // Threshold = 5
        assertTrue(PosCrossTenantMetricsClient.isCohortSuppressed(4, 5));
        assertFalse(PosCrossTenantMetricsClient.isCohortSuppressed(5, 5));
    }

    @Test
    @DisplayName("Zero-PHI Guarantee: Detects and rejects payloads containing patient identifiers")
    void testZeroPhiGuaranteed() {
        String cleanJson = "{\"clinical_metrics\": {\"total_prescriptions\": 500, \"antibiotic_ratio_pct\": \"12.50\"}}";
        assertTrue(PosCrossTenantMetricsClient.validateZeroPhiGuaranteed(cleanJson));

        String dirtyJson1 = "{\"clinical_metrics\": {\"patient_name\": \"Mr. Rahim\", \"total\": 500}}";
        assertFalse(PosCrossTenantMetricsClient.validateZeroPhiGuaranteed(dirtyJson1));

        String dirtyJson2 = "{\"data\": {\"patient_phone\": \"01700000000\"}}"; // synthetic-fixture
        assertFalse(PosCrossTenantMetricsClient.validateZeroPhiGuaranteed(dirtyJson2));

        String dirtyJson3 = "{\"doctor_license\": \"BMDC-12345\"}";
        assertFalse(PosCrossTenantMetricsClient.validateZeroPhiGuaranteed(dirtyJson3));
    }

    @Test
    @DisplayName("Lifecycle State Machine: Strict transitions for metric snapshots")
    void testLifecycleStateTransitions() {
        assertTrue(PosCrossTenantMetricsClient.isValidStateTransition("DRAFT", "PUBLISHED"));
        assertTrue(PosCrossTenantMetricsClient.isValidStateTransition("DRAFT", "ARCHIVED"));
        assertTrue(PosCrossTenantMetricsClient.isValidStateTransition("PUBLISHED", "ARCHIVED"));
        assertTrue(PosCrossTenantMetricsClient.isValidStateTransition("PUBLISHED", "PUBLISHED"));

        assertFalse(PosCrossTenantMetricsClient.isValidStateTransition("ARCHIVED", "PUBLISHED"));
        assertFalse(PosCrossTenantMetricsClient.isValidStateTransition("ARCHIVED", "DRAFT"));
        assertFalse(PosCrossTenantMetricsClient.isValidStateTransition(null, "DRAFT"));
    }

    @Test
    @DisplayName("URL Builders: Accurately format platform and tenant REST endpoints")
    void testUrlBuilders() {
        String platformList = PosCrossTenantMetricsClient.buildPlatformMetricsUrl("https://api.pharmacy.bd", null);
        assertEquals("https://api.pharmacy.bd/api/v2/platform/cross-tenant-metrics/", platformList);

        String platformDetail = PosCrossTenantMetricsClient.buildPlatformMetricsUrl("https://api.pharmacy.bd", "ctm-a1b2c3d4e5f6");
        assertEquals("https://api.pharmacy.bd/api/v2/platform/cross-tenant-metrics/ctm-a1b2c3d4e5f6/", platformDetail);

        String benchmarkUrl = PosCrossTenantMetricsClient.buildTenantBenchmarkUrl("https://api.pharmacy.bd", "tenant-dhaka-01");
        assertEquals("https://api.pharmacy.bd/api/v2/analytics/platform-benchmarks/?tenant_id=tenant-dhaka-01", benchmarkUrl);

        assertThrows(IllegalArgumentException.class, () ->
                PosCrossTenantMetricsClient.buildPlatformMetricsUrl("https://api.pharmacy.bd", "bad-id"));
    }

    @Test
    @DisplayName("Localization: Returns non-empty EN and BN suppression messages")
    void testLocalizedSuppressionMessage() {
        String en = PosCrossTenantMetricsClient.getLocalizedSuppressionMessage(false);
        String bn = PosCrossTenantMetricsClient.getLocalizedSuppressionMessage(true);

        assertNotNull(en);
        assertNotNull(bn);
        assertTrue(en.contains("Privacy Guardrail"));
        assertTrue(bn.contains("গোপনীয়তা"));
    }
}
