package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.util.*;

/**
 * PosSuppositoryEditorClient
 * Architecture Decision: ADR-0150 (P150).
 * Phase 08: Milestone M04 / M05 - Specialized Dosage-Form Editors.
 *
 * Client-side integration for JavaFX Desktop POS managing Suppositories & Pessaries,
 * piece counts, active strength, anatomical routes, and melt-protection storage.
 */
public class PosSuppositoryEditorClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class SuppositoryRecord {
        public final String configId;
        public final String productId;
        public final String tenantId;
        public final String suppositoryBaseType; // FATTY_BASE_COCOA_BUTTER, WATER_SOLUBLE_PEG, GLYCERINATED_GELATIN
        public final String administrationRoute; // RECTAL, VAGINAL, URETHRAL
        public final BigDecimal strengthValue;
        public final String strengthUnit;
        public final int piecesPerStrip;
        public final int stripsPerPack;
        public final Integer totalPiecesPerPack;
        public final boolean loosePieceSaleEligible;
        public final boolean requiresRefrigeration;
        public final String storageCondition;
        public final String patientInstructionsEn;
        public final String patientInstructionsBn;
        public final boolean isActive;

        public SuppositoryRecord(
                String configId,
                String productId,
                String tenantId,
                String suppositoryBaseType,
                String administrationRoute,
                BigDecimal strengthValue,
                String strengthUnit,
                int piecesPerStrip,
                int stripsPerPack,
                Integer totalPiecesPerPack,
                boolean loosePieceSaleEligible,
                boolean requiresRefrigeration,
                String storageCondition,
                String patientInstructionsEn,
                String patientInstructionsBn,
                boolean isActive) {
            this.configId = configId;
            this.productId = productId;
            this.tenantId = tenantId;
            this.suppositoryBaseType = suppositoryBaseType;
            this.administrationRoute = administrationRoute;
            this.strengthValue = strengthValue;
            this.strengthUnit = strengthUnit;
            this.piecesPerStrip = piecesPerStrip;
            this.stripsPerPack = stripsPerPack;
            this.totalPiecesPerPack = totalPiecesPerPack;
            this.loosePieceSaleEligible = loosePieceSaleEligible;
            this.requiresRefrigeration = requiresRefrigeration;
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
            "tablets_per_strip", "units_per_strip",
            "is_scored", "coating_type", "needle_gauge"
    );

    public static final Set<String> ALLOWED_BASES = Set.of(
            "FATTY_BASE_COCOA_BUTTER", "WATER_SOLUBLE_PEG", "GLYCERINATED_GELATIN"
    );

    public static final Set<String> ALLOWED_ROUTES = Set.of(
            "RECTAL", "VAGINAL", "URETHRAL"
    );

