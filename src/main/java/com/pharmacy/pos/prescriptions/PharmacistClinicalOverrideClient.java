package com.pharmacy.pos.prescriptions;

import java.io.Serializable;

/**
 * P249: Credentialed Pharmacist Clinical Override Client (JavaFX Desktop).
 * Architecture Decision: ADR-0249 (P249).
 * Enforces mandatory PCB credentials, statutory policy, patient consent, and narcotic prohibition.
 */
public class PharmacistClinicalOverrideClient {

    public enum OverrideCategory {
        EMERGENCY_CHRONIC_CONTINUATION(
                "Emergency Chronic Care Continuation",
                "জরুরি দীর্ঘমেয়াদী চিকিৎসার ধারাবাহিকতা রক্ষা"
        ),
        PACKAGE_SPLIT_PARTIAL_COURSE(
                "Package Split / Partial Acute Course",
                "প্যাকেজ বিভাজন / আংশিক কোর্স সরবরাহ"
        ),
        OUT_OF_STOCK_GENERIC_DISPENSE(
                "Out-of-Stock Manual Generic Equivalency",
                "স্টক ঘাটতিতে সমতুল্য জেনেরিক বিকল্প সরবরাহ"
        ),
        PRESCRIBER_UNAVAILABLE_EMERGENCY(
                "Prescriber Unavailable Acute Emergency",
                "চিকিৎসক অনুপস্থিতিকালীন জরুরি সরবরাহ"
        );

        private final String enLabel;
        private final String bnLabel;

        OverrideCategory(String enLabel, String bnLabel) {
            this.enLabel = enLabel;
            this.bnLabel = bnLabel;
        }

        public String getLocalized(String lang) {
            return "bn".equalsIgnoreCase(lang) ? bnLabel : enLabel;
        }
    }

    public enum ConsentType {
        INFORMED_VERBAL("Informed Verbal Consent", "অবহিত মৌখিক সম্মতি"),
        SIGNED_PHYSICAL("Signed Paper Consent Form", "স্বাক্ষরিত লিখিত সম্মতিপত্র"),
        SMS_OTP("SMS OTP Digital Consent", "এসএমএস ওটিপি ডিজিটাল সম্মতি"),
        RECORDED_ATTENDANT("Recorded Attendant Consent", "অভিভাবক / প্রতিনিধির নথিবদ্ধ সম্মতি");

        private final String enLabel;
        private final String bnLabel;

        ConsentType(String enLabel, String bnLabel) {
            this.enLabel = enLabel;
            this.bnLabel = bnLabel;
        }

        public String getLocalized(String lang) {
            return "bn".equalsIgnoreCase(lang) ? bnLabel : enLabel;
        }
    }

    public static class ClinicalOverrideRequest implements Serializable {
        private final String saleId;
        private final String productId;
        private final String pharmacistPcbNo;
        private final OverrideCategory category;
        private final String policyReference;
        private final ConsentType consentType;
        private final String consentNotes;
        private final String clinicalJustification;
        private final int approvedUnits;

        public ClinicalOverrideRequest(
                String saleId,
                String productId,
                String pharmacistPcbNo,
                OverrideCategory category,
                String policyReference,
                ConsentType consentType,
                String consentNotes,
                String clinicalJustification,
                int approvedUnits
        ) {
            this.saleId = saleId;
            this.productId = productId;
            this.pharmacistPcbNo = pharmacistPcbNo;
            this.category = category;
            this.policyReference = policyReference;
            this.consentType = consentType;
            this.consentNotes = consentNotes;
            this.clinicalJustification = clinicalJustification;
            this.approvedUnits = approvedUnits;
        }

        public String getSaleId() { return saleId; }
        public String getProductId() { return productId; }
        public String getPharmacistPcbNo() { return pharmacistPcbNo; }
        public OverrideCategory getCategory() { return category; }
        public String getPolicyReference() { return policyReference; }
        public ConsentType getConsentType() { return consentType; }
        public String getConsentNotes() { return consentNotes; }
        public String getClinicalJustification() { return clinicalJustification; }
        public int getApprovedUnits() { return approvedUnits; }
    }

    /**
     * Determines whether a medicine is legally eligible for pharmacist clinical override.
     * Strictly forbids narcotics/Schedule-G and recalled/banned items.
     */
    public static boolean isEligibleForOverride(boolean isNarcotic, boolean isRecalledOrBanned) {
        if (isNarcotic || isRecalledOrBanned) {
            return false;
        }
        return true;
    }

    /**
     * Validates input parameters before submitting a clinical override.
     */
    public static boolean validatePreflight(ClinicalOverrideRequest request) {
        if (request == null) return false;
        if (request.getSaleId() == null || request.getSaleId().trim().isEmpty()) return false;
        if (request.getProductId() == null || request.getProductId().trim().isEmpty()) return false;
        if (request.getPharmacistPcbNo() == null || request.getPharmacistPcbNo().trim().length() < 4) return false;
        if (request.getCategory() == null) return false;
        if (request.getPolicyReference() == null || request.getPolicyReference().trim().length() < 5) return false;
        if (request.getConsentType() == null) return false;
        if (request.getConsentNotes() == null || request.getConsentNotes().trim().length() < 5) return false;
        if (request.getClinicalJustification() == null || request.getClinicalJustification().trim().length() < 5) return false;
        if (request.getApprovedUnits() < 1) return false;
        return true;
    }
}

