package com.pharmacy.pos.roles;

import java.util.*;

/**
 * PosRoleMigrationClient
 * Architecture Decision: ADR-0119 (P119).
 * Phase 06: Milestone M03 - Offline-First Desktop POS & Owner Visibility.
 *
 * Client-side integration for versioning role grants, dry-run simulation,
 * pruning invalid plan entitlements, and zero forbidden grant restoration.
 */
public class PosRoleMigrationClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final Set<String> FORBIDDEN_PERMISSIONS = Set.of(
            "system:hard_delete",
            "stock:dispense_expired",
            "auth:bypass_tenant_isolation"
    );

    public static final Set<String> BASIC_ALLOWED = Set.of(
            "sales:checkout",
            "sales:view_reports",
            "cash:shift_close",
            "inventory:view_stock",
            "finance:due_repayment"
    );

    public static final class RoleMigrationRecord {
        public final String migrationId;
        public final String tenantId;
        public final String roleId;
        public final String roleCode;
        public final int previousVersion;
        public final int newVersion;
        public final String reason;
        public final List<String> previousPermissions;
        public final List<String> retainedPermissions;
        public final List<String> removedPermissions;
        public final List<String> forbiddenGrantsBlocked;
        public final boolean isDryRun;

        public RoleMigrationRecord(
                String migrationId,
                String tenantId,
                String roleId,
                String roleCode,
                int previousVersion,
                int newVersion,
                String reason,
                List<String> previousPermissions,
                List<String> retainedPermissions,
                List<String> removedPermissions,
                List<String> forbiddenGrantsBlocked,
                boolean isDryRun) {
            this.migrationId = migrationId;
            this.tenantId = tenantId;
            this.roleId = roleId;
            this.roleCode = roleCode;
            this.previousVersion = previousVersion;
            this.newVersion = newVersion;
            this.reason = reason;
            this.previousPermissions = previousPermissions;
            this.retainedPermissions = retainedPermissions;
            this.removedPermissions = removedPermissions;
            this.forbiddenGrantsBlocked = forbiddenGrantsBlocked;
            this.isDryRun = isDryRun;
        }
    }

    public static final class MigrationResult {
        public final UiStatus uiStatus;
        public final RoleMigrationRecord record;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public MigrationResult(UiStatus uiStatus, RoleMigrationRecord record, String errorCode, String messageEn, String messageBn) {
            this.uiStatus = uiStatus;
            this.record = record;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static MigrationResult success(RoleMigrationRecord record) {
            return new MigrationResult(
                    UiStatus.SUCCESS,
                    record,
                    null,
                    "Role migration completed successfully. Incremented to version " + record.newVersion,
                    "রোল মাইগ্রেশন সফল হয়েছে। নতুন সংস্করণ: " + record.newVersion
            );
        }

        public static MigrationResult error(UiStatus status, String code, String msgEn, String msgBn) {
            return new MigrationResult(status, null, code, msgEn, msgBn);
        }
    }

    public MigrationResult reconcileRole(
            String callerRole,
            String callerTenantId,
            String targetTenantId,
            String planTier,
            String roleId,
            String roleCode,
            int currentVersion,
            List<String> currentPermissions,
            String reason,
            boolean isDryRun) {

        // 1. Role RBAC: Only Owner or Superadmin
        if (!"PHARMACY_OWNER".equalsIgnoreCase(callerRole) && !"SUPERADMIN".equalsIgnoreCase(callerRole)) {
            return MigrationResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_STAFF_ROLE_DENIED",
                    "Only Pharmacy Owners or Superadmins may execute or preview role migrations.",
                    "শুধুমাত্র ফার্মেসি মালিক অথবা সুপারঅ্যাডমিন রোল মাইগ্রেশন সম্পাদন করতে পারেন।"
            );
        }

        // 2. Cross-Tenant Check
        if (!Objects.equals(callerTenantId, targetTenantId) && !"SUPERADMIN".equalsIgnoreCase(callerRole)) {
            return MigrationResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_CROSS_TENANT_VIOLATION",
                    "Cross-tenant role migration is strictly forbidden.",
                    "অন্য টেন্যান্টের রোল মাইগ্রেশন করা যাবে না।"
            );
        }

        // 3. Prune invalid entitlements & enforce zero forbidden grants
        List<String> retained = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> forbiddenBlocked = new ArrayList<>();

        Set<String> allowedSet = "BASIC".equalsIgnoreCase(planTier) ? BASIC_ALLOWED : null;

        for (String perm : currentPermissions) {
            if (FORBIDDEN_PERMISSIONS.contains(perm)) {
                forbiddenBlocked.add(perm);
                removed.add(perm);
            } else if (allowedSet != null && !allowedSet.contains(perm)) {
                removed.add(perm);
            } else {
                retained.add(perm);
            }
        }

        int newVersion = currentVersion + 1;
        RoleMigrationRecord record = new RoleMigrationRecord(
                "mig-loc-" + UUID.randomUUID().toString().substring(0, 8),
                targetTenantId,
                roleId,
                roleCode,
                currentVersion,
                newVersion,
                reason,
                new ArrayList<>(currentPermissions),
                retained,
                removed,
                forbiddenBlocked,
                isDryRun
        );

        return MigrationResult.success(record);
    }
}
