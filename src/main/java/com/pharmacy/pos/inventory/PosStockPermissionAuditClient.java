package com.pharmacy.pos.inventory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * PosStockPermissionAuditClient
 * Architecture Decision: ADR-0219 (P219).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Client for Role & Branch-Scoped Stock Permissions and Audits:
 * - Evaluates counting, adjustment, quarantine release, transfer, and valuation permissions.
 * - Enforces branch isolation for branch-bound staff.
 * - Records immutable audit trails for every ALLOWED and DENIED attempt.
 */
public class PosStockPermissionAuditClient {

    public enum StockActionScope {
        STOCK_COUNTING("Physical Cycle Counting", "ফিজিক্যাল সাইকেল কাউন্ট"),
        STOCK_ADJUSTMENT("Stock Adjustment / Correction", "স্টক অ্যাডজাস্টমেন্ট ও সংশোধন"),
        QUARANTINE_RELEASE("Quarantine Release", "কোয়ারেন্টাইন থেকে রিলিজ"),
        INTERBRANCH_TRANSFER("Interbranch Transfer", "আন্তঃশাখা স্টক স্থানান্তর"),
        VALUATION_DATA("Inventory Valuation Data", "ইনভেন্টরি ভ্যালুয়েশন ও কস্টিং ডাটা");

        public final String labelEn;
        public final String labelBn;

        StockActionScope(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum PermissionDecision {
        ALLOWED,
        DENIED
    }

    public static class AuditEntry {
        private final String auditId;
        private final String tenantId;
        private final String targetBranchId;
        private final String actorRole;
        private final String actorAssignedBranchId;
        private final StockActionScope actionScope;
        private final String resourceId;
        private final PermissionDecision decision;
        private final String errorCode;
        private final String reasonEn;
        private final String reasonBn;
        private final Instant createdAt;

        public AuditEntry(
                String auditId,
                String tenantId,
                String targetBranchId,
                String actorRole,
                String actorAssignedBranchId,
                StockActionScope actionScope,
                String resourceId,
                PermissionDecision decision,
                String errorCode,
                String reasonEn,
                String reasonBn
        ) {
            this.auditId = auditId;
            this.tenantId = tenantId;
            this.targetBranchId = targetBranchId;
            this.actorRole = actorRole;
            this.actorAssignedBranchId = actorAssignedBranchId;
            this.actionScope = actionScope;
            this.resourceId = resourceId;
            this.decision = decision;
            this.errorCode = errorCode;
            this.reasonEn = reasonEn;
            this.reasonBn = reasonBn;
            this.createdAt = Instant.now();
        }

        public String getAuditId() { return auditId; }
        public String getTenantId() { return tenantId; }
        public String getTargetBranchId() { return targetBranchId; }
        public String getActorRole() { return actorRole; }
        public String getActorAssignedBranchId() { return actorAssignedBranchId; }
        public StockActionScope getActionScope() { return actionScope; }
        public String getResourceId() { return resourceId; }
        public PermissionDecision getDecision() { return decision; }
        public String getErrorCode() { return errorCode; }
        public String getReasonEn() { return reasonEn; }
        public String getReasonBn() { return reasonBn; }
        public Instant getCreatedAt() { return createdAt; }
    }

    private static final Set<String> TENANT_WIDE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "AUDITOR"
    );

