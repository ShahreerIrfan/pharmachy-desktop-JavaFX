package com.pharmacy.pos.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStorageConditionalClientTest
 * Architecture Decision: ADR-0155 (P155).
 * Storage Conditional Fields and Cold-Chain Governance Test Suite.
 */
public class PosStorageConditionalClientTest {

    private PosStorageConditionalClient client;

    @BeforeEach
    public void setUp() {
        client = new PosStorageConditionalClient();
    }

    @Test
    @DisplayName("P155-DESK-01: Valid cold chain storage validation succeeds")
    public void testValidateStorageColdChainSuccess() {
        PosStorageConditionalClient.StorageValidationResult result = client.validateStorageConditions(
                true,
                new BigDecimal("2.00"),
                new BigDecimal("8.00"),
                "SHELF_MIDDLE",
                24,
                "QUARANTINE_IMMEDIATELY"
        );

        assertTrue(result.isValid);
        assertNull(result.errorCode);
        assertTrue(result.isColdChainRequired);
        assertEquals(new BigDecimal("6.00"), result.temperatureDeltaCelsius);
    }

    @Test
    @DisplayName("P155-DESK-02: Non-cold-chain ambient product validation cleanly passes")
    public void testValidateStorageAmbientSuccess() {
        PosStorageConditionalClient.StorageValidationResult result = client.validateStorageConditions(
                false,
                null,
                null,
                null,
                0,
                null
        );

        assertTrue(result.isValid);
        assertFalse(result.isColdChainRequired);
        assertEquals(BigDecimal.ZERO, result.temperatureDeltaCelsius);
    }

    @Test
    @DisplayName("P155-DESK-03: Min temperature >= max temperature triggers ERR_INVALID_TEMPERATURE_RANGE")
    public void testInvalidTemperatureRangeRejected() {
        PosStorageConditionalClient.StorageValidationResult result = client.validateStorageConditions(
                true,
                new BigDecimal("8.00"),
                new BigDecimal("2.00"),
                "SHELF_MIDDLE",
                24,
                "QUARANTINE_IMMEDIATELY"
        );

        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_TEMPERATURE_RANGE", result.errorCode);
    }

    @Test
    @DisplayName("P155-DESK-04: Refrigerator door placement triggers ERR_PROHIBITED_DOOR_PLACEMENT")
    public void testDoorPlacementProhibited() {
        PosStorageConditionalClient.StorageValidationResult result = client.validateStorageConditions(
                true,
                new BigDecimal("2.00"),
                new BigDecimal("8.00"),
                "DOOR_NOT_PERMITTED",
                24,
                "QUARANTINE_IMMEDIATELY"
        );

        assertFalse(result.isValid);
        assertEquals("ERR_PROHIBITED_DOOR_PLACEMENT", result.errorCode);
    }

    @Test
    @DisplayName("P155-DESK-05: Excursion hours exceeding 72 triggers ERR_INVALID_EXCURSION_HOURS")
    public void testInvalidExcursionHoursRejected() {
        PosStorageConditionalClient.StorageValidationResult result = client.validateStorageConditions(
                true,
                new BigDecimal("2.00"),
                new BigDecimal("8.00"),
                "SHELF_MIDDLE",
                100,
                "QUARANTINE_IMMEDIATELY"
        );

        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_EXCURSION_HOURS", result.errorCode);
    }

    @Test
    @DisplayName("P155-DESK-06: Missing temperature range for cold chain product triggers ERR_MISSING_TEMPERATURE_RANGE")
    public void testMissingTemperatureRange() {
        PosStorageConditionalClient.StorageValidationResult result = client.validateStorageConditions(
                true,
                null,
                new BigDecimal("8.00"),
                "SHELF_MIDDLE",
                24,
                "QUARANTINE_IMMEDIATELY"
        );

        assertFalse(result.isValid);
        assertEquals("ERR_MISSING_TEMPERATURE_RANGE", result.errorCode);
    }

    @Test
    @DisplayName("P155-DESK-07: Cashier role is restricted from mutating storage configurations")
    public void testCanMutateStoragePermissions() {
        assertTrue(client.canMutateStorage("STAFF_PHARMACIST"));
        assertTrue(client.canMutateStorage("PHARMACY_OWNER"));
        assertTrue(client.canMutateStorage("SUPERADMIN"));
        assertFalse(client.canMutateStorage("CASHIER"));
        assertFalse(client.canMutateStorage(null));
    }

    @Test
    @DisplayName("P155-DESK-08: Refrigerator placement and action display localized in Bengali and English")
    public void testPlacementAndActionDisplayLocalization() {
        assertEquals("মাঝের তাক (স্থির তাপমাত্রা)", client.getPlacementDisplay("SHELF_MIDDLE", "bn"));
        assertEquals("Middle Shelf (Uniform Temperature)", client.getPlacementDisplay("SHELF_MIDDLE", "en"));
        assertEquals("তাত্ক্ষণিক কোয়ারেন্টাইন করুন", client.getActionDisplay("QUARANTINE_IMMEDIATELY", "bn"));
        assertEquals("Quarantine Immediately for Assessment", client.getActionDisplay("QUARANTINE_IMMEDIATELY", "en"));
    }
}
