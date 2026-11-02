package com.pharmacy.pos.staff;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosStaffLifecycleClient
 * Architecture Decision: ADR-0106
 * Phase 06: Milestone M03 - Staff Lifecycle Management (P106)
 *
 * Client-side integration for JavaFX Desktop POS managing the staff lifecycle:
 * create, update, deactivation, termination, password reset, MFA recovery,
 * session revocation, and offline device credential invalidation with translated EN/BN states.
 */
public class PosStaffLifecycleClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        OFFLINE,
        ERROR,
        PERMISSION_DENIED
    }

    public static final class StaffLifecycleRecord {
        public final String staffId;
        public final String tenantId;
        public final String branchId;
        public final String branchName;
        public final String username;
        public final String email;
        public final String fullName;
        public final String role;
        public final String mobileNumber;
        public final String pcbRegistrationNo;
        public final String lifecycleStatus;
        public final boolean isActive;
        public final boolean mfaEnabled;
        public final int activeSessionsCount;
        public final int activeLeasesCount;
        public final String createdAt;
        public final String deactivatedAt;
        public final String terminatedAt;
        public final String lastCredentialResetAt;
        public final String lastMfaResetAt;

        public StaffLifecycleRecord(
                String staffId,
                String tenantId,
                String branchId,
                String branchName,
                String username,
                String email,
                String fullName,
                String role,
                String mobileNumber,
                String pcbRegistrationNo,
                String lifecycleStatus,
                boolean isActive,
                boolean mfaEnabled,
                int activeSessionsCount,
                int activeLeasesCount,
                String createdAt,
                String deactivatedAt,
                String terminatedAt,
                String lastCredentialResetAt,
                String lastMfaResetAt) {
            this.staffId = staffId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.branchName = branchName;
            this.username = username;
            this.email = email;
            this.fullName = fullName;
            this.role = role != null ? role.toUpperCase() : "CASHIER";
            this.mobileNumber = mobileNumber != null ? mobileNumber : "";
            this.pcbRegistrationNo = pcbRegistrationNo != null ? pcbRegistrationNo : "";
            this.lifecycleStatus = lifecycleStatus != null ? lifecycleStatus.toUpperCase() : "ACTIVE";
            this.isActive = isActive;
            this.mfaEnabled = mfaEnabled;
            this.activeSessionsCount = activeSessionsCount;
            this.activeLeasesCount = activeLeasesCount;
            this.createdAt = createdAt;
            this.deactivatedAt = deactivatedAt;
            this.terminatedAt = terminatedAt;
            this.lastCredentialResetAt = lastCredentialResetAt;
            this.lastMfaResetAt = lastMfaResetAt;
        }
    }

    public static final class StaffLifecycleEventRecord {
        public final String eventId;
        public final String eventType;
        public final String performedBy;
        public final String reason;
        public final String createdAt;

        public StaffLifecycleEventRecord(
                String eventId,
                String eventType,
                String performedBy,
                String reason,
                String createdAt) {
            this.eventId = eventId;
            this.eventType = eventType;
            this.performedBy = performedBy;
            this.reason = reason;
            this.createdAt = createdAt;
        }
    }

    public static final class ValidationResult {
        public final boolean isValid;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public ValidationResult(boolean isValid, String errorCode, String messageEn, String messageBn) {
            this.isValid = isValid;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, "", "", "");
        }

        public static ValidationResult fail(String code, String en, String bn) {
            return new ValidationResult(false, code, en, bn);
        }
    }

    public static final class StaffLifecycleViewState {
        public final UiStatus uiStatus;
        public final StaffLifecycleRecord record;
        public final List<StaffLifecycleEventRecord> eventHistory;
        public final String errorMessageEn;
        public final String errorMessageBn;
        public final boolean isOffline;

        public StaffLifecycleViewState(
                UiStatus uiStatus,
                StaffLifecycleRecord record,
                List<StaffLifecycleEventRecord> eventHistory,
                String errorMessageEn,
                String errorMessageBn,
                boolean isOffline) {
            this.uiStatus = uiStatus;
            this.record = record;
            this.eventHistory = eventHistory != null ? Collections.unmodifiableList(eventHistory) : Collections.emptyList();
            this.errorMessageEn = errorMessageEn;
            this.errorMessageBn = errorMessageBn;
            this.isOffline = isOffline;
        }
    }

    private final Map<String, StaffLifecycleRecord> localCache = new ConcurrentHashMap<>();
    private final Map<String, List<StaffLifecycleEventRecord>> eventCache = new ConcurrentHashMap<>();

    public ValidationResult validateDirectCreation(
            String tenantId,
            String username,
            String email,
            String fullName,
            String role,
            String actorRole) {
        String cleanActorRole = actorRole != null ? actorRole.toUpperCase() : "";
        if (!isSuperUserOrOwner(cleanActorRole)) {
            return ValidationResult.fail(
                    "ERR_PERMISSION_DENIED",
                    "Only pharmacy owners or administrators can provision new staff accounts.",
                    "শুধুমাত্র ফার্মেসি মালিক বা অ্যাডমিন নতুন স্টাফ অ্যাকাউন্ট তৈরি করতে পারেন।"
            );
        }

        if (tenantId == null || tenantId.trim().isEmpty()) {
            return ValidationResult.fail("ERR_INVALID_TENANT", "Tenant ID is required.", "টেন্যান্ট আইডি আবশ্যক।");
        }
        if (username == null || username.trim().length() < 3) {
            return ValidationResult.fail("ERR_INVALID_USERNAME", "Username must be at least 3 characters.", "ইউজারনেম কমপক্ষে ৩ অক্ষরের হতে হবে।");
        }
        if (email == null || !email.contains("@")) {
            return ValidationResult.fail("ERR_INVALID_EMAIL", "A valid email address is required.", "সঠিক ইমেইল ঠিকানা প্রদান করুন।");
        }
        if (fullName == null || fullName.trim().length() < 2) {
            return ValidationResult.fail("ERR_INVALID_NAME", "Full name must be at least 2 characters.", "সম্পূর্ণ নাম কমপক্ষে ২ অক্ষরের হতে হবে।");
        }
        if (role == null || role.trim().isEmpty()) {
            return ValidationResult.fail("ERR_INVALID_ROLE", "Staff role is required.", "স্টাফের ভূমিকা (Role) আবশ্যক।");
        }

        return ValidationResult.ok();
    }

    public ValidationResult validateTermination(String staffId, String reason, String actorRole) {
        String cleanActorRole = actorRole != null ? actorRole.toUpperCase() : "";
        if (!isSuperUserOrOwner(cleanActorRole)) {
            return ValidationResult.fail(
                    "ERR_PERMISSION_DENIED",
                    "Only pharmacy owners or administrators can permanently terminate a staff member.",
                    "শুধুমাত্র ফার্মেসি মালিক বা অ্যাডমিন কোনো স্টাফের চাকরি সমাপ্ত বা টার্মিনেট করতে পারেন।"
            );
        }
        if (reason == null || reason.trim().length() < 4) {
            return ValidationResult.fail(
                    "ERR_TERMINATION_REASON_REQUIRED",
                    "A detailed termination reason (minimum 4 characters) is mandatory.",
                    "চাকরি সমাপ্তির বিস্তারিত কারণ (কমপক্ষে ৪ অক্ষর) উল্লেখ করা বাধ্যতামূলক।"
            );
        }
        return ValidationResult.ok();
    }

    public ValidationResult validateMfaRecovery(String actorRole) {
        String cleanActorRole = actorRole != null ? actorRole.toUpperCase() : "";
        if (!isSuperUserOrOwner(cleanActorRole)) {
            return ValidationResult.fail(
                    "ERR_PERMISSION_DENIED",
                    "Only pharmacy owners or administrators can perform administrative MFA recovery.",
                    "শুধুমাত্র ফার্মেসি মালিক বা অ্যাডমিন এমএফএ রিকভারি করতে পারেন।"
            );
        }
        return ValidationResult.ok();
    }

    public ValidationResult validateSessionRevocation(String actorRole) {
        String cleanActorRole = actorRole != null ? actorRole.toUpperCase() : "";
        if (!isSuperUserOrOwner(cleanActorRole)) {
            return ValidationResult.fail(
                    "ERR_PERMISSION_DENIED",
                    "Only pharmacy owners or administrators can revoke active staff sessions.",
                    "শুধুমাত্র ফার্মেসি মালিক বা অ্যাডমিন সক্রিয় স্টাফ সেশন বাতিল করতে পারেন।"
            );
        }
        return ValidationResult.ok();
    }

    public void cacheRecord(StaffLifecycleRecord record) {
        if (record != null && record.staffId != null) {
            localCache.put(record.staffId, record);
        }
    }

    public StaffLifecycleRecord getCachedRecord(String staffId) {
        return localCache.get(staffId);
    }

    public void recordEvent(String staffId, String eventType, String performedBy, String reason) {
        String eventId = "evt-" + UUID.randomUUID().toString().substring(0, 12);
        StaffLifecycleEventRecord event = new StaffLifecycleEventRecord(
                eventId,
                eventType,
                performedBy != null ? performedBy : "admin",
                reason != null ? reason : "",
                Instant.now().toString()
        );
        eventCache.computeIfAbsent(staffId, k -> new ArrayList<>()).add(0, event);
    }

    public List<StaffLifecycleEventRecord> getEventHistory(String staffId) {
        return eventCache.getOrDefault(staffId, Collections.emptyList());
    }

    public StaffLifecycleViewState applyDeactivation(StaffLifecycleRecord current, String reason, String actor) {
        if (current == null) {
            return new StaffLifecycleViewState(
                    UiStatus.ERROR, null, Collections.emptyList(),
                    "Staff record not found.", "স্টাফ রেকর্ড পাওয়া যায়নি।", false
            );
        }
        StaffLifecycleRecord updated = new StaffLifecycleRecord(
                current.staffId,
                current.tenantId,
                current.branchId,
                current.branchName,
                current.username,
                current.email,
                current.fullName,
                current.role,
                current.mobileNumber,
                current.pcbRegistrationNo,
                "DEACTIVATED",
                false,
                current.mfaEnabled,
                0, // sessions immediately revoked
                current.activeLeasesCount,
                current.createdAt,
                Instant.now().toString(),
                current.terminatedAt,
                current.lastCredentialResetAt,
                current.lastMfaResetAt
        );
        cacheRecord(updated);
        recordEvent(current.staffId, "DEACTIVATED", actor, reason);
        return new StaffLifecycleViewState(
                UiStatus.SUCCESS, updated, getEventHistory(current.staffId),
                "", "", false
        );
    }

    public StaffLifecycleViewState applyTermination(StaffLifecycleRecord current, String reason, String actor) {
        if (current == null) {
            return new StaffLifecycleViewState(
                    UiStatus.ERROR, null, Collections.emptyList(),
                    "Staff record not found.", "স্টাফ রেকর্ড পাওয়া যায়নি।", false
            );
        }
        StaffLifecycleRecord updated = new StaffLifecycleRecord(
                current.staffId,
                current.tenantId,
                current.branchId,
                current.branchName,
                current.username,
                current.email,
                current.fullName,
                current.role,
                current.mobileNumber,
                current.pcbRegistrationNo,
                "TERMINATED",
                false,
                false, // MFA disabled
                0, // sessions revoked
                0, // offline leases revoked
                current.createdAt,
                current.deactivatedAt,
                Instant.now().toString(),
                current.lastCredentialResetAt,
                current.lastMfaResetAt
        );
        cacheRecord(updated);
        recordEvent(current.staffId, "TERMINATED", actor, reason);
        return new StaffLifecycleViewState(
                UiStatus.SUCCESS, updated, getEventHistory(current.staffId),
                "", "", false
        );
    }

    public StaffLifecycleViewState toOfflineState(String staffId) {
        StaffLifecycleRecord cached = getCachedRecord(staffId);
        if (cached != null) {
            return new StaffLifecycleViewState(
                    UiStatus.OFFLINE,
                    cached,
                    getEventHistory(staffId),
                    "Operating in offline mode. Viewing cached staff lifecycle profile.",
                    "অফলাইন মোড সক্রিয়। ক্যাশ করা স্টাফ প্রোফাইল প্রদর্শিত হচ্ছে।",
                    true
            );
        }
        return new StaffLifecycleViewState(
                UiStatus.EMPTY,
                null,
                Collections.emptyList(),
                "No cached staff lifecycle data available offline.",
                "অফলাইনে প্রদর্শনের জন্য কোনো ক্যাশ করা স্টাফ ডেটা নেই।",
                true
        );
    }

    private boolean isSuperUserOrOwner(String role) {
        return "SUPERADMIN".equals(role)
                || "SUPER_ADMIN".equals(role)
                || "PLATFORM_SUPER_ADMIN".equals(role)
                || "PLATFORM_OPERATOR".equals(role)
                || "PHARMACY_OWNER".equals(role)
                || "TENANT_OWNER".equals(role);
    }
}

