package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for P244 PrescriptionValidityClient (JavaFX Desktop).
 */
public class PrescriptionValidityClientTest {

    @Test
    public void testValidPrescriptionPreflight() {
        LocalDate today = LocalDate.of(2026, 11, 17);
        PrescriptionValidityClient.ValidityReportDto report = PrescriptionValidityClient.evaluateClientPreflight(
                "rx-test-001",
                "Dr. Mahfuzur Rahman",
                "A-33214",
                today.minusDays(3),
                today.plusDays(25),
                true,
                true,
                today
        );

        assertTrue(report.isValid());
        assertEquals(PrescriptionValidityClient.ValidityStatus.VALID, report.getValidityStatus());
        assertFalse(report.isExpired());
        assertTrue(report.canDispense());
        assertTrue(report.getErrors().isEmpty());
    }

    @Test
    public void testExpiredPrescriptionDetection() {
        LocalDate today = LocalDate.of(2026, 11, 17);
        PrescriptionValidityClient.ValidityReportDto report = PrescriptionValidityClient.evaluateClientPreflight(
                "rx-expired-001",
                "Dr. Mahfuzur Rahman",
                "A-33214",
                today.minusDays(40),
                today.minusDays(5),
                true,
                false,
                today
        );

        assertFalse(report.isValid());
        assertEquals(PrescriptionValidityClient.ValidityStatus.EXPIRED, report.getValidityStatus());
        assertTrue(report.isExpired());
        assertFalse(report.canDispense());
        assertEquals("মেয়াদোত্তীর্ণ", report.getValidityStatus().getLocalized("bn"));
    }

    @Test
    public void testMissingSupportingDocumentForAntibiotic() {
        LocalDate today = LocalDate.of(2026, 11, 17);
        PrescriptionValidityClient.ValidityReportDto report = PrescriptionValidityClient.evaluateClientPreflight(
                "rx-no-doc-001",
                "Dr. Farhana Yasmin",
                "BMDC-55123",
                today.minusDays(1),
                today.plusDays(6),
                false, // No attachment
                true,  // Contains antibiotic
                today
        );

        assertFalse(report.isValid());
        assertEquals(PrescriptionValidityClient.ValidityStatus.MISSING_DOCUMENTS, report.getValidityStatus());
        assertTrue(report.requiresDocument());
        assertFalse(report.hasValidAttachment());
    }
}

