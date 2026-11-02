package com.pharmacy.pos.device;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P111: PosDeviceRegistrationClient JUnit 5 Tests.
 * Architecture Decision: ADR-0111
 * Phase 06: Milestone M03 - Offline-First Desktop POS & Owner Visibility.
 *
 * Verifies workstation cryptographic registration, branch assignment,
 * SHA-256 key fingerprinting, RBAC approvals, revocation, verification gates,
 * and offline queue handling.
 */
public class PosDeviceRegistrationClientTest {

    private static final String SAMPLE_ED25519_PUB =
            "-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEA9fVbK1b9Lz4N6pQ8rX1yA3s5u7w9y1z3b5d7f9h1j3k=\n-----END PUBLIC KEY-----";

    private static final String ANOTHER_ED25519_PUB =
            "-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEAMN9bK1b9Lz4N6pQ8rX1yA3s5u7w9y1z3b5d7f9h1j99=\n-----END PUBLIC KEY-----";

    private PosDeviceRegistrationClient ownerClient;
    private PosDeviceRegistrationClient managerBranch1Client;
    private PosDeviceRegistrationClient cashierClient;

    @BeforeEach
    void setUp() {
        ownerClient = new PosDeviceRegistrationClient(
                "http://localhost:8000/api/v2",
                "tn-lazz-pharma",
                "PHARMACY_OWNER",
                null,
                "owner_kabir"
        );

        managerBranch1Client = new PosDeviceRegistrationClient(
                "http://localhost:8000/api/v2",
                "tn-lazz-pharma",
                "BRANCH_MANAGER",
                "br-dhanmondi-01",
                "mgr_tariq"
        );

        cashierClient = new PosDeviceRegistrationClient(
                "http://localhost:8000/api/v2",
                "tn-lazz-pharma",
                "CASHIER",
                "br-dhanmondi-01",
                "csh_rahim"
        );
    }

    @Test
    @DisplayName("P111: Workstation registers with public key, generates 64-char SHA-256 fingerprint in PENDING_APPROVAL")
    void testRegisterDeviceSuccessAndFingerprint() {
        PosDeviceRegistrationClient.DeviceRegistrationRecord record = ownerClient.registerDevice(
                "br-dhanmondi-01",
                "term-pos-01",
                "Counter 1 - Fast Dispensing Desk",
                SAMPLE_ED25519_PUB,
                PosDeviceRegistrationClient.KeyAlgorithm.ED25519,
                "HW-XYZ-9988-77",
                "00:1A:2B:3C:4D:5E"
        );

        assertNotNull(record);
        assertTrue(record.registrationId.startsWith("dreg-"));
        assertEquals("term-pos-01", record.deviceId);
        assertEquals("Counter 1 - Fast Dispensing Desk", record.deviceLabel);
        assertEquals(PosDeviceRegistrationClient.DeviceRegistrationStatus.PENDING_APPROVAL, record.status);
        assertNotNull(record.keyFingerprint);
        assertEquals(64, record.keyFingerprint.length());

        // Verify SHA-256 deterministic computation
        String manualFp = ownerClient.computeFingerprint(SAMPLE_ED25519_PUB);
        assertEquals(manualFp, record.keyFingerprint);
    }

    @Test
    @DisplayName("P111: Owner approves device, transitioning to APPROVED with maker-checker audit")
    void testOwnerApproveDevice() {
        PosDeviceRegistrationClient.DeviceRegistrationRecord reg = ownerClient.registerDevice(
                "br-dhanmondi-01",
                "term-pos-02",
                "Counter 2 - Main POS",
                SAMPLE_ED25519_PUB,
                PosDeviceRegistrationClient.KeyAlgorithm.ED25519,
                "HW-ABC-11",
                "00:1A:2B:3C:4D:5F"
        );

        PosDeviceRegistrationClient.DeviceRegistrationRecord approved = ownerClient.approveDevice(
                reg.registrationId,
                "Hardware inspected and approved for dispensing"
        );

        assertEquals(PosDeviceRegistrationClient.DeviceRegistrationStatus.APPROVED, approved.status);
        assertEquals("owner_kabir", approved.approvedBy);
        assertNotNull(approved.approvedAt);
        assertEquals("Hardware inspected and approved for dispensing", approved.approvalNotes);
    }

    @Test
    @DisplayName("P111: Branch Manager can approve device belonging to their assigned branch")
    void testBranchManagerApproveAssignedBranch() {
        PosDeviceRegistrationClient.DeviceRegistrationRecord reg = managerBranch1Client.registerDevice(
                "br-dhanmondi-01",
                "term-pos-03",
                "Counter 3 - Dhanmondi Desk",
                SAMPLE_ED25519_PUB,
                PosDeviceRegistrationClient.KeyAlgorithm.ED25519,
                "HW-BR1-33",
                "00:1A:2B:3C:4D:60"
        );

        PosDeviceRegistrationClient.DeviceRegistrationRecord approved = managerBranch1Client.approveDevice(
                reg.registrationId,
                "Verified physical station at Dhanmondi"
        );

        assertEquals(PosDeviceRegistrationClient.DeviceRegistrationStatus.APPROVED, approved.status);
        assertEquals("mgr_tariq", approved.approvedBy);
    }

