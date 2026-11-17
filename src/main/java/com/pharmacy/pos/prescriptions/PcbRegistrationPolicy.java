package com.pharmacy.pos.prescriptions;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/**
 * P252: Pharmacy Personnel PCB Registration Tracking Policy.
 * Architecture Decision: ADR-0252 (P252).
 * 
 * Enforces Pharmacy Council of Bangladesh (PCB) qualification grades,
 * statutory validity rules, branch assignment governance, and strict
 * evidence access restrictions.
 */
public final class PcbRegistrationPolicy {

    public static final String GRADE_A = "GRADE_A";
    public static final String GRADE_B = "GRADE_B";
    public static final String GRADE_C = "GRADE_C";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PENDING_RENEWAL = "PENDING_RENEWAL";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_REVOKED = "REVOKED";

    private static final Set<String> PRIVILEGED_EVIDENCE_ROLES = Set.of(
        "SUPERADMIN",
        "PHARMACY_OWNER"
    );

    private PcbRegistrationPolicy() {}

    /**
     * Translates PCB grade to localized human-readable display string.
     */
    public static String getGradeDisplay(String grade, String lang) {
        boolean isBn = lang != null && lang.equalsIgnoreCase("bn");
        if (grade == null) return isBn ? "অনির্ধারিত" : "Unspecified";

        return switch (grade.trim().toUpperCase()) {
            case GRADE_A -> isBn ? "এ-গ্রেড (গ্র্যাজুয়েট ফার্মাসিস্ট)" : "Grade A (Graduate Pharmacist)";
            case GRADE_B -> isBn ? "বি-গ্রেড (ডিপ্লোমা ফার্মাসিস্ট)" : "Grade B (Diploma Pharmacist)";
            case GRADE_C -> isBn ? "সি-গ্রেড (ফার্মাসি টেকনিশিয়ান)" : "Grade C (Pharmacy Technician)";
            default -> grade;
        };
    }

    /**
     * Translates PCB validity status to localized display string.
     */
    public static String getStatusDisplay(String status, String lang) {
        boolean isBn = lang != null && lang.equalsIgnoreCase("bn");
        if (status == null) return isBn ? "অজানা" : "Unknown";

        return switch (status.trim().toUpperCase()) {
            case STATUS_ACTIVE -> isBn ? "সক্রিয় ও বৈধ" : "Active Valid";
            case STATUS_PENDING_RENEWAL -> isBn ? "নবায়ন প্রক্রিয়াধীন" : "Renewal Pending";
            case STATUS_EXPIRED -> isBn ? "মেয়াদ উত্তীর্ণ" : "Expired";
            case STATUS_SUSPENDED -> isBn ? "স্থগিত" : "Suspended";
            case STATUS_REVOKED -> isBn ? "বাতিল" : "Revoked";
            default -> status;
        };
    }

    /**
     * Determines whether user can view confidential registration certificate evidence.
     * Restricted to Superadmin, Owner, Branch Manager (own branch), or the registrant themselves.
     */
    public static boolean canAccessEvidence(String userRole, boolean isSelf, boolean isOwnBranch) {
        if (isSelf) return true;
        if (userRole == null) return false;

        String role = userRole.trim().toUpperCase();
        if (PRIVILEGED_EVIDENCE_ROLES.contains(role)) {
            return true;
        }
        return role.equals("BRANCH_MANAGER") && isOwnBranch;
    }

    /**
     * Checks if registrant is authorized to perform clinical overrides or manual brand substitutions.
     * Requires Grade A credential and active/pending-renewal validity.
     */
    public static boolean isAuthorizedForClinicalOverrides(String grade, String status) {
        if (grade == null || status == null) return false;
        boolean isGradeA = grade.trim().equalsIgnoreCase(GRADE_A);
        String st = status.trim().toUpperCase();
        boolean isValid = st.equals(STATUS_ACTIVE) || st.equals(STATUS_PENDING_RENEWAL);
        return isGradeA && isValid;
    }

    /**
     * Calculates days until credential expiry.
     */
    public static long daysUntilExpiry(LocalDate validUntil, LocalDate currentDate) {
        Objects.requireNonNull(validUntil, "validUntil must not be null");
        Objects.requireNonNull(currentDate, "currentDate must not be null");
        return ChronoUnit.DAYS.between(currentDate, validUntil);
    }

    /**
     * Identifies if a credential needs urgent renewal (within 30 days).
     */
    public static boolean isExpiringSoon(long daysRemaining) {
        return daysRemaining >= 0 && daysRemaining <= 30;
    }
}

