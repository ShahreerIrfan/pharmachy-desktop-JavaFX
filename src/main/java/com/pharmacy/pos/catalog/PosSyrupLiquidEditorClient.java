package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.util.*;

/**
 * PosSyrupLiquidEditorClient
 * Architecture Decision: ADR-0142 (P142).
 * Phase 08: Syrup Liquid Editor & Oral Liquid Dispensing Units.
 *
 * Client-side integration for JavaFX Desktop POS managing oral liquid dosage forms
 * (syrups, suspensions, oral drops), concentration-based strength dimensions,
 * nominal bottle volumes, dry syrup reconstitution water, and zero-trust solid packaging isolation.
 */
public class PosSyrupLiquidEditorClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public enum LiquidDosageForm {
        SYRUP("Syrup", "সিরাপ"),
        SUSPENSION("Suspension", "সাসপেনশন"),
        ORAL_SOLUTION("Oral Solution", "ওরাল সল্যুশন"),
        ELIXIR("Elixir", "এলিক্সির"),
        ORAL_EMULSION("Oral Emulsion", "ওরাল ইমালশন"),
        ORAL_DROPS("Oral Drops", "ওরাল ড্রপস"),
        DRY_SYRUP_RECONSTITUTED("Dry Syrup / Powder for Suspension", "ড্রাই সিরাপ / পাউডার সাসপেনশন");

        private final String labelEn;
        private final String labelBn;

        LiquidDosageForm(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
    }

    public enum LiquidConcentrationUnit {
        MG_PER_5ML("mg/5ml", "মিগ্রা/৫মি.লি."),
        MG_PER_ML("mg/ml", "মিগ্রা/মি.লি."),
        PERCENT_W_V("%", "% (w/v)"),
        G_PER_100ML("g/100ml", "গ্রাম/১০০মি.লি."),
        MCG_PER_5ML("mcg/5ml", "মাইক্রোগ্রাম/৫মি.লি."),
        IU_PER_5ML("iu/5ml", "আইইউ/৫মি.লি."),
        MG_PER_10ML("mg/10ml", "মিগ্রা/১০মি.লি.");

        private final String unitValue;
        private final String labelBn;

        LiquidConcentrationUnit(String unitValue, String labelBn) {
            this.unitValue = unitValue;
            this.labelBn = labelBn;
        }

        public String getUnitValue() { return unitValue; }
        public String getLabelBn() { return labelBn; }
    }

    public enum LiquidRoute {
        ORAL("Oral", "মুখে সেব্য"),
        ORAL_GAVAGE("Oral Gavage", "গেভেজ নল দ্বারা সেব্য"),
        SUBLINGUAL_DROPS("Sublingual Drops", "জিহ্বার নিচে ড্রপস");

        private final String labelEn;
        private final String labelBn;

        LiquidRoute(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
    }

    public enum LiquidSaleUnit {
        BOTTLE("Bottle", "বোতল"),
        PACK("Pack", "প্যাক"),
        PHIAL("Phial", "ফায়াল");

        private final String labelEn;
        private final String labelBn;

        LiquidSaleUnit(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
    }

    public enum LiquidStoragePolicy {
        ROOM_TEMPERATURE_BELOW_30C("Room Temperature (Below 30°C)", "স্বাভাবিক তাপমাত্রা (<৩০°সে)"),
        COOL_PLACE_15_25C("Cool Place (15°C - 25°C)", "শীতল স্থান (১৫°সে - ২৫°সে)"),
        DO_NOT_FREEZE("Do Not Freeze", "জমাট বাঁধাবেন না"),
        REFRIGERATE_AFTER_RECONSTITUTION_2_8C("Refrigerate After Reconstitution (2°C - 8°C)", "পানি মিশ্রণের পর রেফ্রিজারেটরে রাখুন (২°সে - ৮°সে)"),
        PROTECT_FROM_DIRECT_SUNLIGHT("Protect From Direct Sunlight", "সরাসরি সূর্যালোক থেকে সুরক্ষিত রাখুন");

        private final String labelEn;
        private final String labelBn;

        LiquidStoragePolicy(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
    }

    public static final Set<String> HIDDEN_FIELDS = Set.of(
        "strips_per_box",
        "tablets_per_strip",
        "units_per_strip",
        "box_size",
        "loose_sale_eligible",
        "is_scored",
        "coating_type",
        "needle_gauge"
    );

    public static final class SyrupLiquidConfigDto {
        public final String configId;
        public final String productId;
        public final String brandName;
        public final String genericName;
        public final String tenantId;
        public final String dosageForm;
        public final BigDecimal concentrationValue;
        public final String concentrationUnit;
        public final BigDecimal bottleVolumeMl;
        public final String saleUnit;
        public final String route;
        public final String storagePolicy;
        public final String measuringDevice;
        public final boolean isReconstitutionRequired;
        public final BigDecimal reconstitutionWaterMl;
        public final Integer shelfLifeDaysAfterReconstitution;
        public final String flavor;
        public final boolean isActive;

