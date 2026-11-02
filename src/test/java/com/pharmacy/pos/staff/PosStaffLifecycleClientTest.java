package com.pharmacy.pos.staff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStaffLifecycleClientTest
 * Tests client-side staff lifecycle validation, state transitions,
 * session revocation simulation, offline fallback, and bilingual messaging (ADR-0106 / P106).
 */
public class PosStaffLifecycleClientTest {

    private PosStaffLifecycleClient client;

    @BeforeEach
    public void setUp() {
        client = new PosStaffLifecycleClient();
    }

    @Test
    @DisplayName("P106: Owner successfully validates direct staff creation")
    public void testDirectCreationValidationSuccess() {
        PosStaffLifecycleClient.ValidationResult result = client.validateDirectCreation(
                "tenant-test-1",
                "salim_cashier",
                "salim@example.com",
                "Salim Reza",
                "CASHIER",
                "PHARMACY_OWNER"
        );
        assertTrue(result.isValid);
        assertEquals("", result.errorCode);
    }

    @Test
    @DisplayName("P106: Frontline cashier is denied direct staff creation (RBAC gate)")
    public void testDirectCreationUnauthorizedForbidden() {
        PosStaffLifecycleClient.ValidationResult result = client.validateDirectCreation(
                "tenant-test-1",
                "new_cashier",
                "new@example.com",
                "New Cashier",
                "CASHIER",
                "CASHIER"
        );
        assertFalse(result.isValid);
        assertEquals("ERR_PERMISSION_DENIED", result.errorCode);
        assertTrue(result.messageBn.contains("মালিক"));
    }

    @Test
    @DisplayName("P106: Incomplete or invalid input is rejected")
    public void testDirectCreationMissingFieldsFails() {
        PosStaffLifecycleClient.ValidationResult badEmail = client.validateDirectCreation(
                "tenant-test-1",
                "valid_user",
                "invalid-email-address",
                "Valid User",
                "CASHIER",
                "PHARMACY_OWNER"
        );
        assertFalse(badEmail.isValid);
        assertEquals("ERR_INVALID_EMAIL", badEmail.errorCode);

        PosStaffLifecycleClient.ValidationResult shortName = client.validateDirectCreation(
                "tenant-test-1",
                "valid_user",
                "user@example.com",
                "A",
                "CASHIER",
                "PHARMACY_OWNER"
        );
        assertFalse(shortName.isValid);
        assertEquals("ERR_INVALID_NAME", shortName.errorCode);
    }

    @Test
    @DisplayName("P106: Termination requires detailed reason and owner permissions")
    public void testTerminationValidationReasonRequired() {
        // Empty reason fails
        PosStaffLifecycleClient.ValidationResult emptyReason = client.validateTermination(
                "stf-123", "", "PHARMACY_OWNER"
        );
        assertFalse(emptyReason.isValid);
        assertEquals("ERR_TERMINATION_REASON_REQUIRED", emptyReason.errorCode);

        // Cashier attempting termination fails
        PosStaffLifecycleClient.ValidationResult denied = client.validateTermination(
                "stf-123", "Gross misconduct", "CASHIER"
        );
        assertFalse(denied.isValid);
        assertEquals("ERR_PERMISSION_DENIED", denied.errorCode);

        // Valid owner termination passes
        PosStaffLifecycleClient.ValidationResult ok = client.validateTermination(
                "stf-123", "Employee resigned with notice", "PHARMACY_OWNER"
        );
        assertTrue(ok.isValid);
    }

    @Test
    @DisplayName("P106: Deactivation and termination transitions zero out active sessions")
    public void testDeactivationAndTerminationTransitionsWithSessionRevocation() {
        PosStaffLifecycleClient.StaffLifecycleRecord activeRecord = new PosStaffLifecycleClient.StaffLifecycleRecord(
                "stf-001",
                "tenant-test-1",
                "br-01",
                "Dhanmondi Branch",
                "employee_one",
                "emp1@example.com",
                "Employee One",
                "CASHIER",
                "+8801700000000",
                "",
                "ACTIVE",
                true,
                true,
                3, // 3 active sessions
                2, // 2 active offline leases
                "2026-01-01T00:00:00Z",
                null,
                null,
                null,
                null
        );

        // 1. Deactivation
        PosStaffLifecycleClient.StaffLifecycleViewState deactState = client.applyDeactivation(
                activeRecord, "Under investigation", "owner_kamal"
        );
        assertEquals(PosStaffLifecycleClient.UiStatus.SUCCESS, deactState.uiStatus);
        assertEquals("DEACTIVATED", deactState.record.lifecycleStatus);
        assertFalse(deactState.record.isActive);
        assertEquals(0, deactState.record.activeSessionsCount);
        assertNotNull(deactState.record.deactivatedAt);

        // 2. Termination
        PosStaffLifecycleClient.StaffLifecycleViewState termState = client.applyTermination(
                activeRecord, "Permanent separation of employment", "owner_kamal"
        );
        assertEquals(PosStaffLifecycleClient.UiStatus.SUCCESS, termState.uiStatus);
        assertEquals("TERMINATED", termState.record.lifecycleStatus);
        assertFalse(termState.record.isActive);
        assertFalse(termState.record.mfaEnabled);
        assertEquals(0, termState.record.activeSessionsCount);
        assertEquals(0, termState.record.activeLeasesCount);
        assertNotNull(termState.record.terminatedAt);
    }

    @Test
    @DisplayName("P106: Offline cached state returns cached profile with translated notice")
    public void testOfflineCachedState() {
        PosStaffLifecycleClient.StaffLifecycleRecord cached = new PosStaffLifecycleClient.StaffLifecycleRecord(
                "stf-999",
                "tenant-test-1",
                "br-01",
                "Dhanmondi Branch",
                "cached_staff",
                "cached@example.com",
                "Cached Staff",
                "CASHIER",
                "+8801700000000",
                "",
                "ACTIVE",
                true,
                false,
                1,
                1,
                "2026-01-01T00:00:00Z",
                null,
                null,
                null,
                null
        );
        client.cacheRecord(cached);

        PosStaffLifecycleClient.StaffLifecycleViewState state = client.toOfflineState("stf-999");
        assertEquals(PosStaffLifecycleClient.UiStatus.OFFLINE, state.uiStatus);
        assertTrue(state.isOffline);
        assertEquals("cached_staff", state.record.username);
        assertTrue(state.errorMessageBn.contains("অফলাইন"));
    }
}

