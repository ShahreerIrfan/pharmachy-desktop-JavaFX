package com.pharmacy.pos.shift;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosShiftScheduleClientTest
 * Verifies staff shift assignments, zero-collision overlap enforcement,
 * attendance boundaries, and offline caching for Desktop POS (ADR-0109 / P109).
 */
public class PosShiftScheduleClientTest {

    private PosShiftScheduleClient client;
    private final String tenantId = "tn-pop-dhk";
    private final String branchA = "br-dhanmondi";
    private final String branchB = "br-gulshan";
    private final String staffUserId = "usr-cashier-01";
    private final String staffEmail = "cashier_rahim@popular.local";

    @BeforeEach
    public void setUp() {
        client = new PosShiftScheduleClient("http://localhost:8000/api/v2");
    }

    @Test
    @DisplayName("P109: Branch Manager successfully schedules Morning Cashier shift")
    public void testCreateShiftSchedule_Success() {
        Instant start = Instant.now().plus(Duration.ofHours(2));
        Instant end = start.plus(Duration.ofHours(8));

        PosShiftScheduleClient.ClientResult<PosShiftScheduleClient.ShiftRecord> result = client.createShiftSchedule(
                tenantId,
                branchA,
                staffUserId,
                staffEmail,
                "Morning Cashier Counter 1",
                PosShiftScheduleClient.ShiftType.MORNING,
                start,
                end,
                15,
                "Priority checkout counter",
                "BRANCH_MANAGER",
                branchA
        );

        assertTrue(result.isSuccess());
        assertEquals(PosShiftScheduleClient.UiStatus.SUCCESS, result.uiStatus);
        assertNotNull(result.data.scheduleId);
        assertEquals(PosShiftScheduleClient.ShiftStatus.SCHEDULED, result.data.status);
        assertEquals(PosShiftScheduleClient.AttendanceStatus.PENDING, result.data.attendanceStatus);
        assertNotNull(result.data.displayStartLocal);
        assertNotNull(result.data.displayEndLocal);
    }

    @Test
    @DisplayName("P109: Inverted interval (end before start) or too short shift is rejected")
    public void testCreateShiftSchedule_InvalidInterval() {
        Instant start = Instant.now().plus(Duration.ofHours(2));
        Instant badEnd = start.minus(Duration.ofHours(1));

        PosShiftScheduleClient.ClientResult<PosShiftScheduleClient.ShiftRecord> inverted = client.createShiftSchedule(
                tenantId,
                branchA,
                staffUserId,
                staffEmail,
                "Bad Shift",
                PosShiftScheduleClient.ShiftType.CUSTOM,
                start,
                badEnd,
                15,
                "Notes",
                "BRANCH_MANAGER",
                branchA
        );
        assertEquals(PosShiftScheduleClient.UiStatus.ERROR, inverted.uiStatus);
        assertEquals("ERR_INVALID_SHIFT_INTERVAL", inverted.errorCode);

        // Too short (< 30 minutes)
        Instant shortEnd = start.plus(Duration.ofMinutes(15));
        PosShiftScheduleClient.ClientResult<PosShiftScheduleClient.ShiftRecord> tooShort = client.createShiftSchedule(
                tenantId,
                branchA,
                staffUserId,
                staffEmail,
                "Short Shift",
                PosShiftScheduleClient.ShiftType.CUSTOM,
                start,
                shortEnd,
                15,
                "Notes",
                "BRANCH_MANAGER",
                branchA
        );
        assertEquals(PosShiftScheduleClient.UiStatus.ERROR, tooShort.uiStatus);
        assertEquals("ERR_SHIFT_TOO_SHORT", tooShort.errorCode);
    }

    @Test
    @DisplayName("P109: Zero Collision Invariant: Overlapping shift for same staff is strictly blocked")
    public void testCreateShiftSchedule_ShiftOverlapCollisionBlocked() {
        Instant start1 = Instant.now().plus(Duration.ofHours(2));
        Instant end1 = start1.plus(Duration.ofHours(6));

        // Schedule first shift
        PosShiftScheduleClient.ClientResult<PosShiftScheduleClient.ShiftRecord> first = client.createShiftSchedule(
                tenantId,
                branchA,
                staffUserId,
                staffEmail,
                "First Shift",
                PosShiftScheduleClient.ShiftType.MORNING,
                start1,
                end1,
                15,
                "Shift 1",
                "BRANCH_MANAGER",
                branchA
        );
        assertTrue(first.isSuccess());

        // Attempt second shift overlapping with first shift (start1 + 2 hours to end1 + 2 hours)
        Instant start2 = start1.plus(Duration.ofHours(2));
        Instant end2 = end1.plus(Duration.ofHours(2));

        PosShiftScheduleClient.ClientResult<PosShiftScheduleClient.ShiftRecord> collision = client.createShiftSchedule(
                tenantId,
                branchA,
                staffUserId,
                staffEmail,
                "Colliding Shift",
                PosShiftScheduleClient.ShiftType.EVENING,
                start2,
                end2,
                15,
                "Shift 2 Overlap",
                "BRANCH_MANAGER",
                branchA
        );

        assertEquals(PosShiftScheduleClient.UiStatus.ERROR, collision.uiStatus);
        assertEquals("ERR_SHIFT_OVERLAP_COLLISION", collision.errorCode);
        assertTrue(collision.messageBn.contains("ইতিমধ্যে অন্য একটি নির্ধারিত শিফট"));
    }

