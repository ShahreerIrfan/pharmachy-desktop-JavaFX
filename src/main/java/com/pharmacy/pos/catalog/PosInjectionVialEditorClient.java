package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.util.*;

/**
 * PosInjectionVialEditorClient
 * Architecture Decision: ADR-0144 (P144).
 * Phase 08: Specialized Dosage-Form Editors & Multi-Tier Packaging.
 *
 * Client-side integration for JavaFX Desktop POS managing parenteral injectable dosage forms
 * (vials, ampoules, infusions), ingredient strength, fill volumes, sterile parenteral routes,
 * single-dose preservative prohibitions, diluent reconstitution, and cold-chain temperature monitoring.
 */
public class PosInjectionVialEditorClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public enum InjectionDosageForm {
        INJECTION_SOLUTION("Injection Solution", "ইনজেকশন দ্রবণ (সলিউশন)"),
        INJECTION_SUSPENSION("Injection Suspension", "ইনজেকশন সাসপেনশন"),
        INJECTION_POWDER_LYOPHILIZED("Injection Powder (Lyophilized)", "লাইওফিলাইজড পাউডার ইনজেকশন"),
        INFUSION_IV("Intravenous Infusion", "ইন্ট্রাভেনাস ইনফিউশন");

        private final String labelEn;
        private final String labelBn;

        InjectionDosageForm(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
    }

    public enum InjectionContainer {
        VIAL_SINGLE_DOSE("Single-Dose Vial", "একক-ডোজ ভায়াল"),
        VIAL_MULTI_DOSE("Multi-Dose Vial", "মাল্টি-ডোজ ভায়াল"),
        AMPOULE_GLASS("Glass Ampoule", "কাঁচের অ্যাম্পুল"),
        PREFILLED_SYRINGE("Pre-filled Syringe", "প্রি-ফিল্ড সিরিঞ্জ"),
        INFUSION_BOTTLE("Infusion Bottle", "ইনফিউশন বোতল / ব্যাগ"),
        CARTRIDGE("Cartridge", "কার্টিজ (ইনসুলিন / ডেন্টাল)");

        private final String labelEn;
        private final String labelBn;

        InjectionContainer(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
    }

    public enum InjectionSterileRoute {
        IV("Intravenous (IV)", "শিরায় প্রয়োগ (আইভি)"),
        IM("Intramuscular (IM)", "মাংসপেশীতে প্রয়োগ (আইএম)"),
        SC("Subcutaneous (SC)", "ত্বকের নিচে প্রয়োগ (এসসি)"),
        ID("Intradermal (ID)", "ত্বকের স্তরে প্রয়োগ (আইডি)"),
        INTRAVITREAL("Intravitreal", "চোখের ভিট্রিয়াসে প্রয়োগ"),
        EPIDURAL("Epidural", "এপিডুরাল প্রয়োগ");

        private final String labelEn;
        private final String labelBn;

