package com.pharmacy.pos.security;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ADR-0067: Platform Super Admin Tenant Suspension & Restore Client.
 * Manages tenant suspension, restoration, reason codes, client lease status,
 * and offline outbox pending-sync policy reconciliation.
 */
public final class PosTenantSuspensionRestoreClient {

    public static final Set<String> AUTHORIZED_PLATFORM_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR"
    );

    public static final Set<String> VALID_SUSPENSION_REASON_CODES = Set.of(
            "NON_PAYMENT",
            "COMPLIANCE_BREACH",
            "FRAUD_INVESTIGATION",
            "DISPUTE_HOLD",
            "ADMIN_MANUAL"
    );

    public enum ClientLeaseStatus {
        ACTIVE,
        SUSPENDED,
        REVOKED,
        EXPIRED
    }

    public enum PendingSyncPolicy {
        ALLOW_SYNC,
        DRAIN_PRE_SUSPENSION,
        REJECT_POST_SUSPENSION
    }

    public record SuspensionRequest(
            String tenantId,
            String reason,
            String reasonCode
    ) {
        public SuspensionRequest {
            Objects.requireNonNull(tenantId, "tenantId cannot be null");
            if (tenantId.trim().isEmpty()) {
                throw new IllegalArgumentException("tenantId cannot be empty");
            }
            Objects.requireNonNull(reason, "reason cannot be null");
            if (reason.trim().length() < 5) {
                throw new IllegalArgumentException("reason must be at least 5 characters");
            }
            if (reasonCode != null && !VALID_SUSPENSION_REASON_CODES.contains(reasonCode.trim().toUpperCase())) {
                throw new IllegalArgumentException("Invalid suspension reasonCode: " + reasonCode);
            }
        }
    }

    public record RestorationRequest(
            String tenantId,
            String reason
    ) {
        public RestorationRequest {
            Objects.requireNonNull(tenantId, "tenantId cannot be null");
            if (tenantId.trim().isEmpty()) {
                throw new IllegalArgumentException("tenantId cannot be empty");
            }
            Objects.requireNonNull(reason, "reason cannot be null");
            if (reason.trim().length() < 5) {
                throw new IllegalArgumentException("reason must be at least 5 characters");
            }
        }
    }

    public record LifecycleActionResult(
            boolean success,
            String code,
            String tenantId,
            String status,
            String reason,
            String reasonCode,
            ClientLeaseStatus leaseStatus,
            PendingSyncPolicy syncPolicy,
            String messageEn,
            String messageBn
    ) {}

    private PosTenantSuspensionRestoreClient() {
        // Utility / client boundary
    }

    /**
     * Checks if caller role is authorized to suspend or restore tenants.
     */
    public static boolean canManageLifecycle(String role) {
        if (role == null) {
            return false;
        }
        return AUTHORIZED_PLATFORM_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Architectural invariant: Lifecycle management controls are strictly platform-level
     * and never accessible within regular store cashier screens.
     */
    public static boolean isLifecycleManagementVisibleInStoreUI() {
        return false;
    }

    /**
     * Evaluates client lease validity based on tenant status.
     */
    public static ClientLeaseStatus evaluateClientLease(String tenantStatus) {
        if (tenantStatus == null) {
            return ClientLeaseStatus.REVOKED;
        }
        String clean = tenantStatus.trim().toUpperCase();
        return switch (clean) {
            case "ACTIVE", "TRIAL" -> ClientLeaseStatus.ACTIVE;
            case "PAST_DUE" -> ClientLeaseStatus.ACTIVE; // Grace period active
            case "SUSPENDED" -> ClientLeaseStatus.SUSPENDED;
            case "ARCHIVED" -> ClientLeaseStatus.REVOKED;
            default -> ClientLeaseStatus.REVOKED;
        };
    }

    /**
     * Evaluates offline POS pending outbox sync policy against suspension timestamp.
     * Zero Data Loss Invariant:
     * - Pre-suspension transaction: DRAIN_PRE_SUSPENSION (settled and audited)
     * - Post-suspension transaction: REJECT_POST_SUSPENSION (blocked from live inventory)
     */
    public static PendingSyncPolicy evaluatePendingSyncPolicy(
            String tenantStatus,
            Instant transactionTime,
            Instant suspendedAt
    ) {
        if (tenantStatus == null || !"SUSPENDED".equalsIgnoreCase(tenantStatus.trim())) {
            return PendingSyncPolicy.ALLOW_SYNC;
        }
        if (transactionTime == null || suspendedAt == null) {
            return PendingSyncPolicy.REJECT_POST_SUSPENSION;
        }

        if (!transactionTime.isAfter(suspendedAt)) {
            return PendingSyncPolicy.DRAIN_PRE_SUSPENSION;
        } else {
            return PendingSyncPolicy.REJECT_POST_SUSPENSION;
        }
    }

    /**
     * Builds platform suspend endpoint path.
     */
    public static String buildSuspendUrl(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        return "/api/v2/platform/console/tenants/" + tenantId.trim() + "/suspend/";
    }

    /**
     * Builds platform restore endpoint path.
     */
    public static String buildRestoreUrl(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        return "/api/v2/platform/console/tenants/" + tenantId.trim() + "/restore/";
    }
}
