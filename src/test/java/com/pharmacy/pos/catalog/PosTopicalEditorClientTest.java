package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosTopicalEditorClientTest
 * Tests for JavaFX POS Topical Cream/Ointment Editor Client (P148).
 */
public class PosTopicalEditorClientTest {

    private PosTopicalEditorClient client;
    private Map<String, Object> validPayload;

    @BeforeEach
    void setUp() {
        client = new PosTopicalEditorClient();
        validPayload = new HashMap<>();
        validPayload.put("product_id", "prod-mupirocin-001");
        validPayload.put("formulation_type", "OINTMENT");
        validPayload.put("administration_route", "TOPICAL_CUTANEOUS");
        validPayload.put("container_type", "TUBE_ALUMINUM");
        validPayload.put("net_weight_grams", "15.00");
        validPayload.put("concentration_value", "2.0000");
        validPayload.put("concentration_unit", "PERCENT_W_W");
        validPayload.put("is_sterile", false);
        validPayload.put("applicator_included", false);
        validPayload.put("opened_shelf_life_days", 28);
    }

    @Test
    @DisplayName("1. Valid topical payload passes client validation")
    void testValidTopicalPayload() {
        PosTopicalEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertTrue(res.isValid);
        assertNull(res.errorCode);
    }

    @Test
    @DisplayName("2. Rejects hidden solid oral field in topical payload")
    void testRejectsSolidOralFields() {
        validPayload.put("strips_per_box", 5);
        PosTopicalEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_HIDDEN_FIELD_VALUE_REJECTED", res.errorCode);
    }

    @Test
    @DisplayName("3. Rejects invalid formulation type")
    void testRejectsInvalidFormulation() {
        validPayload.put("formulation_type", "SYRUP");
        PosTopicalEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_FORMULATION_TYPE", res.errorCode);
    }

    @Test
    @DisplayName("4. Rejects invalid route")
    void testRejectsInvalidRoute() {
        validPayload.put("administration_route", "INTRAVENOUS");
        PosTopicalEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_TOPICAL_ROUTE", res.errorCode);
    }

    @Test
    @DisplayName("5. Rejects zero or negative net weight")
    void testRejectsNonPositiveNetWeight() {
        validPayload.put("net_weight_grams", "0.00");
        PosTopicalEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_NET_WEIGHT", res.errorCode);
    }

    @Test
    @DisplayName("6. Enforces ophthalmic sterility mandate")
    void testEnforcesOphthalmicSterility() {
        validPayload.put("administration_route", "TOPICAL_OPHTHALMIC");
        validPayload.put("is_sterile", false);
        PosTopicalEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_OPHTHALMIC_STERILITY_MANDATORY", res.errorCode);

        // When sterile, passes
        validPayload.put("is_sterile", true);
        PosTopicalEditorClient.ValidationResult resSterile = client.validateDraftPayload(validPayload);
        assertTrue(resSterile.isValid);
    }

    @Test
    @DisplayName("7. Rejects ophthalmic opened shelf life > 28 days")
    void testRejectsOphthalmicShelfLifeOver28Days() {
        validPayload.put("administration_route", "TOPICAL_OPHTHALMIC");
        validPayload.put("is_sterile", true);
        validPayload.put("opened_shelf_life_days", 45);
        PosTopicalEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_OPENED_SHELF_LIFE", res.errorCode);
    }

    @Test
    @DisplayName("8. TopicalRecord field accessibility")
    void testTopicalRecord() {
        PosTopicalEditorClient.TopicalRecord record = new PosTopicalEditorClient.TopicalRecord(
                "cfg-001", "prod-001", "tn-001", "CREAM", "TOPICAL_CUTANEOUS",
                "TUBE_PLASTIC", new BigDecimal("30.00"), new BigDecimal("1.0000"),
                "PERCENT_W_W", false, false, 28, "ROOM_15_25",
                "Apply to skin", "ত্বকে লাগান", true
        );
        assertEquals("cfg-001", record.configId);
        assertEquals("CREAM", record.formulationType);
        assertFalse(record.isSterile);
    }
}

