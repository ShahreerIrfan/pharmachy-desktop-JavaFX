package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * PosLooseTabletSalesClient
 * Architecture Decision: ADR-0164 (P164).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Client-side loose tablet sales and partial strip dispensing engine for JavaFX Desktop POS.
 * Enforces blister-integrity guardrails, exact base-piece stock conservation, and FEFO allocation.
 */
public class PosLooseTabletSalesClient {

    public static final class LooseSalesPolicy {
        public final String configId;
        public final String tenantId;
        public final String branchId;
        public final String productId;
        public final String productName;
        public final boolean allowLooseSales;
        public final int minLooseDispenseUnits;
        public final int maxLooseDispenseUnits;
        public final int stripSizePieces;
        public final BigDecimal stripMrp;
        public final BigDecimal looseUnitMrp;
        public final boolean applyLooseUnitSurcharge;
        public final BigDecimal looseSurchargePercentage;
        public final String prohibitionReason;

        public LooseSalesPolicy(
                String configId,
                String tenantId,
                String branchId,
                String productId,
                String productName,
                boolean allowLooseSales,
                int minLooseDispenseUnits,
                int maxLooseDispenseUnits,
                int stripSizePieces,
                BigDecimal stripMrp,
                BigDecimal looseUnitMrp,
                boolean applyLooseUnitSurcharge,
                BigDecimal looseSurchargePercentage,
                String prohibitionReason) {
            this.configId = configId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.productId = productId;
            this.productName = productName != null ? productName : "";
            this.allowLooseSales = allowLooseSales;
            this.minLooseDispenseUnits = Math.max(1, minLooseDispenseUnits);
            this.maxLooseDispenseUnits = Math.max(this.minLooseDispenseUnits, maxLooseDispenseUnits);
            this.stripSizePieces = Math.max(1, stripSizePieces);
            this.stripMrp = stripMrp != null ? stripMrp : BigDecimal.ZERO;
            this.looseUnitMrp = looseUnitMrp != null ? looseUnitMrp : BigDecimal.ZERO;
            this.applyLooseUnitSurcharge = applyLooseUnitSurcharge;
            this.looseSurchargePercentage = looseSurchargePercentage != null ? looseSurchargePercentage : BigDecimal.ZERO;
            this.prohibitionReason = prohibitionReason != null ? prohibitionReason.trim() : "";
        }
    }

    public static final class BatchStockItem {
        public final String batchId;
        public final String batchNumber;
        public final LocalDate expiryDate;
        public int quantityBaseUnits;
        public final boolean isQuarantined;

        public BatchStockItem(
                String batchId,
                String batchNumber,
                LocalDate expiryDate,
                int quantityBaseUnits,
                boolean isQuarantined) {
            this.batchId = batchId;
            this.batchNumber = batchNumber;
            this.expiryDate = expiryDate;
            this.quantityBaseUnits = quantityBaseUnits;
            this.isQuarantined = isQuarantined;
        }

        public boolean isExpired(LocalDate asOfDate) {
            return expiryDate == null || !expiryDate.isAfter(asOfDate);
        }
    }

    public static final class QuoteCalculation {
        public final boolean isAllowed;
        public final String errorCode;
        public final String errorMessageEn;
        public final String errorMessageBn;
        public final int normalizedStrips;
        public final int normalizedLoosePieces;
        public final int totalBaseUnits;
        public final BigDecimal stripTotal;
        public final BigDecimal looseTotal;
        public final BigDecimal surchargeAmount;
        public final BigDecimal finalQuoteAmount;
        public final int availableStockBaseUnits;
        public final boolean isStockSufficient;

        public QuoteCalculation(
                boolean isAllowed,
                String errorCode,
                String errorMessageEn,
                String errorMessageBn,
                int normalizedStrips,
                int normalizedLoosePieces,
                int totalBaseUnits,
                BigDecimal stripTotal,
                BigDecimal looseTotal,
                BigDecimal surchargeAmount,
                BigDecimal finalQuoteAmount,
                int availableStockBaseUnits,
                boolean isStockSufficient) {
            this.isAllowed = isAllowed;
            this.errorCode = errorCode;
            this.errorMessageEn = errorMessageEn;
            this.errorMessageBn = errorMessageBn;
            this.normalizedStrips = normalizedStrips;
            this.normalizedLoosePieces = normalizedLoosePieces;
            this.totalBaseUnits = totalBaseUnits;
            this.stripTotal = stripTotal;
            this.looseTotal = looseTotal;
            this.surchargeAmount = surchargeAmount;
            this.finalQuoteAmount = finalQuoteAmount;
            this.availableStockBaseUnits = availableStockBaseUnits;
            this.isStockSufficient = isStockSufficient;
        }

