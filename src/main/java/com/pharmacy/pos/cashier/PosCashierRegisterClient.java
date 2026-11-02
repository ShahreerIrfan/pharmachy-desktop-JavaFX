package com.pharmacy.pos.cashier;

import java.util.*;

/**
 * PosCashierRegisterClient
 * Architecture Decision: ADR-0117 (P117).
 * Phase 06: Milestone M03 - Offline-First Desktop POS & Owner Visibility.
 *
 * Client-side integration for JavaFX Desktop POS managing cashier drawer bindings,
 * shift schedule linkage, opening float tracking, and zero concurrent drawer collisions.
 */
public class PosCashierRegisterClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class RegisterAssignmentRecord {
        public final String assignmentId;
        public final String tenantId;
        public final String branchId;
        public final String registerId;
        public final String cashierId;
        public final String cashierName;
        public final double openingFloatAmount;
        public final Double closingFloatAmount;
        public final String status; // ACTIVE, CLOSED, RECONCILED
        public final String assignedAt;

        public RegisterAssignmentRecord(
                String assignmentId,
                String tenantId,
                String branchId,
                String registerId,
                String cashierId,
                String cashierName,
                double openingFloatAmount,
                Double closingFloatAmount,
                String status,
                String assignedAt) {
            this.assignmentId = assignmentId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.registerId = registerId;
            this.cashierId = cashierId;
            this.cashierName = cashierName;
            this.openingFloatAmount = openingFloatAmount;
            this.closingFloatAmount = closingFloatAmount;
            this.status = status;
            this.assignedAt = assignedAt;
        }
    }

    public static final class AssignmentOpResult {
        public final UiStatus uiStatus;
        public final RegisterAssignmentRecord record;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public AssignmentOpResult(UiStatus uiStatus, RegisterAssignmentRecord record, String errorCode, String messageEn, String messageBn) {
            this.uiStatus = uiStatus;
            this.record = record;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static AssignmentOpResult success(RegisterAssignmentRecord record) {
            return new AssignmentOpResult(
                    UiStatus.SUCCESS, record, null,
                    "Cashier register assigned successfully.",
                    "ক্যাশিয়ার রেজিস্টার সফলভাবে বরাদ্দ করা হয়েছে।"
            );
        }

        public static AssignmentOpResult error(UiStatus status, String code, String msgEn, String msgBn) {
            return new AssignmentOpResult(status, null, code, msgEn, msgBn);
        }
    }

    public AssignmentOpResult attemptRegisterAssignment(
            List<RegisterAssignmentRecord> currentAssignments,
            String callerRole,
            String callerTenantId,
            String callerBranchId,
            String callerStaffId,
            String targetTenantId,
            String targetBranchId,
            String targetRegisterId,
            String targetCashierId,
            String targetCashierName,
            double openingFloatAmount) {

        // 1. Cross-tenant check
        if (!Objects.equals(callerTenantId, targetTenantId) && !"SUPERADMIN".equalsIgnoreCase(callerRole)) {
            return AssignmentOpResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_CROSS_TENANT_VIOLATION",
                    "Cross-tenant register assignment forbidden.",
                    "অন্য টেন্যান্টের রেজিস্টার বরাদ্দ করা যাবে না।"
            );
        }

        boolean isOwner = "PHARMACY_OWNER".equalsIgnoreCase(callerRole) || "SUPERADMIN".equalsIgnoreCase(callerRole);
        boolean isManager = "BRANCH_MANAGER".equalsIgnoreCase(callerRole);

        // 2. Branch Manager scoping
        if (isManager && !Objects.equals(callerBranchId, targetBranchId)) {
            return AssignmentOpResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_CROSS_BRANCH_VIOLATION",
                    "Branch managers cannot assign registers in foreign branches.",
                    "শাখা ব্যবস্থাপক অন্য শাখার রেজিস্টার বরাদ্দ করতে পারেন না।"
            );
        }

        // 3. Cashier self-only scoping
        if (!isOwner && !isManager && !Objects.equals(callerStaffId, targetCashierId)) {
            return AssignmentOpResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_PERMISSION_DENIED",
                    "Cashiers cannot assign registers to other staff members.",
                    "ক্যাশিয়ার অন্য স্টাফের জন্য রেজিস্টার বরাদ্দ করতে পারেন না।"
            );
        }

        // 4. Invariant: Register already actively assigned?
        for (RegisterAssignmentRecord r : currentAssignments) {
            if ("ACTIVE".equalsIgnoreCase(r.status) && Objects.equals(r.branchId, targetBranchId) && Objects.equals(r.registerId, targetRegisterId)) {
                return AssignmentOpResult.error(
                        UiStatus.ERROR,
                        "ERR_REGISTER_ALREADY_ASSIGNED",
                        "Register " + targetRegisterId + " is already actively assigned to " + r.cashierName,
                        "এই রেজিস্টারটি ইতিমধ্যেই সক্রিয়ভাবে বরাদ্দকৃত।"
                );
            }
        }

        // 5. Invariant: Cashier already operating another register?
        for (RegisterAssignmentRecord r : currentAssignments) {
            if ("ACTIVE".equalsIgnoreCase(r.status) && Objects.equals(r.cashierId, targetCashierId)) {
                return AssignmentOpResult.error(
                        UiStatus.ERROR,
                        "ERR_CASHIER_ALREADY_ASSIGNED",
                        "Cashier " + targetCashierName + " is already assigned to register " + r.registerId,
                        "এই ক্যাশিয়ার ইতিমধ্যেই অন্য একটি রেজিস্টারে সক্রিয় আছেন।"
                );
            }
        }

        RegisterAssignmentRecord newRecord = new RegisterAssignmentRecord(
                "cra-local-" + UUID.randomUUID().toString().substring(0, 8),
                targetTenantId,
                targetBranchId,
                targetRegisterId,
                targetCashierId,
                targetCashierName,
                openingFloatAmount,
                null,
                "ACTIVE",
                java.time.Instant.now().toString()
        );
        return AssignmentOpResult.success(newRecord);
    }
}
