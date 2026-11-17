package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P254: Inspection Evidence Client Desktop Tests")
class InspectionEvidenceClientTest {

    @Test
    @DisplayName("Role management and export clearance gates")
    void testRoleClearanceGates() {
        assertTrue(InspectionEvidenceClient.canManageEvidence("SUPERADMIN"));
        assertTrue(InspectionEvidenceClient.canManageEvidence("PHARMACY_OWNER"));
        assertTrue(InspectionEvidenceClient.canManageEvidence("BRANCH_MANAGER"));
        assertTrue(InspectionEvidenceClient.canManageEvidence("STAFF_PHARMACIST"));
        assertFalse(InspectionEvidenceClient.canManageEvidence("CASHIER"));

        assertTrue(InspectionEvidenceClient.canExportAuditPackage("PHARMACY_OWNER"));
        assertTrue(InspectionEvidenceClient.canExportAuditPackage("BRANCH_MANAGER"));
        assertFalse(InspectionEvidenceClient.canExportAuditPackage("STAFF_PHARMACIST"));
        assertFalse(InspectionEvidenceClient.canExportAuditPackage("CASHIER"));
    }

    @Test
    @DisplayName("Document access classification hierarchy and branch boundaries")
    void testDocumentAccessHierarchy() {
        // Owner has access everywhere
        assertTrue(InspectionEvidenceClient.canAccessDocument("PHARMACY_OWNER", "RESTRICTED", "br-1", "br-2"));

        // Manager has access in their own branch
        assertTrue(InspectionEvidenceClient.canAccessDocument("BRANCH_MANAGER", "RESTRICTED", "br-1", "br-1"));
        assertFalse(InspectionEvidenceClient.canAccessDocument("BRANCH_MANAGER", "RESTRICTED", "br-1", "br-2"));

        // Pharmacist can view CONFIDENTIAL in their branch, but not RESTRICTED
        assertTrue(InspectionEvidenceClient.canAccessDocument("STAFF_PHARMACIST", "CONFIDENTIAL", "br-1", "br-1"));
        assertFalse(InspectionEvidenceClient.canAccessDocument("STAFF_PHARMACIST", "RESTRICTED", "br-1", "br-1"));
        assertFalse(InspectionEvidenceClient.canAccessDocument("STAFF_PHARMACIST", "CONFIDENTIAL", "br-1", "br-2"));

        // Cashier can only view PUBLIC
        assertTrue(InspectionEvidenceClient.canAccessDocument("CASHIER", "PUBLIC", "br-1", "br-1"));
        assertFalse(InspectionEvidenceClient.canAccessDocument("CASHIER", "CONFIDENTIAL", "br-1", "br-1"));
        assertFalse(InspectionEvidenceClient.canAccessDocument("CASHIER", "RESTRICTED", "br-1", "br-1"));
    }

    @Test
    @DisplayName("Cold-chain temperature excursion logic (2°C - 8°C corridor)")
    void testColdChainExcursions() {
        // Compliant corridor
        assertFalse(InspectionEvidenceClient.isColdChainExcursion(3.5, 6.2));
        assertFalse(InspectionEvidenceClient.isColdChainExcursion(2.0, 8.0));

        // Sub-zero / freezing excursion (< 2°C)
        assertTrue(InspectionEvidenceClient.isColdChainExcursion(1.2, 5.0));

        // High temperature excursion (> 8°C)
        assertTrue(InspectionEvidenceClient.isColdChainExcursion(4.0, 9.8));
    }

    @Test
    @DisplayName("Preflight validation ensures minimum lengths, valid types, and past dates")
    void testPreflightValidation() {
        LocalDate today = LocalDate.now();
        String validHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        assertDoesNotThrow(() ->
            InspectionEvidenceClient.validatePreflight(
                "Daily Chiller Log",
                "REFRIGERATOR_TEMPERATURE_LOG",
                today,
                "/vault/chiller_log.pdf",
                validHash
            )
        );

        // Short title
        assertThrows(IllegalArgumentException.class, () ->
            InspectionEvidenceClient.validatePreflight(
                "X",
                "REFRIGERATOR_TEMPERATURE_LOG",
                today,
                "/vault/doc.pdf",
                validHash
            )
        );

        // Future date
        assertThrows(IllegalArgumentException.class, () ->
            InspectionEvidenceClient.validatePreflight(
                "Training Proof",
                "STAFF_TRAINING_PROOF",
                today.plusDays(2),
                "/vault/doc.pdf",
                validHash
            )
        );

        // Invalid sha256
        assertThrows(IllegalArgumentException.class, () ->
            InspectionEvidenceClient.validatePreflight(
                "Training Proof",
                "STAFF_TRAINING_PROOF",
                today,
                "/vault/doc.pdf",
                "short-hash"
            )
        );
    }

    @Test
    @DisplayName("Localization of evidence categories and audit package summary")
    void testLocalizationAndSummary() {
        assertEquals("রেফ্রিজারেটর তাপমাত্রা লগ (কোল্ড-চেইন)",
            InspectionEvidenceClient.getEvidenceTypeDisplay("REFRIGERATOR_TEMPERATURE_LOG", "bn"));
        assertEquals("Staff Training Proof / Certification",
            InspectionEvidenceClient.getEvidenceTypeDisplay("STAFF_TRAINING_PROOF", "en"));

        String enSummary = InspectionEvidenceClient.formatAuditPackageSummary(
            15, 2, new BigDecimal("12500.50"), "en"
        );
        assertTrue(enSummary.contains("15 records"));
        assertTrue(enSummary.contains("2 cold-chain excursions"));
        assertTrue(enSummary.contains("12500.50"));

        String bnSummary = InspectionEvidenceClient.formatAuditPackageSummary(
            15, 2, new BigDecimal("12500.50"), "bn"
        );
        assertTrue(bnSummary.contains("১৫টি".replaceAll("১৫", "15")) || bnSummary.contains("15"));
        assertTrue(bnSummary.contains("কোল্ড-চেইন"));
    }
}

