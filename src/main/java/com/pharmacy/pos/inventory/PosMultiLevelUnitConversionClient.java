package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * PosMultiLevelUnitConversionClient
 * Architecture Decision: ADR-0163 (P163).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Client-side multi-level unit conversion and validation engine for JavaFX Desktop POS.
 * Enforces fractional tablet prevention and inappropriate dosing-unit guardrails.
 */
public class PosMultiLevelUnitConversionClient {

    public static final class ConversionPolicy {
        public final String policyId;
        public final String productId;
        public final boolean isSplittable;
        public final List<Double> allowedSplitFractions;
        public final String clinicalDosingUnit;
        public final BigDecimal dosingConversionRatio;
        public final Set<String> prohibitedDosingUnits;

        public ConversionPolicy(
                String policyId,
                String productId,
                boolean isSplittable,
                List<Double> allowedSplitFractions,
                String clinicalDosingUnit,
                BigDecimal dosingConversionRatio,
                Collection<String> prohibitedDosingUnits) {
            this.policyId = policyId;
            this.productId = productId;
            this.isSplittable = isSplittable;
            this.allowedSplitFractions = allowedSplitFractions != null ? Collections.unmodifiableList(allowedSplitFractions) : Collections.singletonList(1.0);
            this.clinicalDosingUnit = clinicalDosingUnit != null ? clinicalDosingUnit.trim().toUpperCase() : "MG";
            this.dosingConversionRatio = dosingConversionRatio != null ? dosingConversionRatio : BigDecimal.valueOf(500);
            Set<String> prohibited = new HashSet<>();
            if (prohibitedDosingUnits != null) {
                for (String p : prohibitedDosingUnits) {
                    if (p != null) prohibited.add(p.trim().toUpperCase());
                }
            }
            this.prohibitedDosingUnits = Collections.unmodifiableSet(prohibited);
        }
    }

    public static final class ValidationReport {
        public final boolean isValid;
        public final String errorCode;
        public final String errorMessageEn;
        public final String errorMessageBn;

        public ValidationReport(boolean isValid, String errorCode, String errorMessageEn, String errorMessageBn) {
            this.isValid = isValid;
            this.errorCode = errorCode;
            this.errorMessageEn = errorMessageEn;
            this.errorMessageBn = errorMessageBn;
        }

        public static ValidationReport ok() {
            return new ValidationReport(true, null, null, null);
        }

        public static ValidationReport failure(String code, String en, String bn) {
            return new ValidationReport(false, code, en, bn);
        }
    }

    public static final class ConversionResult {
        public final boolean success;
        public final String errorCode;
        public final String errorMessageEn;
        public final String errorMessageBn;
        public final BigDecimal inputQuantity;
        public final BigDecimal convertedQuantity;
        public final BigDecimal costValuationBdt;
        public final BigDecimal mrpValuationBdt;
        public final boolean isClinicalDosingCalculation;

        public ConversionResult(
                boolean success,
                String errorCode,
                String errorMessageEn,
                String errorMessageBn,
                BigDecimal inputQuantity,
                BigDecimal convertedQuantity,
                BigDecimal costValuationBdt,
                BigDecimal mrpValuationBdt,
                boolean isClinicalDosingCalculation) {
            this.success = success;
            this.errorCode = errorCode;
            this.errorMessageEn = errorMessageEn;
            this.errorMessageBn = errorMessageBn;
            this.inputQuantity = inputQuantity != null ? inputQuantity : BigDecimal.ZERO;
            this.convertedQuantity = convertedQuantity != null ? convertedQuantity : BigDecimal.ZERO;
            this.costValuationBdt = costValuationBdt != null ? costValuationBdt : BigDecimal.ZERO;
            this.mrpValuationBdt = mrpValuationBdt != null ? mrpValuationBdt : BigDecimal.ZERO;
            this.isClinicalDosingCalculation = isClinicalDosingCalculation;
        }

        public static ConversionResult failure(String code, String en, String bn) {
            return new ConversionResult(false, code, en, bn, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }

        public static ConversionResult success(BigDecimal inQty, BigDecimal convQty, BigDecimal costVal, BigDecimal mrpVal, boolean isClinical) {
            return new ConversionResult(true, null, null, null, inQty, convQty, costVal, mrpVal, isClinical);
        }
    }

    public boolean canConfigurePolicy(String userRole) {
        if (userRole == null || userRole.trim().isEmpty()) {
            return false;
        }
        return !"CASHIER".equalsIgnoreCase(userRole.trim());
    }

    public ValidationReport validateDispense(
            String unit,
            BigDecimal quantity,
            String dosageForm,
            ConversionPolicy policy) {

        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return ValidationReport.failure(
                    "ERR_INVALID_QUANTITY",
                    "Quantity must be strictly positive.",
                    "পরিমাণ অবশ্যই ধনাত্মক হতে হবে।"
            );
        }
        if (unit == null || unit.trim().isEmpty()) {
            return ValidationReport.failure("ERR_UNIT_REQUIRED", "Dispense unit is required.", "একক প্রদান করা আবশ্যক।");
        }

