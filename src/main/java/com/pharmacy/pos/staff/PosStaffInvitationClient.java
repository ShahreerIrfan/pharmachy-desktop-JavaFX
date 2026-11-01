package com.pharmacy.pos.staff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PosStaffInvitationClient
 * Architecture Decision: ADR-0102
 * Phase 06: Milestone M03 - Staff Onboarding & Multi-Branch Scopes (P102)
 * Requirement Trace: FR-031, FR-032, FR-049, FR-082
 *
 * Client-side integration for owner-created staff invitations, accepted memberships,
 * roving branch scopes, resend, revocation, and offline state handling in JavaFX Desktop POS.
 */
public class PosStaffInvitationClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        OFFLINE,
        ERROR,
        PERMISSION_DENIED
    }

    public static final class InvitationRecord {
        public final String invitationId;
        public final String tenantId;
        public final String branchId;
        public final String branchName;
        public final String email;
        public final String fullName;
        public final String role;
        public final String customRoleTitle;
        public final String status; // PENDING, ACCEPTED, REVOKED, EXPIRED
        public final String expiresAt;
        public final int resendCount;

        public InvitationRecord(
                String invitationId,
                String tenantId,
                String branchId,
                String branchName,
                String email,
                String fullName,
                String role,
                String customRoleTitle,
                String status,
                String expiresAt,
                int resendCount) {
            this.invitationId = invitationId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.branchName = branchName;
            this.email = email;
            this.fullName = fullName;
            this.role = role;
            this.customRoleTitle = customRoleTitle != null ? customRoleTitle : "";
            this.status = status;
            this.expiresAt = expiresAt;
            this.resendCount = resendCount;
        }

        public boolean isPending() {
            return "PENDING".equalsIgnoreCase(this.status);
        }

        public boolean isAccepted() {
            return "ACCEPTED".equalsIgnoreCase(this.status);
        }

        public boolean isRevoked() {
            return "REVOKED".equalsIgnoreCase(this.status);
        }

        public boolean isExpired() {
            return "EXPIRED".equalsIgnoreCase(this.status);
        }
    }

    public static final class MembershipRecord {
        public final String membershipId;
        public final String tenantId;
        public final String staffId;
        public final String username;
        public final String fullName;
        public final String primaryBranchId;
        public final String primaryBranchName;
        public final List<String> assignedBranchIds;
        public final String role;
        public final String customRoleTitle;
        public final boolean isActive;
        public final String joinedAt;

        public MembershipRecord(
                String membershipId,
                String tenantId,
                String staffId,
                String username,
                String fullName,
                String primaryBranchId,
                String primaryBranchName,
                List<String> assignedBranchIds,
                String role,
                String customRoleTitle,
                boolean isActive,
                String joinedAt) {
            this.membershipId = membershipId;
            this.tenantId = tenantId;
            this.staffId = staffId;
            this.username = username;
            this.fullName = fullName;
            this.primaryBranchId = primaryBranchId;
            this.primaryBranchName = primaryBranchName;
            this.assignedBranchIds = assignedBranchIds != null
                    ? Collections.unmodifiableList(new ArrayList<>(assignedBranchIds))
                    : Collections.emptyList();
            this.role = role;
            this.customRoleTitle = customRoleTitle != null ? customRoleTitle : "";
            this.isActive = isActive;
            this.joinedAt = joinedAt;
        }
    }

    public static final class StaffViewState {
        public final UiStatus uiStatus;
        public final List<InvitationRecord> invitations;
        public final List<MembershipRecord> memberships;
        public final String messageEn;
        public final String messageBn;

        public StaffViewState(
                UiStatus uiStatus,
                List<InvitationRecord> invitations,
                List<MembershipRecord> memberships,
                String messageEn,
                String messageBn) {
            this.uiStatus = uiStatus;
            this.invitations = invitations != null
                    ? Collections.unmodifiableList(new ArrayList<>(invitations))
                    : Collections.emptyList();
            this.memberships = memberships != null
                    ? Collections.unmodifiableList(new ArrayList<>(memberships))
                    : Collections.emptyList();
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }
    }

    /**
     * Pre-evaluates whether caller has authorization to invite prospective role at target branch.
     */
    public boolean canDispatchInvitation(
            String callerRole,
            String callerBranchId,
            String targetRole,
            String targetBranchId) {
        if (callerRole == null) return false;
        String normalizedCaller = callerRole.trim().toUpperCase();
        String normalizedTarget = targetRole != null ? targetRole.trim().toUpperCase() : "";

        if ("PHARMACY_OWNER".equals(normalizedCaller)
                || "TENANT_OWNER".equals(normalizedCaller)
                || "SUPERADMIN".equals(normalizedCaller)
                || "SUPER_ADMIN".equals(normalizedCaller)) {
            return true;
        }

        if ("BRANCH_MANAGER".equals(normalizedCaller)) {
            // Cannot invite owner or branch manager
            if ("PHARMACY_OWNER".equals(normalizedTarget)
                    || "TENANT_OWNER".equals(normalizedTarget)
                    || "BRANCH_MANAGER".equals(normalizedTarget)
                    || "SUPERADMIN".equals(normalizedTarget)) {
                return false;
            }
            // Must match manager's branch
            if (targetBranchId != null && !targetBranchId.equals(callerBranchId)) {
                return false;
            }
            return true;
        }

        // Cashiers, Pharmacists, and Clerks have zero invitation privileges
        return false;
    }

    public boolean canResend(InvitationRecord inv) {
        return inv != null && (inv.isPending() || inv.isExpired());
    }

    public boolean canRevoke(InvitationRecord inv) {
        return inv != null && (inv.isPending() || inv.isExpired());
    }

    public StaffViewState createSuccessState(
            List<InvitationRecord> invitations,
            List<MembershipRecord> memberships) {
        boolean empty = (invitations == null || invitations.isEmpty())
                && (memberships == null || memberships.isEmpty());
        return new StaffViewState(
                empty ? UiStatus.EMPTY : UiStatus.SUCCESS,
                invitations,
                memberships,
                empty ? "No active staff or invitations found." : "Staff roster and invitations loaded successfully.",
                empty ? "কোন স্টাফ বা আমন্ত্রণ পাওয়া যায়নি।" : "স্টাফ তালিকা এবং আমন্ত্রণসমূহ সফলভাবে লোড হয়েছে।"
        );
    }

    public StaffViewState createOfflineState(
            List<InvitationRecord> cachedInvitations,
            List<MembershipRecord> cachedMemberships) {
        return new StaffViewState(
                UiStatus.OFFLINE,
                cachedInvitations,
                cachedMemberships,
                "Working offline. Displaying cached staff roster. New invites will be queued.",
                "অফলাইনে কাজ চলছে। ক্যাশ করা স্টাফ তালিকা দেখানো হচ্ছে। নতুন আমন্ত্রণ পরে সিঙ্ক হবে।"
        );
    }

    public StaffViewState createPermissionDeniedState(String callerRole) {
        return new StaffViewState(
                UiStatus.PERMISSION_DENIED,
                Collections.emptyList(),
                Collections.emptyList(),
                "Access denied for role '" + callerRole + "'. Only pharmacy owners and branch managers can manage staff.",
                "আপনার রোলে ('" + callerRole + "') কর্মী ব্যবস্থাপনা দেখার অনুমতি নেই। শুধুমাত্র মালিক ও ম্যানেজার প্রবেশাধিকার রাখেন।"
        );
    }
}
