package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosDynamicConditionEvaluatorTest
 * Architecture Decision: ADR-0125 (P125).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 */
class PosDynamicConditionEvaluatorTest {

    private PosDynamicConditionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new PosDynamicConditionEvaluator();
    }

    @Test
    @DisplayName("P125-D1: Valid tablet evaluation resolves active and hidden fields")
    void testValidTabletEvaluation() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("brand_name", "Napa Extra");
        payload.put("generic_name", "Paracetamol + Caffeine");
        payload.put("dosage_form", "TABLET");
        payload.put("manufacturer", "Beximco");
        payload.put("strength", "500mg+65mg");
        payload.put("strips_per_box", 20);
        payload.put("units_per_strip", 10);
        payload.put("is_cold_chain", false);

        PosDynamicConditionEvaluator.EvaluationResult res = evaluator.evaluateAndValidate(payload, true);
        assertTrue(res.isValid);
        assertEquals(PosDynamicConditionEvaluator.UiStatus.SUCCESS, res.uiStatus);
        assertTrue(res.activeFields.contains("strips_per_box"));
        assertTrue(res.hiddenFields.contains("volume_ml"));
        assertTrue(res.hiddenFields.contains("min_temperature_celsius"));
        assertNull(res.sanitizedPayload.get("volume_ml"));
    }

    @Test
    @DisplayName("P125-D2: Zero-Trust Guard strictly rejects data submitted in hidden fields")
    void testZeroTrustHiddenFieldRejection() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("brand_name", "Tusca Syrup");
        payload.put("generic_name", "Dextromethorphan");
        payload.put("dosage_form", "SYRUP");
        payload.put("manufacturer", "Square");
        payload.put("strength", "10mg/5mL");
        payload.put("volume_ml", 100.0);
        payload.put("strips_per_box", 10); // INJECTION! Syrup cannot have strips!

        PosDynamicConditionEvaluator.EvaluationResult res = evaluator.evaluateAndValidate(payload, true);
        assertFalse(res.isValid);
        assertEquals(PosDynamicConditionEvaluator.UiStatus.ERROR, res.uiStatus);
        assertEquals("ERR_HIDDEN_FIELD_VALUE_REJECTED", res.errorCode);
        assertTrue(res.messageEn.contains("strips_per_box"));
    }

    @Test
    @DisplayName("P125-D3: Zero-Trust Guard prunes hidden fields safely when non-strict sanitization requested")
    void testZeroTrustHiddenFieldPruning() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("brand_name", "Tusca Syrup");
        payload.put("generic_name", "Dextromethorphan");
        payload.put("dosage_form", "SYRUP");
        payload.put("manufacturer", "Square");
        payload.put("strength", "10mg/5mL");
        payload.put("volume_ml", 100.0);
        payload.put("strips_per_box", 10); // Inactive hidden field

        PosDynamicConditionEvaluator.EvaluationResult res = evaluator.evaluateAndValidate(payload, false);
        assertTrue(res.isValid);
        assertTrue(res.prunedHiddenFields.contains("strips_per_box"));
        assertNull(res.sanitizedPayload.get("strips_per_box"));
    }

    @Test
    @DisplayName("P125-D4: Ambient medicine with is_cold_chain=false rejects hidden temperature data")
    void testColdChainHiddenFieldsWhenFalse() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("brand_name", "Napa 500");
        payload.put("generic_name", "Paracetamol");
        payload.put("dosage_form", "TABLET");
        payload.put("manufacturer", "Beximco");
        payload.put("strength", "500mg");
        payload.put("strips_per_box", 20);
        payload.put("units_per_strip", 10);
        payload.put("is_cold_chain", false);
        payload.put("min_temperature_celsius", 2.0); // Hidden field submitted!

        PosDynamicConditionEvaluator.EvaluationResult res = evaluator.evaluateAndValidate(payload, true);
        assertFalse(res.isValid);
        assertEquals("ERR_HIDDEN_FIELD_VALUE_REJECTED", res.errorCode);
    }

    @Test
    @DisplayName("P125-D5: Type safety checks invalid negative number range")
    void testTypeSafetyIntegerCheck() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("brand_name", "Ace Plus");
        payload.put("generic_name", "Paracetamol");
        payload.put("dosage_form", "TABLET");
        payload.put("manufacturer", "Square");
        payload.put("strength", "500mg");
        payload.put("strips_per_box", -5); // Invalid range!
        payload.put("units_per_strip", 10);

        PosDynamicConditionEvaluator.EvaluationResult res = evaluator.evaluateAndValidate(payload, true);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_NUMBER_RANGE", res.errorCode);
    }

    @Test
    @DisplayName("P125-D6: Invalid select option fails taxonomy validation")
    void testInvalidDosageFormOption() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("brand_name", "Ace Plus");
        payload.put("generic_name", "Paracetamol");
        payload.put("dosage_form", "NON_EXISTENT_DOSAGE_FORM");
        payload.put("manufacturer", "Square");
        payload.put("strength", "500mg");

        PosDynamicConditionEvaluator.EvaluationResult res = evaluator.evaluateAndValidate(payload, true);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_SELECT_OPTION", res.errorCode);
    }
}
