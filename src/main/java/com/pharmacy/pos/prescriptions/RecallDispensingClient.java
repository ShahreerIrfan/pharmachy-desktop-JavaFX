package com.pharmacy.pos.prescriptions;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * RecallDispensingClient.java
 * Architecture Decision: ADR-0256 (P256).
 * Desktop POS client for Batch Recall Notification, Dispensing Lock,
 * and Historical Sales Audit without rewriting history.
 */
public class RecallDispensingClient {

    public static final List<String> VALID_CLASSES = Arrays.asList(
        "CLASS_I",
        "CLASS_II",
        "CLASS_III"
    );

    public static final List<String> VALID_SOURCES = Arrays.asList(
        "DGDA_MANDATORY",
        "MANUFACTURER_VOLUNTARY",
        "INTERNAL_PHARMACY"
    );

    public static boolean canManageRecall(String role) {
        if (role == null) return false;
        String r = role.trim().toUpperCase();
        return "SUPERADMIN".equals(r)
            || "PHARMACY_OWNER".equals(r)
            || "BRANCH_MANAGER".equals(r)
            || "STAFF_PHARMACIST".equals(r);
    }

    public static boolean canDispenseBatch(boolean isRecalled, String status) {
        if (isRecalled) {
            return false;
        }
        if (status == null) {
            return false;
        }
        String s = status.trim().toUpperCase();
        return "ACTIVE".equals(s);
    }

    public static void validatePreflight(
        String batchNumber,
        String productName,
        String recallReason,
        String recallClass
    ) {
        if (batchNumber == null || batchNumber.trim().length() < 2) {
            throw new IllegalArgumentException("batchNumber must be at least 2 characters.");
        }
        if (productName == null || productName.trim().isEmpty()) {
            throw new IllegalArgumentException("productName is required.");
        }
        if (recallReason == null || recallReason.trim().isEmpty()) {
            throw new IllegalArgumentException("recallReason is required.");
        }
        if (recallClass == null || !VALID_CLASSES.contains(recallClass.trim().toUpperCase())) {
            throw new IllegalArgumentException("Invalid recallClass: " + recallClass);
        }
    }

    public static String getRecallClassDisplay(String recallClass, String lang) {
        if (recallClass == null) return "";
        boolean isBn = "bn".equalsIgnoreCase(lang);
        switch (recallClass.trim().toUpperCase()) {
            case "CLASS_I":
                return isBn ? "ক্লাস ১ (মারাত্মক / জীবন বিপন্নকারী ঝুঁকি)" : "Class I (Dangerous / Life-threatening)";
            case "CLASS_II":
                return isBn ? "ক্লাস ২ (অস্থায়ী বা চিকিৎসাযোগ্য ঝুঁকি)" : "Class II (Temporary / Reversible defect)";
            case "CLASS_III":
                return isBn ? "ক্লাস ৩ (লেবেলিং / কারিগরি ত্রুটি)" : "Class III (Technical / Labeling defect)";
            default:
                return recallClass;
        }
    }

    public static String formatHistoricalImpactSummary(
        int totalInvoices,
        BigDecimal totalUnitsSold,
        BigDecimal totalValueBdt,
        String lang
    ) {
        boolean isBn = "bn".equalsIgnoreCase(lang);
        BigDecimal units = totalUnitsSold != null ? totalUnitsSold : BigDecimal.ZERO;
        BigDecimal val = totalValueBdt != null ? totalValueBdt : BigDecimal.ZERO;
        if (isBn) {
            return String.format(
                "রিকল অডিট ট্র্যাকিং: মোট %dটি ইনভয়েস প্রভাবিত, বিক্রিত ওষুধ %s ইউনিট, মোট মূল্য ৳%s (অতীত সেলস রেকর্ড অপরিবর্তিত)",
                totalInvoices, units.toPlainString(), val.toPlainString()
            );
        } else {
            return String.format(
                "Recall Historical Audit: %d invoices impacted, %s units sold, Total value: ৳%s (Past sales history unmodified)",
                totalInvoices, units.toPlainString(), val.toPlainString()
            );
        }
    }
}

