package com.pharmacy.pos.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for P113 PosStaffOfflineAuthClient (ADR-0113).
 */
public class PosStaffOfflineAuthClientTest {

    private PosStaffOfflineAuthClient client;
    private final String localDeviceId = "dev-pos-counter-01";
    private final String salt = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private String validPinHash;

    @BeforeEach
    void setUp() {
        client = new PosStaffOfflineAuthClient(localDeviceId);
        validPinHash = PosStaffOfflineAuthClient.computePbkdf2Hash("1234", salt);
    }

    private PosStaffOfflineAuthClient.StaffOfflineRecord createSampleRecord(String role, Instant expiresAt, boolean isRevoked, String deviceId) {
        PosStaffOfflineAuthClient.RiskLimits limits = new PosStaffOfflineAuthClient.RiskLimits(
                5.0, 25000.0, false, true
        );
        return new PosStaffOfflineAuthClient.StaffOfflineRecord(
                "oath-101",
                "tn-lazz-dhaka",
                "br-dhanmondi-01",
                "staff-cashier-01",
                "cashier01",
                "Rahim Cashier",
                role,
                deviceId != null ? deviceId : localDeviceId,
                salt,
                validPinHash,
                List.of("pos.sale.create", "pos.sale.view", "pos.receipt.print"),
                List.of("pos.refund.process", "pos.discount.override"),
                limits,
                Instant.now().minus(1, ChronoUnit.HOURS),
                expiresAt != null ? expiresAt : Instant.now().plus(48, ChronoUnit.HOURS),
                "dummy-hmac-signature",
                isRevoked
        );
    }

    @Test
    @DisplayName("P113: Valid offline staff authentication succeeds with OFFLINE status")
    void testValidOfflineStaffAuth() {
        var record = createSampleRecord("CASHIER", null, false, null);
        client.cacheAuthorization(record);

        var result = client.verifyOffline("staff-cashier-01", "1234", Instant.now());
        assertTrue(result.isAuthorized);
        assertEquals(PosStaffOfflineAuthClient.UiStatus.OFFLINE, result.uiStatus);
        assertEquals("cashier01", result.username);
        assertEquals("CASHIER", result.role);
    }

    @Test
    @DisplayName("P113: Invalid PIN fails with ERR_INVALID_CREDENTIALS without exposing plaintext")
    void testInvalidPinFails() {
        var record = createSampleRecord("CASHIER", null, false, null);
        client.cacheAuthorization(record);

        var result = client.verifyOffline("staff-cashier-01", "9999", Instant.now());
        assertFalse(result.isAuthorized);
        assertEquals("ERR_INVALID_CREDENTIALS", result.errorCode);
        assertEquals(PosStaffOfflineAuthClient.UiStatus.PERMISSION_DENIED, result.uiStatus);
    }

    @Test
    @DisplayName("P113: Device ID mismatch fails with ERR_OFFLINE_DEVICE_MISMATCH")
    void testDeviceMismatchFails() {
        var record = createSampleRecord("CASHIER", null, false, "dev-other-station");
        client.cacheAuthorization(record);

        var result = client.verifyOffline("staff-cashier-01", "1234", Instant.now());
        assertFalse(result.isAuthorized);
        assertEquals("ERR_OFFLINE_DEVICE_MISMATCH", result.errorCode);
    }

    @Test
    @DisplayName("P113: Expired authorization fails with ERR_OFFLINE_AUTH_EXPIRED")
    void testExpiredAuthFails() {
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        var record = createSampleRecord("CASHIER", past, false, null);
        client.cacheAuthorization(record);

        var result = client.verifyOffline("staff-cashier-01", "1234", Instant.now());
        assertFalse(result.isAuthorized);
        assertEquals("ERR_OFFLINE_AUTH_EXPIRED", result.errorCode);
    }

    @Test
    @DisplayName("P113: Revoked offline authorization fails with ERR_STAFF_ROLE_DENIED")
    void testRevokedAuthFails() {
        var record = createSampleRecord("CASHIER", null, true, null);
        client.cacheAuthorization(record);

        var result = client.verifyOffline("staff-cashier-01", "1234", Instant.now());
        assertFalse(result.isAuthorized);
        assertEquals("ERR_STAFF_ROLE_DENIED", result.errorCode);
    }

    @Test
    @DisplayName("P113: Risk limits enforce maximum discount and transaction amount")
    void testRiskLimitsEnforced() {
        var record = createSampleRecord("CASHIER", null, false, null);
        client.cacheAuthorization(record);

        // Max discount 5%
        assertTrue(client.isDiscountPermitted("staff-cashier-01", 5.0));
        assertFalse(client.isDiscountPermitted("staff-cashier-01", 5.5));

        // Max sale amount 25,000 BDT
        assertTrue(client.isSaleAmountPermitted("staff-cashier-01", 25000.0));
        assertFalse(client.isSaleAmountPermitted("staff-cashier-01", 25000.01));

        // Permitted vs Denied action
        assertTrue(client.canPerformAction("staff-cashier-01", "pos.sale.create"));
        assertFalse(client.canPerformAction("staff-cashier-01", "pos.refund.process"));
    }
}

