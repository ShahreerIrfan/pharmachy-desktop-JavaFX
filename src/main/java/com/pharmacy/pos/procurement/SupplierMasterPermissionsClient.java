package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * SupplierMasterPermissionsClient
 * Architecture Decision: ADR-0271 (P271).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * Desktop POS client governing Supplier Master RBAC permissions,
 * tenant isolation, and controlled duplicate merges.
 */
public class SupplierMasterPermissionsClient {

    private final String tenantId;
    private final String userRole;
    private final String userId;
    private final Map<String, SupplierRecord> supplierStore;

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "STAFF_PHARMACIST",
            "INVENTORY_CLERK", "FINANCE_OFFICER", "AUDITOR", "OWNER_ADMIN", "ADMIN", "PROCUREMENT_OFFICER"
    );

    public static final Set<String> MANAGE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER",
            "INVENTORY_CLERK", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN", "PROCUREMENT_OFFICER"
    );

    public static final Set<String> ADMIN_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "OWNER_ADMIN", "ADMIN"
    );

    public SupplierMasterPermissionsClient(String tenantId, String userRole, String userId) {
        this(tenantId, userRole, userId, new HashMap<>());
    }

    public SupplierMasterPermissionsClient(
            String tenantId,
            String userRole,
            String userId,
            Map<String, SupplierRecord> supplierStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.supplierStore = supplierStore != null ? supplierStore : new HashMap<>();
    }

    public boolean canView() {
        return VIEW_ROLES.contains(this.userRole);
    }

    public boolean canManage() {
        return MANAGE_ROLES.contains(this.userRole);
    }

    public boolean canAdmin() {
        return ADMIN_ROLES.contains(this.userRole);
    }

    public List<SupplierRecord> listSuppliers(String query) {
        if (!canView()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " is not permitted to view suppliers.");
        }
        List<SupplierRecord> result = new ArrayList<>();
        for (SupplierRecord s : supplierStore.values()) {
            if (!this.tenantId.equals(s.tenantId)) {
                continue;
            }
            if (query != null && !query.trim().isEmpty()) {
                String q = query.trim().toLowerCase();
                boolean match = s.name.toLowerCase().contains(q)
                        || (s.phone != null && s.phone.contains(q))
                        || (s.dlNumber != null && s.dlNumber.toLowerCase().contains(q));
                if (!match) {
                    continue;
                }
            }
            result.add(s);
        }
        result.sort(Comparator.comparing(a -> a.name));
        return result;
    }

    public SupplierRecord getSupplier(String supplierId) {
        if (!canView()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " is not permitted to view suppliers.");
        }
        SupplierRecord s = supplierStore.get(supplierId);
        if (s == null) {
            throw new NoSuchElementException("ERR_SUPPLIER_NOT_FOUND: Supplier " + supplierId + " not found.");
        }
        if (!this.tenantId.equals(s.tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cannot access supplier from different tenant.");
        }
        return s;
    }

    public SupplierRecord createSupplier(String name, String phone, BigDecimal creditLimit) {
        if (!canManage()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " cannot register suppliers.");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_SUPPLIER_NAME: Supplier name is required.");
        }
        if (creditLimit != null && creditLimit.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("ERR_INVALID_CREDIT_LIMIT: Credit limit cannot be negative.");
        }
        String supId = "sup-" + UUID.randomUUID().toString().substring(0, 12);
        SupplierRecord s = new SupplierRecord(
                supId,
                this.tenantId,
                name.trim(),
                phone != null ? phone.trim() : "",
                creditLimit != null ? creditLimit : BigDecimal.ZERO,
                BigDecimal.ZERO,
                true,
                null,
                null,
                Instant.now().toString()
        );
        supplierStore.put(supId, s);
        return s;
    }

    public MergeResult mergeSuppliers(String sourceId, String targetId, String reason) {
        if (!canAdmin()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Only owners/managers can merge suppliers.");
        }
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("ERR_CANNOT_MERGE_SELF: Cannot merge supplier into itself.");
        }
        SupplierRecord source = supplierStore.get(sourceId);
        if (source == null) {
            throw new NoSuchElementException("ERR_SOURCE_NOT_FOUND: Source supplier not found.");
        }
        SupplierRecord target = supplierStore.get(targetId);
        if (target == null) {
            throw new NoSuchElementException("ERR_TARGET_NOT_FOUND: Target supplier not found.");
        }
        if (!this.tenantId.equals(source.tenantId) || !this.tenantId.equals(target.tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant merge strictly forbidden.");
        }
        if (!target.isActive || target.mergedIntoId != null) {
            throw new IllegalStateException("ERR_TARGET_ALREADY_MERGED: Target supplier is inactive or merged.");
        }

        // Transfer balance
        BigDecimal transferred = source.currentBalance;
        target.currentBalance = target.currentBalance.add(transferred);

        // Archive source
        source.isActive = false;
        source.mergedIntoId = target.supplierId;
        source.mergeReason = reason != null ? reason : "Consolidation of duplicate supplier entries";
        source.currentBalance = BigDecimal.ZERO;

        return new MergeResult(
                source.supplierId,
                target.supplierId,
                target.name,
                true,
                source.mergeReason,
                Instant.now().toString()
        );
    }

    public SupplierRecord archiveSupplier(String supplierId) {
        if (!canAdmin()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Only owners/managers can archive suppliers.");
        }
        SupplierRecord s = getSupplier(supplierId);
        if (s.currentBalance.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("ERR_SUPPLIER_OUTSTANDING_BALANCE: Outstanding balance exists.");
        }
        s.isActive = false;
        return s;
    }

    public static class SupplierRecord {
        public final String supplierId;
        public final String tenantId;
        public String name;
        public String phone;
        public String dlNumber;
        public BigDecimal creditLimit;
        public BigDecimal currentBalance;
        public boolean isActive;
        public String mergedIntoId;
        public String mergeReason;
        public final String createdAt;

        public SupplierRecord(
                String supplierId,
                String tenantId,
                String name,
                String phone,
                BigDecimal creditLimit,
                BigDecimal currentBalance,
                boolean isActive,
                String mergedIntoId,
                String mergeReason,
                String createdAt) {
            this.supplierId = supplierId;
            this.tenantId = tenantId;
            this.name = name;
            this.phone = phone;
            this.creditLimit = creditLimit;
            this.currentBalance = currentBalance;
            this.isActive = isActive;
            this.mergedIntoId = mergedIntoId;
            this.mergeReason = mergeReason;
            this.createdAt = createdAt;
        }
    }

    public static class MergeResult {
        public final String sourceId;
        public final String targetId;
        public final String targetName;
        public final boolean isMerged;
        public final String mergeReason;
        public final String mergedAt;

        public MergeResult(String sourceId, String targetId, String targetName, boolean isMerged, String mergeReason, String mergedAt) {
            this.sourceId = sourceId;
            this.targetId = targetId;
            this.targetName = targetName;
            this.isMerged = isMerged;
            this.mergeReason = mergeReason;
            this.mergedAt = mergedAt;
        }
    }
}
