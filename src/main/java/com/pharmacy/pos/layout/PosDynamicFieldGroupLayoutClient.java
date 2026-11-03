package com.pharmacy.pos.layout;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PosDynamicFieldGroupLayoutClient
 * Architecture Decision: ADR-0152 (P152).
 * Phase 08: Milestone M04 / M05 - Specialized Item & Consumable Editors.
 *
 * Client-side dynamic layout engine for JavaFX Desktop POS.
 * Coordinates collapsible field groups, keyboard focus traversal,
 * accessible bilingual labels, and inline error feedback.
 */
public class PosDynamicFieldGroupLayoutClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class FieldDefinition {
        public final String fieldName;
        public final int tabIndex;
        public final String labelEn;
        public final String labelBn;
        public final String controlType;
        public final boolean isMandatory;
        public final String desktopGridWidth;
        public final String keyboardMnemonic;
        public final String ariaLabel;

        public FieldDefinition(
                String fieldName,
                int tabIndex,
                String labelEn,
                String labelBn,
                String controlType,
                boolean isMandatory,
                String desktopGridWidth,
                String keyboardMnemonic,
                String ariaLabel) {
            this.fieldName = fieldName;
            this.tabIndex = tabIndex;
            this.labelEn = labelEn;
            this.labelBn = labelBn;
            this.controlType = controlType;
            this.isMandatory = isMandatory;
            this.desktopGridWidth = desktopGridWidth != null ? desktopGridWidth : "HALF";
            this.keyboardMnemonic = keyboardMnemonic != null ? keyboardMnemonic : "";
            this.ariaLabel = ariaLabel != null ? ariaLabel : "";
        }
    }

    public static final class FieldLayoutGroup {
        public final String groupCode;
        public final int displayOrder;
        public final String titleEn;
        public final String titleBn;
        public final boolean isCollapsible;
        public final boolean defaultCollapsed;
        public final String keyboardShortcutMnemonic;
        public final String displayDensity;
        public final Map<String, Object> visibilityCondition;
        public final List<FieldDefinition> fields;
        public boolean isVisible;

        public FieldLayoutGroup(
                String groupCode,
                int displayOrder,
                String titleEn,
                String titleBn,
                boolean isCollapsible,
                boolean defaultCollapsed,
                String keyboardShortcutMnemonic,
                String displayDensity,
                Map<String, Object> visibilityCondition,
                List<FieldDefinition> fields,
                boolean isVisible) {
            this.groupCode = groupCode;
            this.displayOrder = displayOrder;
            this.titleEn = titleEn;
            this.titleBn = titleBn;
            this.isCollapsible = isCollapsible;
            this.defaultCollapsed = defaultCollapsed;
            this.keyboardShortcutMnemonic = keyboardShortcutMnemonic != null ? keyboardShortcutMnemonic : "";
            this.displayDensity = displayDensity != null ? displayDensity : "COMFORTABLE";
            this.visibilityCondition = visibilityCondition != null ? visibilityCondition : Collections.emptyMap();
            this.fields = fields != null ? fields : Collections.emptyList();
            this.isVisible = isVisible;
        }
    }

    public static final class InlineError {
        public final String groupCode;
        public final String fieldName;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;
        public final int tabIndex;

        public InlineError(String groupCode, String fieldName, String errorCode, String messageEn, String messageBn, int tabIndex) {
            this.groupCode = groupCode;
            this.fieldName = fieldName;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
            this.tabIndex = tabIndex;
        }
    }

    /**
     * Evaluates conditional visibility expression against current form state.
     */
    public boolean evaluateVisibility(Map<String, Object> condition, Map<String, Object> formValues) {
        if (condition == null || condition.isEmpty()) {
            return true;
        }

        String field = (String) condition.get("field");
        String operator = (String) condition.get("operator");
        Object expected = condition.get("value");

        if (field == null) {
            return true;
        }

        Object actual = formValues != null ? formValues.get(field) : null;
        String actualStr = actual != null ? actual.toString().trim().toUpperCase() : "";

        if (operator == null || "EQUALS".equalsIgnoreCase(operator)) {
            if (expected instanceof Boolean) {
                return Boolean.valueOf(actualStr).equals(expected);
            }
            return actualStr.equalsIgnoreCase(expected != null ? expected.toString() : "");
        } else if ("IN".equalsIgnoreCase(operator)) {
            if (expected instanceof List) {
                List<?> list = (List<?>) expected;
                for (Object item : list) {
                    if (actualStr.equalsIgnoreCase(item.toString())) {
                        return true;
                    }
                }
                return false;
            }
        } else if ("NOT_EQUALS".equalsIgnoreCase(operator)) {
            return !actualStr.equalsIgnoreCase(expected != null ? expected.toString() : "");
        }

        return true;
    }

    /**
     * Resolves linear tab sequence across all currently visible field groups.
     */
    public List<FieldDefinition> resolveTabSequence(List<FieldLayoutGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return Collections.emptyList();
        }

        return groups.stream()
                .filter(g -> g.isVisible)
                .sorted(Comparator.comparingInt(g -> g.displayOrder))
                .flatMap(g -> g.fields.stream())
                .sorted(Comparator.comparingInt(f -> f.tabIndex))
                .collect(Collectors.toList());
    }

    /**
     * Validates form inputs and returns inline errors mapped per field.
     */
    public List<InlineError> validateFormInputs(List<FieldLayoutGroup> groups, Map<String, Object> formValues) {
        List<InlineError> errors = new ArrayList<>();
        if (groups == null) {
            return errors;
        }

        for (FieldLayoutGroup group : groups) {
            if (!group.isVisible) {
                continue;
            }

            for (FieldDefinition field : group.fields) {
                Object val = formValues != null ? formValues.get(field.fieldName) : null;
                if (field.isMandatory && (val == null || val.toString().trim().isEmpty())) {
                    errors.add(new InlineError(
                            group.groupCode,
                            field.fieldName,
                            "ERR_MANDATORY_FIELD_MISSING",
                            "Field '" + field.labelEn + "' is mandatory.",
                            "'" + field.labelBn + "' ফিল্ডটি পূরণ করা আবশ্যক।",
                            field.tabIndex
                    ));
                }
            }
        }

        // Packaging dimension validation
        if (formValues != null && formValues.containsKey("pieces_per_strip") && formValues.containsKey("strips_per_pack") && formValues.containsKey("total_pieces_per_pack")) {
            try {
                int p = Integer.parseInt(formValues.get("pieces_per_strip").toString());
                int s = Integer.parseInt(formValues.get("strips_per_pack").toString());
                int t = Integer.parseInt(formValues.get("total_pieces_per_pack").toString());
                if (p > 0 && s > 0 && t != p * s) {
                    errors.add(new InlineError(
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

        // Temperature range validation
        if (formValues != null && formValues.containsKey("storage_temperature_celsius_min") && formValues.containsKey("storage_temperature_celsius_max")) {
            try {
                BigDecimal min = new BigDecimal(formValues.get("storage_temperature_celsius_min").toString());
                BigDecimal max = new BigDecimal(formValues.get("storage_temperature_celsius_max").toString());
                if (min.compareTo(max) > 0) {
                    errors.add(new InlineError(
                            "STORAGE_AND_COLD_CHAIN",
                            "storage_temperature_celsius_min",
                            "ERR_INVALID_TEMPERATURE_RANGE",
                            "Minimum temperature cannot exceed maximum temperature.",
                            "সর্বনিম্ন তাপমাত্রা সর্বোচ্চ তাপমাত্রার চেয়ে বেশি হতে পারে না।",
                            22
                    ));
                }
            } catch (Exception ignored) {
            }
        }

        return errors;
    }
}

