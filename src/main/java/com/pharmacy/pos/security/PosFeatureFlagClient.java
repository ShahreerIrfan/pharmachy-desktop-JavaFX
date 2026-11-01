package com.pharmacy.pos.security;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-0072: JavaFX Desktop POS Platform Feature Flag Client.
 * Implements server-authoritative evaluation hierarchy:
 * 1. Kill Switch Active -> false
 * 2. Tenant Override -> override value
 * 3. Plan Tier Gate -> check min plan tier
 * 4. Hashed Percentage Rollout -> md5 hash bucket check
 * 5. Global Enabled -> true
 * 6. Safe Default -> default_enabled
 */
public final class PosFeatureFlagClient {

    private static final Pattern FLAG_KEY_PATTERN = Pattern.compile("^[a-z0-9_-]{3,64}$");

    private static final Set<String> PRIVILEGED_ADMIN_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR"
    );

    private static final Map<String, Integer> PLAN_TIER_RANKS = Map.of(
            "BASIC", 1,
            "STANDARD", 2,
            "PRO", 3
    );

    public record EvaluationResult(boolean isEnabled, String reason) {
        public EvaluationResult {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    private PosFeatureFlagClient() {
        // Utility class
    }

    /**
     * RBAC Invariant: Only SuperAdmin and Platform Operator can mutate flags or activate kill switches.
     */
    public static boolean canRoleMutateFlags(String role) {
        if (role == null) {
            return false;
        }
        return PRIVILEGED_ADMIN_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Validates flag key syntax according to ADR-0072.
     */
    public static boolean isValidFlagKey(String flagKey) {
        if (flagKey == null) {
            return false;
        }
        return FLAG_KEY_PATTERN.matcher(flagKey.trim()).matches();
    }

    /**
     * Calculates deterministic 0-99 rollout bucket using MD5(flagKey:tenantId).
     */
    public static int calculateRolloutBucket(String flagKey, String tenantId) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            String payload = (flagKey != null ? flagKey.trim() : "") + ":" + (tenantId != null ? tenantId.trim() : "");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            BigInteger bigInt = new BigInteger(1, digest);
            return bigInt.mod(BigInteger.valueOf(100)).intValue();
        } catch (Exception e) {
            return 99;
        }
    }

    /**
     * Client-side evaluation helper mirroring ADR-0072 hierarchy.
     */
    public static EvaluationResult evaluateLocal(
            boolean isKillSwitchActive,
            Boolean tenantOverride,
            String tenantPlanTier,
            String minPlanTier,
            int rolloutPercentage,
            boolean isEnabledGlobally,
            boolean defaultEnabled,
            String flagKey,
            String tenantId
    ) {
        // 1. Kill Switch
        if (isKillSwitchActive) {
            return new EvaluationResult(false, "KILL_SWITCH_ACTIVE");
        }

        // 2. Tenant Override
        if (tenantOverride != null) {
            return new EvaluationResult(tenantOverride, "TENANT_OVERRIDE");
        }

        // 3. Plan Tier Gate
        int currentRank = PLAN_TIER_RANKS.getOrDefault((tenantPlanTier != null ? tenantPlanTier : "BASIC").toUpperCase(), 1);
        int requiredRank = PLAN_TIER_RANKS.getOrDefault((minPlanTier != null ? minPlanTier : "BASIC").toUpperCase(), 1);
        if (currentRank < requiredRank) {
            return new EvaluationResult(false, "PLAN_TIER_INSUFFICIENT");
        }

        // 4. Percentage Rollout
        int pct = Math.max(0, Math.min(100, rolloutPercentage));
        if (pct > 0 && pct < 100) {
            int bucket = calculateRolloutBucket(flagKey, tenantId);
            if (bucket < pct) {
                return new EvaluationResult(true, "ROLLOUT_PERCENTAGE_INCLUDED");
            } else {
                return new EvaluationResult(false, "ROLLOUT_PERCENTAGE_EXCLUDED");
            }
        } else if (pct >= 100) {
            return new EvaluationResult(true, "ROLLOUT_PERCENTAGE_INCLUDED");
        }

        // 5. Globally Enabled
        if (isEnabledGlobally) {
            return new EvaluationResult(true, "GLOBAL_ENABLED");
        }

        // 6. Safe Default
        return new EvaluationResult(defaultEnabled, "SAFE_DEFAULT");
    }

    /**
     * Builds platform flag list and management URL.
     */
    public static String buildPlatformFlagsUrl() {
        return "/api/v2/platform/feature-flags/";
    }

    /**
     * Builds platform flag detail URL.
     */
    public static String buildPlatformFlagDetailUrl(String flagKey) {
        String clean = flagKey != null ? flagKey.trim() : "";
        if (!isValidFlagKey(clean)) {
            throw new IllegalArgumentException("Invalid flag key: '" + flagKey + "'");
        }
        return "/api/v2/platform/feature-flags/" + clean + "/";
    }

    /**
     * Builds kill-switch toggle URL.
     */
    public static String buildKillSwitchUrl(String flagKey) {
        String clean = flagKey != null ? flagKey.trim() : "";
        if (!isValidFlagKey(clean)) {
            throw new IllegalArgumentException("Invalid flag key: '" + flagKey + "'");
        }
        return "/api/v2/platform/feature-flags/" + clean + "/kill-switch/";
    }

    /**
     * Builds tenant override URL.
     */
    public static String buildTenantOverrideUrl(String flagKey) {
        String clean = flagKey != null ? flagKey.trim() : "";
        if (!isValidFlagKey(clean)) {
            throw new IllegalArgumentException("Invalid flag key: '" + flagKey + "'");
        }
        return "/api/v2/platform/feature-flags/" + clean + "/tenant-override/";
    }

    /**
     * Builds tenant evaluation URL.
     */
    public static String buildTenantEvalUrl(String flagKey) {
        if (flagKey == null || flagKey.trim().isEmpty()) {
            return "/api/v2/tenants/feature-flags/eval/";
        }
        String clean = flagKey.trim();
        if (!isValidFlagKey(clean)) {
            throw new IllegalArgumentException("Invalid flag key: '" + flagKey + "'");
        }
        return "/api/v2/tenants/feature-flags/eval/?flag_key=" + clean;
    }
}
