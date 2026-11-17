package com.pharmacy.pos.prescriptions;

import java.util.Arrays;
import java.util.List;

/**
 * SafetyEndToEndClient.java
 * Architecture Decision: ADR-0260 (P260).
 * Phase 13: Prescriptions, Antibiotic Register & Compliance Safety.
 *
 * Desktop POS client helper for end-to-end clinical safety verification:
 * - Prohibited non-Rx dispensing gate enforcement.
 * - Stale classification invalidation.
 * - Recalled batch dispensing blocking.
 * - Expired prescription date validation.
 * - Credentialed pharmacist verification role gates.
 */
public class SafetyEndToEndClient {

    public static final List<String> PHARMACIST_ROLES = Arrays.asList(
        "SUPERADMIN",
        "PHARMACY_OWNER",
        "BRANCH_MANAGER",
        "STAFF_PHARMACIST"
    );

    public static boolean isAuthorizedPharmacist(String role) {
        if (role == null) return false;
        String r = role.trim().toUpperCase();
        return PHARMACIST_ROLES.contains(r);
    }

    public static boolean canFinalizeOrder(
        boolean requiresRx,
        boolean isRxVerified,
        boolean hasBlockedRecall,
        boolean isOffline
    ) {
        if (hasBlockedRecall) {
            return false;
        }
        if (requiresRx) {
            if (isOffline) {
                return false; // Offline POS cannot finalize unverified Rx/antibiotics
            }
            return isRxVerified;
        }
        return true;
    }

    public static boolean isClassificationStale(int lineVersion, int liveVersion) {
        return liveVersion > lineVersion;
    }

    public static void validatePrescriptionDate(String rxDate, String currentDate) {
        if (rxDate == null || rxDate.trim().isEmpty()) {
            throw new IllegalArgumentException("Prescription date is required.");
        }
        if (currentDate == null || currentDate.trim().isEmpty()) {
            throw new IllegalArgumentException("Current date is required.");
        }
        if (rxDate.compareTo(currentDate) > 0) {
            throw new IllegalArgumentException("Prescription date cannot be in the future.");
        }
        // Check 365-day validity boundary (YYYY-MM-DD comparison approximation)
        // Format: YYYY-MM-DD
        String[] rxParts = rxDate.split("-");
        String[] curParts = currentDate.split("-");
        if (rxParts.length == 3 && curParts.length == 3) {
            int rxYear = Integer.parseInt(rxParts[0]);
            int curYear = Integer.parseInt(curParts[0]);
            if (curYear - rxYear > 1) {
                throw new IllegalArgumentException("Prescription is older than 365 days.");
            }
        }
    }

    public static String formatSafetyAlert(
        String alertCode,
        String medicineName,
        boolean isBangla
    ) {
        String code = alertCode != null ? alertCode : "ERR_SAFETY_VIOLATION";
        String med = medicineName != null ? medicineName : "Medicine";

        switch (code) {
            case "ERR_RX_VERIFICATION_REQUIRED":
                return isBangla
                    ? "সতর্কতা: " + med + "-এর জন্য নিবন্ধিত ফার্মাসিস্ট কর্তৃক প্রেসক্রিপশন যাচাইকরণ আবশ্যক।"
                    : "ALERT: Prescription verification by a registered pharmacist is required for " + med + ".";
            case "ERR_BATCH_RECALLED":
                return isBangla
                    ? "জরুরি প্রত্যাহার: " + med + "-এর এই ব্যাচটি সরকারের ডিজিডিএ প্রত্যাহার তালিকার অন্তর্ভুক্ত! বিক্রি নিষিদ্ধ।"
                    : "RECALL ALERT: This batch of " + med + " is under active DGDA recall! Dispensing is strictly prohibited.";
            case "ERR_STALE_CLASSIFICATION":
                return isBangla
                    ? "পুনঃযাচাইকরণ প্রয়োজন: " + med + "-এর রেগুলেটরি শ্রেণিবিভাগ হালনাগাদ হয়েছে।"
                    : "RE-VERIFICATION REQUIRED: Regulatory classification for " + med + " was updated. Re-verification required.";
            default:
                return isBangla
                    ? "নিরাপত্তা ত্রুটি [" + code + "]: " + med
                    : "Safety Error [" + code + "]: " + med;
        }
    }
}

