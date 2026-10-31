package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0060: Desktop POS Security Baseline Validator Tests")
class PosSecurityBaselineValidatorTest {

    @Test
    @DisplayName("Cashier branch confinement must strictly deny cross-branch execution")
    void testCashierBranchConfinement() {
        assertTrue(PosSecurityBaselineValidator.isBranchConfinementValid("branch-01", "branch-01", "CASHIER"));
        assertFalse(PosSecurityBaselineValidator.isBranchConfinementValid("branch-01", "branch-99", "CASHIER"));
        // Owner has chain-wide privilege
        assertTrue(PosSecurityBaselineValidator.isBranchConfinementValid("branch-01", "branch-99", "PHARMACY_OWNER"));
    }

    @Test
    @DisplayName("Cost price masking must redact for Cashier and remain intact for Owner")
    void testCostPriceMaskingValidation() {
        assertTrue(PosSecurityBaselineValidator.isCostPriceMaskingEnforced("CASHIER", "125.00"));
        assertTrue(PosSecurityBaselineValidator.isCostPriceMaskingEnforced("PHARMACY_OWNER", "125.00"));
    }

    @Test
    @DisplayName("Absolute system block on invoice deletion must be recognized on desktop POS")
    void testAbsoluteDeletionBlock() {
        assertTrue(PosSecurityBaselineValidator.isDeletionBlocked("DELETE", "/api/v2/invoices/"));
        assertTrue(PosSecurityBaselineValidator.isDeletionBlocked("DELETE", "/api/v2/batches/"));
    }

    @Test
    @DisplayName("Evaluate terminal baseline report produces PASSED posture for valid session")
    void testEvaluateTerminalBaseline() {
        PosSecurityBaselineValidator.PosSecurityPostureReport report =
            PosSecurityBaselineValidator.evaluateTerminalBaseline("tenant-dhk-01", "CASHIER", "branch-01", "150.00");

        assertEquals("pharmacy-desktop-pos", report.service());
        assertEquals("PASSED", report.overallStatus());
        assertEquals(0, report.checksFailed());
        assertTrue(report.checksPassed() >= 4);
        assertTrue(report.verifiedPillars().contains("AUTH_GATE"));
        assertTrue(report.verifiedPillars().contains("PRIVACY_GATE"));
        assertTrue(report.verifiedPillars().contains("AUTHORIZATION_GATE"));
    }
}
