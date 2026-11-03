package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * PosInventoryBaseUnitClient
 * Architecture Decision: ADR-0161 (P161).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Client-side inventory base-unit strategy engine for JavaFX Desktop POS.
 * Handles deterministic multi-tier packaging conversions (Pack -> Strip -> Base),
 * enforces indivisible item dispensing invariants, and calculates exact financial valuation.
 */
public class PosInventoryBaseUnitClient {

    public static final class StrategyConfig {
        public final String strategyId;
        public final String productId;
        public final String baseUnit;
        public final boolean isIndivisible;
        public final boolean allowDecimalQuantities;
        public final int decimalPlacesAllowed;
        public final BigDecimal minimumDispenseQuantity;
        public final String packUnitLabel;
        public final String intermediateUnitLabel;
        public final int packToIntermediateMultiplier;
        public final int intermediateToBaseMultiplier;
        public final int totalBaseUnitsPerPack;
        public final BigDecimal costPerBaseUnit;
        public final BigDecimal mrpPerBaseUnit;

        public StrategyConfig(
                String strategyId,
                String productId,
                String baseUnit,
                boolean isIndivisible,
                boolean allowDecimalQuantities,
                int decimalPlacesAllowed,
                BigDecimal minimumDispenseQuantity,
                String packUnitLabel,
                String intermediateUnitLabel,
                int packToIntermediateMultiplier,
                int intermediateToBaseMultiplier,
                int totalBaseUnitsPerPack,
                BigDecimal costPerBaseUnit,
                BigDecimal mrpPerBaseUnit) {
            this.strategyId = strategyId;
            this.productId = productId;
            this.baseUnit = baseUnit != null ? baseUnit.toUpperCase() : "PIECE";
            this.isIndivisible = isIndivisible;
            this.allowDecimalQuantities = allowDecimalQuantities;
            this.decimalPlacesAllowed = decimalPlacesAllowed;
            this.minimumDispenseQuantity = minimumDispenseQuantity != null ? minimumDispenseQuantity : BigDecimal.ONE;
            this.packUnitLabel = packUnitLabel != null ? packUnitLabel : "BOX";
            this.intermediateUnitLabel = intermediateUnitLabel != null ? intermediateUnitLabel : "STRIP";
            this.packToIntermediateMultiplier = Math.max(1, packToIntermediateMultiplier);
            this.intermediateToBaseMultiplier = Math.max(1, intermediateToBaseMultiplier);
            this.totalBaseUnitsPerPack = totalBaseUnitsPerPack > 0 ? totalBaseUnitsPerPack : (this.packToIntermediateMultiplier * this.intermediateToBaseMultiplier);
            this.costPerBaseUnit = costPerBaseUnit != null ? costPerBaseUnit : BigDecimal.ZERO;
            this.mrpPerBaseUnit = mrpPerBaseUnit != null ? mrpPerBaseUnit : BigDecimal.ZERO;
        }
    }

    public static final class ConversionResult {
        public final boolean success;
        public final String errorCode;
        public final String errorMessageEn;
        public final String errorMessageBn;
        public final BigDecimal baseUnitQuantity;
        public final BigDecimal costValuationBdt;
        public final BigDecimal mrpValuationBdt;

        public ConversionResult(
                boolean success,
                String errorCode,
                String errorMessageEn,
                String errorMessageBn,
                BigDecimal baseUnitQuantity,
                BigDecimal costValuationBdt,
                BigDecimal mrpValuationBdt) {
            this.success = success;
            this.errorCode = errorCode;
            this.errorMessageEn = errorMessageEn;
            this.errorMessageBn = errorMessageBn;
            this.baseUnitQuantity = baseUnitQuantity;
            this.costValuationBdt = costValuationBdt;
            this.mrpValuationBdt = mrpValuationBdt;
        }

