package com.pharmacy.pos.customers;

import java.util.Arrays;
import java.util.List;

/**
 * CustomerReceivableLedgerClient.java
 * Architecture Decision: ADR-0265 (P265).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * Desktop POS helper for Customer Accounts Receivable (AR) Ledger:
 * - Running balance calculation (Debit increases AR, Credit decreases AR).
 * - Balance parity verification (Opening + Debits - Credits == Closing).
 * - RBAC rules for audited adjustments and compensating reversals.
 */
public class CustomerReceivableLedgerClient {

    public static final List<String> LEDGER_ADMIN_ROLES = Arrays.asList(
        "SUPERADMIN",
        "PHARMACY_OWNER",
        "BRANCH_MANAGER",
        "FINANCE_OFFICER"
    );

    public static boolean canPostAdjustment(String role) {
        if (role == null) return false;
        return LEDGER_ADMIN_ROLES.contains(role.trim().toUpperCase());
    }

    public static boolean canReverseEntry(String role) {
        if (role == null) return false;
        return LEDGER_ADMIN_ROLES.contains(role.trim().toUpperCase());
    }

    public static double calculateRunningBalance(double previousBalance, double debit, double credit) {
        double newBal = previousBalance + debit - credit;
        return Math.max(0.0, newBal);
    }

    public static boolean reconcileParity(
        double openingBalance,
        double totalDebits,
        double totalCredits,
        double closingBalance
    ) {
        double expected = Math.max(0.0, openingBalance + totalDebits - totalCredits);
        return Math.abs(expected - closingBalance) < 0.001;
    }

    public static void validateAdjustment(String direction, double amount, String reason) {
        if (direction == null || (!direction.equalsIgnoreCase("DEBIT") && !direction.equalsIgnoreCase("CREDIT"))) {
            throw new IllegalArgumentException("Adjustment direction must be either 'DEBIT' or 'CREDIT'.");
        }
        if (amount <= 0.0) {
            throw new IllegalArgumentException("Adjustment amount must be strictly greater than 0.00 BDT.");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("A documented reason is required for balance adjustment.");
        }
    }
}
