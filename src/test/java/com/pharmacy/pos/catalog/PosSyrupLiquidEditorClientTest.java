package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PosSyrupLiquidEditorClientTest {

    private PosSyrupLiquidEditorClient client;

    @BeforeEach
    public void setUp() {
        client = new PosSyrupLiquidEditorClient();
    }

    @Test
    public void testValidatePayload_Valid() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "SYRUP");
        payload.put("concentration_value", new BigDecimal("120.0"));
        payload.put("concentration_unit", "mg/5ml");
        payload.put("bottle_volume_ml", new BigDecimal("60.0"));
        payload.put("sale_unit", "Bottle");
        payload.put("route", "Oral");
        payload.put("storage_policy", "ROOM_TEMPERATURE_BELOW_30C");

        PosSyrupLiquidEditorClient.ValidationResult res = client.validatePayload(payload);
        assertTrue(res.isValid);
        assertNull(res.errorCode);
    }

    @Test
    public void testValidatePayload_InvalidStrengthDimension() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "SYRUP");
        payload.put("concentration_value", new BigDecimal("500.0"));
        payload.put("concentration_unit", "mg"); // Standalone mass unit disallowed!
        payload.put("bottle_volume_ml", new BigDecimal("100.0"));
        payload.put("sale_unit", "Bottle");
        payload.put("route", "Oral");
        payload.put("storage_policy", "ROOM_TEMPERATURE_BELOW_30C");

        PosSyrupLiquidEditorClient.ValidationResult res = client.validatePayload(payload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_STRENGTH_DIMENSION_FOR_LIQUID", res.errorCode);
    }

    @Test
    public void testValidatePayload_HiddenFieldRejected() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "SYRUP");
        payload.put("concentration_value", new BigDecimal("120.0"));
        payload.put("concentration_unit", "mg/5ml");
        payload.put("bottle_volume_ml", new BigDecimal("60.0"));
        payload.put("sale_unit", "Bottle");
        payload.put("route", "Oral");
        payload.put("storage_policy", "ROOM_TEMPERATURE_BELOW_30C");
        payload.put("tablets_per_strip", 10); // Solid field!

        PosSyrupLiquidEditorClient.ValidationResult res = client.validatePayload(payload);
        assertFalse(res.isValid);
        assertEquals("ERR_HIDDEN_FIELD_VALUE_REJECTED", res.errorCode);
    }

    @Test
    public void testValidatePayload_InvalidBottleVolume() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "SYRUP");
        payload.put("concentration_value", new BigDecimal("120.0"));
        payload.put("concentration_unit", "mg/5ml");
        payload.put("bottle_volume_ml", BigDecimal.ZERO); // Invalid volume!
        payload.put("sale_unit", "Bottle");
        payload.put("route", "Oral");
        payload.put("storage_policy", "ROOM_TEMPERATURE_BELOW_30C");

        PosSyrupLiquidEditorClient.ValidationResult res = client.validatePayload(payload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_BOTTLE_VOLUME", res.errorCode);
    }

    @Test
    public void testValidatePayload_InvalidSaleUnit() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "SYRUP");
        payload.put("concentration_value", new BigDecimal("120.0"));
        payload.put("concentration_unit", "mg/5ml");
        payload.put("bottle_volume_ml", new BigDecimal("60.0"));
        payload.put("sale_unit", "Strip"); // Invalid sale unit!
        payload.put("route", "Oral");
        payload.put("storage_policy", "ROOM_TEMPERATURE_BELOW_30C");

        PosSyrupLiquidEditorClient.ValidationResult res = client.validatePayload(payload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_SALE_UNIT_FOR_LIQUID", res.errorCode);
    }

    @Test
    public void testValidatePayload_InvalidRoute() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "SYRUP");
        payload.put("concentration_value", new BigDecimal("120.0"));
        payload.put("concentration_unit", "mg/5ml");
        payload.put("bottle_volume_ml", new BigDecimal("60.0"));
        payload.put("sale_unit", "Bottle");
        payload.put("route", "Intravenous"); // Invalid route!
        payload.put("storage_policy", "ROOM_TEMPERATURE_BELOW_30C");

        PosSyrupLiquidEditorClient.ValidationResult res = client.validatePayload(payload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_ROUTE_FOR_DOSAGE_FORM", res.errorCode);
    }

    @Test
    public void testValidatePayload_ReconstitutionWaterMandatory() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "DRY_SYRUP_RECONSTITUTED");
        payload.put("concentration_value", new BigDecimal("125.0"));
        payload.put("concentration_unit", "mg/5ml");
        payload.put("bottle_volume_ml", new BigDecimal("100.0"));
        payload.put("sale_unit", "Bottle");
        payload.put("route", "Oral");
        payload.put("storage_policy", "REFRIGERATE_AFTER_RECONSTITUTION_2_8C");
        payload.put("is_reconstitution_required", true);
        // reconstitution_water_ml omitted!

        PosSyrupLiquidEditorClient.ValidationResult res = client.validatePayload(payload);
        assertFalse(res.isValid);
        assertEquals("ERR_RECONSTITUTION_WATER_REQUIRED", res.errorCode);

        // Allowed when reconstitution_water_ml provided
        payload.put("reconstitution_water_ml", new BigDecimal("75.0"));
        PosSyrupLiquidEditorClient.ValidationResult resOk = client.validatePayload(payload);
        assertTrue(resOk.isValid);
    }

    @Test
    public void testOfflineCacheAndRetrieval() {
        PosSyrupLiquidEditorClient.SyrupLiquidConfigDto dto = new PosSyrupLiquidEditorClient.SyrupLiquidConfigDto(
            "slc-napa-syr", "prod-napa-syr", "Napa Syrup 60mL", "Paracetamol",
            "tn-001", "SYRUP", new BigDecimal("120.0"), "mg/5ml",
            new BigDecimal("60.0"), "Bottle", "Oral", "ROOM_TEMPERATURE_BELOW_30C",
            "MEASURING_CUP", false, null, null, "Strawberry", true
        );

        client.cacheConfig(dto);
        client.setOfflineMode(true);
        assertTrue(client.isOfflineMode());

        PosSyrupLiquidEditorClient.SyrupLiquidConfigDto cached = client.getCachedConfig("prod-napa-syr");
        assertNotNull(cached);
        assertEquals("Napa Syrup 60mL", cached.brandName);
        assertEquals("mg/5ml", cached.concentrationUnit);
        assertEquals(new BigDecimal("60.0"), cached.bottleVolumeMl);
        assertEquals(1, client.getLocalConfigs().size());
    }

    @Test
    public void testBilingualLabels() {
        assertEquals("Bottle Volume (mL)", client.getLabel("bottle_volume_ml", "en"));
        assertEquals("বোতলের আয়তন (মি.লি.)", client.getLabel("bottle_volume_ml", "bn"));
        assertEquals("Concentration Unit", client.getLabel("concentration_unit", "en"));
        assertEquals("ঘনমাত্রার একক", client.getLabel("concentration_unit", "bn"));
    }
}