        public static QuoteCalculation prohibited(String reason, int availableStock) {
            String en = "Loose tablet sales prohibited: " + (reason != null && !reason.isEmpty() ? reason : "Blister integrity guardrail.");
            String bn = "এই ঔষধের খোলা ট্যাবলেট বিক্রি নিষিদ্ধ: " + (reason != null && !reason.isEmpty() ? reason : "ব্লিষ্টার ও আর্দ্রতা সুরক্ষা নীতি।");
            return new QuoteCalculation(
                    false,
                    "ERR_LOOSE_TABLET_SALES_PROHIBITED",
                    en,
                    bn,
                    0, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    availableStock, false
            );
        }

        public static QuoteCalculation invalidQuantity(String en, String bn) {
            return new QuoteCalculation(
                    false,
                    "ERR_INVALID_QUANTITY",
                    en,
                    bn,
                    0, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    0, false
            );
        }
    }

    public static final class BatchAllocation {
        public final String batchId;
        public final String batchNumber;
        public final LocalDate expiryDate;
        public final int deductedBaseUnits;
        public final BigDecimal subtotalBdt;

        public BatchAllocation(
                String batchId,
                String batchNumber,
                LocalDate expiryDate,
                int deductedBaseUnits,
                BigDecimal subtotalBdt) {
            this.batchId = batchId;
            this.batchNumber = batchNumber;
            this.expiryDate = expiryDate;
            this.deductedBaseUnits = deductedBaseUnits;
            this.subtotalBdt = subtotalBdt;
        }
    }

    public static final class CheckoutExecution {
        public final boolean success;
        public final String invoiceId;
        public final String invoiceNumber;
        public final String errorCode;
        public final String errorMessageEn;
        public final String errorMessageBn;
        public final QuoteCalculation quote;
        public final List<BatchAllocation> allocations;

        public CheckoutExecution(
                boolean success,
                String invoiceId,
                String invoiceNumber,
                String errorCode,
                String errorMessageEn,
                String errorMessageBn,
                QuoteCalculation quote,
                List<BatchAllocation> allocations) {
            this.success = success;
            this.invoiceId = invoiceId;
            this.invoiceNumber = invoiceNumber;
            this.errorCode = errorCode;
            this.errorMessageEn = errorMessageEn;
            this.errorMessageBn = errorMessageBn;
            this.quote = quote;
            this.allocations = allocations != null ? Collections.unmodifiableList(allocations) : Collections.emptyList();
        }

        public static CheckoutExecution failure(String code, String en, String bn, QuoteCalculation quote) {
            return new CheckoutExecution(false, null, null, code, en, bn, quote, Collections.emptyList());
        }
    }

