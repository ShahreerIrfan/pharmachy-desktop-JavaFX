package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * PosInhalerEditorClient
 * Architecture Decision: ADR-0146 (P146).
 * Phase 08: Milestone M04 / M05 - Specialized Dosage-Form Editors.
 *
 * Client-side integration for JavaFX Desktop POS managing Inhaler (MDI, DPI, SMI)
 * configurations, actuation doses, total puffs, dose counter status, and dimension consistency.
 */
public class PosInhalerEditorClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class InhalerRecord {
        public final String configId;
        public final String productId;
        public final String tenantId;
        public final String deviceType; // MDI_PRESSURIZED, DPI_DRY_POWDER, SMI_SOFT_MIST, BREATH_ACTUATED_MDI, NEBULIZER_AMPOULE
        public final String administrationRoute; // ORAL_INHALATION, NASAL_INHALATION
        public final BigDecimal dosePerActuation;
        public final String doseUnit;
        public final int totalActuations;
        public final BigDecimal totalActiveContent;
        public final boolean hasDoseCounter;
        public final boolean hasSpacerIncluded;
        public final String storageCondition;
        public final boolean primingRequired;
        public final int primingPuffs;
        public final boolean canisterPressureWarning;
        public final String patientInstructionsEn;
        public final String patientInstructionsBn;
        public final boolean isActive;

        public InhalerRecord(
                String configId,
                String productId,
                String tenantId,
                String deviceType,
                String administrationRoute,
                BigDecimal dosePerActuation,
                String doseUnit,
                int totalActuations,
                BigDecimal totalActiveContent,
                boolean hasDoseCounter,
                boolean hasSpacerIncluded,
                String storageCondition,
                boolean primingRequired,
                int primingPuffs,
                boolean canisterPressureWarning,
                String patientInstructionsEn,
                String patientInstructionsBn,
                boolean isActive) {
            this.configId = configId;
            this.productId = productId;
            this.tenantId = tenantId;
            this.deviceType = deviceType;
            this.administrationRoute = administrationRoute;
            this.dosePerActuation = dosePerActuation;
            this.doseUnit = doseUnit;
            this.totalActuations = totalActuations;
            this.totalActiveContent = totalActiveContent;
            this.hasDoseCounter = hasDoseCounter;
            this.hasSpacerIncluded = hasSpacerIncluded;
            this.storageCondition = storageCondition;
            this.primingRequired = primingRequired;
            this.primingPuffs = primingPuffs;
            this.canisterPressureWarning = canisterPressureWarning;
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

    public static final Set<String> ALLOWED_DEVICE_TYPES = Set.of(
            "MDI_PRESSURIZED", "DPI_DRY_POWDER", "SMI_SOFT_MIST", "BREATH_ACTUATED_MDI", "NEBULIZER_AMPOULE"
    );

    public static final Set<String> ALLOWED_ROUTES = Set.of(
            "ORAL_INHALATION", "NASAL_INHALATION"
    );

    public ValidationResult validateDraftPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return ValidationResult.error("ERR_EMPTY_PAYLOAD", "Payload cannot be empty", "পে-লোড খালি হতে পারে না");
        }

        for (String field : FORBIDDEN_SOLID_FIELDS) {
            if (payload.containsKey(field) && payload.get(field) != null) {
                return ValidationResult.error(
                        "ERR_HIDDEN_FIELD_VALUE_REJECTED",
                        "Field '" + field + "' is illegal for inhaler dosage form.",
                        "ইনহেলার ডোজ ফর্মের জন্য '" + field + "' ফিল্ডটি নিষিদ্ধ।"
                );
            }
        }

        String deviceType = (String) payload.get("device_type");
        if (deviceType == null || !ALLOWED_DEVICE_TYPES.contains(deviceType)) {
            return ValidationResult.error("ERR_INVALID_DEVICE_TYPE", "Invalid device type.", "অবৈধ ডিভাইস টাইপ।");
        }

        String route = (String) payload.get("administration_route");
        if (route == null || !ALLOWED_ROUTES.contains(route)) {
            return ValidationResult.error("ERR_INVALID_ADMINISTRATION_ROUTE", "Route must be ORAL_INHALATION or NASAL_INHALATION.", "অবৈধ ইনহেলার রুট।");
        }

        Object doseObj = payload.get("dose_per_actuation");
        if (doseObj == null) {
            return ValidationResult.error("ERR_INVALID_DOSE_PER_ACTUATION", "dose_per_actuation is required.", "ডোজ আবশ্যক।");
        }
        BigDecimal dose;
        try {
            dose = new BigDecimal(doseObj.toString());
            if (dose.compareTo(BigDecimal.ZERO) <= 0) {
                return ValidationResult.error("ERR_INVALID_DOSE_PER_ACTUATION", "dose_per_actuation must be greater than 0.", "ডোজ শূন্যের চেয়ে বড় হতে হবে।");
            }
        } catch (Exception e) {
            return ValidationResult.error("ERR_INVALID_DOSE_PER_ACTUATION", "dose_per_actuation must be numeric.", "ডোজ সংখ্যা হতে হবে।");
        }

        Object actuationsObj = payload.get("total_actuations");
        int actuations;
        if (actuationsObj == null) {
            return ValidationResult.error("ERR_INVALID_ACTUATIONS_COUNT", "total_actuations is required.", "মোট পাফ সংখ্যা আবশ্যক।");
        }
        try {
            actuations = Integer.parseInt(actuationsObj.toString());
            if (actuations < 10 || actuations > 1000) {
                return ValidationResult.error("ERR_INVALID_ACTUATIONS_COUNT", "total_actuations must be between 10 and 1000.", "পাফ সংখ্যা ১০ থেকে ১০০০ এর মধ্যে হতে হবে।");
            }
        } catch (Exception e) {
            return ValidationResult.error("ERR_INVALID_ACTUATIONS_COUNT", "total_actuations must be integer.", "পাফ সংখ্যা পূর্ণসংখ্যা হতে হবে।");
        }

        Object totalContentObj = payload.get("total_active_content");
        if (totalContentObj != null && !totalContentObj.toString().trim().isEmpty()) {
            try {
                BigDecimal actualTotal = new BigDecimal(totalContentObj.toString()).setScale(4, RoundingMode.HALF_UP);
                BigDecimal expectedTotal = dose.multiply(new BigDecimal(actuations)).setScale(4, RoundingMode.HALF_UP);
                if (actualTotal.compareTo(expectedTotal) != 0) {
                    return ValidationResult.error(
                            "ERR_DIMENSION_CONSISTENCY_MISMATCH",
                            "total_active_content (" + actualTotal + ") does not equal dose * actuations (" + expectedTotal + ").",
                            "মোট ওষুধের পরিমাণ ও পাফের হিসাবের অসঙ্গতি।"
                    );
                }
            } catch (Exception e) {
                return ValidationResult.error("ERR_DIMENSION_CONSISTENCY_MISMATCH", "Invalid total active content decimal.", "মোট পরিমাণ সংখ্যা হতে হবে।");
            }
        }

        return ValidationResult.valid();
    }
}

