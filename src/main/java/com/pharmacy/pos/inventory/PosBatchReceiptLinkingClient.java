package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * PosBatchReceiptLinkingClient
 * Architecture Decision: ADR-0168 (P168).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Client-side Goods Received Note (GRN) to verified batch linking engine for JavaFX Desktop POS.
 * Guarantees each receipt line binds to one or more verified manufacturer batches without
 * duplicate stock increments.
 */
public class PosBatchReceiptLinkingClient {

    public static final class BatchAllocation {
        public final String linkId;
        public final String batchId;
        public final String batchNumber;
        public int quantityBaseUnits;
        public final BigDecimal unitCost;
        public boolean isPosted;

        public BatchAllocation(
                String linkId,
                String batchId,
                String batchNumber,
                int quantityBaseUnits,
                BigDecimal unitCost,
                boolean isPosted) {
            this.linkId = linkId;
            this.batchId = batchId;
            this.batchNumber = batchNumber != null ? batchNumber : "";
            this.quantityBaseUnits = Math.max(1, quantityBaseUnits);
            this.unitCost = unitCost != null ? unitCost : BigDecimal.ZERO;
            this.isPosted = isPosted;
        }
    }

    public static final class GrnLine {
        public final String lineId;
        public final String productId;
        public final String productBrandName;
        public final int orderedQuantityBaseUnits;
        public final int receivedQuantityBaseUnits;
        public final BigDecimal unitCost;
        public boolean isFullyAllocated;
        public final List<BatchAllocation> allocations;

        public GrnLine(
                String lineId,
                String productId,
                String productBrandName,
                int orderedQuantityBaseUnits,
                int receivedQuantityBaseUnits,
                BigDecimal unitCost,
                boolean isFullyAllocated,
                List<BatchAllocation> allocations) {
            this.lineId = lineId;
            this.productId = productId;
            this.productBrandName = productBrandName != null ? productBrandName : "";
            this.orderedQuantityBaseUnits = Math.max(0, orderedQuantityBaseUnits);
            this.receivedQuantityBaseUnits = Math.max(1, receivedQuantityBaseUnits);
            this.unitCost = unitCost != null ? unitCost : BigDecimal.ZERO;
            this.isFullyAllocated = isFullyAllocated;
            this.allocations = allocations != null ? allocations : new ArrayList<>();
        }

        public int getAllocatedQuantity() {
            int sum = 0;
            for (BatchAllocation a : allocations) {
                sum += a.quantityBaseUnits;
            }
            return sum;
        }

        public boolean canAddAllocation(int quantity) {
            return getAllocatedQuantity() + quantity <= receivedQuantityBaseUnits;
        }
    }

    public static final class GrnNote {
        public final String grnId;
        public final String grnNumber;
        public final String tenantId;
        public final String branchId;
        public final String supplierId;
        public final String supplierName;
        public final String purchaseOrderNumber;
        public final String supplierInvoiceNumber;
        public final LocalDate receiptDate;
        public String status;
        public final List<GrnLine> lines;

        public GrnNote(
                String grnId,
                String grnNumber,
                String tenantId,
                String branchId,
                String supplierId,
                String supplierName,
                String purchaseOrderNumber,
                String supplierInvoiceNumber,
                LocalDate receiptDate,
                String status,
                List<GrnLine> lines) {
            this.grnId = grnId;
            this.grnNumber = grnNumber != null ? grnNumber : "";
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.supplierId = supplierId;
            this.supplierName = supplierName != null ? supplierName : "";
            this.purchaseOrderNumber = purchaseOrderNumber != null ? purchaseOrderNumber : "";
            this.supplierInvoiceNumber = supplierInvoiceNumber != null ? supplierInvoiceNumber : "";
            this.receiptDate = receiptDate;
            this.status = status != null ? status.toUpperCase() : "DRAFT";
            this.lines = lines != null ? lines : new ArrayList<>();
        }

        public boolean isPosted() {
            return "POSTED".equals(status);
        }

        public boolean isAllLinesFullyAllocated() {
            if (lines.isEmpty()) return false;
            for (GrnLine line : lines) {
                if (line.getAllocatedQuantity() != line.receivedQuantityBaseUnits) {
                    return false;
                }
            }
            return true;
        }
    }

    public static final class PostingResult {
        public final boolean isSuccessful;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;
        public final int postedAllocationsCount;
        public final int totalQuantityIncremented;

        public PostingResult(
                boolean isSuccessful,
                String errorCode,
                String messageEn,
                String messageBn,
                int postedAllocationsCount,
                int totalQuantityIncremented) {
            this.isSuccessful = isSuccessful;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
            this.postedAllocationsCount = postedAllocationsCount;
            this.totalQuantityIncremented = totalQuantityIncremented;
        }

        public static PostingResult success(int count, int totalQty) {
            return new PostingResult(
                    true,
                    null,
                    "GRN posted to inventory successfully. Stock incremented exactly once.",
                    "জিআরএন সফলভাবে ইনভেন্টরিতে পোস্ট করা হয়েছে। স্টক নির্ভুলভাবে বৃদ্ধি পেয়েছে।",
                    count,
                    totalQty);
        }

        public static PostingResult failure(String errorCode, String messageEn, String messageBn) {
            return new PostingResult(false, errorCode, messageEn, messageBn, 0, 0);
        }
    }

    public static final class ReversalResult {
        public final boolean isSuccessful;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;
        public final int totalQuantityDecremented;

        public ReversalResult(
                boolean isSuccessful,
                String errorCode,
                String messageEn,
                String messageBn,
                int totalQuantityDecremented) {
            this.isSuccessful = isSuccessful;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
            this.totalQuantityDecremented = totalQuantityDecremented;
        }

