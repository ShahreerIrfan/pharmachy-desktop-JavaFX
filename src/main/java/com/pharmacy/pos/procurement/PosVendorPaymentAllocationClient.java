package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/**
 * PosVendorPaymentAllocationClient
 * Architecture Decision: ADR-0273 (P273).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * JavaFX Desktop POS client governing vendor payment disbursements, itemized invoice allocations,
 * unapplied advance balance tracking, and role-based authorization controls.
 */
public class PosVendorPaymentAllocationClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final Map<String, InvoiceRecord> invoiceStore;
    private final Map<String, PaymentRecord> paymentStore;
    private final Set<String> idempotencyKeys;

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "STAFF_PHARMACIST",
            "INVENTORY_CLERK", "FINANCE_OFFICER", "AUDITOR", "OWNER_ADMIN", "ADMIN", "PROCUREMENT_OFFICER"
    );

    public static final Set<String> MANAGE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER",
            "INVENTORY_CLERK", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN", "PROCUREMENT_OFFICER"
    );

    public PosVendorPaymentAllocationClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.invoiceStore = new HashMap<>();
        this.paymentStore = new HashMap<>();
        this.idempotencyKeys = new HashSet<>();
    }

    public boolean canView() {
        return VIEW_ROLES.contains(this.userRole);
    }

    public boolean canManage() {
        return MANAGE_ROLES.contains(this.userRole);
    }

    public void registerInvoice(String invoiceId, String invoiceNumber, String supplierId, BigDecimal totalAmount) {
        invoiceStore.put(invoiceId, new InvoiceRecord(invoiceId, invoiceNumber, supplierId, totalAmount));
    }

    public PaymentRecord disburseAndAllocate(
            String supplierId,
            BigDecimal totalAmount,
            String paymentMethod,
            String referenceNumber,
            String notes,
            List<AllocationRequest> allocations,
            String idempotencyKey) {

        if (!canManage()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " is not permitted to disburse vendor payments.");
        }

        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty() && idempotencyKeys.contains(idempotencyKey)) {
            for (PaymentRecord pr : paymentStore.values()) {
                if (idempotencyKey.equals(pr.idempotencyKey)) {
                    return pr;
                }
            }
        }

        if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("ERR_INVALID_PAYMENT_AMOUNT: Total payment amount must be greater than zero.");
        }

        BigDecimal sumAllocated = BigDecimal.ZERO;
        List<AllocationRecord> allocationRecords = new ArrayList<>();

        if (allocations != null) {
            for (AllocationRequest req : allocations) {
                InvoiceRecord inv = invoiceStore.get(req.invoiceId);
                if (inv == null) {
                    throw new NoSuchElementException("ERR_INVOICE_NOT_FOUND: Invoice " + req.invoiceId + " not found.");
                }
                if (!inv.supplierId.equals(supplierId)) {
                    throw new IllegalArgumentException("ERR_INVOICE_SUPPLIER_MISMATCH: Invoice " + req.invoiceId + " does not belong to supplier " + supplierId);
                }
                if (req.amount == null || req.amount.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("ERR_INVALID_ALLOCATION_AMOUNT: Allocation amount must be positive.");
                }

                BigDecimal currentUnpaid = inv.getUnpaidBalance();
                if (req.amount.compareTo(currentUnpaid) > 0) {
                    throw new IllegalArgumentException("ERR_ALLOCATION_EXCEEDS_INVOICE_BALANCE: Allocation " + req.amount + " exceeds unpaid " + currentUnpaid);
                }

                BigDecimal newUnpaid = currentUnpaid.subtract(req.amount).setScale(4, RoundingMode.HALF_UP);
                inv.allocatedTotal = inv.allocatedTotal.add(req.amount).setScale(4, RoundingMode.HALF_UP);

                allocationRecords.add(new AllocationRecord(
                        "vpalloc-" + UUID.randomUUID().toString().substring(0, 8),
                        req.invoiceId,
                        inv.invoiceNumber,
                        req.amount.setScale(4, RoundingMode.HALF_UP),
                        currentUnpaid,
                        newUnpaid,
                        req.notes != null ? req.notes : ""
                ));

                sumAllocated = sumAllocated.add(req.amount);
            }
        }

        if (sumAllocated.compareTo(totalAmount) > 0) {
            throw new IllegalArgumentException("ERR_TOTAL_ALLOCATION_EXCEEDS_PAYMENT: Allocations " + sumAllocated + " exceed payment total " + totalAmount);
        }

        BigDecimal unappliedAdvance = totalAmount.subtract(sumAllocated).setScale(4, RoundingMode.HALF_UP);
        String status = "POSTED";
        if (sumAllocated.compareTo(BigDecimal.ZERO) > 0) {
            status = sumAllocated.compareTo(totalAmount) == 0 ? "FULLY_ALLOCATED" : "PARTIALLY_ALLOCATED";
        }

        String paymentId = "vpay-" + UUID.randomUUID().toString().substring(0, 8);
        String paymentNumber = "VPAY-" + System.currentTimeMillis();

        PaymentRecord payment = new PaymentRecord(
                paymentId,
                paymentNumber,
                tenantId,
                branchId,
                supplierId,
                totalAmount.setScale(4, RoundingMode.HALF_UP),
                sumAllocated.setScale(4, RoundingMode.HALF_UP),
                unappliedAdvance,
                status,
                paymentMethod != null ? paymentMethod : "BANK_TRANSFER",
                referenceNumber != null ? referenceNumber : "",
                notes != null ? notes : "",
                idempotencyKey != null ? idempotencyKey : "",
                allocationRecords,
                Instant.now().toString()
        );

        paymentStore.put(paymentId, payment);
        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            idempotencyKeys.add(idempotencyKey);
        }

        return payment;
    }

    public static class AllocationRequest {
        public final String invoiceId;
        public final BigDecimal amount;
        public final String notes;

        public AllocationRequest(String invoiceId, BigDecimal amount, String notes) {
            this.invoiceId = invoiceId;
            this.amount = amount;
            this.notes = notes;
        }
    }

    public static class InvoiceRecord {
        public final String invoiceId;
        public final String invoiceNumber;
        public final String supplierId;
        public final BigDecimal totalAmount;
        public BigDecimal allocatedTotal;

        public InvoiceRecord(String invoiceId, String invoiceNumber, String supplierId, BigDecimal totalAmount) {
            this.invoiceId = invoiceId;
            this.invoiceNumber = invoiceNumber;
            this.supplierId = supplierId;
            this.totalAmount = totalAmount.setScale(4, RoundingMode.HALF_UP);
            this.allocatedTotal = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        public BigDecimal getUnpaidBalance() {
            BigDecimal unp = totalAmount.subtract(allocatedTotal);
            return unp.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP) : unp.setScale(4, RoundingMode.HALF_UP);
        }

        public String getSettlementStatus() {
            if (allocatedTotal.compareTo(BigDecimal.ZERO) == 0) return "UNPAID";
            if (getUnpaidBalance().compareTo(BigDecimal.ZERO) == 0) return "SETTLED";
            return "PARTIALLY_PAID";
        }
    }

    public static class AllocationRecord {
        public final String allocationId;
        public final String invoiceId;
        public final String invoiceNumber;
        public final BigDecimal allocatedAmount;
        public final BigDecimal previousUnpaidAmount;
        public final BigDecimal remainingUnpaidAmount;
        public final String notes;

        public AllocationRecord(String allocationId, String invoiceId, String invoiceNumber, BigDecimal allocatedAmount,
                                BigDecimal previousUnpaidAmount, BigDecimal remainingUnpaidAmount, String notes) {
            this.allocationId = allocationId;
            this.invoiceId = invoiceId;
            this.invoiceNumber = invoiceNumber;
            this.allocatedAmount = allocatedAmount;
            this.previousUnpaidAmount = previousUnpaidAmount;
            this.remainingUnpaidAmount = remainingUnpaidAmount;
            this.notes = notes;
        }
    }

    public static class PaymentRecord {
        public final String paymentId;
        public final String paymentNumber;
        public final String tenantId;
        public final String branchId;
        public final String supplierId;
        public final BigDecimal totalAmount;
        public final BigDecimal allocatedAmount;
        public final BigDecimal unallocatedAmount;
        public final String status;
        public final String paymentMethod;
        public final String referenceNumber;
        public final String notes;
        public final String idempotencyKey;
        public final List<AllocationRecord> allocations;
        public final String createdAt;

        public PaymentRecord(String paymentId, String paymentNumber, String tenantId, String branchId, String supplierId,
                             BigDecimal totalAmount, BigDecimal allocatedAmount, BigDecimal unallocatedAmount,
                             String status, String paymentMethod, String referenceNumber, String notes,
                             String idempotencyKey, List<AllocationRecord> allocations, String createdAt) {
            this.paymentId = paymentId;
            this.paymentNumber = paymentNumber;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.supplierId = supplierId;
            this.totalAmount = totalAmount;
            this.allocatedAmount = allocatedAmount;
            this.unallocatedAmount = unallocatedAmount;
            this.status = status;
            this.paymentMethod = paymentMethod;
            this.referenceNumber = referenceNumber;
            this.notes = notes;
            this.idempotencyKey = idempotencyKey;
            this.allocations = allocations;
            this.createdAt = createdAt;
        }
    }
}
