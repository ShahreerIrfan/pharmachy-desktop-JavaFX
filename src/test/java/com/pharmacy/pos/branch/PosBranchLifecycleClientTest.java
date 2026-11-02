package com.pharmacy.pos.branch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosBranchLifecycleClientTest
 * Tests client-side branch lifecycle validation, closure rules,
 * zero-data-loss preservation of historical invoice and stock counts,
 * and offline fallback states (ADR-0107 / P107).
 */
public class PosBranchLifecycleClientTest {

    private PosBranchLifecycleClient client;

    @BeforeEach
    public void setUp() {
        client = new PosBranchLifecycleClient();
    }

    @Test
    @DisplayName("P107: Owner successfully validates branch store creation")
    public void testBranchCreationValidationSuccess() {
        PosBranchLifecycleClient.ValidationResult result = client.validateBranchCreation(
                "tenant-test-1",
                "Mirpur 10 Branch",
                "DGDA-DL-MRP-2026-009",
                "PHARMACY_OWNER"
        );
        assertTrue(result.isValid);
        assertEquals("", result.errorCode);
    }

    @Test
    @DisplayName("P107: Branch Manager and Cashier denied branch creation (RBAC gate)")
    public void testBranchCreationUnauthorizedForbidden() {
        PosBranchLifecycleClient.ValidationResult managerResult = client.validateBranchCreation(
                "tenant-test-1",
                "New Branch",
                "DGDA-DL-001",
                "BRANCH_MANAGER"
        );
        assertFalse(managerResult.isValid);
        assertEquals("ERR_PERMISSION_DENIED", managerResult.errorCode);
        assertTrue(managerResult.messageBn.contains("মালিক"));

        PosBranchLifecycleClient.ValidationResult cashierResult = client.validateBranchCreation(
                "tenant-test-1",
                "New Branch",
                "DGDA-DL-001",
                "CASHIER"
        );
        assertFalse(cashierResult.isValid);
        assertEquals("ERR_PERMISSION_DENIED", cashierResult.errorCode);
    }

    @Test
    @DisplayName("P107: Incomplete inputs (short name, missing license) rejected")
    public void testBranchCreationMissingInputsFail() {
        PosBranchLifecycleClient.ValidationResult shortName = client.validateBranchCreation(
                "tenant-test-1",
                "A",
                "DGDA-DL-001",
                "PHARMACY_OWNER"
        );
        assertFalse(shortName.isValid);
        assertEquals("ERR_INVALID_BRANCH_NAME", shortName.errorCode);

        PosBranchLifecycleClient.ValidationResult shortLicense = client.validateBranchCreation(
                "tenant-test-1",
                "Valid Name",
                "DG",
                "PHARMACY_OWNER"
        );
        assertFalse(shortLicense.isValid);
        assertEquals("ERR_INVALID_LICENSE", shortLicense.errorCode);
    }

    @Test
    @DisplayName("P107: Closure requires minimum 4 character reason and owner permission")
    public void testBranchClosureValidationReasonRequired() {
        // Short reason fails
        PosBranchLifecycleClient.ValidationResult shortReason = client.validateBranchClosure(
                "br-001", "no", "PHARMACY_OWNER"
        );
        assertFalse(shortReason.isValid);
        assertEquals("ERR_CLOSURE_REASON_REQUIRED", shortReason.errorCode);

        // Branch manager denied closing store
        PosBranchLifecycleClient.ValidationResult denied = client.validateBranchClosure(
                "br-001", "Store lease expired", "BRANCH_MANAGER"
        );
        assertFalse(denied.isValid);
        assertEquals("ERR_PERMISSION_DENIED", denied.errorCode);

        // Owner valid closure passes
        PosBranchLifecycleClient.ValidationResult ok = client.validateBranchClosure(
                "br-001", "Store lease expired; consolidated operations", "PHARMACY_OWNER"
        );
        assertTrue(ok.isValid);
    }

    @Test
    @DisplayName("P107: Suspension and closure transitions strictly preserve historical records")
    public void testSuspensionAndClosureTransitionsPreserveHistoricalCounts() {
        PosBranchLifecycleClient.BranchLifecycleRecord activeBranch = new PosBranchLifecycleClient.BranchLifecycleRecord(
                "br-mirpur-01",
                "tenant-test-1",
                "Mirpur Central Branch",
                "BR-MRP-01",
                "DGDA-DL-2026-991",
                "Plot 5, Mirpur, Dhaka",
                "+8801700000000",
                "mirpur@primepharma.com",
                "Manager Rafiq",
                true,
                "ACTIVE",
                true,
                false,
                4, // active staff
                3, // active devices
                3500, // historical invoices
                620,  // historical stock batches
                "2025-01-01T00:00:00Z",
                null,
                null
        );

        // 1. Suspension
        PosBranchLifecycleClient.BranchLifecycleViewState suspState = client.applySuspension(
                activeBranch, "Store renovation", "owner_kamal"
        );
        assertEquals(PosBranchLifecycleClient.UiStatus.SUCCESS, suspState.uiStatus);
        assertEquals("SUSPENDED", suspState.record.status);
        assertFalse(suspState.record.isActive);
        assertEquals(3500, suspState.record.historicalInvoicesCount); // Preserved!
        assertEquals(620, suspState.record.historicalStockBatchesCount); // Preserved!

        // 2. Closure
        PosBranchLifecycleClient.BranchLifecycleViewState closeState = client.applyClosure(
                activeBranch, "Permanent shutdown due to relocation", "owner_kamal"
        );
        assertEquals(PosBranchLifecycleClient.UiStatus.SUCCESS, closeState.uiStatus);
        assertEquals("CLOSED", closeState.record.status);
        assertFalse(closeState.record.isActive);
        assertFalse(closeState.record.isMainBranch); // Main branch flag cleared
        assertNotNull(closeState.record.closedAt);
        assertEquals(3500, closeState.record.historicalInvoicesCount); // 100% preserved!
        assertEquals(620, closeState.record.historicalStockBatchesCount); // 100% preserved!
    }

    @Test
    @DisplayName("P107: Offline fallback returns cached branch profile with translated notice")
    public void testOfflineCachedState() {
        PosBranchLifecycleClient.BranchLifecycleRecord cached = new PosBranchLifecycleClient.BranchLifecycleRecord(
                "br-cached-01",
                "tenant-test-1",
                "Cached Store",
                "BR-CACHE-01",
                "DGDA-DL-2026-000",
                "Address",
                "+8801700000000",
                null,
                "Manager",
                false,
                "ACTIVE",
                true,
                false,
                2,
                1,
                100,
                50,
                "2026-01-01T00:00:00Z",
                null,
                null
        );
        client.cacheRecord(cached);

        PosBranchLifecycleClient.BranchLifecycleViewState state = client.toOfflineState("br-cached-01");
        assertEquals(PosBranchLifecycleClient.UiStatus.OFFLINE, state.uiStatus);
        assertTrue(state.isOffline);
        assertEquals("Cached Store", state.record.name);
        assertTrue(state.errorMessageBn.contains("অফলাইন"));
    }
}
