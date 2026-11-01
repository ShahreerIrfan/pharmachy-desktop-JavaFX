package com.pharmacy.pos.billing;

import java.time.Instant;
import java.util.*;

/**
 * PosFeatureEntitlementEngineClient
 * Architecture Decision: ADR-0015, ADR-0080 & ADR-0082
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P082)
 *
 * JavaFX Desktop POS Client for Server-Authoritative Feature Entitlement Engine:
 * - Runtime evaluation of effective capabilities, limits, quotas, and bounded offline lease
 * - Enforces 72-hour maximum offline counter lease countdown
 * - Enforces dispensing lockout during suspension while preserving clinical safety (INV-SAFETY-01)
 * - Safe degradation upon quota breach
 */
public final class PosFeatureEntitlementEngineClient {

    public static final int DEFAULT_OFFLINE_LEASE_HOURS = 72;

    public static final Set<String> CLINICAL_SAFETY_CONTROLS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            "ANTIBIOTIC_REGISTER",
            "BATCH_EXPIRY_BLOCK",
            "RECALL_QUARANTINE",
            "BANNED_DRUG_RESTRICTION",
            "RX_VERIFICATION"
        ))
    );

    private PosFeatureEntitlementEngineClient() {}

    /**
     * Resolves the API endpoint for querying effective entitlements.
     */
    public static String resolveEndpoint(String tenantId) {
        return resolveEndpoint(tenantId, null, null);
    }

    public static String resolveEndpoint(String tenantId, String deviceId, String branchId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("Tenant ID cannot be null or empty.");
        }
        StringBuilder sb = new StringBuilder("/api/v2/billing/effective-entitlements/");
        sb.append(tenantId.trim()).append("/");

        List<String> queryParams = new ArrayList<>();
        if (deviceId != null && !deviceId.trim().isEmpty()) {
            queryParams.add("device_id=" + deviceId.trim());
        }
        if (branchId != null && !branchId.trim().isEmpty()) {
            queryParams.add("branch_id=" + branchId.trim());
        }

        if (!queryParams.isEmpty()) {
            sb.append("?").append(String.join("&", queryParams));
        }
        return sb.toString();
    }

    /**
     * Determines whether offline dispensing checkout operations are permitted.
     * Requires:
     * 1. Subscription allowing checkout (is_checkout_allowed == true)
     * 2. Capability CHECKOUT_DISPENSING == true
     * 3. Offline lease status == ACTIVE
     */
    public static boolean isCheckoutPermitted(
        boolean isCheckoutAllowedBySubscription,
        boolean hasCheckoutCapability,
        String leaseStatus,
        Instant leaseExpiresAt
    ) {
        if (!isCheckoutAllowedBySubscription || !hasCheckoutCapability) {
            return false;
        }
        if (leaseStatus == null || !"ACTIVE".equalsIgnoreCase(leaseStatus.trim())) {
            return false;
        }
        if (leaseExpiresAt != null && Instant.now().isAfter(leaseExpiresAt)) {
            return false;
        }
        return true;
    }

    /**
     * Invariant INV-SAFETY-01: Clinical safety protections are active even if cashier sales are blocked.
     */
    public static boolean isClinicalSafetyActive(String featureKey) {
        if (featureKey == null || featureKey.trim().isEmpty()) {
            return false;
        }
        return CLINICAL_SAFETY_CONTROLS.contains(featureKey.toUpperCase().trim());
    }

    /**
     * Checks if structural quotas (branches, devices, staff) are in an over-quota state.
     */
    public static boolean isQuotaExceeded(int used, int max) {
        return used > max;
    }

    /**
     * Evaluates lease expiration countdown in remaining minutes.
     */
    public static long calculateRemainingLeaseMinutes(Instant leaseExpiresAt) {
        if (leaseExpiresAt == null) return 0;
        long remainingSeconds = leaseExpiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(0, remainingSeconds / 60);
    }
}
