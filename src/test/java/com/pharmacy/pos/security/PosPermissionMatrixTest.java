package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0059: POS Permission Matrix & Least Privilege Tests")
class PosPermissionMatrixTest {

    @Test
    @DisplayName("Cashier should be allowed to perform checkout and search medicines")
    void testCashierAllowedOperations() {
        PosPermissionMatrix.PermissionResult checkoutResult = PosPermissionMatrix.evaluateAccess(
            "CASHIER",
            "POST",
            "/api/v2/invoices/",
            "branch-dhk-01",
            "branch-dhk-01",
            "BASIC"
        );
        assertTrue(checkoutResult.isAllowed());
        assertNull(checkoutResult.errorCode());

        PosPermissionMatrix.PermissionResult medicineReadResult = PosPermissionMatrix.evaluateAccess(
            "CASHIER",
            "GET",
            "/api/v2/medicines/",
            "branch-dhk-01",
            "branch-dhk-01",
            "BASIC"
        );
        assertTrue(medicineReadResult.isAllowed());
    }

    @Test
    @DisplayName("Cashier must be blocked from deleting resources and accessing audit logs")
    void testCashierDeniedAdministrativeOperations() {
        // DELETE attempt
        PosPermissionMatrix.PermissionResult deleteResult = PosPermissionMatrix.evaluateAccess(
            "CASHIER",
            "DELETE",
            "/api/v2/branches/branch-01/",
            "branch-dhk-01",
            "branch-dhk-01",
            "BASIC"
        );
        assertFalse(deleteResult.isAllowed());
        assertEquals("ROLE_PERMISSION_DENIED", deleteResult.errorCode());

        // Audit log read attempt
        PosPermissionMatrix.PermissionResult auditResult = PosPermissionMatrix.evaluateAccess(
            "CASHIER",
            "GET",
            "/api/v2/audit-logs/",
            "branch-dhk-01",
            "branch-dhk-01",
            "BASIC"
        );
        assertFalse(auditResult.isAllowed());
        assertEquals("ROLE_PERMISSION_DENIED", auditResult.errorCode());
    }

    @Test
    @DisplayName("Destructive DELETE on invoices and batches must trigger absolute block across all users")
    void testAbsoluteSystemBlockOnPostedRecords() {
        assertTrue(PosPermissionMatrix.isAbsoluteBlocked("DELETE", "/api/v2/invoices/"));
        assertTrue(PosPermissionMatrix.isAbsoluteBlocked("DELETE", "/api/v2/batches/"));

        PosPermissionMatrix.PermissionResult blockResult = PosPermissionMatrix.evaluateAccess(
            "PHARMACY_OWNER",
            "DELETE",
            "/api/v2/invoices/inv-101/",
            "branch-01",
            "branch-01",
            "PRO"
        );
        assertFalse(blockResult.isAllowed());
        assertEquals("ABSOLUTE_SYSTEM_BLOCK", blockResult.errorCode());
    }

    @Test
    @DisplayName("Cross-branch operation must be denied for branch-bound roles")
    void testCrossBranchBoundaryEnforcement() {
        PosPermissionMatrix.PermissionResult branchMismatchResult = PosPermissionMatrix.evaluateAccess(
            "CASHIER",
            "POST",
            "/api/v2/invoices/",
            "branch-dhaka-01",
            "branch-chittagong-02",
            "BASIC"
        );
        assertFalse(branchMismatchResult.isAllowed());
        assertEquals("FORBIDDEN_BRANCH_BOUNDARY", branchMismatchResult.errorCode());
    }

    @Test
    @DisplayName("Commercial purchase prices must be masked for Cashier but visible to Pharmacy Owner")
    void testFieldMaskingForCostPricesAndPii() {
        String rawCost = "142.50";
        String maskedForCashier = PosPermissionMatrix.maskFieldValue("cost_price", rawCost, "CASHIER");
        assertEquals("***REDACTED***", maskedForCashier);

        String visibleForOwner = PosPermissionMatrix.maskFieldValue("cost_price", rawCost, "PHARMACY_OWNER");
        assertEquals(rawCost, visibleForOwner);

        // Customer phone partial masking
        String rawPhone = "+8801712345678";
        String maskedPhone = PosPermissionMatrix.maskFieldValue("phone_number", rawPhone, "CASHIER");
        assertEquals("+88017****5678", maskedPhone);

        String ownerPhone = PosPermissionMatrix.maskFieldValue("phone_number", rawPhone, "PHARMACY_OWNER");
        assertEquals(rawPhone, ownerPhone);
    }
}
