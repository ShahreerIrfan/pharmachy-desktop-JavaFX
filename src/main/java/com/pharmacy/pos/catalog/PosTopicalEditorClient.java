package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.util.*;

/**
 * PosTopicalEditorClient
 * Architecture Decision: ADR-0148 (P148).
 * Phase 08: Milestone M04 / M05 - Specialized Dosage-Form Editors.
 *
 * Client-side integration for JavaFX Desktop POS managing Creams, Ointments,
 * Gels, and Lotions, net fill weights, topical routes, and sterility governance.
 */
public class PosTopicalEditorClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class TopicalRecord {
        public final String configId;
        public final String productId;
        public final String tenantId;
        public final String formulationType; // CREAM, OINTMENT, GEL, LOTION, PASTE
        public final String administrationRoute; // TOPICAL_CUTANEOUS, TOPICAL_OPHTHALMIC, TOPICAL_RECTAL, TOPICAL_VAGINAL
        public final String containerType; // TUBE_ALUMINUM, TUBE_PLASTIC, JAR_POT, BOTTLE_PUMP, SACHET
        public final BigDecimal netWeightGrams;
        public final BigDecimal concentrationValue;
        public final String concentrationUnit;
        public final boolean isSterile;
        public final boolean applicatorIncluded;
        public final int openedShelfLifeDays;
        public final String storageCondition;
        public final String patientInstructionsEn;
        public final String patientInstructionsBn;
        public final boolean isActive;

        public TopicalRecord(
                String configId,
                String productId,
                String tenantId,
                String formulationType,
                String administrationRoute,
                String containerType,
                BigDecimal netWeightGrams,
                BigDecimal concentrationValue,
                String concentrationUnit,
                boolean isSterile,
                boolean applicatorIncluded,
                int openedShelfLifeDays,
                String storageCondition,
                String patientInstructionsEn,
                String patientInstructionsBn,
                boolean isActive) {
            this.configId = configId;
            this.productId = productId;
            this.tenantId = tenantId;
            this.formulationType = formulationType;
            this.administrationRoute = administrationRoute;
            this.containerType = containerType;
            this.netWeightGrams = netWeightGrams;
            this.concentrationValue = concentrationValue;
            this.concentrationUnit = concentrationUnit;
            this.isSterile = isSterile;
            this.applicatorIncluded = applicatorIncluded;
            this.openedShelfLifeDays = openedShelfLifeDays;
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
            "box_size", "loose_sale_eligible", "is_scored", "coating_type", "needle_gauge"
    );

    public static final Set<String> ALLOWED_FORMULATIONS = Set.of(
            "CREAM", "OINTMENT", "GEL", "LOTION", "PASTE"
    );

    public static final Set<String> ALLOWED_ROUTES = Set.of(
            "TOPICAL_CUTANEOUS", "TOPICAL_OPHTHALMIC", "TOPICAL_RECTAL", "TOPICAL_VAGINAL"
    );

    public static final Set<String> ALLOWED_CONTAINERS = Set.of(
            "TUBE_ALUMINUM", "TUBE_PLASTIC", "JAR_POT", "BOTTLE_PUMP", "SACHET"
    );

