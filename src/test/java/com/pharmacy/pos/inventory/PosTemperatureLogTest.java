package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosTemperatureLogTest
 * Architecture Decision: ADR-0211 (P211).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 */
public class PosTemperatureLogTest {

    private PosTemperatureLogClient client;

    @BeforeEach
    void setUp() {
        client = new PosTemperatureLogClient();
    }

    @Test
    @DisplayName("Record manual reading in normal range generates safe status and audit hash")
    void testRecordManualReadingSafe() {
        var device = client.registerDevice(
                "branch-dhanmondi", "FRIDGE-01", "Elitech RC-5",
                PosTemperatureLogClient.SensorDeviceType.USB_DATA_LOGGER, "SN-ELITECH-11", "PHARMACIST"
        );
        assertNotNull(device.deviceId);

        var reading = client.recordReading(
                "branch-dhanmondi", "FRIDGE-01",
                new BigDecimal("4.50"), new BigDecimal("55.00"),
                new BigDecimal("2.00"), new BigDecimal("8.00"),
                device.deviceId, PosTemperatureLogClient.SourceType.MANUAL_ENTRY,
                "Routine check", "usr-pharm", "PHARMACIST"
        );

        assertFalse(reading.isExcursion);
        assertEquals(PosTemperatureLogClient.ExcursionSeverity.NONE, reading.excursionSeverity);
        assertNotNull(reading.auditHash);
        assertEquals(64, reading.auditHash.length());
    }

    @Test
    @DisplayName("Batch import correctly identifies warning and critical excursions")
    void testBatchImportExcursions() {
        var temps = Arrays.asList(
                new BigDecimal("4.00"),  // Safe
                new BigDecimal("9.20"),  // Warning (>8.00)
                new BigDecimal("17.50")  // Critical (>=15.00)
        );

        var imported = client.importBatch(
                "branch-dhanmondi", "FRIDGE-01", "dev-test",
                temps, new BigDecimal("2.00"), new BigDecimal("8.00"),
                "Batch USB sync", "usr-mgr", "BRANCH_MANAGER"
        );

        assertEquals(3, imported.size());
        assertFalse(imported.get(0).isExcursion);
        assertEquals(PosTemperatureLogClient.ExcursionSeverity.NONE, imported.get(0).excursionSeverity);

        assertTrue(imported.get(1).isExcursion);
        assertEquals(PosTemperatureLogClient.ExcursionSeverity.WARNING, imported.get(1).excursionSeverity);

        assertTrue(imported.get(2).isExcursion);
        assertEquals(PosTemperatureLogClient.ExcursionSeverity.CRITICAL, imported.get(2).excursionSeverity);

        var excursions = client.getExcursions("FRIDGE-01");
        assertEquals(2, excursions.size());
    }

    @Test
    @DisplayName("Inverted thresholds throw IllegalArgumentException")
    void testInvertedThresholdsThrows() {
        assertThrows(IllegalArgumentException.class, () -> client.recordReading(
                "branch-dhanmondi", "FRIDGE-01",
                new BigDecimal("5.00"), null,
                new BigDecimal("10.00"), new BigDecimal("4.00"), // Min > Max
                null, PosTemperatureLogClient.SourceType.MANUAL_ENTRY,
                "Bad threshold", "usr-pharm", "PHARMACIST"
        ));
    }

    @Test
    @DisplayName("Cashier role cannot log or import temperature telemetry")
    void testCashierRoleDenied() {
        assertThrows(SecurityException.class, () -> client.recordReading(
                "branch-dhanmondi", "FRIDGE-01",
                new BigDecimal("5.00"), null,
                new BigDecimal("2.00"), new BigDecimal("8.00"),
                null, PosTemperatureLogClient.SourceType.MANUAL_ENTRY,
                "Cashier attempt", "usr-cashier", "CASHIER"
        ));
    }
}

