package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P255: Incident and Correction Client Desktop Tests")
class IncidentCorrectionClientTest {

    @Test
    @DisplayName("Reporting, investigation, and closure authorization gates")
    void testAuthorizationGates() {
        // Any staff can report
        assertTrue(IncidentCorrectionClient.canReportIncident("CASHIER"));
        assertTrue(IncidentCorrectionClient.canReportIncident("STAFF_PHARMACIST"));
        assertTrue(IncidentCorrectionClient.canReportIncident("BRANCH_MANAGER"));

        // Cashiers cannot investigate
        assertFalse(IncidentCorrectionClient.canConductInvestigation("CASHIER"));
        assertTrue(IncidentCorrectionClient.canConductInvestigation("STAFF_PHARMACIST"));
        assertTrue(IncidentCorrectionClient.canConductInvestigation("BRANCH_MANAGER"));

        // Strict closure gate: Cashier barred from closing incidents
        assertFalse(IncidentCorrectionClient.canApproveClosure("CASHIER", "br-1", "br-1"));

        // Pharmacist and Manager can approve closures in their branch
        assertTrue(IncidentCorrectionClient.canApproveClosure("STAFF_PHARMACIST", "br-1", "br-1"));
        assertFalse(IncidentCorrectionClient.canApproveClosure("STAFF_PHARMACIST", "br-1", "br-2"));
        assertTrue(IncidentCorrectionClient.canApproveClosure("BRANCH_MANAGER", "br-1", "br-1"));

        // Owner can close everywhere
        assertTrue(IncidentCorrectionClient.canApproveClosure("PHARMACY_OWNER", "br-1", "br-2"));
    }

    @Test
    @DisplayName("DGDA regulatory notification triggers for recalls and critical severities")
    void testDgdaNotificationTriggers() {
        // Critical severity always triggers notification
        assertTrue(IncidentCorrectionClient.isDgdaNotificationRequired("INCORRECT_DISPENSING", "CRITICAL"));
        assertTrue(IncidentCorrectionClient.isDgdaNotificationRequired("SUSPECTED_DAMAGE", "CRITICAL"));

        // Product recalls always trigger notification
        assertTrue(IncidentCorrectionClient.isDgdaNotificationRequired("PRODUCT_RECALL", "LOW"));
        assertTrue(IncidentCorrectionClient.isDgdaNotificationRequired("PRODUCT_RECALL", "MEDIUM"));

        // Adverse reaction with HIGH severity triggers notification
        assertTrue(IncidentCorrectionClient.isDgdaNotificationRequired("ADVERSE_DRUG_REACTION", "HIGH"));

        // Minor discrepancy does not trigger notification
        assertFalse(IncidentCorrectionClient.isDgdaNotificationRequired("INVENTORY_DISCREPANCY", "LOW"));
    }

    @Test
    @DisplayName("Mandatory Root Cause and CAPA validation for High and Critical incidents")
    void testClosureCapaValidation() {
        // Low and Medium can close without strict CAPA
        assertDoesNotThrow(() ->
            IncidentCorrectionClient.validateClosureRequirements("LOW", null, null)
        );

        // High without CAPA or root cause throws exception
        assertThrows(IllegalStateException.class, () ->
            IncidentCorrectionClient.validateClosureRequirements("HIGH", "", "Plan")
        );
        assertThrows(IllegalStateException.class, () ->
            IncidentCorrectionClient.validateClosureRequirements("HIGH", "HUMAN_ERROR", "")
        );

        // High with root cause and CAPA succeeds
        assertDoesNotThrow(() ->
            IncidentCorrectionClient.validateClosureRequirements(
                "HIGH", "HUMAN_ERROR", "Retrain cashier on LASA barcode scanning"
            )
        );
    }

    @Test
    @DisplayName("Localization and branch summary formatting")
    void testLocalizationAndFormatting() {
        assertEquals("ভুল ওষুধ প্রদান / নিয়ার-মিস",
            IncidentCorrectionClient.getIncidentTypeDisplay("INCORRECT_DISPENSING", "bn"));
        assertEquals("Incorrect Dispensing / Near Miss",
            IncidentCorrectionClient.getIncidentTypeDisplay("INCORRECT_DISPENSING", "en"));

        String summaryEn = IncidentCorrectionClient.formatBranchSummary(
            8, 1, new BigDecimal("18500.00"), "en"
        );
        assertTrue(summaryEn.contains("8 total"));
        assertTrue(summaryEn.contains("1 critical alerts"));
        assertTrue(summaryEn.contains("18500.00"));

        String summaryBn = IncidentCorrectionClient.formatBranchSummary(
            8, 1, new BigDecimal("18500.00"), "bn"
        );
        assertTrue(summaryBn.contains("8"));
        assertTrue(summaryBn.contains("মারাত্মক/জরুরি"));
    }
}

