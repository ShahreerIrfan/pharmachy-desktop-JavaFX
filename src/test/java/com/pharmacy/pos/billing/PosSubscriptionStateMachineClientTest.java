package com.pharmacy.pos.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P084: JavaFX Desktop POS Subscription State Machine Client Tests")
public class PosSubscriptionStateMachineClientTest {

    @Test
    @DisplayName("Guarded transitions adhere strictly to ADR-0084 FSM state graph")
    public void testGuardedTransitionsFollowStateGraph() {
        // Valid Transitions
        assertTrue(PosSubscriptionStateMachineClient.canTransition(
            PosSubscriptionStateMachineClient.SubscriptionState.PENDING,
            PosSubscriptionStateMachineClient.SubscriptionState.TRIAL
        ));
        assertTrue(PosSubscriptionStateMachineClient.canTransition(
            PosSubscriptionStateMachineClient.SubscriptionState.TRIAL,
            PosSubscriptionStateMachineClient.SubscriptionState.ACTIVE
        ));
        assertTrue(PosSubscriptionStateMachineClient.canTransition(
            PosSubscriptionStateMachineClient.SubscriptionState.ACTIVE,
            PosSubscriptionStateMachineClient.SubscriptionState.PAST_DUE
        ));
        assertTrue(PosSubscriptionStateMachineClient.canTransition(
            PosSubscriptionStateMachineClient.SubscriptionState.PAST_DUE,
            PosSubscriptionStateMachineClient.SubscriptionState.GRACE_PERIOD
        ));
        assertTrue(PosSubscriptionStateMachineClient.canTransition(
            PosSubscriptionStateMachineClient.SubscriptionState.GRACE_PERIOD,
            PosSubscriptionStateMachineClient.SubscriptionState.SUSPENDED
        ));
        assertTrue(PosSubscriptionStateMachineClient.canTransition(
            PosSubscriptionStateMachineClient.SubscriptionState.SUSPENDED,
            PosSubscriptionStateMachineClient.SubscriptionState.ACTIVE
        ));

        // Invalid Transitions
        assertFalse(PosSubscriptionStateMachineClient.canTransition(
            PosSubscriptionStateMachineClient.SubscriptionState.PENDING,
            PosSubscriptionStateMachineClient.SubscriptionState.SUSPENDED
        ));
        assertFalse(PosSubscriptionStateMachineClient.canTransition(
            PosSubscriptionStateMachineClient.SubscriptionState.TRIAL,
            PosSubscriptionStateMachineClient.SubscriptionState.PAST_DUE
        ));
        assertFalse(PosSubscriptionStateMachineClient.canTransition(
            PosSubscriptionStateMachineClient.SubscriptionState.EXPIRED,
            PosSubscriptionStateMachineClient.SubscriptionState.SUSPENDED
        ));
    }

