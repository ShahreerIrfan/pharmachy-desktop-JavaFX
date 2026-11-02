package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.util.*;

/**
 * PosMedicineEditorSchemaClient
 * Architecture Decision: ADR-0124 (P124).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * Client-side integration for JavaFX Desktop POS evaluating server-driven medicine editor schema,
 * dynamic section visibility, field-level requirements, and conditional validation rules.
 */
public class PosMedicineEditorSchemaClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class SchemaField {
        public final String fieldId;
        public final String labelEn;
        public final String labelBn;
        public final String fieldType;
        public final boolean isRequired;
        public final Object defaultValue;

        public SchemaField(String fieldId, String labelEn, String labelBn, String fieldType, boolean isRequired, Object defaultValue) {
            this.fieldId = fieldId;
            this.labelEn = labelEn;
            this.labelBn = labelBn;
            this.fieldType = fieldType;
            this.isRequired = isRequired;
            this.defaultValue = defaultValue;
        }
    }

    public static final class SchemaSection {
        public final String sectionId;
        public final String titleEn;
        public final String titleBn;
        public final int displayOrder;
        public final List<SchemaField> fields;

        public SchemaSection(String sectionId, String titleEn, String titleBn, int displayOrder, List<SchemaField> fields) {
            this.sectionId = sectionId;
            this.titleEn = titleEn;
            this.titleBn = titleBn;
            this.displayOrder = displayOrder;
            this.fields = fields != null ? fields : Collections.emptyList();
        }
    }

    public static final class MedicineEditorSchemaRecord {
        public final String schemaId;
        public final String schemaVersion;
        public final String titleEn;
        public final String titleBn;
        public final boolean isActive;
        public final List<SchemaSection> sections;

        public MedicineEditorSchemaRecord(String schemaId, String schemaVersion, String titleEn, String titleBn, boolean isActive, List<SchemaSection> sections) {
            this.schemaId = schemaId;
            this.schemaVersion = schemaVersion;
            this.titleEn = titleEn;
            this.titleBn = titleBn;
            this.isActive = isActive;
            this.sections = sections != null ? sections : Collections.emptyList();
        }
    }

    public static final class ValidationResult {
        public final UiStatus uiStatus;
        public final boolean isValid;
        public final String schemaVersion;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;
        public final List<String> missingFields;

        public ValidationResult(UiStatus uiStatus, boolean isValid, String schemaVersion, String errorCode, String messageEn, String messageBn, List<String> missingFields) {
            this.uiStatus = uiStatus;
            this.isValid = isValid;
            this.schemaVersion = schemaVersion;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
            this.missingFields = missingFields != null ? missingFields : Collections.emptyList();
        }

        public static ValidationResult success(String version) {
            return new ValidationResult(
                    UiStatus.SUCCESS,
                    true,
                    version,
                    null,
                    "Form validation passed.",
                    "ফর্মের তথ্য ভ্যালিডেশন সফল হয়েছে।",
                    Collections.emptyList()
            );
        }

        public static ValidationResult error(String code, String msgEn, String msgBn, List<String> missingFields) {
            return new ValidationResult(
                    UiStatus.ERROR,
                    false,
                    "v1.0",
                    code,
                    msgEn,
                    msgBn,
                    missingFields
            );
        }
    }

    private MedicineEditorSchemaRecord activeSchema;

    public PosMedicineEditorSchemaClient() {
        initDefaultSchema();
    }

    private void initDefaultSchema() {
        List<SchemaSection> sections = new ArrayList<>();

        // Basic Info
        sections.add(new SchemaSection(
                "basic_info", "Basic Information", "প্রাথমিক তথ্য", 1,
                Arrays.asList(
                        new SchemaField("brand_name", "Brand Name", "ব্র্যান্ড নাম", "text", true, ""),
                        new SchemaField("generic_name", "Generic Name", "জেনেরিক নাম", "text", true, ""),
                        new SchemaField("dosage_form", "Dosage Form", "ডোসেজ ফরম", "select", true, "TABLET"),
                        new SchemaField("manufacturer", "Manufacturer", "উত্পাদক প্রতিষ্ঠান", "text", true, "")
                )
        ));

        // Strength
        sections.add(new SchemaSection(
                "strength_spec", "Dosage Strength", "মাত্রার বিবরণ", 2,
                Arrays.asList(
                        new SchemaField("strength", "Strength String", "মাত্রার সংকেত", "text", true, "500 mg"),
                        new SchemaField("strength_numeric", "Numeric Strength", "সংখ্যার মান", "decimal", true, new BigDecimal("500.0")),
                        new SchemaField("strength_unit", "Measurement Unit", "পরিমাপের একক", "select", true, "mg")
                )
        ));

        // Packaging
        sections.add(new SchemaSection(
                "packaging_config", "Packaging & Conversion", "প্যাকেজিং ও রূপান্তর", 3,
                Arrays.asList(
                        new SchemaField("is_strip_applicable", "Strip Packaging", "পাতা প্রযোজ্য", "boolean", false, true),
                        new SchemaField("strips_per_box", "Strips Per Box", "প্রতি বাক্সে পাতা", "number", false, 10),
                        new SchemaField("units_per_strip", "Units Per Strip", "প্রতি পাতায় ট্যাবলেট", "number", false, 10),
                        new SchemaField("volume_ml", "Volume (mL)", "আয়তন (মিলি)", "decimal", false, null)
                )
        ));

        // Cold Chain
        sections.add(new SchemaSection(
                "cold_chain_spec", "Cold Chain & Storage", "কোল্ড চেইন ও সংরক্ষণ", 4,
                Arrays.asList(
                        new SchemaField("is_cold_chain", "Requires Cold Chain", "কোল্ড চেইন প্রযোজ্য", "boolean", false, false),
                        new SchemaField("min_temperature_celsius", "Min Temperature (°C)", "সর্বনিম্ন তাপমাত্রা", "decimal", false, new BigDecimal("2.0")),
                        new SchemaField("max_temperature_celsius", "Max Temperature (°C)", "সর্বোচ্চ তাপমাত্রা", "decimal", false, new BigDecimal("8.0"))
                )
        ));

        this.activeSchema = new MedicineEditorSchemaRecord(
                "med-schema-v1.0", "v1.0", "Standard Medicine Master Editor", "স্ট্যান্ডার্ড ঔষধ বিবরণী ও মাস্টার ফর্ম", true, sections
        );
    }

    public MedicineEditorSchemaRecord getActiveSchema() {
        return activeSchema;
    }

    /**
     * Determines whether a field is visible based on active conditional rules.
     */
    public boolean isFieldVisible(String fieldId, Map<String, Object> formValues) {
        String dosageForm = String.valueOf(formValues.getOrDefault("dosage_form", "")).toUpperCase();
        boolean isColdChain = Boolean.TRUE.equals(formValues.get("is_cold_chain"));

        switch (fieldId) {
            case "strips_per_box":
            case "units_per_strip":
                return "TABLET".equals(dosageForm) || "CAPSULE".equals(dosageForm);
            case "volume_ml":
                return "SYRUP".equals(dosageForm) || "SUSPENSION".equals(dosageForm) || "SOLUTION".equals(dosageForm) || "DROPS".equals(dosageForm);
            case "min_temperature_celsius":
            case "max_temperature_celsius":
                return isColdChain;
            default:
                return true;
        }
    }

    /**
     * Determines whether a field is required based on active conditional rules.
     */
    public boolean isFieldRequired(String fieldId, Map<String, Object> formValues) {
        String dosageForm = String.valueOf(formValues.getOrDefault("dosage_form", "")).toUpperCase();
        boolean isColdChain = Boolean.TRUE.equals(formValues.get("is_cold_chain"));

        switch (fieldId) {
            case "brand_name":
            case "generic_name":
            case "dosage_form":
            case "manufacturer":
            case "strength":
                return true;
            case "strips_per_box":
            case "units_per_strip":
                return "TABLET".equals(dosageForm) || "CAPSULE".equals(dosageForm);
            case "volume_ml":
                return "SYRUP".equals(dosageForm) || "SUSPENSION".equals(dosageForm) || "SOLUTION".equals(dosageForm) || "DROPS".equals(dosageForm);
            case "min_temperature_celsius":
            case "max_temperature_celsius":
                return isColdChain;
            default:
                return false;
        }
    }

    /**
     * Validates form values locally against schema rules and conditional invariants.
     */
    public ValidationResult validateForm(Map<String, Object> formValues) {
        List<String> missing = new ArrayList<>();
        String[] baseRequired = {"brand_name", "generic_name", "dosage_form", "manufacturer", "strength"};

        for (String field : baseRequired) {
            Object val = formValues.get(field);
            if (val == null || val.toString().trim().isEmpty()) {
                missing.add(field);
            }
        }

        if (!missing.isEmpty()) {
            return ValidationResult.error(
                    "ERR_SCHEMA_VALIDATION_FAILED",
                    "Required fields missing: " + String.join(", ", missing),
                    "আবশ্যকীয় ফিল্ডসমূহ পূরণ করুন।",
                    missing
            );
        }

        String dosageForm = String.valueOf(formValues.get("dosage_form")).toUpperCase();

        // Strip packaging check for tablets
        if ("TABLET".equals(dosageForm) || "CAPSULE".equals(dosageForm)) {
            Object strips = formValues.get("strips_per_box");
            Object units = formValues.get("units_per_strip");
            if (strips == null || !(strips instanceof Number) || ((Number) strips).intValue() <= 0) {
                return ValidationResult.error(
                        "ERR_CONDITIONAL_FIELD_REQUIRED",
                        "Strips per box is required for " + dosageForm + " and must be > 0.",
                        "প্রতি বাক্সে পাতা/স্ট্রিপ সংখ্যা আবশ্যক।",
                        Collections.singletonList("strips_per_box")
                );
            }
            if (units == null || !(units instanceof Number) || ((Number) units).intValue() <= 0) {
                return ValidationResult.error(
                        "ERR_CONDITIONAL_FIELD_REQUIRED",
                        "Units per strip is required for " + dosageForm + " and must be > 0.",
                        "প্রতি পাতায় ট্যাবলেটের সংখ্যা আবশ্যক।",
                        Collections.singletonList("units_per_strip")
                );
            }
        }

        // Volume check for syrups
        if ("SYRUP".equals(dosageForm) || "SUSPENSION".equals(dosageForm)) {
            Object vol = formValues.get("volume_ml");
            if (vol == null || !(vol instanceof Number) || ((Number) vol).doubleValue() <= 0) {
                return ValidationResult.error(
                        "ERR_CONDITIONAL_FIELD_REQUIRED",
                        "Volume in mL is required for " + dosageForm + " and must be > 0.",
                        "মোট আয়তন (মিলি) প্রদান আবশ্যক।",
                        Collections.singletonList("volume_ml")
                );
            }
        }

        // Cold chain check
        if (Boolean.TRUE.equals(formValues.get("is_cold_chain"))) {
            Object minT = formValues.get("min_temperature_celsius");
            Object maxT = formValues.get("max_temperature_celsius");
            if (minT == null || maxT == null || !(minT instanceof Number) || !(maxT instanceof Number)) {
                return ValidationResult.error(
                        "ERR_CONDITIONAL_FIELD_REQUIRED",
                        "Cold chain storage requires min and max temperature thresholds.",
                        "সর্বনিম্ন ও সর্বোচ্চ তাপমাত্রা আবশ্যক।",
                        Arrays.asList("min_temperature_celsius", "max_temperature_celsius")
                );
            }
            double minVal = ((Number) minT).doubleValue();
            double maxVal = ((Number) maxT).doubleValue();
            if (minVal >= maxVal) {
                return ValidationResult.error(
                        "ERR_INVALID_TEMPERATURE_RANGE",
                        "Min temperature (" + minVal + "°C) must be strictly less than max temperature (" + maxVal + "°C).",
                        "সর্বনিম্ন তাপমাত্রা সর্বোচ্চ তাপমাত্রা অপেক্ষা কম হতে হবে।",
                        Arrays.asList("min_temperature_celsius", "max_temperature_celsius")
                );
            }
        }

        return ValidationResult.success(activeSchema.schemaVersion);
    }
}
