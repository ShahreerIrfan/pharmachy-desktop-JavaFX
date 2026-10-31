package com.pharmacy.pos.security;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * PosUserIdentityService
 * Architecture Decision: ADR-0042
 * Manages desktop POS user session identity, role boundaries, and Bangladesh phone format validation.
 */
public class PosUserIdentityService {

    private static final Pattern BD_PHONE_PATTERN = Pattern.compile("^\\+8801[3-9]\\d{8}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");

    public static final class PosUserSession {
        public final String userId;
        public final String tenantId;
        public final String branchId;
        public final String email;
        public final String phoneNumber;
        public final String fullName;
        public final String role;
        public final String activationStatus;
        public final boolean isArchived;

        public PosUserSession(
            String userId,
            String tenantId,
            String branchId,
            String email,
            String phoneNumber,
            String fullName,
            String role,
            String activationStatus,
            boolean isArchived
        ) {
            this.userId = Objects.requireNonNull(userId, "userId required");
            this.tenantId = Objects.requireNonNull(tenantId, "tenantId required");
            this.branchId = branchId != null ? branchId : "";
            this.email = Objects.requireNonNull(email, "email required");
            this.phoneNumber = Objects.requireNonNull(phoneNumber, "phoneNumber required");
            this.fullName = Objects.requireNonNull(fullName, "fullName required");
            this.role = Objects.requireNonNull(role, "role required");
            this.activationStatus = Objects.requireNonNull(activationStatus, "activationStatus required");
            this.isArchived = isArchived;
        }

        public boolean canPerformCheckout() {
            return !isArchived && "ACTIVE".equals(activationStatus) &&
                ("CASHIER".equals(role) || "STAFF_PHARMACIST".equals(role) || "BRANCH_MANAGER".equals(role) || "PHARMACY_OWNER".equals(role));
        }

        public boolean canManageStaff() {
            return !isArchived && "ACTIVE".equals(activationStatus) &&
                ("PHARMACY_OWNER".equals(role) || "ADMIN".equals(role) || "SUPERADMIN".equals(role));
        }
    }

    /**
     * Normalizes a Bangladesh mobile number to E.164 standard.
     */
    public static String normalizeBdPhone(String rawPhone) {
        if (rawPhone == null || rawPhone.trim().isEmpty()) {
            throw new IllegalArgumentException("Phone number cannot be empty.");
        }
        String cleaned = rawPhone.replaceAll("[\\s\\-\\(\\)]", "").trim();
        if (cleaned.startsWith("+880")) {
            // Valid prefix
        } else if (cleaned.startsWith("880")) {
            cleaned = "+" + cleaned;
        } else if (cleaned.startsWith("01")) {
            cleaned = "+88" + cleaned;
        } else if (cleaned.startsWith("1")) {
            cleaned = "+880" + cleaned;
        } else {
            throw new IllegalArgumentException("Invalid Bangladesh phone prefix: " + rawPhone);
        }

        if (!BD_PHONE_PATTERN.matcher(cleaned).matches()) {
            throw new IllegalArgumentException("Invalid Bangladesh mobile number format: " + cleaned);
        }
        return cleaned;
    }

    /**
     * Validates an email address.
     */
    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email.trim()).matches();
    }
}