    @Test
    @DisplayName("P111: Branch Manager cannot approve device from different branch (ERR_CROSS_BRANCH_VIOLATION)")
    void testBranchManagerCrossBranchViolation() {
        PosDeviceRegistrationClient.DeviceRegistrationRecord gulshanDevice = ownerClient.registerDevice(
                "br-gulshan-02",
                "term-pos-gulshan-01",
                "Counter 1 - Gulshan Desk",
                SAMPLE_ED25519_PUB,
                PosDeviceRegistrationClient.KeyAlgorithm.ED25519,
                "HW-GUL-01",
                "00:1A:2B:3C:4D:61"
        );

        // Import gulshan device into manager's client cache
        managerBranch1Client.importRegistration(gulshanDevice);

        SecurityException ex = assertThrows(SecurityException.class, () ->
                managerBranch1Client.approveDevice(gulshanDevice.registrationId, "Unauthorized cross-branch approval")
        );
        assertTrue(ex.getMessage().contains("ERR_CROSS_BRANCH_VIOLATION"));
    }

    @Test
    @DisplayName("P111: Cashier is strictly denied approval and rejection authority (ERR_PERMISSION_DENIED)")
    void testCashierDeniedApprovalAuthority() {
        PosDeviceRegistrationClient.DeviceRegistrationRecord reg = ownerClient.registerDevice(
                "br-dhanmondi-01",
                "term-pos-cashier-test",
                "Counter Cashier Station",
                SAMPLE_ED25519_PUB,
                PosDeviceRegistrationClient.KeyAlgorithm.ED25519,
                "HW-CSH-01",
                "00:1A:2B:3C:4D:62"
        );

        // Import device into cashier's client cache
        cashierClient.importRegistration(reg);

        SecurityException ex = assertThrows(SecurityException.class, () ->
                cashierClient.approveDevice(reg.registrationId, "Cashier self-approval")
        );
        assertTrue(ex.getMessage().contains("ERR_PERMISSION_DENIED"));
    }

    @Test
    @DisplayName("P111: Rejection requires mandatory reason and transitions to REJECTED")
    void testRejectDeviceRequiresReason() {
        PosDeviceRegistrationClient.DeviceRegistrationRecord reg = ownerClient.registerDevice(
                "br-dhanmondi-01",
                "term-pos-suspicious",
                "Rogue Personal Laptop",
                SAMPLE_ED25519_PUB,
                PosDeviceRegistrationClient.KeyAlgorithm.ED25519,
                "HW-ROGUE-99",
                "00:1A:2B:3C:4D:99"
        );

        // Blank reason fails
        IllegalArgumentException emptyEx = assertThrows(IllegalArgumentException.class, () ->
                ownerClient.rejectDevice(reg.registrationId, "  ")
        );
        assertTrue(emptyEx.getMessage().contains("ERR_REASON_REQUIRED"));

        // Valid reason succeeds
        PosDeviceRegistrationClient.DeviceRegistrationRecord rejected = ownerClient.rejectDevice(
                reg.registrationId,
                "Unrecognized MAC address; rogue hardware detected"
        );
        assertEquals(PosDeviceRegistrationClient.DeviceRegistrationStatus.REJECTED, rejected.status);
        assertEquals("owner_kabir", rejected.rejectedBy);
        assertEquals("Unrecognized MAC address; rogue hardware detected", rejected.rejectionReason);
    }

    @Test
    @DisplayName("P111: Revocation immediately transitions trusted device to REVOKED")
    void testRevokeDeviceRequiresReason() {
        PosDeviceRegistrationClient.DeviceRegistrationRecord reg = ownerClient.registerDevice(
                "br-dhanmondi-01",
                "term-pos-stolen",
                "Counter 5 - Dispenser Desk",
                SAMPLE_ED25519_PUB,
                PosDeviceRegistrationClient.KeyAlgorithm.ED25519,
                "HW-STL-55",
                "00:1A:2B:3C:4D:55"
        );
        ownerClient.approveDevice(reg.registrationId, "Approved initially");

        PosDeviceRegistrationClient.DeviceRegistrationRecord revoked = ownerClient.revokeDevice(
                reg.registrationId,
                "Device motherboard stolen from store"
        );

        assertEquals(PosDeviceRegistrationClient.DeviceRegistrationStatus.REVOKED, revoked.status);
        assertEquals("owner_kabir", revoked.revokedBy);
        assertEquals("Device motherboard stolen from store", revoked.revocationReason);
    }