    @Test
    @DisplayName("P109: Frontline Cashier denied creating shift schedules (RBAC gate)")
    public void testCreateShiftSchedule_FrontlineStaffDenied() {
        Instant start = Instant.now().plus(Duration.ofHours(1));
        Instant end = start.plus(Duration.ofHours(8));

        PosShiftScheduleClient.ClientResult<PosShiftScheduleClient.ShiftRecord> res = client.createShiftSchedule(
                tenantId,
                branchA,
                staffUserId,
                staffEmail,
                "Unauthorized Shift",
                PosShiftScheduleClient.ShiftType.MORNING,
                start,
                end,
                15,
                "",
                "CASHIER",
                branchA
        );

        assertEquals(PosShiftScheduleClient.UiStatus.PERMISSION_DENIED, res.uiStatus);
        assertEquals("ERR_PERMISSION_DENIED", res.errorCode);
    }

    @Test
    @DisplayName("P109: Branch Manager cannot schedule shifts for other branches")
    public void testCreateShiftSchedule_BranchManagerCrossBranchViolation() {
        Instant start = Instant.now().plus(Duration.ofHours(1));
        Instant end = start.plus(Duration.ofHours(8));

        // Manager of Branch B attempts to schedule for Branch A
        PosShiftScheduleClient.ClientResult<PosShiftScheduleClient.ShiftRecord> res = client.createShiftSchedule(
                tenantId,
                branchA,
                staffUserId,
                staffEmail,
                "Cross Branch Shift",
                PosShiftScheduleClient.ShiftType.MORNING,
                start,
                end,
                15,
                "",
                "BRANCH_MANAGER",
                branchB // Different branch
        );

        assertEquals(PosShiftScheduleClient.UiStatus.PERMISSION_DENIED, res.uiStatus);
        assertEquals("ERR_CROSS_BRANCH_VIOLATION", res.errorCode);
    }

    @Test
    @DisplayName("P109: Staff clock-in correctly marks attendance (ON_TIME or LATE)")
    public void testClockIn_OnTimeAndLateAttendance() {
        // Shift starting in future -> on-time clock-in
        Instant futureStart = Instant.now().plus(Duration.ofMinutes(10));
        Instant futureEnd = futureStart.plus(Duration.ofHours(8));

        PosShiftScheduleClient.ClientResult<PosShiftScheduleClient.ShiftRecord> sub = client.createShiftSchedule(
                tenantId,
                branchA,
                staffUserId,
                staffEmail,
                "Future Shift",
                PosShiftScheduleClient.ShiftType.MORNING,
                futureStart,
                futureEnd,
                15,
                "",
                "BRANCH_MANAGER",
                branchA
        );
        assertTrue(sub.isSuccess());

        PosShiftScheduleClient.ClientResult<PosShiftScheduleClient.ShiftRecord> clockInRes = client.clockIn(
                tenantId,
                sub.data.scheduleId,
                staffUserId,
                "CASHIER"
        );

        assertTrue(clockInRes.isSuccess());
        assertEquals(PosShiftScheduleClient.ShiftStatus.IN_PROGRESS, clockInRes.data.status);
        assertEquals(PosShiftScheduleClient.AttendanceStatus.ON_TIME, clockInRes.data.attendanceStatus);
        assertNotNull(clockInRes.data.clockInAt);
    }

    @Test
    @DisplayName("P109: Staff clock-out marks shift COMPLETED")
    public void testClockOut_Success() {
        Instant start = Instant.now().plus(Duration.ofMinutes(5));
        Instant end = start.plus(Duration.ofHours(8));

        PosShiftScheduleClient.ClientResult<PosShiftScheduleClient.ShiftRecord> sub = client.createShiftSchedule(
                tenantId,
                branchA,
                staffUserId,
                staffEmail,
                "Active Shift",
                PosShiftScheduleClient.ShiftType.MORNING,
                start,
                end,
                15,
                "",
                "BRANCH_MANAGER",
                branchA
        );
        client.clockIn(tenantId, sub.data.scheduleId, staffUserId, "CASHIER");

        PosShiftScheduleClient.ClientResult<PosShiftScheduleClient.ShiftRecord> clockOutRes = client.clockOut(
                tenantId,
                sub.data.scheduleId,
                staffUserId,
                "CASHIER"
        );

        assertTrue(clockOutRes.isSuccess());
        assertEquals(PosShiftScheduleClient.ShiftStatus.COMPLETED, clockOutRes.data.status);
        assertNotNull(clockOutRes.data.clockOutAt);
    }

    @Test
    @DisplayName("P109: Offline POS queues shifts locally and allows personal roster view")
    public void testOfflineFallback_Queued() {
        client.setSimulatedOffline(true);

        Instant start = Instant.now().plus(Duration.ofHours(3));
        Instant end = start.plus(Duration.ofHours(8));

        PosShiftScheduleClient.ClientResult<PosShiftScheduleClient.ShiftRecord> sub = client.createShiftSchedule(
                tenantId,
                branchA,
                staffUserId,
                staffEmail,
                "Offline Shift",
                PosShiftScheduleClient.ShiftType.MORNING,
                start,
                end,
                15,
                "",
                "BRANCH_MANAGER",
                branchA
        );

        assertEquals(PosShiftScheduleClient.UiStatus.OFFLINE, sub.uiStatus);
        assertEquals("OFFLINE_QUEUED", sub.errorCode);

        // Fetch my shifts
        PosShiftScheduleClient.ClientResult<List<PosShiftScheduleClient.ShiftRecord>> myShifts = client.getMyShifts(tenantId, staffUserId);
        assertTrue(myShifts.isSuccess());
        assertEquals(1, myShifts.data.size());
    }
}
