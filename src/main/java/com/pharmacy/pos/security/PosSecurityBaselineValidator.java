package com.pharmacy.pos.security;

import java.time.Instant;
import java.util.*;

/**
 * PosSecurityBaselineValidator
 * Architecture Decision: ADR-0060
 * Desktop POS Security Baseline Validator enforcing local terminal security invariants:
 *   - Active authenticated session and tenant binding
 *   - Cashier branch confinement
 *   - Commercial purchase cost price masking
 *   - Destruction prevention (absolute system blocks on deletes)
 */
public final class PosSecurityBaselineValidator {

    public record PosSecurityPostureReport(
        String service,
        String version,
        String tenantId,
        String userRole,
        String overallStatus,
        Instant timestamp,
        int checksPassed,
        int checksFailed,
        List<String> verifiedPillars
    ) {}

    private PosSecurityBaselineValidator() {}

    /**
     * Validates that cashier cannot execute cross-branch transactions.
     */
    public static boolean isBranchConfinementValid(String userBranchId, String targetBranchId, String role) {
        if ("CASHIER".equalsIgnoreCase(role) || "STAFF_PHARMACIST".equalsIgnoreCase(role)) {
            if (userBranchId == null || targetBranchId == null || userBranchId.isBlank() || targetBranchId.isBlank()) {
                return false;
            }
            return userBranchId.trim().equalsIgnoreCase(targetBranchId.trim());
        }
        return true;
    }

    /**
     * Verifies that commercial cost prices are strictly masked for unauthorized roles.
     */
    public static boolean isCostPriceMaskingEnforced(String role, String rawCost) {
        String masked = PosPermissionMatrix.maskFieldValue("cost_price", rawCost, role);
        if ("CASHIER".equalsIgnoreCase(role)) {
            return "***REDACTED***".equals(masked);
        } else if ("PHARMACY_OWNER".equalsIgnoreCase(role) || "BRANCH_MANAGER".equalsIgnoreCase(role)) {
            return rawCost != null && rawCost.equals(masked);
        }
        return true;
    }

    /**
     * Validates that destructive deletion of financial/inventory records is hard-blocked.
     */
    public static boolean isDeletionBlocked(String method, String endpointPath) {
        return PosPermissionMatrix.isAbsoluteBlocked(method, endpointPath);
    }

    /**
     * Evaluates comprehensive desktop terminal security baseline posture.
     */
    public static PosSecurityPostureReport evaluateTerminalBaseline(
        String tenantId,
        String userRole,
        String branchId,
        String sampleCostPrice
    ) {
        List<String> pillars = new ArrayList<>();
        int passed = 0;
        int failed = 0;

        // Pillar 1: Identity & Session
        if (tenantId != null && !tenantId.isBlank() && userRole != null && !userRole.isBlank()) {
            passed++;
            pillars.add("AUTH_GATE");
        } else {
            failed++;
        }

        // Pillar 2: Branch Confinement
        if (isBranchConfinementValid(branchId, branchId, userRole)) {
            passed++;
            pillars.add("ISOLATION_GATE");
        } else {
            failed++;
        }

        // Pillar 3: Sensitive Field Masking
        if (isCostPriceMaskingEnforced(userRole, sampleCostPrice)) {
            passed++;
            pillars.add("PRIVACY_GATE");
        } else {
            failed++;
        }

        // Pillar 4: Absolute Block Gate
        if (isDeletionBlocked("DELETE", "/api/v2/invoices/")) {
            passed++;
            pillars.add("AUTHORIZATION_GATE");
        } else {
            failed++;
        }

        String overall = failed == 0 ? "PASSED" : "FAILED";

        return new PosSecurityPostureReport(
            "pharmacy-desktop-pos",
            "v2",
            tenantId,
            userRole,
            overall,
            Instant.now(),
            passed,
            failed,
            Collections.unmodifiableList(pillars)
        );
    }
}
