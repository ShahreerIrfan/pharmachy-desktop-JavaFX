package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * PosSachetEditorClient
 * Architecture Decision: ADR-0149 (P149).
 * Phase 08: Milestone M04 / M05 - Specialized Dosage-Form Editors.
 *
 * Client-side integration for JavaFX Desktop POS managing Sachets, Powders,
 * ORS mixing instructions, break-bulk loose sachet eligibility, and pack dimensions.
 */
public class PosSachetEditorClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class SachetRecord {
        public final String configId;
        public final String productId;
        public final String tenantId;
        public final String powderCategory; // ORAL_REHYDRATION_SALTS, EFFERVESCENT_POWDER, BULK_LAXATIVE_HUSK, etc.
        public final String administrationRoute; // ORAL, TOPICAL
        public final BigDecimal gramsPerSachet;
        public final int sachetsPerPack;
        public final BigDecimal totalPackWeightGrams;
        public final boolean looseSachetSaleEligible;
        public final boolean requiresDissolution;
        public final String diluentType;
        public final BigDecimal diluentVolumeMl;
        public final int reconstitutedStabilityHours;
        public final String storageCondition;
        public final String patientInstructionsEn;
        public final String patientInstructionsBn;
        public final boolean isActive;

        public SachetRecord(
                String configId,
                String productId,
                String tenantId,
                String powderCategory,
                String administrationRoute,
                BigDecimal gramsPerSachet,
                int sachetsPerPack,
                BigDecimal totalPackWeightGrams,
                boolean looseSachetSaleEligible,
                boolean requiresDissolution,
                String diluentType,
                BigDecimal diluentVolumeMl,
                int reconstitutedStabilityHours,
                String storageCondition,
                String patientInstructionsEn,
                String patientInstructionsBn,
                boolean isActive) {
            this.configId = configId;
            this.productId = productId;
            this.tenantId = tenantId;
            this.powderCategory = powderCategory;
            this.administrationRoute = administrationRoute;
            this.gramsPerSachet = gramsPerSachet;
            this.sachetsPerPack = sachetsPerPack;
            this.totalPackWeightGrams = totalPackWeightGrams;
            this.looseSachetSaleEligible = looseSachetSaleEligible;
            this.requiresDissolution = requiresDissolution;
            this.diluentType = diluentType;
            this.diluentVolumeMl = diluentVolumeMl;
            this.reconstitutedStabilityHours = reconstitutedStabilityHours;
            this.storageCondition = storageCondition;
            this.patientInstructionsEn = patientInstructionsEn != null ? patientInstructionsEn : "";
            this.patientInstructionsBn = patientInstructionsBn != null ? patientInstructionsBn : "";
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
            return new ValidationResult(true, null, "Validation passed", "যাচাই সফল হয়েছে");
        }

