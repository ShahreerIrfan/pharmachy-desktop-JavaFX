package com.pharmacy.pos.prescriptions;

import java.io.Serializable;

/**
 * P248: Conservative Offline Prescription Authorization Client (JavaFX Desktop).
 * Architecture Decision: ADR-0248 (P248).
 * Forbids offline dispensing where prescription authorization cannot be validated.
 */
public class OfflinePrescriptionAuthorizationClient {

    public enum OfflineEligibilityStatus {
        ELIGIBLE("Eligible for Offline Dispensing", "অফলাইন সম্পন্ন করার উপযোগী"),
        BLOCKED_UNVERIFIED_RX(
                "Offline Block: Unverified prescription/antibiotic requires registered Pharmacist approval",
                "অফলাইন ব্লক: প্রেসক্রিপশনযুক্ত ওষুধে নিবন্ধিত ফার্মাসিস্টের অনুমোদন আবশ্যক"
        ),
        FORBIDDEN_NARCOTIC(
                "Statutory Forbidden: Narcotics/Schedule-G substances cannot be dispensed offline",
                "সংবিধিবদ্ধ নিষেধাজ্ঞা: মাদক ও সিডিউল-জি ওষুধ অফলাইনে বিক্রি সম্পূর্ণ অবৈধ"
        ),
        BLOCKED_RECALLED(
                "Safety Recall: Cart contains recalled or banned items",
                "নিরাপত্তা সতর্কতা: কার্টে প্রত্যাহৃত বা নিষিদ্ধ ওষুধ রয়েছে"
        );

        private final String enMessage;
        private final String bnMessage;

        OfflineEligibilityStatus(String enMessage, String bnMessage) {
            this.enMessage = enMessage;
            this.bnMessage = bnMessage;
        }

        public String getLocalized(String lang) {
            return "bn".equalsIgnoreCase(lang) ? bnMessage : enMessage;
        }

        public boolean isEligible() {
            return this == ELIGIBLE;
        }
    }

    public static class OfflineAuthorizationRequest implements Serializable {
        private final String saleId;
        private final String pharmacistPcbNo;
        private final String doctorBmdcNo;
        private final String doctorName;
        private final String patientToken;
        private final String reviewNotes;

        public OfflineAuthorizationRequest(
                String saleId,
                String pharmacistPcbNo,
                String doctorBmdcNo,
                String doctorName,
                String patientToken,
                String reviewNotes
        ) {
            this.saleId = saleId;
            this.pharmacistPcbNo = pharmacistPcbNo;
            this.doctorBmdcNo = doctorBmdcNo;
            this.doctorName = doctorName;
            this.patientToken = patientToken;
            this.reviewNotes = reviewNotes;
        }

        public String getSaleId() { return saleId; }
        public String getPharmacistPcbNo() { return pharmacistPcbNo; }
        public String getDoctorBmdcNo() { return doctorBmdcNo; }
        public String getDoctorName() { return doctorName; }
        public String getPatientToken() { return patientToken; }
        public String getReviewNotes() { return reviewNotes; }
    }

    /**
     * Conservative eligibility evaluation for offline POS checkout.
     */
    public static OfflineEligibilityStatus evaluateEligibility(
            boolean hasBlockedRecallOrBan,
            boolean hasNarcotic,
            boolean anyRxRequired,
            boolean allRxApproved
    ) {
        if (hasBlockedRecallOrBan) {
            return OfflineEligibilityStatus.BLOCKED_RECALLED;
        }
        if (hasNarcotic) {
            return OfflineEligibilityStatus.FORBIDDEN_NARCOTIC;
        }
        if (anyRxRequired && !allRxApproved) {
            return OfflineEligibilityStatus.BLOCKED_UNVERIFIED_RX;
        }
        return OfflineEligibilityStatus.ELIGIBLE;
    }

    /**
     * Validates offline pharmacist authorization preflight credentials.
     */
    public static boolean validateAuthorizationPreflight(OfflineAuthorizationRequest request) {
        if (request == null) return false;
        if (request.getSaleId() == null || request.getSaleId().trim().isEmpty()) return false;
        if (request.getPharmacistPcbNo() == null || request.getPharmacistPcbNo().trim().length() < 4) return false;
        if (request.getDoctorBmdcNo() == null || request.getDoctorBmdcNo().trim().length() < 4) return false;
        if (request.getDoctorName() == null || request.getDoctorName().trim().length() < 2) return false;
        if (request.getReviewNotes() == null || request.getReviewNotes().trim().length() < 5) return false;
        return true;
    }
}

