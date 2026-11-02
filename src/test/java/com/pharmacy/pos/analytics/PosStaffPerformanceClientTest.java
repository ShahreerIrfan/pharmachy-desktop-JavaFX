package com.pharmacy.pos.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for P115 PosStaffOfflinePerformanceClient (ADR-0115).
 */
public class PosStaffPerformanceClientTest {

    private PosStaffPerformanceClient client;
    private List<PosStaffPerformanceClient.StaffPerformanceRecord> sampleRecords;

    @BeforeEach
    void setUp() {
        client = new PosStaffPerformanceClient();
        sampleRecords = List.of(
                new PosStaffPerformanceClient.StaffPerformanceRecord(
                        "spm-1", "tn-lazz", "br-dhanmondi", "stf-c1", "Rahim Cashier", "CASHIER",
                        "2026-11-02", 45, 125000.0, 2, 3500.0, 121500.0, 0.0, "BALANCED", 120
                ),
                new PosStaffPerformanceClient.StaffPerformanceRecord(
                        "spm-2", "tn-lazz", "br-dhanmondi", "stf-c2", "Karim Cashier", "CASHIER",
                        "2026-11-02", 30, 80000.0, 1, 1000.0, 79000.0, -200.0, "SHORTAGE", 45
                ),
                new PosStaffPerformanceClient.StaffPerformanceRecord(
                        "spm-3", "tn-lazz", "br-gulshan", "stf-g1", "Gulshan Cashier", "CASHIER",
                        "2026-11-02", 50, 150000.0, 0, 0.0, 150000.0, 50.0, "OVERAGE", 15
                )
        );
    }

    @Test
    @DisplayName("P115: Pharmacy Owner sees all staff across branches")
    void testOwnerFullVisibility() {
        var res = client.filterMetrics(sampleRecords, "PHARMACY_OWNER", "tn-lazz", null, null, "tn-lazz", null, null);
        assertEquals(PosStaffPerformanceClient.UiStatus.SUCCESS, res.uiStatus);
        assertEquals(3, res.records.size());
    }

    @Test
    @DisplayName("P115: Branch Manager is restricted to their branch")
    void testBranchManagerScoped() {
        var res = client.filterMetrics(sampleRecords, "BRANCH_MANAGER", "tn-lazz", "br-dhanmondi", "stf-mgr", "tn-lazz", "br-dhanmondi", null);
        assertEquals(PosStaffPerformanceClient.UiStatus.SUCCESS, res.uiStatus);
        assertEquals(2, res.records.size());
    }

    @Test
    @DisplayName("P115: Branch Manager cannot inspect foreign branch (ERR_CROSS_BRANCH_VIOLATION)")
    void testBranchManagerCrossBranchDenied() {
        var res = client.filterMetrics(sampleRecords, "BRANCH_MANAGER", "tn-lazz", "br-dhanmondi", "stf-mgr", "tn-lazz", "br-gulshan", null);
        assertEquals(PosStaffPerformanceClient.UiStatus.PERMISSION_DENIED, res.uiStatus);
        assertEquals("ERR_CROSS_BRANCH_VIOLATION", res.errorCode);
    }

    @Test
    @DisplayName("P115: Cashier is strictly restricted to self-only metrics")
    void testCashierSelfOnly() {
        var res = client.filterMetrics(sampleRecords, "CASHIER", "tn-lazz", "br-dhanmondi", "stf-c1", "tn-lazz", "br-dhanmondi", null);
        assertEquals(PosStaffPerformanceClient.UiStatus.SUCCESS, res.uiStatus);
        assertEquals(1, res.records.size());
        assertEquals("stf-c1", res.records.get(0).staffId);
    }

    @Test
    @DisplayName("P115: Cashier requesting foreign staff is denied (ERR_PERMISSION_DENIED)")
    void testCashierQueryingOtherStaffDenied() {
        var res = client.filterMetrics(sampleRecords, "CASHIER", "tn-lazz", "br-dhanmondi", "stf-c1", "tn-lazz", "br-dhanmondi", "stf-c2");
        assertEquals(PosStaffPerformanceClient.UiStatus.PERMISSION_DENIED, res.uiStatus);
        assertEquals("ERR_PERMISSION_DENIED", res.errorCode);
    }

    @Test
    @DisplayName("P115: Cross-tenant metrics query is denied (ERR_CROSS_TENANT_VIOLATION)")
    void testCrossTenantDenied() {
        var res = client.filterMetrics(sampleRecords, "PHARMACY_OWNER", "tn-lazz", null, null, "tn-other-competitor", null, null);
        assertEquals(PosStaffPerformanceClient.UiStatus.PERMISSION_DENIED, res.uiStatus);
        assertEquals("ERR_CROSS_TENANT_VIOLATION", res.errorCode);
    }
}

