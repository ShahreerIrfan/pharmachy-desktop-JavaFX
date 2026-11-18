package com.pharmacy.pos.customers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Architecture Decision: ADR-0270 (P270).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * Desktop POS Client for managing customer loyalty points,
 * checking tier benefits, and validating DGDA safety exclusions.
 */
public class CustomerLoyaltyClient {

    public enum LoyaltyTier {
        BRONZE,
        SILVER,
        GOLD,
        PLATINUM
    }

    public static class CartItemForLoyalty {
        public final String productName;
        public final BigDecimal lineTotalBdt;
        public final boolean isPrescriptionRequired;
        public final boolean isAntibiotic;
        public final boolean isNarcotic;

        public CartItemForLoyalty(
                String productName,
                BigDecimal lineTotalBdt,
                boolean isPrescriptionRequired,
                boolean isAntibiotic,
                boolean isNarcotic
        ) {
            this.productName = productName;
            this.lineTotalBdt = lineTotalBdt != null ? lineTotalBdt.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
            this.isPrescriptionRequired = isPrescriptionRequired;
            this.isAntibiotic = isAntibiotic;
            this.isNarcotic = isNarcotic;
        }

        public boolean isRestrictedByDgda() {
            return isPrescriptionRequired || isAntibiotic || isNarcotic;
        }
    }

    public static class SpendCalculationResult {
        public final BigDecimal eligibleSpendBdt;
        public final BigDecimal excludedSpendBdt;
        public final int pointsEarnable;

        public SpendCalculationResult(BigDecimal eligibleSpendBdt, BigDecimal excludedSpendBdt, int pointsEarnable) {
            this.eligibleSpendBdt = eligibleSpendBdt;
            this.excludedSpendBdt = excludedSpendBdt;
            this.pointsEarnable = pointsEarnable;
        }
    }

    public static class LoyaltyValidationResult {
        public final boolean isValid;
        public final String errorCode;
        public final String errorMessage;

        public LoyaltyValidationResult(boolean isValid, String errorCode, String errorMessage) {
            this.isValid = isValid;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public static LoyaltyValidationResult valid() {
            return new LoyaltyValidationResult(true, null, null);
        }

        public static LoyaltyValidationResult error(String code, String message) {
            return new LoyaltyValidationResult(false, code, message);
        }
    }

    /**
     * Calculates eligible vs excluded spend under statutory DGDA rules.
     * Prescription drugs and antibiotics cannot earn promotional loyalty points.
     */
    public static SpendCalculationResult calculateEligibleSpend(
            List<CartItemForLoyalty> items,
            BigDecimal earnRateBdtPerPoint
    ) {
        BigDecimal eligible = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal excluded = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        if (items != null) {
            for (CartItemForLoyalty item : items) {
                if (item.isRestrictedByDgda()) {
                    excluded = excluded.add(item.lineTotalBdt);
                } else {
                    eligible = eligible.add(item.lineTotalBdt);
                }
            }
        }

        int points = 0;
        if (earnRateBdtPerPoint != null && earnRateBdtPerPoint.compareTo(BigDecimal.ZERO) > 0) {
            points = eligible.divideToIntegralValue(earnRateBdtPerPoint).intValue();
        }

        return new SpendCalculationResult(eligible, excluded, points);
    }

    /**
     * Validates redemption request against customer points and eligible non-restricted cart items.
     */
    public static LoyaltyValidationResult validateRedemption(
            int pointsToRedeem,
            int currentPointsBalance,
            int minPointsThreshold,
            BigDecimal eligibleSpendBdt,
            BigDecimal redemptionRateBdtPerPoint,
            int maxRedemptionPercent
    ) {
        if (pointsToRedeem <= 0) {
            return LoyaltyValidationResult.error("ERR_POINTS_INVALID", "Points to redeem must be greater than zero.");
        }

        if (pointsToRedeem < minPointsThreshold) {
            return LoyaltyValidationResult.error(
                    "ERR_MIN_POINTS_THRESHOLD",
                    "Minimum " + minPointsThreshold + " points required for redemption."
            );
        }

        if (pointsToRedeem > currentPointsBalance) {
            return LoyaltyValidationResult.error(
                    "ERR_INSUFFICIENT_LOYALTY_POINTS",
                    "Requested points (" + pointsToRedeem + ") exceeds available balance (" + currentPointsBalance + ")."
            );
        }

        if (eligibleSpendBdt == null || eligibleSpendBdt.compareTo(BigDecimal.ZERO) <= 0) {
            return LoyaltyValidationResult.error(
                    "ERR_NO_ELIGIBLE_ITEMS",
                    "Cart contains only restricted prescription/antibiotic items. Loyalty redemption is forbidden."
            );
        }

        BigDecimal discount = BigDecimal.valueOf(pointsToRedeem).multiply(redemptionRateBdtPerPoint);
        BigDecimal maxAllowedDiscount = eligibleSpendBdt.multiply(BigDecimal.valueOf(maxRedemptionPercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        if (discount.compareTo(maxAllowedDiscount) > 0) {
            return LoyaltyValidationResult.error(
                    "ERR_MAX_REDEMPTION_EXCEEDED",
                    "Discount (" + discount + " BDT) exceeds max allowed (" + maxAllowedDiscount + " BDT)."
            );
        }

        return LoyaltyValidationResult.valid();
    }
}
