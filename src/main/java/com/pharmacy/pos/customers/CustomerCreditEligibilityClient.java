package com.pharmacy.pos.customers;

import java.util.Arrays;
import java.util.List;

/**
 * CustomerCreditEligibilityClient.java
 * Architecture Decision: ADR-0264 (P264).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * Desktop POS helper for evaluating and enforcing customer credit eligibility:
 * - Branch and tenant credit ceiling hard controls.
 * - Customer credit approval and status verification.
 * - Overdue dues aging enforcement.
 * - Controlled/narcotics credit sale prohibition.
 * - RBAC rules for credit policy configuration.
 */
public class CustomerCreditEligibilityClient {

    public static final List<String> POLICY_ADMIN_ROLES = Arrays.asList(
        "SUPERADMIN",
        "PHARMACY_OWNER",
        "BRANCH_MANAGER",
        "FINANCE_OFFICER"
    );

    public static final List<String> APPROVED_CREDIT_STATUSES = Arrays.asList(
        "APPROVED"
    );

    public static boolean canConfigurePolicy(String role) {
        if (role == null) return false;
        return POLICY_ADMIN_ROLES.contains(role.trim().toUpperCase());
    }

    public static boolean isCustomerApproved(String creditStatus, boolean isCreditApproved) {
        if (!isCreditApproved || creditStatus == null) return false;
        return APPROVED_CREDIT_STATUSES.contains(creditStatus.trim().toUpperCase());
    }

    public static double calculateProjectedDue(double currentDue, double additionalDue) {
        return Math.max(0.0, currentDue + additionalDue);
    }

    public static boolean isWithinCustomerLimit(double currentDue, double additionalDue, double creditLimit) {
        if (creditLimit <= 0.0) return false;
        return calculateProjectedDue(currentDue, additionalDue) <= creditLimit;
    }

    public static boolean isWithinBranchCeiling(double currentBranchExposure, double additionalDue, double branchCeiling) {
        if (branchCeiling <= 0.0) return false;
        return (currentBranchExposure + additionalDue) <= branchCeiling;
    }

    public static void validateEligibilityCriteria(
        boolean isApproved,
        String creditStatus,
        double currentDue,
        double additionalDue,
        double creditLimit,
        double branchExposure,
        double branchCeiling,
        boolean hasOverdueDues,
        boolean hasNarcotics,
        boolean allowNarcoticsCredit
    ) {
        if (!isApproved || !"APPROVED".equalsIgnoreCase(creditStatus)) {
            throw new IllegalStateException("Customer is not approved for credit sales.");
        }
        if (hasOverdueDues) {
            throw new IllegalStateException("Customer has overdue unpaid dues past credit terms.");
        }
        if (!isWithinCustomerLimit(currentDue, additionalDue, creditLimit)) {
            throw new IllegalStateException("Requested credit exceeds customer approved limit.");
        }
        if (!isWithinBranchCeiling(branchExposure, additionalDue, branchCeiling)) {
            throw new IllegalStateException("Sale would exceed branch credit exposure ceiling.");
        }
        if (hasNarcotics && !allowNarcoticsCredit) {
            throw new IllegalStateException("Controlled/narcotic medicines cannot be dispensed on credit.");
        }
    }
}
