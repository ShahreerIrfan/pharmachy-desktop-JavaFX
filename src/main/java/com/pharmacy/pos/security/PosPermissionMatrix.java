package com.pharmacy.pos.security;

import java.util.*;

/**
 * PosPermissionMatrix
 * Architecture Decision: ADR-0005 & ADR-0059
 * Enforces least-privilege role boundaries, branch scoping, absolute system blocks,
 * and sensitive field masking on JavaFX POS desktop client.
 */
public final class PosPermissionMatrix {

    private static final Set<String> ABSOLUTE_BLOCK_PATHS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("/api/v2/invoices/", "/api/v2/batches/", "/api/v2/prescriptions/", "/api/v2/audit-logs/"))
    );

    private static final Set<String> COST_PRICE_EXEMPT_ROLES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "SUPERADMIN"))
    );

    private static final Set<String> PII_EXEMPT_ROLES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("PHARMACY_OWNER", "BRANCH_MANAGER", "STAFF_PHARMACIST", "AUDITOR", "SUPERADMIN"))
    );

    public record PermissionResult(
        boolean isAllowed,
        String errorCode,
        String messageEn,
        String messageBn
    ) {
        public static PermissionResult allow() {
            return new PermissionResult(true, null, "Access authorized.", "প্রবেশাধিকার অনুমোদিত।");
        }

        public static PermissionResult deny(String code, String en, String bn) {
            return new PermissionResult(false, code, en, bn);
        }
    }

    private PosPermissionMatrix() {}

    /**
     * Checks if an action is permanently blocked by system invariants (e.g. DELETE on financial records).
     */
    public static boolean isAbsoluteBlocked(String method, String path) {
        if ("DELETE".equalsIgnoreCase(method)) {
            String norm = path != null ? path.trim() : "";
            for (String blocked : ABSOLUTE_BLOCK_PATHS) {
                if (norm.startsWith(blocked) || norm.contains(blocked.replace("/api/v2/", ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Evaluates permission for a desktop user action.
     */
    public static PermissionResult evaluateAccess(
        String role,
        String method,
        String path,
        String userBranchId,
        String targetBranchId,
        String planTier
    ) {
        if (role == null || role.isBlank()) {
            return PermissionResult.deny(
                "UNAUTHENTICATED",
                "User role is missing or unauthenticated.",
                "ব্যবহারকারীর রোল পাওয়া যায়নি অথবা অনুমোদনহীন।"
            );
        }

        String normMethod = method != null ? method.toUpperCase().trim() : "GET";
        String normPath = path != null ? path.trim() : "";

        // 1. Absolute block check
        if (isAbsoluteBlocked(normMethod, normPath)) {
            return PermissionResult.deny(
                "ABSOLUTE_SYSTEM_BLOCK",
                "Destructive deletion of posted records is strictly prohibited.",
                "পোস্ট করা রেকর্ড ডিলিট করা সম্পূর্ণ নিষিদ্ধ।"
            );
        }

        // 2. Cashier least-privilege boundary
        if ("CASHIER".equalsIgnoreCase(role)) {
            if ("DELETE".equals(normMethod)) {
                return PermissionResult.deny(
                    "ROLE_PERMISSION_DENIED",
                    "Cashier role is not permitted to delete resources.",
                    "ক্যাশিয়ারের কোনো রিসোর্স ডিলিট করার অনুমতি নেই।"
                );
            }
            if (normPath.contains("audit-logs") || normPath.contains("secrets") || normPath.contains("subscriptions")) {
                return PermissionResult.deny(
                    "ROLE_PERMISSION_DENIED",
                    "Cashier is forbidden from administrative records.",
                    "ক্যাশিয়ারের প্রশাসনিক রেকর্ড দেখার অনুমতি নেই।"
                );
            }
        }

        // 3. Branch boundary check
        if ("CASHIER".equalsIgnoreCase(role) || "STAFF_PHARMACIST".equalsIgnoreCase(role)) {
            if (userBranchId != null && targetBranchId != null && !userBranchId.isBlank() && !targetBranchId.isBlank()) {
                if (!userBranchId.trim().equalsIgnoreCase(targetBranchId.trim())) {
                    return PermissionResult.deny(
                        "FORBIDDEN_BRANCH_BOUNDARY",
                        "Cross-branch access denied. User belongs to branch " + userBranchId,
                        "অন্য শাখার তথ্যে প্রবেশ নিষিদ্ধ। কর্মী শাখা " + userBranchId + "-এর অন্তর্ভুক্ত।"
                    );
                }
            }
        }

        // 4. Plan tier check
        if (normPath.contains("chain-wide") && !"PRO".equalsIgnoreCase(planTier)) {
            return PermissionResult.deny(
                "INSUFFICIENT_PLAN_TIER",
                "Chain-wide consolidated analytics requires PRO plan.",
                "চেইন-ওয়াইড কনসোলিডেটেড অ্যানালিটিক্সের জন্য প্রো প্ল্যান প্রয়োজন।"
            );
        }

        return PermissionResult.allow();
    }

    /**
     * Masks sensitive field values based on role privilege.
     */
    public static String maskFieldValue(String fieldName, String rawValue, String role) {
        if (rawValue == null) return null;
        String cleanRole = role != null ? role.toUpperCase().trim() : "";
        String cleanField = fieldName != null ? fieldName.toLowerCase().trim() : "";

        // Purchase cost prices
        if (cleanField.contains("cost_price") || cleanField.contains("purchase_price") || cleanField.contains("lot_cost")) {
            if (COST_PRICE_EXEMPT_ROLES.contains(cleanRole)) {
                return rawValue;
            }
            return "***REDACTED***";
        }

        // Customer phone number
        if (cleanField.contains("phone") || cleanField.contains("mobile")) {
            if (PII_EXEMPT_ROLES.contains(cleanRole)) {
                return rawValue;
            }
            if (rawValue.length() >= 11) {
                return rawValue.substring(0, 6) + "****" + rawValue.substring(rawValue.length() - 4);
            }
            return "***REDACTED***";
        }

        // Secret keys & credentials
        if (cleanField.contains("secret") || cleanField.contains("token") || cleanField.contains("password")) {
            if ("SUPERADMIN".equals(cleanRole) || "PHARMACY_OWNER".equals(cleanRole)) {
                return rawValue;
            }
            return "***REDACTED***";
        }

        return rawValue;
    }
}
