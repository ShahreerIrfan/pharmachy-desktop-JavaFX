package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * PosNonDivisiblePackClient
 * Architecture Decision: ADR-0165 (P165).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Client-side non-divisible pack and sterile container guardrail engine for JavaFX Desktop POS.
 * Enforces blister/device integrity for insulin pens, inhalers, eye drop bottles, diagnostic kits,
 * and clinical exemption protocol requirements.
 */
public class PosNonDivisiblePackClient {

    public static final class NonDivisiblePolicy {
        public final String policyId;
        public final String tenantId;
        public final String branchId;
        public final String productId;
        public final String productName;
        public final String containerType;
        public final boolean isSplittingAllowed;
        public final boolean requirePharmacistApproval;
        public final int approvedBreakFactor;
        public final String subUnitLabel;
        public final String prohibitionReason;
        public final Set<String> exemptionProtocols;

        public NonDivisiblePolicy(
                String policyId,
                String tenantId,
                String branchId,
                String productId,
                String productName,
                String containerType,
                boolean isSplittingAllowed,
                boolean requirePharmacistApproval,
                int approvedBreakFactor,
                String subUnitLabel,
                String prohibitionReason,
                Collection<String> exemptionProtocols) {
            this.policyId = policyId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.productId = productId;
            this.productName = productName != null ? productName : "";
            this.containerType = containerType != null ? containerType.toUpperCase() : "SEALED_BOX";
            this.isSplittingAllowed = isSplittingAllowed;
            this.requirePharmacistApproval = requirePharmacistApproval;
            this.approvedBreakFactor = Math.max(1, approvedBreakFactor);
            this.subUnitLabel = subUnitLabel != null ? subUnitLabel.trim().toUpperCase() : "PIECE";
            this.prohibitionReason = prohibitionReason != null ? prohibitionReason.trim() : "";
            Set<String> protocols = new HashSet<>();
            if (exemptionProtocols != null) {
                for (String p : exemptionProtocols) {
                    if (p != null) protocols.add(p.trim().toUpperCase());
                }
            }
            this.exemptionProtocols = Collections.unmodifiableSet(protocols);
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

    public static final class ValidationResult {
        public final boolean isAllowed;
        public final String errorCode;
        public final String errorMessageEn;
        public final String errorMessageBn;
        public final String productId;
        public final String containerType;
        public final boolean isSplittingAllowed;
        public final int approvedBreakFactor;
        public final String subUnitLabel;
        public final boolean isSplitRequest;
        public final String exemptionProtocol;

        public ValidationResult(
                boolean isAllowed,
                String errorCode,
                String errorMessageEn,
                String errorMessageBn,
                String productId,
                String containerType,
                boolean isSplittingAllowed,
                int approvedBreakFactor,
                String subUnitLabel,
                boolean isSplitRequest,
                String exemptionProtocol) {
            this.isAllowed = isAllowed;
            this.errorCode = errorCode;
            this.errorMessageEn = errorMessageEn;
            this.errorMessageBn = errorMessageBn;
            this.productId = productId;
            this.containerType = containerType;
            this.isSplittingAllowed = isSplittingAllowed;
            this.approvedBreakFactor = approvedBreakFactor;
            this.subUnitLabel = subUnitLabel;
            this.isSplitRequest = isSplitRequest;
            this.exemptionProtocol = exemptionProtocol;
        }

        public static ValidationResult ok(NonDivisiblePolicy policy, boolean isSplit, String protocol) {
            return new ValidationResult(
                    true, null, null, null,
                    policy != null ? policy.productId : "",
                    policy != null ? policy.containerType : "STANDARD_PACK",
                    policy != null ? policy.isSplittingAllowed : true,
                    policy != null ? policy.approvedBreakFactor : 1,
                    policy != null ? policy.subUnitLabel : "PIECE",
                    isSplit,
                    protocol
            );
        }

        public static ValidationResult failure(String code, String en, String bn, NonDivisiblePolicy policy, boolean isSplit, String protocol) {
            return new ValidationResult(
                    false, code, en, bn,
                    policy != null ? policy.productId : "",
                    policy != null ? policy.containerType : "UNKNOWN",
                    policy != null ? policy.isSplittingAllowed : false,
                    policy != null ? policy.approvedBreakFactor : 1,
                    policy != null ? policy.subUnitLabel : "PIECE",
                    isSplit,
                    protocol
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

    public static final class CheckoutResult {
        public final boolean success;
        public final String invoiceId;
        public final String invoiceNumber;
        public final String errorCode;
        public final String errorMessageEn;
        public final String errorMessageBn;
        public final ValidationResult validation;
        public final List<BatchAllocation> allocations;

        public CheckoutResult(
                boolean success,
                String invoiceId,
                String invoiceNumber,
                String errorCode,
                String errorMessageEn,
                String errorMessageBn,
                ValidationResult validation,
                List<BatchAllocation> allocations) {
            this.success = success;
            this.invoiceId = invoiceId;
            this.invoiceNumber = invoiceNumber;
            this.errorCode = errorCode;
            this.errorMessageEn = errorMessageEn;
            this.errorMessageBn = errorMessageBn;
            this.validation = validation;
            this.allocations = allocations != null ? Collections.unmodifiableList(allocations) : Collections.emptyList();
        }

        public static CheckoutResult failure(String code, String en, String bn, ValidationResult val) {
            return new CheckoutResult(false, null, null, code, en, bn, val, Collections.emptyList());
        }
    }

    /**
     * Pre-flight validation against container integrity and clinical exemption protocols.
     */
    public ValidationResult validateDispense(
            NonDivisiblePolicy policy,
            BigDecimal requestedQuantity,
            boolean isSplitRequest,
            String exemptionProtocol,
            String userRole) {
        if (requestedQuantity == null || requestedQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return ValidationResult.failure(
                    "ERR_INVALID_QUANTITY",
                    "Requested quantity must be positive.",
                    "পরিমাণ শূন্যের বেশি হতে হবে।",
                    policy, isSplitRequest, exemptionProtocol
            );
        }

        if (policy == null) {
            return ValidationResult.ok(null, isSplitRequest, exemptionProtocol);
        }

        // 1. Fractional quantity guardrail on indivisible container
        if (requestedQuantity.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
            String en = "Fractional dispensing of non-divisible container (" + policy.containerType + ") is strictly prohibited.";
            String bn = "অবিভাজ্য কন্টেইনারের (" + policy.containerType + ") ভগ্নাংশ বা আংশিক বিক্রয় সম্পূর্ণ নিষিদ্ধ।";
            return ValidationResult.failure("ERR_NON_DIVISIBLE_CONTAINER_SPLIT_PROHIBITED", en, bn, policy, isSplitRequest, exemptionProtocol);
        }

        // 2. Container splitting checks
        if (isSplitRequest) {
            if (!policy.isSplittingAllowed) {
                String en = "Breaking sealed container (" + policy.containerType + ") is strictly prohibited: " + policy.prohibitionReason;
                String bn = "সিল্ড কন্টেইনার ভেঙে বিক্রি করা সম্পূর্ণ নিষিদ্ধ: " + policy.prohibitionReason;
                return ValidationResult.failure("ERR_NON_DIVISIBLE_CONTAINER_SPLIT_PROHIBITED", en, bn, policy, isSplitRequest, exemptionProtocol);
            }

            // Exemption protocol check
            if (!policy.exemptionProtocols.isEmpty()) {
                String protoUpper = exemptionProtocol != null ? exemptionProtocol.trim().toUpperCase() : "";
                if (!policy.exemptionProtocols.contains(protoUpper)) {
                    String en = "Approved clinical exemption protocol required to split container. Permitted: " + policy.exemptionProtocols;
                    String bn = "কন্টেইনার বিভাজনের জন্য অনুমোদিত ক্লিনিক্যাল প্রোটোকল প্রয়োজন।";
                    return ValidationResult.failure("ERR_INVALID_EXEMPTION_PROTOCOL", en, bn, policy, isSplitRequest, exemptionProtocol);
                }
            }

            // Role check: Cashier cannot unilaterally authorize splitting
            if (policy.requirePharmacistApproval) {
                if (userRole == null || "CASHIER".equalsIgnoreCase(userRole.trim())) {
                    String en = "Cashier role cannot authorize breaking a non-divisible container. Pharmacist or Manager sign-off required.";
                    String bn = "ক্যাশিয়ার কর্তৃক অবিভাজ্য কন্টেইনার বিভাজনের অনুমোদন দেওয়া নিষিদ্ধ। ফার্মাসিস্টের অনুমোদন আবশ্যক।";
                    return ValidationResult.failure("ERR_ROLE_UNAUTHORIZED", en, bn, policy, isSplitRequest, exemptionProtocol);
                }
            }
        }

        return ValidationResult.ok(policy, isSplitRequest, exemptionProtocol);
    }

    /**
     * Executes atomic FEFO batch checkout, enforcing non-divisible container guardrails.
     */
    public CheckoutResult executeCheckout(
            NonDivisiblePolicy policy,
            List<BatchStockItem> availableBatches,
            BigDecimal requestedQuantity,
            boolean isSplitRequest,
            String exemptionProtocol,
            String userRole,
            BigDecimal unitPrice,
            LocalDate asOfDate) {
        LocalDate today = asOfDate != null ? asOfDate : LocalDate.now();

        ValidationResult val = validateDispense(policy, requestedQuantity, isSplitRequest, exemptionProtocol, userRole);
        if (!val.isAllowed) {
            return CheckoutResult.failure(val.errorCode, val.errorMessageEn, val.errorMessageBn, val);
        }

        int totalUnits = requestedQuantity.intValue();

        // Calculate active unexpired stock
        int activeStock = 0;
        if (availableBatches != null) {
            for (BatchStockItem b : availableBatches) {
                if (b != null && !b.isQuarantined && !b.isExpired(today)) {
                    activeStock += b.quantityBaseUnits;
                }
            }
        }

        if (totalUnits > activeStock) {
            return CheckoutResult.failure(
                    "ERR_INSUFFICIENT_STOCK",
                    "Insufficient unexpired stock available. Required: " + totalUnits + ", Available: " + activeStock,
                    "পর্যাপ্ত মেয়াদযুক্ত স্টক নেই। প্রয়োজনীয়: " + totalUnits + ", মজুদ: " + activeStock,
                    val
            );
        }

        // Sort candidate batches by FEFO
        List<BatchStockItem> candidateBatches = new ArrayList<>();
        for (BatchStockItem b : availableBatches) {
            if (b != null && !b.isQuarantined && !b.isExpired(today) && b.quantityBaseUnits > 0) {
                candidateBatches.add(b);
            }
        }
        candidateBatches.sort(Comparator.comparing((BatchStockItem b) -> b.expiryDate)
                .thenComparing((BatchStockItem b) -> b.batchNumber != null ? b.batchNumber : ""));

        List<BatchAllocation> allocations = new ArrayList<>();
        int remaining = totalUnits;
        BigDecimal price = unitPrice != null ? unitPrice : BigDecimal.ZERO;

        for (BatchStockItem b : candidateBatches) {
            if (remaining <= 0) break;
            int deduct = Math.min(remaining, b.quantityBaseUnits);
            b.quantityBaseUnits -= deduct;

            BigDecimal subtotal = price.multiply(BigDecimal.valueOf(deduct)).setScale(2, RoundingMode.HALF_UP);
            allocations.add(new BatchAllocation(b.batchId, b.batchNumber, b.expiryDate, deduct, subtotal));
            remaining -= deduct;
        }

        String invId = "inv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String invNum = "INV-" + today.toString().replace("-", "") + "-" + invId.substring(4, 10).toUpperCase();

        return new CheckoutResult(true, invId, invNum, null, null, null, val, allocations);
    }

    /**
     * Reverses a completed non-divisible pack sale, restoring deducted base units.
     */
    public boolean reverseSale(CheckoutResult checkout, Map<String, BatchStockItem> batchLookup) {
        if (checkout == null || !checkout.success || checkout.allocations == null || batchLookup == null) {
            return false;
        }

        for (BatchAllocation alloc : checkout.allocations) {
            BatchStockItem batch = batchLookup.get(alloc.batchId);
            if (batch != null) {
                batch.quantityBaseUnits += alloc.deductedBaseUnits;
            }
        }
        return true;
    }
}
