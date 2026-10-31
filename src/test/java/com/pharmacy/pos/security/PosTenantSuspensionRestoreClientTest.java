package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0067: Desktop Platform Super Admin Tenant Suspension and Restore Tests")
class PosTenantSuspensionRestoreClientTest {

    @Test
    @DisplayName("Role privilege separation: Only Super Admin and Platform Operator can suspend or restore")
    void testPrivilegeSeparation() {
        assertTrue(PosTenantSuspensionRestoreClient.canManageLifecycle("SUPERADMIN"));
        assertTrue(PosTenantSuspensionRestoreClient.canManageLifecycle("SUPER_ADMIN"));
        assertTrue(PosTenantSuspensionRestoreClient.canManageLifecycle("PLATFORM_OPERATOR"));

        // Store tenant roles must be strictly denied
        assertFalse(PosTenantSuspensionRestoreClient.canManageLifecycle("PHARMACY_OWNER"));
        assertFalse(PosTenantSuspensionRestoreClient.canManageLifecycle("BRANCH_MANAGER"));
        assertFalse(PosTenantSuspensionRestoreClient.canManageLifecycle("STAFF_PHARMACIST"));
        assertFalse(PosTenantSuspensionRestoreClient.canManageLifecycle("CASHIER"));
        assertFalse(PosTenantSuspensionRestoreClient.canManageLifecycle(null));

        // Screen isolation: Never visible in store UI
        assertFalse(PosTenantSuspensionRestoreClient.isLifecycleManagementVisibleInStoreUI());
    }

    @Test
    @DisplayName("Validates suspension request parameters and rejects invalid inputs")
    void testSuspensionRequestValidation() {
        // Valid request
        var req = new PosTenantSuspensionRestoreClient.SuspensionRequest(
                "tenant-dhaka-main",
                "Non-payment of subscription invoice after grace period",
                "NON_PAYMENT"
        );
        assertEquals("tenant-dhaka-main", req.tenantId());
        assertEquals("NON_PAYMENT", req.reasonCode());

        // Incomplete/invalid inputs
        assertThrows(IllegalArgumentException.class, () ->
                new PosTenantSuspensionRestoreClient.SuspensionRequest("tenant-1", "bad", "NON_PAYMENT"));

        assertThrows(IllegalArgumentException.class, () ->
                new PosTenantSuspensionRestoreClient.SuspensionRequest("", "Reason is long enough", "NON_PAYMENT"));

        assertThrows(IllegalArgumentException.class, () ->
                new PosTenantSuspensionRestoreClient.SuspensionRequest("tenant-1", "Valid reason here", "INVALID_CODE"));
    }

    @Test
    @DisplayName("Validates restoration request parameters and rejects short reason")
    void testRestorationRequestValidation() {
        var req = new PosTenantSuspensionRestoreClient.RestorationRequest(
                "tenant-dhaka-main",
                "Payment received and verified by finance officer"
        );
        assertEquals("tenant-dhaka-main", req.tenantId());

        assertThrows(IllegalArgumentException.class, () ->
                new PosTenantSuspensionRestoreClient.RestorationRequest("tenant-1", "no"));
    }

    @Test
    @DisplayName("Client lease evaluation reflects lifecycle status")
    void testClientLeaseEvaluation() {
        assertEquals(PosTenantSuspensionRestoreClient.ClientLeaseStatus.ACTIVE,
                PosTenantSuspensionRestoreClient.evaluateClientLease("ACTIVE"));
        assertEquals(PosTenantSuspensionRestoreClient.ClientLeaseStatus.ACTIVE,
                PosTenantSuspensionRestoreClient.evaluateClientLease("TRIAL"));
        assertEquals(PosTenantSuspensionRestoreClient.ClientLeaseStatus.ACTIVE,
                PosTenantSuspensionRestoreClient.evaluateClientLease("PAST_DUE"));

        // Suspended tenant has revoked/suspended lease
        assertEquals(PosTenantSuspensionRestoreClient.ClientLeaseStatus.SUSPENDED,
                PosTenantSuspensionRestoreClient.evaluateClientLease("SUSPENDED"));

        // Archived tenant has permanently revoked lease
        assertEquals(PosTenantSuspensionRestoreClient.ClientLeaseStatus.REVOKED,
                PosTenantSuspensionRestoreClient.evaluateClientLease("ARCHIVED"));
        assertEquals(PosTenantSuspensionRestoreClient.ClientLeaseStatus.REVOKED,
                PosTenantSuspensionRestoreClient.evaluateClientLease(null));
    }

    @Test
    @DisplayName("Pending sync policy: Pre-suspension transactions drained safely, post-suspension rejected")
    void testPendingSyncPolicyEvaluation() {
        Instant suspensionTime = Instant.now();
        Instant preSuspensionTx = suspensionTime.minus(10, ChronoUnit.MINUTES);
        Instant postSuspensionTx = suspensionTime.plus(10, ChronoUnit.MINUTES);

        // Active tenant permits all sync
        assertEquals(PosTenantSuspensionRestoreClient.PendingSyncPolicy.ALLOW_SYNC,
                PosTenantSuspensionRestoreClient.evaluatePendingSyncPolicy("ACTIVE", postSuspensionTx, suspensionTime));

        // Suspended tenant: Pre-suspension is DRAINED to protect financial integrity (Zero Data Loss)
        assertEquals(PosTenantSuspensionRestoreClient.PendingSyncPolicy.DRAIN_PRE_SUSPENSION,
                PosTenantSuspensionRestoreClient.evaluatePendingSyncPolicy("SUSPENDED", preSuspensionTx, suspensionTime));

        // Suspended tenant: Post-suspension is REJECTED
        assertEquals(PosTenantSuspensionRestoreClient.PendingSyncPolicy.REJECT_POST_SUSPENSION,
                PosTenantSuspensionRestoreClient.evaluatePendingSyncPolicy("SUSPENDED", postSuspensionTx, suspensionTime));
    }

    @Test
    @DisplayName("Builds correct REST endpoint paths")
    void testEndpointUrls() {
        assertEquals("/api/v2/platform/console/tenants/t-100/suspend/",
                PosTenantSuspensionRestoreClient.buildSuspendUrl("t-100"));
        assertEquals("/api/v2/platform/console/tenants/t-100/restore/",
                PosTenantSuspensionRestoreClient.buildRestoreUrl("t-100"));
    }
}
