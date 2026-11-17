package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ComplianceAuditExportClientTest.java
 * Unit tests for P258 ComplianceAuditExportClient.
 */
public class ComplianceAuditExportClientTest {

    @Test
    @DisplayName("Pharmacist and managers can export compliance audit; cashier is barred")
    void testRoleAuthorization() {
        assertTrue(ComplianceAuditExportClient.canExportCompliancePackage("STAFF_PHARMACIST"));
        assertTrue(ComplianceAuditExportClient.canExportCompliancePackage("BRANCH_MANAGER"));
        assertTrue(ComplianceAuditExportClient.canExportCompliancePackage("PHARMACY_OWNER"));
        assertTrue(ComplianceAuditExportClient.canExportCompliancePackage("SUPERADMIN"));

        assertFalse(ComplianceAuditExportClient.canExportCompliancePackage("CASHIER"));
        assertFalse(ComplianceAuditExportClient.canExportCompliancePackage(null));
    }

    @Test
    @DisplayName("Patient privacy redaction properly masks names and phone numbers")
    void testPatientPrivacyRedaction() {
        assertEquals("M*** R***", ComplianceAuditExportClient.maskPatientName("Mohammad Rahim"));
        assertEquals("K***", ComplianceAuditExportClient.maskPatientName("Kamal"));
        assertEquals("P-ANONYMOUS", ComplianceAuditExportClient.maskPatientName(null));

        assertEquals("+88017****5678", ComplianceAuditExportClient.maskPhoneNumber("+8801712345678"));
        assertEquals("N/A", ComplianceAuditExportClient.maskPhoneNumber(null));
    }

    @Test
    @DisplayName("Date range validation correctly enforces start <= end")
    void testDateRangeValidation() {
        assertDoesNotThrow(() -> ComplianceAuditExportClient.validateDateRange("2026-01-01", "2026-01-31"));
        assertThrows(IllegalArgumentException.class, () -> ComplianceAuditExportClient.validateDateRange("2026-02-01", "2026-01-01"));
        assertThrows(IllegalArgumentException.class, () -> ComplianceAuditExportClient.validateDateRange(null, "2026-01-01"));
    }

    @Test
    @DisplayName("Manifest summary formatting generates clean English and Bengali outputs")
    void testManifestSummaryFormatting() {
        String en = ComplianceAuditExportClient.formatManifestSummary("cae-9988", "ANTIBIOTIC_STEWARDSHIP", 45, "a1b2c3d4e5f6", false);
        assertTrue(en.contains("DGDA Audit Package"));
        assertTrue(en.contains("Records: 45"));

        String bn = ComplianceAuditExportClient.formatManifestSummary("cae-9988", "ANTIBIOTIC_STEWARDSHIP", 45, "a1b2c3d4e5f6", true);
        assertTrue(bn.contains("ডিজিডিএ অডিট প্যাকেজ"));
        assertTrue(bn.contains("রেকর্ড: 45 টি"));
    }
}
