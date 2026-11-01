package com.pharmacy.pos.billing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PosDowngradeAndQuotaClient
 * Architecture Decision: ADR-0007, ADR-0015, ADR-0081, ADR-0083 & ADR-0091
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P091)
 *
 * Client-side downgrade feasibility evaluator, over-quota branch detector,
 * and data-retention presentation for JavaFX Desktop POS.
 */
public class PosDowngradeAndQuotaClient {

    public static final Map<String, Integer> TIER_RANKS = new HashMap<>();
    static {
        TIER_RANKS.put("BASIC", 1);
        TIER_RANKS.put("STANDARD", 2);
        TIER_RANKS.put("PRO", 3);
    }

    public static final class DowngradeAssessmentResult {
        public final boolean isValid;
        public final String fromTier;
        public final String toTier;
        public final int currentBranches;
        public final int targetQuota;
        public final boolean isOverQuota;
        public final int excessBranchesCount;
        public final List<String> retainedBranchIds;
        public final List<String> lockedBranchIds;
        public final boolean zeroDataDeleted;
        public final String errorMessage;

        public DowngradeAssessmentResult(
                boolean isValid,
                String fromTier,
                String toTier,
                int currentBranches,
                int targetQuota,
                boolean isOverQuota,
                int excessBranchesCount,
                List<String> retainedBranchIds,
                List<String> lockedBranchIds,
                boolean zeroDataDeleted,
                String errorMessage) {
            this.isValid = isValid;
            this.fromTier = fromTier;
            this.toTier = toTier;
            this.currentBranches = currentBranches;
            this.targetQuota = targetQuota;
            this.isOverQuota = isOverQuota;
            this.excessBranchesCount = excessBranchesCount;
            this.retainedBranchIds = retainedBranchIds != null ? retainedBranchIds : new ArrayList<>();
            this.lockedBranchIds = lockedBranchIds != null ? lockedBranchIds : new ArrayList<>();
            this.zeroDataDeleted = zeroDataDeleted;
            this.errorMessage = errorMessage;
        }

        public static DowngradeAssessmentResult error(String message) {
            return new DowngradeAssessmentResult(
                false, "", "", 0, 0, false, 0,
                new ArrayList<>(), new ArrayList<>(), true, message
            );
        }
    }

    public static boolean isDowngradeEligible(String currentTier, String currentCycle, String targetTier, String targetCycle) {
        if (currentTier == null || targetTier == null) {
            return false;
        }
        int curRank = TIER_RANKS.getOrDefault(currentTier.toUpperCase().trim(), 0);
        int tgtRank = TIER_RANKS.getOrDefault(targetTier.toUpperCase().trim(), 0);

        if (tgtRank < curRank) {
            return true;
        }
        if (tgtRank == curRank) {
            String curC = (currentCycle != null ? currentCycle : "MONTHLY").toUpperCase().trim();
            String tgtC = (targetCycle != null ? targetCycle : "MONTHLY").toUpperCase().trim();
            return curC.equals("ANNUAL") && tgtC.equals("MONTHLY");
        }
        return false;
    }

    public static DowngradeAssessmentResult assessDowngrade(
            String currentTier,
            String currentCycle,
            String targetTier,
            String targetCycle,
            List<String> currentBranchIds,
            int targetBranchQuota) {

        if (!isDowngradeEligible(currentTier, currentCycle, targetTier, targetCycle)) {
            return DowngradeAssessmentResult.error(
                "Target plan is not an eligible downgrade. Must select lower tier or monthly cycle."
            );
        }

        int curCount = currentBranchIds != null ? currentBranchIds.size() : 0;
        int quota = Math.max(1, targetBranchQuota);
        boolean overQuota = curCount > quota;
        int excess = Math.max(0, curCount - quota);

        List<String> retained = new ArrayList<>();
        List<String> locked = new ArrayList<>();

        if (currentBranchIds != null) {
            for (int i = 0; i < currentBranchIds.size(); i++) {
                if (i < quota) {
                    retained.add(currentBranchIds.get(i));
                } else {
                    locked.add(currentBranchIds.get(i));
                }
            }
        }

        return new DowngradeAssessmentResult(
            true,
            currentTier,
            targetTier,
            curCount,
            quota,
            overQuota,
            excess,
            retained,
            locked,
            true, // Zero data deleted guarantee
            null
        );
    }

    public static String getLocalizedNotice(String key, boolean bangla) {
        if (bangla) {
            switch (key) {
                case "DATA_RETENTION": return "কোনো ব্রাঞ্চ, স্টাফ বা পূর্ববর্তী বিক্রয় ও স্টক ডেটা মুছে ফেলা হবে না।";
                case "OVER_QUOTA_WARNING": return "পরবর্তী প্ল্যানের নির্ধারিত সীমার অতিরিক্ত ব্রাঞ্চগুলো রিড-অনলি মোডে সংরক্ষিত থাকবে।";
                case "READ_ONLY_ACCESS": return "লক হওয়া ব্রাঞ্চের পূর্ববর্তী হিস্ট্রি ও রিপোর্ট সর্বদা দেখা যাবে।";
                case "MUTATION_GATED": return "কোটা অতিক্রম করায় নতুন কোনো সেলস ট্রানজ্যাকশন অনুমোদিত নয়।";
                default: return key;
            }
        } else {
            switch (key) {
                case "DATA_RETENTION": return "Zero data deletion: branches, staff, transactions and stock remain intact.";
                case "OVER_QUOTA_WARNING": return "Excess branches will be archived in read-only mode according to quota limits.";
                case "READ_ONLY_ACCESS": return "Historical ledger, invoices and audit logs remain continuously readable.";
                case "MUTATION_GATED": return "New sales transactions are gated on over-quota locked branches.";
                default: return key;
            }
        }
    }
}
