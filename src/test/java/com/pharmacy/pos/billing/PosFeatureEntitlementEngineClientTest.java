package com.pharmacy.pos.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P082: JavaFX Desktop POS Feature Entitlement Engine Client Tests")
public class PosFeatureEntitlementEngineClientTest {

    @Test
    @DisplayName("Endpoint resolution handles tenant, device, and branch query parameters")
    public void testEndpointResolution() {
        assertEquals(
            "/api/v2/billing/effective-entitlements/tn-dhaka-01/",
            PosFeatureEntitlementEngineClient.resolveEndpoint("tn-dhaka-01")
        );
        assertEquals(
            "/api/v2/billing/effective-entitlements/tn-dhaka-01/?device_id=pos-terminal-1",
            PosFeatureEntitlementEngineClient.resolveEndpoint("tn-dhaka-01", "pos-terminal-1", null)
        );
        assertEquals(
            "/api/v2/billing/effective-entitlements/tn-dhaka-01/?device_id=pos-terminal-1&branch_id=br-main",
            PosFeatureEntitlementEngineClient.resolveEndpoint("tn-dhaka-01", "pos-terminal-1", "br-main")
        );

        assertThrows(IllegalArgumentException.class, () -> PosFeatureEntitlementEngineClient.resolveEndpoint(null));
        assertThrows(IllegalArgumentException.class, () -> PosFeatureEntitlementEngineClient.resolveEndpoint(""));
    }

    @Test
    @DisplayName("Checkout permission requires active subscription, capability, and unexpired lease")
    public void testCheckoutPermittedConditions() {
        Instant futureExpiry = Instant.now().plus(72, ChronoUnit.HOURS);
        Instant pastExpiry = Instant.now().minus(1, ChronoUnit.HOURS);

        // Valid active condition
        assertTrue(PosFeatureEntitlementEngineClient.isCheckoutPermitted(
            true, true, "ACTIVE", futureExpiry
        ));

        // Blocked: subscription disallowed (e.g. suspended)
        assertFalse(PosFeatureEntitlementEngineClient.isCheckoutPermitted(
            false, true, "ACTIVE", futureExpiry
        ));

        // Blocked: missing CHECKOUT_DISPENSING capability
        assertFalse(PosFeatureEntitlementEngineClient.isCheckoutPermitted(
            true, false, "ACTIVE", futureExpiry
        ));

        // Blocked: lease expired
        assertFalse(PosFeatureEntitlementEngineClient.isCheckoutPermitted(
            true, true, "ACTIVE", pastExpiry
        ));

        // Blocked: lease status SUSPENDED or LOCKED
        assertFalse(PosFeatureEntitlementEngineClient.isCheckoutPermitted(
            true, true, "SUSPENDED", futureExpiry
        ));
    }

    @Test
    @DisplayName("Invariant INV-SAFETY-01: Clinical safety protections active across all conditions")
    public void testClinicalSafetyInvariant() {
        for (String feature : PosFeatureEntitlementEngineClient.CLINICAL_SAFETY_CONTROLS) {
            assertTrue(
                PosFeatureEntitlementEngineClient.isClinicalSafetyActive(feature),
                "Feature " + feature + " must always be recognized as a protected clinical control."
            );
        }
        assertFalse(PosFeatureEntitlementEngineClient.isClinicalSafetyActive("CONSOLIDATED_REPORTING"));
        assertFalse(PosFeatureEntitlementEngineClient.isClinicalSafetyActive("CHECKOUT_DISPENSING"));
    }

    @Test
    @DisplayName("Quota exceeded correctly flags when usage exceeds max limits")
    public void testQuotaExceeded() {
        assertFalse(PosFeatureEntitlementEngineClient.isQuotaExceeded(1, 1));
        assertFalse(PosFeatureEntitlementEngineClient.isQuotaExceeded(2, 3));
        assertTrue(PosFeatureEntitlementEngineClient.isQuotaExceeded(4, 3));
    }

    @Test
    @DisplayName("Lease countdown calculates remaining minutes accurately")
    public void testLeaseCountdown() {
        Instant expiryIn2Hours = Instant.now().plus(120, ChronoUnit.MINUTES);
        long remaining = PosFeatureEntitlementEngineClient.calculateRemainingLeaseMinutes(expiryIn2Hours);
        assertTrue(remaining >= 118 && remaining <= 120);

        Instant expired = Instant.now().minus(10, ChronoUnit.MINUTES);
        assertEquals(0, PosFeatureEntitlementEngineClient.calculateRemainingLeaseMinutes(expired));
    }
}
