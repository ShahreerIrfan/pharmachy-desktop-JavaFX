package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * PosPurchaseCostLotClient
 * Architecture Decision: ADR-0167 (P167).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Client-side purchase cost lot allocation engine for JavaFX Desktop POS.
 * Separates acquisition cost from physical batch identity so identical batches
 * received at different prices retain distinct COGS and exact valuation.
 */
public class PosPurchaseCostLotClient {

    public static final class CostLotRecord {
        public final String lotId;
        public final String tenantId;
        public final String branchId;
        public final String productId;
        public final String productBrandName;
        public final String batchId;
        public final String batchNumber;
        public final String supplierId;
        public final String supplierName;
        public final String purchaseOrderNumber;
        public final String grnNumber;
        public final LocalDate purchaseDate;
        public final BigDecimal unitCost;
        public final int initialQuantity;
        public int remainingQuantity;
        public String status;
        public boolean isActive;

        public CostLotRecord(
                String lotId,
                String tenantId,
                String branchId,
                String productId,
                String productBrandName,
                String batchId,
                String batchNumber,
                String supplierId,
                String supplierName,
                String purchaseOrderNumber,
                String grnNumber,
                LocalDate purchaseDate,
                BigDecimal unitCost,
                int initialQuantity,
                int remainingQuantity,
                String status,
                boolean isActive) {
            this.lotId = lotId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.productId = productId;
            this.productBrandName = productBrandName != null ? productBrandName : "";
            this.batchId = batchId;
            this.batchNumber = batchNumber != null ? batchNumber : "";
            this.supplierId = supplierId;
            this.supplierName = supplierName != null ? supplierName : "";
            this.purchaseOrderNumber = purchaseOrderNumber != null ? purchaseOrderNumber : "";
            this.grnNumber = grnNumber != null ? grnNumber : "";
            this.purchaseDate = purchaseDate;
            this.unitCost = unitCost != null ? unitCost : BigDecimal.ZERO;
            this.initialQuantity = Math.max(1, initialQuantity);
            this.remainingQuantity = Math.max(0, remainingQuantity);
            this.status = status != null ? status.toUpperCase() : "ACTIVE";
            this.isActive = isActive;
        }

        public boolean isAvailable() {
            return isActive && "ACTIVE".equals(status) && remainingQuantity > 0;
        }
    }

    public static final class LotAllocation {
        public final String lotId;
        public final String batchId;
        public final String batchNumber;
        public final int unitsAllocated;
        public final BigDecimal unitCost;
        public final BigDecimal lotCogs;

        public LotAllocation(
                String lotId,
                String batchId,
                String batchNumber,
                int unitsAllocated,
                BigDecimal unitCost,
                BigDecimal lotCogs) {
            this.lotId = lotId;
            this.batchId = batchId;
            this.batchNumber = batchNumber;
            this.unitsAllocated = unitsAllocated;
            this.unitCost = unitCost;
            this.lotCogs = lotCogs;
        }
    }

    public static final class CogsAllocationResult {
        public final boolean isSuccessful;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;
        public final int totalUnits;
        public final BigDecimal totalCogs;
        public final BigDecimal effectiveUnitCogs;
        public final List<LotAllocation> allocations;

        public CogsAllocationResult(
                boolean isSuccessful,
                String errorCode,
                String messageEn,
                String messageBn,
                int totalUnits,
                BigDecimal totalCogs,
                BigDecimal effectiveUnitCogs,
                List<LotAllocation> allocations) {
            this.isSuccessful = isSuccessful;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
            this.totalUnits = totalUnits;
            this.totalCogs = totalCogs;
            this.effectiveUnitCogs = effectiveUnitCogs;
            this.allocations = allocations != null ? allocations : Collections.emptyList();
        }

        public static CogsAllocationResult success(
                int totalUnits,
                BigDecimal totalCogs,
                BigDecimal effectiveUnitCogs,
                List<LotAllocation> allocations) {
            return new CogsAllocationResult(
                    true,
                    null,
                    "COGS allocated successfully across purchase lots.",
                    "ক্রয় লটসমূহের মধ্যে সিওজিএস (COGS) সফলভাবে বরাদ্দ করা হয়েছে।",
                    totalUnits,
                    totalCogs,
                    effectiveUnitCogs,
                    allocations);
        }

        public static CogsAllocationResult failure(String errorCode, String messageEn, String messageBn) {
            return new CogsAllocationResult(
                    false,
                    errorCode,
                    messageEn,
                    messageBn,
                    0,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    Collections.emptyList());
        }
    }

    public static final class LotReversalResult {
        public final boolean isSuccessful;
        public final int totalRestoredUnits;
        public final BigDecimal totalRestoredCogs;
        public final String reason;