    private static final Map<StockActionScope, Set<String>> PERMISSION_MATRIX = Map.of(
            StockActionScope.STOCK_COUNTING, Set.of("SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "STAFF_PHARMACIST", "INVENTORY_CLERK"),
            StockActionScope.STOCK_ADJUSTMENT, Set.of("SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "STAFF_PHARMACIST"),
            StockActionScope.QUARANTINE_RELEASE, Set.of("SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "STAFF_PHARMACIST"),
            StockActionScope.INTERBRANCH_TRANSFER, Set.of("SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "STAFF_PHARMACIST"),
            StockActionScope.VALUATION_DATA, Set.of("SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "STAFF_PHARMACIST", "FINANCE_OFFICER", "AUDITOR")
    );

    private final List<AuditEntry> auditLogs = new CopyOnWriteArrayList<>();

    public AuditEntry evaluatePermission(
            String actorRole,
            String actorTenantId,
            String actorAssignedBranchId,
            String targetTenantId,
            String targetBranchId,
            StockActionScope actionScope,
            String resourceId
    ) {
        String roleUpper = actorRole != null ? actorRole.toUpperCase() : "UNKNOWN";
        String auditId = "paudit-" + UUID.randomUUID().toString().substring(0, 12);

        // 1. Cross-Tenant Check
        if (!"SUPERADMIN".equals(roleUpper) && !Objects.equals(actorTenantId, targetTenantId)) {
            AuditEntry entry = new AuditEntry(
                    auditId, targetTenantId, targetBranchId, roleUpper, actorAssignedBranchId,
                    actionScope, resourceId, PermissionDecision.DENIED, "ERR_CROSS_TENANT_ACCESS_DENIED",
                    "Cross-tenant stock action blocked.",
                    "অন্য টেন্যান্টের শাখায় স্টক অপারেশন অনুমোদিত নয়।"
            );
            auditLogs.add(entry);
            return entry;
        }

        // 2. Branch Isolation Check
        if (!TENANT_WIDE_ROLES.contains(roleUpper) && !Objects.equals(actorAssignedBranchId, targetBranchId)) {
            AuditEntry entry = new AuditEntry(
                    auditId, targetTenantId, targetBranchId, roleUpper, actorAssignedBranchId,
                    actionScope, resourceId, PermissionDecision.DENIED, "ERR_CROSS_BRANCH_ACCESS_DENIED",
                    "Cross-branch violation: role assigned to " + actorAssignedBranchId + " cannot act in " + targetBranchId,
                    "শাখার সীমানা লঙ্ঘন: অন্য শাখার ব্যবহারকারী এই শাখায় কাজ করতে পারবেন না।"
            );
            auditLogs.add(entry);
            return entry;
        }

        // 3. Role Matrix Check
        Set<String> allowed = PERMISSION_MATRIX.getOrDefault(actionScope, Collections.emptySet());
        if (!allowed.contains(roleUpper)) {
            AuditEntry entry = new AuditEntry(
                    auditId, targetTenantId, targetBranchId, roleUpper, actorAssignedBranchId,
                    actionScope, resourceId, PermissionDecision.DENIED, "ERR_ROLE_NOT_AUTHORIZED",
                    "Role " + roleUpper + " is forbidden from " + actionScope.name(),
                    roleUpper + " রোল-এর জন্য এই অ্যাকশন নিষিদ্ধ।"
            );
            auditLogs.add(entry);
            return entry;
        }

        // 4. Allowed
        AuditEntry entry = new AuditEntry(
                auditId, targetTenantId, targetBranchId, roleUpper, actorAssignedBranchId,
                actionScope, resourceId, PermissionDecision.ALLOWED, "",
                "Authorized: " + roleUpper + " permitted for " + actionScope.name(),
                "অনুমোদিত।"
        );
        auditLogs.add(entry);
        return entry;
    }

    public AuditEntry assertAuthorized(
            String actorRole,
            String actorTenantId,
            String actorAssignedBranchId,
            String targetTenantId,
            String targetBranchId,
            StockActionScope actionScope,
            String resourceId
    ) {
        AuditEntry entry = evaluatePermission(
                actorRole, actorTenantId, actorAssignedBranchId,
                targetTenantId, targetBranchId, actionScope, resourceId
        );
        if (entry.getDecision() == PermissionDecision.DENIED) {
            throw new SecurityException(entry.getErrorCode() + ": " + entry.getReasonEn());
        }
        return entry;
    }

    public List<AuditEntry> getAuditLogs() {
        return Collections.unmodifiableList(auditLogs);
    }
}
