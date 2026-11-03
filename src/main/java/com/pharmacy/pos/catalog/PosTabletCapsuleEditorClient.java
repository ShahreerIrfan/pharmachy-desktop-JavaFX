package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.util.*;

/**
 * PosTabletCapsuleEditorClient
 * Architecture Decision: ADR-0141 (P141).
 * Phase 08: Tablet/Capsule Editor & Packaging Hierarchy Multipliers.
 *
 * Client-side integration for JavaFX Desktop POS managing solid oral dosage forms
 * (tablets, capsules), packaging multipliers (Box -> Strip -> Unit), dimension
 * consistency, loose-sale eligibility, and clinical route enforcement.
 */
public class PosTabletCapsuleEditorClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public enum SolidDosageForm {
        TABLET("Standard Tablet", "স্ট্যান্ডার্ড ট্যাবলেট"),
        CAPSULE("Capsule", "ক্যাপসুল"),
        CHEWABLE_TABLET("Chewable Tablet", "চিউয়েবল ট্যাবলেট"),
        EFFERVESCENT_TABLET("Effervescent Tablet", "এফারভেসেন্ট ট্যাবলেট"),
        EXTENDED_RELEASE_TABLET("Extended Release Tablet", "এক্সটেন্ডেড রিলিজ ট্যাবলেট"),
        EXTENDED_RELEASE_CAPSULE("Extended Release Capsule", "এক্সটেন্ডেড রিলিজ ক্যাপসুল"),
        SUBLINGUAL_TABLET("Sublingual Tablet", "সাবলিঙ্গুয়াল ট্যাবলেট");

        private final String labelEn;
        private final String labelBn;

        SolidDosageForm(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
    }

    public enum SolidStrengthUnit {
        MG("mg", "মিগ্রা"),
        MCG("mcg", "মাইক্রোগ্রাম"),
        G("g", "গ্রাম");

        private final String labelEn;
        private final String labelBn;

        SolidStrengthUnit(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
    }

    public enum TabletRoute {
        ORAL("Oral", "মুখে সেব্য"),
        SUBLINGUAL("Sublingual", "জিহ্বার নিচে"),
        BUCCAL("Buccal", "গালের পাশে"),
        CHEWABLE("Chewable", "চিবিয়ে সেব্য"),
        DISPERSIBLE("Dispersible", "পানিতে গুলিয়ে সেব্য"),
        EFFERVESCENT("Effervescent", "পানিতে দ্রবণীয় এফারভেসেন্ট");

        private final String labelEn;
        private final String labelBn;

        TabletRoute(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
    }

    public enum SolidStoragePolicy {
        ROOM_TEMPERATURE_DRY("Room Temperature & Dry (<30°C)", "স্বাভাবিক তাপমাত্রা ও শুষ্ক স্থান (<৩০°সে)"),
        PROTECT_FROM_MOISTURE_LIGHT("Protect From Moisture & Light (<25°C)", "আলো ও আর্দ্রতা থেকে সুরক্ষিত (<২৫°সে)"),
        COOL_AND_DRY("Cool & Dry (15°C - 25°C)", "শীতল ও শুষ্ক স্থান (১৫°সে - ২৫°সে)"),
        REFRIGERATED("Refrigerated (2°C - 8°C)", "রেফ্রিজারেটেড (২°সে - ৮°সে)");

        private final String labelEn;
        private final String labelBn;

        SolidStoragePolicy(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
    }

    public static final Set<String> HIDDEN_FIELDS = Set.of(
        "volume_ml",
        "concentration",
        "reconstitution_water_ml",
        "dropper_volume_ml",
        "needle_gauge",
        "is_reconstitution_required"
    );

    public static final class TabletCapsuleConfigDto {
        public final String configId;
        public final String productId;
        public final String brandName;
        public final String genericName;
        public final String tenantId;
        public final String dosageForm;
        public final BigDecimal doseStrength;
        public final String strengthUnit;
        public final int tabletsPerStrip;
        public final int stripsPerBox;
        public final int boxSize;
        public final boolean looseSaleEligible;
        public final String route;
        public final String storagePolicy;
        public final boolean isScored;
        public final String coatingType;
        public final boolean isActive;

