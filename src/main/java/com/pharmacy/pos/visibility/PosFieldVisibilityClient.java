package com.pharmacy.pos.visibility;

import java.math.BigDecimal;
import java.util.*;

/**
 * P110: Fine-Grained Field Visibility & Sensitive Data Masking Client (JavaFX POS).
 * ADR-0110: Mask purchase costs, profits, salaries, prescriptions, and patient PII
 * across UI tables, form dialogs, offline cache, and data exports.
 */
public class PosFieldVisibilityClient {

    public static class FieldVisibilityProfile {
        public final String userId;
        public final String tenantId;
        public final String role;
        public final boolean costVisibility;
        public final boolean profitVisibility;
        public final boolean salaryVisibility;
        public final boolean prescriptionVisibility;
        public final boolean patientPiiVisibility;

        public FieldVisibilityProfile(
                String userId,
                String tenantId,
                String role,
                boolean costVisibility,
                boolean profitVisibility,
                boolean salaryVisibility,
                boolean prescriptionVisibility,
                boolean patientPiiVisibility
        ) {
            this.userId = userId;
            this.tenantId = tenantId;
            this.role = role != null ? role.toUpperCase() : "UNKNOWN";
            this.costVisibility = costVisibility;
            this.profitVisibility = profitVisibility;
            this.salaryVisibility = salaryVisibility;
            this.prescriptionVisibility = prescriptionVisibility;
            this.patientPiiVisibility = patientPiiVisibility;
        }

        public boolean isOwnerOrSuperAdmin() {
            return "PHARMACY_OWNER".equals(role) || "SUPERADMIN".equals(role) || "SUPER_ADMIN".equals(role);
        }
    }

    public static class MaskedMedicine {
        public final String productId;
        public final String brandName;
        public final BigDecimal mrpUnit;
        public final BigDecimal costUnit; // null if masked
        public final boolean isCostMasked;

        public MaskedMedicine(String productId, String brandName, BigDecimal mrpUnit, BigDecimal costUnit, boolean isCostMasked) {
            this.productId = productId;
            this.brandName = brandName;
            this.mrpUnit = mrpUnit;
            this.costUnit = costUnit;
            this.isCostMasked = isCostMasked;
        }

        public String getDisplayCost(String language) {
            if (isCostMasked || costUnit == null) {
                return "***";
            }
            return "৳" + costUnit.toPlainString();
        }

        public String getCostTooltip(String language) {
            if (!isCostMasked) return "";
            return "bn".equalsIgnoreCase(language) ? "পাইকারি ক্রয়মূল্য দেখার অনুমতি নেই।" : "Wholesale purchase cost is restricted.";
        }
    }

    public static class MaskedInvoice {
        public final String invoiceId;
        public final String customerName; // e.g. "R**** U****" if masked
        public final String customerPhone; // e.g. "017****5678" if masked
        public final BigDecimal totalAmount;
        public final BigDecimal cogsAmount; // null if cost masked
        public final BigDecimal grossProfit; // null if profit masked
        public final boolean isCostMasked;
        public final boolean isProfitMasked;
        public final boolean isPiiMasked;

        public MaskedInvoice(
                String invoiceId,
                String customerName,
                String customerPhone,
                BigDecimal totalAmount,
                BigDecimal cogsAmount,
                BigDecimal grossProfit,
                boolean isCostMasked,
                boolean isProfitMasked,
                boolean isPiiMasked
        ) {
            this.invoiceId = invoiceId;
            this.customerName = customerName;
            this.customerPhone = customerPhone;
            this.totalAmount = totalAmount;
            this.cogsAmount = cogsAmount;
            this.grossProfit = grossProfit;
            this.isCostMasked = isCostMasked;
            this.isProfitMasked = isProfitMasked;
            this.isPiiMasked = isPiiMasked;
        }

        public String getDisplayProfit(String language) {
            if (isProfitMasked || grossProfit == null) {
                return "***";
            }
            return "৳" + grossProfit.toPlainString();
        }

        public String getProfitTooltip(String language) {
            if (!isProfitMasked) return "";
            return "bn".equalsIgnoreCase(language) ? "মুনাফার তথ্য দেখার অনুমতি নেই।" : "Gross profit metrics are restricted.";
        }
    }

