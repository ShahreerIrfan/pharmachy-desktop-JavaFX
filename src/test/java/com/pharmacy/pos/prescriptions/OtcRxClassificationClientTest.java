package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P247: Unit Tests for OTC & Prescription Status Versioning Client (JavaFX Desktop).
 * Architecture Decision: ADR-0247 (P247).
 */
public class OtcRxClassificationClientTest {

    @Test
    @DisplayName("OTC does not require prescription; POM, narcotics, antimicrobials require Rx")
    void testClassificationTypeRequirements() {
        assertFalse(OtcRxClassificationClient.ClassificationType.OTC.requiresPrescription());
        assertFalse(OtcRxClassificationClient.ClassificationType.OTC.isDispensingBlocked());

        assertTrue(OtcRxClassificationClient.ClassificationType.PRESCRIPTION_ONLY.requiresPrescription());
        assertFalse(OtcRxClassificationClient.ClassificationType.PRESCRIPTION_ONLY.isDispensingBlocked());

        assertTrue(OtcRxClassificationClient.ClassificationType.NARCOTIC_SCHEDULED.requiresPrescription());
        assertTrue(OtcRxClassificationClient.ClassificationType.ANTIMICROBIAL_ACCESS.requiresPrescription());
        assertTrue(OtcRxClassificationClient.ClassificationType.ANTIMICROBIAL_WATCH.requiresPrescription());
        assertTrue(OtcRxClassificationClient.ClassificationType.ANTIMICROBIAL_RESERVE.requiresPrescription());

        assertTrue(OtcRxClassificationClient.ClassificationType.RECALLED.isDispensingBlocked());
        assertTrue(OtcRxClassificationClient.ClassificationType.BANNED.isDispensingBlocked());
    }

    @Test
    @DisplayName("EN/BN Localized labels render correctly")
    void testLocalizedLabels() {
        assertEquals(
                "প্রেসক্রিপশন ব্যতীত বিক্রয়যোগ্য (OTC)",
                OtcRxClassificationClient.ClassificationType.OTC.getLocalized("bn")
        );
        assertEquals(
                "Over-The-Counter (OTC)",
                OtcRxClassificationClient.ClassificationType.OTC.getLocalized("en")
        );
        assertEquals(
                "ডিজিডিএ প্রত্যাহারকৃত ওষুধ (বিক্রয় নিষিদ্ধ)",
                OtcRxClassificationClient.ClassificationType.RECALLED.getLocalized("bn")
        );
    }

    @Test
    @DisplayName("Preflight validation rejects incomplete reclassification inputs")
    void testPreflightValidation() {
        // Valid
        assertTrue(OtcRxClassificationClient.validateReclassificationPreflight(
                "prod-101",
                OtcRxClassificationClient.ClassificationType.PRESCRIPTION_ONLY,
                "DGDA Gazette 2026/04",
                "Reclassified due to adverse drug reaction reports"
        ));

        // Invalid: missing product ID
        assertFalse(OtcRxClassificationClient.validateReclassificationPreflight(
                "",
                OtcRxClassificationClient.ClassificationType.PRESCRIPTION_ONLY,
                "DGDA Gazette 2026/04",
                "Valid notes"
        ));

        // Invalid: source too short (< 3 chars)
        assertFalse(OtcRxClassificationClient.validateReclassificationPreflight(
                "prod-101",
                OtcRxClassificationClient.ClassificationType.PRESCRIPTION_ONLY,
                "DG",
                "Valid notes"
        ));

        // Invalid: notes too short (< 5 chars)
        assertFalse(OtcRxClassificationClient.validateReclassificationPreflight(
                "prod-101",
                OtcRxClassificationClient.ClassificationType.PRESCRIPTION_ONLY,
                "DGDA Gazette",
                "No"
        ));
    }

    @Test
    @DisplayName("Point-in-time evaluation distinguishes active epochs")
    void testPointInTimeEvaluation() {
        OtcRxClassificationClient.ClassificationSnapshotDto v1 = new OtcRxClassificationClient.ClassificationSnapshotDto(
                "mcs-001",
                "prod-101",
                1,
                OtcRxClassificationClient.ClassificationType.OTC,
                "Initial Registration",
                "2026-01-01T00:00:00Z",
                "2026-06-01T00:00:00Z",
                false,
                false,
                "Initial OTC approval",
                "hash-1"
        );

        OtcRxClassificationClient.ClassificationSnapshotDto v2 = new OtcRxClassificationClient.ClassificationSnapshotDto(
                "mcs-002",
                "prod-101",
                2,
                OtcRxClassificationClient.ClassificationType.PRESCRIPTION_ONLY,
                "DGDA Gazette S.R.O. 12/2026",
                "2026-06-01T00:00:00Z",
                null,
                true,
                false,
                "Elevated to POM following clinical review",
                "hash-2"
        );

        // Before transition: v1 was effective, v2 was not
        assertTrue(OtcRxClassificationClient.isEffectiveAt(v1, "2026-03-15T12:00:00Z"));
        assertFalse(OtcRxClassificationClient.isEffectiveAt(v2, "2026-03-15T12:00:00Z"));

        // After transition: v1 is expired, v2 is effective
        assertFalse(OtcRxClassificationClient.isEffectiveAt(v1, "2026-07-01T10:00:00Z"));
        assertTrue(OtcRxClassificationClient.isEffectiveAt(v2, "2026-07-01T10:00:00Z"));
    }
}

