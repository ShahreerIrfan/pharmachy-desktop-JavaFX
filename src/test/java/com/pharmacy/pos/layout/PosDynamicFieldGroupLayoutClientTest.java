package com.pharmacy.pos.layout;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosDynamicFieldGroupLayoutClientTest
 * Tests for JavaFX POS Dynamic Field Group Layout Client (P152).
 */
public class PosDynamicFieldGroupLayoutClientTest {

    private PosDynamicFieldGroupLayoutClient client;
    private List<PosDynamicFieldGroupLayoutClient.FieldLayoutGroup> sampleGroups;

    @BeforeEach
    void setUp() {
        client = new PosDynamicFieldGroupLayoutClient();

        PosDynamicFieldGroupLayoutClient.FieldDefinition brandField =
                new PosDynamicFieldGroupLayoutClient.FieldDefinition(
                        "brand_name", 1, "Brand Name", "ব্র্যান্ড নাম",
                        "TEXT_INPUT", true, "HALF", "B", "Enter brand name"
                );
        PosDynamicFieldGroupLayoutClient.FieldDefinition genericField =
                new PosDynamicFieldGroupLayoutClient.FieldDefinition(
                        "generic_name", 2, "Generic Name", "জেনেরিক নাম",
                        "TEXT_INPUT", true, "HALF", "N", "Enter generic name"
                );

        PosDynamicFieldGroupLayoutClient.FieldDefinition actuationField =
                new PosDynamicFieldGroupLayoutClient.FieldDefinition(
                        "dose_per_actuation", 5, "Dose per Actuation", "প্রতি পাফে মাত্রা",
                        "TEXT_INPUT", true, "HALF", "E", "Inhaler dose"
                );

        PosDynamicFieldGroupLayoutClient.FieldDefinition stripField =
                new PosDynamicFieldGroupLayoutClient.FieldDefinition(
                        "pieces_per_strip", 10, "Pieces per Strip", "স্ট্রিপে পিস সংখ্যা",
                        "NUMERIC_INPUT", false, "THIRD", "1", "Units per strip"
                );
        PosDynamicFieldGroupLayoutClient.FieldDefinition packField =
                new PosDynamicFieldGroupLayoutClient.FieldDefinition(
                        "strips_per_pack", 11, "Strips per Pack", "প্যাকে স্ট্রিপ",
                        "NUMERIC_INPUT", false, "THIRD", "2", "Strips per pack"
                );
        PosDynamicFieldGroupLayoutClient.FieldDefinition totalField =
                new PosDynamicFieldGroupLayoutClient.FieldDefinition(
                        "total_pieces_per_pack", 12, "Total Pieces per Pack", "মোট পিস",
                        "NUMERIC_INPUT", false, "THIRD", "3", "Total pieces"
                );

        Map<String, Object> inhalerCond = new HashMap<>();
        inhalerCond.put("field", "dosage_form");
        inhalerCond.put("operator", "IN");
        inhalerCond.put("value", List.of("INHALER"));

        PosDynamicFieldGroupLayoutClient.FieldLayoutGroup group1 =
                new PosDynamicFieldGroupLayoutClient.FieldLayoutGroup(
                        "GENERAL_IDENTIFICATION", 1, "General Identification", "সাধারণ পরিচিতি",
                        false, false, "G", "COMFORTABLE",
                        Collections.emptyMap(), List.of(brandField, genericField), true
                );

        PosDynamicFieldGroupLayoutClient.FieldLayoutGroup group2 =
                new PosDynamicFieldGroupLayoutClient.FieldLayoutGroup(
                        "FORM_SPECIFIC_PARAMETERS", 2, "Formulation Parameters", "ডোজ ফর্ম পরিমাপ",
                        true, false, "F", "COMFORTABLE",
                        inhalerCond, List.of(actuationField), true
                );

        PosDynamicFieldGroupLayoutClient.FieldLayoutGroup group3 =
                new PosDynamicFieldGroupLayoutClient.FieldLayoutGroup(
                        "PACKAGING_DIMENSIONS", 3, "Packaging Dimensions", "প্যাকেজিং হিসাব",
                        true, false, "P", "COMFORTABLE",
                        Collections.emptyMap(), List.of(stripField, packField, totalField), true
                );

        sampleGroups = List.of(group1, group2, group3);
    }

