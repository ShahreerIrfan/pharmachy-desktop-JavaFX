package com.pharmacy.pos.security;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosActiveSessionsClient
 * Architecture Decision: ADR-0045, ADR-0112 (P112).
 * Phase 06: Milestone M03 - Offline-First Desktop POS & Owner Visibility.
 *
 * Client-side integration for JavaFX Desktop POS managing active user sessions,
 * multi-device visibility, instant online revocation, and role-based scoping (Owner/Manager/Staff).
 */
public class PosActiveSessionsClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        OFFLINE,
        ERROR,
        PERMISSION_DENIED
    }

    public enum ClientPlatform {
        DESKTOP_POS,
        MOBILE_MANAGER,
        WEB_ADMIN
    }

    public static final class ActiveSessionRecord {
        public final String sessionId;
        public final String userId;
        public final String userEmail;
        public final String userFullName;
        public final String userRole;
        public final String tenantId;
        public final String branchId;
        public final String deviceId;
        public final String deviceName;
        public final String clientPlatform;
        public final String ipAddress;
        public final boolean isActive;
        public final boolean isCurrent;
        public final String revocationReason;
        public final String revokedAt;
        public final String lastSeenAt;
        public final String createdAt;

        public ActiveSessionRecord(
                String sessionId,
                String userId,
                String userEmail,
                String userFullName,
                String userRole,
                String tenantId,
                String branchId,
                String deviceId,
                String deviceName,
                String clientPlatform,
                String ipAddress,
                boolean isActive,
                boolean isCurrent,
                String revocationReason,
                String revokedAt,
                String lastSeenAt,
                String createdAt
        ) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.userEmail = userEmail;
            this.userFullName = userFullName;
            this.userRole = userRole;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.clientPlatform = clientPlatform;
            this.ipAddress = ipAddress;
            this.isActive = isActive;
            this.isCurrent = isCurrent;
            this.revocationReason = revocationReason;
            this.revokedAt = revokedAt;
            this.lastSeenAt = lastSeenAt;
            this.createdAt = createdAt;
        }

        public String getDisplayStatus(String language) {
            if (!isActive) {
                return "bn".equalsIgnoreCase(language) ? "বাতিলকৃত" : "Revoked";
            }
            if (isCurrent) {
                return "bn".equalsIgnoreCase(language) ? "বর্তমান টার্মিনাল (সক্রিয়)" : "Current Terminal (Active)";
            }
            return "bn".equalsIgnoreCase(language) ? "সক্রিয়" : "Active";
        }
    }

    public static final class SessionRevocationResult {
        public final String sessionId;
        public final String status;
        public final boolean revoked;
        public final String messageEn;
        public final String messageBn;

        public SessionRevocationResult(
                String sessionId,
                String status,
                boolean revoked,
                String messageEn,
                String messageBn
        ) {
            this.sessionId = sessionId;
            this.status = status;
            this.revoked = revoked;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }
    }

    public static final class BulkRevocationResult {
        public final String status;
        public final int revokedCount;
        public final String targetUserId;
        public final String messageEn;
        public final String messageBn;

        public BulkRevocationResult(
                String status,
                int revokedCount,
                String targetUserId,
                String messageEn,
                String messageBn
        ) {
            this.status = status;
            this.revokedCount = revokedCount;
            this.targetUserId = targetUserId;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }
    }

    public static final class ActiveSessionsUiState {
        public final UiStatus uiStatus;
        public final String errorMessageEn;
        public final String errorMessageBn;
        public final List<ActiveSessionRecord> sessions;
        public final ActiveSessionRecord selectedSession;
        public final int offlineQueueCount;

        public ActiveSessionsUiState(
                UiStatus uiStatus,
                String errorMessageEn,
                String errorMessageBn,
                List<ActiveSessionRecord> sessions,
                ActiveSessionRecord selectedSession,
                int offlineQueueCount
        ) {
            this.uiStatus = uiStatus;
            this.errorMessageEn = errorMessageEn;
            this.errorMessageBn = errorMessageBn;
            this.sessions = sessions != null ? Collections.unmodifiableList(sessions) : Collections.emptyList();
            this.selectedSession = selectedSession;
            this.offlineQueueCount = offlineQueueCount;
        }
    }

    private final String baseUrl;
    private final String tenantId;
    private final String userRole;
    private final String userBranchId;
    private final String userId;
    private final String userEmail;
    private final String currentSessionId;

    private boolean isOffline = false;
    private final Map<String, ActiveSessionRecord> sessionCache = new ConcurrentHashMap<>();
    private final List<String> offlineRevocationQueue = Collections.synchronizedList(new ArrayList<>());

    public PosActiveSessionsClient(
            String baseUrl,
            String tenantId,
            String userRole,
            String userBranchId,
            String userId,
            String userEmail,
            String currentSessionId
    ) {
        this.baseUrl = baseUrl != null ? baseUrl : "http://localhost:8000/api/v2";
        this.tenantId = tenantId != null ? tenantId : "default-tenant";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userBranchId = userBranchId;
        this.userId = userId != null ? userId : "usr-anon";
        this.userEmail = userEmail != null ? userEmail : "anon@pos.local";
        this.currentSessionId = currentSessionId;
    }

    public boolean isOffline() {
        return isOffline;
    }

    public void setOffline(boolean offline) {
        this.isOffline = offline;
    }

    public void importSession(ActiveSessionRecord session) {
        if (session != null && session.sessionId != null) {
            sessionCache.put(session.sessionId, session);
        }
    }

    private boolean isSuperAdmin() {
        return "SUPERADMIN".equals(userRole) || "SUPER_ADMIN".equals(userRole) || "PLATFORM_OPERATOR".equals(userRole);
    }

    public boolean isOwnerOrSuperAdmin() {
        return isSuperAdmin() || "PHARMACY_OWNER".equals(userRole) || "TENANT_OWNER".equals(userRole);
    }

    public boolean isBranchManager() {
        return "BRANCH_MANAGER".equals(userRole) || "PHARMACY_MANAGER".equals(userRole);
    }

    private void assertRevocationAuthority(ActiveSessionRecord session) {
        // Strict cross-tenant check: non-superadmins can only manage their own tenant
        if (!isSuperAdmin() && !this.tenantId.equals(session.tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_VIOLATION: Cross-tenant session revocation is strictly forbidden.");
        }

        if (isOwnerOrSuperAdmin()) {
            return;
        }

        if (isBranchManager()) {
            if (userBranchId != null && userBranchId.equals(session.branchId)) {
                return;
            }
            throw new SecurityException("ERR_CROSS_BRANCH_VIOLATION: Branch Manager cannot revoke sessions in another branch.");
        }

        // Cashier / Staff: strictly self-only
        if (!this.userId.equals(session.userId)) {
            throw new SecurityException("ERR_PERMISSION_DENIED: Staff members cannot revoke another user's session.");
        }
    }

    public List<ActiveSessionRecord> listSessions(String branchFilter, String userFilter, Boolean activeOnly) {
        List<ActiveSessionRecord> results = new ArrayList<>();
        for (ActiveSessionRecord s : sessionCache.values()) {
            if (!this.tenantId.equals(s.tenantId)) {
                continue;
            }

            // Role scoping
            if (!isOwnerOrSuperAdmin()) {
                if (isBranchManager()) {
                    if (userBranchId != null && !userBranchId.equals(s.branchId)) {
                        continue;
                    }
                } else {
                    // Cashier: strictly self-only
                    if (!this.userId.equals(s.userId)) {
                        continue;
                    }
                }
            }

            if (branchFilter != null && !branchFilter.isEmpty() && !branchFilter.equals(s.branchId)) {
                continue;
            }
            if (userFilter != null && !userFilter.isEmpty() && !userFilter.equals(s.userId)) {
                continue;
            }
            if (activeOnly != null && s.isActive != activeOnly) {
                continue;
            }

            results.add(s);
        }

        results.sort((a, b) -> {
            if (a.isActive != b.isActive) return a.isActive ? -1 : 1;
            return b.lastSeenAt.compareTo(a.lastSeenAt);
        });

        return results;
    }

    public SessionRevocationResult revokeSession(String sessionId, String reason) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId is required");
        }

        ActiveSessionRecord record = sessionCache.get(sessionId);
        if (record == null) {
            throw new NoSuchElementException("SESSION_NOT_FOUND: Session " + sessionId + " not found.");
        }

        assertRevocationAuthority(record);

        if (isOffline) {
            offlineRevocationQueue.add(sessionId);
            ActiveSessionRecord offlineRevoked = new ActiveSessionRecord(
                    record.sessionId,
                    record.userId,
                    record.userEmail,
                    record.userFullName,
                    record.userRole,
                    record.tenantId,
                    record.branchId,
                    record.deviceId,
                    record.deviceName,
                    record.clientPlatform,
                    record.ipAddress,
                    false,
                    record.isCurrent,
                    reason != null ? reason : "OFFLINE_REVOCATION",
                    Instant.now().toString(),
                    record.lastSeenAt,
                    record.createdAt
            );
            sessionCache.put(sessionId, offlineRevoked);
            return new SessionRevocationResult(
                    sessionId,
                    "OFFLINE_QUEUED",
                    true,
                    "Session marked revoked locally. Queued for online sync.",
                    "সেশনটি লোকালি বাতিল করা হয়েছে। অনলাইনে সিঙ্ক হবে।"
            );
        }

        ActiveSessionRecord updated = new ActiveSessionRecord(
                record.sessionId,
                record.userId,
                record.userEmail,
                record.userFullName,
                record.userRole,
                record.tenantId,
                record.branchId,
                record.deviceId,
                record.deviceName,
                record.clientPlatform,
                record.ipAddress,
                false,
                record.isCurrent,
                reason != null ? reason : "MANUAL_REVOCATION",
                Instant.now().toString(),
                record.lastSeenAt,
                record.createdAt
        );
        sessionCache.put(sessionId, updated);

        return new SessionRevocationResult(
                sessionId,
                "SUCCESS",
                true,
                "Device session successfully revoked.",
                "ডিভাইস সেশনটি সফলভাবে বাতিল করা হয়েছে।"
        );
    }

    public BulkRevocationResult revokeAllSessions(String targetUserId, String reason) {
        String effectiveTarget = targetUserId != null && !targetUserId.isEmpty() ? targetUserId : this.userId;

        if (!isOwnerOrSuperAdmin() && !effectiveTarget.equals(this.userId)) {
            if (isBranchManager()) {
                // Must ensure target user belongs to their branch
                boolean targetInBranch = false;
                for (ActiveSessionRecord s : sessionCache.values()) {
                    if (effectiveTarget.equals(s.userId) && userBranchId != null && userBranchId.equals(s.branchId)) {
                        targetInBranch = true;
                        break;
                    }
                }
                if (!targetInBranch) {
                    throw new SecurityException("ERR_CROSS_BRANCH_VIOLATION: Branch Manager cannot revoke sessions in another branch.");
                }
            } else {
                throw new SecurityException("ERR_PERMISSION_DENIED: Staff members cannot bulk-revoke other users' sessions.");
            }
        }

        int count = 0;
        for (ActiveSessionRecord s : sessionCache.values()) {
            if (effectiveTarget.equals(s.userId) && s.isActive) {
                ActiveSessionRecord revoked = new ActiveSessionRecord(
                        s.sessionId,
                        s.userId,
                        s.userEmail,
                        s.userFullName,
                        s.userRole,
                        s.tenantId,
                        s.branchId,
                        s.deviceId,
                        s.deviceName,
                        s.clientPlatform,
                        s.ipAddress,
                        false,
                        s.isCurrent,
                        reason != null ? reason : "LOGOUT_ALL",
                        Instant.now().toString(),
                        s.lastSeenAt,
                        s.createdAt
                );
                sessionCache.put(s.sessionId, revoked);
                count++;
            }
        }

        return new BulkRevocationResult(
                "SUCCESS",
                count,
                effectiveTarget,
                "Successfully revoked " + count + " active device session(s).",
                "সফলভাবে " + count + "টি সক্রিয় ডিভাইস সেশন বাতিল করা হয়েছে।"
        );
    }

    public int syncOfflineRevocations() {
        int count = offlineRevocationQueue.size();
        offlineRevocationQueue.clear();
        return count;
    }

    public ActiveSessionsUiState getUiState(String branchFilter, String userFilter, Boolean activeOnly) {
        if (isOffline) {
            List<ActiveSessionRecord> cached = listSessions(branchFilter, userFilter, activeOnly);
            return new ActiveSessionsUiState(
                    UiStatus.OFFLINE,
                    "Running in offline mode. Session revocations queued locally.",
                    "অফলাইন মোড সক্রিয় রয়েছে। সেশন বাতিল লোকালি সংরক্ষিত।",
                    cached,
                    cached.isEmpty() ? null : cached.get(0),
                    offlineRevocationQueue.size()
            );
        }

        List<ActiveSessionRecord> list = listSessions(branchFilter, userFilter, activeOnly);
        if (list.isEmpty()) {
            return new ActiveSessionsUiState(
                    UiStatus.EMPTY,
                    null,
                    null,
                    Collections.emptyList(),
                    null,
                    0
            );
        }

        ActiveSessionRecord current = null;
        for (ActiveSessionRecord s : list) {
            if (s.isCurrent) {
                current = s;
                break;
            }
        }
        if (current == null) {
            current = list.get(0);
        }

        return new ActiveSessionsUiState(
                UiStatus.SUCCESS,
                null,
                null,
                list,
                current,
                0
        );
    }
}
