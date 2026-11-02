package com.pharmacy.pos.staff;

import java.util.*;

/**
 * PosBranchMembershipClient
 * Architecture Decision: ADR-0105
 * Phase 06: Milestone M03 - Branch-Specific Membership (P105)
 *
 * Client-side integration for branch-specific memberships in JavaFX Desktop POS,
 * allowing staff accounts to hold different roles across separate authorized branches
 * with local evaluation, separation of duties validation, and offline caching.
 */
public class PosBranchMembershipClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        OFFLINE,
        ERROR,
        PERMISSION_DENIED
    }

    public static final class BranchMembershipRecord {
        public final String membershipId;
        public final String tenantId;
        public final String staffMemberId;
        public final String staffName;
        public final String staffUsername;
        public final String branchId;
        public final String branchName;
        public final String role;
        public final String customRoleCode;
        public final String customRoleTitle;
        public final boolean isPrimary;
        public final boolean isActive;
        public final String assignedAt;
        public final String assignedByUsername;

        public BranchMembershipRecord(
                String membershipId,
                String tenantId,
                String staffMemberId,
                String staffName,
                String staffUsername,
                String branchId,
                String branchName,
                String role,
                String customRoleCode,
                String customRoleTitle,
                boolean isPrimary,
                boolean isActive,
                String assignedAt,
                String assignedByUsername) {
            this.membershipId = membershipId;
            this.tenantId = tenantId;
            this.staffMemberId = staffMemberId;
            this.staffName = staffName;
            this.staffUsername = staffUsername;
            this.branchId = branchId;
            this.branchName = branchName;
            this.role = role != null ? role.toUpperCase() : "CASHIER";
            this.customRoleCode = customRoleCode != null ? customRoleCode : "";
            this.customRoleTitle = customRoleTitle != null ? customRoleTitle : "";
            this.isPrimary = isPrimary;
            this.isActive = isActive;
            this.assignedAt = assignedAt;
            this.assignedByUsername = assignedByUsername != null ? assignedByUsername : "";
        }
    }

    public static final class ValidationResult {
        public final boolean isValid;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public ValidationResult(boolean isValid, String errorCode, String messageEn, String messageBn) {
            this.isValid = isValid;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null, "Assignment valid.", "অ্যাসাইনমেন্ট বৈধ।");
        }

        public static ValidationResult invalid(String code, String en, String bn) {
            return new ValidationResult(false, code, en, bn);
        }
    }

    public static final class BranchMembershipViewState {
        public final UiStatus uiStatus;
        public final List<BranchMembershipRecord> memberships;
        public final String messageEn;
        public final String messageBn;

        public BranchMembershipViewState(
                UiStatus uiStatus,
                List<BranchMembershipRecord> memberships,
                String messageEn,
                String messageBn) {
            this.uiStatus = uiStatus;
            this.memberships = memberships != null
                    ? Collections.unmodifiableList(new ArrayList<>(memberships))
                    : Collections.emptyList();
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static BranchMembershipViewState loading() {
            return new BranchMembershipViewState(
                    UiStatus.LOADING, Collections.emptyList(), "Loading branch memberships...", "ব্রাঞ্চ মেম্বারশিপ লোড হচ্ছে...");
        }

        public static BranchMembershipViewState success(List<BranchMembershipRecord> list) {
            if (list == null || list.isEmpty()) {
                return new BranchMembershipViewState(
                        UiStatus.EMPTY, Collections.emptyList(), "No branch assignments found.", "কোনো ব্রাঞ্চ মেম্বারশিপ পাওয়া যায়নি।");
            }
            return new BranchMembershipViewState(
                    UiStatus.SUCCESS, list, "Branch assignments synchronized.", "ব্রাঞ্চ মেম্বারশিপ সিঙ্ক হয়েছে।");
        }

        public static BranchMembershipViewState offline(List<BranchMembershipRecord> cached) {
            return new BranchMembershipViewState(
                    UiStatus.OFFLINE, cached, "Operating offline with cached assignments.", "অফলাইনে ক্যাশ করা ব্রাঞ্চ ডেটা নিয়ে চলছে।");
        }

        public static BranchMembershipViewState denied(String en, String bn) {
            return new BranchMembershipViewState(UiStatus.PERMISSION_DENIED, Collections.emptyList(), en, bn);
        }

        public static BranchMembershipViewState error(String en, String bn) {
            return new BranchMembershipViewState(UiStatus.ERROR, Collections.emptyList(), en, bn);
        }
    }

    private PosBranchMembershipClient() {}

    /**
     * Resolves effective role for a staff member within a specific branch.
     */
    public static Optional<BranchMembershipRecord> resolveEffectiveRole(
            List<BranchMembershipRecord> assignments, String branchId) {
        if (assignments == null || branchId == null) return Optional.empty();
        for (BranchMembershipRecord rec : assignments) {
            if (rec.isActive && branchId.equals(rec.branchId)) {
                return Optional.of(rec);
            }
        }
        return Optional.empty();
    }

    /**
     * Validates whether a caller can assign a specific role to a target branch (RBAC & SoD).
     */
    public static ValidationResult validateAssignment(
            String callerRole, String callerBranchId, String targetBranchId, String targetRole) {
        String cr = callerRole != null ? callerRole.toUpperCase() : "UNKNOWN";
        String tr = targetRole != null ? targetRole.toUpperCase() : "UNKNOWN";

        if ("PHARMACY_OWNER".equals(cr) || "SUPERADMIN".equals(cr)) {
            return ValidationResult.valid();
        }

        if ("BRANCH_MANAGER".equals(cr)) {
            if (callerBranchId != null && targetBranchId != null && !callerBranchId.equals(targetBranchId)) {
                return ValidationResult.invalid(
                        "ERR_BRANCH_ACCESS_DENIED",
                        "Branch Manager cannot assign staff to other branches.",
                        "ব্রাঞ্চ ম্যানেজার অন্য ব্রাঞ্চে স্টাফ নিয়োগ করতে পারেন না।"
                );
            }
            if ("PHARMACY_OWNER".equals(tr) || "BRANCH_MANAGER".equals(tr) || "SUPERADMIN".equals(tr)) {
                return ValidationResult.invalid(
                        "ERR_PERMISSION_DENIED",
                        "Branch Manager cannot assign managerial or ownership roles.",
                        "ব্রাঞ্চ ম্যানেজার ওনার বা ম্যানেজার রোল প্রদান করতে পারেন না।"
                );
            }
            return ValidationResult.valid();
        }

        return ValidationResult.invalid(
                "ERR_PERMISSION_DENIED",
                "Only Pharmacy Owner or Branch Manager can assign branch memberships.",
                "শুধুমাত্র ওনার বা ব্রাঞ্চ ম্যানেজার ব্রাঞ্চ মেম্বারশিপ পরিচালনা করতে পারেন।"
        );
    }

    /**
     * Creates an offline cached fallback record.
     */
    public static BranchMembershipRecord createOfflineFallback(
            String staffMemberId, String staffName, String branchId, String branchName, String role) {
        return new BranchMembershipRecord(
                "bsm-offline-" + branchId,
                "tenant-default",
                staffMemberId,
                staffName,
                staffMemberId + "@pharmacy.local",
                branchId,
                branchName,
                role,
                "",
                "",
                true,
                true,
                null,
                "offline_cached"
        );
    }
}