        public static ConversionResult failure(String errorCode, String messageEn, String messageBn) {
            return new ConversionResult(false, errorCode, messageEn, messageBn, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        public static ConversionResult success(BigDecimal baseUnitQuantity, BigDecimal costVal, BigDecimal mrpVal) {
            return new ConversionResult(true, null, null, null, baseUnitQuantity, costVal, mrpVal);
        }
    }

    public static final class DecomposeResult {
        public final long totalBaseUnits;
        public final long packs;
        public final String packLabel;
        public final long intermediate;
        public final String intermediateLabel;
        public final long looseBase;
        public final String baseUnit;

        public DecomposeResult(long totalBaseUnits, long packs, String packLabel, long intermediate, String intermediateLabel, long looseBase, String baseUnit) {
            this.totalBaseUnits = totalBaseUnits;
            this.packs = packs;
            this.packLabel = packLabel;
            this.intermediate = intermediate;
            this.intermediateLabel = intermediateLabel;
            this.looseBase = looseBase;
            this.baseUnit = baseUnit;
        }
    }

    public boolean canConfigureStrategy(String userRole) {
        if (userRole == null || userRole.trim().isEmpty()) {
            return false;
        }
        return !"CASHIER".equalsIgnoreCase(userRole.trim());
    }

    public ConversionResult convertToBaseUnits(BigDecimal quantity, String unitTier, StrategyConfig config) {
        if (config == null) {
            return ConversionResult.failure("ERR_STRATEGY_NULL", "Strategy configuration cannot be null.", "কৌশল কনফিগারেশন খালি হতে পারে না।");
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return ConversionResult.failure("ERR_INVALID_QUANTITY", "Quantity must be strictly positive.", "পরিমাণ অবশ্যই ধনাত্মক হতে হবে।");
        }

        String tier = unitTier != null ? unitTier.trim().toUpperCase() : "BASE";
        BigDecimal baseQty;

        switch (tier) {
            case "PACK":
                baseQty = quantity.multiply(BigDecimal.valueOf(config.totalBaseUnitsPerPack));
                break;
            case "INTERMEDIATE":
                baseQty = quantity.multiply(BigDecimal.valueOf(config.intermediateToBaseMultiplier));
                break;
            case "BASE":
                baseQty = quantity;
                break;
            default:
                return ConversionResult.failure("ERR_INVALID_UNIT_TIER", "Unit tier must be PACK, INTERMEDIATE, or BASE.", "ইউনিট টায়ার PACK, INTERMEDIATE অথবা BASE হতে হবে।");
        }

        if (config.isIndivisible) {
            if (baseQty.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
                return ConversionResult.failure(
                        "ERR_INDIVISIBLE_UNIT_FRACTION",
                        "Item has indivisible base unit '" + config.baseUnit + "' and cannot have fractional pieces (" + baseQty + ").",
                        "'" + config.baseUnit + "' একটি অবিভাজ্য একক, এটি ভগ্নাংশে রূপান্তর করা যাবে না।"
                );
            }
        }

        BigDecimal costVal = baseQty.multiply(config.costPerBaseUnit).setScale(4, RoundingMode.HALF_UP);
        BigDecimal mrpVal = baseQty.multiply(config.mrpPerBaseUnit).setScale(4, RoundingMode.HALF_UP);

        return ConversionResult.success(baseQty, costVal, mrpVal);
    }

    public DecomposeResult decomposeBaseUnits(long totalBaseUnits, StrategyConfig config) {
        if (config == null || totalBaseUnits < 0) {
            return new DecomposeResult(0, 0, "BOX", 0, "STRIP", 0, "PIECE");
        }

        long packFactor = config.totalBaseUnitsPerPack;
        long stripFactor = config.intermediateToBaseMultiplier;

        long packs = totalBaseUnits / packFactor;
        long remainderAfterPacks = totalBaseUnits % packFactor;

        long strips = remainderAfterPacks / stripFactor;
        long looseBase = remainderAfterPacks % stripFactor;

        return new DecomposeResult(totalBaseUnits, packs, config.packUnitLabel, strips, config.intermediateUnitLabel, looseBase, config.baseUnit);
    }

    public List<String> validateDispenseQuantity(BigDecimal quantity, StrategyConfig config) {
        List<String> errors = new ArrayList<>();
        if (config == null) {
            errors.add("ERR_STRATEGY_NULL");
            return errors;
        }
        if (quantity == null || quantity.compareTo(config.minimumDispenseQuantity) < 0) {
            errors.add("ERR_BELOW_MINIMUM_DISPENSE_QTY");
            return errors;
        }

        if (config.isIndivisible) {
            if (quantity.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
                errors.add("ERR_INDIVISIBLE_UNIT_FRACTION");
            }
        } else {
            int scale = quantity.stripTrailingZeros().scale();
            if (scale > config.decimalPlacesAllowed) {
                errors.add("ERR_EXCEEDS_DECIMAL_PRECISION");
            }
        }
        return errors;
    }
}