    /**
     * Calculates quote with normalized strips, loose units, exact Decimal amounts, and blister-integrity checks.
     */
    public QuoteCalculation calculateQuote(
            LooseSalesPolicy policy,
            int strips,
            int loosePieces,
            Integer totalPieces,
            int availableStockBaseUnits) {
        if (policy == null) {
            return QuoteCalculation.invalidQuantity("Policy is required.", "পলিসি অনুপস্থিত।");
        }

        int normalizedStrips;
        int normalizedLoose;
        int totalUnits;

        if (totalPieces != null) {
            if (totalPieces <= 0) {
                return QuoteCalculation.invalidQuantity("Total pieces must be positive.", "মোট ট্যাবলেটের পরিমাণ ধনাত্মক হতে হবে।");
            }
            normalizedStrips = totalPieces / policy.stripSizePieces;
            normalizedLoose = totalPieces % policy.stripSizePieces;
            totalUnits = totalPieces;
        } else {
            if (strips < 0 || loosePieces < 0) {
                return QuoteCalculation.invalidQuantity("Quantities cannot be negative.", "পরিমাণ ঋণাত্মক হতে পারে না।");
            }
            normalizedStrips = strips + (loosePieces / policy.stripSizePieces);
            normalizedLoose = loosePieces % policy.stripSizePieces;
            totalUnits = (normalizedStrips * policy.stripSizePieces) + normalizedLoose;
            if (totalUnits <= 0) {
                return QuoteCalculation.invalidQuantity("Requested quantity must be positive.", "মোট ট্যাবলেটের পরিমাণ শূন্যের বেশি হতে হবে।");
            }
        }

        // Guardrail: Loose tablet sales prohibition
        if (!policy.allowLooseSales && normalizedLoose > 0) {
            return QuoteCalculation.prohibited(policy.prohibitionReason, availableStockBaseUnits);
        }

        // Min loose units limit check
        if (normalizedLoose > 0 && normalizedLoose < policy.minLooseDispenseUnits) {
            String en = "Minimum loose units is " + policy.minLooseDispenseUnits + " pieces.";
            String bn = "নূন্যতম খোলা ট্যাবলেটের পরিমাণ " + policy.minLooseDispenseUnits + " টি।";
            return new QuoteCalculation(
                    false,
                    "ERR_BELOW_MIN_LOOSE_DISPENSE",
                    en, bn,
                    normalizedStrips, normalizedLoose, totalUnits,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    availableStockBaseUnits, false
            );
        }

        // Exact Decimal pricing
        BigDecimal stripTotal = policy.stripMrp.multiply(BigDecimal.valueOf(normalizedStrips)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal effectiveLooseMrp = policy.looseUnitMrp;
        BigDecimal surchargeAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        if (policy.applyLooseUnitSurcharge && policy.looseSurchargePercentage.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal surchargeFactor = BigDecimal.ONE.add(
                    policy.looseSurchargePercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
            );
            effectiveLooseMrp = policy.looseUnitMrp.multiply(surchargeFactor).setScale(4, RoundingMode.HALF_UP);
            surchargeAmount = (effectiveLooseMrp.subtract(policy.looseUnitMrp))
                    .multiply(BigDecimal.valueOf(normalizedLoose))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal looseTotal = effectiveLooseMrp.multiply(BigDecimal.valueOf(normalizedLoose)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal finalAmount = stripTotal.add(looseTotal).setScale(2, RoundingMode.HALF_UP);
        boolean isSufficient = availableStockBaseUnits >= totalUnits;

        return new QuoteCalculation(
                true,
                null, null, null,
                normalizedStrips,
                normalizedLoose,
                totalUnits,
                stripTotal,
                looseTotal,
                surchargeAmount,
                finalAmount,
                availableStockBaseUnits,
                isSufficient
        );
    }

    /**
     * Executes atomic FEFO batch checkout. Blocks expired batches and guards against overselling.
     */
    public CheckoutExecution executeCheckout(
            LooseSalesPolicy policy,
            List<BatchStockItem> availableBatches,
            int strips,
            int loosePieces,
            Integer totalPieces,
            String customerName,
            String customerPhone,
            String paymentMethod,
            LocalDate asOfDate) {
        LocalDate today = asOfDate != null ? asOfDate : LocalDate.now();

        // Calculate unexpired active stock
        int activeStock = 0;
        if (availableBatches != null) {
            for (BatchStockItem b : availableBatches) {
                if (b != null && !b.isQuarantined && !b.isExpired(today)) {
                    activeStock += b.quantityBaseUnits;
                }
            }
        }

        QuoteCalculation quote = calculateQuote(policy, strips, loosePieces, totalPieces, activeStock);
        if (!quote.isAllowed) {
            return CheckoutExecution.failure(quote.errorCode, quote.errorMessageEn, quote.errorMessageBn, quote);
        }

        if (quote.totalBaseUnits > activeStock) {
            return CheckoutExecution.failure(
                    "ERR_INSUFFICIENT_STOCK",
                    "Insufficient unexpired stock available. Required: " + quote.totalBaseUnits + ", Available: " + activeStock,
                    "পর্যাপ্ত মেয়াদযুক্ত স্টক নেই। প্রয়োজনীয়: " + quote.totalBaseUnits + ", মজুদ: " + activeStock,
                    quote
            );
        }

        // Filter valid unexpired batches and sort FEFO
        List<BatchStockItem> candidateBatches = new ArrayList<>();
        for (BatchStockItem b : availableBatches) {
            if (b != null && !b.isQuarantined && !b.isExpired(today) && b.quantityBaseUnits > 0) {
                candidateBatches.add(b);
            }
        }
        candidateBatches.sort(Comparator.comparing((BatchStockItem b) -> b.expiryDate)
                .thenComparing((BatchStockItem b) -> b.batchNumber != null ? b.batchNumber : ""));

        List<BatchAllocation> allocations = new ArrayList<>();
        int remainingToDeduct = quote.totalBaseUnits;
        BigDecimal unitPrice = quote.finalQuoteAmount.divide(BigDecimal.valueOf(quote.totalBaseUnits), 4, RoundingMode.HALF_UP);

        for (BatchStockItem b : candidateBatches) {
            if (remainingToDeduct <= 0) break;
            int deduct = Math.min(remainingToDeduct, b.quantityBaseUnits);
            b.quantityBaseUnits -= deduct;

            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(deduct)).setScale(2, RoundingMode.HALF_UP);
            allocations.add(new BatchAllocation(b.batchId, b.batchNumber, b.expiryDate, deduct, subtotal));
            remainingToDeduct -= deduct;
        }

        String invId = "inv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String invNum = "INV-" + today.toString().replace("-", "") + "-" + invId.substring(4, 10).toUpperCase();

        return new CheckoutExecution(
                true,
                invId,
                invNum,
                null, null, null,
                quote,
                allocations
        );
    }

    /**
     * Reverses a completed loose sale, re-crediting deducted base units to their respective batches.
     */
    public boolean reverseSale(CheckoutExecution execution, Map<String, BatchStockItem> batchLookup) {
        if (execution == null || !execution.success || execution.allocations == null || batchLookup == null) {
            return false;
        }

        for (BatchAllocation alloc : execution.allocations) {
            BatchStockItem batch = batchLookup.get(alloc.batchId);
            if (batch != null) {
                batch.quantityBaseUnits += alloc.deductedBaseUnits;
            }
        }
        return true;
    }
}

