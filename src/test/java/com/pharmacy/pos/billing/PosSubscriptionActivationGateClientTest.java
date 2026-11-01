package com.pharmacy.pos.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P085: JavaFX Desktop POS Subscription Activation Gate Client Tests")
public class PosSubscriptionActivationGateClientTest {

    @Test
    @DisplayName("Safe read-only methods are always permitted regardless of subscription status")
    public void testSafeReadOnlyMethodsAlwaysPermittedRegardlessOfStatus() {
        String[] statuses = {"SUSPENDED", "EXPIRED", "CANCELLED", "PENDING", "ACTIVE"};
        String[] methods = {"GET", "HEAD", "OPTIONS"};

        for (String status : statuses) {
            for (String method : methods) {
                PosSubscriptionActivationGateClient.GateDecision decision =
                    PosSubscriptionActivationGateClient.evaluateGate(status, method, "/api/v2/sales/");
                assertTrue(
                    decision.isPermitted(),
                    "Method " + method + " must be permitted under status " + status
                );
                assertEquals("READ_ONLY_PERMITTED", decision.getDecisionCode());
            }
        }
    }

    @Test
    @DisplayName("Authorized billing paths are permitted even when subscription is suspended or expired")
    public void testAuthorizedBillingPathsAlwaysPermitted() {
        String[] billingPaths = {
            "/api/v2/billing/subscriptions/tenant-1/verify-payment/",
            "/api/v2/billing/plans/quote/",
            "/api/v2/billing/plans/",
            "/api/v2/billing/entitlements/tenant-1/apply/"
        };

        for (String path : billingPaths) {
            PosSubscriptionActivationGateClient.GateDecision decision =
                PosSubscriptionActivationGateClient.evaluateGate("SUSPENDED", "POST", path);
            assertTrue(
                decision.isPermitted(),
                "Billing path " + path + " must be permitted even when SUSPENDED"
            );
            assertEquals("EXEMPT_PATH", decision.getDecisionCode());
        }
    }

    @Test
    @DisplayName("Operational ERP writes are blocked when subscription is unentitled")
    public void testUnentitledOperationalWritesBlocked() {
        String[] unentitled = {"SUSPENDED", "EXPIRED", "CANCELLED", "PENDING"};
        String[] operationalPaths = {
            "/api/v2/sales/checkout/",
            "/api/v2/inventory/adjustments/",
            "/api/v2/procurement/orders/",
            "/api/v2/prescriptions/dispense/"
        };

        for (String status : unentitled) {
            for (String path : operationalPaths) {
                PosSubscriptionActivationGateClient.GateDecision decision =
                    PosSubscriptionActivationGateClient.evaluateGate(status, "POST", path);
                assertFalse(
                    decision.isPermitted(),
                    "Operational write to " + path + " must be blocked under status " + status
                );
                assertEquals("ERR_SUBSCRIPTION_UNENTITLED_WRITE_BLOCKED", decision.getDecisionCode());
                assertTrue(decision.getMessageEn().contains("does not permit data mutations"));
            }
        }
    }

    @Test
    @DisplayName("Operational ERP writes are permitted when subscription is active, trial, or past due")
    public void testEntitledOperationalWritesPermitted() {
        String[] entitled = {"ACTIVE", "TRIAL", "PAST_DUE", "GRACE_PERIOD"};

        for (String status : entitled) {
            PosSubscriptionActivationGateClient.GateDecision decision =
                PosSubscriptionActivationGateClient.evaluateGate(status, "POST", "/api/v2/sales/checkout/");
            assertTrue(
                decision.isPermitted(),
                "Operational write must be permitted under entitled status " + status
            );
            assertEquals("ENTITLED", decision.getDecisionCode());
        }
    }
}