    public ValidationResult validateDraftPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return ValidationResult.error("ERR_EMPTY_PAYLOAD", "Payload cannot be empty", "পে-লোড খালি হতে পারে না");
        }

        for (String field : FORBIDDEN_SOLID_FIELDS) {
            if (payload.containsKey(field) && payload.get(field) != null) {
                return ValidationResult.error(
                        "ERR_HIDDEN_FIELD_VALUE_REJECTED",
                        "Field '" + field + "' is illegal for topical dosage form.",
                        "টপিকাল ডোজ ফর্মের জন্য '" + field + "' ফিল্ডটি নিষিদ্ধ।"
                );
            }
        }

        String formulation = (String) payload.get("formulation_type");
        if (formulation == null || !ALLOWED_FORMULATIONS.contains(formulation)) {
            return ValidationResult.error("ERR_INVALID_FORMULATION_TYPE", "Invalid formulation type.", "অবৈধ ফর্মুলেশন টাইপ।");
        }

        String route = (String) payload.get("administration_route");
        if (route == null || !ALLOWED_ROUTES.contains(route)) {
            return ValidationResult.error("ERR_INVALID_TOPICAL_ROUTE", "Route must be a valid topical route.", "অবৈধ টপিকাল রুট।");
        }

        String containerType = (String) payload.get("container_type");
        if (containerType == null || !ALLOWED_CONTAINERS.contains(containerType)) {
            return ValidationResult.error("ERR_INVALID_CONTAINER_TYPE", "Invalid container type.", "অবৈধ কন্টেইনার টাইপ।");
        }

        Object weightObj = payload.get("net_weight_grams");
        if (weightObj == null) {
            return ValidationResult.error("ERR_INVALID_NET_WEIGHT", "net_weight_grams is required.", "ওজন আবশ্যক।");
        }
        try {
            BigDecimal weight = new BigDecimal(weightObj.toString());
            if (weight.compareTo(BigDecimal.ZERO) <= 0) {
                return ValidationResult.error("ERR_INVALID_NET_WEIGHT", "net_weight_grams must be greater than 0.", "ওজন শূন্যের চেয়ে বড় হতে হবে।");
            }
        } catch (Exception e) {
            return ValidationResult.error("ERR_INVALID_NET_WEIGHT", "net_weight_grams must be numeric.", "ওজন সংখ্যা হতে হবে।");
        }

        Object concObj = payload.get("concentration_value");
        if (concObj == null) {
            return ValidationResult.error("ERR_INVALID_CONCENTRATION", "concentration_value is required.", "ঘনমাত্রা আবশ্যক।");
        }
        try {
            BigDecimal conc = new BigDecimal(concObj.toString());
            if (conc.compareTo(BigDecimal.ZERO) <= 0) {
                return ValidationResult.error("ERR_INVALID_CONCENTRATION", "concentration_value must be greater than 0.", "ঘনমাত্রা শূন্যের চেয়ে বড় হতে হবে।");
            }
        } catch (Exception e) {
            return ValidationResult.error("ERR_INVALID_CONCENTRATION", "concentration_value must be numeric.", "ঘনমাত্রা সংখ্যা হতে হবে।");
        }

        boolean isSterile = Boolean.TRUE.equals(payload.get("is_sterile"));
        if ("TOPICAL_OPHTHALMIC".equals(route) && !isSterile) {
            return ValidationResult.error("ERR_OPHTHALMIC_STERILITY_MANDATORY", "Ophthalmic ointments must be certified sterile.", "চোখের মলম অবশ্যই জীবাণুমুক্ত হতে হবে।");
        }

        Object budObj = payload.get("opened_shelf_life_days");
        int bud = 28;
        if (budObj != null) {
            try {
                bud = Integer.parseInt(budObj.toString());
                if ("TOPICAL_OPHTHALMIC".equals(route) && bud > 28) {
                    return ValidationResult.error("ERR_INVALID_OPENED_SHELF_LIFE", "Ophthalmic opened shelf life cannot exceed 28 days.", "চোখের মলম খোলার মেয়াদ সর্বোচ্চ ২৮ দিন।");
                }
                if (bud < 7 || bud > 180) {
                    return ValidationResult.error("ERR_INVALID_OPENED_SHELF_LIFE", "opened_shelf_life_days must be between 7 and 180 days.", "মেয়াদ ৭ থেকে ১৮০ দিন হতে হবে।");
                }
            } catch (Exception e) {
                return ValidationResult.error("ERR_INVALID_OPENED_SHELF_LIFE", "opened_shelf_life_days must be integer.", "মেয়াদ পূর্ণসংখ্যা হতে হবে।");
            }
        }

        return ValidationResult.valid();
    }
}

