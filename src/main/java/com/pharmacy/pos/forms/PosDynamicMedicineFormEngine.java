package com.pharmacy.pos.forms;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PosDynamicMedicineFormEngine
 * Architecture Decision: ADR-0157 (P157).
 * Phase 08: Specialized Item & Multi-Tier Packaging Formulation Editors.
 *
 * JavaFX desktop dynamic medicine form engine.
 * Renders schema-driven forms, executes multi-tiered DTO validation,
 * and maintains complete field state retention across dosage form family switches.
 */
public class PosDynamicMedicineFormEngine {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final Set<String> COMMON_MONOGRAPH_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "brand_name",
            "brand_name_bn",
            "generic_name",
            "generic_name_bn",
            "manufacturer",
            "dar_number",
            "cost_unit",
            "mrp_unit",
            "vat_percentage",
            "prescription_required",
            "is_antibiotic",
            "is_narcotic",
            "requires_cold_chain",
            "storage_condition",
            "storage_temperature_celsius_min",
            "storage_temperature_celsius_max",
            "handling_alert"
    )));

    public static final class InlineValidationError {
        public final String groupCode;
        public final String fieldName;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;
        public final int tabIndex;

        public InlineValidationError(String groupCode, String fieldName, String errorCode, String messageEn, String messageBn, int tabIndex) {
            this.groupCode = groupCode;
            this.fieldName = fieldName;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
            this.tabIndex = tabIndex;
        }

        public String getGroupCode() { return groupCode; }
        public String getFieldName() { return fieldName; }
        public String getErrorCode() { return errorCode; }
        public String getMessageEn() { return messageEn; }
        public String getMessageBn() { return messageBn; }
        public int getTabIndex() { return tabIndex; }
    }

    public static final class FormFieldConfig {
        public final String fieldName;
        public final String labelEn;
        public final String labelBn;
        public final String controlType;
        public final boolean isMandatory;
        public final int tabIndex;
        public final String desktopGridWidth;
        public final String keyboardMnemonic;
        public final String ariaLabel;

        public FormFieldConfig(
                String fieldName,
                String labelEn,
                String labelBn,
                String controlType,
                boolean isMandatory,
                int tabIndex,
                String desktopGridWidth,
                String keyboardMnemonic,
                String ariaLabel) {
            this.fieldName = fieldName;
            this.labelEn = labelEn;
            this.labelBn = labelBn;
            this.controlType = controlType;
            this.isMandatory = isMandatory;
            this.tabIndex = tabIndex;
            this.desktopGridWidth = desktopGridWidth != null ? desktopGridWidth : "HALF";
            this.keyboardMnemonic = keyboardMnemonic != null ? keyboardMnemonic : "";
            this.ariaLabel = ariaLabel != null ? ariaLabel : "";
        }
    }

    public static final class FormGroupConfig {
        public final String groupCode;
        public final int displayOrder;
        public final String titleEn;
        public final String titleBn;
        public final boolean isCollapsible;
        public final boolean defaultCollapsed;
        public final String keyboardMnemonic;
        public final List<FormFieldConfig> fields;

        public FormGroupConfig(
                String groupCode,
                int displayOrder,
                String titleEn,
                String titleBn,
                boolean isCollapsible,
                boolean defaultCollapsed,
                String keyboardMnemonic,
                List<FormFieldConfig> fields) {
            this.groupCode = groupCode;
            this.displayOrder = displayOrder;
            this.titleEn = titleEn;
            this.titleBn = titleBn;
            this.isCollapsible = isCollapsible;
            this.defaultCollapsed = defaultCollapsed;
            this.keyboardMnemonic = keyboardMnemonic != null ? keyboardMnemonic : "";
            this.fields = fields != null ? fields : Collections.emptyList();
        }
    }

    private String currentDosageForm;
    private final Map<String, Object> currentFormValues = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> retainedFormCache = new HashMap<>();
    private final List<InlineValidationError> activeErrors = new ArrayList<>();
    private UiStatus uiStatus = UiStatus.EMPTY;
    private String statusMessage = "";

    public PosDynamicMedicineFormEngine() {
        this("TABLET_CAPSULE");
    }

    public PosDynamicMedicineFormEngine(String initialDosageForm) {
        initializeForm(initialDosageForm != null ? initialDosageForm : "TABLET_CAPSULE");
    }

    /**
     * Initializes or resets form with target dosage form family.
     */
    public void initializeForm(String dosageForm) {
        this.currentDosageForm = dosageForm;
        this.currentFormValues.clear();
        this.retainedFormCache.clear();
        this.activeErrors.clear();
        this.uiStatus = UiStatus.EMPTY;
        this.statusMessage = "";
    }

    /**
     * Switches dosage form family without losing entered values.
     * Universal monograph attributes are preserved across the switch.
     * Form-specific parameters for the previous form are cached and
     * restored if the user switches back.
     */
    public void switchDosageForm(String newDosageForm) {
        if (newDosageForm == null || newDosageForm.trim().isEmpty() || newDosageForm.equals(this.currentDosageForm)) {
            return;
        }

        // 1. Separate common fields from form-specific fields
        Map<String, Object> commonValues = new LinkedHashMap<>();
        Map<String, Object> formSpecificValues = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : currentFormValues.entrySet()) {
            if (COMMON_MONOGRAPH_FIELDS.contains(entry.getKey())) {
                commonValues.put(entry.getKey(), entry.getValue());
            } else {
                formSpecificValues.put(entry.getKey(), entry.getValue());
            }
        }

        // 2. Cache previous form-specific values
        if (this.currentDosageForm != null && !this.currentDosageForm.isEmpty()) {
            this.retainedFormCache.put(this.currentDosageForm, formSpecificValues);
        }

        // 3. Switch active form
        this.currentDosageForm = newDosageForm;

        // 4. Rebuild active values: start with preserved common attributes
        this.currentFormValues.clear();
        this.currentFormValues.putAll(commonValues);

        // 5. Restore previously cached values for the target form family if present
        if (this.retainedFormCache.containsKey(newDosageForm)) {
            Map<String, Object> cachedSpecifics = this.retainedFormCache.get(newDosageForm);
            if (cachedSpecifics != null) {
                this.currentFormValues.putAll(cachedSpecifics);
            }
        }

        // Clear transient errors on layout switch
        this.activeErrors.clear();
        this.uiStatus = UiStatus.EMPTY;
        this.statusMessage = "Switched dosage form to " + newDosageForm;
    }

    public String getCurrentDosageForm() {
        return currentDosageForm;
    }

    public void setFieldValue(String fieldName, Object value) {
        if (fieldName != null && !fieldName.trim().isEmpty()) {
            if (value == null) {
                currentFormValues.remove(fieldName);
            } else {
                currentFormValues.put(fieldName, value);
            }
        }
    }

    public Object getFieldValue(String fieldName) {
        return currentFormValues.get(fieldName);
    }

    public Map<String, Object> getCurrentFormValues() {
        return Collections.unmodifiableMap(currentFormValues);
    }

    public Map<String, Map<String, Object>> getRetainedFormCache() {
        return Collections.unmodifiableMap(retainedFormCache);
    }

    public UiStatus getUiStatus() {
        return uiStatus;
    }

    public void setUiStatus(UiStatus uiStatus) {
        this.uiStatus = uiStatus;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public List<InlineValidationError> getActiveErrors() {
        return Collections.unmodifiableList(activeErrors);
    }

    /**
     * Role-Based Access Control check for catalog modification.
     */
    public boolean canMutateForm(String userRole) {
        if (userRole == null || userRole.trim().isEmpty()) {
            return false;
        }
        String normalized = userRole.trim().toUpperCase();
        return !"CASHIER".equals(normalized);
    }

    /**
     * Executes multi-tiered DTO validation on currently entered form values.
     */
    public List<InlineValidationError> validateFormData() {
        activeErrors.clear();

        // 1. Mandatory Common Fields Check
        checkMandatory("GENERAL_IDENTIFICATION", "brand_name", "Brand Name", "ব্র্যান্ডের নাম", 1);
        checkMandatory("GENERAL_IDENTIFICATION", "generic_name", "Generic Name", "জেনেরিক নাম", 2);
        checkMandatory("GENERAL_IDENTIFICATION", "dar_number", "DAR Registration No", "ডিএআর রেজিস্ট্রেশন নম্বর", 4);
        checkMandatory("FINANCIAL_PRICING", "cost_unit", "Unit Cost Price", "ক্রয়মূল্য", 40);
        checkMandatory("FINANCIAL_PRICING", "mrp_unit", "Maximum Retail Price (MRP)", "খুচরা বিক্রয়মূল্য", 41);

        // 2. Financial Pricing Validation
        Object costVal = currentFormValues.get("cost_unit");
        Object mrpVal = currentFormValues.get("mrp_unit");

        BigDecimal cost = null;
        BigDecimal mrp = null;

        if (costVal != null && !costVal.toString().trim().isEmpty()) {
            try {
                cost = new BigDecimal(costVal.toString().trim());
                if (cost.compareTo(BigDecimal.ZERO) < 0) {
                    activeErrors.add(new InlineValidationError(
                            "FINANCIAL_PRICING",
                            "cost_unit",
                            "ERR_NEGATIVE_COST",
                            "Unit cost price cannot be negative.",
                            "ক্রয়মূল্য ঋণাত্মক হতে পারে না।",
                            40
                    ));
                }
            } catch (Exception e) {
                activeErrors.add(new InlineValidationError(
                        "FINANCIAL_PRICING",
                        "cost_unit",
                        "ERR_INVALID_NUMERIC",
                        "Invalid cost price format.",
                        "ক্রয়মূল্যের সংখ্যা সঠিক নয়।",
                        40
                ));
            }
        }

        if (mrpVal != null && !mrpVal.toString().trim().isEmpty()) {
            try {
                mrp = new BigDecimal(mrpVal.toString().trim());
                if (mrp.compareTo(BigDecimal.ZERO) <= 0) {
                    activeErrors.add(new InlineValidationError(
                            "FINANCIAL_PRICING",
                            "mrp_unit",
                            "ERR_INVALID_MRP",
                            "MRP must be greater than zero.",
                            "খুচরা বিক্রয়মূল্য অবশ্যই শূন্যের বেশি হতে হবে।",
                            41
                    ));
                } else if (cost != null && mrp.compareTo(cost) < 0) {
                    activeErrors.add(new InlineValidationError(
                            "FINANCIAL_PRICING",
                            "mrp_unit",
                            "ERR_MRP_BELOW_COST",
                            "MRP (" + mrp + ") cannot be less than purchase cost (" + cost + ").",
                            "খুচরা বিক্রয়মূল্য (" + mrp + ") ক্রয়মূল্যের (" + cost + ") চেয়ে কম হতে পারে না।",
                            41
                    ));
                }
            } catch (Exception e) {
                activeErrors.add(new InlineValidationError(
                        "FINANCIAL_PRICING",
                        "mrp_unit",
                        "ERR_INVALID_NUMERIC",
                        "Invalid MRP format.",
                        "এমআরপির সংখ্যা সঠিক নয়।",
                        41
                ));
            }
        }

        // 3. Packaging Dimension Multipliers
        Object piecesObj = currentFormValues.get("pieces_per_strip");
        Object stripsObj = currentFormValues.get("strips_per_pack");
        Object totalObj = currentFormValues.get("total_pieces_per_pack");
        if (piecesObj != null && stripsObj != null && totalObj != null) {
            try {
                int p = Integer.parseInt(piecesObj.toString().trim());
                int s = Integer.parseInt(stripsObj.toString().trim());
                int t = Integer.parseInt(totalObj.toString().trim());
                if (p > 0 && s > 0 && t != (p * s)) {
                    activeErrors.add(new InlineValidationError(
                            "PACKAGING_DIMENSIONS",
                            "total_pieces_per_pack",
                            "ERR_DIMENSION_MISMATCH",
                            "Total pieces (" + t + ") does not equal pieces * strips (" + (p * s) + ").",
                            "প্যাকের মোট পিস ও স্ট্রিপের হিসাবের গরমিল।",
                            12
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        // 4. Cold Chain Temperature Ordering
        Object minTObj = currentFormValues.get("storage_temperature_celsius_min");
        Object maxTObj = currentFormValues.get("storage_temperature_celsius_max");
        if (minTObj != null && maxTObj != null) {
            try {
                BigDecimal minT = new BigDecimal(minTObj.toString().trim());
                BigDecimal maxT = new BigDecimal(maxTObj.toString().trim());
                if (minT.compareTo(maxT) > 0) {
                    activeErrors.add(new InlineValidationError(
                            "STORAGE_AND_COLD_CHAIN",
                            "storage_temperature_celsius_min",
                            "ERR_INVALID_TEMPERATURE_RANGE",
                            "Minimum storage temperature cannot exceed maximum storage temperature.",
                            "সর্বনিম্ন তাপমাত্রা সর্বোচ্চ তাপমাত্রার চেয়ে বেশি হতে পারে না।",
                            22
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        // 5. Formulation-Specific Validations
        validateFormSpecificFields();

        return Collections.unmodifiableList(activeErrors);
    }

    private void checkMandatory(String groupCode, String fieldName, String labelEn, String labelBn, int tabIndex) {
        Object val = currentFormValues.get(fieldName);
        if (val == null || val.toString().trim().isEmpty()) {
            activeErrors.add(new InlineValidationError(
                    groupCode,
                    fieldName,
                    "ERR_MANDATORY_FIELD_MISSING",
                    "Field '" + labelEn + "' is mandatory.",
                    "'" + labelBn + "' ফিল্ডটি পূরণ করা আবশ্যক।",
                    tabIndex
            ));
        }
    }

    private void validateFormSpecificFields() {
        if ("INHALER".equalsIgnoreCase(currentDosageForm)) {
            checkMandatory("FORM_SPECIFIC_PARAMETERS", "dose_per_actuation", "Dose per Actuation", "প্রতি পাফে মাত্রা", 5);
            checkMandatory("FORM_SPECIFIC_PARAMETERS", "actuations_per_device", "Actuations per Inhaler", "মোট পাফ সংখ্যা", 6);
        } else if ("DROPS".equalsIgnoreCase(currentDosageForm)) {
            checkMandatory("FORM_SPECIFIC_PARAMETERS", "volume_ml", "Volume (mL)", "বোতলের আয়তন (মিলি)", 5);
            checkMandatory("FORM_SPECIFIC_PARAMETERS", "drop_category", "Drop Category", "ড্রপের ধরণ", 6);
        } else if ("TOPICAL_CREAM".equalsIgnoreCase(currentDosageForm)) {
            checkMandatory("FORM_SPECIFIC_PARAMETERS", "tube_size_grams", "Tube Size (g)", "টিউবের ওজন (গ্রাম)", 5);
            checkMandatory("FORM_SPECIFIC_PARAMETERS", "application_site", "Application Site", "ব্যবহারের স্থান", 6);
        } else if ("SACHET_POWDER".equalsIgnoreCase(currentDosageForm)) {
            checkMandatory("FORM_SPECIFIC_PARAMETERS", "grams_per_sachet", "Weight per Sachet (g)", "প্রতি স্যাশের ওজন (গ্রাম)", 5);
            checkMandatory("FORM_SPECIFIC_PARAMETERS", "sachets_per_pack", "Sachets per Pack", "প্যাকে স্যাশের সংখ্যা", 6);
        } else if ("SUPPOSITORY".equalsIgnoreCase(currentDosageForm)) {
            checkMandatory("FORM_SPECIFIC_PARAMETERS", "piece_count", "Piece Count", "পিস সংখ্যা", 5);
            checkMandatory("FORM_SPECIFIC_PARAMETERS", "administration_route", "Administration Route", "প্রয়োগ রুট", 6);
        } else if ("DEVICE_CONSUMABLE".equalsIgnoreCase(currentDosageForm)) {
            checkMandatory("FORM_SPECIFIC_PARAMETERS", "device_type", "Device Type", "ডিভাইসের ধরণ", 5);
        }
    }

    /**
     * Submits form with role verification and inline validation.
     */
    public boolean submitForm(String userRole) {
        if (!canMutateForm(userRole)) {
            this.uiStatus = UiStatus.PERMISSION_DENIED;
            this.statusMessage = "Cashier role has read-only access to master catalog definitions.";
            return false;
        }

        validateFormData();

        if (!activeErrors.isEmpty()) {
            this.uiStatus = UiStatus.ERROR;
            this.statusMessage = "Validation failed with " + activeErrors.size() + " errors.";
            return false;
        }

        this.uiStatus = UiStatus.SUCCESS;
        this.statusMessage = "Form successfully validated and submitted.";
        return true;
    }

    /**
     * Generates dynamic field layout groups for the active dosage form.
     */
    public List<FormGroupConfig> getLayoutForCurrentForm() {
        List<FormGroupConfig> groups = new ArrayList<>();

        // Group 1: General Identification
        List<FormFieldConfig> generalFields = Arrays.asList(
                new FormFieldConfig("brand_name", "Brand Name", "ব্র্যান্ডের নাম", "TEXT_INPUT", true, 1, "HALF", "B", "Brand trade name"),
                new FormFieldConfig("brand_name_bn", "Brand Name (Bengali)", "বাংলা নাম", "TEXT_INPUT", false, 2, "HALF", "", "Bengali trade name"),
                new FormFieldConfig("generic_name", "Generic Name", "জেনেরিক নাম", "TEXT_INPUT", true, 3, "HALF", "G", "Generic scientific name"),
                new FormFieldConfig("dar_number", "DAR Registration No", "ডিএআর রেজিস্ট্রেশন নম্বর", "TEXT_INPUT", true, 4, "HALF", "D", "DGDA DAR registration number")
        );
        groups.add(new FormGroupConfig("GENERAL_IDENTIFICATION", 1, "General Identification", "সাধারণ পণ্যের পরিচিতি", false, false, "I", generalFields));

        // Group 2: Form-Specific Parameters
        List<FormFieldConfig> specificFields = getFormSpecificFieldsConfig(currentDosageForm);
        if (!specificFields.isEmpty()) {
            groups.add(new FormGroupConfig("FORM_SPECIFIC_PARAMETERS", 2, "Formulation Specifications", "ফর্মুলেশনের বিবরণ", true, false, "F", specificFields));
        }

        // Group 3: Packaging Dimensions (relevant for solid orals, strips, sachets)
        if ("TABLET_CAPSULE".equalsIgnoreCase(currentDosageForm) || "SACHET_POWDER".equalsIgnoreCase(currentDosageForm)) {
            List<FormFieldConfig> packFields = Arrays.asList(
                    new FormFieldConfig("pieces_per_strip", "Pieces per Strip", "প্রতি পাতায় পিস", "NUMERIC_INPUT", true, 10, "THIRD", "P", "Pieces per packaging strip"),
                    new FormFieldConfig("strips_per_pack", "Strips per Pack", "প্যাকে পাতার সংখ্যা", "NUMERIC_INPUT", true, 11, "THIRD", "S", "Strips per packaging box"),
                    new FormFieldConfig("total_pieces_per_pack", "Total Pieces per Pack", "প্যাকে মোট পিস", "NUMERIC_INPUT", true, 12, "THIRD", "T", "Total item count per retail unit")
            );
            groups.add(new FormGroupConfig("PACKAGING_DIMENSIONS", 3, "Packaging Dimensions", "প্যাকেজিং অনুপাত", true, false, "P", packFields));
        }

        // Group 4: Storage & Cold Chain
        List<FormFieldConfig> storageFields = Arrays.asList(
                new FormFieldConfig("requires_cold_chain", "Requires Cold Chain", "কোল্ড-চেইন আবশ্যক", "CHECKBOX_SWITCH", false, 20, "HALF", "C", "Cold storage requirement flag"),
                new FormFieldConfig("storage_temperature_celsius_min", "Min Temp (°C)", "সর্বনিম্ন তাপমাত্রা (°সে)", "NUMERIC_INPUT", false, 21, "QUARTER", "L", "Minimum storage temperature"),
                new FormFieldConfig("storage_temperature_celsius_max", "Max Temp (°C)", "সর্বোচ্চ তাপমাত্রা (°সে)", "NUMERIC_INPUT", false, 22, "QUARTER", "H", "Maximum storage temperature")
        );
        groups.add(new FormGroupConfig("STORAGE_AND_COLD_CHAIN", 4, "Storage & Temperature", "সংরক্ষণ ও তাপমাত্রা", true, true, "T", storageFields));

        // Group 5: Financial Pricing
        List<FormFieldConfig> pricingFields = Arrays.asList(
                new FormFieldConfig("cost_unit", "Unit Cost Price (BDT)", "ক্রয়মূল্য (টাকা)", "NUMERIC_INPUT", true, 40, "THIRD", "K", "Acquisition purchase cost"),
                new FormFieldConfig("mrp_unit", "Maximum Retail Price (MRP)", "খুচরা বিক্রয়মূল্য (এমআরপি)", "NUMERIC_INPUT", true, 41, "THIRD", "U", "Maximum consumer retail price")
        );
        groups.add(new FormGroupConfig("FINANCIAL_PRICING", 5, "Financial Pricing & MRP", "মূল্য ও কর নির্ধারণ", false, false, "M", pricingFields));

        return groups;
    }

    private List<FormFieldConfig> getFormSpecificFieldsConfig(String dosageForm) {
        if ("INHALER".equalsIgnoreCase(dosageForm)) {
            return Arrays.asList(
                    new FormFieldConfig("dose_per_actuation", "Dose per Actuation", "প্রতি পাফে মাত্রা", "TEXT_INPUT", true, 5, "HALF", "E", "Inhaler dose per actuation"),
                    new FormFieldConfig("actuations_per_device", "Actuations per Inhaler", "মোট পাফ সংখ্যা", "NUMERIC_INPUT", true, 6, "HALF", "T", "Total actuation count per canister")
            );
        } else if ("DROPS".equalsIgnoreCase(dosageForm)) {
            return Arrays.asList(
                    new FormFieldConfig("volume_ml", "Volume (mL)", "বোতলের আয়তন (মিলি)", "NUMERIC_INPUT", true, 5, "HALF", "V", "Bottle volume in mL"),
                    new FormFieldConfig("drop_category", "Drop Category", "ড্রপের ধরণ", "SELECT_DROPDOWN", true, 6, "HALF", "D", "Ophthalmic or otic category")
            );
        } else if ("TOPICAL_CREAM".equalsIgnoreCase(dosageForm)) {
            return Arrays.asList(
                    new FormFieldConfig("tube_size_grams", "Tube Size (g)", "টিউবের ওজন (গ্রাম)", "NUMERIC_INPUT", true, 5, "HALF", "G", "Tube weight in grams"),
                    new FormFieldConfig("application_site", "Application Site", "ব্যবহারের স্থান", "TEXT_INPUT", true, 6, "HALF", "A", "Skin area for topical application")
            );
        } else if ("SACHET_POWDER".equalsIgnoreCase(dosageForm)) {
            return Arrays.asList(
                    new FormFieldConfig("grams_per_sachet", "Weight per Sachet (g)", "প্রতি স্যাশের ওজন (গ্রাম)", "NUMERIC_INPUT", true, 5, "HALF", "W", "Powder weight per sachet"),
                    new FormFieldConfig("sachets_per_pack", "Sachets per Pack", "প্যাকে স্যাশের সংখ্যা", "NUMERIC_INPUT", true, 6, "HALF", "S", "Sachets per box")
            );
        } else if ("SUPPOSITORY".equalsIgnoreCase(dosageForm)) {
            return Arrays.asList(
                    new FormFieldConfig("piece_count", "Piece Count", "পিস সংখ্যা", "NUMERIC_INPUT", true, 5, "HALF", "C", "Suppositories count per pack"),
                    new FormFieldConfig("administration_route", "Administration Route", "প্রয়োগ রুট", "SELECT_DROPDOWN", true, 6, "HALF", "R", "Rectal or vaginal route")
            );
        } else if ("DEVICE_CONSUMABLE".equalsIgnoreCase(dosageForm)) {
            return Arrays.asList(
                    new FormFieldConfig("device_type", "Device Type", "ডিভাইসের ধরণ", "TEXT_INPUT", true, 5, "HALF", "D", "Consumable or medical device category")
            );
        }
        return Collections.emptyList();
    }

    /**
     * Resolves linear tab sequence across all groups in current layout.
     */
    public List<FormFieldConfig> resolveTabSequence() {
        return getLayoutForCurrentForm().stream()
                .sorted(Comparator.comparingInt(g -> g.displayOrder))
                .flatMap(g -> g.fields.stream())
                .sorted(Comparator.comparingInt(f -> f.tabIndex))
                .collect(Collectors.toList());
    }
}

