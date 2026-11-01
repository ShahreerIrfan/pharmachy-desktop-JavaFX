package com.pharmacy.pos.billing;

import java.math.BigDecimal;

/**
 * PosCustomerPortalClient
 * Architecture Decision: ADR-0007, ADR-0081, ADR-0087, ADR-0088, ADR-0095 & ADR-0097
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P097)
 * Requirement Trace: FR-070, FR-071, FR-072 (Subscription Customer Portal)
 *
 * Client-side customer subscription portal summary evaluation, renewal alerts,
 * and bilingual banner rendering for JavaFX Desktop POS.
 */
public class PosCustomerPortalClient {

    public static final class ActivePackage {
        public final String planCode;
        public final String planTier;
        public final String planName;
        public final String billingCycle;
        public final String status; // ACTIVE, TRIAL, GRACE_PERIOD, EXPIRED, CANCELLED
        public final BigDecimal priceBdt;
        public final String periodStart;
        public final String periodEnd;
        public final boolean autoRenew;

        public ActivePackage(
                String planCode,
                String planTier,
                String planName,
                String billingCycle,
                String status,
                BigDecimal priceBdt,
                String periodStart,
                String periodEnd,
                boolean autoRenew) {
            this.planCode = planCode;
            this.planTier = planTier;
            this.planName = planName;
            this.billingCycle = billingCycle;
            this.status = status;
            this.priceBdt = priceBdt;
            this.periodStart = periodStart;
            this.periodEnd = periodEnd;
            this.autoRenew = autoRenew;
        }

        public boolean isActive() {
            return "ACTIVE".equalsIgnoreCase(status);
        }

        public boolean isTrial() {
            return "TRIAL".equalsIgnoreCase(status);
        }

        public boolean isGracePeriod() {
            return "GRACE_PERIOD".equalsIgnoreCase(status);
        }
    }

    public static final class NextRenewal {
        public final String renewalDate;
        public final int daysRemaining;
        public final BigDecimal projectedAmountBdt;
        public final boolean autoRenew;

        public NextRenewal(
                String renewalDate,
                int daysRemaining,
                BigDecimal projectedAmountBdt,
                boolean autoRenew) {
            this.renewalDate = renewalDate;
            this.daysRemaining = daysRemaining;
            this.projectedAmountBdt = projectedAmountBdt;
            this.autoRenew = autoRenew;
        }

        public boolean isDueSoon() {
            return daysRemaining <= 7;
        }
    }

    public static final class PortalOverview {
        public final String tenantId;
        public final ActivePackage activePackage;
        public final NextRenewal nextRenewal;
        public final int invoicesCount;
        public final int paymentsCount;
        public final String quotaStatus;
        public final String headlineEn;
        public final String headlineBn;
        public final String detailsEn;
        public final String detailsBn;

        public PortalOverview(
                String tenantId,
                ActivePackage activePackage,
                NextRenewal nextRenewal,
                int invoicesCount,
                int paymentsCount,
                String quotaStatus,
                String headlineEn,
                String headlineBn,
                String detailsEn,
                String detailsBn) {
            this.tenantId = tenantId;
            this.activePackage = activePackage;
            this.nextRenewal = nextRenewal;
            this.invoicesCount = invoicesCount;
            this.paymentsCount = paymentsCount;
            this.quotaStatus = quotaStatus;
            this.headlineEn = headlineEn;
            this.headlineBn = headlineBn;
            this.detailsEn = detailsEn;
            this.detailsBn = detailsBn;
        }
    }

    public static PortalOverview evaluatePortalOverview(
            String tenantId,
            String planCode,
            String planTier,
            String planName,
            String status,
            BigDecimal priceBdt,
            String renewalDate,
            int daysRemaining,
            boolean autoRenew,
            int invoicesCount,
            int paymentsCount,
            String quotaStatus) {

        ActivePackage pkg = new ActivePackage(
                planCode, planTier, planName, "MONTHLY", status, priceBdt, "2026-11-01", renewalDate, autoRenew
        );
        NextRenewal renewal = new NextRenewal(renewalDate, daysRemaining, priceBdt, autoRenew);

        String hlEn;
        String hlBn;
        String dtEn;
        String dtBn;

        if ("ACTIVE".equalsIgnoreCase(status)) {
            hlEn = "Subscription Active — " + planName;
            hlBn = "সাবস্ক্রিপশন সক্রিয় — " + planName;
            dtEn = "Renews on " + renewalDate + " (" + daysRemaining + " days remaining). All services operational.";
            dtBn = renewalDate + " তারিখে নবায়নযোগ্য (অবশিষ্ট " + daysRemaining + " দিন)। সকল সেবা সচল রয়েছে।";
        } else if ("GRACE_PERIOD".equalsIgnoreCase(status)) {
            hlEn = "Subscription in Grace Period";
            hlBn = "সাবস্ক্রিপশন গ্রেস পিরিয়ডে রয়েছে";
            dtEn = "Please settle payment immediately to maintain uninterrupted service.";
            dtBn = "নিরবচ্ছিন্ন সেবা বজায় রাখতে অবিলম্বে বকেয়া পরিশোধ করুন।";
        } else if ("EXPIRED".equalsIgnoreCase(status)) {
            hlEn = "Subscription Expired";
            hlBn = "সাবস্ক্রিপশনের মেয়াদ উত্তীর্ণ হয়েছে";
            dtEn = "Renew subscription to restore full access and offline dispensing.";
            dtBn = "পুনরায় সকল সুবিধা ও অফলাইন সেবা চালু করতে সাবস্ক্রিপশন নবায়ন করুন।";
        } else {
            hlEn = "Subscription Status: " + status;
            hlBn = "সাবস্ক্রিপশন স্ট্যাটাস: " + status;
            dtEn = "Manage your package, review invoices, or upgrade.";
            dtBn = "আপনার প্যাকেজ পরিচালনা, ইনভয়েস পর্যালোচনা বা আপগ্রেড করুন।";
        }

        return new PortalOverview(
                tenantId,
                pkg,
                renewal,
                invoicesCount,
                paymentsCount,
                quotaStatus != null ? quotaStatus : "OK",
                hlEn,
                hlBn,
                dtEn,
                dtBn
        );
    }
}

