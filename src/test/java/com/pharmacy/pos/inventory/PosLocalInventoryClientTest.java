package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosLocalInventoryClientTest
 * Architecture Decision: ADR-0156 (P156).
 * Local Inventory-Specific Fields Test Suite.
 */
public class PosLocalInventoryClientTest {

    private PosLocalInventoryClient client;

    @BeforeEach
    public void setUp() {
        client = new PosLocalInventoryClient();
    }

    @Test
    @DisplayName("P156-DESK-01: Valid local inventory parameters succeed with custom selling price")
    public void testValidateLocalInventorySuccess() {
        PosLocalInventoryClient.InventoryValidationResult result = client.validateLocalInventory(
                "SKU-DHK-01",
                new BigDecimal("2.45"),
                new BigDecimal("2.50"),
                15,
                5,
                500,
                "Rack-A2 / Shelf-3"
        );

        assertTrue(result.isValid);
        assertNull(result.errorCode);
        assertEquals(new BigDecimal("2.45"), result.effectiveSellingPrice);
        assertTrue(result.isPriceOverridden);
    }

    @Test
    @DisplayName("P156-DESK-02: Missing local price cleanly falls back to master MRP")
    public void testValidateLocalInventoryFallbackMasterPrice() {
        PosLocalInventoryClient.InventoryValidationResult result = client.validateLocalInventory(
                "SKU-DHK-02",
                null,
                new BigDecimal("2.50"),
                10,
                1,
                null,
                "Rack-B1"
        );

        assertTrue(result.isValid);
        assertEquals(new BigDecimal("2.50"), result.effectiveSellingPrice);
        assertFalse(result.isPriceOverridden);
    }

    @Test
    @DisplayName("P156-DESK-03: Invalid local SKU characters trigger ERR_INVALID_SKU_FORMAT")
    public void testInvalidSkuFormat() {
        PosLocalInventoryClient.InventoryValidationResult result = client.validateLocalInventory(
                "SKU#BAD@CHARS!",
                new BigDecimal("2.45"),
                new BigDecimal("2.50"),
                10,
                1,
                null,
                null
        );

        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_SKU_FORMAT", result.errorCode);
    }

    @Test
    @DisplayName("P156-DESK-04: Selling price below 0.01 triggers ERR_INVALID_SELLING_PRICE")
    public void testInvalidSellingPrice() {
        PosLocalInventoryClient.InventoryValidationResult result = client.validateLocalInventory(
                "SKU-01",
                new BigDecimal("0.00"),
                new BigDecimal("2.50"),
                10,
                1,
                null,
                null
        );

        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_SELLING_PRICE", result.errorCode);
    }

    @Test
    @DisplayName("P156-DESK-05: Negative reorder level triggers ERR_INVALID_REORDER_LEVEL")
    public void testNegativeReorderLevel() {
        PosLocalInventoryClient.InventoryValidationResult result = client.validateLocalInventory(
                "SKU-01",
                null,
                new BigDecimal("2.50"),
                -5,
                1,
                null,
                null
        );

        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_REORDER_LEVEL", result.errorCode);
    }

    @Test
    @DisplayName("P156-DESK-06: Max capacity less than min order quantity triggers ERR_CAPACITY_BELOW_MIN_ORDER")
    public void testCapacityBelowMinOrder() {
        PosLocalInventoryClient.InventoryValidationResult result = client.validateLocalInventory(
                "SKU-01",
                null,
                new BigDecimal("2.50"),
                10,
                50,
                20,
                null
        );

        assertFalse(result.isValid);
        assertEquals("ERR_CAPACITY_BELOW_MIN_ORDER", result.errorCode);
    }

    @Test
    @DisplayName("P156-DESK-07: Low stock detection correctly compares current stock and reorder level")
    public void testLowStockDetection() {
        assertTrue(client.isLowStock(10, 15));
        assertTrue(client.isLowStock(15, 15));
        assertFalse(client.isLowStock(20, 15));
    }

    @Test
    @DisplayName("P156-DESK-08: Cashier is restricted from mutating local inventory configurations")
    public void testCanMutateInventoryPermissions() {
        assertTrue(client.canMutateInventory("STAFF_PHARMACIST"));
        assertTrue(client.canMutateInventory("PHARMACY_OWNER"));
        assertTrue(client.canMutateInventory("SUPERADMIN"));
        assertFalse(client.canMutateInventory("CASHIER"));
        assertFalse(client.canMutateInventory(null));
    }
}