        public TabletCapsuleConfigDto(
            String configId, String productId, String brandName, String genericName,
            String tenantId, String dosageForm, BigDecimal doseStrength, String strengthUnit,
            int tabletsPerStrip, int stripsPerBox, int boxSize, boolean looseSaleEligible,
            String route, String storagePolicy, boolean isScored, String coatingType, boolean isActive
        ) {
            this.configId = configId;
            this.productId = productId;
            this.brandName = brandName;
            this.genericName = genericName;
            this.tenantId = tenantId;
            this.dosageForm = dosageForm;
            this.doseStrength = doseStrength;
            this.strengthUnit = strengthUnit;
            this.tabletsPerStrip = tabletsPerStrip;
            this.stripsPerBox = stripsPerBox;
            this.boxSize = boxSize;
            this.looseSaleEligible = looseSaleEligible;
            this.route = route;
            this.storagePolicy = storagePolicy;
            this.isScored = isScored;
            this.coatingType = coatingType;
            this.isActive = isActive;
        }
    }

    public static final class ValidationResult {
        public final boolean isValid;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;
        public final int computedBoxSize;

        public ValidationResult(boolean isValid, String errorCode, String messageEn, String messageBn, int computedBoxSize) {
            this.isValid = isValid;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
            this.computedBoxSize = computedBoxSize;
        }

        public static ValidationResult valid(int computedBoxSize) {
            return new ValidationResult(true, null, "Validation passed.", "যাচাইকরণ সফল হয়েছে।", computedBoxSize);
        }

        public static ValidationResult error(String errorCode, String messageEn, String messageBn) {
            return new ValidationResult(false, errorCode, messageEn, messageBn, 0);
        }
    }

    private final Map<String, TabletCapsuleConfigDto> localCache = new LinkedHashMap<>();
    private boolean isOfflineMode = false;

    public void setOfflineMode(boolean offlineMode) {
        this.isOfflineMode = offlineMode;
    }

    public boolean isOfflineMode() {
        return isOfflineMode;
    }

    public int calculateBoxSize(int tabletsPerStrip, int stripsPerBox) {
        if (tabletsPerStrip < 1 || stripsPerBox < 1) {
            return 0;
        }
        return tabletsPerStrip * stripsPerBox;
    }

    public boolean isLooseSalePermitted(String dosageForm, String storagePolicy) {
        if ("EFFERVESCENT_TABLET".equalsIgnoreCase(dosageForm)) {
            return false;
        }
        if ("PROTECT_FROM_MOISTURE_LIGHT".equalsIgnoreCase(storagePolicy) && "EFFERVESCENT_TABLET".equalsIgnoreCase(dosageForm)) {
            return false;
        }
        return true;
    }

    public boolean isFieldHidden(String fieldName) {
        if (fieldName == null) return false;
        return HIDDEN_FIELDS.contains(fieldName.toLowerCase().trim());
    }

