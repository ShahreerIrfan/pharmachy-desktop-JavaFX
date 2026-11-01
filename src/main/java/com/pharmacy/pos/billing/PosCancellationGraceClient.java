package com.pharmacy.pos.billing;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * PosCancellationGraceClient
 * Architecture Decision: ADR-0007, ADR-0015, ADR-0073, ADR-0084 & ADR-0092
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P092)
 *
 * Client-side lifecycle state evaluation, grace period banner messaging,
 * continuous clinical dispensing protection, and safe read/export access for JavaFX Desktop POS.
 */
public class PosCancellationGraceClient {

    public static final class LifecycleState {
        public final String status;
        public final boolean canDispenseSales;
        public final boolean canReadHistoricalData;
        public final boolean canExportTenantData;
        public final boolean isInGracePeriod;
        public final long graceDaysRemaining;
        public final String headlineEn;
        public final String headlineBn;
        public final String detailsEn;
        public final String detailsBn;

        public LifecycleState(
                String status,
                boolean canDispenseSales,
                boolean canReadHistoricalData,
                boolean canExportTenantData,
                boolean isInGracePeriod,
                long graceDaysRemaining,
                String headlineEn,
                String headlineBn,
                String detailsEn,
                String detailsBn) {
            this.status = status;
            this.canDispenseSales = canDispenseSales;
            this.canReadHistoricalData = canReadHistoricalData;
            this.canExportTenantData = canExportTenantData;
            this.isInGracePeriod = isInGracePeriod;
            this.graceDaysRemaining = graceDaysRemaining;
            this.headlineEn = headlineEn;
            this.headlineBn = headlineBn;
            this.detailsEn = detailsEn;
            this.detailsBn = detailsBn;
        }
    }

    public static boolean isDispensingPermitted(String status) {
        if (status == null) {
            return false;
        }
        String s = status.toUpperCase().trim();
        return s.equals("ACTIVE") || s.equals("TRIAL") || s.equals("GRACE_PERIOD");
    }

    public static boolean isReadAndExportSafe(String status) {
        // Zero data deletion invariant: historical data & exports ALWAYS safe and readable
        return true;
    }

    public static long calculateGraceDaysRemaining(LocalDate graceEndDate, LocalDate currentDate) {
        if (graceEndDate == null || currentDate == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(currentDate, graceEndDate);
        return Math.max(0, days);
    }

    public static LifecycleState evaluateLifecycle(
            String status,
            LocalDate renewalDate,
            LocalDate graceEndDate,
            LocalDate currentDate,
            boolean isAutoRenew,
            boolean cancelAtPeriodEnd) {

        String s = status != null ? status.toUpperCase().trim() : "ACTIVE";
        boolean canDispense = isDispensingPermitted(s);
        boolean inGrace = s.equals("GRACE_PERIOD");
        long daysRemaining = inGrace ? calculateGraceDaysRemaining(graceEndDate, currentDate) : 0;

        String hlEn;
        String hlBn;
        String dtEn;
        String dtBn;

        if (s.equals("ACTIVE")) {
            if (cancelAtPeriodEnd) {
                hlEn = "Subscription Set to Cancel";
                hlBn = "বিলিং সাইকেল শেষে সাবস্ক্রিপশন বাতিল নির্ধারিত";
                dtEn = "Auto-renewal disabled. Access continues until renewal date.";
                dtBn = "অটো-রিনিউয়াল বন্ধ রয়েছে। রিনিউয়াল তারিখ পর্যন্ত অ্যাক্সেস থাকবে।";
            } else {
                hlEn = "Subscription Active";
                hlBn = "সাবস্ক্রিপশন সচল রয়েছে";
                dtEn = "All clinical and POS dispensing systems in good standing.";
                dtBn = "সকল ক্লিনিক্যাল ও পয়েন্ট-অব-সেল সিস্টেম সম্পূর্ণ সচল।";
            }
        } else if (inGrace) {
            hlEn = "Grace Period Active (" + daysRemaining + " Days Remaining)";
            hlBn = "গ্রেস পিরিয়ড কার্যকর রয়েছে (অবশিষ্ট " + daysRemaining + " দিন)";
            dtEn = "Dispensing remains operational for patient safety. Please settle renewal.";
            dtBn = "রোগীর সেবা সুরক্ষায় বিক্রয় সচল রয়েছে। বকেয়া রিনিউয়াল পরিশোধ করুন।";
        } else if (s.equals("EXPIRED") || s.equals("SUSPENDED")) {
            hlEn = "Subscription Expired";
            hlBn = "সাবস্ক্রিপশনের মেয়াদ উত্তীর্ণ হয়েছে";
            dtEn = "New sales suspended. Historical transactions and exports safely accessible.";
            dtBn = "নতুন বিক্রয় সাময়িক স্থগিত। পূর্ববর্তী সকল হিস্ট্রি ও ডাটা এক্সপোর্ট নিরাপদ।";
        } else if (s.equals("CANCELLED")) {
            hlEn = "Subscription Cancelled";
            hlBn = "সাবস্ক্রিপশন বাতিল করা হয়েছে";
            dtEn = "Account cancelled. All records retained. Settle invoice to reactivate.";
            dtBn = "অ্যাকাউন্ট বাতিল। সকল তথ্য অক্ষত রয়েছে। সচল করতে বকেয়া পরিশোধ করুন।";
        } else {
            hlEn = "Status: " + s;
            hlBn = "স্ট্যাটাস: " + s;
            dtEn = "Subscription lifecycle management.";
            dtBn = "সাবস্ক্রিপশন লাইফসাইকেল ম্যানেজমেন্ট।";
        }

        return new LifecycleState(
            s,
            canDispense,
            true, // canReadHistoricalData
            true, // canExportTenantData
            inGrace,
            daysRemaining,
            hlEn,
            hlBn,
            dtEn,
            dtBn
        );
    }
}
