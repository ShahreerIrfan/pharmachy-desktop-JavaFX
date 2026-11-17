package com.pharmacy.pos.prescriptions;

import java.util.Arrays;
import java.util.List;

/**
 * ComplianceAuditExportClient.java
 * Architecture Decision: ADR-0258 (P258).
 * Phase 13: Prescriptions, Antibiotic Register & Compliance Safety.
 *
 * Desktop client for DGDA Statutory Compliance Audit Package Exports,
 * Time-filter validations, Patient Privacy (PHI) Redactions, and Manifest Verification.
 */
public class ComplianceAuditExportClient {

    public static final List<String> VALID_SCOPES = Arrays.asList(
        "FULL_REGULATORY_BUNDLE",
        "ANTIBIOTIC_STEWARDSHIP",
        "COLD_CHAIN_TEMPERATURE",
        "PRESCRIPTION_VERIFICATION",
        "INCIDENT_CORRECTION"
    );

    public static boolean canExportCompliancePackage(String role) {
        if (role == null) return false;
        String r = role.trim().toUpperCase();
        return "SUPERADMIN".equals(r)
            || "PHARMACY_OWNER".equals(r)
            || "BRANCH_MANAGER".equals(r)
            || "STAFF_PHARMACIST".equals(r);
    }

    public static String maskPatientName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "P-ANONYMOUS";
        }
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.length() <= 1) {
                sb.append(p).append("***");
            } else {
                sb.append(p.charAt(0)).append("***");
            }
            if (i < parts.length - 1) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

    public static String maskPhoneNumber(String phone) {
        if (phone == null || phone.trim().isEmpty()) {
            return "N/A";
        }
        String clean = phone.trim();
        if (clean.length() >= 10) {
            return clean.substring(0, 6) + "****" + clean.substring(clean.length() - 4);
        }
        return clean.substring(0, Math.min(2, clean.length())) + "****";
    }

    public static void validateDateRange(String startDate, String endDate) {
        if (startDate == null || startDate.trim().isEmpty()) {
            throw new IllegalArgumentException("startDate is required.");
        }
        if (endDate == null || endDate.trim().isEmpty()) {
            throw new IllegalArgumentException("endDate is required.");
        }
        if (startDate.compareTo(endDate) > 0) {
            throw new IllegalArgumentException("startDate cannot be later than endDate.");
        }
    }

    public static String formatManifestSummary(
        String exportId,
        String scope,
        int recordsCount,
        String sha256,
        boolean isBangla
    ) {
        String id = exportId != null ? exportId : "UNKNOWN";
        String sc = scope != null ? scope : "FULL_REGULATORY_BUNDLE";
        String hashSnippet = (sha256 != null && sha256.length() >= 8) ? sha256.substring(0, 8) + "..." : "SEALED";

        if (isBangla) {
            return "ডিজিডিএ অডিট প্যাকেজ [" + id + "] - পরিধি: " + sc + ", রেকর্ড: " + recordsCount + " টি (হ্যাশ: " + hashSnippet + ")";
        }
        return "DGDA Audit Package [" + id + "] - Scope: " + sc + ", Records: " + recordsCount + " (Hash: " + hashSnippet + ")";
    }
}
