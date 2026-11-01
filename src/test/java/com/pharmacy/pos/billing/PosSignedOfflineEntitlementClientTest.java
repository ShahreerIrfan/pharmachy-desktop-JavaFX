package com.pharmacy.pos.billing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P093: JavaFX Desktop POS Signed Offline Entitlement Tests")
class PosSignedOfflineEntitlementClientTest {

    private static final String TEST_KEY = "test-offline-signing-secret-key-32ch!";
    private static final String TENANT_ID = "tn-p093-test";
    private static final String BRANCH_ID = "br-main-01";
    private static final String DEVICE_ID = "pos-workstation-counter-01";

    private PosSignedOfflineEntitlementClient.OfflineLeaseCertificate createSampleCert(
            Instant issuedAt,
            Instant expiresAt,
            String status) {

        Map<String, Boolean> caps = new HashMap<>();
        caps.put("CHECKOUT_DISPENSING", true);
        caps.put("ANTIBIOTIC_REGISTER", true);
        caps.put("BATCH_EXPIRY_BLOCK", true);
        caps.put("RX_VERIFICATION", true);

        PosSignedOfflineEntitlementClient.OfflineLeaseCertificate temp =
                new PosSignedOfflineEntitlementClient.OfflineLeaseCertificate(
                        "ls-test-01234567",
                        TENANT_ID,
                        BRANCH_ID,
                        DEVICE_ID,
                        "CASHIER",
                        "STANDARD",
                        "v2.0",
                        72,
                        issuedAt.toString(),
                        expiresAt.toString(),
                        caps,
                        "",
                        status
                );

        String canonical = PosSignedOfflineEntitlementClient.generateCanonicalPayload(temp);
        String sig = PosSignedOfflineEntitlementClient.computeHmacSha256(canonical, TEST_KEY);

        return new PosSignedOfflineEntitlementClient.OfflineLeaseCertificate(
                temp.leaseId,
                temp.tenantId,
                temp.branchId,
                temp.deviceId,
                temp.roleSnapshot,
                temp.planTier,
                temp.policyVersion,
                temp.durationHours,
                temp.issuedAtIso,
                temp.expiresAtIso,
                temp.capabilities,
                sig,
                status
        );
    }

    @Test
    @DisplayName("Valid signed offline lease verifies successfully and grants offline dispensing")
    void testValidOfflineLeaseGrantsDispensing() {
        Instant now = Instant.now();
        Instant issuedAt = now.minus(2, ChronoUnit.HOURS);
        Instant expiresAt = now.plus(70, ChronoUnit.HOURS);

        var cert = createSampleCert(issuedAt, expiresAt, "ACTIVE");

        var result = PosSignedOfflineEntitlementClient.verifyOfflineLease(
                cert,
                TENANT_ID,
                BRANCH_ID,
                DEVICE_ID,
                now,
                TEST_KEY
        );

        assertTrue(result.isValid);
        assertEquals("VALID", result.reason);
        assertTrue(result.canDispenseOffline);
        assertTrue(result.hoursRemaining >= 69);
        assertTrue(result.headlineBn.contains("স্বাক্ষরিত অফলাইন ইজারা সক্রিয়"));
    }

    @Test
    @DisplayName("Tampered signature is rejected and blocks dispensing")
    void testTamperedSignatureRejected() {
        Instant now = Instant.now();
        Instant issuedAt = now.minus(2, ChronoUnit.HOURS);
        Instant expiresAt = now.plus(70, ChronoUnit.HOURS);

        var validCert = createSampleCert(issuedAt, expiresAt, "ACTIVE");

        // Tamper with lease ID or signature
        var tamperedCert = new PosSignedOfflineEntitlementClient.OfflineLeaseCertificate(
                validCert.leaseId,
                validCert.tenantId,
                validCert.branchId,
                validCert.deviceId,
                validCert.roleSnapshot,
                validCert.planTier,
                validCert.policyVersion,
                validCert.durationHours,
                validCert.issuedAtIso,
                validCert.expiresAtIso,
                validCert.capabilities,
                "000000000000000000000000000000000000000000000000000000000000badf", // forged signature
                validCert.status
        );

        var result = PosSignedOfflineEntitlementClient.verifyOfflineLease(
                tamperedCert,
                TENANT_ID,
                BRANCH_ID,
                DEVICE_ID,
                now,
                TEST_KEY
        );

        assertFalse(result.isValid);
        assertEquals("INVALID_SIGNATURE", result.reason);
        assertFalse(result.canDispenseOffline);
        assertTrue(result.headlineBn.contains("স্বাক্ষর অবৈধ"));
    }

    @Test
    @DisplayName("Expired offline lease blocks sales and shows reconnect banner")
    void testExpiredLeaseBlocksSales() {
        Instant now = Instant.now();
        Instant issuedAt = now.minus(75, ChronoUnit.HOURS);
        Instant expiresAt = now.minus(3, ChronoUnit.HOURS); // expired 3 hours ago

        var cert = createSampleCert(issuedAt, expiresAt, "ACTIVE");

        var result = PosSignedOfflineEntitlementClient.verifyOfflineLease(
                cert,
                TENANT_ID,
                BRANCH_ID,
                DEVICE_ID,
                now,
                TEST_KEY
        );

        assertFalse(result.isValid);
        assertEquals("EXPIRED", result.reason);
        assertFalse(result.canDispenseOffline);
        assertTrue(result.headlineBn.contains("মেয়াদ শেষ"));
    }

    @Test
    @DisplayName("Hardware mismatch (wrong device ID or branch) blocks dispensing")
    void testHardwareMismatchBlocked() {
        Instant now = Instant.now();
        Instant issuedAt = now.minus(2, ChronoUnit.HOURS);
        Instant expiresAt = now.plus(70, ChronoUnit.HOURS);

        var cert = createSampleCert(issuedAt, expiresAt, "ACTIVE");

        // Run on an unauthorized terminal
        var resultDeviceMismatch = PosSignedOfflineEntitlementClient.verifyOfflineLease(
                cert,
                TENANT_ID,
                BRANCH_ID,
                "different-device-terminal-99",
                now,
                TEST_KEY
        );

        assertFalse(resultDeviceMismatch.isValid);
        assertEquals("DEVICE_MISMATCH", resultDeviceMismatch.reason);
        assertFalse(resultDeviceMismatch.canDispenseOffline);

        // Run on wrong branch
        var resultBranchMismatch = PosSignedOfflineEntitlementClient.verifyOfflineLease(
                cert,
                TENANT_ID,
                "different-branch-02",
                DEVICE_ID,
                now,
                TEST_KEY
        );

        assertFalse(resultBranchMismatch.isValid);
        assertEquals("BRANCH_MISMATCH", resultBranchMismatch.reason);
        assertFalse(resultBranchMismatch.canDispenseOffline);
    }

    @Test
    @DisplayName("Revoked lease blocks dispensing")
    void testRevokedLeaseBlocksDispensing() {
        Instant now = Instant.now();
        Instant issuedAt = now.minus(2, ChronoUnit.HOURS);
        Instant expiresAt = now.plus(70, ChronoUnit.HOURS);

        var cert = createSampleCert(issuedAt, expiresAt, "REVOKED");

        var result = PosSignedOfflineEntitlementClient.verifyOfflineLease(
                cert,
                TENANT_ID,
                BRANCH_ID,
                DEVICE_ID,
                now,
                TEST_KEY
        );

        assertFalse(result.isValid);
        assertEquals("REVOKED", result.reason);
        assertFalse(result.canDispenseOffline);
        assertTrue(result.headlineBn.contains("বাতিল"));
    }
}
