package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RegulatoryReferenceClientTest.java
 * Architecture Decision: ADR-0259 (P259).
 */
public class RegulatoryReferenceClientTest {

    @Test
    @DisplayName("Should enforce role permissions for managing and viewing regulatory references")
    public void testRolePermissions() {
        assertTrue(RegulatoryReferenceClient.canManageReference("SUPERADMIN"));
        assertTrue(RegulatoryReferenceClient.canManageReference("PHARMACY_OWNER"));
        assertTrue(RegulatoryReferenceClient.canManageReference("BRANCH_MANAGER"));
        assertTrue(RegulatoryReferenceClient.canManageReference("STAFF_PHARMACIST"));
        assertFalse(RegulatoryReferenceClient.canManageReference("CASHIER"));
        assertFalse(RegulatoryReferenceClient.canManageReference(null));

        assertTrue(RegulatoryReferenceClient.canViewReference("CASHIER"));
        assertFalse(RegulatoryReferenceClient.canViewReference("GUEST"));
    }

    @Test
    @DisplayName("Should validate reference input parameters")
    public void testValidation() {
        assertDoesNotThrow(() -> RegulatoryReferenceClient.validateReferenceData(
            "DGDA PMS Guideline 2024",
            "https://dgda.gov.bd/guidelines/pms-2024.pdf",
            "2024.1",
            "2024-01-01",
            "2025-01-01"
        ));

        // Short title
        assertThrows(IllegalArgumentException.class, () -> RegulatoryReferenceClient.validateReferenceData(
            "DG",
            "https://dgda.gov.bd/guidelines/pms-2024.pdf",
            "2024.1",
            "2024-01-01",
            "2025-01-01"
        ));

        // Invalid URI
        assertThrows(IllegalArgumentException.class, () -> RegulatoryReferenceClient.validateReferenceData(
            "DGDA PMS Guideline 2024",
            "invalid-url",
            "2024.1",
            "2024-01-01",
            "2025-01-01"
        ));

        // Invalid date order (effective > nextReview)
        assertThrows(IllegalArgumentException.class, () -> RegulatoryReferenceClient.validateReferenceData(
            "DGDA PMS Guideline 2024",
            "https://dgda.gov.bd/guidelines/pms-2024.pdf",
            "2024.1",
            "2025-06-01",
            "2025-01-01"
        ));
    }

    @Test
    @DisplayName("Should correctly evaluate overdue status")
    public void testOverdueEvaluation() {
        assertTrue(RegulatoryReferenceClient.isReviewOverdue("2024-10-01", "2024-11-01"));
        assertFalse(RegulatoryReferenceClient.isReviewOverdue("2025-01-01", "2024-11-01"));
    }

    @Test
    @DisplayName("Should format reference badges in English and Bengali")
    public void testFormatBadge() {
        String bnOverdue = RegulatoryReferenceClient.formatReferenceBadge(
            "reg-001", "DGDA_BANGLADESH", "v2.0", "ACTIVE", true, true
        );
        assertTrue(bnOverdue.contains("মেয়াদোত্তীর্ণ রিভিউ"));

        String enNormal = RegulatoryReferenceClient.formatReferenceBadge(
            "reg-001", "DGDA_BANGLADESH", "v2.0", "ACTIVE", false, false
        );
        assertTrue(enNormal.contains("[ACTIVE]"));
        assertTrue(enNormal.contains("DGDA_BANGLADESH"));
    }
}