        InjectionSterileRoute(String labelEn, String labelBn) {
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

    public static final Set<String> ALLOWED_STERILE_ROUTES = Set.of(
        "IV", "IM", "SC", "ID", "INTRAVITREAL", "EPIDURAL"
    );

    public static final Set<String> ALLOWED_STRENGTH_UNITS = Set.of(
        "mg/ml", "mcg/ml", "g/ml", "IU/ml", "mg/vial", "g/vial", "%"
    );

    public static final class InjectionVialDto {
        public final String configId;
        public final String productId;
        public final String brandName;
        public final String genericName;
        public final String tenantId;
        public final String dosageForm;
        public final String containerType;
        public final BigDecimal strengthValue;
        public final String strengthUnit;
        public final BigDecimal fillVolumeMl;
        public final String sterileRoute;
        public final boolean isColdChainRequired;
        public final BigDecimal minStorageTempCelsius;
        public final BigDecimal maxStorageTempCelsius;
        public final boolean hasPreservative;
        public final boolean requiresDiluent;
        public final String diluentType;
        public final BigDecimal diluentVolumeMl;
        public final boolean isActive;

        public InjectionVialDto(
            String configId, String productId, String brandName, String genericName,
            String tenantId, String dosageForm, String containerType,
            BigDecimal strengthValue, String strengthUnit, BigDecimal fillVolumeMl,
            String sterileRoute, boolean isColdChainRequired,
            BigDecimal minStorageTempCelsius, BigDecimal maxStorageTempCelsius,
            boolean hasPreservative, boolean requiresDiluent,
            String diluentType, BigDecimal diluentVolumeMl, boolean isActive
        ) {
            this.configId = configId;
            this.productId = productId;
            this.brandName = brandName;
            this.genericName = genericName;
            this.tenantId = tenantId;
            this.dosageForm = dosageForm;
            this.containerType = containerType;
            this.strengthValue = strengthValue;
            this.strengthUnit = strengthUnit;
            this.fillVolumeMl = fillVolumeMl;
            this.sterileRoute = sterileRoute;
            this.isColdChainRequired = isColdChainRequired;
            this.minStorageTempCelsius = minStorageTempCelsius;
            this.maxStorageTempCelsius = maxStorageTempCelsius;
            this.hasPreservative = hasPreservative;
            this.requiresDiluent = requiresDiluent;
            this.diluentType = diluentType;
            this.diluentVolumeMl = diluentVolumeMl;
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

    private final Map<String, InjectionVialDto> localCache = new LinkedHashMap<>();
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

    public ValidationResult validatePayload(Map<String, Object> payload) {
        if (payload == null) {
            return ValidationResult.error("ERR_INVALID_PAYLOAD", "Payload cannot be null.", "পে-লোড খালি হতে পারবে না।");
        }

        // 1. Zero-Trust Hidden Field Guard (Reject solid oral tablet/strip fields)
        for (String hidden : HIDDEN_FIELDS) {
            if (payload.containsKey(hidden) && payload.get(hidden) != null) {
                return ValidationResult.error(
                    "ERR_HIDDEN_FIELD_VALUE_REJECTED",
                    "Field '" + hidden + "' is prohibited for injection vial forms.",
                    "ইনজেকশন ওষুধের জন্য '" + hidden + "' ফিল্ড অনুমোদিত নয়।"
                );
            }
        }

        // 2. Required Fields
        List<String> required = List.of(
            "dosage_form", "container_type", "strength_value", "strength_unit", "fill_volume_ml", "sterile_route"
        );
        for (String req : required) {
            if (!payload.containsKey(req) || payload.get(req) == null || payload.get(req).toString().isBlank()) {
                return ValidationResult.error(
                    "ERR_REQUIRED_FIELD_MISSING",
                    "Missing mandatory field '" + req + "'.",
                    "বাধ্যতামূলক ফিল্ড '" + req + "' অনুপস্থিত।"
                );
            }
        }

        // 3. Sterile Route Validation
        String route = payload.get("sterile_route").toString().trim().toUpperCase();
        if (!ALLOWED_STERILE_ROUTES.contains(route)) {
            return ValidationResult.error(
                "ERR_INVALID_STERILE_ROUTE",
                "Route '" + route + "' is invalid for injectables. Oral routes are strictly prohibited.",
                "ইনজেকশনের জন্য রুট '" + route + "' গ্রহণযোগ্য নয়। ওরাল রুট নিষিদ্ধ।"
            );
        }

        // 4. Strength Unit Validation
        String unit = payload.get("strength_unit").toString().trim();
        if (!ALLOWED_STRENGTH_UNITS.contains(unit)) {
            return ValidationResult.error(
                "ERR_INVALID_STRENGTH_UNIT",
                "Strength unit '" + unit + "' is invalid for injectables.",
                "ইনজেকশনের জন্য মাত্রার একক '" + unit + "' গ্রহণযোগ্য নয়।"
            );
        }

        // 5. Strength Value & Fill Volume
        try {
            BigDecimal strengthVal = new BigDecimal(payload.get("strength_value").toString());
            if (strengthVal.compareTo(BigDecimal.ZERO) <= 0) {
                return ValidationResult.error(
                    "ERR_INVALID_STRENGTH_VALUE",
                    "strength_value must be greater than 0.",
                    "উপাদানের মাত্রা ০ এর চেয়ে বেশি হতে হবে।"
                );
            }
        } catch (Exception ex) {
            return ValidationResult.error("ERR_INVALID_STRENGTH_VALUE", "strength_value must be a valid number.", "উপাদানের মাত্রা সঠিক সংখ্যা হতে হবে।");
        }

        try {
            BigDecimal fillVol = new BigDecimal(payload.get("fill_volume_ml").toString());
            if (fillVol.compareTo(BigDecimal.ZERO) <= 0) {
                return ValidationResult.error(
                    "ERR_INVALID_FILL_VOLUME",
                    "fill_volume_ml must be greater than 0 mL.",
                    "ফিল ভলিউম ০ এর চেয়ে বেশি হতে হবে।"
                );
            }
        } catch (Exception ex) {
            return ValidationResult.error("ERR_INVALID_FILL_VOLUME", "fill_volume_ml must be a valid number.", "ফিল ভলিউম সঠিক সংখ্যা হতে হবে।");
        }

        // 6. Cold Chain Validation
        boolean isColdChain = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("is_cold_chain_required", false)));
        if (isColdChain) {
            Object rawMin = payload.get("min_storage_temp_celsius");
            Object rawMax = payload.get("max_storage_temp_celsius");
            if (rawMin == null || rawMin.toString().isBlank() || rawMax == null || rawMax.toString().isBlank()) {
                return ValidationResult.error(
                    "ERR_INVALID_TEMPERATURE_RANGE",
                    "Both min and max storage temperatures are required when cold chain is active.",
                    "কোল্ড চেইনের ক্ষেত্রে সর্বনিম্ন ও সর্বোচ্চ তাপমাত্রা আবশ্যক।"
                );
            }
            try {
                BigDecimal minTemp = new BigDecimal(rawMin.toString());
                BigDecimal maxTemp = new BigDecimal(rawMax.toString());
                if (minTemp.compareTo(maxTemp) >= 0) {
                    return ValidationResult.error(
                        "ERR_INVALID_TEMPERATURE_RANGE",
                        "min_storage_temp_celsius (" + minTemp + "°C) must be strictly less than max_storage_temp_celsius (" + maxTemp + "°C).",
                        "সর্বনিম্ন তাপমাত্রা (" + minTemp + "°সে) সর্বোচ্চ তাপমাত্রা (" + maxTemp + "°সে) থেকে কম হতে হবে।"
                    );
                }
            } catch (Exception ex) {
                return ValidationResult.error("ERR_INVALID_TEMPERATURE_RANGE", "Storage temperatures must be valid numbers.", "তাপমাত্রা সঠিক সংখ্যা হতে হবে।");
            }
        }

        // 7. Single-Dose Ampoule Preservative Prohibition
        String containerType = payload.get("container_type").toString().trim().toUpperCase();
        boolean hasPreservative = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("has_preservative", false)));
        if ("AMPOULE_GLASS".equals(containerType) && hasPreservative) {
            return ValidationResult.error(
                "ERR_SINGLE_DOSE_PRESERVATIVE_PROHIBITED",
                "Single-dose glass ampoules cannot contain antimicrobial preservatives.",
                "একক-ডোজ কাঁচের অ্যাম্পুলে প্রিজারভেটিভ ব্যবহার নিষিদ্ধ।"
            );
        }