    public ValidationResult validatePayload(Map<String, Object> payload) {
        if (payload == null) {
            return ValidationResult.error("ERR_INVALID_PAYLOAD", "Payload cannot be null.", "পে-লোড খালি হতে পারবে না।");
        }

        // 1. Zero-Trust Hidden Field Guard
        for (String hidden : HIDDEN_FIELDS) {
            if (payload.containsKey(hidden) && payload.get(hidden) != null) {
                return ValidationResult.error(
                    "ERR_HIDDEN_FIELD_VALUE_REJECTED",
                    "Field '" + hidden + "' is prohibited for solid tablet/capsule forms.",
                    "কঠিন ট্যাবলেট বা ক্যাপসুলের জন্য '" + hidden + "' ফিল্ড অনুমোদিত নয়।"
                );
            }
        }

        // 2. Required Fields
        List<String> required = List.of("dosage_form", "dose_strength", "strength_unit", "tablets_per_strip", "strips_per_box", "route", "storage_policy");
        for (String req : required) {
            if (!payload.containsKey(req) || payload.get(req) == null || payload.get(req).toString().isBlank()) {
                return ValidationResult.error(
                    "ERR_REQUIRED_FIELD_MISSING",
                    "Missing mandatory field '" + req + "'.",
                    "বাধ্যতামূলক ফিল্ড '" + req + "' অনুপস্থিত।"
                );
            }
        }

        // 3. Packaging Multipliers & Dimension Consistency
        int tabletsPerStrip;
        int stripsPerBox;
        try {
            tabletsPerStrip = Integer.parseInt(payload.get("tablets_per_strip").toString());
            stripsPerBox = Integer.parseInt(payload.get("strips_per_box").toString());
            if (tabletsPerStrip < 1 || stripsPerBox < 1) {
                return ValidationResult.error(
                    "ERR_INVALID_PACKAGING_MULTIPLIER",
                    "Packaging multipliers must be integers >= 1.",
                    "প্যাকেজিং গুণক অবশ্যই ১ বা তার বেশি পূর্ণসংখ্যা হতে হবে।"
                );
            }
        } catch (NumberFormatException e) {
            return ValidationResult.error(
                "ERR_INVALID_PACKAGING_MULTIPLIER",
                "Packaging multipliers must be valid integers.",
                "প্যাকেজিং গুণক অবশ্যই সঠিক পূর্ণসংখ্যা হতে হবে।"
            );
        }

        int expectedBoxSize = tabletsPerStrip * stripsPerBox;
        if (payload.containsKey("box_size") && payload.get("box_size") != null) {
            try {
                int providedBoxSize = Integer.parseInt(payload.get("box_size").toString());
                if (providedBoxSize != expectedBoxSize) {
                    return ValidationResult.error(
                        "ERR_DIMENSION_CONSISTENCY_MISMATCH",
                        "Dimension mismatch: box_size (" + providedBoxSize + ") must equal strips_per_box * tablets_per_strip (" + expectedBoxSize + ").",
                        "মাত্রা অসঙ্গতি: বক্স সাইজ (" + providedBoxSize + ") অবশ্যই স্ট্রিপ x ট্যাবলেট (" + expectedBoxSize + ") এর সমান হতে হবে।"
                    );
                }
            } catch (NumberFormatException e) {
                return ValidationResult.error(
                    "ERR_INVALID_BOX_SIZE",
                    "box_size must be a valid integer.",
                    "বক্স সাইজ অবশ্যই সঠিক পূর্ণসংখ্যা হতে হবে।"
                );
            }
        }

        // 4. Loose-sale business rule
        String form = payload.get("dosage_form").toString().toUpperCase();
        String storage = payload.get("storage_policy").toString().toUpperCase();
        boolean looseSale = Boolean.parseBoolean(payload.getOrDefault("loose_sale_eligible", "true").toString());

        if ("EFFERVESCENT_TABLET".equals(form) && looseSale) {
            return ValidationResult.error(
                "ERR_LOOSE_SALE_NOT_PERMITTED",
                "Loose sale is not permitted for effervescent tablets due to moisture degradation.",
                "আর্দ্রতাজনিত ক্ষতির কারণে এফারভেসেন্ট ট্যাবলেট খুচরা বিক্রি নিষিদ্ধ।"
            );
        }

        // 5. Solid Strength Unit
        String unit = payload.get("strength_unit").toString().toLowerCase();
        if (!Set.of("mg", "mcg", "g").contains(unit)) {
            return ValidationResult.error(
                "ERR_INVALID_STRENGTH_UNIT_FOR_SOLID_FORM",
                "Strength unit '" + unit + "' is invalid for solid forms (allowed: mg, mcg, g).",
                "কঠিন ওষুধের জন্য '" + unit + "' মাত্রা একক গ্রহণযোগ্য নয় (অনুমোদিত: mg, mcg, g)।"
            );
        }

        // 6. Solid Route
        String route = payload.get("route").toString();
        if (!Set.of("Oral", "Sublingual", "Buccal", "Chewable", "Dispersible", "Effervescent").contains(route)) {
            return ValidationResult.error(
                "ERR_INVALID_ROUTE_FOR_DOSAGE_FORM",
                "Route '" + route + "' is invalid for tablet/capsule dosage forms.",
                "ট্যাবলেট বা ক্যাপসুলের জন্য '" + route + "' সেবনের মাধ্যমটি সঠিক নয়।"
            );
        }

        return ValidationResult.valid(expectedBoxSize);
    }

    public void cacheConfig(TabletCapsuleConfigDto dto) {
        if (dto != null && dto.productId != null) {
            localCache.put(dto.productId, dto);
        }
    }

    public TabletCapsuleConfigDto getCachedConfig(String productId) {
        return localCache.get(productId);
    }

    public List<TabletCapsuleConfigDto> getLocalConfigs() {
        return new ArrayList<>(localCache.values());
    }

    public String getLabel(String key, String lang) {
        boolean isBn = "bn".equalsIgnoreCase(lang);
        switch (key) {
            case "dose_strength":
                return isBn ? "ডোজের মাত্রা" : "Dose Strength";
            case "strength_unit":
                return isBn ? "মাত্রার একক" : "Strength Unit";
            case "tablets_per_strip":
                return isBn ? "পাতা প্রতি ট্যাবলেট" : "Tablets Per Strip";
            case "strips_per_box":
                return isBn ? "বক্স প্রতি পাতা" : "Strips Per Box";
            case "box_size":
                return isBn ? "বক্সের মোট ট্যাবলেট" : "Total Box Units";
            case "loose_sale_eligible":
                return isBn ? "খুচরা বিক্রয়যোগ্য" : "Loose Sale Eligible";
            case "route":
                return isBn ? "সেবনের মাধ্যম" : "Route";
            case "storage_policy":
                return isBn ? "সংরক্ষণ নীতি" : "Storage Policy";
            default:
                return key;
        }
    }
}
