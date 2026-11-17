package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P249: Unit Tests for Credentialed Pharmacist Clinical Override Client (JavaFX Desktop).
 * Architecture Decision: ADR-0249 (P249).
 */
public class PharmacistClinicalOverrideClientTest {

    @Test
    @DisplayName("Narcotics and recalled medicines can NEVER be overridden")
    void testOverrideEligibilityRules() {
        // Standard Rx/antibiotic: eligible for clinical exception
        assertTrue(PharmacistClinicalOverrideClient.isEligibleForOverride(false, false));

        // Narcotics / Schedule-G: strictly forbidden
        assertFalse(PharmacistClinicalOverrideClient.isEligibleForOverride(true, false));

        // Recalled or banned: strictly forbidden
        assertFalse(PharmacistClinicalOverrideClient.isEligibleForOverride(false, true));

        // Both narcotic & recalled: strictly forbidden
        assertFalse(PharmacistClinicalOverrideClient.isEligibleForOverride(true, true));
    }

    @Test
    @DisplayName("EN/BN Localized labels render correctly")
    void testLocalizedLabels() {
        assertEquals(
                "জরুরি দীর্ঘমেয়াদী চিকিৎসার ধারাবাহিকতা রক্ষা",
                PharmacistClinicalOverrideClient.OverrideCategory.EMERGENCY_CHRONIC_CONTINUATION.getLocalized("bn")
        );
        assertEquals(
                "Emergency Chronic Care Continuation",
                PharmacistClinicalOverrideClient.OverrideCategory.EMERGENCY_CHRONIC_CONTINUATION.getLocalized("en")
        );
        assertEquals(
                "স্বাক্ষরিত লিখিত সম্মতিপত্র",
                PharmacistClinicalOverrideClient.ConsentType.SIGNED_PHYSICAL.getLocalized("bn")
        );
    }

    @Test
    @DisplayName("Preflight validation enforces strict PCB, policy reference, consent, and units")
    void testPreflightValidation() {
        PharmacistClinicalOverrideClient.ClinicalOverrideRequest validReq =
                new PharmacistClinicalOverrideClient.ClinicalOverrideRequest(
                        "sale-101",
                        "prod-insulin-01",
                        "PCB-8891",
                        PharmacistClinicalOverrideClient.OverrideCategory.EMERGENCY_CHRONIC_CONTINUATION,
                        "DGDA Practice Guideline Sec 8.3",
                        PharmacistClinicalOverrideClient.ConsentType.INFORMED_VERBAL,
                        "Patient verified previous insulin regimen and consented",
                        "Maintenance dose supplied for weekend until clinic reopens",
                        1
                );
        assertTrue(PharmacistClinicalOverrideClient.validatePreflight(validReq));

        // Short PCB (< 4 chars)
        PharmacistClinicalOverrideClient.ClinicalOverrideRequest badPcb =
                new PharmacistClinicalOverrideClient.ClinicalOverrideRequest(
                        "sale-101",
                        "prod-insulin-01",
                        "PC",
                        PharmacistClinicalOverrideClient.OverrideCategory.EMERGENCY_CHRONIC_CONTINUATION,
                        "DGDA Practice Guideline Sec 8.3",
                        PharmacistClinicalOverrideClient.ConsentType.INFORMED_VERBAL,
                        "Patient verified previous insulin regimen and consented",
                        "Maintenance dose supplied for weekend until clinic reopens",
                        1
                );
        assertFalse(PharmacistClinicalOverrideClient.validatePreflight(badPcb));

        // Short Policy Reference (< 5 chars)
        PharmacistClinicalOverrideClient.ClinicalOverrideRequest badPolicy =
                new PharmacistClinicalOverrideClient.ClinicalOverrideRequest(
                        "sale-101",
                        "prod-insulin-01",
                        "PCB-8891",
                        PharmacistClinicalOverrideClient.OverrideCategory.EMERGENCY_CHRONIC_CONTINUATION,
                        "DGDA",
                        PharmacistClinicalOverrideClient.ConsentType.INFORMED_VERBAL,
                        "Patient verified previous insulin regimen and consented",
                        "Maintenance dose supplied for weekend until clinic reopens",
                        1
                );
        assertFalse(PharmacistClinicalOverrideClient.validatePreflight(badPolicy));

        // Invalid approved units (< 1)
        PharmacistClinicalOverrideClient.ClinicalOverrideRequest zeroUnits =
                new PharmacistClinicalOverrideClient.ClinicalOverrideRequest(
                        "sale-101",
                        "prod-insulin-01",
                        "PCB-8891",
                        PharmacistClinicalOverrideClient.OverrideCategory.EMERGENCY_CHRONIC_CONTINUATION,
                        "DGDA Practice Guideline Sec 8.3",
                        PharmacistClinicalOverrideClient.ConsentType.INFORMED_VERBAL,
                        "Patient verified previous insulin regimen and consented",
                        "Maintenance dose supplied for weekend until clinic reopens",
                        0
                );
        assertFalse(PharmacistClinicalOverrideClient.validatePreflight(zeroUnits));
    }
}