        public SyrupLiquidConfigDto(
            String configId, String productId, String brandName, String genericName,
            String tenantId, String dosageForm, BigDecimal concentrationValue, String concentrationUnit,
            BigDecimal bottleVolumeMl, String saleUnit, String route, String storagePolicy,
            String measuringDevice, boolean isReconstitutionRequired, BigDecimal reconstitutionWaterMl,
            Integer shelfLifeDaysAfterReconstitution, String flavor, boolean isActive
        ) {
            this.configId = configId;
            this.productId = productId;
            this.brandName = brandName;
            this.genericName = genericName;
            this.tenantId = tenantId;
            this.dosageForm = dosageForm;
            this.concentrationValue = concentrationValue;
            this.concentrationUnit = concentrationUnit;
            this.bottleVolumeMl = bottleVolumeMl;
            this.saleUnit = saleUnit;
            this.route = route;
            this.storagePolicy = storagePolicy;
            this.measuringDevice = measuringDevice;
            this.isReconstitutionRequired = isReconstitutionRequired;
            this.reconstitutionWaterMl = reconstitutionWaterMl;
            this.shelfLifeDaysAfterReconstitution = shelfLifeDaysAfterReconstitution;
            this.flavor = flavor;
            this.isActive = isActive;
        }
    }

    public static final class ValidationResult {
        public final boolean isValid;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public ValidationResult(boolean isValid, String errorCode, String messageEn, String messageBn) {
            this.isValid = isValid;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null, "Validation passed.", "যাচাইকরণ সফল হয়েছে।");
        }

        public static ValidationResult error(String errorCode, String messageEn, String messageBn) {
            return new ValidationResult(false, errorCode, messageEn, messageBn);
        }
    }

    private final Map<String, SyrupLiquidConfigDto> localCache = new LinkedHashMap<>();
    private boolean isOfflineMode = false;

    public void setOfflineMode(boolean offlineMode) {
        this.isOfflineMode = offlineMode;
    }

    public boolean isOfflineMode() {
        return isOfflineMode;
    }

    public boolean isFieldHidden(String fieldName) {
        if (fieldName == null) return false;
        return HIDDEN_FIELDS.contains(fieldName.toLowerCase().trim());
    }

    public boolean isStrengthDimensionValid(String unit) {
        if (unit == null) return false;
        String clean = unit.trim().toLowerCase();
        for (LiquidConcentrationUnit cu : LiquidConcentrationUnit.values()) {
            if (cu.getUnitValue().equalsIgnoreCase(clean)) {
                return true;
            }
        }
        return false;
    }

