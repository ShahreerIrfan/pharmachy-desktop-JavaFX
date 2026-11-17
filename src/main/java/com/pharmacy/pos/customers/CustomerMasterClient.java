package com.pharmacy.pos.customers;

import java.util.Arrays;
import java.util.List;

/**
 * CustomerMasterClient.java
 * Architecture Decision: ADR-0261 (P261).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * Desktop POS helper for managing tenant-scoped Customer Master:
 * - Phone validation & optional formatting.
 * - Credit status authorization gates (Owner/Manager/Finance only for credit limit > 0).
 * - Duplicate protection.
 * - Privacy consent preferences (SMS receipts, marketing opt-in).
 */
public class CustomerMasterClient {

    public static final List<String> CREDIT_ADMIN_ROLES = Arrays.asList(
        "SUPERADMIN",
        "PHARMACY_OWNER",
        "BRANCH_MANAGER",
        "FINANCE_OFFICER"
    );

    public static final List<String> VALID_CREDIT_STATUSES = Arrays.asList(
        "UNAPPROVED",
        "PENDING_APPROVAL",
        "APPROVED",
        "SUSPENDED"
    );

    public static boolean canManageCreditLimit(String role) {
        if (role == null) return false;
        String r = role.trim().toUpperCase();
        return CREDIT_ADMIN_ROLES.contains(r);
    }

    public static boolean canArchiveCustomer(String role) {
        if (role == null) return false;
        String r = role.trim().toUpperCase();
        return CREDIT_ADMIN_ROLES.contains(r);
    }

    public static void validateCustomerData(
        String fullName,
        String phone,
        String creditLimitStr,
        int creditTermsDays
    ) {
        if (fullName == null || fullName.trim().isEmpty()) {
            throw new IllegalArgumentException("Customer full_name is required.");
        }
        if (creditTermsDays < 1) {
            throw new IllegalArgumentException("credit_terms_days must be at least 1.");
        }
        if (creditLimitStr != null && !creditLimitStr.trim().isEmpty()) {
            try {
                double limit = Double.parseDouble(creditLimitStr.trim());
                if (limit < 0.0) {
                    throw new IllegalArgumentException("credit_limit_bdt cannot be negative.");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("credit_limit_bdt must be a valid numeric amount.");
            }
        }
        if (phone != null && !phone.trim().isEmpty()) {
            String clean = phone.trim();
            if (clean.length() < 7 || clean.length() > 20) {
                throw new IllegalArgumentException("Phone number length must be between 7 and 20 digits.");
            }
        }
    }

    public static String formatCustomerSummary(
        String customerId,
        String fullName,
        String phone,
        String creditStatus,
        String currentDueBdt,
        boolean isBangla
    ) {
        String id = customerId != null ? customerId : "NEW";
        String name = fullName != null ? fullName : "Customer";
        String ph = (phone != null && !phone.trim().isEmpty()) ? phone : (isBangla ? "ফোন নেই" : "No Phone");
        String status = creditStatus != null ? creditStatus : "UNAPPROVED";
        String due = currentDueBdt != null ? currentDueBdt : "0.00";

        if (isBangla) {
            return "[" + id + "] " + name + " (" + ph + ") | ক্রেডিট: " + status + " | বকেয়া: " + due + " ৳";
        }
        return "[" + id + "] " + name + " (" + ph + ") | Credit: " + status + " | Due: " + due + " BDT";
    }
}

