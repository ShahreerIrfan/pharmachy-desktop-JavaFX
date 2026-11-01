package com.pharmacy.pos.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * PosSubscriptionStateMachineClient
 * Architecture Decision: ADR-0013, ADR-0015, ADR-0033 & ADR-0084
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P084)
 *
 * JavaFX Desktop POS Subscription State Machine Client:
 * - Guarded transitions: PENDING, TRIAL, ACTIVE, PAST_DUE, GRACE_PERIOD, SUSPENDED, CANCELLED, EXPIRED.
 * - Checkout lockout evaluation: SUSPENDED, CANCELLED, and EXPIRED lock out POS cash checkouts.
 * - Delinquency detection: PAST_DUE and GRACE_PERIOD allow checkouts with persistent warning alerts.
 * - Invariant INV-SAFETY-01: Clinical safety controls are NEVER gated, even when suspended.
 */
public final class PosSubscriptionStateMachineClient {

    public enum SubscriptionState {
        PENDING,
        TRIAL,
        ACTIVE,
        PAST_DUE,
        GRACE_PERIOD,
        SUSPENDED,
        CANCELLED,
        EXPIRED
    }

    public static final Set<String> CLINICAL_SAFETY_FEATURES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            "ANTIBIOTIC_REGISTER",
            "BATCH_EXPIRY_BLOCK",
            "RECALL_QUARANTINE",
            "BANNED_DRUG_RESTRICTION",
            "RX_VERIFICATION"
        ))
    );

    public static class SubscriptionStateSnapshot {
        private final String subscriptionId;
        private final String tenantId;
        private final String planTier;
        private final SubscriptionState status;
        private final BigDecimal amountBdt;
        private final String billingCycle;
        private final LocalDate startDate;
        private final LocalDate renewalDate;
        private final LocalDate gracePeriodEndDate;
        private final boolean isCheckoutAllowed;
        private final boolean isDelinquent;
        private final List<SubscriptionState> allowedNextTransitions;

        public SubscriptionStateSnapshot(
            String subscriptionId,
            String tenantId,
            String planTier,
            SubscriptionState status,
            BigDecimal amountBdt,
            String billingCycle,
            LocalDate startDate,
            LocalDate renewalDate,
            LocalDate gracePeriodEndDate,
            List<SubscriptionState> allowedNextTransitions
        ) {
            this.subscriptionId = subscriptionId;
            this.tenantId = tenantId;
            this.planTier = planTier;
            this.status = status;
            this.amountBdt = amountBdt;
            this.billingCycle = billingCycle;
            this.startDate = startDate;
            this.renewalDate = renewalDate;
            this.gracePeriodEndDate = gracePeriodEndDate;
            this.isCheckoutAllowed = PosSubscriptionStateMachineClient.isCheckoutAllowed(status);
            this.isDelinquent = PosSubscriptionStateMachineClient.isDelinquent(status);
            this.allowedNextTransitions = Collections.unmodifiableList(new ArrayList<>(allowedNextTransitions));
        }

        public String getSubscriptionId() { return subscriptionId; }
        public String getTenantId() { return tenantId; }
        public String getPlanTier() { return planTier; }
        public SubscriptionState getStatus() { return status; }
        public BigDecimal getAmountBdt() { return amountBdt; }
        public String getBillingCycle() { return billingCycle; }
        public LocalDate getStartDate() { return startDate; }
        public LocalDate getRenewalDate() { return renewalDate; }
        public LocalDate getGracePeriodEndDate() { return gracePeriodEndDate; }
        public boolean isCheckoutAllowed() { return isCheckoutAllowed; }
        public boolean isDelinquent() { return isDelinquent; }
        public List<SubscriptionState> getAllowedNextTransitions() { return allowedNextTransitions; }
    }

    private PosSubscriptionStateMachineClient() {}

    /**
     * Invariant INV-SAFETY-01: Verifies whether clinical features remain active regardless of subscription state.
     */
    public static boolean isFeatureEntitled(SubscriptionState status, String featureKey) {
        if (featureKey == null || featureKey.trim().isEmpty()) return false;
        String clean = featureKey.toUpperCase().trim();
        if (CLINICAL_SAFETY_FEATURES.contains(clean)) {
            return true; // Clinical safety features are NEVER gated under any subscription status
        }
        if (status == null || !isCheckoutAllowed(status)) {
            return false;
        }
        return true;
    }

    /**
     * Evaluates whether POS sales checkouts are permitted for the given status.
     */
    public static boolean isCheckoutAllowed(SubscriptionState status) {
        if (status == null) return false;
        switch (status) {
            case TRIAL:
            case ACTIVE:
            case PAST_DUE:
            case GRACE_PERIOD:
                return true;
            case PENDING:
            case SUSPENDED:
            case CANCELLED:
            case EXPIRED:
            default:
                return false;
        }
    }

    /**
     * Returns true if the subscription requires immediate payment settlement.
     */
    public static boolean isDelinquent(SubscriptionState status) {
        if (status == null) return false;
        switch (status) {
            case PAST_DUE:
            case GRACE_PERIOD:
            case SUSPENDED:
            case EXPIRED:
                return true;
            default:
                return false;
        }
    }

    /**
     * Guard: checks if the state transition is valid under ADR-0084.
     */
    public static boolean canTransition(SubscriptionState from, SubscriptionState to) {
        if (from == null || to == null) return false;
        if (from == to) return true;

        switch (from) {
            case PENDING:
                return to == SubscriptionState.TRIAL || to == SubscriptionState.ACTIVE || to == SubscriptionState.CANCELLED;
            case TRIAL:
                return to == SubscriptionState.ACTIVE || to == SubscriptionState.EXPIRED || to == SubscriptionState.CANCELLED;
            case ACTIVE:
                return to == SubscriptionState.PAST_DUE || to == SubscriptionState.GRACE_PERIOD || to == SubscriptionState.SUSPENDED || to == SubscriptionState.CANCELLED;
            case PAST_DUE:
                return to == SubscriptionState.ACTIVE || to == SubscriptionState.GRACE_PERIOD || to == SubscriptionState.SUSPENDED || to == SubscriptionState.CANCELLED;
            case GRACE_PERIOD:
                return to == SubscriptionState.ACTIVE || to == SubscriptionState.SUSPENDED || to == SubscriptionState.EXPIRED || to == SubscriptionState.CANCELLED;
            case SUSPENDED:
                return to == SubscriptionState.ACTIVE || to == SubscriptionState.CANCELLED || to == SubscriptionState.EXPIRED;
            case CANCELLED:
                return to == SubscriptionState.ACTIVE || to == SubscriptionState.EXPIRED;
            case EXPIRED:
                return to == SubscriptionState.ACTIVE;
            default:
                return false;
        }
    }

    /**
     * Guard: checks if transitioning to the target state requires a mandatory non-empty reason.
     */
    public static boolean requiresReason(SubscriptionState target) {
        if (target == null) return false;
        switch (target) {
            case SUSPENDED:
            case CANCELLED:
            case EXPIRED:
            case PAST_DUE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Resolves localized bilingual status labels.
     */
    public static String getStatusLabel(SubscriptionState status, String lang) {
        boolean isBn = "BN".equalsIgnoreCase(lang);
        if (status == null) return isBn ? "অজানা" : "Unknown";

        switch (status) {
            case PENDING:
                return isBn ? "পেমেন্ট অপেক্ষারত" : "Pending Payment";
            case TRIAL:
                return isBn ? "মূল্যায়ন ট্রায়াল" : "Evaluation Trial";
            case ACTIVE:
                return isBn ? "সক্রিয়" : "Active";
            case PAST_DUE:
                return isBn ? "বকেয়া পেমেন্ট" : "Past Due";
            case GRACE_PERIOD:
                return isBn ? "গ্রেস পিরিয়ড" : "Grace Period";
            case SUSPENDED:
                return isBn ? "সাময়িক স্থগিত" : "Suspended";
            case CANCELLED:
                return isBn ? "বাতিলকৃত" : "Cancelled";
            case EXPIRED:
                return isBn ? "মেয়াদোত্তীর্ণ" : "Expired";
            default:
                return status.name();
        }
    }
}
