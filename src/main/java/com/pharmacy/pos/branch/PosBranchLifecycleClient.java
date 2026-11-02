package com.pharmacy.pos.branch;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosBranchLifecycleClient
 * Architecture Decision: ADR-0107
 * Phase 06: Milestone M03 - Branch Lifecycle Management (P107)
 *
 * Client-side integration for JavaFX Desktop POS managing the physical branch lifecycle:
 * branch store creation, updates, temporary suspension, permanent closure,
 * offline lease revocation, and historic transaction & inventory trail preservation.
 */
public class PosBranchLifecycleClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        OFFLINE,
        ERROR,
        PERMISSION_DENIED
    }

    public static final class BranchLifecycleRecord {
        public final String branchId;
        public final String tenantId;
        public final String name;
        public final String branchCode;
        public final String licenseNumber;
        public final String address;
        public final String phoneNumber;
        public final String email;
        public final String managerName;
        public final boolean isMainBranch;
        public final String status;
        public final boolean isActive;
        public final boolean isOverQuotaLocked;
        public final int activeStaffCount;
        public final int activeDevicesCount;
        public final int historicalInvoicesCount;
        public final int historicalStockBatchesCount;
        public final String createdAt;
        public final String closedAt;
        public final String closureReason;

        public BranchLifecycleRecord(
                String branchId,
                String tenantId,
                String name,
                String branchCode,
                String licenseNumber,
                String address,
                String phoneNumber,
                String email,
                String managerName,
                boolean isMainBranch,
                String status,
                boolean isActive,
                boolean isOverQuotaLocked,
                int activeStaffCount,
                int activeDevicesCount,
                int historicalInvoicesCount,
                int historicalStockBatchesCount,
                String createdAt,
                String closedAt,
                String closureReason) {
            this.branchId = branchId;
            this.tenantId = tenantId;
            this.name = name;
            this.branchCode = branchCode != null ? branchCode : "";
            this.licenseNumber = licenseNumber != null ? licenseNumber : "";
            this.address = address != null ? address : "";
            this.phoneNumber = phoneNumber != null ? phoneNumber : "";
            this.email = email;
            this.managerName = managerName != null ? managerName : "";
            this.isMainBranch = isMainBranch;
            this.status = status != null ? status.toUpperCase() : "ACTIVE";
            this.isActive = isActive;
            this.isOverQuotaLocked = isOverQuotaLocked;
            this.activeStaffCount = activeStaffCount;
            this.activeDevicesCount = activeDevicesCount;
            this.historicalInvoicesCount = historicalInvoicesCount;
            this.historicalStockBatchesCount = historicalStockBatchesCount;
            this.createdAt = createdAt;
            this.closedAt = closedAt;
            this.closureReason = closureReason;
        }
    }

    public static final class BranchLifecycleEventRecord {
        public final String eventId;
        public final String eventType;
        public final String performedBy;
        public final String reason;
        public final String createdAt;

        public BranchLifecycleEventRecord(
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

    public static final class BranchLifecycleViewState {
        public final UiStatus uiStatus;
        public final BranchLifecycleRecord record;
        public final List<BranchLifecycleEventRecord> eventHistory;
        public final String errorMessageEn;
        public final String errorMessageBn;
        public final boolean isOffline;

        public BranchLifecycleViewState(
                UiStatus uiStatus,
                BranchLifecycleRecord record,
                List<BranchLifecycleEventRecord> eventHistory,
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

    private final Map<String, BranchLifecycleRecord> localCache = new ConcurrentHashMap<>();
    private final Map<String, List<BranchLifecycleEventRecord>> eventCache = new ConcurrentHashMap<>();

    public ValidationResult validateBranchCreation(
            String tenantId,
            String name,
            String licenseNumber,
            String actorRole) {
        String cleanActorRole = actorRole != null ? actorRole.toUpperCase() : "";
        if (!isSuperUserOrOwner(cleanActorRole)) {
            return ValidationResult.fail(
                    "ERR_PERMISSION_DENIED",
                    "Only pharmacy owners or administrators can create new branches.",
                    "শুধুমাত্র ফার্মেসি মালিক বা অ্যাডমিন নতুন ব্রাঞ্চ তৈরি করতে পারেন।"
            );
        }
        if (tenantId == null || tenantId.trim().isEmpty()) {
            return ValidationResult.fail("ERR_INVALID_TENANT", "Tenant ID is required.", "টেন্যান্ট আইডি আবশ্যক।");
        }
        if (name == null || name.trim().length() < 2) {
            return ValidationResult.fail("ERR_INVALID_BRANCH_NAME", "Branch name must be at least 2 characters.", "ব্রাঞ্চের নাম কমপক্ষে ২ অক্ষরের হতে হবে।");
        }
        if (licenseNumber == null || licenseNumber.trim().length() < 3) {
            return ValidationResult.fail("ERR_INVALID_LICENSE", "DGDA drug license number must be at least 3 characters.", "ডিজিডিএ ড্রাগ লাইসেন্স নম্বর আবশ্যক।");
        }
        return ValidationResult.ok();
    }

    public ValidationResult validateBranchClosure(
            String branchId,
            String reason,
            String actorRole) {
        String cleanActorRole = actorRole != null ? actorRole.toUpperCase() : "";
        if (!isSuperUserOrOwner(cleanActorRole)) {
            return ValidationResult.fail(
                    "ERR_PERMISSION_DENIED",
                    "Only pharmacy owners or administrators can close a branch store.",
                    "শুধুমাত্র ফার্মেসি মালিক বা অ্যাডমিন কোনো ব্রাঞ্চ বন্ধ করতে পারেন।"
            );
        }
        if (reason == null || reason.trim().length() < 4) {
            return ValidationResult.fail(
                    "ERR_CLOSURE_REASON_REQUIRED",
                    "A detailed closure reason (minimum 4 characters) is mandatory.",
                    "ব্রাঞ্চ বন্ধের বিস্তারিত কারণ (কমপক্ষে ৪ অক্ষর) উল্লেখ করা বাধ্যতামূলক।"
            );
        }
        return ValidationResult.ok();
    }

    public void cacheRecord(BranchLifecycleRecord record) {
        if (record != null && record.branchId != null) {
            localCache.put(record.branchId, record);
        }
    }

    public BranchLifecycleRecord getCachedRecord(String branchId) {
        return localCache.get(branchId);
    }

    public void recordEvent(String branchId, String eventType, String performedBy, String reason) {
        String eventId = "evt-br-" + UUID.randomUUID().toString().substring(0, 12);
        BranchLifecycleEventRecord event = new BranchLifecycleEventRecord(
                eventId,
                eventType,
                performedBy != null ? performedBy : "admin",
                reason != null ? reason : "",
                Instant.now().toString()
        );
        eventCache.computeIfAbsent(branchId, k -> new ArrayList<>()).add(0, event);
    }

    public List<BranchLifecycleEventRecord> getEventHistory(String branchId) {
        return eventCache.getOrDefault(branchId, Collections.emptyList());
    }

    public BranchLifecycleViewState applySuspension(BranchLifecycleRecord current, String reason, String actor) {
        if (current == null) {
            return new BranchLifecycleViewState(
                    UiStatus.ERROR, null, Collections.emptyList(),
                    "Branch not found.", "ব্রাঞ্চ পাওয়া যায়নি।", false
            );
        }
        BranchLifecycleRecord updated = new BranchLifecycleRecord(
                current.branchId,
                current.tenantId,
                current.name,
                current.branchCode,
                current.licenseNumber,
                current.address,
                current.phoneNumber,
                current.email,
                current.managerName,
                current.isMainBranch,
                "SUSPENDED",
                false,
                current.isOverQuotaLocked,
                current.activeStaffCount,
                0, // offline leases revoked
                current.historicalInvoicesCount,
                current.historicalStockBatchesCount,
                current.createdAt,
                current.closedAt,
                current.closureReason
        );
        cacheRecord(updated);
        recordEvent(current.branchId, "SUSPENDED", actor, reason);
        return new BranchLifecycleViewState(
                UiStatus.SUCCESS, updated, getEventHistory(current.branchId),
                "", "", false
        );
    }

    public BranchLifecycleViewState applyClosure(BranchLifecycleRecord current, String reason, String actor) {
        if (current == null) {
            return new BranchLifecycleViewState(
                    UiStatus.ERROR, null, Collections.emptyList(),
                    "Branch not found.", "ব্রাঞ্চ পাওয়া যায়নি।", false
            );
        }
        BranchLifecycleRecord updated = new BranchLifecycleRecord(
                current.branchId,
                current.tenantId,
                current.name,
                current.branchCode,
                current.licenseNumber,
                current.address,
                current.phoneNumber,
                current.email,
                current.managerName,
                false,
                "CLOSED",
                false,
                false,
                0, // staff detached
                0, // device leases revoked
                current.historicalInvoicesCount, // preserved
                current.historicalStockBatchesCount, // preserved
                current.createdAt,
                Instant.now().toString(),
                reason
        );
        cacheRecord(updated);
        recordEvent(current.branchId, "CLOSED", actor, reason);
        return new BranchLifecycleViewState(
                UiStatus.SUCCESS, updated, getEventHistory(current.branchId),
                "", "", false
        );
    }

    public BranchLifecycleViewState toOfflineState(String branchId) {
        BranchLifecycleRecord cached = getCachedRecord(branchId);
        if (cached != null) {
            return new BranchLifecycleViewState(
                    UiStatus.OFFLINE,
                    cached,
                    getEventHistory(branchId),
                    "Operating in offline mode. Viewing cached branch store profile.",
                    "অফলাইন মোড সক্রিয়। ক্যাশ করা ব্রাঞ্চ প্রোফাইল প্রদর্শিত হচ্ছে।",
                    true
            );
        }
        return new BranchLifecycleViewState(
                UiStatus.EMPTY,
                null,
                Collections.emptyList(),
                "No cached branch data available offline.",
                "অফলাইনে প্রদর্শনের জন্য কোনো ক্যাশ করা ব্রাঞ্চ ডেটা নেই।",
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
