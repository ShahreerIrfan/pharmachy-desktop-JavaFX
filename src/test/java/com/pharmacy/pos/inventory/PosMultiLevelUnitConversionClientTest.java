package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosMultiLevelUnitConversionClientTest
 * Architecture Decision: ADR-0163 (P163).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Unit test suite for multi-level packaging conversion, fractional tablet prevention,
 * and dosing-unit guardrails in JavaFX POS.
 */
public class PosMultiLevelUnitConversionClientTest {

    private PosMultiLevelUnitConversionClient client;
    private PosMultiLevelUnitConversionClient.ConversionPolicy unsplitTabletPolicy;
    private PosMultiLevelUnitConversionClient.ConversionPolicy splittableTabletPolicy;

    @BeforeEach
    public void setUp() {
        client = new PosMultiLevelUnitConversionClient();

        // Napa Extra: Unsplit film-coated tablet
        unsplitTabletPolicy = new PosMultiLevelUnitConversionClient.ConversionPolicy(
                "ucp-01",
                "prod-napa-500",
                false, // NOT splittable
                Collections.singletonList(1.0),
                "MG",
                BigDecimal.valueOf(500),
                Arrays.asList("ML", "L", "PUFF", "ACTUATION", "DROP", "VIAL", "AMPOULE")
        );

        // Bisoprolol 5mg: Scored half-tablet splittable
        splittableTabletPolicy = new PosMultiLevelUnitConversionClient.ConversionPolicy(
                "ucp-02",
                "prod-biso-5",
                true, // Splittable
                Arrays.asList(0.5, 1.0),
                "MG",
                BigDecimal.valueOf(5),
                Arrays.asList("ML", "L", "PUFF", "ACTUATION", "DROP", "VIAL")
        );
    }

    @Test
    @DisplayName("1. Valid scenario: Multi-tier packaging conversion calculates exact stock and Decimal valuation")
    public void testValidPackagingConversion() {
        // 2 Boxes -> 200 Tablets (100 per box)
        PosMultiLevelUnitConversionClient.ConversionResult res = client.convertMultiLevel(
                new BigDecimal("2"),
                "BOX",
                "TABLET",
                100,
                "Tablet",
                unsplitTabletPolicy,
                new BigDecimal("0.8000"),
                new BigDecimal("1.2000")
        );

        assertTrue(res.success);
        assertEquals(0, new BigDecimal("200").compareTo(res.convertedQuantity));
        assertEquals(0, new BigDecimal("160.0000").compareTo(res.costValuationBdt));
        assertEquals(0, new BigDecimal("240.0000").compareTo(res.mrpValuationBdt));
    }

    @Test
    @DisplayName("2. Guardrail: Fractional tablet is strictly rejected on unsplit/film-coated tablet")
    public void testFractionalTabletBlockedOnUnsplitTablet() {
        PosMultiLevelUnitConversionClient.ValidationReport report =
                client.validateDispense("TABLET", new BigDecimal("1.5"), "Tablet", unsplitTabletPolicy);

        assertFalse(report.isValid);
        assertEquals("ERR_FRACTIONAL_TABLET_NOT_ALLOWED", report.errorCode);
        assertNotNull(report.errorMessageBn);
    }

    @Test
    @DisplayName("3. Splittable tablet allows authorized half-tablet split (0.5 and 1.5)")
    public void testSplittableTabletAllowedHalf() {
        PosMultiLevelUnitConversionClient.ValidationReport reportHalf =
                client.validateDispense("TABLET", new BigDecimal("0.5"), "Tablet", splittableTabletPolicy);
        assertTrue(reportHalf.isValid);

        PosMultiLevelUnitConversionClient.ValidationReport reportOneHalf =
                client.validateDispense("TABLET", new BigDecimal("2.5"), "Tablet", splittableTabletPolicy);
        assertTrue(reportOneHalf.isValid);
    }

    @Test
    @DisplayName("4. Guardrail: Arbitrary fractional split (0.25 or 1.33) is rejected on half-scored tablet")
    public void testInvalidSplitFractionRejected() {
        PosMultiLevelUnitConversionClient.ValidationReport report =
                client.validateDispense("TABLET", new BigDecimal("0.25"), "Tablet", splittableTabletPolicy);

        assertFalse(report.isValid);
        assertEquals("ERR_INVALID_TABLET_SPLIT", report.errorCode);
    }

    @Test
    @DisplayName("5. Guardrail: Inappropriate dosing-unit conversion (Tablet to ML or PUFF) is strictly blocked")
    public void testIncompatibleDosingUnitBlocked() {
        PosMultiLevelUnitConversionClient.ValidationReport mlReport =
                client.validateDispense("ML", new BigDecimal("10"), "Tablet", unsplitTabletPolicy);

        assertFalse(mlReport.isValid);
        assertEquals("ERR_INCOMPATIBLE_DOSING_UNIT", mlReport.errorCode);

        PosMultiLevelUnitConversionClient.ValidationReport puffReport =
                client.validateDispense("PUFF", new BigDecimal("2"), "Tablet", unsplitTabletPolicy);

        assertFalse(puffReport.isValid);
        assertEquals("ERR_INCOMPATIBLE_DOSING_UNIT", puffReport.errorCode);
    }

    @Test
    @DisplayName("6. Clinical calculation: Tablets to active substance MG calculates potency without stock drift")
    public void testClinicalDosingPotencyCalculation() {
        // 2 Napa Extra tablets to MG -> 2 * 500 = 1,000 MG
        PosMultiLevelUnitConversionClient.ConversionResult res = client.convertMultiLevel(
                new BigDecimal("2"),
                "TABLET",
                "MG",
                1,
                "Tablet",
                unsplitTabletPolicy,
                new BigDecimal("0.8000"),
                new BigDecimal("1.2000")
        );

        assertTrue(res.success);
        assertTrue(res.isClinicalDosingCalculation);
        assertEquals(0, new BigDecimal("1000").compareTo(res.convertedQuantity));
    }

    @Test
    @DisplayName("7. Guardrail: Indivisible unit fraction (0.5 Vial) is strictly rejected")
    public void testIndivisibleUnitFractionBlocked() {
        PosMultiLevelUnitConversionClient.ValidationReport report =
                client.validateDispense("VIAL", new BigDecimal("0.5"), "Injection", null);

        assertFalse(report.isValid);
        assertEquals("ERR_INDIVISIBLE_UNIT_FRACTION", report.errorCode);
    }

    @Test
    @DisplayName("8. Role authorization: Cashier blocked from configuring unit conversion policy")
    public void testRoleAuthorization() {
        assertTrue(client.canConfigurePolicy("OWNER"));
        assertTrue(client.canConfigurePolicy("MANAGER"));
        assertTrue(client.canConfigurePolicy("PHARMACIST"));
        assertFalse(client.canConfigurePolicy("CASHIER"));
        assertFalse(client.canConfigurePolicy(null));
    }
}

