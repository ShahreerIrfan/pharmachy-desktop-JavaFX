package com.pharmacy.pos.stock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosExpiryManufacturingClientTest
 * Architecture Decision: ADR-0154 (P154).
 * Regulated Stock Entry, Expiry, Manufacturing Date, and Lot Controls Test Suite.
 */
public class PosExpiryManufacturingClientTest {

    private PosExpiryManufacturingClient client;
    private LocalDate today;

    @BeforeEach
    public void setUp() {
        client = new PosExpiryManufacturingClient();
        today = LocalDate.of(2026, 11, 3);
    }

    @Test
    @DisplayName("P154-DESK-01: Valid compliant stock intake validation succeeds")
    public void testValidateStockIntakeSuccess() {
        LocalDate mfg = today.minusDays(90);
        LocalDate exp = today.plusDays(500);

        PosExpiryManufacturingClient.ValidationResult result = client.validateStockIntake(
                "LOT-SQ-2026",
                mfg,
                exp,
                today,
                30,
                false
        );

        assertTrue(result.isValid);
        assertNull(result.errorCode);
        assertEquals(590, result.shelfLifeDays);
        assertFalse(result.isAutoQuarantined);
    }

    @Test
    @DisplayName("P154-DESK-02: Future manufacturing date triggers ERR_FUTURE_MANUFACTURING_DATE")
    public void testFutureManufacturingDateRejected() {
        LocalDate mfg = today.plusDays(5);
        LocalDate exp = today.plusDays(500);

        PosExpiryManufacturingClient.ValidationResult result = client.validateStockIntake(
                "LOT-FUT-01",
                mfg,
                exp,
                today,
                30,
                false
        );

        assertFalse(result.isValid);
        assertEquals("ERR_FUTURE_MANUFACTURING_DATE", result.errorCode);
    }

    @Test
    @DisplayName("P154-DESK-03: Expiry date before manufacturing date triggers ERR_EXPIRY_BEFORE_MANUFACTURING")
    public void testExpiryBeforeManufacturingRejected() {
        LocalDate mfg = today.minusDays(30);
        LocalDate exp = today.minusDays(40);

        PosExpiryManufacturingClient.ValidationResult result = client.validateStockIntake(
                "LOT-INV-01",
                mfg,
                exp,
                today,
                30,
                false
        );

        assertFalse(result.isValid);
        assertEquals("ERR_EXPIRY_BEFORE_MANUFACTURING", result.errorCode);
    }

    @Test
    @DisplayName("P154-DESK-04: Expired stock intake into active inventory triggers ERR_STOCK_ALREADY_EXPIRED")
    public void testExpiredStockIntakeRejected() {
        LocalDate mfg = today.minusDays(400);
        LocalDate exp = today.minusDays(10);

        PosExpiryManufacturingClient.ValidationResult result = client.validateStockIntake(
                "LOT-EXP-01",
                mfg,
                exp,
                today,
                30,
                false
        );

        assertFalse(result.isValid);
        assertEquals("ERR_STOCK_ALREADY_EXPIRED", result.errorCode);
    }

    @Test
    @DisplayName("P154-DESK-05: Expired stock intake explicitly quarantined is permitted")
    public void testExpiredStockIntakeAllowedIfQuarantined() {
        LocalDate mfg = today.minusDays(400);
        LocalDate exp = today.minusDays(10);

        PosExpiryManufacturingClient.ValidationResult result = client.validateStockIntake(
                "LOT-EXP-02",
                mfg,
                exp,
                today,
                30,
                true // explicitly quarantined
        );

        assertTrue(result.isValid);
        assertTrue(result.isAutoQuarantined);
    }

    @Test
    @DisplayName("P154-DESK-06: Near-expiry stock within lead window is automatically flagged quarantined")
    public void testNearExpiryAutoQuarantine() {
        LocalDate mfg = today.minusDays(180);
        LocalDate exp = today.plusDays(15); // 15 days <= 30 lead

        PosExpiryManufacturingClient.ValidationResult result = client.validateStockIntake(
                "LOT-NEAR-01",
                mfg,
                exp,
                today,
                30,
                false
        );

        assertTrue(result.isValid);
        assertTrue(result.isAutoQuarantined);
    }

    @Test
    @DisplayName("P154-DESK-07: Invalid lot reference format triggers ERR_INVALID_LOT_FORMAT")
    public void testInvalidLotFormat() {
        LocalDate mfg = today.minusDays(30);
        LocalDate exp = today.plusDays(300);

        PosExpiryManufacturingClient.ValidationResult result = client.validateStockIntake(
                "LOT#BAD$CHARS!",
                mfg,
                exp,
                today,
                30,
                false
        );

        assertFalse(result.isValid);
        assertEquals("ERR_INVALID_LOT_FORMAT", result.errorCode);
    }

    @Test
    @DisplayName("P154-DESK-08: Cashier is restricted from performing stock intake mutations")
    public void testCanMutateStockRole() {
        assertTrue(client.canMutateStock("STAFF_PHARMACIST"));
        assertTrue(client.canMutateStock("PHARMACY_OWNER"));
        assertTrue(client.canMutateStock("SUPERADMIN"));
        assertFalse(client.canMutateStock("CASHIER"));
        assertFalse(client.canMutateStock(null));
    }

    @Test
    @DisplayName("P154-DESK-09: Storage condition display returns localized text in Bengali and English")
    public void testStorageConditionDisplay() {
        assertEquals("কোল্ড চেইন রেফ্রিজারেশন (২°সে - ৮°সে)", client.getStorageConditionDisplay("COLD_CHAIN_2_8C", "bn"));
        assertEquals("Cold Chain (2°C - 8°C)", client.getStorageConditionDisplay("COLD_CHAIN_2_8C", "en"));
        assertEquals("স্বাভাবিক তাপমাত্রা (১৫°সে - ২৫°সে)", client.getStorageConditionDisplay("ROOM_TEMPERATURE_15_25C", "bn"));
    }
}

