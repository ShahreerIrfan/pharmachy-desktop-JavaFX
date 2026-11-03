package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * PosInsulinEditorClient
 * Architecture Decision: ADR-0145 (P145).
 * Phase 08: Specialized Dosage-Form Editors & Multi-Tier Packaging.
 *
 * Client-side integration for JavaFX Desktop POS managing biopharmaceutical insulin formulations,
 * standardized concentrations in IU/mL (U-100 to U-500), pen/cartridge/vial dimension consistency,
 * dual unopened (2°C-8°C) vs in-use room-temperature stability (14-56 days), and excursion protocols.
 */
public class PosInsulinEditorClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public enum InsulinActionCategory {
        RAPID_ACTING("Rapid-Acting (Aspart / Lispro / Glulisine)", "র‌্যাপিড-অ্যাক্টিং (অ্যাসপার্ট / লিসপ্রো / গুলিসিন)"),
        SHORT_ACTING_REGULAR("Short-Acting Regular (Crystalline Human)", "শর্ট-অ্যাক্টিং রেগুলার (ক্রিস্টালাইন হিউম্যান)"),
        INTERMEDIATE_NPH("Intermediate-Acting NPH (Isophane)", "ইন্টারমিডিয়েট এনপিএইচ (আইসোফেন)"),
        LONG_ACTING_ANALOG("Long-Acting Analog (Glargine / Detemir / Degludec)", "লং-অ্যাক্টিং অ্যানালগ (গ্লার্জিন / ডিটেমির / ডেগ্লুডেক)"),
        PREMIXED_BIPHASIC("Premixed Biphasic (70/30, 50/50)", "প্রিমিক্সড বাইফ্যাসিক (৭০/৩০, ৫০/৫০)");

        private final String labelEn;
        private final String labelBn;

        InsulinActionCategory(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
    }

    public enum InsulinDeliveryDevice {
        PEN_DISPOSABLE("Pre-filled Disposable Pen (3 mL)", "ডিসপোজেবল পেন (৩ মি.লি.)"),
        CARTRIDGE_REFILL("Pen Cartridge Refill (3 mL)", "পেন কার্টিজ রিফিল (৩ মি.লি.)"),
        VIAL_MULTI_DOSE("Multi-Dose Vial (10 mL)", "মাল্টি-ডোজ ভায়াল (১০ মি.লি.)"),
        PUMP_RESERVOIR("Insulin Pump Cartridge / Reservoir", "ইনসুলিন পাম্প রিজার্ভার");

        private final String labelEn;
        private final String labelBn;

        InsulinDeliveryDevice(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
    }

    public enum InsulinExcursionAction {
        QUARANTINE_FOR_PHARMACIST_ASSESSMENT("Quarantine for Pharmacist Assessment", "ফার্মাসিস্ট মূল্যায়নের জন্য কোয়ারেন্টাইন"),
        DISPOSE_IF_FROZEN("Immediate Disposal If Frozen (<0°C)", "জমাট বাঁধলে তাৎক্ষণিক অপসারণ (<০°সে)"),
        ACCELERATE_EXPIRY_TO_IN_USE("Accelerate Expiry to In-Use Limit (28 Days)", "ব্যবহারকালীন মেয়াদে (২৮ দিন) কমিয়ে আনা"),
        RETURN_TO_SUPPLIER("Formal Return to Cold-Chain Supplier", "সরবরাহকারীকে ফেরত প্রদান");

        private final String labelEn;
        private final String labelBn;