    public ValidationResult validatePayload(Map<String, Object> payload) {
        if (payload == null) {
            return ValidationResult.error("ERR_INVALID_PAYLOAD", "Payload cannot be null.", "পে-লোড খালি হতে পারবে না।");
        }

        // 1. Zero-Trust Hidden Field Guard (Reject solid oral tablet/strip fields)
        for (String hidden : HIDDEN_FIELDS) {
            if (payload.containsKey(hidden) && payload.get(hidden) != null) {
                return ValidationResult.error(
                    "ERR_HIDDEN_FIELD_VALUE_REJECTED",
                    "Field '" + hidden + "' is prohibited for oral liquid and syrup forms.",
                    "সিরাপ বা তরল ওষুধের জন্য '" + hidden + "' ফিল্ড অনুমোদিত নয়।"
                );
            }
        }

        // 2. Required Fields
        List<String> required = List.of("dosage_form", "concentration_value", "concentration_unit", "bottle_volume_ml", "sale_unit", "route", "storage_policy");
        for (String req : required) {
            if (!payload.containsKey(req) || payload.get(req) == null || payload.get(req).toString().isBlank()) {
                return ValidationResult.error(
                    "ERR_REQUIRED_FIELD_MISSING",
                    "Missing mandatory field '" + req + "'.",
                    "বাধ্যতামূলক ফিল্ড '" + req + "' অনুপস্থিত।"
                );
            }
        }

        // 3. Concentration Unit & Dimension Validation
        String unit = payload.get("concentration_unit").toString().toLowerCase().trim();
        if (!isStrengthDimensionValid(unit)) {
            return ValidationResult.error(
                "ERR_INVALID_STRENGTH_DIMENSION_FOR_LIQUID",
                "Concentration unit '" + unit + "' lacks liquid volumetric denominator (allowed: mg/5ml, mg/ml, %, etc.).",
                "তরল ওষুধের জন্য '" + unit + "' ঘনমাত্রার একক গ্রহণযোগ্য নয় (অনুমোদিত: mg/5ml, mg/ml, % ইত্যাদি)।"
            );
        }

        // 4. Bottle Volume Validation
        try {
            BigDecimal vol = new BigDecimal(payload.get("bottle_volume_ml").toString());
            if (vol.compareTo(BigDecimal.ZERO) <= 0) {
                return ValidationResult.error(
                    "ERR_INVALID_BOTTLE_VOLUME",
                    "bottle_volume_ml must be greater than 0.",
                    "বোতলের আয়তন অবশ্যই ০-এর বেশি হতে হবে।"
                );
            }
        } catch (Exception e) {
            return ValidationResult.error(
                "ERR_INVALID_BOTTLE_VOLUME",
                "bottle_volume_ml must be a valid number.",
                "বোতলের আয়তন একটি সঠিক সংখ্যা হতে হবে।"
            );
        }

        // 5. Sale Unit Validation
        String saleUnit = payload.get("sale_unit").toString().trim();
        if (!Set.of("Bottle", "Pack", "Phial").contains(saleUnit)) {
            return ValidationResult.error(
                "ERR_INVALID_SALE_UNIT_FOR_LIQUID",
                "Sale unit '" + saleUnit + "' is invalid for oral liquids.",
                "তরল ওষুধের জন্য '" + saleUnit + "' বিক্রয় একক সঠিক নয়।"
            );
        }

        // 6. Oral Route Validation
        String route = payload.get("route").toString().trim();
        if (!Set.of("Oral", "Oral Gavage", "Sublingual Drops").contains(route)) {
            return ValidationResult.error(
                "ERR_INVALID_ROUTE_FOR_DOSAGE_FORM",
                "Route '" + route + "' is invalid for oral liquid forms.",
                "সিরাপ বা তরল ওষুধের জন্য '" + route + "' সেবনের মাধ্যমটি গ্রহণযোগ্য নয়।"
            );
        }

        // 7. Reconstitution Water Volume Validation
        boolean isRecon = Boolean.parseBoolean(payload.getOrDefault("is_reconstitution_required", "false").toString());
        String form = payload.get("dosage_form").toString().toUpperCase();
        if ("DRY_SYRUP_RECONSTITUTED".equals(form)) {
            isRecon = true;
        }

        if (isRecon) {
            Object waterObj = payload.get("reconstitution_water_ml");
            if (waterObj == null || waterObj.toString().isBlank()) {
                return ValidationResult.error(
                    "ERR_RECONSTITUTION_WATER_REQUIRED",
                    "reconstitution_water_ml is mandatory when reconstitution is required.",
                    "ড্রাই সিরাপ বা সাসপেনশনের জন্য মিশ্রণযোগ্য পানির পরিমাণ (মি.লি.) উল্লেখ করা আবশ্যক।"
                );
            }
            try {
                BigDecimal water = new BigDecimal(waterObj.toString());
                if (water.compareTo(BigDecimal.ZERO) <= 0) {
                    return ValidationResult.error(
                        "ERR_RECONSTITUTION_WATER_REQUIRED",
                        "reconstitution_water_ml must be greater than 0 mL.",
                        "মিশ্রণযোগ্য পানির পরিমাণ অবশ্যই ০ মি.লি.-এর বেশি হতে হবে।"
                    );
                }
            } catch (Exception e) {
                return ValidationResult.error(
                    "ERR_RECONSTITUTION_WATER_REQUIRED",
                    "reconstitution_water_ml must be a valid number.",
                    "পানির পরিমাণ একটি সঠিক সংখ্যা হতে হবে।"
                );
            }
        }

        return ValidationResult.valid();
    }

    public void cacheConfig(SyrupLiquidConfigDto dto) {
        if (dto != null && dto.productId != null) {
            localCache.put(dto.productId, dto);
        }
    }

    public SyrupLiquidConfigDto getCachedConfig(String productId) {
        return localCache.get(productId);
    }

    public List<SyrupLiquidConfigDto> getLocalConfigs() {
        return new ArrayList<>(localCache.values());
    }

    public String getLabel(String key, String lang) {
        boolean isBn = "bn".equalsIgnoreCase(lang);
        switch (key) {
            case "concentration_value":
                return isBn ? "ঘনমাত্রার মান" : "Concentration Value";
            case "concentration_unit":
                return isBn ? "ঘনমাত্রার একক" : "Concentration Unit";
            case "bottle_volume_ml":
                return isBn ? "বোতলের আয়তন (মি.লি.)" : "Bottle Volume (mL)";
            case "sale_unit":
                return isBn ? "বিক্রয় একক" : "Sale Unit";
            case "route":
                return isBn ? "সেবনের মাধ্যম" : "Route";
            case "storage_policy":
                return isBn ? "সংরক্ষণ নীতি" : "Storage Policy";
            case "reconstitution_water_ml":
                return isBn ? "মিশ্রণযোগ্য পানির পরিমাণ (মি.লি.)" : "Reconstitution Water (mL)";
            default:
                return key;
        }
    }
}
