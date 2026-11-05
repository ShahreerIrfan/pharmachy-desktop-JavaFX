package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * PosPurchaseRequisitionClient
 * Architecture Decision: ADR-0184 (P184).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 *
 * Desktop POS client governing department purchase requisitions, requested item lines,
 * budget limits, and multi-tier approval state machine.
 */
public class PosPurchaseRequisitionClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, PurchaseRequisitionDto> requisitionStore;

    private static final Set<String> ALLOWED_PROCUREMENT_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "PROCUREMENT_OFFICER", "AUDITOR"
    );

    private static final Set<String> APPROVAL_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "PROCUREMENT_OFFICER"
    );

    public PosPurchaseRequisitionClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, PurchaseRequisitionDto> requisitionStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.requisitionStore = requisitionStore != null ? requisitionStore : new HashMap<>();
    }

    public static final class PurchaseRequisitionItemDto {
        public final String itemId;
        public final String productId;
        public final String productName;
        public final String preferredSupplierId;
        public final String preferredSupplierName;
        public final String packageType;
        public final int packageSize;
        public final int requestedQuantity;
        public final BigDecimal estimatedUnitCost;
        public final BigDecimal estimatedTotalCost;
        public final int currentStockQuantity;
        public final String notes;

        public PurchaseRequisitionItemDto(
                String itemId,
                String productId,
                String productName,
                String preferredSupplierId,
                String preferredSupplierName,
                String packageType,
                int packageSize,
                int requestedQuantity,
                BigDecimal estimatedUnitCost,
                int currentStockQuantity,
                String notes) {
            this.itemId = itemId != null ? itemId : "pri-" + UUID.randomUUID().toString().substring(0, 8);
            this.productId = productId != null ? productId : "";
            this.productName = productName != null ? productName : "";
            this.preferredSupplierId = preferredSupplierId;
            this.preferredSupplierName = preferredSupplierName != null ? preferredSupplierName : "";
            this.packageType = packageType != null ? packageType : "BOX";
            this.packageSize = Math.max(1, packageSize);
            this.requestedQuantity = Math.max(1, requestedQuantity);
            this.estimatedUnitCost = estimatedUnitCost != null ? estimatedUnitCost.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.estimatedTotalCost = this.estimatedUnitCost.multiply(BigDecimal.valueOf(this.requestedQuantity)).setScale(4, RoundingMode.HALF_UP);
            this.currentStockQuantity = Math.max(0, currentStockQuantity);
            this.notes = notes != null ? notes : "";
        }
    }

    public static final class PurchaseRequisitionDto {
        public final String requisitionId;
        public final String tenantId;
        public final String branchId;
        public String branchName;
        public String department;
        public String priority;
        public String status;
        public BigDecimal totalEstimatedCost;
        public BigDecimal allocatedBudget;
        public String justification;
        public LocalDate targetDeliveryDate;
        public String submittedById;
        public Instant submittedAt;
        public String approvedById;
        public Instant approvedAt;
        public String approvalNotes;
        public String rejectedById;
        public Instant rejectedAt;
        public String rejectionReason;
        public boolean isActive;
        public final List<PurchaseRequisitionItemDto> items;
        public final Instant createdAt;

        public PurchaseRequisitionDto(
                String requisitionId,
                String tenantId,
                String branchId,
                String branchName,
                String department,
                String priority,
                String status,
                BigDecimal totalEstimatedCost,
                BigDecimal allocatedBudget,
                String justification,
                LocalDate targetDeliveryDate,
                String submittedById,
                Instant submittedAt,
                String approvedById,
                Instant approvedAt,
                String approvalNotes,
                String rejectedById,
                Instant rejectedAt,
                String rejectionReason,
                boolean isActive,
                List<PurchaseRequisitionItemDto> items,
                Instant createdAt) {
            this.requisitionId = requisitionId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.branchName = branchName != null ? branchName : "";
            this.department = department != null ? department : "DISPENSARY";
            this.priority = priority != null ? priority : "MEDIUM";
            this.status = status != null ? status : "DRAFT";
            this.totalEstimatedCost = totalEstimatedCost != null ? totalEstimatedCost.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.allocatedBudget = allocatedBudget != null ? allocatedBudget.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.justification = justification != null ? justification : "";
            this.targetDeliveryDate = targetDeliveryDate;
            this.submittedById = submittedById;
            this.submittedAt = submittedAt;
            this.approvedById = approvedById;
            this.approvedAt = approvedAt;
            this.approvalNotes = approvalNotes != null ? approvalNotes : "";
            this.rejectedById = rejectedById;
            this.rejectedAt = rejectedAt;
            this.rejectionReason = rejectionReason != null ? rejectionReason : "";
            this.isActive = isActive;
            this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
            this.createdAt = createdAt != null ? createdAt : Instant.now();
        }
    }

    private void checkProcurementRole() {
        if (!ALLOWED_PROCUREMENT_ROLES.contains(userRole)) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers and unauthorized roles cannot manage requisitions");
        }
    }

    private void checkApprovalRole() {
        if (!APPROVAL_ROLES.contains(userRole)) {
            throw new SecurityException("ERR_APPROVAL_ROLE_REQUIRED: Only Branch Managers and Owners can approve or reject requisitions");
        }
    }

    public PurchaseRequisitionDto createRequisition(
            String department,
            String priority,
            BigDecimal allocatedBudget,
            String justification,
            LocalDate targetDeliveryDate,
            List<PurchaseRequisitionItemDto> items) {
        checkProcurementRole();

        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("ERR_EMPTY_REQUISITION_ITEMS: Requisition must contain at least one item");
        }

        BigDecimal total = BigDecimal.ZERO;
        for (PurchaseRequisitionItemDto itm : items) {
            if (itm.productId == null || itm.productId.trim().isEmpty()) {
                throw new IllegalArgumentException("ERR_MISSING_PRODUCT_ID: Product ID cannot be empty");
            }
            if (itm.requestedQuantity < 1) {
                throw new IllegalArgumentException("ERR_INVALID_QUANTITY: Requested quantity must be at least 1");
            }
            if (itm.estimatedUnitCost.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("ERR_INVALID_COST_PRICE: Estimated unit cost must be positive");
            }
            total = total.add(itm.estimatedTotalCost);
        }

        BigDecimal budget = allocatedBudget != null ? allocatedBudget.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        if (budget.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("ERR_INVALID_BUDGET: Allocated budget cannot be negative");
        }

        String reqId = "req-" + UUID.randomUUID().toString().substring(0, 12);
        PurchaseRequisitionDto req = new PurchaseRequisitionDto(
                reqId,
                tenantId,
                branchId,
                "Branch " + branchId,
                department != null ? department.toUpperCase() : "DISPENSARY",
                priority != null ? priority.toUpperCase() : "MEDIUM",
                "DRAFT",
                total.setScale(4, RoundingMode.HALF_UP),
                budget,
                justification != null ? justification.trim() : "",
                targetDeliveryDate,
                null, null, null, null, "", null, null, "",
                true,
                items,
                Instant.now()
        );

        requisitionStore.put(reqId, req);
        return req;
    }

    public PurchaseRequisitionDto submitRequisition(String requisitionId, boolean budgetOverride) {
        checkProcurementRole();

        PurchaseRequisitionDto existing = requisitionStore.get(requisitionId);
        if (existing == null) {
            throw new NoSuchElementException("ERR_REQUISITION_NOT_FOUND: Requisition " + requisitionId + " not found");
        }
        if (!existing.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant requisition submission forbidden");
        }
        if (!"DRAFT".equals(existing.status)) {
            throw new IllegalStateException("ERR_INVALID_STATE_TRANSITION: Requisition must be in DRAFT to submit");
        }
        if (existing.items.isEmpty()) {
            throw new IllegalArgumentException("ERR_EMPTY_REQUISITION_ITEMS: Cannot submit requisition without items");
        }

        // Budget guard
        if (existing.allocatedBudget.compareTo(BigDecimal.ZERO) > 0
                && existing.totalEstimatedCost.compareTo(existing.allocatedBudget) > 0) {
            if (!budgetOverride) {
                throw new IllegalArgumentException(
                        "ERR_BUDGET_EXCEEDED: Total estimated cost (" + existing.totalEstimatedCost
                                + ") exceeds allocated budget (" + existing.allocatedBudget + ")"
                );
            }
        }

        PurchaseRequisitionDto submitted = new PurchaseRequisitionDto(
                existing.requisitionId,
                existing.tenantId,
                existing.branchId,
                existing.branchName,
                existing.department,
                existing.priority,
                "SUBMITTED",
                existing.totalEstimatedCost,
                existing.allocatedBudget,
                existing.justification,
                existing.targetDeliveryDate,
                userId,
                Instant.now(),
                null, null, "", null, null, "",
                existing.isActive,
                existing.items,
                existing.createdAt
        );

        requisitionStore.put(requisitionId, submitted);
        return submitted;
    }

    public PurchaseRequisitionDto approveRequisition(String requisitionId, String approvalNotes) {
        checkApprovalRole();

        PurchaseRequisitionDto existing = requisitionStore.get(requisitionId);
        if (existing == null) {
            throw new NoSuchElementException("ERR_REQUISITION_NOT_FOUND: Requisition " + requisitionId + " not found");
        }
        if (!existing.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant requisition approval forbidden");
        }
        if (!"SUBMITTED".equals(existing.status)) {
            throw new IllegalStateException("ERR_INVALID_STATE_TRANSITION: Requisition must be SUBMITTED to approve");
        }

        PurchaseRequisitionDto approved = new PurchaseRequisitionDto(
                existing.requisitionId,
                existing.tenantId,
                existing.branchId,
                existing.branchName,
                existing.department,
                existing.priority,
                "APPROVED",
                existing.totalEstimatedCost,
                existing.allocatedBudget,
                existing.justification,
                existing.targetDeliveryDate,
                existing.submittedById,
                existing.submittedAt,
                userId,
                Instant.now(),
                approvalNotes != null ? approvalNotes.trim() : "",
                null, null, "",
                existing.isActive,
                existing.items,
                existing.createdAt
        );

        requisitionStore.put(requisitionId, approved);
        return approved;
    }

    public PurchaseRequisitionDto rejectRequisition(String requisitionId, String rejectionReason) {
        checkApprovalRole();

        PurchaseRequisitionDto existing = requisitionStore.get(requisitionId);
        if (existing == null) {
            throw new NoSuchElementException("ERR_REQUISITION_NOT_FOUND: Requisition " + requisitionId + " not found");
        }
        if (!existing.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant requisition rejection forbidden");
        }
        if (!"SUBMITTED".equals(existing.status)) {
            throw new IllegalStateException("ERR_INVALID_STATE_TRANSITION: Requisition must be SUBMITTED to reject");
        }
        if (rejectionReason == null || rejectionReason.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_REJECTION_REASON: rejection_reason is required");
        }

        PurchaseRequisitionDto rejected = new PurchaseRequisitionDto(
                existing.requisitionId,
                existing.tenantId,
                existing.branchId,
                existing.branchName,
                existing.department,
                existing.priority,
                "REJECTED",
                existing.totalEstimatedCost,
                existing.allocatedBudget,
                existing.justification,
                existing.targetDeliveryDate,
                existing.submittedById,
                existing.submittedAt,
                null, null, "",
                userId,
                Instant.now(),
                rejectionReason.trim(),
                existing.isActive,
                existing.items,
                existing.createdAt
        );

        requisitionStore.put(requisitionId, rejected);
        return rejected;
    }

    public PurchaseRequisitionDto cancelRequisition(String requisitionId) {
        checkProcurementRole();

        PurchaseRequisitionDto existing = requisitionStore.get(requisitionId);
        if (existing == null) {
            throw new NoSuchElementException("ERR_REQUISITION_NOT_FOUND: Requisition " + requisitionId + " not found");
        }
        if (!existing.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant requisition cancellation forbidden");
        }
        if ("APPROVED".equals(existing.status) || "CONVERTED_TO_PO".equals(existing.status)) {
            throw new IllegalStateException("ERR_CANNOT_CANCEL_APPROVED: Cannot cancel already approved requisition");
        }

        PurchaseRequisitionDto cancelled = new PurchaseRequisitionDto(
                existing.requisitionId,
                existing.tenantId,
                existing.branchId,
                existing.branchName,
                existing.department,
                existing.priority,
                "CANCELLED",
                existing.totalEstimatedCost,
                existing.allocatedBudget,
                existing.justification,
                existing.targetDeliveryDate,
                existing.submittedById,
                existing.submittedAt,
                existing.approvedById,
                existing.approvedAt,
                existing.approvalNotes,
                existing.rejectedById,
                existing.rejectedAt,
                existing.rejectionReason,
                existing.isActive,
                existing.items,
                existing.createdAt
        );

        requisitionStore.put(requisitionId, cancelled);
        return cancelled;
    }

    public PurchaseRequisitionDto getRequisition(String requisitionId) {
        PurchaseRequisitionDto existing = requisitionStore.get(requisitionId);
        if (existing == null) {
            throw new NoSuchElementException("ERR_REQUISITION_NOT_FOUND: Requisition " + requisitionId + " not found");
        }
        if (!existing.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant requisition access forbidden");
        }
        return existing;
    }

    public List<PurchaseRequisitionDto> listRequisitions(String department, String priority, String status) {
        List<PurchaseRequisitionDto> list = new ArrayList<>();
        for (PurchaseRequisitionDto r : requisitionStore.values()) {
            if (!r.tenantId.equals(tenantId)) {
                continue;
            }
            if (department != null && !r.department.equalsIgnoreCase(department)) {
                continue;
            }
            if (priority != null && !r.priority.equalsIgnoreCase(priority)) {
                continue;
            }
            if (status != null && !r.status.equalsIgnoreCase(status)) {
                continue;
            }
            list.add(r);
        }
        list.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return list;
    }
}