        InsulinExcursionAction(String labelEn, String labelBn) {
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

    public static final Set<Integer> ALLOWED_CONCENTRATIONS = Set.of(100, 200, 300, 500);

    public static final class InsulinConfigDto {
        public final String configId;
        public final String productId;
        public final String brandName;
        public final String genericName;
        public final String tenantId;
        public final String actionCategory;
        public final String deliveryDevice;
        public final int concentrationIuPerMl;
        public final BigDecimal volumeMl;
        public final int totalUnits;
        public final int inUseShelfLifeDays;
        public final BigDecimal unopenedMinTempCelsius;
        public final BigDecimal unopenedMaxTempCelsius;
        public final BigDecimal inUseMaxTempCelsius;
        public final String excursionAction;
        public final String patientCounselingEn;
        public final String patientCounselingBn;
        public final boolean isActive;

        public InsulinConfigDto(
            String configId, String productId, String brandName, String genericName,
            String tenantId, String actionCategory, String deliveryDevice,
            int concentrationIuPerMl, BigDecimal volumeMl, int totalUnits,
            int inUseShelfLifeDays, BigDecimal unopenedMinTempCelsius,
            BigDecimal unopenedMaxTempCelsius, BigDecimal inUseMaxTempCelsius,
            String excursionAction, String patientCounselingEn, String patientCounselingBn,
            boolean isActive
        ) {
            this.configId = configId;
            this.productId = productId;
            this.brandName = brandName;
            this.genericName = genericName;
            this.tenantId = tenantId;
            this.actionCategory = actionCategory;
            this.deliveryDevice = deliveryDevice;
            this.concentrationIuPerMl = concentrationIuPerMl;
            this.volumeMl = volumeMl;
            this.totalUnits = totalUnits;
            this.inUseShelfLifeDays = inUseShelfLifeDays;
            this.unopenedMinTempCelsius = unopenedMinTempCelsius;
            this.unopenedMaxTempCelsius = unopenedMaxTempCelsius;
            this.inUseMaxTempCelsius = inUseMaxTempCelsius;
            this.excursionAction = excursionAction;
            this.patientCounselingEn = patientCounselingEn;
            this.patientCounselingBn = patientCounselingBn;
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

    private final Map<String, InsulinConfigDto> localCache = new LinkedHashMap<>();
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

    public int calculateTotalUnits(int concentrationIuPerMl, BigDecimal volumeMl) {
        if (volumeMl == null) return 0;
        return volumeMl.multiply(BigDecimal.valueOf(concentrationIuPerMl)).setScale(0, RoundingMode.HALF_UP).intValue();
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
                    "Field '" + hidden + "' is prohibited for insulin preparations.",
                    "ইনসুলিনের জন্য '" + hidden + "' ফিল্ড অনুমোদিত নয়।"
                );
            }
        }

        // 2. Required Fields
        List<String> required = List.of(
            "action_category", "delivery_device", "concentration_iu_per_ml",
            "volume_ml", "total_units", "in_use_shelf_life_days", "excursion_action"
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

        // 3. Concentration Validation
        int concentration;
        try {
            concentration = Integer.parseInt(payload.get("concentration_iu_per_ml").toString());
            if (!ALLOWED_CONCENTRATIONS.contains(concentration)) {
                return ValidationResult.error(
                    "ERR_INVALID_INSULIN_CONCENTRATION",
                    "Concentration " + concentration + " IU/mL is invalid. Standard strengths: " + ALLOWED_CONCENTRATIONS + ".",
                    "ইনসুলিনের ঘনমাত্রা " + concentration + " আইইউ/মি.লি. অগ্রহণযোগ্য।"
                );
            }
        } catch (Exception ex) {
            return ValidationResult.error(
                "ERR_INVALID_INSULIN_CONCENTRATION",
                "concentration_iu_per_ml must be a valid integer.",
                "ইনসুলিনের ঘনমাত্রা সঠিক সংখ্যা হতে হবে।"
            );
        }

        // 4. Volume Validation
        BigDecimal volume;
        try {
            volume = new BigDecimal(payload.get("volume_ml").toString());
            if (volume.compareTo(BigDecimal.ZERO) <= 0) {
                return ValidationResult.error(
                    "ERR_INVALID_VOLUME",
                    "volume_ml must be greater than 0 mL.",
                    "ভলিউম ০ এর চেয়ে বেশি হতে হবে।"
                );
            }
        } catch (Exception ex) {
            return ValidationResult.error("ERR_INVALID_VOLUME", "volume_ml must be a valid number.", "ভলিউম সঠিক সংখ্যা হতে হবে।");
        }

        // 5. Total Units Dimension Consistency Check
        int totalUnits;
        try {
            totalUnits = Integer.parseInt(payload.get("total_units").toString());
            if (totalUnits <= 0) {
                return ValidationResult.error(
                    "ERR_DIMENSION_CONSISTENCY_MISMATCH",
                    "total_units must be greater than 0.",
                    "মোট ইউনিট ০ এর চেয়ে বেশি হতে হবে।"
                );
            }
        } catch (Exception ex) {
            return ValidationResult.error("ERR_DIMENSION_CONSISTENCY_MISMATCH", "total_units must be a valid integer.", "মোট ইউনিট সঠিক সংখ্যা হতে হবে।");
        }

        int expectedUnits = calculateTotalUnits(concentration, volume);
        if (totalUnits != expectedUnits) {
            return ValidationResult.error(
                "ERR_DIMENSION_CONSISTENCY_MISMATCH",
                "Dimension consistency mismatch: total_units (" + totalUnits + " IU) does not equal concentration (" + concentration + " IU/mL) * volume (" + volume + " mL) = " + expectedUnits + " IU.",
                "ডাইমেনশন অমিল: মোট ইউনিট (" + totalUnits + ") অবশ্যই ঘনমাত্রা (" + concentration + ") × ভলিউম (" + volume + ") = " + expectedUnits + " ইউনিটের সমান হতে হবে।"
            );
        }

        // 6. In-Use Shelf Life Days (14 - 56 days)
        int shelfLife;
        try {
            shelfLife = Integer.parseInt(payload.get("in_use_shelf_life_days").toString());
            if (shelfLife < 14 || shelfLife > 56) {
                return ValidationResult.error(
                    "ERR_INVALID_IN_USE_SHELF_LIFE",
                    "in_use_shelf_life_days must be between 14 and 56 days.",
                    "ব্যবহারকালীন রুম টেম্পারেচার মেয়াদ ১৪ থেকে ৫৬ দিনের মধ্যে হতে হবে।"
                );
            }
        } catch (Exception ex) {
            return ValidationResult.error("ERR_INVALID_IN_USE_SHELF_LIFE", "in_use_shelf_life_days must be a valid integer.", "মেয়াদ সঠিক সংখ্যা হতে হবে।");
        }

        return ValidationResult.valid();
    }

    public void putCache(String configId, InsulinConfigDto dto) {
        if (configId != null && dto != null) {
            localCache.put(configId, dto);
        }
    }

    public Optional<InsulinConfigDto> getFromCache(String configId) {
        return Optional.ofNullable(localCache.get(configId));
    }

    public List<InsulinConfigDto> getAllCached() {
        return new ArrayList<>(localCache.values());
    }

    public void clearCache() {
        localCache.clear();
    }

    public String getLabel(String fieldKey, String lang) {
        boolean isBn = "bn".equalsIgnoreCase(lang);
        switch (fieldKey) {
            case "action_category":
                return isBn ? "কার্যকারিতার ধরন" : "Action Profile";
            case "delivery_device":
                return isBn ? "ডেলিভারি প্রেজেন্টেশন" : "Delivery Presentation";
            case "concentration_iu_per_ml":
                return isBn ? "ঘনমাত্রা (আইইউ/মি.লি.)" : "Concentration (IU/mL)";
            case "volume_ml":
                return isBn ? "কনটেইনারের আয়তন (মি.লি.)" : "Container Volume (mL)";
            case "total_units":
                return isBn ? "মোট সক্রিয় ইউনিট (আইইউ)" : "Total Units (IU)";
            case "in_use_shelf_life_days":
                return isBn ? "ব্যবহারকালীন মেয়াদ (দিন)" : "In-Use Shelf Life (Days)";
            case "excursion_action":
                return isBn ? "তাপমাত্রা বিচ্যুতিতে করণীয়" : "Excursion Protocol";
            default:
                return fieldKey;
        }
    }
}

