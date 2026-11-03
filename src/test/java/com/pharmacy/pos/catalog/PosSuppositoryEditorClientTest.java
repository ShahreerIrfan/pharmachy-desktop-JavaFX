package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosSuppositoryEditorClientTest
 * Tests for JavaFX POS Suppository & Pessary Editor Client (P150).
 */
public class PosSuppositoryEditorClientTest {

    private PosSuppositoryEditorClient client;
    private Map<String, Object> validPayload;

    @BeforeEach
    void setUp() {
        client = new PosSuppositoryEditorClient();
        validPayload = new HashMap<>();
        validPayload.put("product_id", "prod-napa-supp-001");
        validPayload.put("suppository_base_type", "FATTY_BASE_COCOA_BUTTER");
        validPayload.put("administration_route", "RECTAL");
        validPayload.put("strength_value", "250.0000");
        validPayload.put("strength_unit", "MG");
        validPayload.put("pieces_per_strip", 5);
        validPayload.put("strips_per_pack", 2);
        validPayload.put("total_pieces_per_pack", 10);
        validPayload.put("loose_piece_sale_eligible", true);
        validPayload.put("requires_refrigeration", true);
        validPayload.put("storage_condition", "REFRIGERATED_2_8");
    }

    @Test
    @DisplayName("1. Valid suppository payload passes client validation")
    void testValidSuppositoryPayload() {
        PosSuppositoryEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertTrue(res.isValid);
        assertNull(res.errorCode);
    }

    @Test
    @DisplayName("2. Rejects hidden solid oral tablet field in suppository payload")
    void testRejectsSolidOralFields() {
        validPayload.put("tablets_per_strip", 10);
        PosSuppositoryEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_HIDDEN_FIELD_VALUE_REJECTED", res.errorCode);
    }

    @Test
    @DisplayName("3. Rejects invalid suppository base")
    void testRejectsInvalidBase() {
        validPayload.put("suppository_base_type", "PETROLEUM_JELLY");
        PosSuppositoryEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_SUPPOSITORY_BASE", res.errorCode);
    }

    @Test
    @DisplayName("4. Rejects invalid route")
    void testRejectsInvalidRoute() {
        validPayload.put("administration_route", "ORAL");
        PosSuppositoryEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_SUPPOSITORY_ROUTE", res.errorCode);
    }

    @Test
    @DisplayName("5. Rejects zero or negative strength")
    void testRejectsNonPositiveStrength() {
        validPayload.put("strength_value", "0.0000");
        PosSuppositoryEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_INVALID_STRENGTH", res.errorCode);
    }

    @Test
    @DisplayName("6. Rejects dimension consistency mismatch")
    void testRejectsDimensionConsistencyMismatch() {
        // 5 * 2 = 10 != 15
        validPayload.put("total_pieces_per_pack", 15);
        PosSuppositoryEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_DIMENSION_CONSISTENCY_MISMATCH", res.errorCode);
    }

    @Test
    @DisplayName("7. Enforces melt protection when refrigeration is required")
    void testEnforcesMeltProtectionStorage() {
        validPayload.put("requires_refrigeration", true);
        validPayload.put("storage_condition", "COOL_BELOW_30");
        PosSuppositoryEditorClient.ValidationResult res = client.validateDraftPayload(validPayload);
        assertFalse(res.isValid);
        assertEquals("ERR_MELT_PROTECTION_STORAGE_REQUIRED", res.errorCode);
    }

    @Test
    @DisplayName("8. SuppositoryRecord field accessibility")
    void testSuppositoryRecord() {
        PosSuppositoryEditorClient.SuppositoryRecord record = new PosSuppositoryEditorClient.SuppositoryRecord(
                "cfg-001", "prod-001", "tn-001", "FATTY_BASE_COCOA_BUTTER", "RECTAL",
                new BigDecimal("250.0000"), "MG", 5, 2, 10,
                true, true, "REFRIGERATED_2_8",
                "Insert into rectum", "মলদ্বারে প্রবেশ করান", true
        );
        assertEquals("cfg-001", record.configId);
        assertEquals(5, record.piecesPerStrip);
        assertEquals(10, record.totalPiecesPerPack);
        assertTrue(record.requiresRefrigeration);
    }
}
