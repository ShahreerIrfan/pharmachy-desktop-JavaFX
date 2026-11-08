package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/**
 * PosPurchaseReturnClient
 * Architecture Decision: ADR-0191 (P191).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 *
 * Desktop JavaFX client governing Supplier Purchase Returns & Debit Notes:
 * - Ties supplier returns directly to physical batches and cost lots.
 * - Guards against returning stock already dispensed or reserved.
 * - Decrements batch inventory upon posting.
 * - Issues AP debit notes reducing vendor payable balance in the supplier ledger.
 */
public class PosPurchaseReturnClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, PurchaseReturnDto> returnStore;
    private final Map<String, BatchStockDto> batchStore;
    private final Map<String, SupplierLedgerEntryDto> ledgerStore;

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "PROCUREMENT_OFFICER", "INVENTORY_CLERK"
    );

    public PosPurchaseReturnClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, PurchaseReturnDto> returnStore,
            Map<String, BatchStockDto> batchStore,
            Map<String, SupplierLedgerEntryDto> ledgerStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.returnStore = returnStore != null ? returnStore : new HashMap<>();
        this.batchStore = batchStore != null ? batchStore : new HashMap<>();
        this.ledgerStore = ledgerStore != null ? ledgerStore : new HashMap<>();
    }

    public static final class BatchStockDto {
        public final String batchId;
        public final String batchNumber;
        public final String productId;
        public int availableQuantity;
        public final BigDecimal costPerUnit;
        public String status;

        public BatchStockDto(String batchId, String batchNumber, String productId, int availableQuantity, BigDecimal costPerUnit, String status) {
            this.batchId = batchId;
            this.batchNumber = batchNumber;
            this.productId = productId;
            this.availableQuantity = availableQuantity;
            this.costPerUnit = costPerUnit;
            this.status = status;
        }
    }

    public static final class SupplierLedgerEntryDto {
        public final String postingId;
        public final String supplierId;
        public final String postingType;
        public final String referenceNumber;
        public final BigDecimal debitAmount;
        public final BigDecimal creditAmount;
        public final BigDecimal balanceAfter;
        public final Instant postedAt;

        public SupplierLedgerEntryDto(String postingId, String supplierId, String postingType, String referenceNumber,
                                      BigDecimal debitAmount, BigDecimal creditAmount, BigDecimal balanceAfter, Instant postedAt) {
            this.postingId = postingId;
            this.supplierId = supplierId;
            this.postingType = postingType;
            this.referenceNumber = referenceNumber;
            this.debitAmount = debitAmount;
            this.creditAmount = creditAmount;
            this.balanceAfter = balanceAfter;
            this.postedAt = postedAt;
        }
    }

    public static final class PurchaseReturnItemDto {
        public final String itemId;
        public final String productId;
        public final String productBrandName;
        public final String batchStockId;
        public final String costLotId;
        public final String batchNumber;
        public final int returnQuantity;
        public final BigDecimal unitCost;
        public final BigDecimal lineDebitAmount;
        public final String reason;
        public final String notes;

        public PurchaseReturnItemDto(String itemId, String productId, String productBrandName, String batchStockId,
                                     String costLotId, String batchNumber, int returnQuantity, BigDecimal unitCost,
                                     BigDecimal lineDebitAmount, String reason, String notes) {
            this.itemId = itemId;
            this.productId = productId;
            this.productBrandName = productBrandName;
            this.batchStockId = batchStockId;
            this.costLotId = costLotId;
            this.batchNumber = batchNumber;
            this.returnQuantity = returnQuantity;
            this.unitCost = unitCost != null ? unitCost : BigDecimal.ZERO;
            this.lineDebitAmount = lineDebitAmount != null ? lineDebitAmount : BigDecimal.ZERO;
            this.reason = reason;
            this.notes = notes;
        }
    }

    public static final class PurchaseReturnDto {
        public final String returnId;
        public final String returnNumber;
        public final String tenantId;
        public final String branchId;
        public final String supplierId;
        public final String supplierName;
        public final String goodsReceiptId;
        public final String purchaseOrderId;
        public String status; // DRAFT, POSTED_DEBIT_NOTE, CANCELLED
        public final String returnReason;
        public final String disposition;
        public String reasonNotes;
        public final int totalReturnQuantity;
        public final BigDecimal totalDebitAmount;
        public final String debitNoteReference;
        public final String vendorCreditNoteRef;
        public final String carrierPickupReference;
        public String debitLedgerEntryId;
        public Instant postedAt;
        public final String createdBy;
        public String approvedBy;
        public final List<PurchaseReturnItemDto> items;

        public PurchaseReturnDto(String returnId, String returnNumber, String tenantId, String branchId,
                                 String supplierId, String supplierName, String goodsReceiptId, String purchaseOrderId,
                                 String status, String returnReason, String disposition, String reasonNotes,
                                 int totalReturnQuantity, BigDecimal totalDebitAmount, String debitNoteReference,
                                 String vendorCreditNoteRef, String carrierPickupReference, String debitLedgerEntryId,
                                 Instant postedAt, String createdBy, String approvedBy, List<PurchaseReturnItemDto> items) {
            this.returnId = returnId;
            this.returnNumber = returnNumber;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.supplierId = supplierId;
            this.supplierName = supplierName;
            this.goodsReceiptId = goodsReceiptId;
            this.purchaseOrderId = purchaseOrderId;
            this.status = status;
            this.returnReason = returnReason;
            this.disposition = disposition;
            this.reasonNotes = reasonNotes;
            this.totalReturnQuantity = totalReturnQuantity;
            this.totalDebitAmount = totalDebitAmount;
            this.debitNoteReference = debitNoteReference;
            this.vendorCreditNoteRef = vendorCreditNoteRef;
            this.carrierPickupReference = carrierPickupReference;
            this.debitLedgerEntryId = debitLedgerEntryId;
            this.postedAt = postedAt;
            this.createdBy = createdBy;
            this.approvedBy = approvedBy;
            this.items = items != null ? items : new ArrayList<>();
        }
    }

    private void assertProcurementRole() {
        if ("CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Staff role " + userRole + " cannot perform supplier returns.");
        }
    }

    public PurchaseReturnDto createPurchaseReturn(
            String supplierId,
            String supplierName,
            String goodsReceiptId,
            String purchaseOrderId,
            String returnReason,
            String disposition,
            String reasonNotes,
            String debitNoteRef,
            List<PurchaseReturnItemDto> items,
            boolean autoPost) {
        assertProcurementRole();

        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("ERR_EMPTY_ITEMS: Purchase return must contain at least one item.");
        }

        int totalQty = 0;
        BigDecimal totalDebit = BigDecimal.ZERO;
        List<PurchaseReturnItemDto> validatedItems = new ArrayList<>();

        for (PurchaseReturnItemDto item : items) {
            if (item.returnQuantity <= 0) {
                throw new IllegalArgumentException("ERR_INVALID_QUANTITY: Return quantity must be positive.");
            }

            BatchStockDto batch = batchStore.get(item.batchStockId);
            if (batch != null && batch.availableQuantity < item.returnQuantity) {
                throw new IllegalArgumentException("ERR_INSUFFICIENT_STOCK_FOR_RETURN: Batch " + batch.batchNumber
                        + " only has " + batch.availableQuantity + " available, requested " + item.returnQuantity);
            }

            BigDecimal cost = item.unitCost.compareTo(BigDecimal.ZERO) > 0 ? item.unitCost :
                    (batch != null ? batch.costPerUnit : BigDecimal.ONE);
            BigDecimal lineDebit = cost.multiply(BigDecimal.valueOf(item.returnQuantity)).setScale(4, RoundingMode.HALF_UP);

            totalQty += item.returnQuantity;
            totalDebit = totalDebit.add(lineDebit);

            validatedItems.add(new PurchaseReturnItemDto(
                    item.itemId != null ? item.itemId : "pri-" + UUID.randomUUID().toString().substring(0, 8),
                    item.productId,
                    item.productBrandName,
                    item.batchStockId,
                    item.costLotId,
                    batch != null ? batch.batchNumber : item.batchNumber,
                    item.returnQuantity,
                    cost,
                    lineDebit,
                    item.reason != null ? item.reason : returnReason,
                    item.notes
            ));
        }

        String retId = "pret-" + UUID.randomUUID().toString().substring(0, 8);
        String retNum = "PR-" + System.currentTimeMillis();
        String debitRef = debitNoteRef != null ? debitNoteRef : "DN-" + System.currentTimeMillis();

        PurchaseReturnDto pret = new PurchaseReturnDto(
                retId,
                retNum,
                tenantId,
                branchId,
                supplierId,
                supplierName,
                goodsReceiptId,
                purchaseOrderId,
                "DRAFT",
                returnReason != null ? returnReason : "DAMAGED_DELIVERY",
                disposition != null ? disposition : "RETURNED_TO_VENDOR",
                reasonNotes,
                totalQty,
                totalDebit,
                debitRef,
                "",
                "",
                null,
                null,
                userId,
                null,
                validatedItems
        );

        returnStore.put(retId, pret);

        if (autoPost) {
            return postPurchaseReturn(retId);
        }

        return pret;
    }

    public PurchaseReturnDto postPurchaseReturn(String returnId) {
        assertProcurementRole();

        PurchaseReturnDto pret = returnStore.get(returnId);
        if (pret == null) {
            throw new NoSuchElementException("ERR_RETURN_NOT_FOUND: Return " + returnId + " not found.");
        }

        if ("POSTED_DEBIT_NOTE".equals(pret.status)) {
            throw new IllegalStateException("ERR_ALREADY_POSTED: Return already posted as debit note.");
        }

        if ("CANCELLED".equals(pret.status)) {
            throw new IllegalStateException("ERR_RETURN_CANCELLED: Cannot post a cancelled purchase return.");
        }

        // 1. Decrement batch stock
        for (PurchaseReturnItemDto item : pret.items) {
            BatchStockDto batch = batchStore.get(item.batchStockId);
            if (batch != null) {
                if (batch.availableQuantity < item.returnQuantity) {
                    throw new IllegalArgumentException("ERR_INSUFFICIENT_STOCK_FOR_RETURN: Batch " + batch.batchNumber
                            + " has only " + batch.availableQuantity + " units remaining.");
                }
                batch.availableQuantity -= item.returnQuantity;
                if (batch.availableQuantity <= 0) {
                    batch.availableQuantity = 0;
                    batch.status = "DEPLETED";
                }
            }
        }

        // 2. Post AP Debit Note
        BigDecimal prevBalance = BigDecimal.ZERO;
        for (SupplierLedgerEntryDto entry : ledgerStore.values()) {
            if (pret.supplierId.equals(entry.supplierId)) {
                prevBalance = entry.balanceAfter;
            }
        }

        BigDecimal newBalance = prevBalance.subtract(pret.totalDebitAmount);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            newBalance = BigDecimal.ZERO;
        }

        String postingId = "sup-post-" + UUID.randomUUID().toString().substring(0, 8);
        SupplierLedgerEntryDto ledgerEntry = new SupplierLedgerEntryDto(
                postingId,
                pret.supplierId,
                "RETURN_DEBIT_NOTE",
                pret.debitNoteReference,
                pret.totalDebitAmount,
                BigDecimal.ZERO,
                newBalance,
                Instant.now()
        );
        ledgerStore.put(postingId, ledgerEntry);

        pret.status = "POSTED_DEBIT_NOTE";
        pret.debitLedgerEntryId = postingId;
        pret.postedAt = Instant.now();
        pret.approvedBy = userId;

        return pret;
    }

    public PurchaseReturnDto cancelPurchaseReturn(String returnId, String reason) {
        assertProcurementRole();

        PurchaseReturnDto pret = returnStore.get(returnId);
        if (pret == null) {
            throw new NoSuchElementException("ERR_RETURN_NOT_FOUND: Return " + returnId + " not found.");
        }

        if (!"DRAFT".equals(pret.status)) {
            throw new IllegalStateException("ERR_CANNOT_CANCEL_POSTED_RETURN: Cannot cancel return in status " + pret.status);
        }

        pret.status = "CANCELLED";
        pret.reasonNotes = (pret.reasonNotes != null ? pret.reasonNotes : "") + " | Cancelled: " + reason;
        return pret;
    }

    public PurchaseReturnDto getPurchaseReturn(String returnId) {
        PurchaseReturnDto pret = returnStore.get(returnId);
        if (pret == null) {
            throw new NoSuchElementException("ERR_RETURN_NOT_FOUND: Return " + returnId + " not found.");
        }
        return pret;
    }

    public List<PurchaseReturnDto> listPurchaseReturns(String supplierId, String statusFilter) {
        List<PurchaseReturnDto> results = new ArrayList<>();
        for (PurchaseReturnDto r : returnStore.values()) {
            if (supplierId != null && !supplierId.isEmpty() && !supplierId.equals(r.supplierId)) {
                continue;
            }
            if (statusFilter != null && !statusFilter.isEmpty() && !statusFilter.equalsIgnoreCase(r.status)) {
                continue;
            }
            results.add(r);
        }
        return results;
    }
}

