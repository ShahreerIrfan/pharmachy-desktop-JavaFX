package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStrengthTaxonomyClientTest
 * Architecture Decision: ADR-0123 (P123).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 */
class PosStrengthTaxonomyClientTest {

    private PosStrengthTaxonomyClient client;

    @BeforeEach
    void setUp() {
        client = new PosStrengthTaxonomyClient();
    }

    private void assertBigDecimalEquals(BigDecimal expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, expected.compareTo(actual), "Expected " + expected + " but got " + actual);
    }

    @Test
    @DisplayName("P123-D1: Valid intra-family conversion for MASS (g to mg, mg to g)")
    void testMassUnitConversion() {
        PosStrengthTaxonomyClient.ConversionResult result = client.convertStrength(
                new BigDecimal("1"), "g", "mg"
        );
        assertEquals(PosStrengthTaxonomyClient.UiStatus.SUCCESS, result.uiStatus);
        assertTrue(result.isCompatible);
        assertBigDecimalEquals(new BigDecimal("1000"), result.convertedValue);
        assertEquals("1000 mg", result.formattedResult);

        // Convert back
        PosStrengthTaxonomyClient.ConversionResult back = client.convertStrength(
                new BigDecimal("500"), "mg", "g"
        );
        assertEquals(PosStrengthTaxonomyClient.UiStatus.SUCCESS, back.uiStatus);
        assertBigDecimalEquals(new BigDecimal("0.5"), back.convertedValue);
    }

    @Test
    @DisplayName("P123-D2: Valid intra-family conversion for VOLUME (L to mL)")
    void testVolumeUnitConversion() {
        PosStrengthTaxonomyClient.ConversionResult result = client.convertStrength(
                new BigDecimal("2.5"), "L", "ml"
        );
        assertEquals(PosStrengthTaxonomyClient.UiStatus.SUCCESS, result.uiStatus);
        assertTrue(result.isCompatible);
        assertBigDecimalEquals(new BigDecimal("2500"), result.convertedValue);
        assertEquals("2500 ml", result.formattedResult);
    }

    @Test
    @DisplayName("P123-D3: Guard strictly prevents cross-family conversion (MASS to VOLUME: mg to mL)")
    void testCrossFamilyConversionPrevented() {
        PosStrengthTaxonomyClient.ConversionResult result = client.convertStrength(
                new BigDecimal("500"), "mg", "ml"
        );
        assertEquals(PosStrengthTaxonomyClient.UiStatus.ERROR, result.uiStatus);
        assertFalse(result.isCompatible);
        assertEquals("ERR_INCOMPATIBLE_UNIT_CONVERSION", result.errorCode);
        assertTrue(result.messageEn.contains("strictly forbidden"));
        assertTrue(result.messageBn.contains("নিষিদ্ধ"));
    }

    @Test
    @DisplayName("P123-D4: Guard strictly prevents cross-family conversion (MASS to PERCENTAGE: mg to %)")
    void testMassToPercentagePrevented() {
        PosStrengthTaxonomyClient.ConversionResult result = client.convertStrength(
                new BigDecimal("100"), "mg", "%"
        );
        assertEquals(PosStrengthTaxonomyClient.UiStatus.ERROR, result.uiStatus);
        assertEquals("ERR_INCOMPATIBLE_UNIT_CONVERSION", result.errorCode);
    }

    @Test
    @DisplayName("P123-D5: Parses standard strength strings (e.g. '500 mg', '250mg/5mL', '0.5%')")
    void testParseValidStrengthStrings() {
        PosStrengthTaxonomyClient.ParsedStrength p1 = client.parseStrengthString("500 mg");
        assertBigDecimalEquals(new BigDecimal("500"), p1.numericValue);
        assertEquals("mg", p1.unit);
        assertEquals(PosStrengthTaxonomyClient.MeasurementFamily.MASS, p1.family);
        assertFalse(p1.isComposite);

        PosStrengthTaxonomyClient.ParsedStrength p2 = client.parseStrengthString("250mg/5ml");
        assertBigDecimalEquals(new BigDecimal("250"), p2.numericValue);
        assertEquals("mg/5ml", p2.unit);
        assertEquals(PosStrengthTaxonomyClient.MeasurementFamily.CONCENTRATION, p2.family);

        PosStrengthTaxonomyClient.ParsedStrength p3 = client.parseStrengthString("0.5%");
        assertBigDecimalEquals(new BigDecimal("0.5"), p3.numericValue);
        assertEquals("%", p3.unit);
        assertEquals(PosStrengthTaxonomyClient.MeasurementFamily.PERCENTAGE, p3.family);
    }

    @Test
    @DisplayName("P123-D6: Composite strength strings parsed with composite flag (e.g. '500mg+65mg')")
    void testParseCompositeStrengthString() {
        PosStrengthTaxonomyClient.ParsedStrength comp = client.parseStrengthString("500mg+65mg");
        assertBigDecimalEquals(new BigDecimal("500"), comp.numericValue);
        assertEquals("mg", comp.unit);
        assertTrue(comp.isComposite);
    }

    @Test
    @DisplayName("P123-D7: Unknown unit rejected with ERR_UNKNOWN_STRENGTH_UNIT")
    void testUnknownUnitRejected() {
        PosStrengthTaxonomyClient.ConversionResult result = client.convertStrength(
                new BigDecimal("10"), "invalid_unit", "mg"
        );
        assertEquals(PosStrengthTaxonomyClient.UiStatus.ERROR, result.uiStatus);
        assertEquals("ERR_UNKNOWN_STRENGTH_UNIT", result.errorCode);
    }
}
