package com.pharmacy.pos.prescriptions;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * P251: Branch Drug License Client & Compliance Evaluator (JavaFX Desktop POS).
 * Architecture Decision: ADR-0251 (P251).
 * 
 * Tracks official branch DGDA retail and wholesale drug licenses,
 * verifies validity periods, expiry warnings, and role-based permissions.
 */
public final class BranchDrugLicenseClient {

    private static final Set<String> MANAGE_ROLES = Set.of(
        "SUPERADMIN",
        "PHARMACY_OWNER",
        "BRANCH_MANAGER"
    );

    public enum LicenseStatus {
        ACTIVE,
        RENEWAL_PENDING,
        EXPIRED,
        SUSPENDED,
        REVOKED;

        public static LicenseStatus fromString(String val) {
            if (val == null) return ACTIVE;
            try {
                return LicenseStatus.valueOf(val.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return ACTIVE;
            }
        }
    }

    public enum LicenseType {
        FORM_20_RETAIL,
        FORM_20A_BIOLOGICAL,
        FORM_21_WHOLESALE;

        public String getLocalizedLabel(String lang) {
            boolean isBn = lang != null && lang.equalsIgnoreCase("bn");
            return switch (this) {
                case FORM_20_RETAIL -> isBn ? "ফরম ২০ - অ্যালোপ্যাথিক খুচরা ড্রাগ লাইসেন্স" : "Form 20 - Retail Drug License";
                case FORM_20A_BIOLOGICAL -> isBn ? "ফরম ২০এ - বায়োলজিক্যাল ড্রাগ লাইসেন্স" : "Form 20A - Biological Drug License";
                case FORM_21_WHOLESALE -> isBn ? "ফরম ২১ - পাইকারি ড্রাগ লাইসেন্স" : "Form 21 - Wholesale Drug License";
            };
        }
    }

    private BranchDrugLicenseClient() {}

    /**
     * Checks if user role is authorized to register or update branch drug licenses.
     */
    public static boolean isRoleAuthorizedToManage(String role) {
        if (role == null) return false;
        return MANAGE_ROLES.contains(role.trim().toUpperCase());
    }

    /**
     * Evaluates live license validity and warning states.
     */
    public static LicenseEvaluation evaluateLicense(LicenseStatus status, long daysUntilExpiry) {
        if (status == LicenseStatus.SUSPENDED || status == LicenseStatus.REVOKED) {
            return new LicenseEvaluation(
                false,
                status.name(),
                "Drug license is suspended or revoked by DGDA. Pharmacy operations are legally restricted.",
                "ডিজিডিএ কর্তৃক ড্রাগ লাইসেন্স স্থগিত বা বাতিল করা হয়েছে।"
            );
        }
        if (daysUntilExpiry < 0) {
            if (status == LicenseStatus.RENEWAL_PENDING) {
                return new LicenseEvaluation(
                    true,
                    "RENEWAL_PENDING",
                    "License expired but renewal application is submitted. Operating under temporary renewal grace.",
                    "লাইসেন্সের মেয়াদ শেষ হলেও নবায়ন আবেদন দাখিলকৃত।"
                );
            }
            return new LicenseEvaluation(
                false,
                "EXPIRED",
                "Drug license is expired. Operating without an active license violates DGDA regulations.",
                "ড্রাগ লাইসেন্সের মেয়াদ উত্তীর্ণ। নবায়ন ব্যতীত পরিচালনা বেআইনি।"
            );
        }
        if (daysUntilExpiry <= 30) {
            return new LicenseEvaluation(
                true,
                "EXPIRING_SOON",
                "Drug license expires in " + daysUntilExpiry + " days. Immediate renewal required.",
                "ড্রাগ লাইসেন্সের মেয়াদ আর " + daysUntilExpiry + " দিন বাকি। জরুরি নবায়ন আবশ্যক।"
            );
        }
        return new LicenseEvaluation(true, "ACTIVE", "License is active and valid.", "লাইসেন্স বৈধ ও সক্রিয়।");
    }

    /**
     * Preflight validation for drug license registration inputs.
     */
    public static PreflightResult validatePreflight(
        String licenseNumber,
        String issuingAuthority,
        String issueDateStr,
        String expiryDateStr
    ) {
        if (licenseNumber == null || licenseNumber.trim().length() < 4) {
            return PreflightResult.failure("ERR_INVALID_LICENSE_NO", "License number must be at least 4 characters.");
        }
        if (issuingAuthority == null || issuingAuthority.trim().length() < 3) {
            return PreflightResult.failure("ERR_INVALID_AUTHORITY", "Issuing authority must be at least 3 characters.");
        }
        try {
            LocalDate issueDate = LocalDate.parse(issueDateStr.trim());
            LocalDate expiryDate = LocalDate.parse(expiryDateStr.trim());
            if (!expiryDate.isAfter(issueDate)) {
                return PreflightResult.failure("ERR_INVALID_DATES", "Expiry date must be after issue date.");
            }
        } catch (DateTimeParseException | NullPointerException e) {
            return PreflightResult.failure("ERR_DATE_FORMAT", "Valid ISO dates (YYYY-MM-DD) are required.");
        }
        return PreflightResult.success();
    }

    public record LicenseEvaluation(
        boolean isValid,
        String evaluationCode,
        String messageEn,
        String messageBn
    ) {}

    public record PreflightResult(boolean isValid, String errorCode, String errorMessage) {
        public static PreflightResult success() {
            return new PreflightResult(true, null, null);
        }

        public static PreflightResult failure(String code, String message) {
            return new PreflightResult(false, code, message);
        }
    }
}

