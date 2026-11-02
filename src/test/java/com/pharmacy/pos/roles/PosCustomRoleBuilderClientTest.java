package com.pharmacy.pos.roles;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosCustomRoleBuilderClientTest
 * Tests for JavaFX desktop custom role builder client.
 */
public class PosCustomRoleBuilderClientTest {

    private PosCustomRoleBuilderClient client;

    @BeforeEach
    public void setUp() {
        client = new PosCustomRoleBuilderClient();
    }

    @Test
    @DisplayName("01: Enforces clinical safety minimum: Rx dispensing requires PCB registration")
    public void test01PcbSafetyMinimumEnforcement() {
        List<String> rxPermissions = Arrays.asList("dispense:rx_antibiotics", "sales:checkout");

        // Fails when requiresPcbRegistration is false
        PosCustomRoleBuilderClient.ValidationResult invalidResult = client.validateRoleSafety(
                "ROLE_UNQUALIFIED_DISPENSER",
                "Unqualified Dispenser",
                rxPermissions,
                false
        );
        assertFalse(invalidResult.isValid);
        assertEquals("ERR_SAFETY_MINIMUM_PCB_REQUIRED", invalidResult.errorCode);
        assertTrue(invalidResult.messageEn.contains("PCB registration"));

        // Passes when requiresPcbRegistration is true
        PosCustomRoleBuilderClient.ValidationResult validResult = client.validateRoleSafety(
                "ROLE_QUALIFIED_DISPENSER",
                "Qualified Pharmacist Dispenser",
                rxPermissions,
                true
        );
        assertTrue(validResult.isValid);
    }

    @Test
    @DisplayName("02: Rejects blocked absolute danger permissions")
    public void test02BlockedPermissionsRejection() {
        List<String> dangerousPermissions = Arrays.asList("system:hard_delete", "sales:checkout");
        PosCustomRoleBuilderClient.ValidationResult result = client.validateRoleSafety(
                "ROLE_DANGEROUS",
                "Dangerous Role",
                dangerousPermissions,
                false
        );
        assertFalse(result.isValid);
        assertEquals("ERR_BLOCKED_PERMISSION", result.errorCode);
        assertTrue(result.messageEn.contains("system:hard_delete"));
    }

    @Test
    @DisplayName("03: System-protected role immutability and role modification RBAC")
    public void test03SystemRoleProtectionAndRbac() {
        PosCustomRoleBuilderClient.CustomRoleRecord systemRole = new PosCustomRoleBuilderClient.CustomRoleRecord(
                "role-sys-1", "tn-1", "ROLE_PHARMACY_OWNER", "Pharmacy Owner",
                "All permissions", "PHARMACY_OWNER", Collections.singletonList("sales:checkout"),
                true, true, false, null
        );
        PosCustomRoleBuilderClient.CustomRoleRecord customRole = new PosCustomRoleBuilderClient.CustomRoleRecord(
                "role-cust-1", "tn-1", "ROLE_EVENING_CASHIER", "Evening Cashier",
                "Cashier shift", "CASHIER", Collections.singletonList("sales:checkout"),
                false, false, false, null
        );

        // Owner CAN modify custom role, but CANNOT modify system-protected role
        assertTrue(client.canModifyRole(customRole, "PHARMACY_OWNER"));
        assertFalse(client.canModifyRole(systemRole, "PHARMACY_OWNER"));

        // Branch manager and cashier CANNOT modify any role
        assertFalse(client.canModifyRole(customRole, "BRANCH_MANAGER"));
        assertFalse(client.canModifyRole(customRole, "CASHIER"));
    }

    @Test
    @DisplayName("04: UI state handling for custom roles: success, offline cache, and permission denied")
    public void test04UiStateHandling() {
        List<PosCustomRoleBuilderClient.CustomRoleRecord> roles = Collections.singletonList(
                new PosCustomRoleBuilderClient.CustomRoleRecord(
                        "role-101", "tn-1", "ROLE_LEAD_DISPENSER", "Lead Dispenser",
                        "", "PHARMACIST", Arrays.asList("dispense:rx_antibiotics", "rx:verify"),
                        true, false, false, null
                )
        );

        PosCustomRoleBuilderClient.RoleBuilderViewState successState = client.createSuccessState(roles);
        assertEquals(PosCustomRoleBuilderClient.UiStatus.SUCCESS, successState.uiStatus);
        assertEquals(1, successState.roles.size());
        assertTrue(successState.roles.get(0).allowsClinicalDispensing());

        PosCustomRoleBuilderClient.RoleBuilderViewState offlineState = client.createOfflineState(roles);
        assertEquals(PosCustomRoleBuilderClient.UiStatus.OFFLINE, offlineState.uiStatus);
        assertTrue(offlineState.messageEn.contains("offline"));
        assertTrue(offlineState.messageBn.contains("অফলাইনে"));

        PosCustomRoleBuilderClient.RoleBuilderViewState deniedState = client.createPermissionDeniedState("CASHIER");
        assertEquals(PosCustomRoleBuilderClient.UiStatus.PERMISSION_DENIED, deniedState.uiStatus);
        assertTrue(deniedState.messageEn.contains("CASHIER"));
        assertTrue(deniedState.messageBn.contains("অনুমতি নেই"));
    }
}
