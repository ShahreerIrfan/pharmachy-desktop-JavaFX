package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P248: Unit Tests for Offline Prescription Authorization Client (JavaFX Desktop).
 * Architecture Decision: ADR-0248 (P248).
 */
public class OfflinePrescriptionAuthorizationClientTest {

    @Test
    @DisplayName("Pure OTC cart is offline eligible; recalled/narcotics are strictly blocked")
    void testEligibilityEvaluation() {
        // Pure OTC cart
        assertEquals(
                OfflinePrescriptionAuthorizationClient.OfflineEligibilityStatus.ELIGIBLE,
                OfflinePrescriptionAuthorizationClient.evaluateEligibility(false, false, false, false)
        );

        // Cart with unverified Rx
        assertEquals(
                OfflinePrescriptionAuthorizationClient.OfflineEligibilityStatus.BLOCKED_UNVERIFIED_RX,
                OfflinePrescriptionAuthorizationClient.evaluateEligibility(false, false, true, false)
        );

        // Cart with verified Rx (pharmacist approved)
        assertEquals(
                OfflinePrescriptionAuthorizationClient.OfflineEligibilityStatus.ELIGIBLE,
                OfflinePrescriptionAuthorizationClient.evaluateEligibility(false, false, true, true)
        );

        // Cart with narcotics: FORBIDDEN even if claimed approved
        assertEquals(
                OfflinePrescriptionAuthorizationClient.OfflineEligibilityStatus.FORBIDDEN_NARCOTIC,
                OfflinePrescriptionAuthorizationClient.evaluateEligibility(false, true, true, true)
        );

        // Cart with recalled item: BLOCKED
        assertEquals(
                OfflinePrescriptionAuthorizationClient.OfflineEligibilityStatus.BLOCKED_RECALLED,
                OfflinePrescriptionAuthorizationClient.evaluateEligibility(true, false, false, false)
        );
    }

    @Test
    @DisplayName("Localized EN/BN error messages reflect conservative safety rules")
    void testLocalizedMessages() {
        OfflinePrescriptionAuthorizationClient.OfflineEligibilityStatus status =
                OfflinePrescriptionAuthorizationClient.OfflineEligibilityStatus.FORBIDDEN_NARCOTIC;

        assertEquals(
                "সংবিধিবদ্ধ নিষেধাজ্ঞা: মাদক ও সিডিউল-জি ওষুধ অফলাইনে বিক্রি সম্পূর্ণ অবৈধ",
                status.getLocalized("bn")
        );
        assertEquals(
                "Statutory Forbidden: Narcotics/Schedule-G substances cannot be dispensed offline",
                status.getLocalized("en")
        );
    }

    @Test
    @DisplayName("Preflight validation enforces strict PCB, BMDC, and justification notes")
    void testPreflightValidation() {
        OfflinePrescriptionAuthorizationClient.OfflineAuthorizationRequest validReq =
                new OfflinePrescriptionAuthorizationClient.OfflineAuthorizationRequest(
                        "sale-001",
                        "PCB-7741",
                        "BMDC-A9812",
                        "Dr. Nazmul Huda",
                        "PT-101",
                        "Emergency offline physical Rx verified by on-duty pharmacist"
                );
        assertTrue(OfflinePrescriptionAuthorizationClient.validateAuthorizationPreflight(validReq));

        // Short PCB (< 4 chars)
        OfflinePrescriptionAuthorizationClient.OfflineAuthorizationRequest badPcb =
                new OfflinePrescriptionAuthorizationClient.OfflineAuthorizationRequest(
                        "sale-001",
                        "PCB",
                        "BMDC-A9812",
                        "Dr. Nazmul Huda",
                        "PT-101",
                        "Emergency offline physical Rx verified by on-duty pharmacist"
                );
        assertFalse(OfflinePrescriptionAuthorizationClient.validateAuthorizationPreflight(badPcb));

        // Short BMDC (< 4 chars)
        OfflinePrescriptionAuthorizationClient.OfflineAuthorizationRequest badBmdc =
                new OfflinePrescriptionAuthorizationClient.OfflineAuthorizationRequest(
                        "sale-001",
                        "PCB-7741",
                        "BM",
                        "Dr. Nazmul Huda",
                        "PT-101",
                        "Emergency offline physical Rx verified by on-duty pharmacist"
                );
        assertFalse(OfflinePrescriptionAuthorizationClient.validateAuthorizationPreflight(badBmdc));

        // Short notes (< 5 chars)
        OfflinePrescriptionAuthorizationClient.OfflineAuthorizationRequest badNotes =
                new OfflinePrescriptionAuthorizationClient.OfflineAuthorizationRequest(
                        "sale-001",
                        "PCB-7741",
                        "BMDC-A9812",
                        "Dr. Nazmul Huda",
                        "PT-101",
                        "OK"
                );
        assertFalse(OfflinePrescriptionAuthorizationClient.validateAuthorizationPreflight(badNotes));
    }
}

