package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * PosSuspensionReconstitutionClient
 * Architecture Decision: ADR-0143 (P143).
 * Phase 08: Specialized Dosage-Form Editors & Multi-Tier Packaging.
 *
 * Client-side integration for JavaFX Desktop POS managing dry powder suspension reconstitution,
 * diluent water volumes, dimension consistency (final >= water), beyond-use dates (BUD),
 * pharmacist clinical reviews, and zero-trust solid oral field isolation.
 */
public class PosSuspensionReconstitutionClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public enum ReconstitutionDosageForm {
        SUSPENSION("Suspension", "সাসপেনশন"),
        DRY_SYRUP_RECONSTITUTED("Dry Syrup / Powder for Suspension", "ড্রাই সিরাপ / পাউডার সাসপেনশন"),
        ORAL_POWDER_SUSPENSION("Oral Powder for Suspension", "ওরাল পাউডার ফর সাসপেনশন");

        private final String labelEn;
        private final String labelBn;

        ReconstitutionDosageForm(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
    }

    public enum ReconstitutionDiluent {
        BOILED_COOLED_WATER("Boiled and Cooled Water", "ফুটিয়ে ঠাণ্ডা করা পানি"),
        STERILE_WATER_FOR_INJECTION("Sterile Water for Injection", "স্টেরাইল ওয়াটার ফর ইনজেকশন"),
        PURIFIED_DRINKING_WATER("Purified Drinking Water", "বিশুদ্ধ খাবার পানি"),
        NORMAL_SALINE_0_9("Normal Saline (0.9% NaCl)", "নরমাল স্যালাইন (০.৯%)");

        private final String labelEn;
        private final String labelBn;

        ReconstitutionDiluent(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
    }

    public enum ReconstitutionStoragePolicy {
        REFRIGERATE_2_8C_AFTER_RECONSTITUTION("Refrigerate (2°C - 8°C) After Reconstitution", "পানি মিশ্রণের পর রেফ্রিজারেটরে রাখুন (২°সে - ৮°সে)"),
        ROOM_TEMPERATURE_BELOW_25C("Room Temperature (Below 25°C)", "স্বাভাবিক তাপমাত্রা (<২৫°সে)"),
        COOL_DRY_PLACE_BELOW_30C("Cool, Dry Place (Below 30°C)", "শীতল ও শুষ্ক স্থান (<৩০°সে)"),
        DO_NOT_FREEZE("Do Not Freeze", "জমাট বাঁধাবেন না");

        private final String labelEn;
        private final String labelBn;

