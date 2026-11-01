package com.pharmacy.pos.billing;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * PosQuotaUsageCounterClient
 * Architecture Decision: ADR-0007, ADR-0081, ADR-0082 & ADR-0095
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P095)
 * Requirement Trace: FR-070 / FR-071 / FR-072 (SaaS Plans, Billing & Tenant Lifecycle)
 *
 * Client-side quota usage monitoring, capacity gauge evaluation,
 * and bilingual capacity warning banners for JavaFX Desktop POS.
 */
public class PosQuotaUsageCounterClient {

    public static final class ResourceCounter {
        public final String resourceType;
        public final int currentUsed;
        public final int maxLimit;
        public final int remaining;
        public final double utilizationPct;
        public final boolean isOverQuota;
        public final String status; // NORMAL, WARNING, LIMIT_REACHED, OVER_QUOTA

        public ResourceCounter(
                String resourceType,
                int currentUsed,
                int maxLimit,
                int remaining,
                double utilizationPct,
                boolean isOverQuota,
                String status) {
            this.resourceType = resourceType;
            this.currentUsed = currentUsed;
            this.maxLimit = maxLimit;
            this.remaining = remaining;
            this.utilizationPct = utilizationPct;
            this.isOverQuota = isOverQuota;
            this.status = status;
        }
    }

    public static final class QuotaReport {
        public final String tenantId;
        public final String planCode;
        public final String planTier;
        public final String overallStatus;
        public final Map<String, ResourceCounter> counters;
        public final String headlineEn;
        public final String headlineBn;
        public final String detailsEn;
        public final String detailsBn;

        public QuotaReport(
                String tenantId,
                String planCode,
                String planTier,
                String overallStatus,
                Map<String, ResourceCounter> counters,
                String headlineEn,
                String headlineBn,
                String detailsEn,
                String detailsBn) {
            this.tenantId = tenantId;
            this.planCode = planCode;
            this.planTier = planTier;
            this.overallStatus = overallStatus;
            this.counters = counters != null ? Collections.unmodifiableMap(new HashMap<>(counters)) : Collections.emptyMap();
            this.headlineEn = headlineEn;
            this.headlineBn = headlineBn;
            this.detailsEn = detailsEn;
            this.detailsBn = detailsBn;
        }
    }

    public static double calculateUtilizationPct(int currentUsed, int maxLimit) {
        if (maxLimit <= 0) return 0.0;
        return Math.round(((double) currentUsed / maxLimit) * 1000.0) / 10.0;
    }

    public static String evaluateResourceStatus(int currentUsed, int maxLimit) {
        if (currentUsed > maxLimit) {
            return "OVER_QUOTA";
        }
        if (currentUsed >= maxLimit) {
            return "LIMIT_REACHED";
        }
        double pct = calculateUtilizationPct(currentUsed, maxLimit);
        if (pct >= 80.0) {
            return "WARNING";
        }
        return "NORMAL";
    }

    public static ResourceCounter createCounter(String resourceType, int currentUsed, int maxLimit) {
        int rem = Math.max(0, maxLimit - currentUsed);
        double pct = calculateUtilizationPct(currentUsed, maxLimit);
        boolean isOver = currentUsed > maxLimit;
        String st = evaluateResourceStatus(currentUsed, maxLimit);
        return new ResourceCounter(resourceType, currentUsed, maxLimit, rem, pct, isOver, st);
    }

    public static QuotaReport evaluateQuotaSummary(
            String tenantId,
            String planTier,
            int branchesUsed, int branchesLimit,
            int staffUsed, int staffLimit,
            int devicesUsed, int devicesLimit) {

        Map<String, ResourceCounter> map = new HashMap<>();
        map.put("BRANCHES", createCounter("BRANCHES", branchesUsed, branchesLimit));
        map.put("STAFF_SEATS", createCounter("STAFF_SEATS", staffUsed, staffLimit));
        map.put("DEVICES", createCounter("DEVICES", devicesUsed, devicesLimit));

        boolean anyOver = branchesUsed > branchesLimit || staffUsed > staffLimit || devicesUsed > devicesLimit;
        boolean anyLimit = branchesUsed >= branchesLimit || staffUsed >= staffLimit || devicesUsed >= devicesLimit;
        boolean anyWarning = map.values().stream().anyMatch(c -> c.status.equals("WARNING"));

        String overall;
        String hlEn;
        String hlBn;
        String dtEn;
        String dtBn;

        if (anyOver) {
            overall = "OVER_QUOTA";
            hlEn = "Plan Quota Exceeded";
            hlBn = "সাবস্ক্রিপশন কোটা অতিক্রম করেছে";
            dtEn = "One or more allocated capacity limits have been exceeded. Please upgrade.";
            dtBn = "এক বা একাধিক রিসোর্স সীমা অতিক্রান্ত হয়েছে। অনুগ্রহ করে আপগ্রেড করুন।";
        } else if (anyLimit) {
            overall = "LIMIT_REACHED";
            hlEn = "Plan Quota Limit Reached";
            hlBn = "কোটা সীমা পূর্ণ হয়েছে";
            dtEn = "All allocated capacity in use. Upgrade to add more capacity.";
            dtBn = "বরাদ্দকৃত সকল সক্ষমতা ব্যবহৃত হচ্ছে। অতিরিক্ত রিসোর্স যুক্ত করতে আপগ্রেড করুন।";
        } else if (anyWarning) {
            overall = "NEAR_LIMIT";
            hlEn = "Plan Quota Near Capacity";
            hlBn = "কোটা ধারণক্ষমতার কাছাকাছি পৌঁছেছে";
            dtEn = "Capacity usage is above 80% threshold.";
            dtBn = "রিসোর্স ব্যবহার ৮০% এর বেশি পৌঁছেছে।";
        } else {
            overall = "OK";
            hlEn = "Plan Quotas in Good Standing";
            hlBn = "কোটা ব্যবহার স্বাভাবিক রয়েছে";
            dtEn = "All seats, branches, and devices within allocated limits.";
            dtBn = "সকল ব্রাঞ্চ, কর্মী ও ডিভাইস কোটা স্বাভাবিক রয়েছে।";
        }

        return new QuotaReport(
                tenantId,
                "PLAN_" + planTier.toUpperCase(),
                planTier.toUpperCase(),
                overall,
                map,
                hlEn,
                hlBn,
                dtEn,
                dtBn
        );
    }
}

