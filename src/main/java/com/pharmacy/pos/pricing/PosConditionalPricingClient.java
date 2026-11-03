package com.pharmacy.pos.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * PosConditionalPricingClient
 * Architecture Decision: ADR-0153 (P153).
 * Phase 08: Milestone M04 / M05 - Specialized Item & Multi-Tier Packaging Formulation Editors.
 *
 * Client-side conditional pricing engine for JavaFX Desktop POS.
 * Coordinates unit-aware price conversions across packs, strips, and base pieces
 * using strict BigDecimal arithmetic and break-bulk validation safeguards.
 */
public class PosConditionalPricingClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public enum BaseDispensingUnit {
        PIECE("Piece / Unit", "পিস / একক"),
        TABLET("Tablet", "ট্যাবলেট"),
        CAPSULE("Capsule", "ক্যাপসুল"),
        VIAL("Vial", "ভায়াল"),
        AMPOULE("Ampoule", "অ্যাম্পুল"),
        BOTTLE("Bottle", "বোতল"),
        DEVICE("Device", "ডিভাইস"),
        SACHET("Sachet", "স্যাচেট"),
        SUPPOSITORY("Suppository", "সাপোজিটরি");

        private final String labelEn;
        private final String labelBn;

        BaseDispensingUnit(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() {
            return labelEn;
        }

        public String getLabelBn() {
            return labelBn;
        }
    }

    public static final class PricingCalculationResult {
        public final boolean isValid;
        public final String errorCode;
        public final String errorMessage;
        public final int totalBaseUnitsPerPack;
        public final BigDecimal stripCostBdt;
        public final BigDecimal stripMrpBdt;
        public final BigDecimal packCostBdt;
        public final BigDecimal packMrpBdt;

        private PricingCalculationResult(
                boolean isValid,
                String errorCode,
                String errorMessage,
                int totalBaseUnitsPerPack,
                BigDecimal stripCostBdt,
                BigDecimal stripMrpBdt,
                BigDecimal packCostBdt,
                BigDecimal packMrpBdt) {
            this.isValid = isValid;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.totalBaseUnitsPerPack = totalBaseUnitsPerPack;
            this.stripCostBdt = stripCostBdt;
            this.stripMrpBdt = stripMrpBdt;
            this.packCostBdt = packCostBdt;
            this.packMrpBdt = packMrpBdt;
        }

        public static PricingCalculationResult success(
                int totalUnits,
                BigDecimal stripCost,
                BigDecimal stripMrp,
                BigDecimal packCost,
                BigDecimal packMrp) {
            return new PricingCalculationResult(true, null, null, totalUnits, stripCost, stripMrp, packCost, packMrp);
        }

        public static PricingCalculationResult failure(String errorCode, String errorMessage) {
            return new PricingCalculationResult(false, errorCode, errorMessage, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public static final class ConditionalPricingState {
        public final UiStatus uiStatus;
        public final String errorMessage;
        public final String userRole;
        public final boolean isOffline;
        public final Map<String, Object> pricingData;

        public ConditionalPricingState(
                UiStatus uiStatus,
                String errorMessage,
                String userRole,
                boolean isOffline,
                Map<String, Object> pricingData) {
            this.uiStatus = uiStatus;
            this.errorMessage = errorMessage;
            this.userRole = userRole;
            this.isOffline = isOffline;
            this.pricingData = pricingData != null ? Collections.unmodifiableMap(new LinkedHashMap<>(pricingData)) : Collections.emptyMap();
        }
    }

    /**
     * Calculates derived pack and strip prices from base piece pricing and packaging multipliers.
     * Uses strict BigDecimal multiplication with 4 decimal places rounding.
     */
    public PricingCalculationResult calculateDerivedPricing(
            int packToStripMultiplier,
            int stripToPieceMultiplier,
            Integer explicitTotalUnits,
            BigDecimal costPerPieceBdt,
            BigDecimal mrpPerPieceBdt,
            boolean allowStripSale,
            boolean allowPieceSale) {

        if (packToStripMultiplier < 1 || stripToPieceMultiplier < 1) {
            return PricingCalculationResult.failure(
                    "ERR_INVALID_MULTIPLIER",
                    "Multipliers must be positive integers (>= 1)."
            );
        }

        int expectedTotalUnits = packToStripMultiplier * stripToPieceMultiplier;
        if (explicitTotalUnits != null && explicitTotalUnits != expectedTotalUnits) {
            return PricingCalculationResult.failure(
                    "ERR_DIMENSION_MISMATCH",
                    "Total units (" + explicitTotalUnits + ") does not match multiplier product (" + expectedTotalUnits + ")."
            );
        }

        if (costPerPieceBdt == null || costPerPieceBdt.compareTo(BigDecimal.ZERO) <= 0) {
            return PricingCalculationResult.failure(
                    "ERR_INVALID_PRICE",
                    "Cost per piece must be greater than 0."
            );
        }

        if (mrpPerPieceBdt == null || mrpPerPieceBdt.compareTo(BigDecimal.ZERO) <= 0) {
            return PricingCalculationResult.failure(
                    "ERR_INVALID_PRICE",
                    "MRP per piece must be greater than 0."
            );
        }

        if (expectedTotalUnits > 1 && !allowStripSale && !allowPieceSale) {
            return PricingCalculationResult.failure(
                    "ERR_INVALID_BREAK_BULK_POLICY",
                    "Multi-unit packaging requires either strip or piece sales to be enabled."
            );
        }

        BigDecimal stripMultiplier = BigDecimal.valueOf(stripToPieceMultiplier);
        BigDecimal totalMultiplier = BigDecimal.valueOf(expectedTotalUnits);

        BigDecimal stripCost = costPerPieceBdt.multiply(stripMultiplier).setScale(4, RoundingMode.HALF_UP);
        BigDecimal stripMrp = mrpPerPieceBdt.multiply(stripMultiplier).setScale(4, RoundingMode.HALF_UP);
        BigDecimal packCost = costPerPieceBdt.multiply(totalMultiplier).setScale(4, RoundingMode.HALF_UP);
        BigDecimal packMrp = mrpPerPieceBdt.multiply(totalMultiplier).setScale(4, RoundingMode.HALF_UP);

        return PricingCalculationResult.success(expectedTotalUnits, stripCost, stripMrp, packCost, packMrp);
    }

    /**
     * Evaluates permission boundaries for conditional pricing edits.
     * Cashiers have strictly read-only access.
     */
    public boolean canEditPricing(String userRole) {
        if (userRole == null) {
            return false;
        }
        String normalized = userRole.toUpperCase().trim();
        return !"CASHIER".equals(normalized);
    }

    /**
     * Resolves localized label for base dispensing unit.
     */
    public String getUnitDisplay(String unitCode, String locale) {
        if (unitCode == null) return "";
        try {
            BaseDispensingUnit unit = BaseDispensingUnit.valueOf(unitCode.toUpperCase().trim());
            return "bn".equalsIgnoreCase(locale) ? unit.getLabelBn() : unit.getLabelEn();
        } catch (IllegalArgumentException e) {
            return unitCode;
        }
    }
}

