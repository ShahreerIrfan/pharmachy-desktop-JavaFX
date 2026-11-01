package com.pharmacy.pos.billing;

import java.util.*;

/**
 * PosSubscriptionActivationGateClient
 * Architecture Decision: ADR-0013, ADR-0015, ADR-0033, ADR-0084 & ADR-0085
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P085)
 *
 * JavaFX Desktop POS Subscription Activation Gate Client:
 * - Client-side validation ensuring unentitled subscriptions do not dispatch operational ERP writes.
 * - Permits safe read-only methods (GET, HEAD, OPTIONS) for regulatory audit and viewing.
 * - Permits authorized billing routes (/api/v2/billing/*) for renewals, quotes, and payment verification.
 * - Redirects blocked cashiers and managers to billing settlement workflows.
 */
public final class PosSubscriptionActivationGateClient {

    public static final Set<String> SAFE_HTTP_METHODS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("GET", "HEAD", "OPTIONS"))
    );

    public static final Set<String> ENTITLED_STATUSES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("ACTIVE", "TRIAL", "PAST_DUE", "GRACE_PERIOD"))
    );

    public static final Set<String> UNENTITLED_STATUSES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("SUSPENDED", "CANCELLED", "EXPIRED", "PENDING"))
    );

    public static final List<String> EXEMPT_PATH_PREFIXES = Collections.unmodifiableList(
        Arrays.asList(
            "/api/v2/billing/",
            "/api/v2/auth/",
            "/api/v2/health",
            "/health/",
            "/api/v2/docs",
            "/api/v2/schema",
            "/api/v2/platform/",
            "/api/v2/support/"
        )
    );

    public static class GateDecision {
        private final boolean isPermitted;
        private final String decisionCode;
        private final String messageEn;
        private final String messageBn;

        public GateDecision(boolean isPermitted, String decisionCode, String messageEn, String messageBn) {
            this.isPermitted = isPermitted;
            this.decisionCode = decisionCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public boolean isPermitted() { return isPermitted; }
        public String getDecisionCode() { return decisionCode; }
        public String getMessageEn() { return messageEn; }
        public String getMessageBn() { return messageBn; }
    }

    private PosSubscriptionActivationGateClient() {}

    /**
     * Evaluates whether an HTTP request with given method, path, and subscription status is permitted.
     */
    public static GateDecision evaluateGate(String subscriptionStatus, String httpMethod, String path) {
        String methodUpper = (httpMethod == null ? "GET" : httpMethod.toUpperCase().trim());
        String cleanPath = (path == null ? "" : path.trim());

        // 1. Safe read-only methods
        if (SAFE_HTTP_METHODS.contains(methodUpper)) {
            return new GateDecision(
                true,
                "READ_ONLY_PERMITTED",
                "Read-only request is permitted for regulatory and audit viewing.",
                "রেগুলেটরি ও অডিট দেখার জন্য রিড-অনলি রিকোয়েস্ট অনুমোদিত।"
            );
        }

        // 2. Exempt paths (Billing, Auth, Health, etc.)
        if (isPathExempt(cleanPath)) {
            return new GateDecision(
                true,
                "EXEMPT_PATH",
                "Path is an authorized billing or reconciliation route.",
                "রুটটি অনুমোদিত বিলিং বা পুনর্মিলন পথ।"
            );
        }

        // 3. Subscription Status Check
        String statusUpper = (subscriptionStatus == null ? "ACTIVE" : subscriptionStatus.toUpperCase().trim());
        if (UNENTITLED_STATUSES.contains(statusUpper)) {
            return new GateDecision(
                false,
                "ERR_SUBSCRIPTION_UNENTITLED_WRITE_BLOCKED",
                "Operational ERP write rejected: Subscription status '" + statusUpper + "' does not permit data mutations.",
                "অপারেশনাল ডেটা পরিবর্তন প্রত্যাখ্যাত: সাবস্ক্রিপশন স্ট্যাটাস '" + statusUpper + "' নতুন তথ্য যুক্ত বা পরিবর্তন করার অনুমতি দেয় না।"
            );
        }

        return new GateDecision(
            true,
            "ENTITLED",
            "Subscription is entitled for operational ERP writes.",
            "সাবস্ক্রিপশন অপারেশনাল ডেটা পরিবর্তনের জন্য সক্রিয়।"
        );
    }

    public static boolean isPathExempt(String path) {
        if (path == null) return false;
        for (String prefix : EXEMPT_PATH_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    public static String getBillingRenewalUrl(String tenantId) {
        return "/api/v2/billing/subscriptions/" + (tenantId == null ? "" : tenantId.trim()) + "/";
    }
}
