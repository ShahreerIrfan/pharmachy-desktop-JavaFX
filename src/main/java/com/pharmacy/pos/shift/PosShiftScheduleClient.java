package com.pharmacy.pos.shift;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosShiftScheduleClient
 * Architecture Decision: ADR-0109
 * Phase 06: Milestone M03 - Shift Scheduling & Attendance Boundaries (P109)
 *
 * Client-side integration for JavaFX Desktop POS managing staff rosters,
 * zero-collision shift overlap enforcement, and clock-in/clock-out attendance.
 */
public class PosShiftScheduleClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        OFFLINE,
        ERROR,
        PERMISSION_DENIED
    }

    public enum ShiftType {
        MORNING,
        EVENING,
        NIGHT,
        FULL_DAY,
        CUSTOM
    }

    public enum ShiftStatus {
        SCHEDULED,
        CONFIRMED,
        IN_PROGRESS,
        COMPLETED,
        CANCELLED,
        NO_SHOW
    }

    public enum AttendanceStatus {
        PENDING,
        ON_TIME,
        LATE,
        EARLY_DEPARTURE,
        ABSENT
    }

    public static final class ShiftRecord {
        public final String scheduleId;
        public final String tenantId;
        public final String branchId;
        public final String staffUserId;
        public final String staffEmail;
        public final String shiftName;
        public final ShiftType shiftType;
        public final Instant startTime;
        public final Instant endTime;
        public final String displayStartLocal;
        public final String displayEndLocal;
        public final String timezoneName;
        public final ShiftStatus status;
        public final int gracePeriodMinutes;
        public final Instant clockInAt;
        public final Instant clockOutAt;
        public final AttendanceStatus attendanceStatus;
        public final String notes;
        public final String createdAt;

        public ShiftRecord(
                String scheduleId,
                String tenantId,
                String branchId,
                String staffUserId,
                String staffEmail,
                String shiftName,
                ShiftType shiftType,
                Instant startTime,
                Instant endTime,
                String displayStartLocal,
                String displayEndLocal,
                String timezoneName,
                ShiftStatus status,
                int gracePeriodMinutes,
                Instant clockInAt,
                Instant clockOutAt,
                AttendanceStatus attendanceStatus,
                String notes,
                String createdAt
        ) {
            this.scheduleId = scheduleId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.staffUserId = staffUserId;
            this.staffEmail = staffEmail;
            this.shiftName = shiftName;
            this.shiftType = shiftType;
            this.startTime = startTime;
            this.endTime = endTime;
            this.displayStartLocal = displayStartLocal;
            this.displayEndLocal = displayEndLocal;
            this.timezoneName = timezoneName;
            this.status = status;
            this.gracePeriodMinutes = gracePeriodMinutes;
            this.clockInAt = clockInAt;
            this.clockOutAt = clockOutAt;
            this.attendanceStatus = attendanceStatus;
            this.notes = notes;
            this.createdAt = createdAt;
        }
    }

    public static final class ClientResult<T> {
        public final UiStatus uiStatus;
        public final T data;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public ClientResult(UiStatus uiStatus, T data, String errorCode, String messageEn, String messageBn) {
            this.uiStatus = uiStatus;
            this.data = data;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public boolean isSuccess() {
            return uiStatus == UiStatus.SUCCESS;
        }
    }

    private final String baseUrl;
    private final Map<String, ShiftRecord> localCache = new ConcurrentHashMap<>();
    private boolean simulatedOffline = false;
    private static final ZoneId BD_ZONE = ZoneId.of("Asia/Dhaka");
    private static final DateTimeFormatter LOCAL_FMT = DateTimeFormatter.ofPattern("hh:mm a z");

    public PosShiftScheduleClient(String baseUrl) {
        this.baseUrl = baseUrl != null ? baseUrl : "http://localhost:8000/api/v2";
    }

    public void setSimulatedOffline(boolean offline) {
        this.simulatedOffline = offline;
    }

    private String formatLocal(Instant instant) {
        if (instant == null) return null;
        ZonedDateTime zdt = instant.atZone(BD_ZONE);
        return zdt.format(LOCAL_FMT);
    }

    /**
     * Schedules a new staff shift. Enforces interval validity, role authority, and zero-collision overlap rule.
     */
    public ClientResult<ShiftRecord> createShiftSchedule(
            String tenantId,
            String branchId,
            String staffUserId,
            String staffEmail,
            String shiftName,
            ShiftType shiftType,
            Instant startTime,
            Instant endTime,
            int gracePeriodMinutes,
            String notes,
            String actorRole,
            String actorBranchId
    ) {
        // 1. RBAC Guard: Only Owner, Superadmin, or Branch Manager
        String role = actorRole != null ? actorRole.toUpperCase() : "";
        if (!"SUPERADMIN".equals(role) && !"PHARMACY_OWNER".equals(role) && !"BRANCH_MANAGER".equals(role)) {
            return new ClientResult<>(
                    UiStatus.PERMISSION_DENIED,
                    null,
                    "ERR_PERMISSION_DENIED",
                    "Frontline staff cannot schedule or modify rosters.",
                    "শিফট শিডিউল তৈরি করার অনুমতি আপনার নেই।"
            );
        }

        // 2. Branch Manager boundary check
        if ("BRANCH_MANAGER".equals(role) && actorBranchId != null && !actorBranchId.equals(branchId)) {
            return new ClientResult<>(
                    UiStatus.PERMISSION_DENIED,
                    null,
                    "ERR_CROSS_BRANCH_VIOLATION",
                    "Branch Manager cannot schedule shifts for other branches.",
                    "ব্রাঞ্চ ম্যানেজার অন্য ব্রাঞ্চের জন্য শিফট নির্ধারণ করতে পারেন না।"
            );
        }

        // 3. Interval checks
        if (shiftName == null || shiftName.trim().length() < 2) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    null,
                    "INVALID_SHIFT_NAME",
                    "Shift name must be at least 2 characters.",
                    "শিফটের নাম কমপক্ষে ২ অক্ষরের হতে হবে।"
            );
        }

        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    null,
                    "ERR_INVALID_SHIFT_INTERVAL",
                    "Shift end time must be after start time.",
                    "শিফটের সমাপ্তি সময় অবশ্যই শুরুর সময়ের পরে হতে হবে।"
            );
        }

        Duration dur = Duration.between(startTime, endTime);
        if (dur.toMinutes() < 30) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    null,
                    "ERR_SHIFT_TOO_SHORT",
                    "Shift duration must be at least 30 minutes.",
                    "শিফটের স্থায়িত্ব কমপক্ষে ৩০ মিনিট হতে হবে।"
            );
        }

        // 4. Zero Collision Overlap Invariant
        for (ShiftRecord r : localCache.values()) {
            if (r.tenantId.equals(tenantId) && r.staffUserId.equals(staffUserId) && r.status != ShiftStatus.CANCELLED) {
                // (Existing Start < New End) AND (Existing End > New Start)
                if (r.startTime.isBefore(endTime) && r.endTime.isAfter(startTime)) {
                    return new ClientResult<>(
                            UiStatus.ERROR,
                            null,
                            "ERR_SHIFT_OVERLAP_COLLISION",
                            "Staff member already has an overlapping shift: " + r.shiftName,
                            "কর্মী ইতিমধ্যে অন্য একটি নির্ধারিত শিফটে অন্তর্ভুক্ত রয়েছেন।"
                    );
                }
            }
        }

        String schedId = "sched-" + UUID.randomUUID().toString().substring(0, 12);
        ShiftRecord record = new ShiftRecord(
                schedId,
                tenantId,
                branchId,
                staffUserId,
                staffEmail,
                shiftName.trim(),
                shiftType != null ? shiftType : ShiftType.MORNING,
                startTime,
                endTime,
                formatLocal(startTime),
                formatLocal(endTime),
                "Asia/Dhaka",
                ShiftStatus.SCHEDULED,
                gracePeriodMinutes > 0 ? gracePeriodMinutes : 15,
                null,
                null,
                AttendanceStatus.PENDING,
                notes != null ? notes.trim() : "",
                Instant.now().toString()
        );

        localCache.put(schedId, record);

        if (simulatedOffline) {
            return new ClientResult<>(
                    UiStatus.OFFLINE,
                    record,
                    "OFFLINE_QUEUED",
                    "POS is offline. Shift schedule cached locally.",
                    "পিওএস অফলাইনে আছে। শিফট শিডিউল লোকাল ক্যাশে জমা রাখা হয়েছে।"
            );
        }

        return new ClientResult<>(
                UiStatus.SUCCESS,
                record,
                null,
                "Shift scheduled successfully in branch display time.",
                "শিফট শিডিউল সফলভাবে সম্পন্ন হয়েছে।"
        );
    }

    /**
     * Clocks in staff member, evaluating attendance grace period (ON_TIME vs LATE).
     */
    public ClientResult<ShiftRecord> clockIn(
            String tenantId,
            String scheduleId,
            String actorUserId,
            String actorRole
    ) {
        ShiftRecord r = localCache.get(scheduleId);
        if (r == null) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    null,
                    "ERR_NOT_FOUND",
                    "Shift schedule not found.",
                    "শিফট শিডিউল খুঁজে পাওয়া যায়নি।"
            );
        }

        String role = actorRole != null ? actorRole.toUpperCase() : "";
        if (!r.staffUserId.equals(actorUserId) && !"BRANCH_MANAGER".equals(role)
                && !"PHARMACY_OWNER".equals(role) && !"SUPERADMIN".equals(role)) {
            return new ClientResult<>(
                    UiStatus.PERMISSION_DENIED,
                    null,
                    "ERR_CANNOT_CLOCK_IN_FOR_OTHERS",
                    "You can only clock in to your own assigned shift.",
                    "আপনি কেবল আপনার নিজের শিফটে ক্লক-ইন করতে পারবেন।"
            );
        }

        if (r.status == ShiftStatus.IN_PROGRESS) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    r,
                    "ALREADY_CLOCKED_IN",
                    "Shift is already in progress.",
                    "শিফট ইতিমধ্যে চলমান রয়েছে।"
            );
        }

        Instant now = Instant.now();
        Instant graceLimit = r.startTime.plus(Duration.ofMinutes(r.gracePeriodMinutes));
        AttendanceStatus attStatus = now.isAfter(graceLimit) ? AttendanceStatus.LATE : AttendanceStatus.ON_TIME;

        ShiftRecord updated = new ShiftRecord(
                r.scheduleId,
                r.tenantId,
                r.branchId,
                r.staffUserId,
                r.staffEmail,
                r.shiftName,
                r.shiftType,
                r.startTime,
                r.endTime,
                r.displayStartLocal,
                r.displayEndLocal,
                r.timezoneName,
                ShiftStatus.IN_PROGRESS,
                r.gracePeriodMinutes,
                now,
                r.clockOutAt,
                attStatus,
                r.notes,
                r.createdAt
        );

        localCache.put(scheduleId, updated);

        return new ClientResult<>(
                UiStatus.SUCCESS,
                updated,
                null,
                "Clocked in successfully (" + attStatus + ").",
                "ক্লক-ইন সম্পন্ন হয়েছে (" + attStatus + ")।"
        );
    }

    /**
     * Clocks out staff member, marking shift COMPLETED.
     */
    public ClientResult<ShiftRecord> clockOut(
            String tenantId,
            String scheduleId,
            String actorUserId,
            String actorRole
    ) {
        ShiftRecord r = localCache.get(scheduleId);
        if (r == null) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    null,
                    "ERR_NOT_FOUND",
                    "Shift schedule not found.",
                    "শিফট শিডিউল খুঁজে পাওয়া যায়নি।"
            );
        }

        String role = actorRole != null ? actorRole.toUpperCase() : "";
        if (!r.staffUserId.equals(actorUserId) && !"BRANCH_MANAGER".equals(role)
                && !"PHARMACY_OWNER".equals(role) && !"SUPERADMIN".equals(role)) {
            return new ClientResult<>(
                    UiStatus.PERMISSION_DENIED,
                    null,
                    "ERR_CANNOT_CLOCK_OUT_FOR_OTHERS",
                    "You can only clock out of your own assigned shift.",
                    "আপনি কেবল আপনার নিজের শিফটে ক্লক-আউট করতে পারবেন।"
            );
        }

        if (r.status != ShiftStatus.IN_PROGRESS) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    r,
                    "SHIFT_NOT_IN_PROGRESS",
                    "Shift is not currently in progress.",
                    "শিফট চলমান নেই।"
            );
        }

        Instant now = Instant.now();
        AttendanceStatus att = r.attendanceStatus;
        if (now.isBefore(r.endTime.minus(Duration.ofMinutes(15))) && att == AttendanceStatus.ON_TIME) {
            att = AttendanceStatus.EARLY_DEPARTURE;
        }

        ShiftRecord completed = new ShiftRecord(
                r.scheduleId,
                r.tenantId,
                r.branchId,
                r.staffUserId,
                r.staffEmail,
                r.shiftName,
                r.shiftType,
                r.startTime,
                r.endTime,
                r.displayStartLocal,
                r.displayEndLocal,
                r.timezoneName,
                ShiftStatus.COMPLETED,
                r.gracePeriodMinutes,
                r.clockInAt,
                now,
                att,
                r.notes,
                r.createdAt
        );

        localCache.put(scheduleId, completed);

        return new ClientResult<>(
                UiStatus.SUCCESS,
                completed,
                null,
                "Shift completed and clocked out.",
                "শিফট সম্পন্ন এবং ক্লক-আউট হয়েছে।"
        );
    }

    /**
     * Lists personal shifts for logged in staff member.
     */
    public ClientResult<List<ShiftRecord>> getMyShifts(String tenantId, String staffUserId) {
        List<ShiftRecord> list = new ArrayList<>();
        for (ShiftRecord r : localCache.values()) {
            if (r.tenantId.equals(tenantId) && r.staffUserId.equals(staffUserId)) {
                list.add(r);
            }
        }

        if (list.isEmpty()) {
            return new ClientResult<>(
                    UiStatus.EMPTY,
                    Collections.emptyList(),
                    null,
                    "No assigned shifts found.",
                    "কোনো নির্ধারিত শিফট পাওয়া যায়নি।"
            );
        }

        return new ClientResult<>(
                UiStatus.SUCCESS,
                list,
                null,
                "Retrieved " + list.size() + " shifts.",
                list.size() + "টি শিফট পাওয়া গেছে।"
        );
    }

    /**
     * Lists shifts for branch roster.
     */
    public ClientResult<List<ShiftRecord>> getBranchRoster(String tenantId, String branchId) {
        List<ShiftRecord> list = new ArrayList<>();
        for (ShiftRecord r : localCache.values()) {
            if (r.tenantId.equals(tenantId) && r.branchId.equals(branchId)) {
                list.add(r);
            }
        }

        if (list.isEmpty()) {
            return new ClientResult<>(
                    UiStatus.EMPTY,
                    Collections.emptyList(),
                    null,
                    "No shifts found for branch.",
                    "ব্রাঞ্চের জন্য কোনো শিফট পাওয়া যায়নি।"
            );
        }

        return new ClientResult<>(
                UiStatus.SUCCESS,
                list,
                null,
                "Retrieved " + list.size() + " shifts.",
                list.size() + "টি শিফট পাওয়া গেছে।"
        );
    }
}
