package com.pharmacy.pos.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for P116 PosBranchOperatingSettingsClient (ADR-0116).
 */
public class PosBranchOperatingSettingsClientTest {

    private PosBranchOperatingSettingsClient client;
    private PosBranchOperatingSettingsClient.BranchSettingsRecord sampleRecord;

    @BeforeEach
    void setUp() {
        client = new PosBranchOperatingSettingsClient();
        sampleRecord = new PosBranchOperatingSettingsClient.BranchSettingsRecord(
                "bos-101",
                "tn-lazz",
                "br-dhanmondi",
                "Dhanmondi Branch",
                new PosBranchOperatingSettingsClient.ReceiptFormat("80mm", "Lazz Pharma", "Thank you", true, "BILINGUAL"),
                new PosBranchOperatingSettingsClient.DevicePolicies(5, true, 72, true),
                0.0,
                10
        );
    }

    @Test
    @DisplayName("P116: Owner can read and write branch operating settings")
    void testOwnerReadWrite() {
        var readRes = client.evaluateAccess("PHARMACY_OWNER", "tn-lazz", null, sampleRecord, false);
        assertEquals(PosBranchOperatingSettingsClient.UiStatus.SUCCESS, readRes.uiStatus);

        var writeRes = client.evaluateAccess("PHARMACY_OWNER", "tn-lazz", null, sampleRecord, true);
        assertEquals(PosBranchOperatingSettingsClient.UiStatus.SUCCESS, writeRes.uiStatus);
    }

    @Test
    @DisplayName("P116: Branch Manager can write assigned branch settings")
    void testManagerAssignedBranchWrite() {
        var res = client.evaluateAccess("BRANCH_MANAGER", "tn-lazz", "br-dhanmondi", sampleRecord, true);
        assertEquals(PosBranchOperatingSettingsClient.UiStatus.SUCCESS, res.uiStatus);
    }

    @Test
    @DisplayName("P116: Branch Manager cannot write foreign branch settings (ERR_CROSS_BRANCH_VIOLATION)")
    void testManagerForeignBranchWriteDenied() {
        var res = client.evaluateAccess("BRANCH_MANAGER", "tn-lazz", "br-gulshan", sampleRecord, true);
        assertEquals(PosBranchOperatingSettingsClient.UiStatus.PERMISSION_DENIED, res.uiStatus);
        assertEquals("ERR_CROSS_BRANCH_VIOLATION", res.errorCode);
    }

    @Test
    @DisplayName("P116: Cashier can read operating settings for POS initialization")
    void testCashierReadPermitted() {
        var res = client.evaluateAccess("CASHIER", "tn-lazz", "br-dhanmondi", sampleRecord, false);
        assertEquals(PosBranchOperatingSettingsClient.UiStatus.SUCCESS, res.uiStatus);
    }

    @Test
    @DisplayName("P116: Cashier cannot modify operating settings (ERR_PERMISSION_DENIED)")
    void testCashierWriteDenied() {
        var res = client.evaluateAccess("CASHIER", "tn-lazz", "br-dhanmondi", sampleRecord, true);
        assertEquals(PosBranchOperatingSettingsClient.UiStatus.PERMISSION_DENIED, res.uiStatus);
        assertEquals("ERR_PERMISSION_DENIED", res.errorCode);
    }

    @Test
    @DisplayName("P116: Cross-tenant access is rejected (ERR_CROSS_TENANT_VIOLATION)")
    void testCrossTenantDenied() {
        var res = client.evaluateAccess("PHARMACY_OWNER", "tn-foreign-corp", null, sampleRecord, false);
        assertEquals(PosBranchOperatingSettingsClient.UiStatus.PERMISSION_DENIED, res.uiStatus);
        assertEquals("ERR_CROSS_TENANT_VIOLATION", res.errorCode);
    }
}

