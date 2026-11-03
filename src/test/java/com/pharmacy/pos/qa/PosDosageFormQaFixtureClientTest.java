package com.pharmacy.pos.qa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosDosageFormQaFixtureClientTest
 * Architecture Decision: ADR-0160 (P160).
 * Phase 08: Specialized Item & Multi-Tier Packaging Formulation Editors.
 *
 * Automated verification suite for dosage form QA fixture execution harness.
 * Tests positive fixtures across all pharmaceutical dosage families and
 * verifies rejection behavior on negative regression fixtures.
 */
public class PosDosageFormQaFixtureClientTest {

    private PosDosageFormQaFixtureClient client;

    @BeforeEach
    public void setUp() {
        client = new PosDosageFormQaFixtureClient();
    }

    private Map<String, Object> createBaseAttributes(String brand, String generic, String dar, String cost, String mrp) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("brand_name", brand);
        attrs.put("generic_name", generic);
        attrs.put("dar_number", dar);
        attrs.put("cost_unit", cost);
        attrs.put("mrp_unit", mrp);
        attrs.put("vat_percentage", "2.5");
        attrs.put("is_prescription_only", false);
        attrs.put("is_antibiotic", false);
        attrs.put("is_narcotic", false);
        attrs.put("requires_cold_chain", false);
        attrs.put("storage_condition", "ROOM_TEMPERATURE");
        return attrs;
    }

    @Test
    @DisplayName("1. Positive: TABLET_CAPSULE packaging fixture passes validation")
    public void testPositiveTabletCapsuleFixture() {
        Map<String, Object> attrs = createBaseAttributes("Napa Extra", "Paracetamol + Caffeine", "012-345-678", "2.10", "3.00");
        attrs.put("pieces_per_strip", 10);
        attrs.put("strips_per_pack", 10);
        attrs.put("total_pieces_per_pack", 100);
        attrs.put("capsule_shell_type", "FILM_COATED_TABLET");
        attrs.put("is_delayed_release", false);

        PosDosageFormQaFixtureClient.FixtureExecutionResult result =
                client.runPositiveFixture("POS-001-TABLET-CAPSULE", "2026.01", "TABLET_CAPSULE", attrs);

        assertTrue(result.passed);
        assertEquals(0, result.report.errorCount);
    }

    @Test
    @DisplayName("2. Positive: SYRUP_SUSPENSION liquid fixture passes validation")
    public void testPositiveSyrupLiquidFixture() {
        Map<String, Object> attrs = createBaseAttributes("Tofen Syrup", "Ketotifen", "015-889-112", "55.00", "70.00");
        attrs.put("bottle_size_ml", 100);
        attrs.put("is_dry_powder", false);
        attrs.put("reconstituted_expiry_days", 30);

        PosDosageFormQaFixtureClient.FixtureExecutionResult result =
                client.runPositiveFixture("POS-002-SYRUP-LIQUID", "2026.01", "SYRUP_SUSPENSION", attrs);

        assertTrue(result.passed);
        assertEquals(0, result.report.errorCount);
    }

    @Test
    @DisplayName("3. Positive: INJECTION_INFUSION vial fixture passes validation")
    public void testPositiveInjectionVialFixture() {
        Map<String, Object> attrs = createBaseAttributes("Rocephin 1g IV", "Ceftriaxone", "018-990-234", "150.00", "190.00");
        attrs.put("is_prescription_only", true);
        attrs.put("is_antibiotic", true);
        attrs.put("vial_volume_ml", 10);
        attrs.put("is_lyophilized", true);
        attrs.put("diluent_required", true);

        PosDosageFormQaFixtureClient.FixtureExecutionResult result =
                client.runPositiveFixture("POS-004-INJECTION-VIAL", "2026.01", "INJECTION_INFUSION", attrs);

        assertTrue(result.passed);
        assertEquals(0, result.report.errorCount);
    }

    @Test
    @DisplayName("4. Positive: INHALER respiratory fixture passes validation")
    public void testPositiveInhalerFixture() {
        Map<String, Object> attrs = createBaseAttributes("Ventolin Evohaler", "Salbutamol", "033-112-998", "180.00", "225.00");
        attrs.put("dose_per_actuation", "100mcg");
        attrs.put("actuations_per_device", 200);
        attrs.put("canister_type", "ALUMINIUM_MDI");

        PosDosageFormQaFixtureClient.FixtureExecutionResult result =
                client.runPositiveFixture("POS-006-INHALER-RESPIRATORY", "2026.01", "INHALER", attrs);

        assertTrue(result.passed);
        assertEquals(0, result.report.errorCount);
    }

    @Test
    @DisplayName("5. Positive: TOPICAL_CREAM dermatological fixture passes validation")
    public void testPositiveTopicalDermatologicalFixture() {
        Map<String, Object> attrs = createBaseAttributes("Dermovate Cream", "Clobetasol Propionate", "044-556-778", "85.00", "110.00");
        attrs.put("tube_size_grams", 20.0);
        attrs.put("application_site", "SKIN_LESIONS");
        attrs.put("vehicle_base", "CREAM_WATER_WASHABLE");

        PosDosageFormQaFixtureClient.FixtureExecutionResult result =
                client.runPositiveFixture("POS-007-TOPICAL-DERMATOLOGICAL", "2026.01", "TOPICAL_CREAM", attrs);

        assertTrue(result.passed);
        assertEquals(0, result.report.errorCount);
    }

    @Test
    @DisplayName("6. Positive: DROPS ophthalmic fixture passes validation")
    public void testPositiveDropsFixture() {
        Map<String, Object> attrs = createBaseAttributes("Tobrex Ophthalmic Solution", "Tobramycin", "055-667-889", "120.00", "150.00");
        attrs.put("volume_ml", 5.0);
        attrs.put("drop_category", "EYE_DROPS");
        attrs.put("is_preservative_free", false);

        PosDosageFormQaFixtureClient.FixtureExecutionResult result =
                client.runPositiveFixture("POS-008-DROPS-OPHTHALMIC", "2026.01", "DROPS", attrs);

        assertTrue(result.passed);
        assertEquals(0, result.report.errorCount);
    }

    @Test
    @DisplayName("7. Positive: SACHET_POWDER rehydration fixture passes validation")
    public void testPositiveSachetPowderFixture() {
        Map<String, Object> attrs = createBaseAttributes("Oral Rehydration Salts (ORS)", "Electrolyte Formula", "066-778-990", "5.00", "6.50");
        attrs.put("grams_per_sachet", 10.2);
        attrs.put("sachets_per_pack", 25);
        attrs.put("reconstitution_guidance", "Dissolve in 500ml fresh drinking water");

        PosDosageFormQaFixtureClient.FixtureExecutionResult result =
                client.runPositiveFixture("POS-009-SACHET-POWDER", "2026.01", "SACHET_POWDER", attrs);

        assertTrue(result.passed);
        assertEquals(0, result.report.errorCount);
    }

    @Test
    @DisplayName("8. Positive: SUPPOSITORY fixture passes validation")
    public void testPositiveSuppositoryFixture() {
        Map<String, Object> attrs = createBaseAttributes("Napa 125 Suppository", "Paracetamol", "077-889-001", "8.00", "10.00");
        attrs.put("piece_count", 5);
        attrs.put("administration_route", "RECTAL");
        attrs.put("suppository_base_type", "WITEPSOL");

        PosDosageFormQaFixtureClient.FixtureExecutionResult result =
                client.runPositiveFixture("POS-010-SUPPOSITORY-PESSARY", "2026.01", "SUPPOSITORY", attrs);

        assertTrue(result.passed);
        assertEquals(0, result.report.errorCount);
    }

    @Test
    @DisplayName("9. Positive: DEVICE_CONSUMABLE fixture passes validation")
    public void testPositiveDeviceConsumableFixture() {
        Map<String, Object> attrs = createBaseAttributes("Dispovan 5ml Syringe", "Medical Disposable Syringe", "088-990-112", "6.00", "8.00");
        attrs.put("device_type", "DISPOSABLE_SYRINGE_5ML");
        attrs.put("is_sterile", true);
        attrs.put("gauge_size", "22G_X_1_25");

        PosDosageFormQaFixtureClient.FixtureExecutionResult result =
                client.runPositiveFixture("POS-011-DEVICE-CONSUMABLE", "2026.01", "DEVICE_CONSUMABLE", attrs);

        assertTrue(result.passed);
        assertEquals(0, result.report.errorCount);
    }

    @Test
    @DisplayName("10. Negative: Negative cost is rejected with ERR_NEGATIVE_COST")
    public void testNegativeFixtureNegativeCost() {
        Map<String, Object> attrs = createBaseAttributes("Faulty Tablet", "Sample", "999-001-001", "-10.00", "15.00");
        attrs.put("pieces_per_strip", 10);
        attrs.put("strips_per_pack", 10);
        attrs.put("total_pieces_per_pack", 100);

        PosDosageFormQaFixtureClient.FixtureExecutionResult result =
                client.runNegativeFixture("NEG-001-NEGATIVE-COST", "ERR_NEGATIVE_COST", "2026.01", "TABLET_CAPSULE", attrs);

        assertTrue(result.passed);
        assertFalse(result.report.isValid);
    }

    @Test
    @DisplayName("11. Negative: MRP below cost is rejected with ERR_MRP_BELOW_COST")
    public void testNegativeFixtureMrpBelowCost() {
        Map<String, Object> attrs = createBaseAttributes("Loss Leader", "Sample", "999-003-003", "100.00", "80.00");
        attrs.put("pieces_per_strip", 10);
        attrs.put("strips_per_pack", 10);
        attrs.put("total_pieces_per_pack", 100);

        PosDosageFormQaFixtureClient.FixtureExecutionResult result =
                client.runNegativeFixture("NEG-003-MRP-BELOW-COST", "ERR_MRP_BELOW_COST", "2026.01", "TABLET_CAPSULE", attrs);

        assertTrue(result.passed);
        assertFalse(result.report.isValid);
    }

    @Test
    @DisplayName("12. Negative: Temperature range inversion is rejected with ERR_INVALID_TEMPERATURE_RANGE")
    public void testNegativeFixtureTemperatureInversion() {
        Map<String, Object> attrs = createBaseAttributes("Cold Inverted", "Sample", "999-005-005", "100.00", "120.00");
        attrs.put("pieces_per_strip", 10);
        attrs.put("strips_per_pack", 10);
        attrs.put("total_pieces_per_pack", 100);
        attrs.put("storage_temperature_celsius_min", 25.0);
        attrs.put("storage_temperature_celsius_max", 15.0); // Min > Max

        PosDosageFormQaFixtureClient.FixtureExecutionResult result =
                client.runNegativeFixture("NEG-005-TEMP-INVERSION", "ERR_INVALID_TEMPERATURE_RANGE", "2026.01", "TABLET_CAPSULE", attrs);

        assertTrue(result.passed);
        assertFalse(result.report.isValid);
    }

    @Test
    @DisplayName("13. Negative: Incompatible form parameter is rejected with ERR_INCOMPATIBLE_FIELD")
    public void testNegativeFixtureIncompatibleField() {
        Map<String, Object> attrs = createBaseAttributes("Mismatched Inhaler", "Salbutamol", "999-006-006", "180.00", "225.00");
        attrs.put("dose_per_actuation", "100mcg");
        attrs.put("actuations_per_device", 200);
        // INCOMPATIBLE: pieces_per_strip belongs to TABLET_CAPSULE
        attrs.put("pieces_per_strip", 10);

        PosDosageFormQaFixtureClient.FixtureExecutionResult result =
                client.runNegativeFixture("NEG-006-INCOMPATIBLE-FIELD", "ERR_INCOMPATIBLE_FIELD", "2026.01", "INHALER", attrs);

        assertTrue(result.passed);
        assertFalse(result.report.isValid);
    }

    @Test
    @DisplayName("14. Negative: Unknown schema field is rejected with ERR_UNKNOWN_FIELD")
    public void testNegativeFixtureUnknownField() {
        Map<String, Object> attrs = createBaseAttributes("Random Drug", "Sample", "999-007-007", "50.00", "65.00");
        attrs.put("pieces_per_strip", 10);
        attrs.put("strips_per_pack", 10);
        attrs.put("total_pieces_per_pack", 100);
        // UNKNOWN: unmapped arbitrary attribute
        attrs.put("hologram_serial_uuid", "HOLO-999-123");

        PosDosageFormQaFixtureClient.FixtureExecutionResult result =
                client.runNegativeFixture("NEG-007-UNKNOWN-FIELD", "ERR_UNKNOWN_FIELD", "2026.01", "TABLET_CAPSULE", attrs);

        assertTrue(result.passed);
        assertFalse(result.report.isValid);
    }

    @Test
    @DisplayName("15. Role authorization check: Cashier blocked from seeding catalog")
    public void testRoleAuthorizationForCatalogSeeding() {
        assertTrue(client.canSeedCatalog("OWNER"));
        assertTrue(client.canSeedCatalog("MANAGER"));
        assertTrue(client.canSeedCatalog("PHARMACIST"));
        assertFalse(client.canSeedCatalog("CASHIER"));
        assertFalse(client.canSeedCatalog(null));
        assertFalse(client.canSeedCatalog(""));
    }
}