    public ValidationResult validateDraftPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return ValidationResult.error("ERR_EMPTY_PAYLOAD", "Payload cannot be empty", "পে-লোড খালি হতে পারে না");
        }

        for (String field : FORBIDDEN_SOLID_FIELDS) {
            if (payload.containsKey(field) && payload.get(field) != null) {
                return ValidationResult.error(
                        "ERR_HIDDEN_FIELD_VALUE_REJECTED",
                        "Field '" + field + "' is illegal for suppository dosage form.",
                        "সা Cream/পাউডার/সাপোজিটরি ডোজ ফর্মের জন্য '" + field + "' ফিল্ডটি নিষিদ্ধ।"
                );
            }
        }

        String baseType = (String) payload.get("suppository_base_type");
        if (baseType == null || !ALLOWED_BASES.contains(baseType)) {
            return ValidationResult.error("ERR_INVALID_SUPPOSITORY_BASE", "Invalid suppository base.", "অবৈধ সাপোজিটরি বেস।");
        }

        String route = (String) payload.get("administration_route");
        if (route == null || !ALLOWED_ROUTES.contains(route)) {
            return ValidationResult.error("ERR_INVALID_SUPPOSITORY_ROUTE", "Route must be RECTAL, VAGINAL, or URETHRAL.", "অবৈধ প্রয়োগ রুট।");
        }

        Object strObj = payload.get("strength_value");
        if (strObj == null) {
            return ValidationResult.error("ERR_INVALID_STRENGTH", "strength_value is required.", "মাত্রা আবশ্যক।");
        }
        try {
            BigDecimal str = new BigDecimal(strObj.toString());
            if (str.compareTo(BigDecimal.ZERO) <= 0) {
                return ValidationResult.error("ERR_INVALID_STRENGTH", "strength_value must be greater than 0.", "মাত্রা শূন্যের চেয়ে বড় হতে হবে।");
            }
        } catch (Exception e) {
            return ValidationResult.error("ERR_INVALID_STRENGTH", "strength_value must be numeric.", "মাত্রা সংখ্যা হতে হবে।");
        }

        Object piecesObj = payload.get("pieces_per_strip");
        int pieces;
        if (piecesObj == null) {
            return ValidationResult.error("ERR_INVALID_PIECES_PER_STRIP", "pieces_per_strip is required.", "স্ট্রিপে পিস সংখ্যা আবশ্যক।");
        }
        try {
            pieces = Integer.parseInt(piecesObj.toString());
            if (pieces < 1 || pieces > 50) {
                return ValidationResult.error("ERR_INVALID_PIECES_PER_STRIP", "pieces_per_strip must be between 1 and 50.", "পিস সংখ্যা ১ থেকে ৫০ এর মধ্যে হতে হবে।");
            }
        } catch (Exception e) {
            return ValidationResult.error("ERR_INVALID_PIECES_PER_STRIP", "pieces_per_strip must be integer.", "পিস সংখ্যা পূর্ণসংখ্যা হতে হবে।");
        }

        Object stripsObj = payload.get("strips_per_pack");
        int strips;
        if (stripsObj == null) {
            return ValidationResult.error("ERR_INVALID_STRIPS_PER_PACK", "strips_per_pack is required.", "প্যাকে স্ট্রিপ সংখ্যা আবশ্যক।");
        }
        try {
            strips = Integer.parseInt(stripsObj.toString());
            if (strips < 1 || strips > 100) {
                return ValidationResult.error("ERR_INVALID_STRIPS_PER_PACK", "strips_per_pack must be between 1 and 100.", "স্ট্রিপ সংখ্যা ১ থেকে ১০০ এর মধ্যে হতে হবে।");
            }
        } catch (Exception e) {
            return ValidationResult.error("ERR_INVALID_STRIPS_PER_PACK", "strips_per_pack must be integer.", "স্ট্রিপ সংখ্যা পূর্ণসংখ্যা হতে হবে।");
        }

        Object totalPiecesObj = payload.get("total_pieces_per_pack");
        if (totalPiecesObj != null && !totalPiecesObj.toString().trim().isEmpty()) {
            try {
                int actualTotal = Integer.parseInt(totalPiecesObj.toString());
                int expectedTotal = pieces * strips;
                if (actualTotal != expectedTotal) {
                    return ValidationResult.error(
                            "ERR_DIMENSION_CONSISTENCY_MISMATCH",
                            "total_pieces_per_pack (" + actualTotal + ") does not equal pieces * strips (" + expectedTotal + ").",
                            "প্যাকের মোট পিস ও স্ট্রিপের হিসাবের গরমিল।"
                    );
                }
            } catch (Exception e) {
                return ValidationResult.error("ERR_DIMENSION_CONSISTENCY_MISMATCH", "Invalid total pieces integer.", "মোট পিস পূর্ণসংখ্যা হতে হবে।");
            }
        }

        boolean requiresRefrig = Boolean.TRUE.equals(payload.get("requires_refrigeration"));
        String storage = (String) payload.get("storage_condition");
        if (requiresRefrig && storage != null && !storage.equals("REFRIGERATED_2_8") && !storage.equals("COOL_BELOW_25")) {
            return ValidationResult.error(
                    "ERR_MELT_PROTECTION_STORAGE_REQUIRED",
                    "Suppositories requiring refrigeration must specify REFRIGERATED_2_8 or COOL_BELOW_25 storage condition.",
                    "সাপোজিটরি গলে যাওয়া প্রতিরোধে ঠান্ডা বা ফ্রিজ স্টোরেজ আবশ্যক।"
            );
        }

        return ValidationResult.valid();
    }
}
