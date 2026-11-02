package com.pharmacy.pos.roles;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosRoleMigrationClientTest
 * Tests P119 Role Migration and Grants client logic.
 */
class PosRoleMigrationClientTest {

    private PosRoleMigrationClient client;

    @BeforeEach
    void setUp() {
        client = new PosRoleMigrationClient();
    }

    @Test
    @DisplayName("Owner successfully reconciles role grants on plan downgrade to Basic")
    void testOwnerReconcilesRoleGrantsSuccess() {
        List<String> perms = List.of(
                "sales:checkout",
                "inventory:view_stock",
                "finance:executive_dashboard", // Not entitled in BASIC
                "audit:export"                 // Not entitled in BASIC
        );

        var result = client.reconcileRole(
                "PHARMACY_OWNER",
                "tenant-1",
                "tenant-1",
                "BASIC",
                "role-101",
                "BILLING_SPECIALIST",
                1,
                perms,
                "PLAN_DOWNGRADE",
                false
        );

        assertEquals(PosRoleMigrationClient.UiStatus.SUCCESS, result.uiStatus);
        assertNotNull(result.record);
        assertEquals(1, result.record.previousVersion);
        assertEquals(2, result.record.newVersion);
        assertEquals(2, result.record.retainedPermissions.size());
        assertTrue(result.record.retainedPermissions.contains("sales:checkout"));
        assertTrue(result.record.retainedPermissions.contains("inventory:view_stock"));
        assertEquals(2, result.record.removedPermissions.size());
        assertTrue(result.record.removedPermissions.contains("finance:executive_dashboard"));
    }

    @Test
    @DisplayName("Forbidden permissions are stripped and never restored")
    void testForbiddenPermissionsBlockedAndStripped() {
        List<String> perms = List.of(
                "sales:checkout",
                "stock:dispense_expired",       // FORBIDDEN
                "system:hard_delete"           // FORBIDDEN
        );

        var result = client.reconcileRole(
                "PHARMACY_OWNER",
                "tenant-1",
                "tenant-1",
                "BASIC",
                "role-102",
                "TEST_ROLE",
                2,
                perms,
                "COMPLIANCE_PRUNING",
                false
        );

        assertEquals(PosRoleMigrationClient.UiStatus.SUCCESS, result.uiStatus);
        assertEquals(2, result.record.forbiddenGrantsBlocked.size());
        assertFalse(result.record.retainedPermissions.contains("stock:dispense_expired"));
        assertFalse(result.record.retainedPermissions.contains("system:hard_delete"));
    }

    @Test
    @DisplayName("Dry-run preview calculates changes without mutating version flag")
    void testDryRunPreview() {
        List<String> perms = List.of("sales:checkout", "audit:export");

        var result = client.reconcileRole(
                "PHARMACY_OWNER",
                "tenant-1",
                "tenant-1",
                "BASIC",
                "role-101",
                "CASHIER_PLUS",
                3,
                perms,
                "TEMPLATE_EVOLUTION",
                true // Dry-run
        );

        assertEquals(PosRoleMigrationClient.UiStatus.SUCCESS, result.uiStatus);
        assertTrue(result.record.isDryRun);
        assertEquals(4, result.record.newVersion);
    }

    @Test
    @DisplayName("Branch Manager cannot execute or preview role migrations")
    void testBranchManagerDenied() {
        var result = client.reconcileRole(
                "BRANCH_MANAGER",
                "tenant-1",
                "tenant-1",
                "BASIC",
                "role-101",
                "CASHIER_PLUS",
                1,
                List.of("sales:checkout"),
                "PLAN_DOWNGRADE",
                false
        );

        assertEquals(PosRoleMigrationClient.UiStatus.PERMISSION_DENIED, result.uiStatus);
        assertEquals("ERR_STAFF_ROLE_DENIED", result.errorCode);
    }

    @Test
    @DisplayName("Cashier cannot execute or preview role migrations")
    void testCashierDenied() {
        var result = client.reconcileRole(
                "CASHIER",
                "tenant-1",
                "tenant-1",
                "BASIC",
                "role-101",
                "CASHIER_PLUS",
                1,
                List.of("sales:checkout"),
                "PLAN_DOWNGRADE",
                false
        );

        assertEquals(PosRoleMigrationClient.UiStatus.PERMISSION_DENIED, result.uiStatus);
        assertEquals("ERR_STAFF_ROLE_DENIED", result.errorCode);
    }

    @Test
    @DisplayName("Cross-tenant role migration is forbidden")
    void testCrossTenantForbidden() {
        var result = client.reconcileRole(
                "PHARMACY_OWNER",
                "tenant-1",
                "tenant-2",
                "BASIC",
                "role-101",
                "CASHIER_PLUS",
                1,
                List.of("sales:checkout"),
                "PLAN_DOWNGRADE",
                false
        );

        assertEquals(PosRoleMigrationClient.UiStatus.PERMISSION_DENIED, result.uiStatus);
        assertEquals("ERR_CROSS_TENANT_VIOLATION", result.errorCode);
    }
}