        String u = unit.trim().toUpperCase();
        if (policy != null && policy.prohibitedDosingUnits.contains(u)) {
            return ValidationReport.failure(
                    "ERR_INCOMPATIBLE_DOSING_UNIT",
                    "Unit '" + u + "' is incompatible with dosage form '" + dosageForm + "'.",
                    "'" + u + "' ইউনিটটি '" + dosageForm + "' ডোজেজ ফর্মের সাথে সামঞ্জস্যপূর্ণ নয়।"
            );
        }

        boolean hasFraction = quantity.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0;
        if (hasFraction) {
            boolean isSolidTablet = "TABLET".equalsIgnoreCase(u) || "CAPSULE".equalsIgnoreCase(u) ||
                    (dosageForm != null && (dosageForm.toUpperCase().contains("TABLET") || dosageForm.toUpperCase().contains("CAPSULE")));

            if (isSolidTablet) {
                if (policy == null || !policy.isSplittable) {
                    return ValidationReport.failure(
                            "ERR_FRACTIONAL_TABLET_NOT_ALLOWED",
                            "Tablet is not scored or splittable. Fractional dispensing is prohibited.",
                            "ট্যাবলেটটি অবিভাজ্য এবং ভগ্নাংশে বিক্রি বা বিতরণ নিষিদ্ধ।"
                    );
                }
                double frac = quantity.remainder(BigDecimal.ONE).doubleValue();
                boolean matchesAllowed = false;
                for (double allowed : policy.allowedSplitFractions) {
                    if (allowed < 1.0 && Math.abs(frac - allowed) < 0.001) {
                        matchesAllowed = true;
                        break;
                    }
                }
                if (!matchesAllowed) {
                    return ValidationReport.failure(
                            "ERR_INVALID_TABLET_SPLIT",
                            "Fractional step (" + frac + ") is not in allowed split fractions " + policy.allowedSplitFractions + ".",
                            "অননুমোদিত ভগ্নাংশে ট্যাবলেট বিভাজন প্রচেষ্টা।"
                    );
                }
            } else if ("VIAL".equalsIgnoreCase(u) || "AMPOULE".equalsIgnoreCase(u) || "BOTTLE".equalsIgnoreCase(u) || "CANISTER".equalsIgnoreCase(u)) {
                return ValidationReport.failure(
                        "ERR_INDIVISIBLE_UNIT_FRACTION",
                        "Unit '" + u + "' is indivisible and cannot be dispensed in fractions.",
                        "'" + u + "' একটি অবিভাজ্য একক এবং ভগ্নাংশে বিতরণ করা যাবে না।"
                );
            }
        }

        return ValidationReport.ok();
    }

    public ConversionResult convertMultiLevel(
            BigDecimal quantity,
            String fromUnit,
            String toUnit,
            int packagingMultiplier,
            String dosageForm,
            ConversionPolicy policy,
            BigDecimal costPerBaseUnit,
            BigDecimal mrpPerBaseUnit) {

        ValidationReport fromCheck = validateDispense(fromUnit, quantity, dosageForm, policy);
        if (!fromCheck.isValid) {
            return ConversionResult.failure(fromCheck.errorCode, fromCheck.errorMessageEn, fromCheck.errorMessageBn);
        }

        String toU = toUnit != null ? toUnit.trim().toUpperCase() : "";
        if (policy != null && policy.prohibitedDosingUnits.contains(toU)) {
            return ConversionResult.failure(
                    "ERR_INCOMPATIBLE_DOSING_UNIT",
                    "Target unit '" + toU + "' is prohibited for dosage form '" + dosageForm + "'.",
                    "টার্গেট ইউনিট '" + toU + "' নিষিদ্ধ।"
            );
        }

        // Clinical calculation (e.g. Tablets -> MG)
        if (policy != null && toU.equals(policy.clinicalDosingUnit)) {
            BigDecimal totalPotency = quantity.multiply(policy.dosingConversionRatio);
            return ConversionResult.success(quantity, totalPotency, BigDecimal.ZERO, BigDecimal.ZERO, true);
        }

        // Packaging conversion
        BigDecimal mult = BigDecimal.valueOf(Math.max(1, packagingMultiplier));
        BigDecimal convertedQty = quantity.multiply(mult);

        ValidationReport toCheck = validateDispense(toU, convertedQty, dosageForm, policy);
        if (!toCheck.isValid) {
            return ConversionResult.failure(toCheck.errorCode, toCheck.errorMessageEn, toCheck.errorMessageBn);
        }

        BigDecimal costUnit = costPerBaseUnit != null ? costPerBaseUnit : BigDecimal.ZERO;
        BigDecimal mrpUnit = mrpPerBaseUnit != null ? mrpPerBaseUnit : BigDecimal.ZERO;
        BigDecimal costVal = convertedQty.multiply(costUnit).setScale(4, RoundingMode.HALF_UP);
        BigDecimal mrpVal = convertedQty.multiply(mrpUnit).setScale(4, RoundingMode.HALF_UP);

        return ConversionResult.success(quantity, convertedQty, costVal, mrpVal, false);
    }
}

