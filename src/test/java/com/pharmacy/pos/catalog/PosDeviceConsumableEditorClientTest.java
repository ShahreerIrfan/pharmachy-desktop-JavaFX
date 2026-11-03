package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosDeviceConsumableEditorClientTest
 * Tests for JavaFX POS Medical Device and Consumable Editor Client (P151).
 */
public class PosDeviceConsumableEditorClientTest {

    private PosDeviceConsumableEditorClient client;
    private Map<String, Object> needlePayload;
    private Map<String, Object> stripPayload;

    @BeforeEach
    void setUp() {
        client = new PosDeviceConsumableEditorClient();

        needlePayload = new HashMap<>();
        needlePayload.put("product_id", "prod-needle-31g-001");
        needlePayload.put("device_category", "NEEDLE_OR_SYRINGE");
        needlePayload.put("brand_name", "BD Ultra-Fine");
        needlePayload.put("model_number", "UF-31G-5MM");
        needlePayload.put("needle_gauge", "31G");
        needlePayload.put("needle_length_mm", "5.00");
        needlePayload.put("is_sterile", true);
        needlePayload.put("is_single_use", true);
        needlePayload.put("storage_temperature_celsius_min", "2.00");
        needlePayload.put("storage_temperature_celsius_max", "30.00");

        stripPayload = new HashMap<>();
        stripPayload.put("product_id", "prod-accuchek-strip-50");
        stripPayload.put("device_category", "DIAGNOSTIC_TEST_STRIP");
        stripPayload.put("brand_name", "Accu-Chek Instant");
        stripPayload.put("meter_compatibility", "Accu-Chek Instant Meter");
        stripPayload.put("strip_count_per_pack", 50);
        stripPayload.put("sample_volume_microliter", "0.60");
        stripPayload.put("test_time_seconds", 4);
        stripPayload.put("is_sterile", false);
        stripPayload.put("is_single_use", true);
    }

    @Test
    @DisplayName("1. Valid needle payload passes client validation")
    void testValidNeedlePayload() {
        PosDeviceConsumableEditorClient.ValidationResult res = client.validateDraftPayload(needlePayload);
        assertTrue(res.isValid);
        assertNull(res.errorCode);
    }

    @Test
    @DisplayName("2. Valid diagnostic strip payload passes client validation")
    void testValidStripPayload() {
        PosDeviceConsumableEditorClient.ValidationResult res = client.validateDraftPayload(stripPayload);
        assertTrue(res.isValid);
        assertNull(res.errorCode);
    }

    @Test
    @DisplayName("3. Rejects medicine-only fields (ERR_MEDICINE_FIELD_REJECTED)")
    void testRejectsMedicineOnlyFields() {
        needlePayload.put("dosage_form", "TABLET");
        PosDeviceConsumableEditorClient.ValidationResult res = client.validateDraftPayload(needlePayload);
        assertFalse(res.isValid);
        assertEquals("ERR_MEDICINE_FIELD_REJECTED", res.errorCode);

        needlePayload.remove("dosage_form");
        needlePayload.put("is_antibiotic", true);
        PosDeviceConsumableEditorClient.ValidationResult res2 = client.validateDraftPayload(needlePayload);
        assertFalse(res2.isValid);
        assertEquals("ERR_MEDICINE_FIELD_REJECTED", res2.errorCode);
    }

    @Test
    @DisplayName("4. Rejects invalid device category")
    void testRejectsInvalidCategory() {
        needlePayload.put("device_category", "ORAL_LIQUID");
        PosDeviceConsumableEditorClient.ValidationResult res = client.validateDraftPayload(needlePayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_DEVICE_CATEGORY", res.errorCode);
    }

    @Test
    @DisplayName("5. Rejects empty brand name")
    void testRejectsEmptyBrandName() {
        needlePayload.put("brand_name", "  ");
        PosDeviceConsumableEditorClient.ValidationResult res = client.validateDraftPayload(needlePayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_BRAND_NAME", res.errorCode);
    }

    @Test
    @DisplayName("6. Rejects needle without specified gauge")
    void testRejectsNeedleWithoutGauge() {
        needlePayload.put("needle_gauge", "NA");
        PosDeviceConsumableEditorClient.ValidationResult res = client.validateDraftPayload(needlePayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_NEEDLE_GAUGE", res.errorCode);
    }

    @Test
    @DisplayName("7. Rejects needle with non-positive length")
    void testRejectsNonPositiveNeedleLength() {
        needlePayload.put("needle_length_mm", "0.00");
        PosDeviceConsumableEditorClient.ValidationResult res = client.validateDraftPayload(needlePayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_NEEDLE_LENGTH", res.errorCode);
    }

    @Test
    @DisplayName("8. Rejects diagnostic strip missing meter compatibility")
    void testRejectsStripMissingMeter() {
        stripPayload.put("meter_compatibility", "");
        PosDeviceConsumableEditorClient.ValidationResult res = client.validateDraftPayload(stripPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_MISSING_METER_COMPATIBILITY", res.errorCode);
    }

    @Test
    @DisplayName("9. Rejects invalid strip count per pack")
    void testRejectsInvalidStripCount() {
        stripPayload.put("strip_count_per_pack", 0);
        PosDeviceConsumableEditorClient.ValidationResult res = client.validateDraftPayload(stripPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_STRIP_COUNT", res.errorCode);
    }

    @Test
    @DisplayName("10. Rejects min storage temperature greater than max")
    void testRejectsInvalidTemperatureRange() {
        needlePayload.put("storage_temperature_celsius_min", "35.00");
        needlePayload.put("storage_temperature_celsius_max", "20.00");
        PosDeviceConsumableEditorClient.ValidationResult res = client.validateDraftPayload(needlePayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_TEMPERATURE_RANGE", res.errorCode);
    }
}
