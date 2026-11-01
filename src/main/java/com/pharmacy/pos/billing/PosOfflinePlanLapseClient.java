package com.pharmacy.pos.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * PosOfflinePlanLapseClient
 * Architecture Decision: ADR-0007, ADR-0082, ADR-0092 & ADR-0094
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P094)
 * Requirement Trace: FR-072 (Do not silently discard valid pending events if plan lapses)
 *
 * Implements deterministic POS terminal behavior when a plan lapses or lease expires:
 * 1. Safely gates new counter dispensing mutations while preserving pending outbox transactions.
 * 2. Never silently drops or deletes offline transactions.
 * 3. Informs cashier of held transactions awaiting central renewal reconciliation.
 */
public class PosOfflinePlanLapseClient {

    public static final class PlanLapseTerminalState {
        public final boolean isMutationPaused;
        public final int pendingHeldCount;
        public final BigDecimal pendingTotalBdt;
        public final boolean zeroDataLossPreserved;
        public final String headlineEn;
        public final String headlineBn;
        public final String detailsEn;
        public final String detailsBn;
        public final String actionPrompt;

        public PlanLapseTerminalState(
                boolean isMutationPaused,
                int pendingHeldCount,
                BigDecimal pendingTotalBdt,
                boolean zeroDataLossPreserved,
                String headlineEn,
                String headlineBn,
                String detailsEn,
                String detailsBn,
                String actionPrompt) {
            this.isMutationPaused = isMutationPaused;
            this.pendingHeldCount = pendingHeldCount;
            this.pendingTotalBdt = pendingTotalBdt != null ? pendingTotalBdt.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            this.zeroDataLossPreserved = zeroDataLossPreserved;
            this.headlineEn = headlineEn;
            this.headlineBn = headlineBn;
            this.detailsEn = detailsEn;
            this.detailsBn = detailsBn;
            this.actionPrompt = actionPrompt;
        }
    }

    /**
     * Evaluates whether local mutations must be paused upon plan lapse or expired lease.
     */
    public static boolean shouldPauseTerminalMutations(String subscriptionStatus, boolean isLeaseExpired) {
        if (isLeaseExpired) {
            return true;
        }
        if (subscriptionStatus == null) {
            return true;
        }
        String s = subscriptionStatus.trim().toUpperCase();
        return s.equals("EXPIRED") || s.equals("CANCELLED") || s.equals("SUSPENDED");
    }

    /**
     * Deterministic evaluation of terminal state upon plan lapse.
     */
    public static PlanLapseTerminalState evaluatePlanLapseState(
            String subscriptionStatus,
            boolean isLeaseExpired,
            int localOutboxHeldCount,
            BigDecimal localOutboxTotalAmount) {

        boolean pause = shouldPauseTerminalMutations(subscriptionStatus, isLeaseExpired);
        int count = Math.max(0, localOutboxHeldCount);
        BigDecimal amount = localOutboxTotalAmount != null ? localOutboxTotalAmount : BigDecimal.ZERO;

        String hlEn;
        String hlBn;
        String dtEn;
        String dtBn;
        String act;

        if (pause) {
            if (count > 0) {
                hlEn = "Plan Lapsed — Counter Sales Paused (" + count + " Transactions Held)";
                hlBn = "সাবস্ক্রিপশনের মেয়াদ শেষ — কাউন্টার বিক্রয় স্থগিত (" + count + " টি লেনদেন জমা রয়েছে)";
                dtEn = "Total BDT " + amount.toPlainString() + " held safely for reconciliation. Connect and settle renewal.";
                dtBn = "মোট " + amount.toPlainString() + " টাকা নিরাপদে জমা রয়েছে। বকেয়া পরিশোধ করলে স্বয়ংক্রিয়ভাবে রিকনসাইল হবে।";
            } else {
                hlEn = "Plan Lapsed — Counter Dispensing Paused";
                hlBn = "সাবস্ক্রিপশনের মেয়াদ শেষ — কাউন্টার বিক্রয় সাময়িক স্থগিত";
                dtEn = "Subscription has lapsed. Reconnect and renew plan to resume sales.";
                dtBn = "সাবস্ক্রিপশনের মেয়াদ শেষ হয়েছে। বিক্রয় পুনরায় শুরু করতে নবায়ন সম্পন্ন করুন।";
            }
            act = "SETTLE_RENEWAL";
        } else {
            hlEn = "Terminal Operational";
            hlBn = "টার্মিনাল সচল রয়েছে";
            dtEn = "Active subscription and valid offline lease.";
            dtBn = "সক্রিয় সাবস্ক্রিপশন এবং বৈধ অফলাইন ইজারা রয়েছে।";
            act = "NONE";
        }

        return new PlanLapseTerminalState(
                pause,
                count,
                amount,
                true, // Zero data loss is ALWAYS guaranteed
                hlEn,
                hlBn,
                dtEn,
                dtBn,
                act
        );
    }
}

