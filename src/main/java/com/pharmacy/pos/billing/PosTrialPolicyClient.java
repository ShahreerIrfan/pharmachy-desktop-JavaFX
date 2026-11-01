package com.pharmacy.pos.billing;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * PosTrialPolicyClient
 * Architecture Decision: ADR-0007, ADR-0023, ADR-0085 & ADR-0086
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P086)
 *
 * Client-side trial evaluation, anti-abuse validation, and expiration notice handler
 * for JavaFX Desktop POS.
 */
public class PosTrialPolicyClient {

    public enum TrialState {
        ACTIVE_TRIAL,
        EXPIRING_SOON,
        EXPIRED,
        NON_TRIAL
    }

    public static final class TrialEvaluationResult {
        public final String tenantId;
        public final boolean isTrial;
        public final TrialState trialState;
        public final long daysRemaining;
        public final boolean isExpired;
        public final boolean isWritePermitted;
        public final String noticeEn;
        public final String noticeBn;
        public final int maxBranches;
        public final int maxDevices;
        public final int offlineLeaseHours;
        public final boolean nonDestructiveGuarantee;

        public TrialEvaluationResult(
            String tenantId,
            boolean isTrial,
            TrialState trialState,
            long daysRemaining,
            boolean isExpired,
            boolean isWritePermitted,
            String noticeEn,
            String noticeBn,
            int maxBranches,
            int maxDevices,
            int offlineLeaseHours,
            boolean nonDestructiveGuarantee
        ) {
            this.tenantId = Objects.requireNonNull(tenantId, "tenantId required");
            this.isTrial = isTrial;
            this.trialState = Objects.requireNonNull(trialState, "trialState required");
            this.daysRemaining = daysRemaining;
            this.isExpired = isExpired;
            this.isWritePermitted = isWritePermitted;
            this.noticeEn = Objects.requireNonNull(noticeEn, "noticeEn required");
            this.noticeBn = Objects.requireNonNull(noticeBn, "noticeBn required");
            this.maxBranches = maxBranches;
            this.maxDevices = maxDevices;
            this.offlineLeaseHours = offlineLeaseHours;
            this.nonDestructiveGuarantee = nonDestructiveGuarantee;
        }
    }

    /**
     * Evaluates client-side trial terms and status from local clock or server lease.
     */
    public static TrialEvaluationResult evaluate(
        String tenantId,
        boolean isTrial,
        Instant trialEndsAt,
        Instant now
    ) {
        String cleanTenantId = tenantId != null ? tenantId.trim() : "tenant-unbound";
        Instant currentTime = now != null ? now : Instant.now();

        if (!isTrial || trialEndsAt == null) {
            return new TrialEvaluationResult(
                cleanTenantId,
                false,
                TrialState.NON_TRIAL,
                0,
                false,
                true,
                "Tenant is operating under commercial subscription plan.",
                "ফার্মেসি বাণিজ্যিক সাবস্ক্রিপশন প্ল্যানে পরিচালিত হচ্ছে।",
                5,
                10,
                72,
                true
            );
        }

        Duration diff = Duration.between(currentTime, trialEndsAt);
        long daysRemaining = Math.max(0, diff.toDays());
        boolean isExpired = currentTime.isAfter(trialEndsAt) || currentTime.equals(trialEndsAt);

        if (isExpired) {
            return new TrialEvaluationResult(
                cleanTenantId,
                true,
                TrialState.EXPIRED,
                0,
                true,
                false,
                "Your evaluation trial has expired. Operational mutations are locked. Historical records are preserved.",
                "আপনার ট্রায়ালের মেয়াদ শেষ হয়েছে। নতুন বিক্রয় সাময়িকভাবে স্থগিত, তবে পূর্ববর্তী তথ্য নিরাপদ আছে।",
                1,
                2,
                24,
                true
            );
        } else if (daysRemaining <= 3) {
            return new TrialEvaluationResult(
                cleanTenantId,
                true,
                TrialState.EXPIRING_SOON,
                daysRemaining,
                false,
                true,
                "Urgent: Trial expires in " + daysRemaining + " day(s). Upgrade to prevent counter checkout interruption.",
                "জরুরি: আপনার ট্রায়ালের মেয়াদ আর মাত্র " + daysRemaining + " দিন বাকি। বিক্রয় সচল রাখতে প্ল্যান আপগ্রেড করুন।",
                1,
                2,
                24,
                true
            );
        } else {
            return new TrialEvaluationResult(
                cleanTenantId,
                true,
                TrialState.ACTIVE_TRIAL,
                daysRemaining,
                false,
                true,
                "Evaluation trial is active with " + daysRemaining + " days remaining.",
                "মূল্যায়ন ট্রায়াল সক্রিয় রয়েছে, বাকি আছে " + daysRemaining + " দিন।",
                1,
                2,
                24,
                true
            );
        }
    }

    /**
     * Evaluates anti-abuse format preconditions before sending trial request.
     */
    public static boolean isAntiAbuseCandidateValid(String licenseNumber, String phoneNumber) {
        if (licenseNumber == null || licenseNumber.trim().length() < 5) {
            return false;
        }
        if (phoneNumber == null || phoneNumber.trim().length() < 10) {
            return false;
        }
        return true;
    }
}
