package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0072: JavaFX Desktop POS Platform Feature Flag Client Tests")
class PosFeatureFlagClientTest {

    @Test
    @DisplayName("Role RBAC: Only SuperAdmin and Platform Operator can mutate flags")
    void testCanRoleMutateFlags() {
        assertTrue(PosFeatureFlagClient.canRoleMutateFlags("SUPERADMIN"));
        assertTrue(PosFeatureFlagClient.canRoleMutateFlags("SUPER_ADMIN"));
        assertTrue(PosFeatureFlagClient.canRoleMutateFlags("PLATFORM_OPERATOR"));

        assertFalse(PosFeatureFlagClient.canRoleMutateFlags("PHARMACY_OWNER"));
        assertFalse(PosFeatureFlagClient.canRoleMutateFlags("BRANCH_MANAGER"));
        assertFalse(PosFeatureFlagClient.canRoleMutateFlags("STAFF_PHARMACIST"));
        assertFalse(PosFeatureFlagClient.canRoleMutateFlags("CASHIER"));
        assertFalse(PosFeatureFlagClient.canRoleMutateFlags(null));
    }

    @Test
    @DisplayName("Flag Key validation: Must be alphanumeric kebab/snake case 3-64 chars")
    void testIsValidFlagKey() {
        assertTrue(PosFeatureFlagClient.isValidFlagKey("pos_new_checkout"));
        assertTrue(PosFeatureFlagClient.isValidFlagKey("ai-refill-prediction"));
        assertTrue(PosFeatureFlagClient.isValidFlagKey("v2_dark_mode"));

        assertFalse(PosFeatureFlagClient.isValidFlagKey("ab"));
        assertFalse(PosFeatureFlagClient.isValidFlagKey("Flag Key"));
        assertFalse(PosFeatureFlagClient.isValidFlagKey("flag@invalid"));
        assertFalse(PosFeatureFlagClient.isValidFlagKey(null));
    }

    @Test
    @DisplayName("Evaluation Hierarchy: Kill switch overrides all other conditions")
    void testKillSwitchOverridesAll() {
        var res = PosFeatureFlagClient.evaluateLocal(
                true,   // isKillSwitchActive
                true,   // tenantOverride = true
                "PRO",  // tenantPlanTier
                "BASIC",// minPlanTier
                100,    // rolloutPercentage
                true,   // isEnabledGlobally
                true,   // defaultEnabled
                "pos_new_checkout",
                "tenant-1"
        );
        assertFalse(res.isEnabled());
        assertEquals("KILL_SWITCH_ACTIVE", res.reason());
    }

    @Test
    @DisplayName("Evaluation Hierarchy: Tenant override takes precedence over plan and rollout")
    void testTenantOverridePrecedence() {
        // Explicitly disabled override on a globally enabled flag
        var resDisabled = PosFeatureFlagClient.evaluateLocal(
                false,
                false,  // tenantOverride = false
                "PRO",
                "BASIC",
                100,
                true,
                true,
                "pos_new_checkout",
                "tenant-1"
        );
        assertFalse(resDisabled.isEnabled());
        assertEquals("TENANT_OVERRIDE", resDisabled.reason());

        // Explicitly enabled override on a globally disabled flag
        var resEnabled = PosFeatureFlagClient.evaluateLocal(
                false,
                true,   // tenantOverride = true
                "BASIC",
                "PRO",
                0,
                false,
                false,
                "pos_new_checkout",
                "tenant-1"
        );
        assertTrue(resEnabled.isEnabled());
        assertEquals("TENANT_OVERRIDE", resEnabled.reason());
    }

    @Test
    @DisplayName("Evaluation Hierarchy: Plan Tier gate restricts insufficient plans")
    void testPlanTierGate() {
        var res = PosFeatureFlagClient.evaluateLocal(
                false,
                null,
                "BASIC", // tenant is BASIC
                "PRO",   // required is PRO
                100,
                true,
                true,
                "ai_copilot",
                "tenant-1"
        );
        assertFalse(res.isEnabled());
        assertEquals("PLAN_TIER_INSUFFICIENT", res.reason());
    }

    @Test
    @DisplayName("Evaluation Hierarchy: Deterministic rollout and global enabled fallback")
    void testRolloutAndGlobalFallback() {
        // 100% rollout
        var res100 = PosFeatureFlagClient.evaluateLocal(
                false,
                null,
                "PRO",
                "BASIC",
                100,
                false,
                false,
                "pos_new_checkout",
                "tenant-1"
        );
        assertTrue(res100.isEnabled());
        assertEquals("ROLLOUT_PERCENTAGE_INCLUDED", res100.reason());

        // Global enabled fallback
        var resGlobal = PosFeatureFlagClient.evaluateLocal(
                false,
                null,
                "STANDARD",
                "BASIC",
                0,
                true,
                false,
                "pos_new_checkout",
                "tenant-1"
        );
        assertTrue(resGlobal.isEnabled());
        assertEquals("GLOBAL_ENABLED", resGlobal.reason());

        // Safe default fallback
        var resDefault = PosFeatureFlagClient.evaluateLocal(
                false,
                null,
                "STANDARD",
                "BASIC",
                0,
                false,
                false,
                "pos_new_checkout",
                "tenant-1"
        );
        assertFalse(resDefault.isEnabled());
        assertEquals("SAFE_DEFAULT", resDefault.reason());
    }

    @Test
    @DisplayName("Builds endpoint URLs correctly")
    void testBuildUrls() {
        assertEquals("/api/v2/platform/feature-flags/", PosFeatureFlagClient.buildPlatformFlagsUrl());
        assertEquals("/api/v2/platform/feature-flags/pos_checkout/", PosFeatureFlagClient.buildPlatformFlagDetailUrl("pos_checkout"));
        assertEquals("/api/v2/platform/feature-flags/pos_checkout/kill-switch/", PosFeatureFlagClient.buildKillSwitchUrl("pos_checkout"));
        assertEquals("/api/v2/platform/feature-flags/pos_checkout/tenant-override/", PosFeatureFlagClient.buildTenantOverrideUrl("pos_checkout"));
        assertEquals("/api/v2/tenants/feature-flags/eval/?flag_key=pos_checkout", PosFeatureFlagClient.buildTenantEvalUrl("pos_checkout"));
        assertEquals("/api/v2/tenants/feature-flags/eval/", PosFeatureFlagClient.buildTenantEvalUrl(null));

        assertThrows(IllegalArgumentException.class, () -> PosFeatureFlagClient.buildPlatformFlagDetailUrl("invalid flag!"));
    }
}
