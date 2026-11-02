package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.util.*;

/**
 * PosDynamicConditionEvaluator
 * Architecture Decision: ADR-0125 (P125).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * Client-side dynamic condition evaluation and zero-trust hidden field guard for JavaFX Desktop POS.
 * Ensures hidden fields are neither trusted nor permitted to pollute catalog or inventory data.
 */
public class PosDynamicConditionEvaluator {

    public enum UiStatus {
        SUCCESS,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final Set<String> ALLOWED_DOSAGE_FORMS = new HashSet<>(Arrays.asList(
            "TABLET", "CAPSULE", "SYRUP", "SUSPENSION", "INJECTION", "EYE_DROPS", "OINTMENT", "INHALER", "SUPPOSITORY"
    ));

    public static final Set<String> ALLOWED_STRENGTH_UNITS = new HashSet<>(Arrays.asList(
            "mg", "mcg", "g", "ml", "l", "%", "iu", "iu/ml", "mg/5ml", "mg/ml"
    ));

    public static final class EvaluationResult {
        public final UiStatus uiStatus;
        public final boolean isValid;
        public final String schemaVersion;
        public final List<String> activeFields;
        public final List<String> hiddenFields;
        public final List<String> prunedHiddenFields;
        public final Map<String, Object> sanitizedPayload;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public EvaluationResult(
                UiStatus uiStatus,
                boolean isValid,
                String schemaVersion,
                List<String> activeFields,
                List<String> hiddenFields,
                List<String> prunedHiddenFields,
                Map<String, Object> sanitizedPayload,
                String errorCode,
                String messageEn,
                String messageBn) {
            this.uiStatus = uiStatus;
            this.isValid = isValid;
            this.schemaVersion = schemaVersion;
            this.activeFields = activeFields != null ? activeFields : Collections.emptyList();
            this.hiddenFields = hiddenFields != null ? hiddenFields : Collections.emptyList();
            this.prunedHiddenFields = prunedHiddenFields != null ? prunedHiddenFields : Collections.emptyList();
            this.sanitizedPayload = sanitizedPayload != null ? sanitizedPayload : Collections.emptyMap();
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static EvaluationResult success(
                String version,
                List<String> activeFields,
                List<String> hiddenFields,
                List<String> prunedFields,
                Map<String, Object> sanitized) {
            return new EvaluationResult(
                    UiStatus.SUCCESS,
                    true,
                    version,
                    activeFields,
                    hiddenFields,
                    prunedFields,
                    sanitized,
                    null,
                    "Payload passed dynamic condition evaluation and zero-trust hidden field guard.",
                    "পেলোডটি শর্তভিত্তিক মূল্যায়ন এবং হিডেন ফিল্ড সুরক্ষা সফলভাবে উত্তীর্ণ হয়েছে।"
            );
        }

        public static EvaluationResult error(
                String code,
                String msgEn,
                String msgBn,
                List<String> activeFields,
                List<String> hiddenFields) {
            return new EvaluationResult(
                    UiStatus.ERROR,
                    false,
                    "v1.0",
                    activeFields,
                    hiddenFields,
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    code,
                    msgEn,
                    msgBn
            );
        }
    }

