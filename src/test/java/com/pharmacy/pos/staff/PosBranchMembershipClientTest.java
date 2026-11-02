package com.pharmacy.pos.staff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PosBranchMembershipClient Tests (P105 / ADR-0105)")
class PosBranchMembershipClientTest {

    @Test
    @DisplayName("Staff account holds different roles in separate branches")
    void testMultiBranchDifferentRoles() {
        var b1 = new PosBranchMembershipClient.BranchMembershipRecord(
                "bsm-1", "tn-1", "stf-kamal", "Kamal Hossain", "kamal@pharma.com",
                "branch-dhanmondi", "Dhanmondi", "BRANCH_MANAGER", "", "Store Lead",
                true, true, "2026-11-02T10:00:00Z", "owner"
        );
        var b2 = new PosBranchMembershipClient.BranchMembershipRecord(
                "bsm-2", "tn-1", "stf-kamal", "Kamal Hossain", "kamal@pharma.com",
                "branch-gulshan", "Gulshan", "CASHIER", "", "Floor Cashier",
                false, true, "2026-11-02T10:00:00Z", "owner"
        );

        List<PosBranchMembershipClient.BranchMembershipRecord> list = Arrays.asList(b1, b2);

        var dhanmondiRole = PosBranchMembershipClient.resolveEffectiveRole(list, "branch-dhanmondi");
        assertTrue(dhanmondiRole.isPresent());
        assertEquals("BRANCH_MANAGER", dhanmondiRole.get().role);
        assertTrue(dhanmondiRole.get().isPrimary);

        var gulshanRole = PosBranchMembershipClient.resolveEffectiveRole(list, "branch-gulshan");
        assertTrue(gulshanRole.isPresent());
        assertEquals("CASHIER", gulshanRole.get().role);
        assertFalse(gulshanRole.get().isPrimary);

        var foreignBranch = PosBranchMembershipClient.resolveEffectiveRole(list, "branch-uttara");
        assertFalse(foreignBranch.isPresent());
    }

    @Test
    @DisplayName("Pharmacy Owner can assign any role across any branch")
    void testOwnerAuthority() {
        var res = PosBranchMembershipClient.validateAssignment(
                "PHARMACY_OWNER", "branch-dhanmondi", "branch-gulshan", "BRANCH_MANAGER"
        );
        assertTrue(res.isValid);
    }

    @Test
    @DisplayName("Branch Manager cannot assign roles to another branch")
    void testBranchManagerCannotAssignToOtherBranch() {
        var res = PosBranchMembershipClient.validateAssignment(
                "BRANCH_MANAGER", "branch-dhanmondi", "branch-gulshan", "CASHIER"
        );
        assertFalse(res.isValid);
        assertEquals("ERR_BRANCH_ACCESS_DENIED", res.errorCode);
    }

    @Test
    @DisplayName("Branch Manager cannot promote to Manager or Owner")
    void testBranchManagerCannotPromoteToManager() {
        var res = PosBranchMembershipClient.validateAssignment(
                "BRANCH_MANAGER", "branch-dhanmondi", "branch-dhanmondi", "BRANCH_MANAGER"
        );
        assertFalse(res.isValid);
        assertEquals("ERR_PERMISSION_DENIED", res.errorCode);
    }

    @Test
    @DisplayName("Frontline staff cannot assign branch memberships")
    void testFrontlineStaffDenied() {
        var res = PosBranchMembershipClient.validateAssignment(
                "CASHIER", "branch-dhanmondi", "branch-dhanmondi", "CASHIER"
        );
        assertFalse(res.isValid);
        assertEquals("ERR_PERMISSION_DENIED", res.errorCode);
    }

    @Test
    @DisplayName("ViewState handles transitions across loading, success, empty, and offline")
    void testViewStateTransitions() {
        var loading = PosBranchMembershipClient.BranchMembershipViewState.loading();
        assertEquals(PosBranchMembershipClient.UiStatus.LOADING, loading.uiStatus);

        var empty = PosBranchMembershipClient.BranchMembershipViewState.success(List.of());
        assertEquals(PosBranchMembershipClient.UiStatus.EMPTY, empty.uiStatus);

        var offlineRec = PosBranchMembershipClient.createOfflineFallback(
                "stf-1", "Rahim", "br-1", "Main Branch", "CASHIER"
        );
        var offline = PosBranchMembershipClient.BranchMembershipViewState.offline(List.of(offlineRec));
        assertEquals(PosBranchMembershipClient.UiStatus.OFFLINE, offline.uiStatus);
        assertEquals(1, offline.memberships.size());
    }
}
