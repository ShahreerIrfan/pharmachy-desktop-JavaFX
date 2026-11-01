package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0074: JavaFX Desktop POS Tenant Deletion Client Tests")
class PosTenantDeletionClientTest {

    @Test
    @DisplayName("Role RBAC: Pharmacy Owner and SuperAdmin can request deletion; Cashier and Pharmacist blocked")
    void testCanRoleRequestDeletion() {
        assertTrue(PosTenantDeletionClient.canRoleRequestDeletion("PHARMACY_OWNER"));
        assertTrue(PosTenantDeletionClient.canRoleRequestDeletion("OWNER"));
        assertTrue(PosTenantDeletionClient.canRoleRequestDeletion("SUPERADMIN"));
        assertTrue(PosTenantDeletionClient.canRoleRequestDeletion("PLATFORM_OPERATOR"));

        assertFalse(PosTenantDeletionClient.canRoleRequestDeletion("BRANCH_MANAGER"));
        assertFalse(PosTenantDeletionClient.canRoleRequestDeletion("STAFF_PHARMACIST"));
        assertFalse(PosTenantDeletionClient.canRoleRequestDeletion("CASHIER"));
        assertFalse(PosTenantDeletionClient.canRoleRequestDeletion(null));
    }

    @Test
    @DisplayName("Role RBAC: Only SuperAdmin and Platform Operator can assess holds and execute purges")
    void testCanRoleAssessOrPurge() {
        assertTrue(PosTenantDeletionClient.canRoleAssessOrPurge("SUPERADMIN"));
        assertTrue(PosTenantDeletionClient.canRoleAssessOrPurge("SUPER_ADMIN"));
        assertTrue(PosTenantDeletionClient.canRoleAssessOrPurge("PLATFORM_OPERATOR"));

        assertFalse(PosTenantDeletionClient.canRoleAssessOrPurge("PHARMACY_OWNER"));
        assertFalse(PosTenantDeletionClient.canRoleAssessOrPurge("BRANCH_MANAGER"));
        assertFalse(PosTenantDeletionClient.canRoleAssessOrPurge("CASHIER"));
        assertFalse(PosTenantDeletionClient.canRoleAssessOrPurge(null));
    }

    @Test
    @DisplayName("Request ID validation: Must start with tdr- followed by 8-64 alphanumeric chars")
    void testIsValidRequestId() {
        assertTrue(PosTenantDeletionClient.isValidRequestId("tdr-a1b2c3d4e5f6"));
        assertTrue(PosTenantDeletionClient.isValidRequestId("tdr-tenant_close_2026"));

        assertFalse(PosTenantDeletionClient.isValidRequestId("invalid-no-prefix"));
        assertFalse(PosTenantDeletionClient.isValidRequestId("tdr-short"));
        assertFalse(PosTenantDeletionClient.isValidRequestId("tdr-invalid with spaces"));
        assertFalse(PosTenantDeletionClient.isValidRequestId(null));
    }

    @Test
    @DisplayName("Guardrail: Physical purge is strictly blocked if active holds exist")
    void testIsPhysicalPurgeBlocked() {
        assertTrue(PosTenantDeletionClient.isPhysicalPurgeBlocked(true));
        assertFalse(PosTenantDeletionClient.isPhysicalPurgeBlocked(false));
    }

    @Test
    @DisplayName("Confirmation text validation: Must equal 'CONFIRM_PERMANENT_DELETION'")
    void testIsConfirmationTextValid() {
        assertTrue(PosTenantDeletionClient.isConfirmationTextValid("CONFIRM_PERMANENT_DELETION"));
        assertTrue(PosTenantDeletionClient.isConfirmationTextValid("confirm_permanent_deletion"));
        assertTrue(PosTenantDeletionClient.isConfirmationTextValid("  CONFIRM_PERMANENT_DELETION  "));

        assertFalse(PosTenantDeletionClient.isConfirmationTextValid("YES_DELETE"));
        assertFalse(PosTenantDeletionClient.isConfirmationTextValid(""));
        assertFalse(PosTenantDeletionClient.isConfirmationTextValid(null));
    }

    @Test
    @DisplayName("Lifecycle State Machine: Validates strict state transitions")
    void testCanTransition() {
        assertTrue(PosTenantDeletionClient.canTransition("SUBMITTED", "HOLD_ACTIVE"));
        assertTrue(PosTenantDeletionClient.canTransition("SUBMITTED", "ELIGIBLE_FOR_PURGE"));
        assertTrue(PosTenantDeletionClient.canTransition("SUBMITTED", "CANCELLED"));
        assertTrue(PosTenantDeletionClient.canTransition("HOLD_ACTIVE", "REJECTED"));
        assertTrue(PosTenantDeletionClient.canTransition("ELIGIBLE_FOR_PURGE", "CONFIRMED"));
        assertTrue(PosTenantDeletionClient.canTransition("CONFIRMED", "PURGED"));

        assertFalse(PosTenantDeletionClient.canTransition("HOLD_ACTIVE", "CONFIRMED"));
        assertFalse(PosTenantDeletionClient.canTransition("SUBMITTED", "PURGED"));
        assertFalse(PosTenantDeletionClient.canTransition("PURGED", "SUBMITTED"));
        assertFalse(PosTenantDeletionClient.canTransition(null, "HOLD_ACTIVE"));
    }

    @Test
    @DisplayName("Builds endpoint URLs correctly with validation")
    void testBuildUrls() {
        assertEquals("/api/v2/tenants/deletion-requests/", PosTenantDeletionClient.buildTenantDeletionListUrl());
        assertEquals("/api/v2/tenants/deletion-requests/tdr-12345678/", PosTenantDeletionClient.buildTenantDeletionDetailUrl("tdr-12345678"));
        assertEquals("/api/v2/tenants/deletion-requests/tdr-12345678/cancel/", PosTenantDeletionClient.buildTenantCancelUrl("tdr-12345678"));

        assertEquals("/api/v2/platform/deletion-assessments/", PosTenantDeletionClient.buildPlatformAssessmentListUrl());
        assertEquals("/api/v2/platform/deletion-assessments/tdr-12345678/assess/", PosTenantDeletionClient.buildPlatformAssessUrl("tdr-12345678"));
        assertEquals("/api/v2/platform/deletion-assessments/tdr-12345678/reject/", PosTenantDeletionClient.buildPlatformRejectUrl("tdr-12345678"));
        assertEquals("/api/v2/platform/deletion-assessments/tdr-12345678/purge/", PosTenantDeletionClient.buildPlatformPurgeUrl("tdr-12345678"));

        assertThrows(IllegalArgumentException.class, () -> PosTenantDeletionClient.buildTenantDeletionDetailUrl("invalid-id"));
    }
}