        public LotReversalResult(
                boolean isSuccessful,
                int totalRestoredUnits,
                BigDecimal totalRestoredCogs,
                String reason) {
            this.isSuccessful = isSuccessful;
            this.totalRestoredUnits = totalRestoredUnits;
            this.totalRestoredCogs = totalRestoredCogs;
            this.reason = reason;
        }
    }

    /**
     * Allocates requested quantity across active purchase cost lots in FIFO order.
     */
    public static CogsAllocationResult allocateCogs(List<CostLotRecord> candidateLots, int requestedUnits) {
        if (requestedUnits <= 0) {
            return CogsAllocationResult.failure(
                    "ERR_INVALID_QUANTITY",
                    "Requested quantity must be positive.",
                    "অনুরোধকৃত পরিমাণ অবশ্যই শূন্যের চেয়ে বেশি হতে হবে।");
        }

        if (candidateLots == null || candidateLots.isEmpty()) {
            return CogsAllocationResult.failure(
                    "ERR_NO_COST_LOTS",
                    "No purchase cost lots found for allocation.",
                    "বরাদ্দের জন্য কোনো ক্রয় খরচ লট পাওয়া যায়নি।");
        }

        // Filter and sort active lots by FIFO (purchaseDate ascending, then lotId)
        List<CostLotRecord> activeLots = new ArrayList<>();
        int availableTotal = 0;
        for (CostLotRecord lot : candidateLots) {
            if (lot.isAvailable()) {
                activeLots.add(lot);
                availableTotal += lot.remainingQuantity;
            }
        }

        if (availableTotal < requestedUnits) {
            return CogsAllocationResult.failure(
                    "ERR_INSUFFICIENT_COST_LOT_STOCK",
                    "Insufficient purchase cost lot stock. Requested " + requestedUnits + ", available " + availableTotal + ".",
                    "ক্রয় লটে পর্যাপ্ত স্টক নেই। চাওয়া হয়েছে " + requestedUnits + ", অবশিষ্ট " + availableTotal + "।");
        }

        activeLots.sort(Comparator.comparing((CostLotRecord l) -> l.purchaseDate)
                .thenComparing(l -> l.lotId));

        int needed = requestedUnits;
        BigDecimal totalCogs = BigDecimal.ZERO;
        List<LotAllocation> allocations = new ArrayList<>();

        for (CostLotRecord lot : activeLots) {
            int take = Math.min(lot.remainingQuantity, needed);
            BigDecimal lotCogs = lot.unitCost.multiply(BigDecimal.valueOf(take));
            totalCogs = totalCogs.add(lotCogs);

            lot.remainingQuantity -= take;
            if (lot.remainingQuantity == 0) {
                lot.status = "DEPLETED";
            }

            allocations.add(new LotAllocation(
                    lot.lotId,
                    lot.batchId,
                    lot.batchNumber,
                    take,
                    lot.unitCost,
                    lotCogs));

            needed -= take;
            if (needed == 0) {
                break;
            }
        }

        BigDecimal effectiveUnitCogs = totalCogs.divide(BigDecimal.valueOf(requestedUnits), 4, RoundingMode.HALF_UP);
        return CogsAllocationResult.success(requestedUnits, totalCogs, effectiveUnitCogs, allocations);
    }

    /**
     * Reverses previous allocations, restoring quantities back into originating lots.
     */
    public static LotReversalResult reverseAllocation(List<CostLotRecord> lots, List<LotAllocation> allocations, String reason) {
        if (lots == null || allocations == null || allocations.isEmpty()) {
            return new LotReversalResult(false, 0, BigDecimal.ZERO, reason);
        }

        Map<String, CostLotRecord> lotMap = new HashMap<>();
        for (CostLotRecord l : lots) {
            lotMap.put(l.lotId, l);
        }

        int restoredUnits = 0;
        BigDecimal restoredCogs = BigDecimal.ZERO;

        for (LotAllocation alloc : allocations) {
            CostLotRecord lot = lotMap.get(alloc.lotId);
            if (lot != null) {
                lot.remainingQuantity += alloc.unitsAllocated;
                if ("DEPLETED".equals(lot.status)) {
                    lot.status = "ACTIVE";
                }
                restoredUnits += alloc.unitsAllocated;
                restoredCogs = restoredCogs.add(alloc.lotCogs);
            }
        }

        return new LotReversalResult(true, restoredUnits, restoredCogs, reason);
    }

    /**
     * Computes aggregate balance sheet valuation across active lots.
     */
    public static BigDecimal computeInventoryValuation(List<CostLotRecord> lots) {
        if (lots == null || lots.isEmpty()) return BigDecimal.ZERO;
        BigDecimal totalValuation = BigDecimal.ZERO;
        for (CostLotRecord l : lots) {
            if (l.isActive && !"REVERSED".equals(l.status)) {
                totalValuation = totalValuation.add(l.unitCost.multiply(BigDecimal.valueOf(l.remainingQuantity)));
            }
        }
        return totalValuation.setScale(4, RoundingMode.HALF_UP);
    }
}

