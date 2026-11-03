package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PosInjectionVialEditorClientTest {

    private PosInjectionVialEditorClient client;

    @BeforeEach
    public void setUp() {
        client = new PosInjectionVialEditorClient();
    }

    @Test
    public void testHiddenFieldsCheck() {
        assertTrue(client.isFieldHidden("strips_per_box"));
        assertTrue(client.isFieldHidden("tablets_per_strip"));
        assertTrue(client.isFieldHidden("units_per_strip"));
        assertTrue(client.isFieldHidden("loose_sale_eligible"));
        assertTrue(client.isFieldHidden("needle_gauge"));
        assertFalse(client.isFieldHidden("fill_volume_ml"));
        assertFalse(client.isFieldHidden("sterile_route"));
    }

    @Test
    public void testValidatePayload_Valid() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "INJECTION_SOLUTION");
        payload.put("container_type", "VIAL_SINGLE_DOSE");
        payload.put("strength_value", "100.0");
        payload.put("strength_unit", "mg/ml");
        payload.put("fill_volume_ml", "2.0");
        payload.put("sterile_route", "IV");
        payload.put("is_cold_chain_required", false);

        var result = client.validatePayload(payload);
        assertTrue(result.isValid);
        assertNull(result.errorCode);
    }

    @Test
    public void testValidatePayload_OralRouteRejected() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "INJECTION_SOLUTION");
        payload.put("container_type", "VIAL_SINGLE_DOSE");
        payload.put("strength_value", "100.0");
        payload.put("strength_unit", "mg/ml");
        payload.put("fill_volume_ml", "2.0");
        payload.put("sterile_route", "ORAL"); // Oral route prohibited for injectables

        var result = client.validatePayload(payload);
        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_STERILE_ROUTE", result.errorCode);
    }

    @Test
    public void testValidatePayload_HiddenFieldRejected() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "INJECTION_SOLUTION");
        payload.put("container_type", "VIAL_SINGLE_DOSE");
        payload.put("strength_value", "100.0");
        payload.put("strength_unit", "mg/ml");
        payload.put("fill_volume_ml", "2.0");
        payload.put("sterile_route", "IV");
        payload.put("strips_per_box", 10); // Solid oral packaging attribute forbidden

        var result = client.validatePayload(payload);
        assertFalse(result.isValid);
        assertEquals("ERR_HIDDEN_FIELD_VALUE_REJECTED", result.errorCode);
    }

    @Test
    public void testValidatePayload_ColdChainInvalidRange() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "INJECTION_SUSPENSION");
        payload.put("container_type", "VIAL_MULTI_DOSE");
        payload.put("strength_value", "100.0");
        payload.put("strength_unit", "IU/ml");
        payload.put("fill_volume_ml", "10.0");
        payload.put("sterile_route", "SC");
        payload.put("is_cold_chain_required", true);
        payload.put("min_storage_temp_celsius", "10.0"); // min > max!
        payload.put("max_storage_temp_celsius", "2.0");

        var result = client.validatePayload(payload);
        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_TEMPERATURE_RANGE", result.errorCode);
    }

    @Test
    public void testValidatePayload_AmpoulePreservativeProhibited() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "INJECTION_SOLUTION");
        payload.put("container_type", "AMPOULE_GLASS");
        payload.put("strength_value", "50.0");
        payload.put("strength_unit", "mg/ml");
        payload.put("fill_volume_ml", "1.0");
        payload.put("sterile_route", "IM");
        payload.put("has_preservative", true); // Prohibited on single-dose glass ampoule!

        var result = client.validatePayload(payload);
        assertFalse(result.isValid);
        assertEquals("ERR_SINGLE_DOSE_PRESERVATIVE_PROHIBITED", result.errorCode);
    }

    @Test
    public void testValidatePayload_LyophilizedDiluentRequired() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "INJECTION_POWDER_LYOPHILIZED");
        payload.put("container_type", "VIAL_SINGLE_DOSE");
        payload.put("strength_value", "1.0");
        payload.put("strength_unit", "g/vial");
        payload.put("fill_volume_ml", "10.0");
        payload.put("sterile_route", "IV");
        payload.put("requires_diluent", true);
        // Missing diluent_volume_ml and diluent_type

        var result = client.validatePayload(payload);
        assertFalse(result.isValid);
        assertEquals("ERR_DILUENT_REQUIRED", result.errorCode);

        // When provided, should pass
        payload.put("diluent_volume_ml", "10.0");
        payload.put("diluent_type", "WATER_FOR_INJECTION");
        var passResult = client.validatePayload(payload);
        assertTrue(passResult.isValid);
    }

    @Test
    public void testValidatePayload_InvalidStrengthUnit() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "INJECTION_SOLUTION");
        payload.put("container_type", "VIAL_SINGLE_DOSE");
        payload.put("strength_value", "100.0");
        payload.put("strength_unit", "mg/tablet"); // Invalid for injectables
        payload.put("fill_volume_ml", "2.0");
        payload.put("sterile_route", "IV");

        var result = client.validatePayload(payload);
        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_STRENGTH_UNIT", result.errorCode);
    }

    @Test
    public void testCacheAndOfflineMode() {
        assertFalse(client.isOfflineMode());
        client.setOfflineMode(true);
        assertTrue(client.isOfflineMode());

        var dto = new PosInjectionVialEditorClient.InjectionVialDto(
            "ivc-ins-1", "prod-ins-1", "Insulatard", "Insulin NPH",
            "tn-1", "INJECTION_SUSPENSION", "VIAL_MULTI_DOSE",
            new BigDecimal("100.00"), "IU/ml", new BigDecimal("10.00"),
            "SC", true, new BigDecimal("2.00"), new BigDecimal("8.00"),
            true, false, "NONE", null, true
        );

        client.putCache("ivc-ins-1", dto);
        assertTrue(client.getFromCache("ivc-ins-1").isPresent());
        assertEquals("Insulatard", client.getFromCache("ivc-ins-1").get().brandName);
        assertEquals(1, client.getAllCached().size());

        client.clearCache();
        assertTrue(client.getFromCache("ivc-ins-1").isEmpty());
    }

    @Test
    public void testBilingualLabels() {
        assertEquals("Container Type", client.getLabel("container_type", "en"));
        assertEquals("কনটেইনারের ধরন", client.getLabel("container_type", "bn"));
        assertEquals("Sterile Route", client.getLabel("sterile_route", "en"));
        assertEquals("প্রয়োগের স্টেরাইল রুট", client.getLabel("sterile_route", "bn"));
        assertEquals("Cold-Chain Required", client.getLabel("is_cold_chain_required", "en"));
        assertEquals("কোল্ড চেইন আবশ্যক", client.getLabel("is_cold_chain_required", "bn"));
    }
}