    public static class MaskedSalary {
        public final String salaryId;
        public final String staffUserId;
        public final String username;
        public final BigDecimal baseSalaryBdt; // null if masked
        public final String bankAccountNo; // "***REDACTED***" if masked
        public final boolean isSalaryMasked;

        public MaskedSalary(
                String salaryId,
                String staffUserId,
                String username,
                BigDecimal baseSalaryBdt,
                String bankAccountNo,
                boolean isSalaryMasked
        ) {
            this.salaryId = salaryId;
            this.staffUserId = staffUserId;
            this.username = username;
            this.baseSalaryBdt = baseSalaryBdt;
            this.bankAccountNo = bankAccountNo;
            this.isSalaryMasked = isSalaryMasked;
        }

        public String getDisplaySalary(String language) {
            if (isSalaryMasked || baseSalaryBdt == null) {
                return "***REDACTED***";
            }
            return "৳" + baseSalaryBdt.toPlainString();
        }
    }

    public static class MaskedPrescription {
        public final String prescriptionId;
        public final String doctorName;
        public final String diagnosis;
        public final String notes;
        public final String prescriptionImageUrl;
        public final boolean isPrescriptionMasked;

        public MaskedPrescription(
                String prescriptionId,
                String doctorName,
                String diagnosis,
                String notes,
                String prescriptionImageUrl,
                boolean isPrescriptionMasked
        ) {
            this.prescriptionId = prescriptionId;
            this.doctorName = doctorName;
            this.diagnosis = diagnosis;
            this.notes = notes;
            this.prescriptionImageUrl = prescriptionImageUrl;
            this.isPrescriptionMasked = isPrescriptionMasked;
        }
    }

    // -------------------------------------------------------------------------
    // Profile Resolution
    // -------------------------------------------------------------------------

    public static FieldVisibilityProfile resolveProfile(
            String userId,
            String tenantId,
            String role,
            List<String> customPermissionKeys
    ) {
        String cleanRole = role != null ? role.toUpperCase().trim() : "CASHIER";

        // Superadmin and Owner get all 5
        if ("PHARMACY_OWNER".equals(cleanRole) || "SUPERADMIN".equals(cleanRole) || "SUPER_ADMIN".equals(cleanRole)) {
            return new FieldVisibilityProfile(userId, tenantId, cleanRole, true, true, true, true, true);
        }

        // Custom Role overrides
        if (customPermissionKeys != null && !customPermissionKeys.isEmpty()) {
            Set<String> keys = new HashSet<>(customPermissionKeys);
            boolean cost = keys.contains("cost_visibility") || keys.contains("action:cost_visibility") || keys.contains("inventory:view_cost");
            boolean profit = keys.contains("profit_visibility") || keys.contains("action:profit_visibility") || keys.contains("finance:profit_visibility");
            boolean salary = keys.contains("salary_visibility") || keys.contains("action:salary_visibility") || keys.contains("hr:view_salary");
            boolean rx = keys.contains("prescription_visibility") || keys.contains("action:rx_review") || keys.contains("rx:verify");
            boolean pii = keys.contains("patient_pii_visibility") || keys.contains("patient:view_pii");
            return new FieldVisibilityProfile(userId, tenantId, cleanRole, cost, profit, salary, rx, pii);
        }

        // Canonical System Defaults (ADR-0110)
        switch (cleanRole) {
            case "BRANCH_MANAGER":
                return new FieldVisibilityProfile(userId, tenantId, cleanRole, true, false, false, true, true);
            case "FINANCE_OFFICER":
                return new FieldVisibilityProfile(userId, tenantId, cleanRole, true, true, true, false, false);
            case "STAFF_PHARMACIST":
            case "PHARMACIST":
                return new FieldVisibilityProfile(userId, tenantId, cleanRole, false, false, false, true, true);
            case "INVENTORY_CLERK":
                return new FieldVisibilityProfile(userId, tenantId, cleanRole, true, false, false, false, false);
            case "AUDITOR":
                return new FieldVisibilityProfile(userId, tenantId, cleanRole, true, true, false, true, true);
            case "CASHIER":
            default:
                return new FieldVisibilityProfile(userId, tenantId, cleanRole, false, false, false, false, false);
        }
    }