    @Test
    @DisplayName("Checkout lockout and delinquency statuses are accurately determined")
    public void testCheckoutLockoutAndDelinquencyEvaluation() {
        // Permitted Checkouts
        assertTrue(PosSubscriptionStateMachineClient.isCheckoutAllowed(
            PosSubscriptionStateMachineClient.SubscriptionState.ACTIVE
        ));
        assertTrue(PosSubscriptionStateMachineClient.isCheckoutAllowed(
            PosSubscriptionStateMachineClient.SubscriptionState.TRIAL
        ));
        assertTrue(PosSubscriptionStateMachineClient.isCheckoutAllowed(
            PosSubscriptionStateMachineClient.SubscriptionState.PAST_DUE
        ));
        assertTrue(PosSubscriptionStateMachineClient.isCheckoutAllowed(
            PosSubscriptionStateMachineClient.SubscriptionState.GRACE_PERIOD
        ));

        // Locked Out Checkouts
        assertFalse(PosSubscriptionStateMachineClient.isCheckoutAllowed(
            PosSubscriptionStateMachineClient.SubscriptionState.SUSPENDED
        ));
        assertFalse(PosSubscriptionStateMachineClient.isCheckoutAllowed(
            PosSubscriptionStateMachineClient.SubscriptionState.CANCELLED
        ));
        assertFalse(PosSubscriptionStateMachineClient.isCheckoutAllowed(
            PosSubscriptionStateMachineClient.SubscriptionState.EXPIRED
        ));
        assertFalse(PosSubscriptionStateMachineClient.isCheckoutAllowed(
            PosSubscriptionStateMachineClient.SubscriptionState.PENDING
        ));

        // Delinquency
        assertTrue(PosSubscriptionStateMachineClient.isDelinquent(
            PosSubscriptionStateMachineClient.SubscriptionState.PAST_DUE
        ));
        assertTrue(PosSubscriptionStateMachineClient.isDelinquent(
            PosSubscriptionStateMachineClient.SubscriptionState.GRACE_PERIOD
        ));
        assertTrue(PosSubscriptionStateMachineClient.isDelinquent(
            PosSubscriptionStateMachineClient.SubscriptionState.SUSPENDED
        ));
        assertFalse(PosSubscriptionStateMachineClient.isDelinquent(
            PosSubscriptionStateMachineClient.SubscriptionState.ACTIVE
        ));
        assertFalse(PosSubscriptionStateMachineClient.isDelinquent(
            PosSubscriptionStateMachineClient.SubscriptionState.TRIAL
        ));
    }

    @Test
    @DisplayName("Invariant INV-SAFETY-01: Clinical safety controls are entitled even under SUSPENDED or EXPIRED state")
    public void testClinicalSafetyFeaturesAlwaysEntitledEvenWhenSuspended() {
        PosSubscriptionStateMachineClient.SubscriptionState[] allStates =
            PosSubscriptionStateMachineClient.SubscriptionState.values();

        String[] safetyControls = {
            "ANTIBIOTIC_REGISTER",
            "BATCH_EXPIRY_BLOCK",
            "RECALL_QUARANTINE",
            "BANNED_DRUG_RESTRICTION",
            "RX_VERIFICATION"
        };

        for (PosSubscriptionStateMachineClient.SubscriptionState state : allStates) {
            for (String control : safetyControls) {
                assertTrue(
                    PosSubscriptionStateMachineClient.isFeatureEntitled(state, control),
                    "Safety control " + control + " must be entitled under status " + state
                );
            }
        }

        // Commercial feature blocked under SUSPENDED
        assertFalse(PosSubscriptionStateMachineClient.isFeatureEntitled(
            PosSubscriptionStateMachineClient.SubscriptionState.SUSPENDED,
            "COMMERCIAL_REPORTS"
        ));
    }

    @Test
    @DisplayName("Mandatory reason guard and localized bilingual labels")
    public void testMandatoryReasonGuardAndBilingualLabels() {
        assertTrue(PosSubscriptionStateMachineClient.requiresReason(
            PosSubscriptionStateMachineClient.SubscriptionState.SUSPENDED
        ));
        assertTrue(PosSubscriptionStateMachineClient.requiresReason(
            PosSubscriptionStateMachineClient.SubscriptionState.CANCELLED
        ));
        assertTrue(PosSubscriptionStateMachineClient.requiresReason(
            PosSubscriptionStateMachineClient.SubscriptionState.EXPIRED
        ));
        assertFalse(PosSubscriptionStateMachineClient.requiresReason(
            PosSubscriptionStateMachineClient.SubscriptionState.ACTIVE
        ));

        // Localization
        assertEquals("Active", PosSubscriptionStateMachineClient.getStatusLabel(
            PosSubscriptionStateMachineClient.SubscriptionState.ACTIVE, "EN"
        ));
        assertEquals("সক্রিয়", PosSubscriptionStateMachineClient.getStatusLabel(
            PosSubscriptionStateMachineClient.SubscriptionState.ACTIVE, "BN"
        ));
        assertEquals("সাময়িক স্থগিত", PosSubscriptionStateMachineClient.getStatusLabel(
            PosSubscriptionStateMachineClient.SubscriptionState.SUSPENDED, "BN"
        ));
    }
}
