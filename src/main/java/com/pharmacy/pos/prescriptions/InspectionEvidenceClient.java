package com.pharmacy.pos.prescriptions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * InspectionEvidenceClient.java
 * Architecture Decision: ADR-0254 (P254).
 * Desktop POS client for DGDA Statutory Inspection Evidence Repository:
 * Refrigerator logs (cold-chain), staff training proofs, expired stock disposal,
 * access classifications, and audit package verification.
 */
public class InspectionEvidenceClient {

    public static final List<String> VALID_EVIDENCE_TYPES = Arrays.asList(
        "REFRIGERATOR_TEMPERATURE_LOG",
        "STAFF_TRAINING_PROOF",
        "EXPIRED_STOCK_DISPOSAL",
        "PEST_CONTROL_SANITATION",
        "DGDA_INSPECTION_MEMO",
        "EQUIPMENT_CALIBRATION"
    );

    public static final List<String> VALID_ACCESS_LEVELS = Arrays.asList(
        "PUBLIC",
        "INTERNAL",
        "CONFIDENTIAL",
        "RESTRICTED"
    );

    public static boolean canManageEvidence(String role) {
        if (role == null) return false;
        String normalized = role.trim().toUpperCase();
        return "SUPERADMIN".equals(normalized)
            || "PHARMACY_OWNER".equals(normalized)
            || "BRANCH_MANAGER".equals(normalized)
            || "STAFF_PHARMACIST".equals(normalized);
    }

    public static boolean canExportAuditPackage(String role) {
        if (role == null) return false;
        String normalized = role.trim().toUpperCase();
        return "SUPERADMIN".equals(normalized)
            || "PHARMACY_OWNER".equals(normalized)
            || "BRANCH_MANAGER".equals(normalized);
    }

    public static boolean canAccessDocument(
        String role,
        String accessLevel,
        String userBranchId,
        String recordBranchId
    ) {
        if (role == null || accessLevel == null) return false;
        String normRole = role.trim().toUpperCase();
        String normLevel = accessLevel.trim().toUpperCase();

        if ("SUPERADMIN".equals(normRole) || "PHARMACY_OWNER".equals(normRole)) {
            return true;
        }

        if ("BRANCH_MANAGER".equals(normRole)) {
            return userBranchId != null && userBranchId.equals(recordBranchId);
        }

        if ("STAFF_PHARMACIST".equals(normRole)) {
            if (userBranchId == null || !userBranchId.equals(recordBranchId)) {
                return false;
            }
            return !"RESTRICTED".equals(normLevel);
        }

        if ("CASHIER".equals(normRole)) {
            return "PUBLIC".equals(normLevel);
        }

        return false;
    }

    public static boolean isColdChainExcursion(double minTempCelsius, double maxTempCelsius) {
        return minTempCelsius < 2.0 || maxTempCelsius > 8.0;
    }

    public static void validatePreflight(
        String title,
        String evidenceType,
        LocalDate logDate,
        String documentUri,
        String sha256Hash
    ) {
        if (title == null || title.trim().length() < 3) {
            throw new IllegalArgumentException("Evidence title must be at least 3 characters.");
        }
        if (evidenceType == null || !VALID_EVIDENCE_TYPES.contains(evidenceType.trim().toUpperCase())) {
            throw new IllegalArgumentException("Invalid evidence type: " + evidenceType);
        }
        if (logDate == null) {
            throw new IllegalArgumentException("logDate is required.");
        }
        if (logDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("logDate cannot be in the future.");
        }
        if (documentUri == null || documentUri.trim().isEmpty()) {
            throw new IllegalArgumentException("documentUri is required.");
        }
        if (sha256Hash != null && !sha256Hash.trim().isEmpty()) {
            if (sha256Hash.trim().length() != 64) {
                throw new IllegalArgumentException("sha256Hash must be a 64-character hexadecimal string.");
            }
        }
    }

    public static String getEvidenceTypeDisplay(String type, String lang) {
        if (type == null) return "";
        boolean isBn = "bn".equalsIgnoreCase(lang);
        switch (type.trim().toUpperCase()) {
            case "REFRIGERATOR_TEMPERATURE_LOG":
                return isBn ? "রেফ্রিজারেটর তাপমাত্রা লগ (কোল্ড-চেইন)" : "Refrigerator Temperature Log (Cold-Chain)";
            case "STAFF_TRAINING_PROOF":
                return isBn ? "কর্মী প্রশিক্ষণ প্রমাণপত্র" : "Staff Training Proof / Certification";
            case "EXPIRED_STOCK_DISPOSAL":
                return isBn ? "মেয়াদোত্তীর্ণ ওষুধ বিনষ্টকরণ নথি" : "Expired Stock Disposal Record";
            case "PEST_CONTROL_SANITATION":
                return isBn ? "কীটপতঙ্গ নিয়ন্ত্রণ ও স্যানিটেশন লগ" : "Pest Control & Sanitation Record";
            case "DGDA_INSPECTION_MEMO":
                return isBn ? "ডিজিডিএ পরিদর্শন মেমো / পর্যবেক্ষণ" : "DGDA Inspection Memo / Finding";
            case "EQUIPMENT_CALIBRATION":
                return isBn ? "যন্ত্রপাতি ক্যালিব্রেশন রেকর্ড" : "Equipment Calibration Record";
            default:
                return type;
        }
    }

    public static String formatAuditPackageSummary(
        int totalRecords,
        int excursionCount,
        BigDecimal disposalBdt,
        String lang
    ) {
        boolean isBn = "bn".equalsIgnoreCase(lang);
        BigDecimal val = disposalBdt != null ? disposalBdt : BigDecimal.ZERO;
        if (isBn) {
            return String.format(
                "ডিজিডিএ অডিট প্যাকেজ: মোট %dটি রেকর্ড, %dটি কোল্ড-চেইন বিচ্যুতি, বিনষ্ট ওষুধের মূল্য ৳%s",
                totalRecords, excursionCount, val.toPlainString()
            );
        } else {
            return String.format(
                "DGDA Audit Package: %d records, %d cold-chain excursions, Expired stock value: ৳%s",
                totalRecords, excursionCount, val.toPlainString()
            );
        }
    }
}

