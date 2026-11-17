package com.pharmacy.pos.prescriptions;

import java.util.Arrays;
import java.util.List;

/**
 * RegulatoryReferenceClient.java
 * Architecture Decision: ADR-0259 (P259).
 * Phase 13: Prescriptions, Antibiotic Register & Compliance Safety.
 *
 * Desktop POS helper for maintaining reviewed statutory and clinical regulatory references
 * with scheduled reviews, authority provenance, and overdue alert status badges.
 */
public class RegulatoryReferenceClient {

    public static final List<String> VALID_JURISDICTIONS = Arrays.asList(
        "DGDA_BANGLADESH",
        "PCB_BANGLADESH",
        "WHO_INTERNATIONAL",
        "MOHFW_BANGLADESH",
        "OTHER"
    );

    public static final List<String> VALID_STATUSES = Arrays.asList(
        "ACTIVE",
        "UNDER_REVIEW",
        "SUPERSEDED",
        "ARCHIVED"
    );

    public static boolean canManageReference(String role) {
        if (role == null) return false;
        String r = role.trim().toUpperCase();
        return "SUPERADMIN".equals(r)
            || "PHARMACY_OWNER".equals(r)
            || "BRANCH_MANAGER".equals(r)
            || "STAFF_PHARMACIST".equals(r);
    }

    public static boolean canViewReference(String role) {
        if (role == null) return false;
        String r = role.trim().toUpperCase();
        return "SUPERADMIN".equals(r)
            || "PHARMACY_OWNER".equals(r)
            || "BRANCH_MANAGER".equals(r)
            || "STAFF_PHARMACIST".equals(r)
            || "CASHIER".equals(r);
    }

    public static void validateReferenceData(
        String title,
        String sourceUri,
        String version,
        String effectiveDate,
        String nextReviewDate
    ) {
        if (title == null || title.trim().length() < 3) {
            throw new IllegalArgumentException("Title must be at least 3 characters.");
        }
        if (sourceUri == null || (!sourceUri.startsWith("http://") && !sourceUri.startsWith("https://") && !sourceUri.startsWith("ftp://"))) {
            throw new IllegalArgumentException("sourceUri must be a valid URL starting with http://, https://, or ftp://.");
        }
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalArgumentException("version is required.");
        }
        if (effectiveDate == null || effectiveDate.trim().isEmpty()) {
            throw new IllegalArgumentException("effectiveDate is required (YYYY-MM-DD).");
        }
        if (nextReviewDate == null || nextReviewDate.trim().isEmpty()) {
            throw new IllegalArgumentException("nextReviewDate is required (YYYY-MM-DD).");
        }
        if (effectiveDate.compareTo(nextReviewDate) > 0) {
            throw new IllegalArgumentException("nextReviewDate cannot be earlier than effectiveDate.");
        }
    }

    public static boolean isReviewOverdue(String nextReviewDate, String currentDate) {
        if (nextReviewDate == null || currentDate == null) return false;
        return nextReviewDate.compareTo(currentDate) < 0;
    }

    public static String formatReferenceBadge(
        String referenceId,
        String jurisdiction,
        String version,
        String status,
        boolean isOverdue,
        boolean isBangla
    ) {
        String id = referenceId != null ? referenceId : "NEW";
        String jur = jurisdiction != null ? jurisdiction : "DGDA";
        String ver = version != null ? version : "v1.0";
        String st = status != null ? status : "ACTIVE";

        if (isOverdue) {
            return isBangla
                ? "[মেয়াদোত্তীর্ণ রিভিউ] " + jur + " - " + id + " (ভার্সন: " + ver + ")"
                : "[REVIEW OVERDUE] " + jur + " - " + id + " (ver: " + ver + ")";
        }

        return isBangla
            ? "[" + st + "] " + jur + " - " + id + " (ভার্সন: " + ver + ")"
            : "[" + st + "] " + jur + " - " + id + " (ver: " + ver + ")";
    }
}