        public static ValidationResult error(String code, String msgEn, String msgBn) {
            return new ValidationResult(false, code, msgEn, msgBn);
        }
    }

    public static final List<String> FORBIDDEN_SOLID_FIELDS = List.of(
            "strips_per_box", "tablets_per_strip", "units_per_strip",
            "is_scored", "coating_type", "needle_gauge"
    );

    public static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "ORAL_REHYDRATION_SALTS", "EFFERVESCENT_POWDER", "BULK_LAXATIVE_HUSK",
            "SUSPENSION_GRANULES", "TOPICAL_DUSTING_POWDER"
    );

    public static final Set<String> ALLOWED_ROUTES = Set.of(
            "ORAL", "TOPICAL"
    );

    public ValidationResult validateDraftPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return ValidationResult.error("ERR_EMPTY_PAYLOAD", "Payload cannot be empty", "পে-লোড খালি হতে পারে না");
        }

        for (String field : FORBIDDEN_SOLID_FIELDS) {
            if (payload.containsKey(field) && payload.get(field) != null) {
                return ValidationResult.error(
                        "ERR_HIDDEN_FIELD_VALUE_REJECTED",
                        "Field '" + field + "' is illegal for sachet/powder dosage form.",
                        "স্যাশে/পাউডার ডোজ ফর্মের জন্য '" + field + "' ফিল্ডটি নিষিদ্ধ।"
                );
            }
        }

        String category = (String) payload.get("powder_category");
        if (category == null || !ALLOWED_CATEGORIES.contains(category)) {
            return ValidationResult.error("ERR_INVALID_POWDER_CATEGORY", "Invalid powder category.", "অবৈধ পাউডার ক্যাটাগরি।");
        }

        String route = (String) payload.get("administration_route");
        if (route == null || !ALLOWED_ROUTES.contains(route)) {
            return ValidationResult.error("ERR_INVALID_ADMINISTRATION_ROUTE", "Route must be ORAL or TOPICAL.", "অবৈধ প্রয়োগ রুট।");
        }

        Object gramsObj = payload.get("grams_per_sachet");
        if (gramsObj == null) {
            return ValidationResult.error("ERR_INVALID_GRAMS_PER_SACHET", "grams_per_sachet is required.", "স্যাশের ওজন আবশ্যক।");
        }
        BigDecimal grams;
        try {
            grams = new BigDecimal(gramsObj.toString());
            if (grams.compareTo(BigDecimal.ZERO) <= 0) {
                return ValidationResult.error("ERR_INVALID_GRAMS_PER_SACHET", "grams_per_sachet must be greater than 0.", "স্যাশের ওজন শূন্যের চেয়ে বড় হতে হবে।");
            }
        } catch (Exception e) {
            return ValidationResult.error("ERR_INVALID_GRAMS_PER_SACHET", "grams_per_sachet must be numeric.", "স্যাশের ওজন সংখ্যা হতে হবে।");
        }

        Object sachetsObj = payload.get("sachets_per_pack");
        int count;
        if (sachetsObj == null) {
            return ValidationResult.error("ERR_INVALID_SACHETS_PER_PACK", "sachets_per_pack is required.", "প্যাকে স্যাশে সংখ্যা আবশ্যক।");
        }
        try {
            count = Integer.parseInt(sachetsObj.toString());
            if (count < 1 || count > 500) {
                return ValidationResult.error("ERR_INVALID_SACHETS_PER_PACK", "sachets_per_pack must be between 1 and 500.", "স্যাশে সংখ্যা ১ থেকে ৫০০ এর মধ্যে হতে হবে।");
            }
        } catch (Exception e) {
            return ValidationResult.error("ERR_INVALID_SACHETS_PER_PACK", "sachets_per_pack must be integer.", "স্যাশে সংখ্যা পূর্ণসংখ্যা হতে হবে।");
        }

        Object packWeightObj = payload.get("total_pack_weight_grams");
        if (packWeightObj != null && !packWeightObj.toString().trim().isEmpty()) {
            try {
                BigDecimal actualTotal = new BigDecimal(packWeightObj.toString()).setScale(2, RoundingMode.HALF_UP);
                BigDecimal expectedTotal = grams.multiply(new BigDecimal(count)).setScale(2, RoundingMode.HALF_UP);
                if (actualTotal.compareTo(expectedTotal) != 0) {
                    return ValidationResult.error(
                            "ERR_DIMENSION_CONSISTENCY_MISMATCH",
                            "total_pack_weight_grams (" + actualTotal + ") does not equal grams * sachets (" + expectedTotal + ").",
                            "প্যাকের মোট ওজন ও স্যাশের ওজনের হিসাবের গরমিল।"
                    );
                }
            } catch (Exception e) {
                return ValidationResult.error("ERR_DIMENSION_CONSISTENCY_MISMATCH", "Invalid total pack weight decimal.", "প্যাকের মোট ওজন সংখ্যা হতে হবে।");
            }
        }

        boolean requiresDissolution = Boolean.TRUE.equals(payload.get("requires_dissolution"));
        if (requiresDissolution) {
            Object volObj = payload.get("diluent_volume_ml");
            if (volObj == null || volObj.toString().trim().isEmpty()) {
                return ValidationResult.error("ERR_MISSING_DILUENT_VOLUME", "diluent_volume_ml is required when dissolution is required.", "পানির পরিমাণ আবশ্যক।");
            }
            try {
                BigDecimal vol = new BigDecimal(volObj.toString());
                if (vol.compareTo(BigDecimal.ZERO) <= 0) {
                    return ValidationResult.error("ERR_MISSING_DILUENT_VOLUME", "diluent_volume_ml must be greater than 0.", "পানির পরিমাণ শূন্যের চেয়ে বড় হতে হবে।");
                }
            } catch (Exception e) {
                return ValidationResult.error("ERR_MISSING_DILUENT_VOLUME", "diluent_volume_ml must be numeric.", "পানির পরিমাণ সংখ্যা হতে হবে।");
            }
        }

        return ValidationResult.valid();
    }
}

