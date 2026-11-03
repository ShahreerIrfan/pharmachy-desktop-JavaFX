package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Test Suite for PosDropsEditorClient.
 * Architecture Decision: ADR-0147 (P147).
 */
public class PosDropsEditorClientTest {

    private PosDropsEditorClient client;
    private Map<String, Object> validPayload;

    @BeforeEach
    void setUp() {
        client = new PosDropsEditorClient();
        validPayload = new HashMap<>();
        validPayload.put("product_id", "med-cipro-001");
        validPayload.put("route", "OPHTHALMIC");
        validPayload.put("container_type", "DROPPER_BOTTLE");
        validPayload.put("volume_ml", "5.00");
        validPayload.put("concentration_value", "0.3000");
        validPayload.put("concentration_unit", "PERCENT_W_V");
        validPayload.put("opened_shelf_life_days", 28);
    }

    @Test
    @DisplayName("1. Valid drops payload passes client validation")
    void testValidPayload() {
        PosDropsEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertTrue(res.isValid);
        assertNull(res.errorCode);
    }

    @Test
    @DisplayName("2. Rejects hidden solid oral field in drops payload")
    void testRejectSolidFields() {
        validPayload.put("strips_per_box", 10);
        PosDropsEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_HIDDEN_FIELD_VALUE_REJECTED", res.errorCode);
    }

    @Test
    @DisplayName("3. Rejects invalid route")
    void testInvalidRoute() {
        validPayload.put("route", "INTRAVENOUS");
        PosDropsEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_DROPS_ROUTE", res.errorCode);
    }

    @Test
    @DisplayName("4. Rejects negative or zero fill volume")
    void testNegativeVolume() {
        validPayload.put("volume_ml", "-2.5");
        PosDropsEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_VOLUME", res.errorCode);
    }

    @Test
    @DisplayName("5. Rejects out-of-range opened shelf life")
    void testOutOfRangeBud() {
        validPayload.put("opened_shelf_life_days", 90);
        PosDropsEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_OPENED_SHELF_LIFE", res.errorCode);
    }

    @Test
    @DisplayName("6. Single-dose minim must have opened_shelf_life_days = 1")
    void testSingleDoseMinimRule() {
        validPayload.put("container_type", "SINGLE_DOSE_MINIM");
        validPayload.put("opened_shelf_life_days", 14);
        PosDropsEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_SINGLE_DOSE_MINIM_EXPIRY", res.errorCode);

        validPayload.put("opened_shelf_life_days", 1);
        PosDropsEditorClient.ValidationResult validMinim = client.validateDraftPayload(validPayload);
        assertTrue(validMinim.isValid);
    }
}