    @Test
    @DisplayName("P111: Verification challenge accurately verifies trust, approval states, and key mismatch")
    void testVerifyDeviceGate() {
        PosDeviceRegistrationClient.DeviceRegistrationRecord reg = ownerClient.registerDevice(
                "br-dhanmondi-01",
                "term-pos-gate-test",
                "Gate Testing Terminal",
                SAMPLE_ED25519_PUB,
                PosDeviceRegistrationClient.KeyAlgorithm.ED25519,
                "HW-GATE-01",
                "00:1A:2B:3C:4D:77"
        );

        // 1. Pending approval -> rejected
        PosDeviceRegistrationClient.VerificationResult res1 = ownerClient.verifyDevice("term-pos-gate-test", SAMPLE_ED25519_PUB);
        assertFalse(res1.isTrusted);
        assertEquals("ERR_DEVICE_NOT_APPROVED", res1.errorCode);

        // 2. Approved with matching key -> trusted
        ownerClient.approveDevice(reg.registrationId, "Approved for testing");
        PosDeviceRegistrationClient.VerificationResult res2 = ownerClient.verifyDevice("term-pos-gate-test", SAMPLE_ED25519_PUB);
        assertTrue(res2.isTrusted);
        assertEquals("APPROVED", res2.status);
        assertEquals("term-pos-gate-test", res2.deviceId);

        // 3. Approved but different public key -> key mismatch rejected
        PosDeviceRegistrationClient.VerificationResult res3 = ownerClient.verifyDevice("term-pos-gate-test", ANOTHER_ED25519_PUB);
        assertFalse(res3.isTrusted);
        assertEquals("ERR_DEVICE_KEY_MISMATCH", res3.errorCode);

        // 4. Revoked -> revoked rejected
        ownerClient.revokeDevice(reg.registrationId, "Decommissioned workstation");
        PosDeviceRegistrationClient.VerificationResult res4 = ownerClient.verifyDevice("term-pos-gate-test", SAMPLE_ED25519_PUB);
        assertFalse(res4.isTrusted);
        assertEquals("ERR_DEVICE_REVOKED", res4.errorCode);

        // 5. Non-existent device -> not found
        PosDeviceRegistrationClient.VerificationResult res5 = ownerClient.verifyDevice("term-unknown-999", SAMPLE_ED25519_PUB);
        assertFalse(res5.isTrusted);
        assertEquals("ERR_DEVICE_NOT_FOUND", res5.errorCode);
    }

    @Test
    @DisplayName("P111: Update device label updates record and preserves key fingerprint")
    void testUpdateDeviceLabel() {
        PosDeviceRegistrationClient.DeviceRegistrationRecord reg = ownerClient.registerDevice(
                "br-dhanmondi-01",
                "term-pos-label-test",
                "Old Counter Label",
                SAMPLE_ED25519_PUB,
                PosDeviceRegistrationClient.KeyAlgorithm.ED25519,
                "HW-LBL-01",
                "00:1A:2B:3C:4D:88"
        );

        PosDeviceRegistrationClient.DeviceRegistrationRecord updated = ownerClient.updateDeviceLabel(
                reg.registrationId,
                "Counter 1 - Express Checkout"
        );

        assertEquals("Counter 1 - Express Checkout", updated.deviceLabel);
        assertEquals(reg.keyFingerprint, updated.keyFingerprint);
    }

    @Test
    @DisplayName("P111: Offline mode enqueues registrations, reports OFFLINE UI status, and syncs upon reconnection")
    void testOfflineQueueAndUiState() {
        PosDeviceRegistrationClient offlineClient = new PosDeviceRegistrationClient(
                "http://localhost:8000/api/v2",
                "tn-lazz-pharma",
                "PHARMACY_OWNER",
                "br-dhanmondi-01",
                "owner_kabir"
        );
        offlineClient.setOffline(true);

        offlineClient.registerDevice(
                "br-dhanmondi-01",
                "term-offline-01",
                "Offline Desk 1",
                SAMPLE_ED25519_PUB,
                PosDeviceRegistrationClient.KeyAlgorithm.ED25519,
                "HW-OFF-01",
                "00:1A:2B:3C:4D:90"
        );

        PosDeviceRegistrationClient.DeviceUiState uiState = offlineClient.getUiState(null, null);
        assertEquals(PosDeviceRegistrationClient.UiStatus.OFFLINE, uiState.uiStatus);
        assertEquals(1, uiState.offlineQueueCount);
        assertNotNull(uiState.errorMessageBn);

        int synced = offlineClient.syncOfflineRegistrations();
        assertEquals(1, synced);

        offlineClient.setOffline(false);
        PosDeviceRegistrationClient.DeviceUiState onlineState = offlineClient.getUiState(null, null);
        assertEquals(PosDeviceRegistrationClient.UiStatus.SUCCESS, onlineState.uiStatus);
        assertEquals(0, onlineState.offlineQueueCount);
    }
}
