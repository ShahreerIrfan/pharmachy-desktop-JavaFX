package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PosStrengthTaxonomyClient
 * Architecture Decision: ADR-0123 (P123).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * Client-side integration for JavaFX Desktop POS managing strength measurement taxonomy,
 * canonical unit classifications, parsing composite strings, and preventing incompatible unit conversions.
 */
public class PosStrengthTaxonomyClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public enum MeasurementFamily {
        MASS("Mass / Weight", "ওজন / ভর"),
        VOLUME("Liquid Volume", "তরল আয়তন"),
        PERCENTAGE("Percentage Concentration", "শতাংশ মাত্রা"),
        CONCENTRATION("Dosing Concentration", "ডোজের মাত্রা"),
        BIOLOGICAL("Biological Units", "বায়োলজিক্যাল ইউনিট");

        private final String labelEn;
        private final String labelBn;

        MeasurementFamily(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
    }

    public static final class UnitDefinition {
        public final String unitCode;
        public final MeasurementFamily family;
        public final BigDecimal ratioToBase;
        public final String nameEn;
        public final String nameBn;

        public UnitDefinition(String unitCode, MeasurementFamily family, BigDecimal ratioToBase, String nameEn, String nameBn) {
            this.unitCode = unitCode;
            this.family = family;
            this.ratioToBase = ratioToBase;
            this.nameEn = nameEn;
            this.nameBn = nameBn;
        }
    }

    public static final class ParsedStrength {
        public final String rawStrength;
        public final BigDecimal numericValue;
        public final String unit;
        public final MeasurementFamily family;
        public final boolean isComposite;

        public ParsedStrength(String rawStrength, BigDecimal numericValue, String unit, MeasurementFamily family, boolean isComposite) {
            this.rawStrength = rawStrength;
            this.numericValue = numericValue;
            this.unit = unit;
            this.family = family;
            this.isComposite = isComposite;
        }
    }

    public static final class ConversionResult {
        public final UiStatus uiStatus;
        public final BigDecimal originalValue;
        public final String fromUnit;
        public final MeasurementFamily fromFamily;
        public final BigDecimal convertedValue;
        public final String toUnit;
        public final MeasurementFamily toFamily;
        public final boolean isCompatible;
        public final String formattedResult;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        private ConversionResult(
                UiStatus uiStatus,
                BigDecimal originalValue,
                String fromUnit,
                MeasurementFamily fromFamily,
                BigDecimal convertedValue,
                String toUnit,
                MeasurementFamily toFamily,
                boolean isCompatible,
                String formattedResult,
                String errorCode,
                String messageEn,
                String messageBn) {
            this.uiStatus = uiStatus;
            this.originalValue = originalValue;
            this.fromUnit = fromUnit;
            this.fromFamily = fromFamily;
            this.convertedValue = convertedValue;
            this.toUnit = toUnit;
            this.toFamily = toFamily;
            this.isCompatible = isCompatible;
            this.formattedResult = formattedResult;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static ConversionResult success(
                BigDecimal originalValue,
                String fromUnit,
                MeasurementFamily fromFamily,
                BigDecimal convertedValue,
                String toUnit,
                MeasurementFamily toFamily) {
            String formatted = convertedValue.stripTrailingZeros().toPlainString() + " " + toUnit;
            return new ConversionResult(
                    UiStatus.SUCCESS,
                    originalValue,
                    fromUnit,
                    fromFamily,
                    convertedValue,
                    toUnit,
                    toFamily,
                    true,
                    formatted,
                    null,
                    "Converted successfully.",
                    "সফলভাবে রূপান্তর সম্পন্ন হয়েছে।"
            );
        }

        public static ConversionResult error(UiStatus status, String code, String msgEn, String msgBn) {
            return new ConversionResult(
                    status,
                    BigDecimal.ZERO,
                    "",
                    null,
                    BigDecimal.ZERO,
                    "",
                    null,
                    false,
                    "",
                    code,
                    msgEn,
                    msgBn
            );
        }
    }

    private final Map<String, UnitDefinition> unitRegistry = new HashMap<>();