        public static ReversalResult success(int totalQty) {
            return new ReversalResult(
                    true,
                    null,
                    "GRN reversed successfully. Stock deducted cleanly.",
                    "জিআরএন সফলভাবে রিভার্স করা হয়েছে এবং স্টক সমন্বয় করা হয়েছে।",
                    totalQty);
        }

        public static ReversalResult failure(String errorCode, String messageEn, String messageBn) {
            return new ReversalResult(false, errorCode, messageEn, messageBn, 0);
        }
    }

    /**
     * Binds a batch allocation to a line, verifying capacity limits.
     */
    public static boolean addBatchAllocationToLine(GrnLine line, BatchAllocation newAllocation) {
        if (line == null || newAllocation == null) return false;
        if (!line.canAddAllocation(newAllocation.quantityBaseUnits)) {
            return false;
        }

        // Check if batch is already allocated on this line
        for (BatchAllocation existing : line.allocations) {
            if (existing.batchId.equals(newAllocation.batchId)) {
                existing.quantityBaseUnits += newAllocation.quantityBaseUnits;
                line.isFullyAllocated = line.getAllocatedQuantity() == line.receivedQuantityBaseUnits;
                return true;
            }
        }

        line.allocations.add(newAllocation);
        line.isFullyAllocated = line.getAllocatedQuantity() == line.receivedQuantityBaseUnits;
        return true;
    }

    /**
     * Idempotently posts the GRN, incrementing batch stock exactly once.
     * Re-posting an already posted GRN is strictly prevented.
     */
    public static PostingResult postGrn(GrnNote grn, Map<String, Integer> batchStockMap) {
        if (grn == null) {
            return PostingResult.failure("ERR_GRN_NULL", "GRN record cannot be null.", "জিআরএন রেকর্ড নাল হতে পারে না।");
        }

        // Anti-duplicate posting guardrail
        if (grn.isPosted()) {
            return PostingResult.failure(
                    "ERR_ALREADY_POSTED",
                    "GRN is already posted. Duplicate inventory increments are strictly prohibited.",
                    "জিআরএন ইতোমধ্যে পোস্ট করা হয়েছে। ডুপ্লিকেট স্টক বৃদ্ধি সম্পূর্ণ নিষিদ্ধ।");
        }

        if ("CANCELLED".equals(grn.status) || "REVERSED".equals(grn.status)) {
            return PostingResult.failure(
                    "ERR_GRN_INACTIVE",
                    "Cannot post " + grn.status + " GRN.",
                    "বাতিল বা রিভার্স করা জিআরএন পোস্ট করা অসম্ভব।");
        }

        if (!grn.isAllLinesFullyAllocated()) {
            return PostingResult.failure(
                    "ERR_ALLOCATION_MISMATCH",
                    "Cannot post GRN: one or more lines are not fully allocated to verified batches.",
                    "জিআরএন পোস্ট করা যাচ্ছে না: এক বা একাধিক লাইনে ব্যাচ বরাদ্দ অসম্পূর্ণ।");
        }

        int count = 0;
        int totalQty = 0;

        for (GrnLine line : grn.lines) {
            for (BatchAllocation alloc : line.allocations) {
                if (alloc.isPosted) {
                    continue; // Skip already posted allocation guard
                }

                // Increment batch stock
                if (batchStockMap != null) {
                    int current = batchStockMap.getOrDefault(alloc.batchId, 0);
                    batchStockMap.put(alloc.batchId, current + alloc.quantityBaseUnits);
                }

                alloc.isPosted = true;
                count++;
                totalQty += alloc.quantityBaseUnits;
            }
        }

        grn.status = "POSTED";
        return PostingResult.success(count, totalQty);
    }

    /**
     * Reverses a posted GRN, deducting stock if it hasn't already been consumed.
     */
    public static ReversalResult reverseGrn(
            GrnNote grn,
            Map<String, Integer> batchStockMap,
            Map<String, Integer> consumedStockMap) {
        if (grn == null) {
            return ReversalResult.failure("ERR_GRN_NULL", "GRN record is null.", "জিআরএন নাল।");
        }

        if (!grn.isPosted()) {
            grn.status = "CANCELLED";
            return ReversalResult.success(0);
        }

        // Check whether stock was consumed
        for (GrnLine line : grn.lines) {
            for (BatchAllocation alloc : line.allocations) {
                if (alloc.isPosted && batchStockMap != null) {
                    int currentStock = batchStockMap.getOrDefault(alloc.batchId, 0);
                    if (currentStock < alloc.quantityBaseUnits) {
                        return ReversalResult.failure(
                                "ERR_CANNOT_REVERSE_CONSUMED_STOCK",
                                "Cannot reverse GRN; stock for batch " + alloc.batchNumber + " has already been consumed.",
                                "জিআরএন রিভার্স করা যাচ্ছে না; ব্যাচ " + alloc.batchNumber + "-এর স্টক ইতোমধ্যে বিক্রয় হয়েছে।");
                    }
                }
            }
        }

        int totalDecremented = 0;
        for (GrnLine line : grn.lines) {
            for (BatchAllocation alloc : line.allocations) {
                if (alloc.isPosted) {
                    if (batchStockMap != null) {
                        int currentStock = batchStockMap.getOrDefault(alloc.batchId, 0);
                        batchStockMap.put(alloc.batchId, Math.max(0, currentStock - alloc.quantityBaseUnits));
                    }
                    alloc.isPosted = false;
                    totalDecremented += alloc.quantityBaseUnits;
                }
            }
        }

        grn.status = "REVERSED";
        return ReversalResult.success(totalDecremented);
    }
}

