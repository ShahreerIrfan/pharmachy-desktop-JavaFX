package com.pharmacy.pos.payload;

import java.math.BigDecimal;
import java.util.*;

/**
 * PosFlexibleMedicinePayloadClient
 * Architecture Decision: ADR-0159 (P159).
 * Phase 08: Specialized Item & Multi-Tier Packaging Formulation Editors.
 *
 * Validates typed, versioned dynamic medicine payloads for JavaFX Desktop POS.
 * Rejects unknown or incompatible fields with field-level error codes.
 */
public class PosFlexibleMedicinePayloadClient {

    public static final Set<String> SUPPORTED_SCHEMA_VERSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "2026.01",
            "v2"
    )));

    public static final Set<String> COMMON_ATTRIBUTES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "brand_name",
            "brand_name_bn",
            "generic_name",
            "generic_name_bn",
            "manufacturer",
            "dar_number",
            "cost_unit",
            "mrp_unit",
            "vat_percentage",
            "is_prescription_only",
            "is_antibiotic",
            "is_narcotic",
            "requires_cold_chain",
            "storage_condition",
            "storage_temperature_celsius_min",
            "storage_temperature_celsius_max"
    )));

    public static final Set<String> MANDATORY_COMMON = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "brand_name",
            "generic_name",
            "dar_number",
            "cost_unit",
            "mrp_unit"
    )));

    public static final Map<String, Set<String>> FORM_SPECIFIC_ATTRIBUTES;
    public static final Map<String, Set<String>> MANDATORY_FORM_SPECIFIC;
    public static final Set<String> ALL_RECOGNIZED_FORM_ATTRIBUTES;

    static {
        Map<String, Set<String>> specifics = new HashMap<>();
        specifics.put("TABLET_CAPSULE", new HashSet<>(Arrays.asList(
                "pieces_per_strip", "strips_per_pack", "total_pieces_per_pack", "capsule_shell_type", "is_delayed_release"
        )));
        specifics.put("INHALER", new HashSet<>(Arrays.asList(
                "dose_per_actuation", "actuations_per_device", "canister_type"
        )));
        specifics.put("DROPS", new HashSet<>(Arrays.asList(
                "volume_ml", "drop_category", "is_preservative_free"
        )));
        specifics.put("TOPICAL_CREAM", new HashSet<>(Arrays.asList(
                "tube_size_grams", "application_site", "vehicle_base"
        )));
        specifics.put("SACHET_POWDER", new HashSet<>(Arrays.asList(
                "grams_per_sachet", "sachets_per_pack", "reconstitution_guidance"
        )));
        specifics.put("SUPPOSITORY", new HashSet<>(Arrays.asList(
                "piece_count", "suppository_base_type", "administration_route"
        )));
        specifics.put("DEVICE_CONSUMABLE", new HashSet<>(Arrays.asList(
                "device_type", "is_sterile", "gauge_size"
        )));
        specifics.put("SYRUP_SUSPENSION", new HashSet<>(Arrays.asList(
                "bottle_size_ml", "is_dry_powder", "reconstituted_expiry_days"
        )));
        specifics.put("INJECTION_INFUSION", new HashSet<>(Arrays.asList(
                "vial_volume_ml", "is_lyophilized", "diluent_required"
        )));
        FORM_SPECIFIC_ATTRIBUTES = Collections.unmodifiableMap(specifics);

        Map<String, Set<String>> mandatory = new HashMap<>();
        mandatory.put("INHALER", new HashSet<>(Arrays.asList("dose_per_actuation", "actuations_per_device")));
        mandatory.put("DROPS", new HashSet<>(Arrays.asList("volume_ml", "drop_category")));
        mandatory.put("TOPICAL_CREAM", new HashSet<>(Arrays.asList("tube_size_grams", "application_site")));
        mandatory.put("SACHET_POWDER", new HashSet<>(Arrays.asList("grams_per_sachet", "sachets_per_pack")));
        mandatory.put("SUPPOSITORY", new HashSet<>(Arrays.asList("piece_count", "administration_route")));
        mandatory.put("DEVICE_CONSUMABLE", new HashSet<>(Collections.singletonList("device_type")));
        MANDATORY_FORM_SPECIFIC = Collections.unmodifiableMap(mandatory);

        Set<String> allForms = new HashSet<>();
        for (Set<String> set : specifics.values()) {
            allForms.addAll(set);
        }
        ALL_RECOGNIZED_FORM_ATTRIBUTES = Collections.unmodifiableSet(allForms);
    }

    public static final class FieldError {
        public final String field;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public FieldError(String field, String errorCode, String messageEn, String messageBn) {
            this.field = field;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public String getField() { return field; }
        public String getErrorCode() { return errorCode; }
        public String getMessageEn() { return messageEn; }
        public String getMessageBn() { return messageBn; }
    }

    public static final class PayloadValidationReport {
        public final String schemaVersion;
        public final String dosageFormFamily;
        public final boolean isValid;
        public final int errorCount;
        public final List<FieldError> errors;

        public PayloadValidationReport(String schemaVersion, String dosageFormFamily, boolean isValid, List<FieldError> errors) {
            this.schemaVersion = schemaVersion;
            this.dosageFormFamily = dosageFormFamily;
            this.isValid = isValid;
            this.errors = errors != null ? Collections.unmodifiableList(errors) : Collections.emptyList();
            this.errorCount = this.errors.size();
        }
    }

    public boolean canMutate(String userRole) {
        if (userRole == null || userRole.trim().isEmpty()) {
            return false;
        }
        return !"CASHIER".equalsIgnoreCase(userRole.trim());
    }

    public PayloadValidationReport validatePayload(String schemaVersion, String dosageFormFamily, Map<String, Object> attributes) {
        List<FieldError> errors = new ArrayList<>();

        // 1. Version validation
        if (schemaVersion == null || !SUPPORTED_SCHEMA_VERSIONS.contains(schemaVersion.trim())) {
            errors.add(new FieldError(
                    "schema_version",
                    "ERR_PAYLOAD_VERSION_UNSUPPORTED",
                    "Schema version '" + schemaVersion + "' is unsupported.",
                    "স্কিমা ভার্সন '" + schemaVersion + "' সমর্থিত নয়।"
            ));
            return new PayloadValidationReport(schemaVersion, dosageFormFamily, false, errors);
        }

        // 2. Dosage form family validation
        if (dosageFormFamily == null || dosageFormFamily.trim().isEmpty()) {
            errors.add(new FieldError(
                    "dosage_form_family",
                    "ERR_INVALID_DOSAGE_FORM",
                    "Dosage form family is required.",
                    "ডোজ ফর্ম টাইপ প্রদান করা আবশ্যক।"
            ));
            return new PayloadValidationReport(schemaVersion, dosageFormFamily, false, errors);
        }

        String form = dosageFormFamily.trim().toUpperCase();
        Map<String, Object> attrs = attributes != null ? attributes : Collections.emptyMap();
        Set<String> allowedSpecific = FORM_SPECIFIC_ATTRIBUTES.getOrDefault(form, Collections.emptySet());

        // 3. Incompatible & Unknown Fields Detection
        for (String key : attrs.keySet()) {
            if (COMMON_ATTRIBUTES.contains(key)) {
                continue;
            }
            if (allowedSpecific.contains(key)) {
                continue;
            }
            if (ALL_RECOGNIZED_FORM_ATTRIBUTES.contains(key)) {
                errors.add(new FieldError(
                        key,
                        "ERR_INCOMPATIBLE_FIELD",
                        "Field '" + key + "' is incompatible with dosage form family '" + form + "'.",
                        "'" + key + "' ফিল্ডটি '" + form + "' ডোজ ফর্মের সাথে অসঙ্গতিপূর্ণ।"
                ));
            } else {
                errors.add(new FieldError(
                        key,
                        "ERR_UNKNOWN_FIELD",
                        "Unknown field '" + key + "' is not permitted in medicine payload schema.",
                        "অজানা ফিল্ড '" + key + "' অনুমোদিত নয়।"
                ));
            }
        }

        // 4. Mandatory Common Attributes Check
        for (String mField : MANDATORY_COMMON) {
            Object val = attrs.get(mField);
            if (val == null || val.toString().trim().isEmpty()) {
                errors.add(new FieldError(
                        mField,
                        "ERR_MANDATORY_FIELD_MISSING",
                        "Mandatory attribute '" + mField + "' is missing.",
                        "বাধ্যতামূলক ফিল্ড '" + mField + "' অনুপস্থিত।"
                ));
            }
        }

        // 5. Mandatory Form-Specific Attributes Check
        Set<String> reqFormAttrs = MANDATORY_FORM_SPECIFIC.getOrDefault(form, Collections.emptySet());
        for (String reqField : reqFormAttrs) {
            Object val = attrs.get(reqField);
            if (val == null || val.toString().trim().isEmpty()) {
                errors.add(new FieldError(
                        reqField,
                        "ERR_MANDATORY_FIELD_MISSING",
                        "Attribute '" + reqField + "' is mandatory for '" + form + "'.",
                        "'" + form + "' এর জন্য '" + reqField + "' ফিল্ডটি আবশ্যক।"
                ));
            }
        }

        // 6. Pricing Rules
        Object costObj = attrs.get("cost_unit");
        Object mrpObj = attrs.get("mrp_unit");
        BigDecimal cost = null;
        BigDecimal mrp = null;

        if (costObj != null && !costObj.toString().trim().isEmpty()) {
            try {
                cost = new BigDecimal(costObj.toString().trim());
                if (cost.compareTo(BigDecimal.ZERO) < 0) {
                    errors.add(new FieldError(
                            "cost_unit",
                            "ERR_NEGATIVE_COST",
                            "Unit cost price cannot be negative.",
                            "ক্রয়মূল্য ঋণাত্মক হতে পারে না।"
                    ));
                }
            } catch (Exception e) {
                errors.add(new FieldError(
                        "cost_unit",
                        "ERR_INVALID_TYPE",
                        "Cost price must be a valid numeric decimal.",
                        "ক্রয়মূল্য সঠিক সংখ্যা হতে হবে।"
                ));
            }
        }

        if (mrpObj != null && !mrpObj.toString().trim().isEmpty()) {
            try {
                mrp = new BigDecimal(mrpObj.toString().trim());
                if (mrp.compareTo(BigDecimal.ZERO) <= 0) {
                    errors.add(new FieldError(
                            "mrp_unit",
                            "ERR_INVALID_MRP",
                            "MRP must be strictly positive.",
                            "খুচরা মূল্য অবশ্যই শূন্যের বেশি হতে হবে।"
                    ));
                } else if (cost != null && mrp.compareTo(cost) < 0) {
                    errors.add(new FieldError(
                            "mrp_unit",
                            "ERR_MRP_BELOW_COST",
                            "MRP (" + mrp + ") cannot be less than purchase cost (" + cost + ").",
                            "খুচরা মূল্য ক্রয়মূল্যের চেয়ে কম হতে পারে না।"
                    ));
                }
            } catch (Exception e) {
                errors.add(new FieldError(
                        "mrp_unit",
                        "ERR_INVALID_TYPE",
                        "MRP must be a valid numeric decimal.",
                        "খুচরা মূল্য সঠিক সংখ্যা হতে হবে।"
                ));
            }
        }

        // 7. Packaging Invariant
        Object pObj = attrs.get("pieces_per_strip");
        Object sObj = attrs.get("strips_per_pack");
        Object tObj = attrs.get("total_pieces_per_pack");
        if (pObj != null && sObj != null && tObj != null) {
            try {
                int p = Integer.parseInt(pObj.toString().trim());
                int s = Integer.parseInt(sObj.toString().trim());
                int t = Integer.parseInt(tObj.toString().trim());
                if (p > 0 && s > 0 && t != (p * s)) {
                    errors.add(new FieldError(
                            "total_pieces_per_pack",
                            "ERR_DIMENSION_MISMATCH",
                            "Total pieces (" + t + ") does not equal pieces (" + p + ") * strips (" + s + ") = " + (p * s) + ".",
                            "প্যাকের মোট পিস ও স্ট্রিপের হিসাবের গরমিল।"
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        // 8. Storage Temperature Invariant
        Object minTObj = attrs.get("storage_temperature_celsius_min");
        Object maxTObj = attrs.get("storage_temperature_celsius_max");
        if (minTObj != null && maxTObj != null) {
            try {
                BigDecimal minT = new BigDecimal(minTObj.toString().trim());
                BigDecimal maxT = new BigDecimal(maxTObj.toString().trim());
                if (minT.compareTo(maxT) > 0) {
                    errors.add(new FieldError(
                            "storage_temperature_celsius_min",
                            "ERR_INVALID_TEMPERATURE_RANGE",
                            "Minimum storage temperature cannot exceed maximum storage temperature.",
                            "সর্বনিম্ন তাপমাত্রা সর্বোচ্চ তাপমাত্রার চেয়ে বেশি হতে পারে না।"
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        return new PayloadValidationReport(schemaVersion, form, errors.isEmpty(), errors);
    }
}

