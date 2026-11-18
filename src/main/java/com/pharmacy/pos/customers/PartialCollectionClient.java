package com.pharmacy.pos.customers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;

/**
 * PartialCollectionClient.java
 * Architecture Decision: ADR-0266 (P266).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * Desktop POS helper for applying partial collections against original invoices:
 * - Strict overpayment protection (amount <= remainingDue)
 * - Duplicate settlement protection
 * - Digital tender validation
 * - Reverse collection RBAC verification
 */
public class PartialCollectionClient {

    public static final List<String> COLLECTION_ADMIN_ROLES = Arrays.asList(
        "SUPERADMIN",
        "PHARMACY_OWNER",
        "BRANCH_MANAGER",
        "FINANCE_OFFICER"
    );

    public static final List<String> DIGITAL_TENDERS = Arrays.asList(
        "BKASH",
        "NAGAD",
        "ROCKET",
        "UPAY",
        "CARD",
        "BANK_TRANSFER"
    );

    public static boolean canReverseCollection(String role) {
        if (role == null) return false;
        return COLLECTION_ADMIN_ROLES.contains(role.trim().toUpperCase());
    }

    public static BigDecimal roundMoney(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    public static void validatePartialCollection(
        double amount,
        double remainingDue,
        String tenderType,
        String gatewayRef
    ) {
        if (remainingDue <= 0.0) {
            throw new IllegalStateException("Invoice is already fully settled or has 0.00 BDT remaining due.");
        }
        if (amount <= 0.0) {
            throw new IllegalArgumentException("Collection amount must be strictly greater than 0.00 BDT.");
        }
        if (amount > remainingDue + 0.0001) {
            throw new IllegalArgumentException(
                String.format("Collection amount (%.2f BDT) exceeds remaining invoice due (%.2f BDT). Overpayments are prohibited.",
                    amount, remainingDue)
            );
        }
        String cleanTender = tenderType != null ? tenderType.trim().toUpperCase() : "CASH";
        if (cleanTender.equals("DUE")) {
            throw new IllegalArgumentException("Cannot collect customer due balance using DUE tender.");
        }
        if (DIGITAL_TENDERS.contains(cleanTender)) {
            if (gatewayRef == null || gatewayRef.trim().length() < 4) {
                throw new IllegalArgumentException(cleanTender + " collection requires a valid gateway reference (min 4 chars).");
            }
        }
    }

    public static double calculateRemainingDue(double currentRemaining, double amountCollected) {
        double rem = currentRemaining - amountCollected;
        return Math.max(0.0, Math.round(rem * 100.0) / 100.0);
    }

    public static boolean isSettled(double currentRemaining, double amountCollected) {
        return calculateRemainingDue(currentRemaining, amountCollected) <= 0.001;
    }
}
