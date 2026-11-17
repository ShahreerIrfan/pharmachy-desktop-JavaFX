package com.pharmacy.pos.prescriptions;

import java.util.Objects;
import java.util.Set;

/**
 * P250: No Automated Substitution, Clinical Advice or Prescriber Impersonation Policy.
 * Architecture Decision: ADR-0250 (P250).
 * 
 * Enforces strict compliance with Bangladesh DGDA guidelines:
 * 1. Zero autonomous software substitution or AI-driven brand swapping.
 * 2. Absolute prohibition of algorithmic diagnosis and automated clinical dosage advice.
 * 3. Prohibition of prescriber impersonation or synthetic BMDC generation.
 * 4. Human-pharmacist-only manual brand substitution with mandatory PCB credentials.
 */
public final class NoAutomatedSubstitutionPolicy {

    public static final String DISCLAIMER_EN = 
        "Pharmacy ERP is an administrative workflow system and does not provide medical diagnoses, " +
        "automated prescription substitutions, or clinical dosage advice. All clinical decisions must be " +
        "made by qualified registered medical practitioners (BMDC) or licensed pharmacists (PCB).";

    public static final String DISCLAIMER_BN = 
        "ফার্মাসি ইআরপি একটি প্রশাসনিক ওয়ার্কফ্লো সিস্টেম। এটি কোনো চিকিৎসাগত রোগ নির্ণয়, " +
        "স্বয়ংক্রিয় ওষুধ পরিবর্তন বা ক্লিনিক্যাল ডোজের পরামর্শ প্রদান করে না। সকল ক্লিনিক্যাল সিদ্ধান্ত " +
        "অবশ্যই বিএমডিসি নিবন্ধিত চিকিৎসক বা পিসিবি নিবন্ধিত ফার্মাসিস্ট কর্তৃক গৃহীত হতে হবে।";

    private static final Set<String> AUTHORIZED_ROLES = Set.of(
        "STAFF_PHARMACIST",
        "PHARMACY_OWNER",
        "BRANCH_MANAGER",
        "SUPERADMIN"
    );

    private NoAutomatedSubstitutionPolicy() {}

    /**
     * Returns statutory disclaimer text based on user language.
     */
    public static String getDisclaimer(String lang) {
        if (lang != null && lang.equalsIgnoreCase("bn")) {
            return DISCLAIMER_BN;
        }
        return DISCLAIMER_EN;
    }

    /**
     * Checks if user role is authorized to execute manual brand substitutions.
     * Ordinary cashiers are strictly barred.
     */
    public static boolean isRoleAuthorized(String role) {
        if (role == null) return false;
        return AUTHORIZED_ROLES.contains(role.trim().toUpperCase());
    }

    /**
     * Evaluates medicine eligibility for brand substitution.
     * Narcotics and recalled/banned items can never be substituted.
     */
    public static boolean isEligibleForSubstitution(boolean isNarcotic, boolean isRecalledOrBanned) {
        if (isNarcotic || isRecalledOrBanned) {
            return false;
        }
        return true;
    }

    /**
     * Validates manual substitution preflight request.
     * Strictly rejects any autonomous machine-generated substitution attempts.
     */
    public static ValidationResult validateManualSubstitution(
        String originalProductId,
        String replacementProductId,
        String pharmacistPcbNo,
        String clinicalRationale,
        boolean isAutonomousMachineRequest
    ) {
        if (isAutonomousMachineRequest) {
            return ValidationResult.failure(
                "ERR_AUTOMATED_SUBSTITUTION_FORBIDDEN",
                "Autonomous brand substitution is prohibited by law. Every substitution must be a manual clinical decision by a licensed pharmacist."
            );
        }
        if (originalProductId == null || originalProductId.trim().isEmpty()) {
            return ValidationResult.failure("ERR_ORIGINAL_PRODUCT_REQUIRED", "Original product ID is required.");
        }
        if (replacementProductId == null || replacementProductId.trim().isEmpty()) {
            return ValidationResult.failure("ERR_REPLACEMENT_PRODUCT_REQUIRED", "Replacement product ID is required.");
        }
        if (Objects.equals(originalProductId.trim(), replacementProductId.trim())) {
            return ValidationResult.failure("ERR_IDENTICAL_PRODUCTS", "Original and replacement products must be distinct.");
        }
        if (pharmacistPcbNo == null || pharmacistPcbNo.trim().length() < 4) {
            return ValidationResult.failure("ERR_INVALID_PCB", "A valid Pharmacist PCB registration number (minimum 4 characters) is required.");
        }
        if (clinicalRationale == null || clinicalRationale.trim().length() < 5) {
            return ValidationResult.failure("ERR_INVALID_RATIONALE", "Clinical rationale (minimum 5 characters) is mandatory.");
        }
        return ValidationResult.success();
    }

    public record ValidationResult(boolean isValid, String errorCode, String errorMessage) {
        public static ValidationResult success() {
            return new ValidationResult(true, null, null);
        }

        public static ValidationResult failure(String code, String message) {
            return new ValidationResult(false, code, message);
        }
    }
}

