package com.pharmacy.pos.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * PosProratedUpgradeClient
 * Architecture Decision: ADR-0007, ADR-0015, ADR-0081, ADR-0083 & ADR-0090
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P090)
 *
 * Client-side prorated upgrade quote calculator, credit policy resolution,
 * immediate entitlement verification, and bilingual presentation for JavaFX Desktop POS.
 */
public class PosProratedUpgradeClient {

    public static final Map<String, Integer> TIER_RANKS = new HashMap<>();
    static {
        TIER_RANKS.put("BASIC", 1);
        TIER_RANKS.put("STANDARD", 2);
        TIER_RANKS.put("PRO", 3);
    }

    public static final class ProratedQuoteResult {
        public final boolean isValid;
        public final String fromTier;
        public final String toTier;
        public final String fromCycle;
        public final String toCycle;
        public final int totalDays;
        public final int usedDays;
        public final int remainingDays;
        public final BigDecimal currentDailyRate;
        public final BigDecimal unusedCredit;
        public final BigDecimal proratedGrossTarget;
        public final BigDecimal netPayable;
        public final String errorMessage;

        public ProratedQuoteResult(
                boolean isValid,
                String fromTier,
                String toTier,
                String fromCycle,
                String toCycle,
                int totalDays,
                int usedDays,
                int remainingDays,
                BigDecimal currentDailyRate,
                BigDecimal unusedCredit,
                BigDecimal proratedGrossTarget,
                BigDecimal netPayable,
                String errorMessage) {
            this.isValid = isValid;
            this.fromTier = fromTier;
            this.toTier = toTier;
            this.fromCycle = fromCycle;
            this.toCycle = toCycle;
            this.totalDays = totalDays;
            this.usedDays = usedDays;
            this.remainingDays = remainingDays;
            this.currentDailyRate = currentDailyRate;
            this.unusedCredit = unusedCredit;
            this.proratedGrossTarget = proratedGrossTarget;
            this.netPayable = netPayable;
            this.errorMessage = errorMessage;
        }

        public static ProratedQuoteResult error(String message) {
            return new ProratedQuoteResult(
                false, "", "", "", "",
                0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                message
            );
        }
    }

    public static final class UpgradeExecutionResult {
        public final boolean isSuccess;
        public final String upgradeId;
        public final String invoiceNumber;
        public final BigDecimal netPaidBdt;
        public final boolean immediateEntitlementsGranted;
        public final String messageEn;
        public final String messageBn;

        public UpgradeExecutionResult(
                boolean isSuccess,
                String upgradeId,
                String invoiceNumber,
                BigDecimal netPaidBdt,
                boolean immediateEntitlementsGranted,
                String messageEn,
                String messageBn) {
            this.isSuccess = isSuccess;
            this.upgradeId = upgradeId;
            this.invoiceNumber = invoiceNumber;
            this.netPaidBdt = netPaidBdt;
            this.immediateEntitlementsGranted = immediateEntitlementsGranted;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }
    }

    public static boolean isUpgradeEligible(String currentTier, String currentCycle, String targetTier, String targetCycle) {
        if (currentTier == null || targetTier == null) {
            return false;
        }
        int curRank = TIER_RANKS.getOrDefault(currentTier.toUpperCase().trim(), 0);
        int tgtRank = TIER_RANKS.getOrDefault(targetTier.toUpperCase().trim(), 0);

        if (tgtRank > curRank) {
            return true;
        }
        if (tgtRank == curRank) {
            String curC = (currentCycle != null ? currentCycle : "MONTHLY").toUpperCase().trim();
            String tgtC = (targetCycle != null ? targetCycle : "MONTHLY").toUpperCase().trim();
            return curC.equals("MONTHLY") && tgtC.equals("ANNUAL");
        }
        return false;
    }

    public static ProratedQuoteResult calculateProratedQuote(
            String currentTier,
            String currentCycle,
            BigDecimal currentPrice,
            String targetTier,
            String targetCycle,
            BigDecimal targetPrice,
            int totalDays,
            int usedDays) {

        if (!isUpgradeEligible(currentTier, currentCycle, targetTier, targetCycle)) {
            return ProratedQuoteResult.error("Target plan is not an eligible upgrade. Must select higher tier or annual cycle.");
        }

        int cleanTotalDays = Math.max(1, totalDays);
        int cleanUsedDays = Math.max(0, Math.min(cleanTotalDays, usedDays));
        int remainingDays = Math.max(0, cleanTotalDays - cleanUsedDays);

        BigDecimal curAmt = currentPrice != null ? currentPrice : BigDecimal.ZERO;
        BigDecimal tgtAmt = targetPrice != null ? targetPrice : BigDecimal.ZERO;

        BigDecimal curDaily = curAmt.divide(BigDecimal.valueOf(cleanTotalDays), 4, RoundingMode.HALF_UP);
        BigDecimal unusedCredit = curAmt.multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(cleanTotalDays), 2, RoundingMode.HALF_UP);

        boolean isAnnualCycle = "ANNUAL".equalsIgnoreCase(targetCycle);
        boolean isCycleUpgrade = !"ANNUAL".equalsIgnoreCase(currentCycle) && isAnnualCycle;

        BigDecimal proratedGrossTarget;
        if (isCycleUpgrade || isAnnualCycle) {
            proratedGrossTarget = tgtAmt.setScale(2, RoundingMode.HALF_UP);
        } else {
            proratedGrossTarget = tgtAmt.multiply(BigDecimal.valueOf(remainingDays))
                    .divide(BigDecimal.valueOf(cleanTotalDays), 2, RoundingMode.HALF_UP);
        }

        BigDecimal netPayable = proratedGrossTarget.subtract(unusedCredit);
        if (netPayable.compareTo(BigDecimal.ZERO) < 0) {
            netPayable = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        } else {
            netPayable = netPayable.setScale(2, RoundingMode.HALF_UP);
        }

        return new ProratedQuoteResult(
            true,
            currentTier,
            targetTier,
            currentCycle,
            targetCycle,
            cleanTotalDays,
            cleanUsedDays,
            remainingDays,
            curDaily,
            unusedCredit,
            proratedGrossTarget,
            netPayable,
            null
        );
    }

    public static String formatBdt(BigDecimal amount) {
        if (amount == null) {
            return "BDT 0.00";
        }
        return "BDT " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static String getLocalizedLabel(String key, boolean bangla) {
        if (bangla) {
            switch (key) {
                case "UNUSED_CREDIT": return "অব্যবহৃত ক্রেডিট (পূর্ববর্তী প্ল্যান)";
                case "PRORATED_GROSS": return "নতুন প্ল্যানের নির্ধারিত মূল্য";
                case "NET_PAYABLE": return "সর্বমোট প্রদেয় আপগ্রেড ফি";
                case "IMMEDIATE_GRANT": return "নতুন ফিচার অবিলম্বে কার্যকর হয়েছে";
                case "REMAINING_DAYS": return "অবশিষ্ট দিন";
                default: return key;
            }
        } else {
            switch (key) {
                case "UNUSED_CREDIT": return "Unused Credit (Current Plan)";
                case "PRORATED_GROSS": return "Prorated Target Charge";
                case "NET_PAYABLE": return "Net Upgrade Payable";
                case "IMMEDIATE_GRANT": return "Immediate Entitlements Granted";
                case "REMAINING_DAYS": return "Remaining Days";
                default: return key;
            }
        }
    }
}
