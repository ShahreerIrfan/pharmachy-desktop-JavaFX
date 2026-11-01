package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PosTenantLifecycleEndToEndClient Tests (ADR-0080)")
public class PosTenantLifecycleEndToEndClientTest {

    @Test
    @DisplayName("Should permit POS checkout only during TRIAL or ACTIVE states")
    void testCheckoutLockout() {
        assertTrue(PosTenantLifecycleEndToEndClient.isCheckoutPermitted("TRIAL"));
        assertTrue(PosTenantLifecycleEndToEndClient.isCheckoutPermitted("ACTIVE"));

        assertFalse(PosTenantLifecycleEndToEndClient.isCheckoutPermitted("SUSPENDED"));
        assertFalse(PosTenantLifecycleEndToEndClient.isCheckoutPermitted("PAST_DUE"));
        assertFalse(PosTenantLifecycleEndToEndClient.isCheckoutPermitted("ARCHIVED"));
        assertFalse(PosTenantLifecycleEndToEndClient.isCheckoutPermitted(null));
    }

    @Test
    @DisplayName("Should enforce Super Admin authorization for lifecycle management")
    void testSuperAdminAuthorization() {
        assertTrue(PosTenantLifecycleEndToEndClient.isLifecycleAdminAuthorized("SUPERADMIN"));
        assertTrue(PosTenantLifecycleEndToEndClient.isLifecycleAdminAuthorized("SUPER_ADMIN"));
        assertTrue(PosTenantLifecycleEndToEndClient.isLifecycleAdminAuthorized("PLATFORM_OPERATOR"));

        assertFalse(PosTenantLifecycleEndToEndClient.isLifecycleAdminAuthorized("PHARMACY_OWNER"));
        assertFalse(PosTenantLifecycleEndToEndClient.isLifecycleAdminAuthorized("CASHIER"));
        assertFalse(PosTenantLifecycleEndToEndClient.isLifecycleAdminAuthorized(null));
    }

    @Test
    @DisplayName("Should compute correct plan tier quotas and Decimal BDT amounts")
    void testPlanTierQuotas() {
        assertEquals(1, PosTenantLifecycleEndToEndClient.getBranchQuota("BASIC"));
        assertEquals(2, PosTenantLifecycleEndToEndClient.getDeviceQuota("BASIC"));
        assertEquals(new BigDecimal("2500.00"), PosTenantLifecycleEndToEndClient.getMonthlyAmountBdt("BASIC"));

        assertEquals(5, PosTenantLifecycleEndToEndClient.getBranchQuota("STANDARD"));
        assertEquals(10, PosTenantLifecycleEndToEndClient.getDeviceQuota("STANDARD"));
        assertEquals(new BigDecimal("6500.00"), PosTenantLifecycleEndToEndClient.getMonthlyAmountBdt("STANDARD"));

        assertEquals(25, PosTenantLifecycleEndToEndClient.getBranchQuota("PRO"));
        assertEquals(50, PosTenantLifecycleEndToEndClient.getDeviceQuota("PRO"));
        assertEquals(new BigDecimal("15000.00"), PosTenantLifecycleEndToEndClient.getMonthlyAmountBdt("PRO"));
    }

    @Test
    @DisplayName("Should validate finite state machine transitions")
    void testFsmTransitions() {
        // Valid forward progressions
        assertTrue(PosTenantLifecycleEndToEndClient.isValidTransition("PROVISIONING", "TRIAL"));
        assertTrue(PosTenantLifecycleEndToEndClient.isValidTransition("TRIAL", "ACTIVE"));
        assertTrue(PosTenantLifecycleEndToEndClient.isValidTransition("ACTIVE", "SUSPENDED"));
        assertTrue(PosTenantLifecycleEndToEndClient.isValidTransition("SUSPENDED", "ACTIVE"));
        assertTrue(PosTenantLifecycleEndToEndClient.isValidTransition("ACTIVE", "ARCHIVED"));

        // Invalid backward or illegal jumps
        assertFalse(PosTenantLifecycleEndToEndClient.isValidTransition("PROVISIONING", "ARCHIVED"));
        assertFalse(PosTenantLifecycleEndToEndClient.isValidTransition("ARCHIVED", "ACTIVE"));
    }

    @Test
    @DisplayName("Should resolve correct tenant lifecycle API endpoints")
    void testEndpointResolution() {
        assertEquals("/api/v2/tenants/t-demo/activate-subscription/",
            PosTenantLifecycleEndToEndClient.resolveEndpoint("activate-subscription", "t-demo"));
        assertEquals("/api/v2/tenants/t-demo/change-plan/",
            PosTenantLifecycleEndToEndClient.resolveEndpoint("change-plan", "t-demo"));
        assertEquals("/api/v2/tenants/t-demo/suspend/",
            PosTenantLifecycleEndToEndClient.resolveEndpoint("suspend", "t-demo"));
        assertEquals("/api/v2/tenants/t-demo/restore/",
            PosTenantLifecycleEndToEndClient.resolveEndpoint("restore", "t-demo"));
        assertEquals("/api/v2/tenants/t-demo/lifecycle-timeline/",
            PosTenantLifecycleEndToEndClient.resolveEndpoint("timeline", "t-demo"));
    }
}
