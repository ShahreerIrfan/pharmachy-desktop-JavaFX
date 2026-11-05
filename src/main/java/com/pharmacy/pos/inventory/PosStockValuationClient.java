package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * PosStockValuationClient
 * Architecture Decision: ADR-0179 (P179).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Desktop POS inventory valuation, FIFO actual cost lot & weighted average calculations,
 * 5-tier aging analysis, potential gross margin, and realized margin tracking.
 */
public class PosStockValuationClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, ValuationSnapshotDto> snapshotStore;

    public PosStockValuationClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, ValuationSnapshotDto> snapshotStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.snapshotStore = snapshotStore != null ? snapshotStore : new HashMap<>();
    }

    public static final class CostLotItem {
        public final String lotId;
        public final String tenantId;
        public final String branchId;
        public final String productId;
        public final String brandName;
        public final String genericName;
        public final String dosageForm;
        public final LocalDate purchaseDate;
        public final int remainingQuantity;
        public final BigDecimal unitCost;
        public final BigDecimal mrpUnit;

        public CostLotItem(
                String lotId,
                String tenantId,
                String branchId,
                String productId,
                String brandName,
                String genericName,
                String dosageForm,
                LocalDate purchaseDate,
                int remainingQuantity,
                BigDecimal unitCost,
                BigDecimal mrpUnit) {
            this.lotId = lotId != null ? lotId : "";
            this.tenantId = tenantId != null ? tenantId : "";
            this.branchId = branchId != null ? branchId : "";
            this.productId = productId != null ? productId : "";
            this.brandName = brandName != null ? brandName : "";
            this.genericName = genericName != null ? genericName : "";
            this.dosageForm = dosageForm != null ? dosageForm : "";
            this.purchaseDate = purchaseDate != null ? purchaseDate : LocalDate.now();
            this.remainingQuantity = Math.max(0, remainingQuantity);
            this.unitCost = unitCost != null ? unitCost.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.mrpUnit = mrpUnit != null ? mrpUnit.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
    }

    public static final class SaleAllocationItem {
        public final String allocationId;
        public final String tenantId;
        public final String branchId;
        public final String productId;
        public final LocalDate saleDate;
        public final int quantityBaseUnits;
        public final BigDecimal totalSellingPrice;
        public final BigDecimal totalCost;
        public final BigDecimal grossProfit;

        public SaleAllocationItem(
                String allocationId,
                String tenantId,
                String branchId,
                String productId,
                LocalDate saleDate,
                int quantityBaseUnits,
                BigDecimal totalSellingPrice,
                BigDecimal totalCost,
                BigDecimal grossProfit) {
            this.allocationId = allocationId != null ? allocationId : "";
            this.tenantId = tenantId != null ? tenantId : "";
            this.branchId = branchId != null ? branchId : "";
            this.productId = productId != null ? productId : "";
            this.saleDate = saleDate != null ? saleDate : LocalDate.now();
            this.quantityBaseUnits = Math.max(0, quantityBaseUnits);
            this.totalSellingPrice = totalSellingPrice != null ? totalSellingPrice.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.totalCost = totalCost != null ? totalCost.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.grossProfit = grossProfit != null ? grossProfit.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
    }

    public static final class AgingBucketDto {
        public final String label;
        public final int quantityBaseUnits;
        public final BigDecimal costValuationBdt;
        public final BigDecimal pctOfTotalCost;

        public AgingBucketDto(String label, int quantityBaseUnits, BigDecimal costValuationBdt, BigDecimal pctOfTotalCost) {
            this.label = label != null ? label : "";
            this.quantityBaseUnits = quantityBaseUnits;
            this.costValuationBdt = costValuationBdt != null ? costValuationBdt.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.pctOfTotalCost = pctOfTotalCost != null ? pctOfTotalCost.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
    }

    public static final class ProductValuationItemDto {
        public final String productId;
        public final String brandName;
        public final String genericName;
        public final String dosageForm;
        public final int quantityBaseUnits;
        public final BigDecimal costValuationBdt;
        public final BigDecimal retailValuationBdt;
        public final BigDecimal potentialMarginPct;
        public final BigDecimal unitCostEffective;
        public final BigDecimal mrpUnit;

        public ProductValuationItemDto(
                String productId,
                String brandName,
                String genericName,
                String dosageForm,
                int quantityBaseUnits,
                BigDecimal costValuationBdt,
                BigDecimal retailValuationBdt,
                BigDecimal potentialMarginPct,
                BigDecimal unitCostEffective,
                BigDecimal mrpUnit) {
            this.productId = productId != null ? productId : "";
            this.brandName = brandName != null ? brandName : "";
            this.genericName = genericName != null ? genericName : "";
            this.dosageForm = dosageForm != null ? dosageForm : "";
            this.quantityBaseUnits = quantityBaseUnits;
            this.costValuationBdt = costValuationBdt != null ? costValuationBdt.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.retailValuationBdt = retailValuationBdt != null ? retailValuationBdt.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.potentialMarginPct = potentialMarginPct != null ? potentialMarginPct.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.unitCostEffective = unitCostEffective != null ? unitCostEffective.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.mrpUnit = mrpUnit != null ? mrpUnit.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
    }

    public static final class RealizedMarginDto {
        public final int allocationsCount;
        public final BigDecimal totalRevenueBdt;
        public final BigDecimal totalCogsBdt;
        public final BigDecimal realizedGrossProfitBdt;
        public final BigDecimal realizedMarginPct;

        public RealizedMarginDto(
                int allocationsCount,
                BigDecimal totalRevenueBdt,
                BigDecimal totalCogsBdt,
                BigDecimal realizedGrossProfitBdt,
                BigDecimal realizedMarginPct) {
            this.allocationsCount = allocationsCount;
            this.totalRevenueBdt = totalRevenueBdt != null ? totalRevenueBdt.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.totalCogsBdt = totalCogsBdt != null ? totalCogsBdt.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.realizedGrossProfitBdt = realizedGrossProfitBdt != null ? realizedGrossProfitBdt.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.realizedMarginPct = realizedMarginPct != null ? realizedMarginPct.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
    }

    public static final class ValuationSummaryDto {
        public final String tenantId;
        public final String branchId;
        public final LocalDate asOfDate;
        public final String costingMethod;
        public final int totalProductsCount;
        public final int totalQuantityBaseUnits;
        public final BigDecimal totalCostValuationBdt;
        public final BigDecimal totalRetailValuationBdt;
        public final BigDecimal potentialGrossMarginBdt;
        public final BigDecimal potentialMarginPct;
        public final Map<String, AgingBucketDto> agingSummary;
        public final RealizedMarginDto realizedMargin;
        public final List<ProductValuationItemDto> productBreakdown;

        public ValuationSummaryDto(
                String tenantId,
                String branchId,
                LocalDate asOfDate,
                String costingMethod,
                int totalProductsCount,
                int totalQuantityBaseUnits,
                BigDecimal totalCostValuationBdt,
                BigDecimal totalRetailValuationBdt,
                BigDecimal potentialGrossMarginBdt,
                BigDecimal potentialMarginPct,
                Map<String, AgingBucketDto> agingSummary,
                RealizedMarginDto realizedMargin,
                List<ProductValuationItemDto> productBreakdown) {
            this.tenantId = tenantId != null ? tenantId : "";
            this.branchId = branchId != null ? branchId : "";
            this.asOfDate = asOfDate != null ? asOfDate : LocalDate.now();
            this.costingMethod = costingMethod != null ? costingMethod : "FIFO_ACTUAL";
            this.totalProductsCount = totalProductsCount;
            this.totalQuantityBaseUnits = totalQuantityBaseUnits;
            this.totalCostValuationBdt = totalCostValuationBdt != null ? totalCostValuationBdt.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.totalRetailValuationBdt = totalRetailValuationBdt != null ? totalRetailValuationBdt.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.potentialGrossMarginBdt = potentialGrossMarginBdt != null ? potentialGrossMarginBdt.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.potentialMarginPct = potentialMarginPct != null ? potentialMarginPct.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.agingSummary = agingSummary != null ? agingSummary : new HashMap<>();
            this.realizedMargin = realizedMargin;
            this.productBreakdown = productBreakdown != null ? productBreakdown : new ArrayList<>();
        }
    }

    public static final class ValuationSnapshotDto {
        public final String snapshotId;
        public final String tenantId;
        public final String branchId;
        public final LocalDate valuationDate;
        public final String costingMethod;
        public final int totalQuantityBaseUnits;
        public final BigDecimal totalCostValuationBdt;
        public final BigDecimal totalRetailValuationBdt;
        public final BigDecimal potentialGrossMarginBdt;
        public final BigDecimal potentialMarginPct;
        public final String notes;
        public final Instant createdAt;

        public ValuationSnapshotDto(
                String snapshotId,
                String tenantId,
                String branchId,
                LocalDate valuationDate,
                String costingMethod,
                int totalQuantityBaseUnits,
                BigDecimal totalCostValuationBdt,
                BigDecimal totalRetailValuationBdt,
                BigDecimal potentialGrossMarginBdt,
                BigDecimal potentialMarginPct,
                String notes,
                Instant createdAt) {
            this.snapshotId = snapshotId != null ? snapshotId : "";
            this.tenantId = tenantId != null ? tenantId : "";
            this.branchId = branchId != null ? branchId : "";
            this.valuationDate = valuationDate != null ? valuationDate : LocalDate.now();
            this.costingMethod = costingMethod != null ? costingMethod : "FIFO_ACTUAL";
            this.totalQuantityBaseUnits = totalQuantityBaseUnits;
            this.totalCostValuationBdt = totalCostValuationBdt != null ? totalCostValuationBdt.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.totalRetailValuationBdt = totalRetailValuationBdt != null ? totalRetailValuationBdt.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.potentialGrossMarginBdt = potentialGrossMarginBdt != null ? potentialGrossMarginBdt.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.potentialMarginPct = potentialMarginPct != null ? potentialMarginPct.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.notes = notes != null ? notes : "";
            this.createdAt = createdAt != null ? createdAt : Instant.now();
        }
    }

    public static final class Result<T> {
        public final boolean success;
        public final T data;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        private Result(boolean success, T data, String errorCode, String messageEn, String messageBn) {
            this.success = success;
            this.data = data;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static <T> Result<T> ok(T data, String messageEn, String messageBn) {
            return new Result<>(true, data, null, messageEn, messageBn);
        }

        public static <T> Result<T> fail(String errorCode, String messageEn, String messageBn) {
            return new Result<>(false, null, errorCode, messageEn, messageBn);
        }
    }

    private boolean isAuthorized() {
        return "BRANCH_MANAGER".equalsIgnoreCase(userRole)
                || "PHARMACY_OWNER".equalsIgnoreCase(userRole)
                || "SUPERADMIN".equalsIgnoreCase(userRole)
                || "STAFF_PHARMACIST".equalsIgnoreCase(userRole)
                || "AUDITOR".equalsIgnoreCase(userRole);
    }

    /**
     * Calculates valuation, aging buckets, and margin metrics.
     */
    public Result<ValuationSummaryDto> calculateValuation(
            List<CostLotItem> costLots,
            List<SaleAllocationItem> allocations,
            LocalDate asOfDate,
            String costingMethod) {

        if (!isAuthorized()) {
            return Result.fail(
                    "ERR_PERMISSION_DENIED",
                    "Cashier role is unauthorized to view inventory valuation and margin metrics.",
                    "ক্যাশিয়ারদের ইনভেন্টরি মূল্যায়ন এবং লাভের হিসাব দেখার অনুমতি নেই।"
            );
        }

        if (asOfDate == null) {
            asOfDate = LocalDate.now();
        }

        if (costingMethod == null || (!costingMethod.equalsIgnoreCase("FIFO_ACTUAL") && !costingMethod.equalsIgnoreCase("WEIGHTED_AVERAGE"))) {
            return Result.fail(
                    "ERR_INVALID_VALUATION_INPUT",
                    "Invalid costing method. Must be FIFO_ACTUAL or WEIGHTED_AVERAGE.",
                    "অবৈধ কস্টিং পদ্ধতি। FIFO_ACTUAL অথবা WEIGHTED_AVERAGE নির্বাচন করুন।"
            );
        }

        int totalUnits = 0;
        BigDecimal totalCost = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        BigDecimal totalRetail = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        Map<String, Integer> bucketUnits = new HashMap<>();
        Map<String, BigDecimal> bucketCost = new HashMap<>();
        String[] buckets = {"days_0_to_30", "days_31_to_60", "days_61_to_90", "days_91_to_180", "days_181_plus"};
        for (String b : buckets) {
            bucketUnits.put(b, 0);
            bucketCost.put(b, BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        }

        Map<String, List<CostLotItem>> productLots = new HashMap<>();

        if (costLots != null) {
            for (CostLotItem lot : costLots) {
                if (!lot.tenantId.equalsIgnoreCase(tenantId) || !lot.branchId.equalsIgnoreCase(branchId)) {
                    continue; // Skip cross-tenant/cross-branch
                }
                if (lot.purchaseDate.isAfter(asOfDate)) {
                    continue; // Skip future purchases
                }

                int qty = lot.remainingQuantity;
                if (qty <= 0) continue;

                BigDecimal costVal = lot.unitCost.multiply(BigDecimal.valueOf(qty)).setScale(4, RoundingMode.HALF_UP);
                BigDecimal retailVal = lot.mrpUnit.multiply(BigDecimal.valueOf(qty)).setScale(4, RoundingMode.HALF_UP);

                totalUnits += qty;
                totalCost = totalCost.add(costVal);
                totalRetail = totalRetail.add(retailVal);

                long days = ChronoUnit.DAYS.between(lot.purchaseDate, asOfDate);
                String bKey;
                if (days <= 30) bKey = "days_0_to_30";
                else if (days <= 60) bKey = "days_31_to_60";
                else if (days <= 90) bKey = "days_61_to_90";
                else if (days <= 180) bKey = "days_91_to_180";
                else bKey = "days_181_plus";

                bucketUnits.put(bKey, bucketUnits.get(bKey) + qty);
                bucketCost.put(bKey, bucketCost.get(bKey).add(costVal));

                productLots.computeIfAbsent(lot.productId, k -> new ArrayList<>()).add(lot);
            }
        }

        // Potential Margin
        BigDecimal potentialGrossMargin = totalRetail.subtract(totalCost).setScale(4, RoundingMode.HALF_UP);
        BigDecimal potentialMarginPct = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (totalRetail.compareTo(BigDecimal.ZERO) > 0) {
            potentialMarginPct = potentialGrossMargin.multiply(BigDecimal.valueOf(100)).divide(totalRetail, 2, RoundingMode.HALF_UP);
        }

        // Aging Buckets
        Map<String, AgingBucketDto> agingSummary = new HashMap<>();
        String[] labels = {
                "0 - 30 Days (Fresh)",
                "31 - 60 Days (Normal)",
                "61 - 90 Days (Moderate)",
                "91 - 180 Days (Slow-Moving)",
                "181+ Days (Aged / Dormant)"
        };
        for (int i = 0; i < buckets.length; i++) {
            String bKey = buckets[i];
            int bUnits = bucketUnits.get(bKey);
            BigDecimal bCost = bucketCost.get(bKey);
            BigDecimal bPct = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
                bPct = bCost.multiply(BigDecimal.valueOf(100)).divide(totalCost, 2, RoundingMode.HALF_UP);
            }
            agingSummary.put(bKey, new AgingBucketDto(labels[i], bUnits, bCost, bPct));
        }

        // Product Breakdown
        List<ProductValuationItemDto> productBreakdown = new ArrayList<>();
        for (Map.Entry<String, List<CostLotItem>> entry : productLots.entrySet()) {
            List<CostLotItem> lots = entry.getValue();
            if (lots.isEmpty()) continue;
            CostLotItem first = lots.get(0);
            int pQty = 0;
            BigDecimal pCost = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            BigDecimal pRetail = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

            for (CostLotItem l : lots) {
                pQty += l.remainingQuantity;
                pCost = pCost.add(l.unitCost.multiply(BigDecimal.valueOf(l.remainingQuantity)));
                pRetail = pRetail.add(l.mrpUnit.multiply(BigDecimal.valueOf(l.remainingQuantity)));
            }

            BigDecimal pMargin = pRetail.subtract(pCost);
            BigDecimal pMarginPct = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            if (pRetail.compareTo(BigDecimal.ZERO) > 0) {
                pMarginPct = pMargin.multiply(BigDecimal.valueOf(100)).divide(pRetail, 2, RoundingMode.HALF_UP);
            }
            BigDecimal effCost = pQty > 0 ? pCost.divide(BigDecimal.valueOf(pQty), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

            productBreakdown.add(new ProductValuationItemDto(
                    first.productId,
                    first.brandName,
                    first.genericName,
                    first.dosageForm,
                    pQty,
                    pCost,
                    pRetail,
                    pMarginPct,
                    effCost,
                    first.mrpUnit
            ));
        }

        // Realized Margin
        int allocCount = 0;
        BigDecimal realizedRev = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        BigDecimal realizedCogs = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        BigDecimal realizedProfit = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        if (allocations != null) {
            for (SaleAllocationItem a : allocations) {
                if (!a.tenantId.equalsIgnoreCase(tenantId) || !a.branchId.equalsIgnoreCase(branchId)) continue;
                allocCount++;
                realizedRev = realizedRev.add(a.totalSellingPrice);
                realizedCogs = realizedCogs.add(a.totalCost);
                realizedProfit = realizedProfit.add(a.grossProfit);
            }
        }

        BigDecimal realizedMarginPct = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (realizedRev.compareTo(BigDecimal.ZERO) > 0) {
            realizedMarginPct = realizedProfit.multiply(BigDecimal.valueOf(100)).divide(realizedRev, 2, RoundingMode.HALF_UP);
        }

        RealizedMarginDto realizedMargin = new RealizedMarginDto(
                allocCount, realizedRev, realizedCogs, realizedProfit, realizedMarginPct
        );

        ValuationSummaryDto summary = new ValuationSummaryDto(
                tenantId,
                branchId,
                asOfDate,
                costingMethod.toUpperCase(),
                productBreakdown.size(),
                totalUnits,
                totalCost,
                totalRetail,
                potentialGrossMargin,
                potentialMarginPct,
                agingSummary,
                realizedMargin,
                productBreakdown
        );

        return Result.ok(
                summary,
                "Stock valuation and margin analytics derived successfully.",
                "ইনভেন্টরি মূল্যায়ন এবং লাভের হিসাব সফলভাবে সম্পন্ন হয়েছে।"
        );
    }

    /**
     * Persists an immutable point-in-time valuation snapshot.
     */
    public Result<ValuationSnapshotDto> createSnapshot(
            List<CostLotItem> costLots,
            List<SaleAllocationItem> allocations,
            LocalDate asOfDate,
            String costingMethod,
            String notes) {

        var valRes = calculateValuation(costLots, allocations, asOfDate, costingMethod);
        if (!valRes.success) {
            return Result.fail(valRes.errorCode, valRes.messageEn, valRes.messageBn);
        }

        ValuationSummaryDto s = valRes.data;
        String snapId = "valsnap-" + UUID.randomUUID().toString().substring(0, 12);
        ValuationSnapshotDto snapshot = new ValuationSnapshotDto(
                snapId,
                tenantId,
                branchId,
                s.asOfDate,
                s.costingMethod,
                s.totalQuantityBaseUnits,
                s.totalCostValuationBdt,
                s.totalRetailValuationBdt,
                s.potentialGrossMarginBdt,
                s.potentialMarginPct,
                notes != null ? notes : "",
                Instant.now()
        );

        snapshotStore.put(snapId, snapshot);
        return Result.ok(
                snapshot,
                "Valuation snapshot saved successfully.",
                "মূল্যায়ন স্ন্যাপশট সফলভাবে সংরক্ষিত হয়েছে।"
        );
    }
}

