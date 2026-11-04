package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * PosSaleLotAllocationClient
 * Architecture Decision: ADR-0170 (P170).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Client-side engine managing sale item lot allocation records, DGDA recall traceability,
 * controlled sales return restock, and unblended GAAP-compliant gross profit derivations.
 */
public class PosSaleLotAllocationClient {

    public static final class LotAllocationItem {
        public final String allocationId;
        public final String invoiceId;
        public final String invoiceNumber;
        public final String customerName;
        public final String customerPhone;
        public final String productId;
        public final String productBrandName;
        public final String batchId;
        public final String batchNumber;
        public final String costLotId;
        public final int quantityBaseUnits;
        public int returnedQuantityBaseUnits;
        public final BigDecimal unitSellingPrice;
        public final BigDecimal unitCost;
        public final BigDecimal totalSellingPrice;
        public final BigDecimal totalCost;
        public BigDecimal grossProfit;
        public BigDecimal profitMarginPct;
        public String status;
        public boolean isReturned;

        public LotAllocationItem(
                String allocationId,
                String invoiceId,
                String invoiceNumber,
                String customerName,
                String customerPhone,
                String productId,
                String productBrandName,
                String batchId,
                String batchNumber,
                String costLotId,
                int quantityBaseUnits,
                int returnedQuantityBaseUnits,
                BigDecimal unitSellingPrice,
                BigDecimal unitCost,
                String status) {
            this.allocationId = allocationId != null ? allocationId : "";
            this.invoiceId = invoiceId != null ? invoiceId : "";
            this.invoiceNumber = invoiceNumber != null ? invoiceNumber : "";
            this.customerName = customerName != null ? customerName : "Walk-in Customer";
            this.customerPhone = customerPhone != null ? customerPhone : "";
            this.productId = productId != null ? productId : "";
            this.productBrandName = productBrandName != null ? productBrandName : "";
            this.batchId = batchId != null ? batchId : "";
            this.batchNumber = batchNumber != null ? batchNumber : "";
            this.costLotId = costLotId;
            this.quantityBaseUnits = Math.max(1, quantityBaseUnits);
            this.returnedQuantityBaseUnits = Math.max(0, returnedQuantityBaseUnits);
            this.unitSellingPrice = unitSellingPrice != null ? unitSellingPrice : BigDecimal.ZERO;
            this.unitCost = unitCost != null ? unitCost : BigDecimal.ZERO;
            this.status = status != null ? status : "COMMITTED";
            this.isReturned = this.returnedQuantityBaseUnits > 0;

            this.totalSellingPrice = this.unitSellingPrice.multiply(BigDecimal.valueOf(this.quantityBaseUnits));
            this.totalCost = this.unitCost.multiply(BigDecimal.valueOf(this.quantityBaseUnits));
            this.grossProfit = this.totalSellingPrice.subtract(this.totalCost);
            if (this.totalSellingPrice.compareTo(BigDecimal.ZERO) > 0) {
                this.profitMarginPct = this.grossProfit
                        .multiply(BigDecimal.valueOf(100))
                        .divide(this.totalSellingPrice, 2, RoundingMode.HALF_UP);
            } else {
                this.profitMarginPct = BigDecimal.ZERO;
            }
        }

        public int getNetRetainedUnits() {
            return Math.max(0, quantityBaseUnits - returnedQuantityBaseUnits);
        }

        public boolean canReturn(int units) {
            return units > 0 && units <= getNetRetainedUnits();
        }
    }

    public static final class CustomerTrace {
        public final String allocationId;
        public final String invoiceId;
        public final String invoiceNumber;
        public final String customerName;
        public final String customerPhone;
        public final int quantityDispensed;
        public final int quantityReturned;
        public final int netQuantityWithCustomer;

