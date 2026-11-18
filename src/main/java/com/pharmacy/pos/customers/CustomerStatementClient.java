package com.pharmacy.pos.customers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

/**
 * CustomerStatementClient.java
 * Architecture Decision: ADR-0267 (P267).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * Desktop POS helper for generating and verifying customer statements:
 * - Opening balance + Charges - Payments == Closing balance parity.
 * - 4-tier due aging computation (Current, 31-60d, 61-90d, 91d+).
 * - Date range validation.
 */
public class CustomerStatementClient {

    public static final List<String> STATEMENT_READ_ROLES = Arrays.asList(
        "SUPERADMIN",
        "PHARMACY_OWNER",
        "BRANCH_MANAGER",
        "STAFF_PHARMACIST",
        "CASHIER",
        "FINANCE_OFFICER",
        "AUDITOR"
    );

    public static boolean canGenerateStatement(String role) {
        if (role == null) return false;
        return STATEMENT_READ_ROLES.contains(role.trim().toUpperCase());
    }

    public static boolean verifyStatementParity(
        double openingBalance,
        double totalCharges,
        double totalPayments,
        double closingBalance
    ) {
        double expected = Math.max(0.0, openingBalance + totalCharges - totalPayments);
        return Math.abs(expected - closingBalance) < 0.001;
    }

    public static double sumAgingBuckets(
        double current,
        double days31To60,
        double days61To90,
        double days91Plus
    ) {
        double sum = current + days31To60 + days61To90 + days91Plus;
        return BigDecimal.valueOf(sum).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public static void validateDateRange(String startDateStr, String endDateStr) {
        if (startDateStr == null || startDateStr.trim().isEmpty()) {
            throw new IllegalArgumentException("Start date cannot be empty.");
        }
        if (endDateStr == null || endDateStr.trim().isEmpty()) {
            throw new IllegalArgumentException("End date cannot be empty.");
        }
        try {
            LocalDate start = LocalDate.parse(startDateStr.trim());
            LocalDate end = LocalDate.parse(endDateStr.trim());
            if (start.isAfter(end)) {
                throw new IllegalArgumentException("Start date cannot be later than end date.");
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format. Expected YYYY-MM-DD.");
        }
    }
}
