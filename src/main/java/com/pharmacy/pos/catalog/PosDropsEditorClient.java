package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.util.*;

/**
 * PosDropsEditorClient
 * Architecture Decision: ADR-0147 (P147).
 * Phase 08: Milestone M04 / M05 - Specialized Dosage-Form Editors.
 *
 * Client-side integration for JavaFX Desktop POS managing ophthalmic, otic,
 * and nasal drops configurations, fill volumes, preservative checks, and
 * opened-container shelf life (BUD).
 */
public class PosDropsEditorClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class DropsRecord {
        public final String configId;
        public final String productId;
        public final String tenantId;
        public final String route; // OPHTHALMIC, OTIC, NASAL
        public final String containerType; // DROPPER_BOTTLE, SINGLE_DOSE_MINIM, SPRAY_PUMP
        public final BigDecimal volumeMl;
        public final BigDecimal concentrationValue;
        public final String concentrationUnit;
        public final boolean isPreservativeFree;
        public final String preservativeName;
        public final int openedShelfLifeDays;
        public final String storageTemp;
        public final String patientInstructionsEn;
        public final String patientInstructionsBn;
        public final boolean isActive;

        public DropsRecord(
                String configId,
                String productId,
                String tenantId,
                String route,
                String containerType,
                BigDecimal volumeMl,
                BigDecimal concentrationValue,
                String concentrationUnit,
                boolean isPreservativeFree,
                String preservativeName,
                int openedShelfLifeDays,
                String storageTemp,
                String patientInstructionsEn,
                String patientInstructionsBn,
                boolean isActive) {
            this.configId = configId;
            this.productId = productId;
            this.tenantId = tenantId;
            this.route = route;
            this.containerType = containerType;
            this.volumeMl = volumeMl;
            this.concentrationValue = concentrationValue;
            this.concentrationUnit = concentrationUnit;
            this.isPreservativeFree = isPreservativeFree;
            this.preservativeName = preservativeName != null ? preservativeName : "";
            this.openedShelfLifeDays = openedShelfLifeDays;
            this.storageTemp = storageTemp;
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
            "box_size", "loose_sale_eligible", "is_scored", "coating_type", "needle_gauge"
    );

    public ValidationResult validateDraftPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return ValidationResult.error("ERR_EMPTY_PAYLOAD", "Payload cannot be empty", "পে-লোড খালি হতে পারে না");
        }

        for (String field : FORBIDDEN_SOLID_FIELDS) {
            if (payload.containsKey(field) && payload.get(field) != null) {
                return ValidationResult.error(
                        "ERR_HIDDEN_FIELD_VALUE_REJECTED",
                        "Field '" + field + "' is illegal for liquid drops dosage form.",
                        "ড্রপ ডোজ ফর্মের জন্য '" + field + "' ফিল্ডটি নিষিদ্ধ।"
                );
            }
        }

        String route = (String) payload.get("route");
        if (route == null || (!route.equals("OPHTHALMIC") && !route.equals("OTIC") && !route.equals("NASAL"))) {
            return ValidationResult.error("ERR_INVALID_DROPS_ROUTE", "Route must be OPHTHALMIC, OTIC, or NASAL.", "অবৈধ ড্রপ রুট।");
        }

        String containerType = (String) payload.get("container_type");
        if (containerType == null || (!containerType.equals("DROPPER_BOTTLE") && !containerType.equals("SINGLE_DOSE_MINIM") && !containerType.equals("SPRAY_PUMP"))) {
            return ValidationResult.error("ERR_INVALID_CONTAINER_TYPE", "Invalid container type.", "অবৈধ কন্টেইনার টাইপ।");
        }

        Object volObj = payload.get("volume_ml");
        if (volObj == null) {
            return ValidationResult.error("ERR_INVALID_VOLUME", "volume_ml is required.", "ভলিউম আবশ্যক।");
        }
        try {
            BigDecimal vol = new BigDecimal(volObj.toString());
            if (vol.compareTo(BigDecimal.ZERO) <= 0) {
                return ValidationResult.error("ERR_INVALID_VOLUME", "volume_ml must be greater than 0.", "ভলিউম শূন্যের চেয়ে বড় হতে হবে।");
            }
        } catch (Exception e) {
            return ValidationResult.error("ERR_INVALID_VOLUME", "volume_ml must be numeric.", "ভলিউম সংখ্যা হতে হবে।");
        }

        Object budObj = payload.get("opened_shelf_life_days");
        int bud = 28;
        if (budObj != null) {
            try {
                bud = Integer.parseInt(budObj.toString());
                if (bud < 1 || bud > 60) {
                    return ValidationResult.error("ERR_INVALID_OPENED_SHELF_LIFE", "opened_shelf_life_days must be 1 to 60 days.", "মেয়াদ ১ থেকে ৬০ দিন হতে হবে।");
                }
            } catch (Exception e) {
                return ValidationResult.error("ERR_INVALID_OPENED_SHELF_LIFE", "opened_shelf_life_days must be integer.", "মেয়াদ পূর্ণসংখ্যা হতে হবে।");
            }
        }

        if ("SINGLE_DOSE_MINIM".equals(containerType) && bud != 1) {
            return ValidationResult.error("ERR_SINGLE_DOSE_MINIM_EXPIRY", "Single-dose minim must have opened_shelf_life_days = 1.", "সিঙ্গেল-ডোজ মিনিমের ব্যবহারের মেয়াদ ১ দিন।");
        }

        return ValidationResult.valid();
    }
}