        public CustomerTrace(
                String allocationId,
                String invoiceId,
                String invoiceNumber,
                String customerName,
                String customerPhone,
                int quantityDispensed,
                int quantityReturned) {
            this.allocationId = allocationId;
            this.invoiceId = invoiceId;
            this.invoiceNumber = invoiceNumber;
            this.customerName = customerName;
            this.customerPhone = customerPhone;
            this.quantityDispensed = quantityDispensed;
            this.quantityReturned = quantityReturned;
            this.netQuantityWithCustomer = Math.max(0, quantityDispensed - quantityReturned);
        }
    }

    public static final class ReturnResult {
        public final boolean isSuccessful;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;
        public final String allocationId;
        public final int returnedUnits;
        public final BigDecimal refundAmount;
        public final BigDecimal refundedCogs;

        public ReturnResult(
                boolean isSuccessful,
                String errorCode,
                String messageEn,
                String messageBn,
                String allocationId,
                int returnedUnits,
                BigDecimal refundAmount,
                BigDecimal refundedCogs) {
            this.isSuccessful = isSuccessful;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
            this.allocationId = allocationId;
            this.returnedUnits = returnedUnits;
            this.refundAmount = refundAmount != null ? refundAmount : BigDecimal.ZERO;
            this.refundedCogs = refundedCogs != null ? refundedCogs : BigDecimal.ZERO;
        }

        public static ReturnResult success(String allocationId, int units, BigDecimal refund, BigDecimal refundedCogs) {
            return new ReturnResult(
                    true,
                    null,
                    "Return processed successfully and stock restored.",
                    "বিক্রয় ফেরত সফলভাবে প্রক্রিয়া করা হয়েছে এবং স্টক পুনরুদ্ধার করা হয়েছে।",
                    allocationId,
                    units,
                    refund,
                    refundedCogs);
        }