    /**
     * Evaluates dynamic field conditions and enforces zero-trust hidden client fields.
     */
    public EvaluationResult evaluateAndValidate(Map<String, Object> payload, boolean strictHiddenCheck) {
        String dosageForm = String.valueOf(payload.getOrDefault("dosage_form", "")).toUpperCase();
        boolean isColdChain = Boolean.TRUE.equals(payload.get("is_cold_chain"));

        List<String> active = new ArrayList<>();
        List<String> hidden = new ArrayList<>();

        // Base fields always active
        active.addAll(Arrays.asList("brand_name", "generic_name", "dosage_form", "manufacturer", "strength", "strength_numeric", "strength_unit", "is_strip_applicable", "is_cold_chain", "is_prescription_required", "is_antibiotic", "is_narcotic"));

        // Dosage form packaging conditions
        if ("TABLET".equals(dosageForm) || "CAPSULE".equals(dosageForm)) {
            active.add("strips_per_box");
            active.add("units_per_strip");
            hidden.add("volume_ml");
        } else if ("SYRUP".equals(dosageForm) || "SUSPENSION".equals(dosageForm) || "SOLUTION".equals(dosageForm) || "DROPS".equals(dosageForm)) {
            active.add("volume_ml");
            hidden.add("strips_per_box");
            hidden.add("units_per_strip");
        } else {
            hidden.add("strips_per_box");
            hidden.add("units_per_strip");
            hidden.add("volume_ml");
        }

        // Cold chain conditions
        if (isColdChain) {
            active.add("min_temperature_celsius");
            active.add("max_temperature_celsius");
        } else {
            hidden.add("min_temperature_celsius");
            hidden.add("max_temperature_celsius");
        }

        Map<String, Object> sanitized = new HashMap<>();
        List<String> pruned = new ArrayList<>();

        // ZERO-TRUST HIDDEN FIELD GUARD
        for (String hField : hidden) {
            if (payload.containsKey(hField)) {
                Object val = payload.get(hField);
                if (val != null && !val.toString().trim().isEmpty() && !Boolean.FALSE.equals(val)) {
                    if (strictHiddenCheck) {
                        return EvaluationResult.error(
                                "ERR_HIDDEN_FIELD_VALUE_REJECTED",
                                String.format("Field '%s' is inactive/hidden under %s conditions and must not contain data.", hField, dosageForm),
                                String.format("বর্তমান শর্তানুসারে '%s' ফিল্ডটি প্রযোজ্য নয় এবং এতে কোনো তথ্য রাখা নিষিদ্ধ।", hField),
                                active,
                                hidden
                        );
                    } else {
                        pruned.add(hField);
                    }
                }
            }
            sanitized.put(hField, null);
        }

        // Copy and validate active fields
        for (String aField : active) {
            if (payload.containsKey(aField)) {
                Object val = payload.get(aField);

                // Type safety checks
                if ("dosage_form".equals(aField)) {
                    String df = String.valueOf(val).toUpperCase();
                    if (!ALLOWED_DOSAGE_FORMS.contains(df)) {
                        return EvaluationResult.error(
                                "ERR_INVALID_SELECT_OPTION",
                                "Dosage form '" + df + "' is not in allowed taxonomy.",
                                "ডোসেজ ফর্ম '" + df + "' অনুমোদিত নয়।",
                                active, hidden
                        );
                    }
                    sanitized.put(aField, df);
                } else if ("strips_per_box".equals(aField) || "units_per_strip".equals(aField)) {
                    if (val == null || !(val instanceof Number) || ((Number) val).intValue() <= 0) {
                        return EvaluationResult.error(
                                "ERR_INVALID_NUMBER_RANGE",
                                "Field '" + aField + "' must be a positive integer (> 0).",
                                "'" + aField + "' ফিল্ডটি অবশ্যই ধনাত্মক পূর্ণসংখ্যা হতে হবে।",
                                active, hidden
                        );
                    }
                    sanitized.put(aField, ((Number) val).intValue());
                } else if ("volume_ml".equals(aField)) {
                    if (val == null || !(val instanceof Number) || ((Number) val).doubleValue() <= 0) {
                        return EvaluationResult.error(
                                "ERR_INVALID_DECIMAL",
                                "Field 'volume_ml' must be a valid positive decimal (> 0).",
                                "'volume_ml' ফিল্ডটি অবশ্যই ধনাত্মক দশমিক সংখ্যা হতে হবে।",
                                active, hidden
                        );
                    }
                    sanitized.put(aField, ((Number) val).doubleValue());
                } else {
                    sanitized.put(aField, val);
                }
            }
        }

        // Check required fields
        String[] requiredBase = {"brand_name", "generic_name", "dosage_form", "manufacturer", "strength"};
        for (String req : requiredBase) {
            Object v = sanitized.get(req);
            if (v == null || v.toString().trim().isEmpty()) {
                return EvaluationResult.error(
                        "ERR_CONDITIONAL_FIELD_REQUIRED",
                        "Required field missing: " + req,
                        "আবশ্যক ফিল্ড অনুপস্থিত: " + req,
                        active, hidden
                );
            }
        }

        // Cold chain range check
        if (Boolean.TRUE.equals(sanitized.get("is_cold_chain"))) {
            Object minT = sanitized.get("min_temperature_celsius");
            Object maxT = sanitized.get("max_temperature_celsius");
            if (minT == null || maxT == null || !(minT instanceof Number) || !(maxT instanceof Number)) {
                return EvaluationResult.error(
                        "ERR_CONDITIONAL_FIELD_REQUIRED",
                        "Cold chain storage requires min and max temperature thresholds.",
                        "কোল্ড চেইন ঔষধের জন্য সর্বনিম্ন এবং সর্বোচ্চ তাপমাত্রা নির্ধারণ আবশ্যক।",
                        active, hidden
                );
            }
            double minV = ((Number) minT).doubleValue();
            double maxV = ((Number) maxT).doubleValue();
            if (minV >= maxV) {
                return EvaluationResult.error(
                        "ERR_INVALID_TEMPERATURE_RANGE",
                        "Min temperature (" + minV + "°C) must be strictly less than max temperature (" + maxV + "°C).",
                        "সর্বনিম্ন তাপমাত্রা সর্বোচ্চ তাপমাত্রা অপেক্ষা কম হতে হবে।",
                        active, hidden
                );
            }
        }

        // Antibiotic Rx rule
        if (Boolean.TRUE.equals(sanitized.get("is_antibiotic"))) {
            sanitized.put("is_prescription_required", true);
        }

        return EvaluationResult.success("v1.0", active, hidden, pruned, sanitized);
    }
}