    public PosStrengthTaxonomyClient() {
        initDefaultTaxonomy();
    }

    private void initDefaultTaxonomy() {
        // MASS (Base: mg)
        registerUnit("mg", MeasurementFamily.MASS, new BigDecimal("1.0"), "Milligram", "মিলিগ্রাম");
        registerUnit("g", MeasurementFamily.MASS, new BigDecimal("1000.0"), "Gram", "গ্রাম");
        registerUnit("mcg", MeasurementFamily.MASS, new BigDecimal("0.001"), "Microgram", "মাইক্রোগ্রাম");
        registerUnit("ug", MeasurementFamily.MASS, new BigDecimal("0.001"), "Microgram (ug)", "মাইক্রোগ্রাম");

        // VOLUME (Base: mL)
        registerUnit("ml", MeasurementFamily.VOLUME, new BigDecimal("1.0"), "Milliliter", "মিলিলিটার");
        registerUnit("l", MeasurementFamily.VOLUME, new BigDecimal("1000.0"), "Liter", "লিটার");

        // PERCENTAGE (Base: %)
        registerUnit("%", MeasurementFamily.PERCENTAGE, new BigDecimal("1.0"), "Percentage w/w or w/v", "শতাংশ মাত্রা");

        // CONCENTRATION
        registerUnit("mg/5ml", MeasurementFamily.CONCENTRATION, new BigDecimal("1.0"), "Milligrams per 5 mL", "মিলিগ্রাম প্রতি ৫ মিলি");
        registerUnit("mg/ml", MeasurementFamily.CONCENTRATION, new BigDecimal("5.0"), "Milligrams per mL", "মিলিগ্রাম প্রতি মিলি");
        registerUnit("mcg/ml", MeasurementFamily.CONCENTRATION, new BigDecimal("0.005"), "Micrograms per mL", "মাইক্রোগ্রাম প্রতি মিলি");

        // BIOLOGICAL
        registerUnit("iu", MeasurementFamily.BIOLOGICAL, new BigDecimal("1.0"), "International Unit", "আন্তর্জাতিক ইউনিট");
        registerUnit("iu/ml", MeasurementFamily.BIOLOGICAL, new BigDecimal("1.0"), "International Units per mL", "আইইউ প্রতি মিলি");
    }

    public void registerUnit(String code, MeasurementFamily family, BigDecimal ratio, String nameEn, String nameBn) {
        String normalized = normalizeUnitCode(code);
        unitRegistry.put(normalized, new UnitDefinition(normalized, family, ratio, nameEn, nameBn));
    }

    public String normalizeUnitCode(String code) {
        if (code == null) return "";
        String s = code.trim().toLowerCase();
        if (s.equals("%") || s.equals("percent") || s.equals("percentage")) {
            return "%";
        }
        return s;
    }

    public Optional<UnitDefinition> getUnit(String code) {
        return Optional.ofNullable(unitRegistry.get(normalizeUnitCode(code)));
    }

    public Optional<MeasurementFamily> getUnitFamily(String code) {
        return getUnit(code).map(u -> u.family);
    }

    public boolean isConversionCompatible(String fromUnit, String toUnit) {
        Optional<UnitDefinition> fromDef = getUnit(fromUnit);
        Optional<UnitDefinition> toDef = getUnit(toUnit);
        if (fromDef.isEmpty() || toDef.isEmpty()) {
            return false;
        }
        return fromDef.get().family == toDef.get().family;
    }

