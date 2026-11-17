package com.pharmacy.pos.customers;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

/**
 * CustomerPurchaseHistoryClient.java
 * Architecture Decision: ADR-0263 (P263).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * Desktop POS client helper for viewing customer purchase history:
 * - Query parameter construction with pagination, event_type, date filters.
 * - Client-side validation for dates, page bounds, and event filters.
 * - Role-based PHI / PII privacy masking (cashier vs licensed pharmacist/manager).
 * - Net spend calculation and summary reconciliation.
 */
public class CustomerPurchaseHistoryClient {

    public static final List<String> PRIVILEGED_ROLES = Arrays.asList(
        "SUPERADMIN",
        "PHARMACY_OWNER",
        "BRANCH_MANAGER",
        "STAFF_PHARMACIST"
    );

    public static final List<String> VALID_EVENT_TYPES = Arrays.asList(
        "ALL",
        "SALE",
        "RETURN",
        "COLLECTION"
    );

    public static boolean isPrivilegedRole(String role) {
        if (role == null) return false;
        return PRIVILEGED_ROLES.contains(role.trim().toUpperCase());
    }

    public static String buildHistoryUri(
        String customerId,
        int page,
        int pageSize,
        String eventType,
        String startDate,
        String endDate,
        String branchId
    ) {
        if (customerId == null || customerId.trim().isEmpty()) {
            throw new IllegalArgumentException("Customer ID is required.");
        }
        if (page < 1) {
            throw new IllegalArgumentException("Page must be >= 1.");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100.");
        }

        StringBuilder uri = new StringBuilder("/api/v2/pos/customers/")
            .append(customerId.trim())
            .append("/purchase-history/?page=")
            .append(page)
            .append("&page_size=")
            .append(pageSize);

        if (eventType != null && !eventType.trim().isEmpty()) {
            String type = eventType.trim().toUpperCase();
            if (!VALID_EVENT_TYPES.contains(type)) {
                throw new IllegalArgumentException("Invalid event_type: " + eventType);
            }
            uri.append("&event_type=").append(type);
        }

        if (startDate != null && !startDate.trim().isEmpty()) {
            validateDate(startDate.trim());
            uri.append("&start_date=").append(startDate.trim());
        }

        if (endDate != null && !endDate.trim().isEmpty()) {
            validateDate(endDate.trim());
            uri.append("&end_date=").append(endDate.trim());
        }

        if (branchId != null && !branchId.trim().isEmpty()) {
            uri.append("&branch=").append(branchId.trim());
        }

        return uri.toString();
    }

    public static void validateDate(String dateStr) {
        try {
            LocalDate.parse(dateStr);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format. Expected YYYY-MM-DD: " + dateStr);
        }
    }

    public static String maskPhoneNumber(String phone, String role) {
        if (isPrivilegedRole(role)) {
            return phone != null ? phone : "";
        }
        if (phone == null || phone.trim().isEmpty()) {
            return "";
        }
        String clean = phone.trim();
        if (clean.length() <= 6) {
            return "******";
        }
        if (clean.startsWith("+880")) {
            int middleCount = Math.max(2, clean.length() - 10);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < middleCount; i++) sb.append("*");
            return clean.substring(0, 6) + sb.toString() + clean.substring(clean.length() - 4);
        } else if (clean.startsWith("01")) {
            int middleCount = Math.max(2, clean.length() - 7);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < middleCount; i++) sb.append("*");
            return clean.substring(0, 3) + sb.toString() + clean.substring(clean.length() - 4);
        } else {
            return clean.substring(0, 2) + "****" + clean.substring(clean.length() - 2);
        }
    }

    public static double calculateNetSpend(double totalPurchases, double totalReturns) {
        return Math.max(0.0, totalPurchases - totalReturns);
    }
}

