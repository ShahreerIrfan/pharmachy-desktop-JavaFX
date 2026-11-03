package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PosInsulinEditorClientTest {

    private PosInsulinEditorClient client;

    @BeforeEach
    public void setUp() {
        client = new PosInsulinEditorClient();
    }

    @Test
    public void testHiddenFieldsCheck() {
        assertTrue(client.isFieldHidden("strips_per_box"));
        assertTrue(client.isFieldHidden("tablets_per_strip"));
        assertTrue(client.isFieldHidden("box_size"));
        assertTrue(client.isFieldHidden("loose_sale_eligible"));
        assertFalse(client.isFieldHidden("total_units"));
        assertFalse(client.isFieldHidden("concentration_iu_per_ml"));
    }

    @Test
    public void testCalculateTotalUnits() {
        assertEquals(300, client.calculateTotalUnits(100, new BigDecimal("3.00")));
        assertEquals(1000, client.calculateTotalUnits(100, new BigDecimal("10.00")));
        assertEquals(600, client.calculateTotalUnits(200, new BigDecimal("3.00")));
    }

    @Test
    public void testValidatePayload_Valid() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action_category", "INTERMEDIATE_NPH");
        payload.put("delivery_device", "PEN_DISPOSABLE");
        payload.put("concentration_iu_per_ml", 100);
        payload.put("volume_ml", "3.0");
        payload.put("total_units", 300);
        payload.put("in_use_shelf_life_days", 28);
        payload.put("excursion_action", "QUARANTINE_FOR_PHARMACIST_ASSESSMENT");

        var result = client.validatePayload(payload);
        assertTrue(result.isValid);
        assertNull(result.errorCode);
    }

    @Test
    public void testValidatePayload_DimensionMismatch() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action_category", "INTERMEDIATE_NPH");
        payload.put("delivery_device", "PEN_DISPOSABLE");
        payload.put("concentration_iu_per_ml", 100);
        payload.put("volume_ml", "3.0");
        payload.put("total_units", 400); // 100 * 3.0 = 300 != 400
        payload.put("in_use_shelf_life_days", 28);
        payload.put("excursion_action", "QUARANTINE_FOR_PHARMACIST_ASSESSMENT");

        var result = client.validatePayload(payload);
        assertFalse(result.isValid);
        assertEquals("ERR_DIMENSION_CONSISTENCY_MISMATCH", result.errorCode);
    }

    @Test
    public void testValidatePayload_HiddenFieldRejected() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action_category", "INTERMEDIATE_NPH");
        payload.put("delivery_device", "PEN_DISPOSABLE");
        payload.put("concentration_iu_per_ml", 100);
        payload.put("volume_ml", "3.0");
        payload.put("total_units", 300);
        payload.put("in_use_shelf_life_days", 28);
        payload.put("excursion_action", "QUARANTINE_FOR_PHARMACIST_ASSESSMENT");
        payload.put("strips_per_box", 5); // Solid oral attribute prohibited

        var result = client.validatePayload(payload);
        assertFalse(result.isValid);
        assertEquals("ERR_HIDDEN_FIELD_VALUE_REJECTED", result.errorCode);
    }

    @Test
    public void testValidatePayload_InvalidConcentration() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action_category", "INTERMEDIATE_NPH");
        payload.put("delivery_device", "PEN_DISPOSABLE");
        payload.put("concentration_iu_per_ml", 150); // Invalid concentration (must be 100, 200, 300, 500)
        payload.put("volume_ml", "3.0");
        payload.put("total_units", 450);
        payload.put("in_use_shelf_life_days", 28);
        payload.put("excursion_action", "QUARANTINE_FOR_PHARMACIST_ASSESSMENT");

        var result = client.validatePayload(payload);
        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_INSULIN_CONCENTRATION", result.errorCode);
    }

    @Test
    public void testValidatePayload_InUseShelfLifeRange() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action_category", "INTERMEDIATE_NPH");
        payload.put("delivery_device", "PEN_DISPOSABLE");
        payload.put("concentration_iu_per_ml", 100);
        payload.put("volume_ml", "3.0");
        payload.put("total_units", 300);
        payload.put("in_use_shelf_life_days", 10); // Less than 14 days minimum!
        payload.put("excursion_action", "QUARANTINE_FOR_PHARMACIST_ASSESSMENT");

        var result = client.validatePayload(payload);
        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_IN_USE_SHELF_LIFE", result.errorCode);

        payload.put("in_use_shelf_life_days", 60); // Greater than 56 days maximum!
        var resHigh = client.validatePayload(payload);
        assertFalse(resHigh.isValid);
        assertEquals("ERR_INVALID_IN_USE_SHELF_LIFE", resHigh.errorCode);
    }

    @Test
    public void testCacheAndOfflineMode() {
        assertFalse(client.isOfflineMode());
        client.setOfflineMode(true);
        assertTrue(client.isOfflineMode());

        var dto = new PosInsulinEditorClient.InsulinConfigDto(
            "ins-novo-1", "prod-novo-1", "NovoMix 30", "Biphasic Insulin Aspart",
            "tn-1", "PREMIXED_BIPHASIC", "PEN_DISPOSABLE",
            100, new BigDecimal("3.00"), 300, 28,
            new BigDecimal("2.00"), new BigDecimal("8.00"), new BigDecimal("30.00"),
            "QUARANTINE_FOR_PHARMACIST_ASSESSMENT",
            "Store in fridge", "ফ্রিজে রাখুন", true
        );

        client.putCache("ins-novo-1", dto);
        assertTrue(client.getFromCache("ins-novo-1").isPresent());
        assertEquals("NovoMix 30", client.getFromCache("ins-novo-1").get().brandName);
        assertEquals(1, client.getAllCached().size());

        client.clearCache();
        assertTrue(client.getFromCache("ins-novo-1").isEmpty());
    }

    @Test
    public void testBilingualLabels() {
        assertEquals("Action Profile", client.getLabel("action_category", "en"));
        assertEquals("কার্যকারিতার ধরন", client.getLabel("action_category", "bn"));
        assertEquals("Total Units (IU)", client.getLabel("total_units", "en"));
        assertEquals("মোট সক্রিয় ইউনিট (আইইউ)", client.getLabel("total_units", "bn"));
        assertEquals("In-Use Shelf Life (Days)", client.getLabel("in_use_shelf_life_days", "en"));
        assertEquals("ব্যবহারকালীন মেয়াদ (দিন)", client.getLabel("in_use_shelf_life_days", "bn"));
    }
}