        // 8. Lyophilized Diluent Requirements
        String dosageForm = payload.get("dosage_form").toString().trim().toUpperCase();
        boolean requiresDiluent = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("requires_diluent", false)));
        if ("INJECTION_POWDER_LYOPHILIZED".equals(dosageForm)) {
            requiresDiluent = true;
        }

        if (requiresDiluent) {
            Object rawDilVol = payload.get("diluent_volume_ml");
            if (rawDilVol == null || rawDilVol.toString().isBlank()) {
                return ValidationResult.error(
                    "ERR_DILUENT_REQUIRED",
                    "diluent_volume_ml is required for lyophilized powder injections.",
                    "পাউডার ইনজেকশনের জন্য ডিলুয়েন্টের পরিমাণ আবশ্যক।"
                );
            }
            try {
                BigDecimal dilVol = new BigDecimal(rawDilVol.toString());
                if (dilVol.compareTo(BigDecimal.ZERO) <= 0) {
                    return ValidationResult.error(
                        "ERR_DILUENT_REQUIRED",
                        "diluent_volume_ml must be greater than 0 mL.",
                        "ডিলুয়েন্টের পরিমাণ ০ এর চেয়ে বেশি হতে হবে।"
                    );
                }
            } catch (Exception ex) {
                return ValidationResult.error("ERR_DILUENT_REQUIRED", "diluent_volume_ml must be a valid number.", "ডিলুয়েন্টের পরিমাণ সঠিক সংখ্যা হতে হবে।");
            }

            String dilType = String.valueOf(payload.getOrDefault("diluent_type", "NONE")).trim().toUpperCase();
            if ("NONE".equals(dilType) || dilType.isBlank()) {
                return ValidationResult.error(
                    "ERR_DILUENT_REQUIRED",
                    "A valid diluent_type is required for lyophilized powder injections.",
                    "পাউডার ইনজেকশনের জন্য সঠিক ডিলুয়েন্টের ধরন আবশ্যক।"
                );
            }
        }

        return ValidationResult.valid();
    }

    public void putCache(String configId, InjectionVialDto dto) {
        if (configId != null && dto != null) {
            localCache.put(configId, dto);
        }
    }

    public Optional<InjectionVialDto> getFromCache(String configId) {
        return Optional.ofNullable(localCache.get(configId));
    }

    public List<InjectionVialDto> getAllCached() {
        return new ArrayList<>(localCache.values());
    }

    public void clearCache() {
        localCache.clear();
    }

    public String getLabel(String fieldKey, String lang) {
        boolean isBn = "bn".equalsIgnoreCase(lang);
        switch (fieldKey) {
            case "container_type":
                return isBn ? "কনটেইনারের ধরন" : "Container Type";
            case "fill_volume_ml":
                return isBn ? "ফিল ভলিউম (মি.লি.)" : "Fill Volume (mL)";
            case "sterile_route":
                return isBn ? "প্রয়োগের স্টেরাইল রুট" : "Sterile Route";
            case "is_cold_chain_required":
                return isBn ? "কোল্ড চেইন আবশ্যক" : "Cold-Chain Required";
            case "min_storage_temp_celsius":
                return isBn ? "সর্বনিম্ন তাপমাত্রা (°সে)" : "Min Storage Temp (°C)";
            case "max_storage_temp_celsius":
                return isBn ? "সর্বোচ্চ তাপমাত্রা (°সে)" : "Max Storage Temp (°C)";
            case "has_preservative":
                return isBn ? "প্রিজারভেটিভ সংযুক্ত" : "Contains Preservative";
            case "diluent_type":
                return isBn ? "ডিলুয়েন্টের ধরন" : "Diluent Type";
            default:
                return fieldKey;
        }
    }
}
