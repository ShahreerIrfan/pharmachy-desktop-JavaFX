package com.pharmacy.pos.audit;

import java.util.*;
import java.util.stream.Collectors;

/**
 * PosStaffAuditTimelineClient
 * Architecture Decision: ADR-0118 (P118).
 * Phase 06: Milestone M03 - Offline-First Desktop POS & Owner Visibility.
 *
 * Exposes a chronological audit timeline for role modifications, permission denials,
 * data exports, refunds, approvals, and offline events with actor and device attribution.
 */
public class PosStaffAuditTimelineClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class TimelineItem {
        public final String timelineId;
        public final String timestamp;
        public final String tenantId;
        public final String branchId;
        public final String eventCategory; // ROLE_CHANGE, PERMISSION_DENIAL, DATA_EXPORT, REFUND, APPROVAL, OFFLINE_EVENT
        public final String action;
        public final String status; // SUCCESS, DENIED, ERROR
        public final String actorId;
        public final String actorEmail;
        public final String actorRole;
        public final String deviceId;
        public final String ipAddress;
        public final String targetStaffId;
        public final String targetStaffName;
        public final String summaryEn;
        public final String summaryBn;

        public TimelineItem(
                String timelineId,
                String timestamp,
                String tenantId,
                String branchId,
                String eventCategory,
                String action,
                String status,
                String actorId,
                String actorEmail,
                String actorRole,
                String deviceId,
                String ipAddress,
                String targetStaffId,
                String targetStaffName,
                String summaryEn,
                String summaryBn) {
            this.timelineId = timelineId;
            this.timestamp = timestamp;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.eventCategory = eventCategory;
            this.action = action;
            this.status = status;
            this.actorId = actorId;
            this.actorEmail = actorEmail;
            this.actorRole = actorRole;
            this.deviceId = deviceId;
            this.ipAddress = ipAddress;
            this.targetStaffId = targetStaffId;
            this.targetStaffName = targetStaffName;
            this.summaryEn = summaryEn;
            this.summaryBn = summaryBn;
        }
    }

    public static final class TimelineQueryResult {
        public final UiStatus uiStatus;
        public final List<TimelineItem> items;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public TimelineQueryResult(UiStatus uiStatus, List<TimelineItem> items, String errorCode, String messageEn, String messageBn) {
            this.uiStatus = uiStatus;
            this.items = items != null ? items : Collections.emptyList();
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static TimelineQueryResult success(List<TimelineItem> items) {
            if (items == null || items.isEmpty()) {
                return new TimelineQueryResult(
                        UiStatus.EMPTY,
                        Collections.emptyList(),
                        null,
                        "No audit timeline events found for this filter.",
                        "এই ফিল্টারে কোনো অডিট টাইমলাইন পাওয়া যায়নি।"
                );
            }
            return new TimelineQueryResult(
                    UiStatus.SUCCESS,
                    items,
                    null,
                    "Loaded " + items.size() + " timeline event(s).",
                    items.size() + " টি অডিট ইভেন্ট পাওয়া গেছে।"
            );
        }

        public static TimelineQueryResult error(UiStatus status, String code, String msgEn, String msgBn) {
            return new TimelineQueryResult(status, Collections.emptyList(), code, msgEn, msgBn);
        }
    }

    private static final Set<String> AUTHORIZED_ROLES = Set.of(
            "PHARMACY_OWNER",
            "OWNER",
            "TENANT_OWNER",
            "BRANCH_MANAGER",
            "MANAGER",
            "AUDITOR",
            "SUPERADMIN"
    );

    /**
     * Filters timeline events based on caller role, branch, staff, and category.
     */
    public TimelineQueryResult filterTimeline(
            List<TimelineItem> sourceItems,
            String callerRole,
            String callerTenantId,
            String callerBranchId,
            String targetTenantId,
            String targetBranchId,
            String filterStaffId,
            String filterCategory,
            String filterDeviceId) {

        // 1. Role Authorization
        if (callerRole == null || !AUTHORIZED_ROLES.contains(callerRole.toUpperCase())) {
            return TimelineQueryResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_STAFF_ROLE_DENIED",
                    "Only Owners, Managers, and Auditors may access the staff audit timeline.",
                    "শুধুমাত্র মালিক, শাখা ব্যবস্থাপক এবং অডিটরগণ স্টাফ অডিট টাইমলাইন দেখতে পারেন।"
            );
        }

        // 2. Cross-Tenant Check
        if (!Objects.equals(callerTenantId, targetTenantId) && !"SUPERADMIN".equalsIgnoreCase(callerRole)) {
            return TimelineQueryResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_CROSS_TENANT_VIOLATION",
                    "Cross-tenant timeline inspection is strictly forbidden.",
                    "অন্য টেন্যান্টের অডিট টাইমলাইন দেখতে পারবেন না।"
            );
        }

        // 3. Branch Manager Scoping
        boolean isManager = "BRANCH_MANAGER".equalsIgnoreCase(callerRole) || "MANAGER".equalsIgnoreCase(callerRole);
        boolean isOwner = "PHARMACY_OWNER".equalsIgnoreCase(callerRole) || "SUPERADMIN".equalsIgnoreCase(callerRole);

        if (isManager && !isOwner) {
            if (targetBranchId != null && !Objects.equals(callerBranchId, targetBranchId)) {
                return TimelineQueryResult.error(
                        UiStatus.PERMISSION_DENIED,
                        "ERR_CROSS_BRANCH_VIOLATION",
                        "Branch managers cannot inspect audit logs outside their assigned branch.",
                        "শাখা ব্যবস্থাপক অন্য শাখার অডিট রেকর্ড দেখতে পারেন না।"
                );
            }
        }

        String effectiveBranch = isManager ? callerBranchId : targetBranchId;

        // 4. Filtering
        List<TimelineItem> filtered = sourceItems.stream()
                .filter(item -> Objects.equals(item.tenantId, targetTenantId))
                .filter(item -> effectiveBranch == null || Objects.equals(item.branchId, effectiveBranch))
                .filter(item -> filterStaffId == null || Objects.equals(item.actorId, filterStaffId) || Objects.equals(item.targetStaffId, filterStaffId))
                .filter(item -> filterCategory == null || Objects.equals(item.eventCategory, filterCategory))
                .filter(item -> filterDeviceId == null || Objects.equals(item.deviceId, filterDeviceId))
                .collect(Collectors.toList());

        return TimelineQueryResult.success(filtered);
    }
}