    // -------------------------------------------------------------------------
    // Masking Helpers
    // -------------------------------------------------------------------------

    public static String maskPhoneNumber(String phone) {
        if (phone == null || phone.trim().isEmpty()) return "***";
        String s = phone.trim();
        if (s.length() >= 11) {
            return s.substring(0, 3) + "****" + s.substring(s.length() - 4);
        } else if (s.length() >= 6) {
            return s.substring(0, 2) + "****" + s.substring(s.length() - 2);
        }
        return "***REDACTED***";
    }

    public static String maskPersonName(String name) {
        if (name == null || name.trim().isEmpty()) return "***";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.length() > 1) {
                sb.append(p.charAt(0)).append("****");
            } else {
                sb.append(p);
            }
            if (i < parts.length - 1) sb.append(" ");
        }
        return sb.toString();
    }

    public static MaskedMedicine maskMedicine(
            String productId,
            String brandName,
            BigDecimal mrpUnit,
            BigDecimal costUnit,
            FieldVisibilityProfile profile
    ) {
        boolean maskCost = !profile.costVisibility;
        return new MaskedMedicine(
                productId,
                brandName,
                mrpUnit,
                maskCost ? null : costUnit,
                maskCost
        );
    }

    public static MaskedInvoice maskInvoice(
            String invoiceId,
            String customerName,
            String customerPhone,
            BigDecimal totalAmount,
            BigDecimal cogsAmount,
            BigDecimal grossProfit,
            FieldVisibilityProfile profile
    ) {
        boolean maskCost = !profile.costVisibility;
        boolean maskProfit = !profile.profitVisibility;
        boolean maskPii = !profile.patientPiiVisibility;

        String effName = maskPii ? maskPersonName(customerName) : customerName;
        String effPhone = maskPii ? maskPhoneNumber(customerPhone) : customerPhone;

        return new MaskedInvoice(
                invoiceId,
                effName,
                effPhone,
                totalAmount,
                maskCost ? null : cogsAmount,
                maskProfit ? null : grossProfit,
                maskCost,
                maskProfit,
                maskPii
        );
    }

    public static MaskedSalary maskSalary(
            String salaryId,
            String staffUserId,
            String username,
            BigDecimal baseSalaryBdt,
            String bankAccountNo,
            FieldVisibilityProfile profile
    ) {
        boolean maskSalary = !profile.salaryVisibility;
        return new MaskedSalary(
                salaryId,
                staffUserId,
                username,
                maskSalary ? null : baseSalaryBdt,
                maskSalary ? "***REDACTED***" : bankAccountNo,
                maskSalary
        );
    }

    public static MaskedPrescription maskPrescription(
            String prescriptionId,
            String doctorName,
            String diagnosis,
            String notes,
            String prescriptionImageUrl,
            FieldVisibilityProfile profile
    ) {
        boolean maskRx = !profile.prescriptionVisibility;
        return new MaskedPrescription(
                prescriptionId,
                doctorName,
                maskRx ? "[CLINICAL_DIAGNOSIS_REDACTED]" : diagnosis,
                maskRx ? "[CONFIDENTIAL_CLINICAL_NOTES]" : notes,
                maskRx ? null : prescriptionImageUrl,
                maskRx
        );
    }

    /**
     * Client-side CSV export generator with guaranteed field masking.
     */
    public static String exportInvoicesCsv(List<MaskedInvoice> invoices) {
        StringBuilder sb = new StringBuilder();
        sb.append("Invoice ID,Customer Name,Customer Phone,Total Amount,COGS Amount,Gross Profit\n");
        for (MaskedInvoice inv : invoices) {
            sb.append(inv.invoiceId).append(",");
            sb.append(inv.customerName).append(",");
            sb.append(inv.customerPhone).append(",");
            sb.append(inv.totalAmount).append(",");
            sb.append(inv.cogsAmount != null ? inv.cogsAmount.toPlainString() : "").append(",");
            sb.append(inv.grossProfit != null ? inv.grossProfit.toPlainString() : "").append("\n");
        }
        return sb.toString();
    }
}

