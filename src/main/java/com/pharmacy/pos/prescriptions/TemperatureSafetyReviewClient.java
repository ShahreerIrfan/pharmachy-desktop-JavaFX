package com.pharmacy.pos.prescriptions;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * TemperatureSafetyReviewClient.java
 * Architecture Decision: ADR-0257 (P257).
 * Phase 13: Prescriptions, Antibiotic Register & Compliance Safety.
 *
 * Desktop POS client for Temperature Excursion Safety Reviews,
 * Pharmacist review authorization gates, clinical justification checks,
 * and immutable decision audit hashes.
 */
public class TemperatureSafetyReviewClient {

    public static final List<String> VALID_DECISIONS = Arrays.asList(
        "RELEASE_TO_ACTIVE",
        "RETURN_TO_SUPPLIER",
        "CONDEMN_DISPOSAL"
    );

    public static final List<String> VALID_SEVERITIES = Arrays.asList(
        "MINOR",
        "MODERATE",
        "CRITICAL"
    );

    public static boolean canPerformSafetyReview(String role) {
        if (role == null) return false;
        String r = role.trim().toUpperCase();
        return "SUPERADMIN".equals(r)
            || "PHARMACY_OWNER".equals(r)
            || "BRANCH_MANAGER".equals(r)
            || "STAFF_PHARMACIST".equals(r);
    }

    public static boolean isDispensingAllowed(boolean isQuarantined, String batchStatus, String latestReviewDecision) {
        if (!isQuarantined && "ACTIVE".equalsIgnoreCase(batchStatus)) {
            return true;
        }
        return "RELEASE_TO_ACTIVE".equalsIgnoreCase(latestReviewDecision);
    }

    public static void validateReviewPreflight(
        String batchNumber,
        String decision,
        BigDecimal maxExposedTemp,
        String clinicalJustification,
        boolean isExpired
    ) {
        if (batchNumber == null || batchNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("batchNumber is required.");
        }
        if (decision == null || !VALID_DECISIONS.contains(decision.trim().toUpperCase())) {
            throw new IllegalArgumentException("Valid review decision is required: " + VALID_DECISIONS);
        }
        if (maxExposedTemp == null) {
            throw new IllegalArgumentException("maxExposedTemp is required.");
        }

        String normDecision = decision.trim().toUpperCase();
        if ("RELEASE_TO_ACTIVE".equals(normDecision)) {
            if (clinicalJustification == null || clinicalJustification.trim().length() < 15) {
                throw new IllegalArgumentException("Clinical justification must be at least 15 characters for release to active stock.");
            }
            if (isExpired) {
                throw new IllegalStateException("Statutory Safety Violation: Expired medicine batch cannot be released to active sellable stock.");
            }
        }
    }

    public static String formatClinicalAlert(String batchNumber, String productName, String decision, boolean isBangla) {
        String bn = batchNumber != null ? batchNumber : "UNKNOWN";
        String pn = productName != null ? productName : "Medicine";

        if (isBangla) {
            if ("RELEASE_TO_ACTIVE".equalsIgnoreCase(decision)) {
                return pn + " (ব্যাচ: " + bn + ") তাপমাত্রার পর্যালোচনা সম্পন্ন করে সক্রিয় স্টকে ফেরত আনা হয়েছে।";
            } else if ("CONDEMN_DISPOSAL".equalsIgnoreCase(decision)) {
                return pn + " (ব্যাচ: " + bn + ") তাপমাত্রাজনিত অবক্ষয়ের কারণে বিনষ্ট ও প্রত্যাহারের নির্দেশ দেওয়া হয়েছে।";
            } else {
                return pn + " (ব্যাচ: " + bn + ") সরবরাহকারীকে ফেরতের জন্য কোয়ারেন্টাইন করা হয়েছে।";
            }
        } else {
            if ("RELEASE_TO_ACTIVE".equalsIgnoreCase(decision)) {
                return pn + " (Batch: " + bn + ") released to active sellable stock following clinical review.";
            } else if ("CONDEMN_DISPOSAL".equalsIgnoreCase(decision)) {
                return pn + " (Batch: " + bn + ") condemned and scheduled for hazardous disposal.";
            } else {
                return pn + " (Batch: " + bn + ") quarantined for supplier return.";
            }
        }
    }
}
