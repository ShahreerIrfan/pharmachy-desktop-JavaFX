package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PosSuspensionReconstitutionClientTest {

    private PosSuspensionReconstitutionClient client;

    @BeforeEach
    public void setUp() {
        client = new PosSuspensionReconstitutionClient();
    }

    @Test
    public void testHiddenFieldsCheck() {
        assertTrue(client.isFieldHidden("strips_per_box"));
        assertTrue(client.isFieldHidden("tablets_per_strip"));
        assertTrue(client.isFieldHidden("units_per_strip"));
        assertTrue(client.isFieldHidden("loose_sale_eligible"));
        assertTrue(client.isFieldHidden("is_scored"));
        assertFalse(client.isFieldHidden("water_volume_ml"));
        assertFalse(client.isFieldHidden("final_volume_ml"));
    }

    @Test
    public void testVolumeDimensionConsistencyValid() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "DRY_SYRUP_RECONSTITUTED");
        payload.put("reconstitution_diluent", "BOILED_COOLED_WATER");
        payload.put("water_volume_ml", "35.0");
        payload.put("final_volume_ml", "50.0");
        payload.put("shelf_life_days", 7);
        payload.put("instructions_en", "Add 35 mL water and shake well.");
        payload.put("storage_policy", "REFRIGERATE_2_8C_AFTER_RECONSTITUTION");

        var result = client.validatePayload(payload, "STAFF_PHARMACIST");
        assertTrue(result.isValid);
        assertNull(result.errorCode);
    }

    @Test
    public void testVolumeDimensionConsistencyWaterExceedsFinal() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "DRY_SYRUP_RECONSTITUTED");
        payload.put("reconstitution_diluent", "BOILED_COOLED_WATER");
        payload.put("water_volume_ml", "60.0"); // 60 mL water for 50 mL bottle is impossible
        payload.put("final_volume_ml", "50.0");
        payload.put("shelf_life_days", 7);
        payload.put("instructions_en", "Add 60 mL water.");
        payload.put("storage_policy", "REFRIGERATE_2_8C_AFTER_RECONSTITUTION");

        var result = client.validatePayload(payload, "STAFF_PHARMACIST");
        assertFalse(result.isValid);
        assertEquals("ERR_DIMENSION_CONSISTENCY_MISMATCH", result.errorCode);
    }

    @Test
    public void testRejectHiddenSolidField() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "DRY_SYRUP_RECONSTITUTED");
        payload.put("reconstitution_diluent", "BOILED_COOLED_WATER");
        payload.put("water_volume_ml", "35.0");
        payload.put("final_volume_ml", "50.0");
        payload.put("shelf_life_days", 7);
        payload.put("instructions_en", "Add 35 mL water.");
        payload.put("storage_policy", "REFRIGERATE_2_8C_AFTER_RECONSTITUTION");
        payload.put("tablets_per_strip", 10); // Solid oral attribute prohibited

        var result = client.validatePayload(payload, "STAFF_PHARMACIST");
        assertFalse(result.isValid);
        assertEquals("ERR_HIDDEN_FIELD_VALUE_REJECTED", result.errorCode);
    }

    @Test
    public void testBeyondUseDateAutoCalculation() {
        LocalDate openDate = LocalDate.of(2026, 11, 3);
        LocalDate bud = client.calculateBeyondUseDate(openDate, 7);
        assertEquals(LocalDate.of(2026, 11, 10), bud);
    }

    @Test
    public void testBeyondUseDatePrecedingOpeningDateRejected() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "DRY_SYRUP_RECONSTITUTED");
        payload.put("reconstitution_diluent", "BOILED_COOLED_WATER");
        payload.put("water_volume_ml", "35.0");
        payload.put("final_volume_ml", "50.0");
        payload.put("shelf_life_days", 7);
        payload.put("opening_date", "2026-11-03");
        payload.put("beyond_use_date", "2026-11-02"); // Before opening date
        payload.put("instructions_en", "Add 35 mL water.");
        payload.put("storage_policy", "REFRIGERATE_2_8C_AFTER_RECONSTITUTION");

        var result = client.validatePayload(payload, "STAFF_PHARMACIST");
        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_BEYOND_USE_DATE", result.errorCode);
    }

    @Test
    public void testBeyondUseDateExceedingShelfLifeRejected() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "DRY_SYRUP_RECONSTITUTED");
        payload.put("reconstitution_diluent", "BOILED_COOLED_WATER");
        payload.put("water_volume_ml", "35.0");
        payload.put("final_volume_ml", "50.0");
        payload.put("shelf_life_days", 7);
        payload.put("opening_date", "2026-11-03");
        payload.put("beyond_use_date", "2026-11-15"); // Exceeds 7-day shelf life
        payload.put("instructions_en", "Add 35 mL water.");
        payload.put("storage_policy", "REFRIGERATE_2_8C_AFTER_RECONSTITUTION");

        var result = client.validatePayload(payload, "STAFF_PHARMACIST");
        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_BEYOND_USE_DATE", result.errorCode);
    }

    @Test
    public void testPharmacistReviewAuthorizedRoles() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dosage_form", "DRY_SYRUP_RECONSTITUTED");
        payload.put("reconstitution_diluent", "BOILED_COOLED_WATER");
        payload.put("water_volume_ml", "35.0");
        payload.put("final_volume_ml", "50.0");
        payload.put("shelf_life_days", 7);
        payload.put("instructions_en", "Add 35 mL water.");
        payload.put("storage_policy", "REFRIGERATE_2_8C_AFTER_RECONSTITUTION");
        payload.put("is_pharmacist_reviewed", true);

        // Pharmacist OK
        var res1 = client.validatePayload(payload, "STAFF_PHARMACIST");
        assertTrue(res1.isValid);

        // Owner OK
        var res2 = client.validatePayload(payload, "PHARMACY_OWNER");
        assertTrue(res2.isValid);

        // Cashier Rejected
        var res3 = client.validatePayload(payload, "CASHIER");
        assertFalse(res3.isValid);
        assertEquals("ERR_PHARMACIST_REVIEW_REQUIRED", res3.errorCode);
    }

    @Test
    public void testCacheAndOfflineMode() {
        assertFalse(client.isOfflineMode());
        client.setOfflineMode(true);
        assertTrue(client.isOfflineMode());

        var dto = new PosSuspensionReconstitutionClient.SuspensionReconstitutionDto(
            "src-cef-1", "prod-cef-1", "Cef-3", "Cefixime",
            "tn-1", "DRY_SYRUP_RECONSTITUTED", "BOILED_COOLED_WATER",
            new BigDecimal("35.00"), new BigDecimal("50.00"), 7,
            LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 10),
            "REFRIGERATE_2_8C_AFTER_RECONSTITUTION",
            "Add 35 mL water in two parts.", "৩৫ মি.লি. পানি মেশান।",
            "Refrigerate after mix.", "২-৮ ডিগ্রিতে রাখুন।",
            true, "usr-pharma-1", "Registered Pharmacist",
            "Monograph verified", true
        );
        client.putCache("src-cef-1", dto);
        assertTrue(client.getFromCache("src-cef-1").isPresent());
        assertEquals("Cef-3", client.getFromCache("src-cef-1").get().brandName);
        assertEquals(1, client.getAllCached().size());

        client.clearCache();
        assertTrue(client.getFromCache("src-cef-1").isEmpty());
    }

    @Test
    public void testBilingualLabels() {
        assertEquals("Reconstitution Diluent", client.getLabel("reconstitution_diluent", "en"));
        assertEquals("প্রস্তুতকারক পানি / দ্রাবক", client.getLabel("reconstitution_diluent", "bn"));
        assertEquals("Beyond-Use Date (BUD)", client.getLabel("beyond_use_date", "en"));
        assertEquals("ব্যবহারের শেষ তারিখ (বিইউডি)", client.getLabel("beyond_use_date", "bn"));
    }
}
