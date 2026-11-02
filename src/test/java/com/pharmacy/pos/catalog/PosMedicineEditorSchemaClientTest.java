package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosMedicineEditorSchemaClientTest
 * Architecture Decision: ADR-0124 (P124).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 */
class PosMedicineEditorSchemaClientTest {

    private PosMedicineEditorSchemaClient client;

    @BeforeEach
    void setUp() {
        client = new PosMedicineEditorSchemaClient();
    }

    @Test
    @DisplayName("P124-D1: Active schema loaded with standard sections and v1.0 versioning")
    void testActiveSchemaLoaded() {
        PosMedicineEditorSchemaClient.MedicineEditorSchemaRecord schema = client.getActiveSchema();
        assertNotNull(schema);
        assertEquals("v1.0", schema.schemaVersion);
        assertTrue(schema.isActive);
        assertEquals(4, schema.sections.size());
    }

    @Test
    @DisplayName("P124-D2: Dynamic visibility handles conditional sections for TABLET vs SYRUP")
    void testConditionalVisibility() {
        Map<String, Object> tabletValues = new HashMap<>();
        tabletValues.put("dosage_form", "TABLET");

        assertTrue(client.isFieldVisible("strips_per_box", tabletValues));
        assertTrue(client.isFieldRequired("strips_per_box", tabletValues));
        assertFalse(client.isFieldVisible("volume_ml", tabletValues));

        Map<String, Object> syrupValues = new HashMap<>();
        syrupValues.put("dosage_form", "SYRUP");

        assertFalse(client.isFieldVisible("strips_per_box", syrupValues));
        assertTrue(client.isFieldVisible("volume_ml", syrupValues));
        assertTrue(client.isFieldRequired("volume_ml", syrupValues));
    }

    @Test
    @DisplayName("P124-D3: Valid tablet payload passes schema validation")
    void testValidTabletSubmission() {
        Map<String, Object> values = new HashMap<>();
        values.put("brand_name", "Napa Extra");
        values.put("generic_name", "Paracetamol + Caffeine");
        values.put("dosage_form", "TABLET");
        values.put("manufacturer", "Beximco Pharmaceuticals Ltd.");
        values.put("strength", "500mg+65mg");
        values.put("strips_per_box", 20);
        values.put("units_per_strip", 10);

        PosMedicineEditorSchemaClient.ValidationResult result = client.validateForm(values);
        assertTrue(result.isValid);
        assertEquals(PosMedicineEditorSchemaClient.UiStatus.SUCCESS, result.uiStatus);
        assertEquals("v1.0", result.schemaVersion);
    }

    @Test
    @DisplayName("P124-D4: Incomplete basic payload fails validation with ERR_SCHEMA_VALIDATION_FAILED")
    void testMissingRequiredFields() {
        Map<String, Object> values = new HashMap<>();
        values.put("brand_name", "");
        values.put("generic_name", "Paracetamol");
        values.put("dosage_form", "TABLET");

        PosMedicineEditorSchemaClient.ValidationResult result = client.validateForm(values);
        assertFalse(result.isValid);
        assertEquals(PosMedicineEditorSchemaClient.UiStatus.ERROR, result.uiStatus);
        assertEquals("ERR_SCHEMA_VALIDATION_FAILED", result.errorCode);
        assertTrue(result.missingFields.contains("brand_name"));
    }

    @Test
    @DisplayName("P124-D5: Missing conditional strip fields for TABLET fails with ERR_CONDITIONAL_FIELD_REQUIRED")
    void testMissingConditionalTabletFields() {
        Map<String, Object> values = new HashMap<>();
        values.put("brand_name", "Ace Plus");
        values.put("generic_name", "Paracetamol + Caffeine");
        values.put("dosage_form", "TABLET");
        values.put("manufacturer", "Square Pharmaceuticals Ltd.");
        values.put("strength", "500mg+65mg");
        // strips_per_box omitted!

        PosMedicineEditorSchemaClient.ValidationResult result = client.validateForm(values);
        assertFalse(result.isValid);
        assertEquals("ERR_CONDITIONAL_FIELD_REQUIRED", result.errorCode);
        assertTrue(result.missingFields.contains("strips_per_box"));
    }

    @Test
    @DisplayName("P124-D6: Inverted cold-chain temperature range fails with ERR_INVALID_TEMPERATURE_RANGE")
    void testColdChainInvertedTemperatureRange() {
        Map<String, Object> values = new HashMap<>();
        values.put("brand_name", "Insulin Glargine");
        values.put("generic_name", "Insulin Glargine");
        values.put("dosage_form", "INJECTION");
        values.put("manufacturer", "Novo Nordisk");
        values.put("strength", "100 IU/mL");
        values.put("is_cold_chain", true);
        values.put("min_temperature_celsius", 10.0);
        values.put("max_temperature_celsius", 5.0); // Inverted: min > max!

        PosMedicineEditorSchemaClient.ValidationResult result = client.validateForm(values);
        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_TEMPERATURE_RANGE", result.errorCode);
    }
}
