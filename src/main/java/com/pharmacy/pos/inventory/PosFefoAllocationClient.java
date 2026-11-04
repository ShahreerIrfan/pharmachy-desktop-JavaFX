package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * PosFefoAllocationClient
 * Architecture Decision: ADR-0169 (P169).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Deterministic First-Expired, First-Out (FEFO) Inventory Allocation Engine for JavaFX Desktop POS.
 * Enforces hard expiry blocks, quarantine/recall exclusions, active reservation deduction,
 * and 4-tier deterministic tie-breaker sorting.
 */
public class PosFefoAllocationClient {

    public static final class BatchRecord implements Comparable<BatchRecord> {
        public final String batchId;
        public final String batchNumber;
        public final LocalDate expiryDate;
        public final LocalDate purchaseDate;
        public int totalQuantityBaseUnits;
        public int reservedQuantityBaseUnits;
        public final BigDecimal costPerUnit;
        public boolean isQuarantined;
        public boolean isRecalled;
        public boolean isActive;

        public BatchRecord(
                String batchId,
                String batchNumber,
                LocalDate expiryDate,
                LocalDate purchaseDate,
                int totalQuantityBaseUnits,
                int reservedQuantityBaseUnits,
                BigDecimal costPerUnit,
                boolean isQuarantined,
                boolean isRecalled,
                boolean isActive) {
            this.batchId = batchId != null ? batchId : "";
            this.batchNumber = batchNumber != null ? batchNumber : "";
            this.expiryDate = expiryDate;
            this.purchaseDate = purchaseDate != null ? purchaseDate : LocalDate.now();
            this.totalQuantityBaseUnits = Math.max(0, totalQuantityBaseUnits);
            this.reservedQuantityBaseUnits = Math.max(0, reservedQuantityBaseUnits);
            this.costPerUnit = costPerUnit != null ? costPerUnit : BigDecimal.ZERO;
            this.isQuarantined = isQuarantined;
            this.isRecalled = isRecalled;
            this.isActive = isActive;
        }

        public int getAvailableQuantity() {
            return Math.max(0, totalQuantityBaseUnits - reservedQuantityBaseUnits);
        }

        public boolean isDispensable(LocalDate asOfDate) {
            if (!isActive || isQuarantined || isRecalled) {
                return false;
            }
            if (expiryDate == null || !expiryDate.isAfter(asOfDate)) {
                return false; // Expired or expiring today
            }
            return getAvailableQuantity() > 0;
        }

        @Override
        public int compareTo(BatchRecord other) {
            if (other == null) return -1;
            // Tier 1: Expiry date ASC (earliest first)
            int c = this.expiryDate.compareTo(other.expiryDate);
            if (c != 0) return c;

            // Tier 2: Purchase date ASC (FIFO tie-breaker)
            c = this.purchaseDate.compareTo(other.purchaseDate);
            if (c != 0) return c;

            // Tier 3: Batch number ASC (lexicographical tie-breaker)
            c = this.batchNumber.compareTo(other.batchNumber);
            if (c != 0) return c;

            // Tier 4: Primary key batchId ASC
            return this.batchId.compareTo(other.batchId);
        }
    }

    public static final class BatchAllocation {
        public final String batchId;
        public final String batchNumber;
        public final LocalDate expiryDate;
        public final LocalDate purchaseDate;
        public final int allocatedUnits;
        public final BigDecimal unitCost;
        public final BigDecimal lotCogs;
        public final String reservationId;

        public BatchAllocation(
                String batchId,
                String batchNumber,
                LocalDate expiryDate,
                LocalDate purchaseDate,
                int allocatedUnits,
                BigDecimal unitCost,
                BigDecimal lotCogs,
                String reservationId) {
            this.batchId = batchId;
            this.batchNumber = batchNumber;
            this.expiryDate = expiryDate;
            this.purchaseDate = purchaseDate;
            this.allocatedUnits = allocatedUnits;
            this.unitCost = unitCost != null ? unitCost : BigDecimal.ZERO;
            this.lotCogs = lotCogs != null ? lotCogs : BigDecimal.ZERO;
            this.reservationId = reservationId;
        }
    }

    public static final class AllocationResult {
        public final boolean isSuccessful;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;
        public final String productId;
        public final int requestedUnits;
        public final int allocatedUnits;
        public final BigDecimal totalCogs;
        public final boolean isFullyAllocated;
        public final List<BatchAllocation> allocations;

        public AllocationResult(
                boolean isSuccessful,
                String errorCode,
                String messageEn,
                String messageBn,
                String productId,
                int requestedUnits,
                int allocatedUnits,
                BigDecimal totalCogs,
                boolean isFullyAllocated,
                List<BatchAllocation> allocations) {
            this.isSuccessful = isSuccessful;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
            this.productId = productId != null ? productId : "";
            this.requestedUnits = requestedUnits;
            this.allocatedUnits = allocatedUnits;
            this.totalCogs = totalCogs != null ? totalCogs : BigDecimal.ZERO;
            this.isFullyAllocated = isFullyAllocated;
            this.allocations = allocations != null ? allocations : Collections.emptyList();
        }