        public static ReturnResult failure(String errorCode, String messageEn, String messageBn, String allocationId) {
            return new ReturnResult(false, errorCode, messageEn, messageBn, allocationId, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public static final class RecallImpactResult {
        public final String batchId;
        public final int totalDispensedUnits;
        public final int totalReturnedUnits;
        public final int netPatientExposureUnits;
        public final int affectedInvoicesCount;
        public final List<CustomerTrace> affectedCustomers;

        public RecallImpactResult(
                String batchId,
                int totalDispensedUnits,
                int totalReturnedUnits,
                int netPatientExposureUnits,
                int affectedInvoicesCount,
                List<CustomerTrace> affectedCustomers) {
            this.batchId = batchId != null ? batchId : "";
            this.totalDispensedUnits = totalDispensedUnits;
            this.totalReturnedUnits = totalReturnedUnits;
            this.netPatientExposureUnits = netPatientExposureUnits;
            this.affectedInvoicesCount = affectedInvoicesCount;
            this.affectedCustomers = affectedCustomers != null ? affectedCustomers : Collections.emptyList();
        }
    }

    public static final class ProfitAnalyticsResult {
        public final BigDecimal totalRevenue;
        public final BigDecimal totalCogs;
        public final BigDecimal grossProfit;
        public final BigDecimal profitMarginPct;
        public final int totalUnitsSold;
        public final int totalUnitsReturned;
        public final int netUnitsRetained;
        public final int allocationsCount;

        public ProfitAnalyticsResult(
                BigDecimal totalRevenue,
                BigDecimal totalCogs,
                BigDecimal grossProfit,
                BigDecimal profitMarginPct,
                int totalUnitsSold,
                int totalUnitsReturned,
                int allocationsCount) {
            this.totalRevenue = totalRevenue;
            this.totalCogs = totalCogs;
            this.grossProfit = grossProfit;
            this.profitMarginPct = profitMarginPct;
            this.totalUnitsSold = totalUnitsSold;
            this.totalUnitsReturned = totalUnitsReturned;
            this.netUnitsRetained = totalUnitsSold - totalUnitsReturned;
            this.allocationsCount = allocationsCount;
        }
    }

    /**
     * Processes return against an existing lot allocation.
     */
    public static ReturnResult processReturn(LotAllocationItem allocation, int returnQuantity) {
        if (allocation == null) {
            return ReturnResult.failure("ERR_ALLOCATION_NULL", "Allocation item is null.", "লট বরাদ্দের তথ্য পাওয়া যায়নি।", "");
        }
        if (returnQuantity <= 0) {
            return ReturnResult.failure(
                    "ERR_INVALID_QUANTITY",
                    "Return quantity must be positive.",
                    "ফেরত পরিমাণ অবশ্যই ধনাত্মক হতে হবে।",
                    allocation.allocationId);
        }
        if (!allocation.canReturn(returnQuantity)) {
            return ReturnResult.failure(
                    "ERR_RETURN_EXCEEDS_DISPENSED",
                    "Return quantity exceeds net retained units (" + allocation.getNetRetainedUnits() + ").",
                    "ফেরতের পরিমাণ গ্রাহকের কাছে অবশিষ্ট পরিমাণের বেশি।",
                    allocation.allocationId);
        }

        allocation.returnedQuantityBaseUnits += returnQuantity;
        allocation.isReturned = true;
        if (allocation.returnedQuantityBaseUnits == allocation.quantityBaseUnits) {
            allocation.status = "FULLY_RETURNED";
        } else {
            allocation.status = "PARTIALLY_RETURNED";
        }

        BigDecimal refundAmount = allocation.unitSellingPrice.multiply(BigDecimal.valueOf(returnQuantity));
        BigDecimal refundedCogs = allocation.unitCost.multiply(BigDecimal.valueOf(returnQuantity));

        return ReturnResult.success(allocation.allocationId, returnQuantity, refundAmount, refundedCogs);
    }

    /**
     * Generates statutory recall impact trace for patient safety notification.
     */
    public static RecallImpactResult traceRecallImpact(List<LotAllocationItem> allocations, String targetBatchId) {
        int dispensed = 0;
        int returned = 0;
        Set<String> invoiceSet = new HashSet<>();
        List<CustomerTrace> customers = new ArrayList<>();

        if (allocations != null) {
            for (LotAllocationItem a : allocations) {
                if (targetBatchId == null || targetBatchId.equals(a.batchId)) {
                    dispensed += a.quantityBaseUnits;
                    returned += a.returnedQuantityBaseUnits;
                    invoiceSet.add(a.invoiceId);
                    customers.add(new CustomerTrace(
                            a.allocationId,
                            a.invoiceId,
                            a.invoiceNumber,
                            a.customerName,
                            a.customerPhone,
                            a.quantityBaseUnits,
                            a.returnedQuantityBaseUnits));
                }
            }
        }

        return new RecallImpactResult(
                targetBatchId,
                dispensed,
                returned,
                dispensed - returned,
                invoiceSet.size(),
                customers);
    }

    /**
     * Computes exact unblended gross profit analytics across allocations.
     */
    public static ProfitAnalyticsResult computeProfitAnalytics(List<LotAllocationItem> allocations) {
        BigDecimal totalRev = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        int totalUnits = 0;
        int totalReturned = 0;

        if (allocations != null) {
            for (LotAllocationItem a : allocations) {
                totalRev = totalRev.add(a.totalSellingPrice);
                totalCost = totalCost.add(a.totalCost);
                totalUnits += a.quantityBaseUnits;
                totalReturned += a.returnedQuantityBaseUnits;
            }
        }

        BigDecimal grossProfit = totalRev.subtract(totalCost);
        BigDecimal marginPct = BigDecimal.ZERO;
        if (totalRev.compareTo(BigDecimal.ZERO) > 0) {
            marginPct = grossProfit
                    .multiply(BigDecimal.valueOf(100))
                    .divide(totalRev, 2, RoundingMode.HALF_UP);
        }

        return new ProfitAnalyticsResult(
                totalRev,
                totalCost,
                grossProfit,
                marginPct,
                totalUnits,
                totalReturned,
                allocations != null ? allocations.size() : 0);
    }
}

