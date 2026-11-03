package com.pharmacy.pos.payload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosFlexibleMedicinePayloadClientTest
 * Architecture Decision: ADR-0159 (P159).
 * Unit test suite for typed, versioned dynamic medicine API payloads.
 */
public class PosFlexibleMedicinePayloadClientTest {

    private PosFlexibleMedicinePayloadClient client;

    @BeforeEach
    public void setUp() {
        client = new PosFlexibleMedicinePayloadClient();
    }

    @Test
    @DisplayName("1. Valid scenario: Typed versioned INHALER payload is accepted")
    public void testValidInhalerPayloadAccepted() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("brand_name", "Ventolin Evohaler");
        attrs.put("generic_name", "Salbutamol");
        attrs.put("dar_number", "001-223-445");
        attrs.put("cost_unit", "180.00");
        attrs.put("mrp_unit", "225.00");
        attrs.put("dose_per_actuation", "100mcg");
        attrs.put("actuations_per_device", 200);

        PosFlexibleMedicinePayloadClient.PayloadValidationReport report =
                client.validatePayload("2026.01", "INHALER", attrs);

        assertTrue(report.isValid);
        assertEquals(0, report.errorCount);
        assertTrue(report.errors.isEmpty());
    }

    @Test
    @DisplayName("2. Incompatible field rejected: Tablet packaging field injected into INHALER payload")
    public void testIncompatibleFieldRejected() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("brand_name", "Seretide");
        attrs.put("generic_name", "Salmeterol");
        attrs.put("dar_number", "002-334-556");
        attrs.put("cost_unit", "400.00");
        attrs.put("mrp_unit", "500.00");
        attrs.put("dose_per_actuation", "125mcg");
        attrs.put("actuations_per_device", 120);
        // INCOMPATIBLE field: pieces_per_strip is only valid for TABLET_CAPSULE
        attrs.put("pieces_per_strip", 10);

        PosFlexibleMedicinePayloadClient.PayloadValidationReport report =
                client.validatePayload("2026.01", "INHALER", attrs);

        assertFalse(report.isValid);
        assertTrue(report.errors.stream().anyMatch(e ->
                "pieces_per_strip".equals(e.getField()) && "ERR_INCOMPATIBLE_FIELD".equals(e.getErrorCode())
        ));
    }

    @Test
    @DisplayName("3. Unknown field rejected: Arbitrary unpermitted key rejected with ERR_UNKNOWN_FIELD")
    public void testUnknownFieldRejected() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("brand_name", "Napa 500");
        attrs.put("generic_name", "Paracetamol");
        attrs.put("dar_number", "001-001-001");
        attrs.put("cost_unit", "1.00");
        attrs.put("mrp_unit", "1.50");
        // UNKNOWN field
        attrs.put("arbitrary_injected_parameter", "hack_attempt");

        PosFlexibleMedicinePayloadClient.PayloadValidationReport report =
                client.validatePayload("2026.01", "TABLET_CAPSULE", attrs);

        assertFalse(report.isValid);
        assertTrue(report.errors.stream().anyMatch(e ->
                "arbitrary_injected_parameter".equals(e.getField()) && "ERR_UNKNOWN_FIELD".equals(e.getErrorCode())
        ));
    }

    @Test
    @DisplayName("4. Unsupported schema version rejected")
    public void testUnsupportedSchemaVersionRejected() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("brand_name", "Napa 500");

        PosFlexibleMedicinePayloadClient.PayloadValidationReport report =
                client.validatePayload("v0.1-alpha", "TABLET_CAPSULE", attrs);

        assertFalse(report.isValid);
        assertTrue(report.errors.stream().anyMatch(e ->
                "schema_version".equals(e.getField()) && "ERR_PAYLOAD_VERSION_UNSUPPORTED".equals(e.getErrorCode())
        ));
    }

    @Test
    @DisplayName("5. Mandatory common attributes missing detected")
    public void testMissingMandatoryCommonAttributes() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("brand_name", "Azithromycin 500");
        // Missing generic_name, dar_number, cost_unit, mrp_unit

        PosFlexibleMedicinePayloadClient.PayloadValidationReport report =
                client.validatePayload("2026.01", "TABLET_CAPSULE", attrs);

        assertFalse(report.isValid);
        assertTrue(report.errors.stream().anyMatch(e -> "generic_name".equals(e.getField())));
        assertTrue(report.errors.stream().anyMatch(e -> "dar_number".equals(e.getField())));
        assertTrue(report.errors.stream().anyMatch(e -> "cost_unit".equals(e.getField())));
        assertTrue(report.errors.stream().anyMatch(e -> "mrp_unit".equals(e.getField())));
    }

    @Test
    @DisplayName("6. Mandatory formulation-specific attributes missing detected")
    public void testMissingMandatoryFormSpecificAttributes() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("brand_name", "Ciprofloxacin Drops");
        attrs.put("generic_name", "Ciprofloxacin");
        attrs.put("dar_number", "003-445-667");
        attrs.put("cost_unit", "40.00");
        attrs.put("mrp_unit", "60.00");
        // Missing volume_ml and drop_category for DROPS

        PosFlexibleMedicinePayloadClient.PayloadValidationReport report =
                client.validatePayload("2026.01", "DROPS", attrs);

        assertFalse(report.isValid);
        assertTrue(report.errors.stream().anyMatch(e -> "volume_ml".equals(e.getField())));
        assertTrue(report.errors.stream().anyMatch(e -> "drop_category".equals(e.getField())));
    }

    @Test
    @DisplayName("7. Pricing rules validation: negative cost, zero MRP, MRP below cost")
    public void testPricingRules() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("brand_name", "Test Item");
        attrs.put("generic_name", "Test");
        attrs.put("dar_number", "001-001");
        attrs.put("cost_unit", "50.00");
        attrs.put("mrp_unit", "40.00"); // MRP < Cost

        PosFlexibleMedicinePayloadClient.PayloadValidationReport report =
                client.validatePayload("2026.01", "TABLET_CAPSULE", attrs);

        assertFalse(report.isValid);
        assertTrue(report.errors.stream().anyMatch(e -> "ERR_MRP_BELOW_COST".equals(e.getErrorCode())));
    }

    @Test
    @DisplayName("8. Role-Based Access Control: Cashier denied mutation")
    public void testRbacCashierDenied() {
        assertFalse(client.canMutate("CASHIER"));
        assertFalse(client.canMutate("cashier"));
        assertFalse(client.canMutate(null));
        assertTrue(client.canMutate("STAFF_PHARMACIST"));
        assertTrue(client.canMutate("BRANCH_MANAGER"));
        assertTrue(client.canMutate("OWNER"));
    }
}