        public static AllocationResult success(
                String productId,
                int requestedUnits,
                BigDecimal totalCogs,
                List<BatchAllocation> allocations) {
            return new AllocationResult(
                    true,
                    null,
                    "FEFO allocation completed successfully.",
                    "FEFO ব্যাচ বরাদ্দ সফলভাবে সম্পন্ন হয়েছে।",
                    productId,
                    requestedUnits,
                    requestedUnits,
                    totalCogs,
                    true,
                    allocations);
        }

        public static AllocationResult failure(
                String errorCode,
                String messageEn,
                String messageBn,
                String productId,
                int requestedUnits,
                int allocatedUnits) {
            return new AllocationResult(
                    false,
                    errorCode,
                    messageEn,
                    messageBn,
                    productId,
                    requestedUnits,
                    allocatedUnits,
                    BigDecimal.ZERO,
                    false,
                    Collections.emptyList());
        }
    }

    /**
     * Executes deterministic FEFO allocation and optional concurrency reservation.
     */
    public static AllocationResult allocateFefo(
            String productId,
            List<BatchRecord> batches,
            int requestedUnits,
            LocalDate asOfDate,
            boolean reserve) {
        if (requestedUnits <= 0) {
            return AllocationResult.failure(
                    "ERR_INVALID_QUANTITY",
                    "Requested quantity must be a positive integer.",
                    "অনুরোধকৃত পরিমাণ অবশ্যই ধনাত্মক পূর্ণসংখ্যা হতে হবে।",
                    productId,
                    requestedUnits,
                    0);
        }

        LocalDate today = asOfDate != null ? asOfDate : LocalDate.now();

        // 1. Filter eligible candidate batches
        List<BatchRecord> candidates = new ArrayList<>();
        if (batches != null) {
            for (BatchRecord b : batches) {
                if (b.isDispensable(today)) {
                    candidates.add(b);
                }
            }
        }

        if (candidates.isEmpty()) {
            return AllocationResult.failure(
                    "ERR_NO_ELIGIBLE_BATCHES",
                    "No eligible FEFO batches available for product.",
                    "এই ওষুধের জন্য কোন যোগ্য FEFO ব্যাচ পাওয়া যায়নি।",
                    productId,
                    requestedUnits,
                    0);
        }

        // 2. Sort candidate batches using deterministic 4-tier cascade
        Collections.sort(candidates);

        int remainingNeeded = requestedUnits;
        List<BatchAllocation> allocations = new ArrayList<>();
        BigDecimal totalCogs = BigDecimal.ZERO;

        for (BatchRecord b : candidates) {
            int avail = b.getAvailableQuantity();
            if (avail <= 0) continue;

            int take = Math.min(avail, remainingNeeded);
            BigDecimal lotCogs = b.costPerUnit.multiply(BigDecimal.valueOf(take));
            totalCogs = totalCogs.add(lotCogs);

            String reservationId = null;
            if (reserve) {
                reservationId = "resv-" + UUID.randomUUID().toString().substring(0, 8);
                b.reservedQuantityBaseUnits += take;
            }

            allocations.add(new BatchAllocation(
                    b.batchId,
                    b.batchNumber,
                    b.expiryDate,
                    b.purchaseDate,
                    take,
                    b.costPerUnit,
                    lotCogs,
                    reservationId));

            remainingNeeded -= take;
            if (remainingNeeded == 0) break;
        }

        if (remainingNeeded > 0) {
            // Roll back reservations if partially allocated
            if (reserve) {
                for (BatchAllocation alloc : allocations) {
                    for (BatchRecord b : candidates) {
                        if (b.batchId.equals(alloc.batchId)) {
                            b.reservedQuantityBaseUnits = Math.max(0, b.reservedQuantityBaseUnits - alloc.allocatedUnits);
                            break;
                        }
                    }
                }
            }
            int availableTotal = requestedUnits - remainingNeeded;
            return AllocationResult.failure(
                    "ERR_INSUFFICIENT_STOCK",
                    "Insufficient dispensable stock: requested " + requestedUnits + ", available " + availableTotal + ".",
                    "অপর্যাপ্ত স্টক: অনুরোধকৃত " + requestedUnits + ", বর্তমান স্টক " + availableTotal + "।",
                    productId,
                    requestedUnits,
                    availableTotal);
        }

        return AllocationResult.success(productId, requestedUnits, totalCogs, allocations);
    }

    /**
     * Commits an active reservation upon successful checkout.
     */
    public static boolean commitReservation(BatchRecord batch, int reservedUnits) {
        if (batch == null || reservedUnits <= 0) return false;
        batch.totalQuantityBaseUnits = Math.max(0, batch.totalQuantityBaseUnits - reservedUnits);
        batch.reservedQuantityBaseUnits = Math.max(0, batch.reservedQuantityBaseUnits - reservedUnits);
        return true;
    }

    /**
     * Releases an active reservation upon checkout cancellation or cart timeout.
     */
    public static boolean releaseReservation(BatchRecord batch, int reservedUnits) {
        if (batch == null || reservedUnits <= 0) return false;
        batch.reservedQuantityBaseUnits = Math.max(0, batch.reservedQuantityBaseUnits - reservedUnits);
        return true;
    }
}

