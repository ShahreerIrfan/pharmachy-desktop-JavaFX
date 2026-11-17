package com.pharmacy.pos.customers;

/**
 * WalkInSalesClient.java
 * Architecture Decision: ADR-0262 (P262).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * Desktop POS helper for managing privacy-preserving anonymous walk-in counter sales:
 * - Minimal identifier enforcement (no forced phone or personal details).
 * - Immediate blocking of credit/due payment for unattached/anonymous customers.
 * - Anonymous receipt generation with zero PII leakage.
 */
public class WalkInSalesClient {

    public static final String DEFAULT_WALK_IN_NAME = "Walk-in Customer";
    public static final String DEFAULT_WALK_IN_NAME_BN = "ওয়াক-ইন ক্রেতা";

    public static boolean isWalkInCustomer(String customerId) {
        return customerId == null || customerId.trim().isEmpty() || "WALK_IN".equalsIgnoreCase(customerId.trim());
    }

    public static boolean canTenderCredit(String customerId) {
        // Walk-in anonymous customers CANNOT buy on credit/due
        return !isWalkInCustomer(customerId);
    }

    public static void validateWalkInTender(String customerId, String paymentMethod) {
        if (paymentMethod != null && "DUE".equalsIgnoreCase(paymentMethod.trim())) {
            if (isWalkInCustomer(customerId)) {
                throw new IllegalArgumentException(
                    "Credit/due sales are strictly prohibited for anonymous walk-in customers. An approved customer profile is required."
                );
            }
        }
    }

    public static String getCustomerDisplayName(String customerName, boolean isBangla) {
        if (customerName == null || customerName.trim().isEmpty()) {
            return isBangla ? DEFAULT_WALK_IN_NAME_BN : DEFAULT_WALK_IN_NAME;
        }
        return customerName.trim();
    }

    public static String formatReceiptBanner(
        String orderNumber,
        String customerName,
        boolean isAnonymous,
        boolean isBangla
    ) {
        String ord = orderNumber != null ? orderNumber : "SO-COUNTER";
        String name = getCustomerDisplayName(customerName, isBangla);

        if (isBangla) {
            return isAnonymous
                ? "ক্যাশ মেমো [" + ord + "] | সাধারণ ওয়াক-ইন বিক্রয় (ব্যক্তিগত তথ্য সংরক্ষিত)"
                : "ক্যাশ মেমো [" + ord + "] | গ্রাহক: " + name;
        }
        return isAnonymous
            ? "CASH MEMO [" + ord + "] | Anonymous Counter Sale (Privacy Preserved)"
            : "CASH MEMO [" + ord + "] | Customer: " + name;
    }
}

