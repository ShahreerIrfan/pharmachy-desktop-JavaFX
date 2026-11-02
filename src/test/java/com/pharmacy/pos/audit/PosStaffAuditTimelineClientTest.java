package com.pharmacy.pos.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStaffAuditTimelineClientTest
 * Tests P118 Staff Audit Timeline desktop client logic.
 */
class PosStaffAuditTimelineClientTest {

    private PosStaffAuditTimelineClient client;
    private List<PosStaffAuditTimelineClient.TimelineItem> sampleEvents;

    @BeforeEach
    void setUp() {
        client = new PosStaffAuditTimelineClient();
        sampleEvents = new ArrayList<>();

        sampleEvents.add(new PosStaffAuditTimelineClient.TimelineItem(
                "tl-001", "2026-11-02T10:00:00Z", "tenant-1", "branch-1",
                "ROLE_CHANGE", "ROLE_ELEVATED", "SUCCESS",
                "owner-1", "owner@pharmacy.local", "PHARMACY_OWNER",
                "DEV-POS-01", "192.168.1.10", "staff-101", "Rahim Cashier",
                "Promoted to Senior Cashier", "সিনিয়র ক্যাশিয়ার পদে পদোন্নতি দেওয়া হয়েছে"
        ));

        sampleEvents.add(new PosStaffAuditTimelineClient.TimelineItem(
                "tl-002", "2026-11-02T10:30:00Z", "tenant-1", "branch-1",
                "PERMISSION_DENIAL", "DRAWER_ACCESS_DENIED", "DENIED",
                "staff-102", "karim@pharmacy.local", "CASHIER",
                "DEV-POS-02", "192.168.1.12", null, null,
                "Unauthorized drawer access attempt", "অননুমোদিত ড্রয়ার অ্যাক্সেস প্রচেষ্টা"
        ));

        sampleEvents.add(new PosStaffAuditTimelineClient.TimelineItem(
                "tl-003", "2026-11-02T11:00:00Z", "tenant-1", "branch-2",
                "REFUND", "REFUND_AUTHORIZED", "SUCCESS",
                "mgr-2", "mgr2@pharmacy.local", "BRANCH_MANAGER",
                "DEV-POS-05", "192.168.2.10", "staff-103", "Fatima",
                "Refund BDT 450 approved", "৪৫০ টাকা রিফান্ড অনুমোদন"
        ));
    }

    @Test
    @DisplayName("Owner can inspect all branch timeline events")
    void testOwnerQueryAllEventsSuccess() {
        var result = client.filterTimeline(
                sampleEvents,
                "PHARMACY_OWNER",
                "tenant-1",
                "branch-1",
                "tenant-1",
                null,
                null,
                null,
                null
        );

        assertEquals(PosStaffAuditTimelineClient.UiStatus.SUCCESS, result.uiStatus);
        assertEquals(3, result.items.size());
    }

    @Test
    @DisplayName("Filter timeline by event category e.g. PERMISSION_DENIAL")
    void testFilterByCategory() {
        var result = client.filterTimeline(
                sampleEvents,
                "PHARMACY_OWNER",
                "tenant-1",
                "branch-1",
                "tenant-1",
                null,
                null,
                "PERMISSION_DENIAL",
                null
        );

        assertEquals(PosStaffAuditTimelineClient.UiStatus.SUCCESS, result.uiStatus);
        assertEquals(1, result.items.size());
        assertEquals("DRAWER_ACCESS_DENIED", result.items.get(0).action);
        assertEquals("DEV-POS-02", result.items.get(0).deviceId);
    }

    @Test
    @DisplayName("Branch Manager is restricted strictly to own branch")
    void testBranchManagerRestrictedToOwnBranch() {
        var result = client.filterTimeline(
                sampleEvents,
                "BRANCH_MANAGER",
                "tenant-1",
                "branch-1",
                "tenant-1",
                "branch-1",
                null,
                null,
                null
        );

        assertEquals(PosStaffAuditTimelineClient.UiStatus.SUCCESS, result.uiStatus);
        assertEquals(2, result.items.size());
        assertTrue(result.items.stream().allMatch(i -> "branch-1".equals(i.branchId)));
    }

    @Test
    @DisplayName("Branch Manager querying foreign branch is rejected")
    void testBranchManagerCrossBranchRejected() {
        var result = client.filterTimeline(
                sampleEvents,
                "BRANCH_MANAGER",
                "tenant-1",
                "branch-1",
                "tenant-1",
                "branch-2",
                null,
                null,
                null
        );

        assertEquals(PosStaffAuditTimelineClient.UiStatus.PERMISSION_DENIED, result.uiStatus);
        assertEquals("ERR_CROSS_BRANCH_VIOLATION", result.errorCode);
    }

    @Test
    @DisplayName("Cashier access to staff timeline is denied")
    void testCashierAccessDenied() {
        var result = client.filterTimeline(
                sampleEvents,
                "CASHIER",
                "tenant-1",
                "branch-1",
                "tenant-1",
                "branch-1",
                null,
                null,
                null
        );

        assertEquals(PosStaffAuditTimelineClient.UiStatus.PERMISSION_DENIED, result.uiStatus);
        assertEquals("ERR_STAFF_ROLE_DENIED", result.errorCode);
    }

    @Test
    @DisplayName("Cross-tenant timeline query is forbidden")
    void testCrossTenantForbidden() {
        var result = client.filterTimeline(
                sampleEvents,
                "PHARMACY_OWNER",
                "tenant-1",
                "branch-1",
                "tenant-999",
                null,
                null,
                null,
                null
        );

        assertEquals(PosStaffAuditTimelineClient.UiStatus.PERMISSION_DENIED, result.uiStatus);
        assertEquals("ERR_CROSS_TENANT_VIOLATION", result.errorCode);
    }
}
