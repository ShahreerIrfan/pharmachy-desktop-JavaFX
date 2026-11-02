package com.pharmacy.pos.security;

import java.util.*;

/**
 * PosRbacNegativeMatrixClient
 * Architecture Decision: ADR-0120 (P120).
 * Phase 06: Milestone M03 - Offline-First Desktop POS & Owner Visibility.
 *
 * Client-side security verification engine enforcing deterministic
 * 403 Forbidden rejections on forbidden actions across all staff roles and branch boundaries.
 */
public class PosRbacNegativeMatrixClient {

    public enum RbacDecision {
        PERMITTED,
        DENIED_ROLE,
        DENIED_BRANCH,
        DENIED_CROSS_TENANT,
        DENIED_SUBSCRIPTION
    }

    public static final class RbacEvalResult {
        public final boolean isAllowed;
        public final int httpStatusCode;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public RbacEvalResult(boolean isAllowed, int httpStatusCode, String errorCode, String messageEn, String messageBn) {
            this.isAllowed = isAllowed;
            this.httpStatusCode = httpStatusCode;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static RbacEvalResult permitted() {
            return new RbacEvalResult(true, 200, null, "Action permitted.", "অনুমোদিত অ্যাকশন।");
        }

        public static RbacEvalResult forbidden(String errorCode, String msgEn, String msgBn) {
            return new RbacEvalResult(false, 403, errorCode, msgEn, msgBn);
        }
    }

    /**
     * Evaluates permission for an intended staff action against role, branch, and tenant boundaries.
     */
    public RbacEvalResult evaluateAction(
            String callerRole,
            String callerTenantId,
            String callerBranchId,
            String targetTenantId,
            String targetBranchId,
            String actionName,
            String subscriptionStatus) {

        // 1. Cross-Tenant Barrier
        if (!Objects.equals(callerTenantId, targetTenantId) && !"SUPERADMIN".equalsIgnoreCase(callerRole)) {
            return RbacEvalResult.forbidden(
                    "ERR_CROSS_TENANT_VIOLATION",
                    "Cross-tenant access strictly forbidden.",
                    "অন্য টেন্যান্টের ডেটা অ্যাক্সেস নিষিদ্ধ।"
            );
        }

        // 2. Suspended Tenant Barrier
        if ("SUSPENDED".equalsIgnoreCase(subscriptionStatus) || "ARCHIVED".equalsIgnoreCase(subscriptionStatus)) {
            return RbacEvalResult.forbidden(
                    "ERR_SUBSCRIPTION_UNENTITLED_WRITE_BLOCKED",
                    "Tenant subscription is suspended.",
                    "টেন্যান্ট অ্যাকাউন্ট স্থগিত থাকায় পরিবর্তন নিষিদ্ধ।"
            );
        }

        String role = callerRole != null ? callerRole.toUpperCase() : "";
        boolean isOwner = "PHARMACY_OWNER".equals(role) || "SUPERADMIN".equals(role);
        boolean isManager = "BRANCH_MANAGER".equals(role);
        boolean isCashier = "CASHIER".equals(role);
        boolean isPharmacist = "STAFF_PHARMACIST".equals(role) || "PHARMACIST".equals(role);

        // 3. Actions requiring Pharmacy Owner / Superadmin
        if (Set.of("ROLE_MIGRATION", "MODIFY_SYSTEM_POLICIES", "MANAGE_BILLING").contains(actionName)) {
            if (!isOwner) {
                return RbacEvalResult.forbidden(
                        "ERR_STAFF_ROLE_DENIED",
                        "Only Pharmacy Owners may execute this administrative action.",
                        "শুধুমাত্র ফার্মেসি মালিক এই প্রশাসনিক কাজ করতে পারেন।"
                );
            }
        }

        // 4. Branch Operating Settings
        if ("PATCH_BRANCH_SETTINGS".equals(actionName)) {
            if (isCashier || isPharmacist) {
                return RbacEvalResult.forbidden(
                        "ERR_STAFF_ROLE_DENIED",
                        "Cashiers and Pharmacists cannot modify branch operating settings.",
                        "ক্যাশিয়ার এবং ফার্মাসিস্ট শাখা সেটিংস পরিবর্তন করতে পারেন না।"
                );
            }
            if (isManager && !Objects.equals(callerBranchId, targetBranchId)) {
                return RbacEvalResult.forbidden(
                        "ERR_CROSS_BRANCH_VIOLATION",
                        "Branch managers cannot modify foreign branch settings.",
                        "শাখা ব্যবস্থাপক অন্য শাখার সেটিংস পরিবর্তন করতে পারেন না।"
                );
            }
        }

        // 5. Staff Audit Timeline
        if ("GET_STAFF_TIMELINE".equals(actionName)) {
            if (isCashier || isPharmacist) {
                return RbacEvalResult.forbidden(
                        "ERR_STAFF_ROLE_DENIED",
                        "Staff role is unauthorized to view staff audit timelines.",
                        "স্টাফ অডিট টাইমলাইন দেখার অনুমতি নেই।"
                );
            }
            if (isManager && targetBranchId != null && !Objects.equals(callerBranchId, targetBranchId)) {
                return RbacEvalResult.forbidden(
                        "ERR_CROSS_BRANCH_VIOLATION",
                        "Branch managers cannot inspect foreign branch timelines.",
                        "শাখা ব্যবস্থাপক অন্য শাখার টাইমলাইন দেখতে পারেন না।"
                );
            }
        }

        // 6. Cashier Drawer Assignment
        if ("ASSIGN_OTHER_DRAWER".equals(actionName)) {
            if (isCashier) {
                return RbacEvalResult.forbidden(
                        "ERR_PERMISSION_DENIED",
                        "Cashiers cannot assign registers to other staff members.",
                        "ক্যাশিয়ার অন্য স্টাফের জন্য রেজিস্টার বরাদ্দ করতে পারেন না।"
                );
            }
            if (isManager && !Objects.equals(callerBranchId, targetBranchId)) {
                return RbacEvalResult.forbidden(
                        "ERR_CROSS_BRANCH_VIOLATION",
                        "Branch managers cannot assign registers in foreign branches.",
                        "শাখা ব্যবস্থাপক অন্য শাখার রেজিস্টার বরাদ্দ করতে পারেন না।"
                );
            }
        }

        return RbacEvalResult.permitted();
    }
}