        ReconstitutionStoragePolicy(String labelEn, String labelBn) {
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

    public static final Set<String> AUTHORIZED_REVIEWER_ROLES = Set.of(
        "STAFF_PHARMACIST",
        "PHARMACY_OWNER",
        "SUPERADMIN",
        "PLATFORM_OPERATOR",
        "PLATFORM_SUPER_ADMIN"
    );

    public static final class SuspensionReconstitutionDto {
        public final String configId;
        public final String productId;
        public final String brandName;
        public final String genericName;
        public final String tenantId;
        public final String dosageForm;
        public final String reconstitutionDiluent;
        public final BigDecimal waterVolumeMl;
        public final BigDecimal finalVolumeMl;
        public final int shelfLifeDays;
        public final LocalDate openingDate;
        public final LocalDate beyondUseDate;
        public final String storagePolicy;
        public final String instructionsEn;
        public final String instructionsBn;
        public final String handlingGuidanceEn;
        public final String handlingGuidanceBn;
        public final boolean isPharmacistReviewed;
        public final String reviewedById;
        public final String reviewedByName;
        public final String reviewerNotes;
        public final boolean isActive;

        public SuspensionReconstitutionDto(
            String configId, String productId, String brandName, String genericName,
            String tenantId, String dosageForm, String reconstitutionDiluent,
            BigDecimal waterVolumeMl, BigDecimal finalVolumeMl, int shelfLifeDays,
            LocalDate openingDate, LocalDate beyondUseDate, String storagePolicy,
            String instructionsEn, String instructionsBn,
            String handlingGuidanceEn, String handlingGuidanceBn,
            boolean isPharmacistReviewed, String reviewedById, String reviewedByName,
            String reviewerNotes, boolean isActive
        ) {
            this.configId = configId;
            this.productId = productId;
            this.brandName = brandName;
            this.genericName = genericName;
            this.tenantId = tenantId;
            this.dosageForm = dosageForm;
            this.reconstitutionDiluent = reconstitutionDiluent;
            this.waterVolumeMl = waterVolumeMl;
            this.finalVolumeMl = finalVolumeMl;
            this.shelfLifeDays = shelfLifeDays;
            this.openingDate = openingDate;
            this.beyondUseDate = beyondUseDate;
            this.storagePolicy = storagePolicy;
            this.instructionsEn = instructionsEn;
            this.instructionsBn = instructionsBn;
            this.handlingGuidanceEn = handlingGuidanceEn;
            this.handlingGuidanceBn = handlingGuidanceBn;
            this.isPharmacistReviewed = isPharmacistReviewed;
            this.reviewedById = reviewedById;
            this.reviewedByName = reviewedByName;
            this.reviewerNotes = reviewerNotes;
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

    private final Map<String, SuspensionReconstitutionDto> localCache = new LinkedHashMap<>();
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

    public LocalDate calculateBeyondUseDate(LocalDate openingDate, int shelfLifeDays) {
        if (openingDate == null) return null;
        return openingDate.plusDays(shelfLifeDays);
    }

    public ValidationResult validatePayload(Map<String, Object> payload, String userRole) {
        if (payload == null) {
            return ValidationResult.error("ERR_INVALID_PAYLOAD", "Payload cannot be null.", "পে-লোড খালি হতে পারবে না।");
        }

        // 1. Zero-Trust Hidden Field Guard (Reject solid oral tablet/strip fields)
        for (String hidden : HIDDEN_FIELDS) {
            if (payload.containsKey(hidden) && payload.get(hidden) != null) {
                return ValidationResult.error(
                    "ERR_HIDDEN_FIELD_VALUE_REJECTED",
                    "Field '" + hidden + "' is prohibited for suspension reconstitution forms.",
                    "সাসপেনশন প্রস্তুতকরণের জন্য '" + hidden + "' ফিল্ড অনুমোদিত নয়।"
                );
            }
        }

        // 2. Required Fields
        List<String> required = List.of(
            "dosage_form", "reconstitution_diluent", "water_volume_ml",
            "final_volume_ml", "shelf_life_days", "instructions_en", "storage_policy"
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

        // 3. Volumes & Dimension Consistency
        BigDecimal waterVolume;
        BigDecimal finalVolume;
        try {
            waterVolume = new BigDecimal(payload.get("water_volume_ml").toString());
            if (waterVolume.compareTo(BigDecimal.ZERO) <= 0) {
                return ValidationResult.error(
                    "ERR_INVALID_WATER_VOLUME",
                    "water_volume_ml must be greater than 0 mL.",
                    "পানির পরিমাণ ০ এর চেয়ে বেশি হতে হবে।"
                );
            }
        } catch (Exception ex) {
            return ValidationResult.error("ERR_INVALID_WATER_VOLUME", "water_volume_ml must be a valid number.", "পানির পরিমাণ সঠিক সংখ্যা হতে হবে।");
        }

        try {
            finalVolume = new BigDecimal(payload.get("final_volume_ml").toString());
            if (finalVolume.compareTo(BigDecimal.ZERO) <= 0) {
                return ValidationResult.error(
                    "ERR_INVALID_FINAL_VOLUME",
                    "final_volume_ml must be greater than 0 mL.",
                    "মোট আয়তন ০ এর চেয়ে বেশি হতে হবে।"
                );
            }
        } catch (Exception ex) {
            return ValidationResult.error("ERR_INVALID_FINAL_VOLUME", "final_volume_ml must be a valid number.", "মোট আয়তন সঠিক সংখ্যা হতে হবে।");
        }

        if (finalVolume.compareTo(waterVolume) < 0) {
            return ValidationResult.error(
                "ERR_DIMENSION_CONSISTENCY_MISMATCH",
                "Reconstituted final volume (" + finalVolume + " mL) cannot be less than diluent water volume (" + waterVolume + " mL).",
                "প্রস্তুতকৃত মোট আয়তন (" + finalVolume + " মি.লি.) যোগ করা পানির পরিমাণ (" + waterVolume + " মি.লি.) থেকে কম হতে পারে না।"
            );
        }

        // 4. Shelf Life Days
        int shelfLifeDays;
        try {
            shelfLifeDays = Integer.parseInt(payload.get("shelf_life_days").toString());
            if (shelfLifeDays < 1 || shelfLifeDays > 60) {
                return ValidationResult.error(
                    "ERR_INVALID_SHELF_LIFE",
                    "shelf_life_days must be between 1 and 60 days.",
                    "স্থায়িত্বকাল ১ থেকে ৬০ দিনের মধ্যে হতে হবে।"
                );
            }
        } catch (Exception ex) {
            return ValidationResult.error("ERR_INVALID_SHELF_LIFE", "shelf_life_days must be a valid integer.", "স্থায়িত্বকাল সঠিক সংখ্যা হতে হবে।");
        }

        // 5. Opening Date and BUD Validation
        LocalDate openDate = null;
        if (payload.containsKey("opening_date") && payload.get("opening_date") != null && !payload.get("opening_date").toString().isBlank()) {
            try {
                openDate = LocalDate.parse(payload.get("opening_date").toString().trim());
            } catch (DateTimeParseException ex) {
                return ValidationResult.error("ERR_INVALID_OPENING_DATE", "opening_date must be in ISO YYYY-MM-DD format.", "তারিখ YYYY-MM-DD ফরম্যাটে হতে হবে।");
            }
        }

        if (openDate != null && payload.containsKey("beyond_use_date") && payload.get("beyond_use_date") != null && !payload.get("beyond_use_date").toString().isBlank()) {
            LocalDate bud;
            try {
                bud = LocalDate.parse(payload.get("beyond_use_date").toString().trim());
            } catch (DateTimeParseException ex) {
                return ValidationResult.error("ERR_INVALID_BEYOND_USE_DATE", "beyond_use_date must be in ISO YYYY-MM-DD format.", "বিইউডি YYYY-MM-DD ফরম্যাটে হতে হবে।");
            }

            LocalDate maxBud = openDate.plusDays(shelfLifeDays);
            if (bud.isBefore(openDate)) {
                return ValidationResult.error(
                    "ERR_INVALID_BEYOND_USE_DATE",
                    "Beyond-use date (" + bud + ") cannot precede opening date (" + openDate + ").",
                    "ব্যবহারের শেষ তারিখ প্রস্তুতের তারিখের পূর্বে হতে পারবে না।"
                );
            }
            if (bud.isAfter(maxBud)) {
                return ValidationResult.error(
                    "ERR_INVALID_BEYOND_USE_DATE",
                    "Beyond-use date (" + bud + ") exceeds maximum shelf life (" + maxBud + ").",
                    "ব্যবহারের শেষ তারিখ সর্বোচ্চ স্থায়িত্বকাল (" + maxBud + ") অতিক্রম করেছে।"
                );
            }
        }

        // 6. Pharmacist Review Authorization
        boolean isPharmacistReviewed = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("is_pharmacist_reviewed", false)));
        if (isPharmacistReviewed) {
            if (userRole == null || !AUTHORIZED_REVIEWER_ROLES.contains(userRole.trim().toUpperCase())) {
                return ValidationResult.error(
                    "ERR_PHARMACIST_REVIEW_REQUIRED",
                    "Reconstitution handling guidance approval requires licensed pharmacist or owner credentials.",
                    "সাসপেনশন প্রস্তুতকরণ নির্দেশনা অনুমোদনের জন্য রেজিস্টার্ড ফার্মাসিস্ট বা মালিকের অনুমতি আবশ্যক।"
                );
            }
        }

        return ValidationResult.valid();
    }

    public void putCache(String configId, SuspensionReconstitutionDto dto) {
        if (configId != null && dto != null) {
            localCache.put(configId, dto);
        }
    }

    public Optional<SuspensionReconstitutionDto> getFromCache(String configId) {
        return Optional.ofNullable(localCache.get(configId));
    }

    public List<SuspensionReconstitutionDto> getAllCached() {
        return new ArrayList<>(localCache.values());
    }

    public void clearCache() {
        localCache.clear();
    }

    public String getLabel(String fieldKey, String lang) {
        boolean isBn = "bn".equalsIgnoreCase(lang);
        switch (fieldKey) {
            case "reconstitution_diluent":
                return isBn ? "প্রস্তুতকারক পানি / দ্রাবক" : "Reconstitution Diluent";
            case "water_volume_ml":
                return isBn ? "যোগ করার পানির পরিমাণ (মি.লি.)" : "Diluent Water Volume (mL)";
            case "final_volume_ml":
                return isBn ? "প্রস্তুতকৃত মোট আয়তন (মি.লি.)" : "Final Reconstituted Volume (mL)";
            case "shelf_life_days":
                return isBn ? "স্থায়িত্বকাল (দিন)" : "Reconstituted Shelf Life (Days)";
            case "beyond_use_date":
                return isBn ? "ব্যবহারের শেষ তারিখ (বিইউডি)" : "Beyond-Use Date (BUD)";
            case "storage_policy":
                return isBn ? "সংরক্ষণ পদ্ধতি" : "Storage Policy";
            case "is_pharmacist_reviewed":
                return isBn ? "ফার্মাসিস্ট দ্বারা অনুমোদিত" : "Pharmacist Reviewed & Approved";
            default:
                return fieldKey;
        }
    }
}
