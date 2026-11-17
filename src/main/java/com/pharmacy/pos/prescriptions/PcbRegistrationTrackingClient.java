package com.pharmacy.pos.prescriptions;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * P252: Pharmacy Personnel PCB Registration Tracking & Access Client (JavaFX Desktop POS).
 * Architecture Decision: ADR-0252 (P252).
 * 
 * Tracks Pharmacy Council of Bangladesh (PCB) qualification grades (A/B/C),
 * statutory validity periods, branch assignment, and sensitive evidence access restrictions.
 */
public final class PcbRegistrationTrackingClient {

    private static final Set<String> MANAGE_ROLES = Set.of(
        "SUPERADMIN",
        "PHARMACY_OWNER",
        "BRANCH_MANAGER"
    );

    public enum PcbGrade {
        GRADE_A,
        GRADE_B,
        GRADE_C;

        public String getLocalizedLabel(String lang) {
            boolean isBn = lang != null && lang.equalsIgnoreCase("bn");
            return switch (this) {
                case GRADE_A -> isBn ? "এ-গ্রেড (গ্র্যাজুয়েট ফার্মাসিস্ট - বি.ফার্ম/এম.ফার্ম)" : "Grade A (Graduate Pharmacist - B.Pharm/M.Pharm)";
                case GRADE_B -> isBn ? "বি-গ্রেড (ডিপ্লোমা ইন ফার্মাসি)" : "Grade B (Diploma in Pharmacy)";
                case GRADE_C -> isBn ? "সি-গ্রেড (ফার্মাসি টেকনিশিয়ান / শিক্ষানবিস)" : "Grade C (Pharmacy Technician / Apprentice)";
            };
        }

        public static PcbGrade fromString(String val) {
            if (val == null) return GRADE_A;
            try {
                return PcbGrade.valueOf(val.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return GRADE_A;
            }
        }
    }

    public enum PcbStatus {
        ACTIVE,
        PENDING_RENEWAL,
        EXPIRED,
        SUSPENDED,
        REVOKED;

        public static PcbStatus fromString(String val) {
            if (val == null) return ACTIVE;
            try {
                return PcbStatus.valueOf(val.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return ACTIVE;
            }
        }
    }

    private PcbRegistrationTrackingClient() {}

    /**
     * Checks if user role is authorized to create or modify personnel PCB records.
     */
    public static boolean isRoleAuthorizedToManage(String role) {
        if (role == null) return false;
        return MANAGE_ROLES.contains(role.trim().toUpperCase());
    }

    /**
     * Checks if caller can inspect confidential credential evidence (scans/IDs).
     */
    public static boolean canViewEvidence(String role, boolean isSelf) {
        if (isSelf) return true;
        if (role == null) return false;
        String clean = role.trim().toUpperCase();
        return clean.equals("SUPERADMIN") || clean.equals("PHARMACY_OWNER") || clean.equals("BRANCH_MANAGER");
    }

    /**
     * Enforces statutory grade qualification hierarchy for clinical operations.
     * Grade A can execute any clinical action.
     * Grade B can execute dispensing and supervision.
     * Grade C cannot execute Grade A or B clinical actions.
     */
    public static boolean isGradeAuthorized(PcbGrade registrantGrade, PcbGrade requiredGrade) {
        if (registrantGrade == null || requiredGrade == null) return false;
        if (registrantGrade == PcbGrade.GRADE_A) return true;
        if (registrantGrade == PcbGrade.GRADE_B) {
            return requiredGrade == PcbGrade.GRADE_B || requiredGrade == PcbGrade.GRADE_C;
        }
        return requiredGrade == PcbGrade.GRADE_C;
    }

    /**
     * Evaluates live registration status and warnings.
     */
    public static RegistrationEvaluation evaluateRegistration(PcbStatus status, long daysUntilExpiry) {
        if (status == PcbStatus.SUSPENDED || status == PcbStatus.REVOKED) {
            return new RegistrationEvaluation(
                false,
                status.name(),
                "PCB Registration is suspended or revoked by the Pharmacy Council.",
                "ফার্মেসি কাউন্সিল কর্তৃক পিসিবি রেজিস্ট্রেশন স্থগিত বা বাতিল করা হয়েছে।"
            );
        }
        if (daysUntilExpiry < 0) {
            if (status == PcbStatus.PENDING_RENEWAL) {
                return new RegistrationEvaluation(
                    true,
                    "PENDING_RENEWAL",
                    "PCB Registration expired but renewal documentation is submitted.",
                    "পিসিবি রেজিস্ট্রেশনের মেয়াদ শেষ হলেও নবায়ন আবেদন প্রক্রিয়াধীন।"
                );
            }
            return new RegistrationEvaluation(
                false,
                "EXPIRED",
                "PCB Registration is expired. Personnel is legally unauthorized for clinical practice.",
                "পিসিবি রেজিস্ট্রেশনের মেয়াদ উত্তীর্ণ। অনুশীলনের অনুমতি নেই।"
            );
        }
        if (daysUntilExpiry <= 30) {
            return new RegistrationEvaluation(
                true,
                "EXPIRING_SOON",
                "PCB Registration expires in " + daysUntilExpiry + " days. Renewal required.",
                "পিসিবি রেজিস্ট্রেশনের মেয়াদ আর " + daysUntilExpiry + " দিন বাকি।"
            );
        }
        return new RegistrationEvaluation(true, "ACTIVE", "Registration is active and valid.", "রেজিস্ট্রেশন বৈধ ও সক্রিয়।");
    }

    /**
     * Preflight validation for PCB registration entry.
     */
    public static PreflightResult validatePreflight(
        String registrationNo,
        String registeredName,
        String issueDateStr,
        String validUntilStr
    ) {
        if (registrationNo == null || registrationNo.trim().length() < 3) {
            return PreflightResult.failure("ERR_INVALID_REG_NO", "PCB Registration number must be at least 3 characters.");
        }
        if (registeredName == null || registeredName.trim().length() < 2) {
            return PreflightResult.failure("ERR_INVALID_NAME", "Registered name must be at least 2 characters.");
        }
        try {
            LocalDate issueDate = LocalDate.parse(issueDateStr.trim());
            LocalDate validUntil = LocalDate.parse(validUntilStr.trim());
            if (validUntil.isBefore(issueDate)) {
                return PreflightResult.failure("ERR_INVALID_DATES", "Validity date cannot be before council issue date.");
            }
        } catch (DateTimeParseException | NullPointerException e) {
            return PreflightResult.failure("ERR_DATE_FORMAT", "Valid ISO dates (YYYY-MM-DD) are required.");
        }
        return PreflightResult.success();
    }

    public record RegistrationEvaluation(
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

