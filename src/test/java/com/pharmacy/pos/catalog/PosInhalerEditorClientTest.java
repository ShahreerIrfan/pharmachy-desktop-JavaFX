package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosInhalerEditorClientTest
 * Tests for JavaFX POS Inhaler Editor Client (P146).
 */
public class PosInhalerEditorClientTest {

    private PosInhalerEditorClient client;
    private Map<String, Object> validPayload;

    @BeforeEach
    void setUp() {
        client = new PosInhalerEditorClient();
        validPayload = new HashMap<>();
        validPayload.put("product_id", "prod-salbutamol-001");
        validPayload.put("device_type", "MDI_PRESSURIZED");
        validPayload.put("administration_route", "ORAL_INHALATION");
        validPayload.put("dose_per_actuation", "100.0000");
        validPayload.put("dose_unit", "MCG");
        validPayload.put("total_actuations", 200);
        validPayload.put("total_active_content", "20000.0000");
        validPayload.put("has_dose_counter", true);
        validPayload.put("has_spacer_included", false);
        validPayload.put("storage_condition", "ROOM_15_25");
        validPayload.put("priming_required", true);
        validPayload.put("priming_puffs", 2);
    }

    @Test
    @DisplayName("1. Valid inhaler payload passes client validation")
    void testValidInhalerPayload() {
        PosInhalerEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertTrue(res.isValid);
        assertNull(res.errorCode);
    }

    @Test
    @DisplayName("2. Rejects hidden solid oral field in inhaler payload")
    void testRejectsSolidOralFields() {
        validPayload.put("strips_per_box", 5);
        PosInhalerEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_HIDDEN_FIELD_VALUE_REJECTED", res.errorCode);
    }

    @Test
    @DisplayName("3. Rejects invalid device type")
    void testRejectsInvalidDeviceType() {
        validPayload.put("device_type", "INJECTION_PEN");
        PosInhalerEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_DEVICE_TYPE", res.errorCode);
    }

    @Test
    @DisplayName("4. Rejects invalid route")
    void testRejectsInvalidRoute() {
        validPayload.put("administration_route", "TOPICAL");
        PosInhalerEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_ADMINISTRATION_ROUTE", res.errorCode);
    }

    @Test
    @DisplayName("5. Rejects zero or negative dose per actuation")
    void testRejectsNonPositiveDose() {
        validPayload.put("dose_per_actuation", "0.0000");
        PosInhalerEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_DOSE_PER_ACTUATION", res.errorCode);
    }

    @Test
    @DisplayName("6. Rejects out-of-range actuations (< 10 or > 1000)")
    void testRejectsOutOfRangeActuations() {
        validPayload.put("total_actuations", 5);
        PosInhalerEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_ACTUATIONS_COUNT", res.errorCode);
    }

    @Test
    @DisplayName("7. Rejects dimension consistency mismatch")
    void testRejectsDimensionConsistencyMismatch() {
        // 100 * 200 = 20000, mismatch with 25000
        validPayload.put("total_active_content", "25000.0000");
        PosInhalerEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_DIMENSION_CONSISTENCY_MISMATCH", res.errorCode);
    }

    @Test
    @DisplayName("8. InhalerRecord field accessibility")
    void testInhalerRecord() {
        PosInhalerEditorClient.InhalerRecord record = new PosInhalerEditorClient.InhalerRecord(
                "cfg-001", "prod-001", "tn-001", "MDI_PRESSURIZED", "ORAL_INHALATION",
                new BigDecimal("100.0000"), "MCG", 200, new BigDecimal("20000.0000"),
                true, false, "ROOM_15_25", true, 2, true,
                "Inhale 1 puff", "১ পাফ ইনহেল করুন", true
        );
        assertEquals("cfg-001", record.configId);
        assertEquals(200, record.totalActuations);
        assertTrue(record.hasDoseCounter);
        assertFalse(record.hasSpacerIncluded);
    }
}