    @Test
    @DisplayName("1. Evaluate visibility condition for INHALER dosage form")
    void testEvaluateVisibilityCondition() {
        Map<String, Object> condition = new HashMap<>();
        condition.put("field", "dosage_form");
        condition.put("operator", "IN");
        condition.put("value", List.of("INHALER", "DROPS"));

        Map<String, Object> inhalerValues = Map.of("dosage_form", "INHALER");
        assertTrue(client.evaluateVisibility(condition, inhalerValues));

        Map<String, Object> tabletValues = Map.of("dosage_form", "TABLET");
        assertFalse(client.evaluateVisibility(condition, tabletValues));
    }

    @Test
    @DisplayName("2. Resolve linear keyboard tab sequence across visible groups")
    void testResolveTabSequence() {
        List<PosDynamicFieldGroupLayoutClient.FieldDefinition> tabSeq =
                client.resolveTabSequence(sampleGroups);

        assertEquals(6, tabSeq.size());
        assertEquals("brand_name", tabSeq.get(0).fieldName);
        assertEquals(1, tabSeq.get(0).tabIndex);
        assertEquals("generic_name", tabSeq.get(1).fieldName);
        assertEquals(2, tabSeq.get(1).tabIndex);
        assertEquals("dose_per_actuation", tabSeq.get(2).fieldName);
        assertEquals(5, tabSeq.get(2).tabIndex);
        assertEquals("pieces_per_strip", tabSeq.get(3).fieldName);
        assertEquals(10, tabSeq.get(3).tabIndex);
        assertEquals("strips_per_pack", tabSeq.get(4).fieldName);
        assertEquals(11, tabSeq.get(4).tabIndex);
        assertEquals("total_pieces_per_pack", tabSeq.get(5).fieldName);
        assertEquals(12, tabSeq.get(5).tabIndex);
    }

    @Test
    @DisplayName("3. Inline errors aggregated for missing mandatory fields")
    void testValidateFormInputsMissingMandatory() {
        Map<String, Object> formValues = new HashMap<>();
        formValues.put("brand_name", ""); // Missing
        formValues.put("generic_name", "Salbutamol");
        formValues.put("dose_per_actuation", ""); // Missing

        List<PosDynamicFieldGroupLayoutClient.InlineError> errors =
                client.validateFormInputs(sampleGroups, formValues);

        assertEquals(2, errors.size());
        assertEquals("brand_name", errors.get(0).fieldName);
        assertEquals("ERR_MANDATORY_FIELD_MISSING", errors.get(0).errorCode);
        assertEquals("dose_per_actuation", errors.get(1).fieldName);
    }

    @Test
    @DisplayName("4. Inline error on packaging dimension mismatch")
    void testValidateFormInputsDimensionMismatch() {
        Map<String, Object> formValues = new HashMap<>();
        formValues.put("brand_name", "Napa 500");
        formValues.put("generic_name", "Paracetamol");
        formValues.put("dose_per_actuation", "100mcg");
        formValues.put("pieces_per_strip", 10);
        formValues.put("strips_per_pack", 5);
        formValues.put("total_pieces_per_pack", 60); // 10 * 5 = 50 != 60

        List<PosDynamicFieldGroupLayoutClient.InlineError> errors =
                client.validateFormInputs(sampleGroups, formValues);

        assertEquals(1, errors.size());
        assertEquals("total_pieces_per_pack", errors.get(0).fieldName);
        assertEquals("ERR_DIMENSION_MISMATCH", errors.get(0).errorCode);
    }

    @Test
    @DisplayName("5. Inline error on storage temperature range order")
    void testValidateFormInputsTemperatureRange() {
        Map<String, Object> formValues = new HashMap<>();
        formValues.put("brand_name", "Cold Chain Drug");
        formValues.put("generic_name", "Sample");
        formValues.put("dose_per_actuation", "N/A");
        formValues.put("storage_temperature_celsius_min", "30.0");
        formValues.put("storage_temperature_celsius_max", "20.0"); // Min > Max

        List<PosDynamicFieldGroupLayoutClient.InlineError> errors =
                client.validateFormInputs(sampleGroups, formValues);

        assertEquals(1, errors.size());
        assertEquals("storage_temperature_celsius_min", errors.get(0).fieldName);
        assertEquals("ERR_INVALID_TEMPERATURE_RANGE", errors.get(0).errorCode);
    }
}

