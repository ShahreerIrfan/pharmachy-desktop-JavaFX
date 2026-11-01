package com.pharmacy.pos.staff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStaffInvitationClientTest
 * Tests for JavaFX desktop staff membership and invitation client.
 */
public class PosStaffInvitationClientTest {

    private PosStaffInvitationClient client;

    @BeforeEach
    public void setUp() {
        client = new PosStaffInvitationClient();
    }

    @Test
    @DisplayName("01: Owner and Branch Manager role-based invitation dispatch permissions")
    public void test01RoleBasedInvitationDispatchRules() {
        // Owner can invite any role and branch
        assertTrue(client.canDispatchInvitation("PHARMACY_OWNER", "br-main", "BRANCH_MANAGER", "br-north"));
        assertTrue(client.canDispatchInvitation("PHARMACY_OWNER", "br-main", "CASHIER", "br-main"));

        // Branch Manager can invite Cashier or Pharmacist to their own branch
        assertTrue(client.canDispatchInvitation("BRANCH_MANAGER", "br-north", "CASHIER", "br-north"));
        assertTrue(client.canDispatchInvitation("BRANCH_MANAGER", "br-north", "PHARMACIST", "br-north"));

        // Branch Manager CANNOT invite another Branch Manager or Owner
        assertFalse(client.canDispatchInvitation("BRANCH_MANAGER", "br-north", "BRANCH_MANAGER", "br-north"));
        assertFalse(client.canDispatchInvitation("BRANCH_MANAGER", "br-north", "PHARMACY_OWNER", "br-north"));

        // Branch Manager CANNOT invite to another branch
        assertFalse(client.canDispatchInvitation("BRANCH_MANAGER", "br-north", "CASHIER", "br-south"));

        // Frontline cashier has NO invitation privileges
        assertFalse(client.canDispatchInvitation("CASHIER", "br-north", "CASHIER", "br-north"));
        assertFalse(client.canDispatchInvitation("STAFF_PHARMACIST", "br-north", "CASHIER", "br-north"));
    }

    @Test
    @DisplayName("02: Invitation lifecycle evaluation: resend and revocation criteria")
    public void test02InvitationLifecycleActions() {
        PosStaffInvitationClient.InvitationRecord pendingInv = new PosStaffInvitationClient.InvitationRecord(
                "inv-101", "tn-1", "br-1", "Main Branch", "cashier@test.com", "Rahim",
                "CASHIER", "Night Cashier", "PENDING", "2026-11-08T12:00:00Z", 0
        );
        assertTrue(pendingInv.isPending());
        assertTrue(client.canResend(pendingInv));
        assertTrue(client.canRevoke(pendingInv));

        PosStaffInvitationClient.InvitationRecord acceptedInv = new PosStaffInvitationClient.InvitationRecord(
                "inv-102", "tn-1", "br-1", "Main Branch", "pharmacist@test.com", "Karim",
                "PHARMACIST", "Clinical Lead", "ACCEPTED", "2026-11-08T12:00:00Z", 1
        );
        assertTrue(acceptedInv.isAccepted());
        assertFalse(client.canResend(acceptedInv));
        assertFalse(client.canRevoke(acceptedInv));

        PosStaffInvitationClient.InvitationRecord revokedInv = new PosStaffInvitationClient.InvitationRecord(
                "inv-103", "tn-1", "br-1", "Main Branch", "clerk@test.com", "Salam",
                "INVENTORY_CLERK", "Stock Keeper", "REVOKED", "2026-11-08T12:00:00Z", 0
        );
        assertTrue(revokedInv.isRevoked());
        assertFalse(client.canResend(revokedInv));
        assertFalse(client.canRevoke(revokedInv));
    }

    @Test
    @DisplayName("03: UI state construction: success, offline cache, and permission denied")
    public void test03UiStateHandling() {
        List<PosStaffInvitationClient.InvitationRecord> invs = Collections.singletonList(
                new PosStaffInvitationClient.InvitationRecord(
                        "inv-201", "tn-1", "br-1", "Branch 1", "test@test.com", "User",
                        "CASHIER", "", "PENDING", "2026-11-08T12:00:00Z", 0
                )
        );
        List<PosStaffInvitationClient.MembershipRecord> mems = Collections.singletonList(
                new PosStaffInvitationClient.MembershipRecord(
                        "mem-001", "tn-1", "stf-001", "john_doe", "John Doe",
                        "br-1", "Branch 1", Arrays.asList("br-1", "br-2"),
                        "CASHIER", "Lead Cashier", true, "2026-11-01T10:00:00Z"
                )
        );

        PosStaffInvitationClient.StaffViewState successState = client.createSuccessState(invs, mems);
        assertEquals(PosStaffInvitationClient.UiStatus.SUCCESS, successState.uiStatus);
        assertEquals(1, successState.invitations.size());
        assertEquals(1, successState.memberships.size());
        assertEquals(2, successState.memberships.get(0).assignedBranchIds.size());

        PosStaffInvitationClient.StaffViewState offlineState = client.createOfflineState(invs, mems);
        assertEquals(PosStaffInvitationClient.UiStatus.OFFLINE, offlineState.uiStatus);
        assertTrue(offlineState.messageEn.contains("offline"));
        assertTrue(offlineState.messageBn.contains("অফলাইনে"));

        PosStaffInvitationClient.StaffViewState deniedState = client.createPermissionDeniedState("CASHIER");
        assertEquals(PosStaffInvitationClient.UiStatus.PERMISSION_DENIED, deniedState.uiStatus);
        assertTrue(deniedState.messageEn.contains("CASHIER"));
        assertTrue(deniedState.messageBn.contains("অনুমতি নেই"));
    }
}
