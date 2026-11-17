package com.pharmacy.pos.prescriptions;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * P247: OTC & Prescription Classification Status Versioning Client (JavaFX Desktop).
 * Architecture Decision: ADR-0247 (P247).
 * Enforces dynamic classification without hardcoded static lists.
 */
public class OtcRxClassificationClient {

    public enum ClassificationType {
        OTC("Over-The-Counter (OTC)", "প্রেসক্রিপশন ব্যতীত বিক্রয়যোগ্য (OTC)"),
        PRESCRIPTION_ONLY("Prescription-Only Medicine (POM)", "প্রেসক্রিপশন আবশ্যক (POM)"),
        NARCOTIC_SCHEDULED("Narcotic / Controlled Substance (Schedule-G)", "নিয়ন্ত্রিত মাদক / সিডিউল-জি ওষুধ"),
        ANTIMICROBIAL_ACCESS("Antimicrobial AWaRe - Access", "অ্যান্টিমাইক্রোবিয়াল - অ্যাক্সেস গ্রুপ"),
        ANTIMICROBIAL_WATCH("Antimicrobial AWaRe - Watch", "অ্যান্টিমাইক্রোবিয়াল - ওয়াচ গ্রুপ"),
        ANTIMICROBIAL_RESERVE("Antimicrobial AWaRe - Reserve", "অ্যান্টিমাইক্রোবিয়াল - রিজার্ভ গ্রুপ"),
        RECALLED("DGDA Statutory Safety Recall", "ডিজিডিএ প্রত্যাহারকৃত ওষুধ (বিক্রয় নিষিদ্ধ)"),
        BANNED("Banned & Prohibited", "নিষিদ্ধ ওষুধ (বিক্রয় সম্পূর্ণ অবৈধ)");

        private final String enLabel;
        private final String bnLabel;

        ClassificationType(String enLabel, String bnLabel) {
            this.enLabel = enLabel;
            this.bnLabel = bnLabel;
        }

        public String getLocalized(String lang) {
            return "bn".equalsIgnoreCase(lang) ? bnLabel : enLabel;
        }

        public boolean requiresPrescription() {
            return this != OTC;
        }

        public boolean isDispensingBlocked() {
            return this == RECALLED || this == BANNED;
        }
    }

    public static class ClassificationSnapshotDto implements Serializable {
        private final String snapshotId;
        private final String productId;
        private final int versionNumber;
        private final ClassificationType classificationType;
        private final String regulatorySource;
        private final String effectiveFrom;
        private final String effectiveTo;
        private final boolean isCurrent;
        private final boolean isDispensingBlocked;
        private final String reviewNotes;
        private final String immutableHash;

        public ClassificationSnapshotDto(
                String snapshotId,
                String productId,
                int versionNumber,
                ClassificationType classificationType,
                String regulatorySource,
                String effectiveFrom,
                String effectiveTo,
                boolean isCurrent,
                boolean isDispensingBlocked,
                String reviewNotes,
                String immutableHash
        ) {
            this.snapshotId = snapshotId;
            this.productId = productId;
            this.versionNumber = versionNumber;
            this.classificationType = classificationType;
            this.regulatorySource = regulatorySource;
            this.effectiveFrom = effectiveFrom;
            this.effectiveTo = effectiveTo;
            this.isCurrent = isCurrent;
            this.isDispensingBlocked = isDispensingBlocked;
            this.reviewNotes = reviewNotes;
            this.immutableHash = immutableHash;
        }

        public String getSnapshotId() { return snapshotId; }
        public String getProductId() { return productId; }
        public int getVersionNumber() { return versionNumber; }
        public ClassificationType getClassificationType() { return classificationType; }
        public String getRegulatorySource() { return regulatorySource; }
        public String getEffectiveFrom() { return effectiveFrom; }
        public String getEffectiveTo() { return effectiveTo; }
        public boolean isCurrent() { return isCurrent; }
        public boolean isDispensingBlocked() { return isDispensingBlocked; }
        public String getReviewNotes() { return reviewNotes; }
        public String getImmutableHash() { return immutableHash; }

        public boolean requiresPrescription() {
            return classificationType != null && classificationType.requiresPrescription();
        }
    }

    /**
     * Validates input parameters before publishing a regulatory reclassification.
     */
    public static boolean validateReclassificationPreflight(
            String productId,
            ClassificationType classificationType,
            String regulatorySource,
            String reviewNotes
    ) {
        if (productId == null || productId.trim().isEmpty()) return false;
        if (classificationType == null) return false;
        if (regulatorySource == null || regulatorySource.trim().length() < 3) return false;
        if (reviewNotes == null || reviewNotes.trim().length() < 5) return false;
        return true;
    }

    /**
     * Point-in-time offline resolution helper: checks whether a snapshot was effective
     * at a specified ISO datetime point.
     */
    public static boolean isEffectiveAt(
            ClassificationSnapshotDto snapshot,
            String targetIsoDateTime
    ) {
        if (snapshot == null || targetIsoDateTime == null) return false;
        if (snapshot.getEffectiveFrom() != null && targetIsoDateTime.compareTo(snapshot.getEffectiveFrom()) < 0) {
            return false;
        }
        if (snapshot.getEffectiveTo() != null && targetIsoDateTime.compareTo(snapshot.getEffectiveTo()) >= 0) {
            return false;
        }
        return true;
    }
}

