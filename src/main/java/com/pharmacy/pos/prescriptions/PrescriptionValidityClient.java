package com.pharmacy.pos.prescriptions;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * P244: Clinical Prescription Validity Controls Client (JavaFX Desktop).
 * Architecture Decision: ADR-0244 (P244).
 */
public class PrescriptionValidityClient {

    public enum ValidityStatus {
        PENDING_CHECK("Pending Check", "যাচাই প্রক্রিয়াধীন"),
        VALID("Valid", "বৈধ ও প্রস্তুত"),
        EXPIRED("Expired", "মেয়াদোত্তীর্ণ"),
        INCOMPLETE_CREDENTIALS("Incomplete Credentials", "চিকিৎসকের তথ্যে ঘাটতি"),
        MISSING_DOCUMENTS("Missing Documents", "প্রেসক্রিপশন স্ক্যান নেই"),
        INVALID_DATES("Invalid Dates", "তারিখের ত্রুটি"),
        REVOKED("Revoked", "বাতিলকৃত");

        private final String enLabel;
        private final String bnLabel;

        ValidityStatus(String enLabel, String bnLabel) {
            this.enLabel = enLabel;
            this.bnLabel = bnLabel;
        }

        public String getLocalized(String lang) {
            return "bn".equalsIgnoreCase(lang) ? bnLabel : enLabel;
        }
    }

    public static class ValidityReportDto implements Serializable {
        private final String prescriptionId;
        private final String prescriptionNumber;
        private final ValidityStatus validityStatus;
        private final boolean isValid;
        private final boolean isExpired;
        private final boolean hasValidAttachment;
        private final boolean requiresDocument;
        private final boolean canDispense;
        private final List<String> errors;

        public ValidityReportDto(
                String prescriptionId,
                String prescriptionNumber,
                ValidityStatus validityStatus,
                boolean isValid,
                boolean isExpired,
                boolean hasValidAttachment,
                boolean requiresDocument,
                boolean canDispense,
                List<String> errors
        ) {
            this.prescriptionId = prescriptionId;
            this.prescriptionNumber = prescriptionNumber;
            this.validityStatus = validityStatus;
            this.isValid = isValid;
            this.isExpired = isExpired;
            this.hasValidAttachment = hasValidAttachment;
            this.requiresDocument = requiresDocument;
            this.canDispense = canDispense;
            this.errors = errors != null ? errors : Collections.emptyList();
        }

        public String getPrescriptionId() { return prescriptionId; }
        public String getPrescriptionNumber() { return prescriptionNumber; }
        public ValidityStatus getValidityStatus() { return validityStatus; }
        public boolean isValid() { return isValid; }
        public boolean isExpired() { return isExpired; }
        public boolean hasValidAttachment() { return hasValidAttachment; }
        public boolean requiresDocument() { return requiresDocument; }
        public boolean canDispense() { return canDispense; }
        public List<String> getErrors() { return errors; }
    }

    /**
     * Client-side validation preflight for prescriber credentials and dates.
     */
    public static ValidityReportDto evaluateClientPreflight(
            String prescriptionId,
            String doctorName,
            String doctorBmdcNo,
            LocalDate prescriptionDate,
            LocalDate expiryDate,
            boolean hasAttachment,
            boolean containsAntibiotic,
            LocalDate today
    ) {
        LocalDate now = today != null ? today : LocalDate.now();
        List<String> errors = new java.util.ArrayList<>();

        // 1. Prescriber check
        if (doctorName == null || doctorName.trim().length() < 3) {
            errors.add("Doctor name is mandatory (minimum 3 characters).");
        }
        if (doctorBmdcNo == null || doctorBmdcNo.trim().length() < 4) {
            errors.add("Doctor BMDC registration number is invalid or too short.");
        } else {
            String cleanBmdc = doctorBmdcNo.trim().toUpperCase();
            if (cleanBmdc.equals("NONE") || cleanBmdc.equals("NA") || cleanBmdc.equals("TEST") || cleanBmdc.equals("0000")) {
                errors.add("BMDC number cannot be a placeholder.");
            }
        }

        // 2. Date checks
        boolean isExpired = false;
        if (prescriptionDate != null && prescriptionDate.isAfter(now)) {
            errors.add("Prescription date cannot be in the future.");
        }
        if (expiryDate != null) {
            if (prescriptionDate != null && expiryDate.isBefore(prescriptionDate)) {
                errors.add("Expiry date cannot precede prescription date.");
            } else if (expiryDate.isBefore(now)) {
                isExpired = true;
                errors.add("Prescription has expired.");
            }
        }

        // 3. Document check
        if (containsAntibiotic && !hasAttachment) {
            errors.add("Supporting prescription document is required for antibiotics.");
        }

        ValidityStatus status;
        if (isExpired) {
            status = ValidityStatus.EXPIRED;
        } else if (containsAntibiotic && !hasAttachment) {
            status = ValidityStatus.MISSING_DOCUMENTS;
        } else if (!errors.isEmpty()) {
            status = ValidityStatus.INCOMPLETE_CREDENTIALS;
        } else {
            status = ValidityStatus.VALID;
        }

        boolean isValid = errors.isEmpty() && status == ValidityStatus.VALID;
        return new ValidityReportDto(
                prescriptionId,
                "RX-LOCAL-PREFLIGHT",
                status,
                isValid,
                isExpired,
                hasAttachment,
                containsAntibiotic,
                isValid,
                errors
        );
    }
}

