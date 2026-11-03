package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosInventoryBaseUnitClientTest
 * Architecture Decision: ADR-0161 (P161).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Unit tests for JavaFX Inventory Base-Unit client engine.
 */
public class PosInventoryBaseUnitClientTest {

    private PosInventoryBaseUnitClient client;
    private PosInventoryBaseUnitClient.StrategyConfig tabletConfig;
    private PosInventoryBaseUnitClient.StrategyConfig vialConfig;

    @BeforeEach
    public void setUp() {
        client = new PosInventoryBaseUnitClient();

        // Tablet Config: 10 strips per pack, 10 tablets per strip = 100 tablets per pack
        tabletConfig = new PosInventoryBaseUnitClient.StrategyConfig(
                "ibus-tablet-01",
                "prod-napa-500",
                "TABLET",
                true, // indivisible
                false,
                0,
                BigDecimal.ONE,
                "BOX",
                "STRIP",
                10,
                10,
                100,
                new BigDecimal("0.8000"), // 0.80 cost per tablet
                new BigDecimal("1.2000")  // 1.20 mrp per tablet
        );

        // Vial Config: 5 vials per pack, indivisible
        vialConfig = new PosInventoryBaseUnitClient.StrategyConfig(
                "ibus-vial-01",
                "prod-rocephin-1g",
                "VIAL",
                true,
                false,
                0,
                BigDecimal.ONE,
                "BOX",
                "TRAY",
                1,
                5,
                5,
                new BigDecimal("150.0000"),
                new BigDecimal("190.0000")
        );
    }

    @Test
    @DisplayName("1. Valid scenario: Pack to base conversion conserves stock and computes exact Decimal valuation")
    public void testPackToBaseConversion() {
        // 2 boxes of Napa Extra = 2 * 100 = 200 tablets
        PosInventoryBaseUnitClient.ConversionResult result =
                client.convertToBaseUnits(new BigDecimal("2"), "PACK", tabletConfig);

        assertTrue(result.success);
        assertEquals(0, new BigDecimal("200").compareTo(result.baseUnitQuantity));
        assertEquals(0, new BigDecimal("160.0000").compareTo(result.costValuationBdt)); // 200 * 0.80
        assertEquals(0, new BigDecimal("240.0000").compareTo(result.mrpValuationBdt));  // 200 * 1.20
    }

    @Test
    @DisplayName("2. Valid scenario: Intermediate strip to base conversion")
    public void testIntermediateToBaseConversion() {
        // 3 strips = 3 * 10 = 30 tablets
        PosInventoryBaseUnitClient.ConversionResult result =
                client.convertToBaseUnits(new BigDecimal("3"), "INTERMEDIATE", tabletConfig);

        assertTrue(result.success);
        assertEquals(0, new BigDecimal("30").compareTo(result.baseUnitQuantity));
        assertEquals(0, new BigDecimal("24.0000").compareTo(result.costValuationBdt));
        assertEquals(0, new BigDecimal("36.0000").compareTo(result.mrpValuationBdt));
    }

    @Test
    @DisplayName("3. Invalid scenario: Fractional conversion on indivisible vial is strictly rejected")
    public void testFractionalConversionRejectedForIndivisibleVial() {
        // 0.5 box of vials = 0.5 * 5 = 2.5 vials -> fractional piece on indivisible item
        PosInventoryBaseUnitClient.ConversionResult result =
                client.convertToBaseUnits(new BigDecimal("0.5"), "PACK", vialConfig);

        assertFalse(result.success);
        assertEquals("ERR_INDIVISIBLE_UNIT_FRACTION", result.errorCode);
        assertNotNull(result.errorMessageBn);
    }

    @Test
    @DisplayName("4. Reversal / Unpacking: 125 base tablets decomposes into 1 box, 2 strips, 5 loose tablets")
    public void testDecomposeBaseUnitsReversal() {
        PosInventoryBaseUnitClient.DecomposeResult result =
                client.decomposeBaseUnits(125, tabletConfig);

        assertEquals(125, result.totalBaseUnits);
        assertEquals(1, result.packs); // 1 box (100)
        assertEquals(2, result.intermediate); // 2 strips (20)
        assertEquals(5, result.looseBase); // 5 loose tablets
        assertEquals("TABLET", result.baseUnit);
    }

    @Test
    @DisplayName("5. Dispense quantity validation: Below minimum quantity is rejected")
    public void testValidateQuantityBelowMinimum() {
        List<String> errors = client.validateDispenseQuantity(new BigDecimal("0.5"), tabletConfig);
        assertTrue(errors.contains("ERR_BELOW_MINIMUM_DISPENSE_QTY"));
    }

    @Test
    @DisplayName("6. Dispense quantity validation: Indivisible fractional piece is rejected")
    public void testValidateQuantityIndivisibleFraction() {
        List<String> errors = client.validateDispenseQuantity(new BigDecimal("2.5"), vialConfig);
        assertTrue(errors.contains("ERR_INDIVISIBLE_UNIT_FRACTION"));
    }

    @Test
    @DisplayName("7. Role authorization: Cashier role blocked from configuring base unit strategies")
    public void testRoleAuthorization() {
        assertTrue(client.canConfigureStrategy("OWNER"));
        assertTrue(client.canConfigureStrategy("MANAGER"));
        assertTrue(client.canConfigureStrategy("PHARMACIST"));
        assertFalse(client.canConfigureStrategy("CASHIER"));
        assertFalse(client.canConfigureStrategy(null));
    }
}

