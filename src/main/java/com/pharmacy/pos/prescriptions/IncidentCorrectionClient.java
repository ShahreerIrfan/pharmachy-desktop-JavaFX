package com.pharmacy.pos.prescriptions;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * IncidentCorrectionClient.java
 * Architecture Decision: ADR-0255 (P255).
 * Desktop POS client for Clinical and Operational Incident Management:
 * Incorrect dispensing, damage reports, product recalls, inventory discrepancies,
 * CAPA tracking, and strict Pharmacist/Manager closure approvals.
 */
public class IncidentCorrectionClient {

    public static final List<String> VALID_TYPES = Arrays.asList(
        "INCORRECT_DISPENSING",
        "SUSPECTED_DAMAGE",
        "PRODUCT_RECALL",
        "INVENTORY_DISCREPANCY",
        "ADVERSE_DRUG_REACTION",
        "COLD_CHAIN_EXCURSION"
    );

    public static final List<String> VALID_SEVERITIES = Arrays.asList(
        "LOW",
        "MEDIUM",
        "HIGH",
        "CRITICAL"
    );

    public static final List<String> VALID_STATUSES = Arrays.asList(
        "REPORTED",
        "UNDER_INVESTIGATION",
        "CORRECTIVE_ACTION_PENDING",
        "CLOSED",
        "DISMISSED"
    );

    public static boolean canReportIncident(String role) {
        if (role == null) return false;
        String r = role.trim().toUpperCase();
        return "SUPERADMIN".equals(r)
            || "PHARMACY_OWNER".equals(r)
            || "BRANCH_MANAGER".equals(r)
            || "STAFF_PHARMACIST".equals(r)
            || "CASHIER".equals(r);
    }

    public static boolean canConductInvestigation(String role) {
        if (role == null) return false;
        String r = role.trim().toUpperCase();
        return "SUPERADMIN".equals(r)
            || "PHARMACY_OWNER".equals(r)
            || "BRANCH_MANAGER".equals(r)
            || "STAFF_PHARMACIST".equals(r);
    }

    public static boolean canApproveClosure(
        String role,
        String userBranchId,
        String recordBranchId
    ) {
        if (role == null) return false;
        String r = role.trim().toUpperCase();
        if ("SUPERADMIN".equals(r) || "PHARMACY_OWNER".equals(r)) {
            return true;
        }
        if ("BRANCH_MANAGER".equals(r) || "STAFF_PHARMACIST".equals(r)) {
            return userBranchId != null && userBranchId.equals(recordBranchId);
        }
        // Cashiers cannot approve closures
        return false;
    }

    public static boolean isDgdaNotificationRequired(String incidentType, String severity) {
        if ("CRITICAL".equalsIgnoreCase(severity)) {
            return true;
        }
        if ("PRODUCT_RECALL".equalsIgnoreCase(incidentType)) {
            return true;
        }
        if ("ADVERSE_DRUG_REACTION".equalsIgnoreCase(incidentType) && "HIGH".equalsIgnoreCase(severity)) {
            return true;
        }
        return false;
    }

    public static void validateClosureRequirements(String severity, String rootCause, String capa) {
        if (severity == null) return;
        String sev = severity.trim().toUpperCase();
        if ("HIGH".equals(sev) || "CRITICAL".equals(sev)) {
            if (rootCause == null || rootCause.trim().isEmpty()) {
                throw new IllegalStateException("High and Critical incidents require documented Root Cause before closure.");
            }
            if (capa == null || capa.trim().isEmpty()) {
                throw new IllegalStateException("High and Critical incidents require a documented Corrective and Preventive Action (CAPA) plan before closure.");
            }
        }
    }

    public static String getIncidentTypeDisplay(String type, String lang) {
        if (type == null) return "";
        boolean isBn = "bn".equalsIgnoreCase(lang);
        switch (type.trim().toUpperCase()) {
            case "INCORRECT_DISPENSING":
                return isBn ? "ভুল ওষুধ প্রদান / নিয়ার-মিস" : "Incorrect Dispensing / Near Miss";
            case "SUSPECTED_DAMAGE":
                return isBn ? "ওষুধের ক্ষতি / প্যাকেজিং ত্রুটি" : "Suspected Damage / Packaging Compromise";
            case "PRODUCT_RECALL":
                return isBn ? "ওষুধ প্রত্যাহার (রিকল)" : "Product Recall (DGDA / Manufacturer)";
            case "INVENTORY_DISCREPANCY":
                return isBn ? "ইনভেন্টরি অমিল / স্টক গরমিল" : "Inventory Discrepancy";
            case "ADVERSE_DRUG_REACTION":
                return isBn ? "ওষুধের পার্শ্বপ্রতিক্রিয়া (ADR)" : "Adverse Drug Reaction";
            case "COLD_CHAIN_EXCURSION":
                return isBn ? "কোল্ড-চেইন তাপমাত্রা বিচ্যুতি" : "Cold-Chain Temperature Excursion";
            default:
                return type;
        }
    }

    public static String getSeverityDisplay(String severity, String lang) {
        if (severity == null) return "";
        boolean isBn = "bn".equalsIgnoreCase(lang);
        switch (severity.trim().toUpperCase()) {
            case "LOW":
                return isBn ? "স্বাভাবিক (Low)" : "Low (Minor / Near-miss)";
            case "MEDIUM":
                return isBn ? "মাঝারি (Medium)" : "Medium (Moderate)";
            case "HIGH":
                return isBn ? "উচ্চ (High)" : "High (Clinical Error)";
            case "CRITICAL":
                return isBn ? "মারাত্মক (Critical)" : "Critical (DGDA Alert)";
            default:
                return severity;
        }
    }

    public static String formatBranchSummary(
        int totalIncidents,
        int criticalCount,
        BigDecimal totalFinancialImpact,
        String lang
    ) {
        boolean isBn = "bn".equalsIgnoreCase(lang);
        BigDecimal val = totalFinancialImpact != null ? totalFinancialImpact : BigDecimal.ZERO;
        if (isBn) {
            return String.format(
                "ইনসিডেন্ট রিপোর্ট: মোট %dটি ঘটনা, %dটি মারাত্মক/জরুরি, মোট আর্থিক ক্ষতি ৳%s",
                totalIncidents, criticalCount, val.toPlainString()
            );
        } else {
            return String.format(
                "Incident Summary: %d total, %d critical alerts, Total financial loss: ৳%s",
                totalIncidents, criticalCount, val.toPlainString()
            );
        }
    }
}

