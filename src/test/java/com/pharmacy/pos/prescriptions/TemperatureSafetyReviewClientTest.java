package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TemperatureSafetyReviewClientTest.java
 * Unit tests for P257 TemperatureSafetyReviewClient.
 */
public class TemperatureSafetyReviewClientTest {

    @Test
    @DisplayName("Pharmacist and managers can perform safety review; cashier is barred")
    void testRoleAuthorization() {
        assertTrue(TemperatureSafetyReviewClient.canPerformSafetyReview("STAFF_PHARMACIST"));
        assertTrue(TemperatureSafetyReviewClient.canPerformSafetyReview("BRANCH_MANAGER"));
        assertTrue(TemperatureSafetyReviewClient.canPerformSafetyReview("PHARMACY_OWNER"));
        assertTrue(TemperatureSafetyReviewClient.canPerformSafetyReview("SUPERADMIN"));

        assertFalse(TemperatureSafetyReviewClient.canPerformSafetyReview("CASHIER"));
        assertFalse(TemperatureSafetyReviewClient.canPerformSafetyReview("GUEST"));
        assertFalse(TemperatureSafetyReviewClient.canPerformSafetyReview(null));
    }

    @Test
    @DisplayName("Dispensing gate blocks quarantined lots unless approved for active release")
    void testDispensingGate() {
        // Active and not quarantined -> allowed
        assertTrue(TemperatureSafetyReviewClient.isDispensingAllowed(false, "ACTIVE", null));

        // Quarantined with no review -> blocked
        assertFalse(TemperatureSafetyReviewClient.isDispensingAllowed(true, "QUARANTINED", null));

        // Quarantined but released to active by pharmacist -> allowed
        assertTrue(TemperatureSafetyReviewClient.isDispensingAllowed(true, "QUARANTINED", "RELEASE_TO_ACTIVE"));

        // Quarantined and condemned for disposal -> blocked
        assertFalse(TemperatureSafetyReviewClient.isDispensingAllowed(true, "ARCHIVED", "CONDEMN_DISPOSAL"));
    }

    @Test
    @DisplayName("Preflight validation enforces justification and forbids releasing expired lots")
    void testPreflightValidation() {
        // Valid release
        assertDoesNotThrow(() -> TemperatureSafetyReviewClient.validateReviewPreflight(
            "BATCH-001",
            "RELEASE_TO_ACTIVE",
            new BigDecimal("12.50"),
            "Manufacturer confirmed stability data for 4 hours at 12C.",
            false
        ));

        // Short justification fails
        assertThrows(IllegalArgumentException.class, () -> TemperatureSafetyReviewClient.validateReviewPreflight(
            "BATCH-001",
            "RELEASE_TO_ACTIVE",
            new BigDecimal("12.50"),
            "Too short",
            false
        ));

        // Releasing expired lot fails with IllegalStateException
        assertThrows(IllegalStateException.class, () -> TemperatureSafetyReviewClient.validateReviewPreflight(
            "BATCH-001",
            "RELEASE_TO_ACTIVE",
            new BigDecimal("12.50"),
            "Manufacturer confirmed stability data for 4 hours at 12C.",
            true
        ));
    }

    @Test
    @DisplayName("Localization alert formatting produces valid English and Bengali outputs")
    void testLocalization() {
        String en = TemperatureSafetyReviewClient.formatClinicalAlert("BT-99", "Insulin Glargine", "CONDEMN_DISPOSAL", false);
        assertTrue(en.contains("Insulin Glargine"));
        assertTrue(en.contains("condemned"));

        String bn = TemperatureSafetyReviewClient.formatClinicalAlert("BT-99", "Insulin Glargine", "CONDEMN_DISPOSAL", true);
        assertTrue(bn.contains("Insulin Glargine"));
        assertTrue(bn.contains("বিনষ্ট"));
    }
}
