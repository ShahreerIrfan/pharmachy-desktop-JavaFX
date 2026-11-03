package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PosTabletCapsuleEditorClientTest {

    private PosTabletCapsuleEditorClient client;

    @BeforeEach
    public void setUp() {
        client = new PosTabletCapsuleEditorClient();
    }

    @Test
    public void testCalculateBoxSize() {
        assertEquals(100, client.calculateBoxSize(10, 10));
        assertEquals(240, client.calculateBoxSize(12, 20));
        assertEquals(0, client.calculateBoxSize(0, 10));
    }

    @Test
    public void testValidatePayload_Valid() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "TABLET");
        payload.put("dose_strength", new BigDecimal("500.0"));
        payload.put("strength_unit", "mg");
        payload.put("tablets_per_strip", 10);
        payload.put("strips_per_box", 10);
        payload.put("box_size", 100);
        payload.put("loose_sale_eligible", true);
        payload.put("route", "Oral");
        payload.put("storage_policy", "ROOM_TEMPERATURE_DRY");

        PosTabletCapsuleEditorClient.ValidationResult res = client.validatePayload(payload);
        assertTrue(res.isValid);
        assertNull(res.errorCode);
        assertEquals(100, res.computedBoxSize);
    }

    @Test
    public void testValidatePayload_DimensionMismatch() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "TABLET");
        payload.put("dose_strength", new BigDecimal("500.0"));
        payload.put("strength_unit", "mg");
        payload.put("tablets_per_strip", 10);
        payload.put("strips_per_box", 10);
        payload.put("box_size", 120); // Discrepancy!
        payload.put("loose_sale_eligible", true);
        payload.put("route", "Oral");
        payload.put("storage_policy", "ROOM_TEMPERATURE_DRY");

        PosTabletCapsuleEditorClient.ValidationResult res = client.validatePayload(payload);
        assertFalse(res.isValid);
        assertEquals("ERR_DIMENSION_CONSISTENCY_MISMATCH", res.errorCode);
    }

    @Test
    public void testValidatePayload_EffervescentLooseSaleProhibited() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "EFFERVESCENT_TABLET");
        payload.put("dose_strength", new BigDecimal("1000.0"));
        payload.put("strength_unit", "mg");
        payload.put("tablets_per_strip", 10);
        payload.put("strips_per_box", 2);
        payload.put("box_size", 20);
        payload.put("loose_sale_eligible", true); // Prohibited!
        payload.put("route", "Effervescent");
        payload.put("storage_policy", "PROTECT_FROM_MOISTURE_LIGHT");

        PosTabletCapsuleEditorClient.ValidationResult res = client.validatePayload(payload);
        assertFalse(res.isValid);
        assertEquals("ERR_LOOSE_SALE_NOT_PERMITTED", res.errorCode);

        // Allowed when loose_sale_eligible is false
        payload.put("loose_sale_eligible", false);
        PosTabletCapsuleEditorClient.ValidationResult resOk = client.validatePayload(payload);
        assertTrue(resOk.isValid);
    }

    @Test
    public void testValidatePayload_HiddenFieldRejected() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "TABLET");
        payload.put("dose_strength", new BigDecimal("500.0"));
        payload.put("strength_unit", "mg");
        payload.put("tablets_per_strip", 10);
        payload.put("strips_per_box", 10);
        payload.put("box_size", 100);
        payload.put("loose_sale_eligible", true);
        payload.put("route", "Oral");
        payload.put("storage_policy", "ROOM_TEMPERATURE_DRY");
        payload.put("volume_ml", 100.0); // Hidden liquid field!

        PosTabletCapsuleEditorClient.ValidationResult res = client.validatePayload(payload);
        assertFalse(res.isValid);
        assertEquals("ERR_HIDDEN_FIELD_VALUE_REJECTED", res.errorCode);
    }

    @Test
    public void testValidatePayload_InvalidRoute() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "TABLET");
        payload.put("dose_strength", new BigDecimal("500.0"));
        payload.put("strength_unit", "mg");
        payload.put("tablets_per_strip", 10);
        payload.put("strips_per_box", 10);
        payload.put("box_size", 100);
        payload.put("loose_sale_eligible", true);
        payload.put("route", "Intravenous"); // Invalid for tablet!
        payload.put("storage_policy", "ROOM_TEMPERATURE_DRY");

        PosTabletCapsuleEditorClient.ValidationResult res = client.validatePayload(payload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_ROUTE_FOR_DOSAGE_FORM", res.errorCode);
    }

    @Test
    public void testOfflineCacheAndRetrieval() {
        PosTabletCapsuleEditorClient.TabletCapsuleConfigDto dto = new PosTabletCapsuleEditorClient.TabletCapsuleConfigDto(
            "tcc-napa-500", "prod-napa-500", "Napa 500mg", "Paracetamol",
            "tn-001", "TABLET", new BigDecimal("500.0"), "mg",
            10, 10, 100, true, "Oral", "ROOM_TEMPERATURE_DRY",
            true, "FILM_COATED", true
        );

        client.cacheConfig(dto);
        client.setOfflineMode(true);
        assertTrue(client.isOfflineMode());

        PosTabletCapsuleEditorClient.TabletCapsuleConfigDto cached = client.getCachedConfig("prod-napa-500");
        assertNotNull(cached);
        assertEquals("Napa 500mg", cached.brandName);
        assertEquals(100, cached.boxSize);
        assertTrue(cached.looseSaleEligible);
        assertEquals(1, client.getLocalConfigs().size());
    }

    @Test
    public void testBilingualLabels() {
        assertEquals("Tablets Per Strip", client.getLabel("tablets_per_strip", "en"));
        assertEquals("পাতা প্রতি ট্যাবলেট", client.getLabel("tablets_per_strip", "bn"));
        assertEquals("Loose Sale Eligible", client.getLabel("loose_sale_eligible", "en"));
        assertEquals("খুচরা বিক্রয়যোগ্য", client.getLabel("loose_sale_eligible", "bn"));
    }
}
