package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosSachetEditorClientTest
 * Tests for JavaFX POS Sachet/Powder Editor Client (P149).
 */
public class PosSachetEditorClientTest {

    private PosSachetEditorClient client;
    private Map<String, Object> validPayload;

    @BeforeEach
    void setUp() {
        client = new PosSachetEditorClient();
        validPayload = new HashMap<>();
        validPayload.put("product_id", "prod-ors-001");
        validPayload.put("powder_category", "ORAL_REHYDRATION_SALTS");
        validPayload.put("administration_route", "ORAL");
        validPayload.put("grams_per_sachet", "10.25");
        validPayload.put("sachets_per_pack", 25);
        validPayload.put("total_pack_weight_grams", "256.25");
        validPayload.put("loose_sachet_sale_eligible", true);
        validPayload.put("requires_dissolution", true);
        validPayload.put("diluent_type", "POTABLE_WATER");
        validPayload.put("diluent_volume_ml", "500.00");
    }

    @Test
    @DisplayName("1. Valid sachet payload passes client validation")
    void testValidSachetPayload() {
        PosSachetEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertTrue(res.isValid);
        assertNull(res.errorCode);
    }

    @Test
    @DisplayName("2. Rejects hidden solid oral tablet field in sachet payload")
    void testRejectsSolidOralFields() {
        validPayload.put("strips_per_box", 5);
        PosSachetEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_HIDDEN_FIELD_VALUE_REJECTED", res.errorCode);
    }

    @Test
    @DisplayName("3. Rejects invalid powder category")
    void testRejectsInvalidCategory() {
        validPayload.put("powder_category", "INJECTION_SOLUTION");
        PosSachetEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_POWDER_CATEGORY", res.errorCode);
    }

    @Test
    @DisplayName("4. Rejects invalid route")
    void testRejectsInvalidRoute() {
        validPayload.put("administration_route", "OPHTHALMIC");
        PosSachetEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_ADMINISTRATION_ROUTE", res.errorCode);
    }

    @Test
    @DisplayName("5. Rejects zero or negative grams per sachet")
    void testRejectsNonPositiveGrams() {
        validPayload.put("grams_per_sachet", "0.00");
        PosSachetEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_GRAMS_PER_SACHET", res.errorCode);
    }

    @Test
    @DisplayName("6. Rejects dimension consistency mismatch")
    void testRejectsDimensionConsistencyMismatch() {
        // 10.25 * 25 = 256.25 != 300.00
        validPayload.put("total_pack_weight_grams", "300.00");
        PosSachetEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_DIMENSION_CONSISTENCY_MISMATCH", res.errorCode);
    }

    @Test
    @DisplayName("7. Enforces diluent volume when dissolution is required")
    void testEnforcesDiluentVolume() {
        validPayload.put("requires_dissolution", true);
        validPayload.put("diluent_volume_ml", null);
        PosSachetEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_MISSING_DILUENT_VOLUME", res.errorCode);
    }

    @Test
    @DisplayName("8. SachetRecord field accessibility")
    void testSachetRecord() {
        PosSachetEditorClient.SachetRecord record = new PosSachetEditorClient.SachetRecord(
                "cfg-001", "prod-001", "tn-001", "ORAL_REHYDRATION_SALTS", "ORAL",
                new BigDecimal("10.25"), 25, new BigDecimal("256.25"),
                true, true, "POTABLE_WATER", new BigDecimal("500.00"), 24, "COOL_DRY_BELOW_30",
                "Dissolve in water", "পানিতে গুলিয়ে নিন", true
        );
        assertEquals("cfg-001", record.configId);
        assertEquals(25, record.sachetsPerPack);
        assertTrue(record.looseSachetSaleEligible);
    }
}

