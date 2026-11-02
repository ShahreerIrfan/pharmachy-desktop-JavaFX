package com.pharmacy.pos.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosRbacNegativeMatrixClientTest
 * Tests client-side RBAC negative matrix evaluations (ADR-0120 / P120).
 */
class PosRbacNegativeMatrixClientTest {

    private PosRbacNegativeMatrixClient client;

    @BeforeEach
    void setUp() {
        client = new PosRbacNegativeMatrixClient();
    }

    @Test
    @DisplayName("Cashier denied branch settings modification")
    void testCashierDeniedBranchSettings() {
        var res = client.evaluateAction("CASHIER", "tenant-1", "branch-1", "tenant-1", "branch-1", "PATCH_BRANCH_SETTINGS", "ACTIVE");
        assertFalse(res.isAllowed);
        assertEquals(403, res.httpStatusCode);
        assertEquals("ERR_STAFF_ROLE_DENIED", res.errorCode);
    }

    @Test
    @DisplayName("Cashier denied staff audit timeline")
    void testCashierDeniedStaffTimeline() {
        var res = client.evaluateAction("CASHIER", "tenant-1", "branch-1", "tenant-1", "branch-1", "GET_STAFF_TIMELINE", "ACTIVE");
        assertFalse(res.isAllowed);
        assertEquals(403, res.httpStatusCode);
        assertEquals("ERR_STAFF_ROLE_DENIED", res.errorCode);
    }

    @Test
    @DisplayName("Branch Manager denied foreign branch drawer assignment")
    void testBranchManagerDeniedForeignBranchDrawer() {
        var res = client.evaluateAction("BRANCH_MANAGER", "tenant-1", "branch-1", "tenant-1", "branch-2", "ASSIGN_OTHER_DRAWER", "ACTIVE");
        assertFalse(res.isAllowed);
        assertEquals(403, res.httpStatusCode);
        assertEquals("ERR_CROSS_BRANCH_VIOLATION", res.errorCode);
    }

    @Test
    @DisplayName("Branch Manager denied role migration execution")
    void testBranchManagerDeniedRoleMigration() {
        var res = client.evaluateAction("BRANCH_MANAGER", "tenant-1", "branch-1", "tenant-1", "branch-1", "ROLE_MIGRATION", "ACTIVE");
        assertFalse(res.isAllowed);
        assertEquals(403, res.httpStatusCode);
        assertEquals("ERR_STAFF_ROLE_DENIED", res.errorCode);
    }

    @Test
    @DisplayName("Cross-tenant action strictly rejected across all roles")
    void testCrossTenantActionRejected() {
        var res = client.evaluateAction("PHARMACY_OWNER", "tenant-1", "branch-1", "tenant-2", "branch-1", "PATCH_BRANCH_SETTINGS", "ACTIVE");
        assertFalse(res.isAllowed);
        assertEquals(403, res.httpStatusCode);
        assertEquals("ERR_CROSS_TENANT_VIOLATION", res.errorCode);
    }

    @Test
    @DisplayName("Suspended tenant mutation is blocked")
    void testSuspendedTenantBlocked() {
        var res = client.evaluateAction("PHARMACY_OWNER", "tenant-1", "branch-1", "tenant-1", "branch-1", "ROLE_MIGRATION", "SUSPENDED");
        assertFalse(res.isAllowed);
        assertEquals(403, res.httpStatusCode);
        assertEquals("ERR_SUBSCRIPTION_UNENTITLED_WRITE_BLOCKED", res.errorCode);
    }
}