    /**
     * Converts strength from one unit to another, strictly preventing cross-family unit conversions.
     */
    public ConversionResult convertStrength(BigDecimal value, String fromUnit, String toUnit) {
        if (value == null) {
            return ConversionResult.error(
                    UiStatus.ERROR,
                    "ERR_INVALID_NUMERIC_VALUE",
                    "Strength numeric value cannot be null.",
                    "স্ট্রেংথ সংখ্যা মান খালি রাখা যাবে না।"
            );
        }

        String normFrom = normalizeUnitCode(fromUnit);
        String normTo = normalizeUnitCode(toUnit);

        Optional<UnitDefinition> fromOpt = getUnit(normFrom);
        Optional<UnitDefinition> toOpt = getUnit(normTo);

        if (fromOpt.isEmpty()) {
            return ConversionResult.error(
                    UiStatus.ERROR,
                    "ERR_UNKNOWN_STRENGTH_UNIT",
                    "Source unit '" + fromUnit + "' is not registered in the taxonomy.",
                    "উৎস পরিমাপের ইউনিটটি অনুমোদিত নয়।"
            );
        }

        if (toOpt.isEmpty()) {
            return ConversionResult.error(
                    UiStatus.ERROR,
                    "ERR_UNKNOWN_STRENGTH_UNIT",
                    "Target unit '" + toUnit + "' is not registered in the taxonomy.",
                    "লক্ষ্য পরিমাপের ইউনিটটি অনুমোদিত নয়।"
            );
        }

        UnitDefinition fromDef = fromOpt.get();
        UnitDefinition toDef = toOpt.get();

        // INCOMPATIBLE UNIT CONVERSION GUARD
        if (fromDef.family != toDef.family) {
            return ConversionResult.error(
                    UiStatus.ERROR,
                    "ERR_INCOMPATIBLE_UNIT_CONVERSION",
                    String.format("Incompatible unit conversion from '%s' (%s) to '%s' (%s). Cross-family conversion is strictly forbidden.",
                            fromUnit, fromDef.family.name(), toUnit, toDef.family.name()),
                    "অসঙ্গতিপূর্ণ পরিমাপের ইউনিট রূপান্তর সম্পূর্ণ নিষিদ্ধ।"
            );
        }

        // Conversion calculation via base unit
        BigDecimal baseVal = value.multiply(fromDef.ratioToBase);
        BigDecimal targetVal = baseVal.divide(toDef.ratioToBase, 6, RoundingMode.HALF_UP).stripTrailingZeros();

        return ConversionResult.success(
                value,
                normFrom,
                fromDef.family,
                targetVal,
                normTo,
                toDef.family
        );
    }

    /**
     * Parses human-readable composite strength into normalized numeric value, unit, and family.
     * e.g. "500 mg" -> 500, "mg", MASS
     */
    public ParsedStrength parseStrengthString(String rawStrength) {
        if (rawStrength == null || rawStrength.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_INVALID_STRENGTH_STRING: Strength string cannot be empty.");
        }

        String cleaned = rawStrength.trim();
        Pattern pattern = Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z%]+(?:/[0-9]*[a-zA-Z]+)?)\\s*$");
        Matcher matcher = pattern.matcher(cleaned);

        if (!matcher.matches()) {
            // Check composite fallback (e.g. 500mg+65mg)
            Pattern compPattern = Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z%]+)");
            Matcher compMatcher = compPattern.matcher(cleaned);
            if (compMatcher.find()) {
                BigDecimal val = new BigDecimal(compMatcher.group(1));
                String unit = normalizeUnitCode(compMatcher.group(2));
                MeasurementFamily fam = getUnitFamily(unit).orElse(MeasurementFamily.MASS);
                return new ParsedStrength(cleaned, val, unit, fam, true);
            }
            throw new IllegalArgumentException("ERR_UNPARSEABLE_STRENGTH: Could not parse strength from '" + cleaned + "'.");
        }

        BigDecimal val = new BigDecimal(matcher.group(1));
        String unit = normalizeUnitCode(matcher.group(2));
        MeasurementFamily fam = getUnitFamily(unit).orElseThrow(() ->
                new IllegalArgumentException("ERR_UNKNOWN_STRENGTH_UNIT: Unrecognized strength unit '" + unit + "'."));

        return new ParsedStrength(cleaned, val, unit, fam, false);
    }

    public List<UnitDefinition> getUnitsByFamily(MeasurementFamily family) {
        List<UnitDefinition> list = new ArrayList<>();
        for (UnitDefinition def : unitRegistry.values()) {
            if (def.family == family) {
                list.add(def);
            }
        }
        list.sort(Comparator.comparing(u -> u.unitCode));
        return list;
    }
}
