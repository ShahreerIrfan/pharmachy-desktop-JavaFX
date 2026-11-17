package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BranchDrugLicenseClientTest {

    @Test
    void testRoleAuthorization() {
        assertTrue(BranchDrugLicenseClient.isRoleAuthorizedToManage("SUPERADMIN"));
        assertTrue(BranchDrugLicenseClient.isRoleAuthorizedToManage("PHARMACY_OWNER"));
        assertTrue(BranchDrugLicenseClient.isRoleAuthorizedToManage("BRANCH_MANAGER"));
        assertFalse(BranchDrugLicenseClient.isRoleAuthorizedToManage("STAFF_PHARMACIST"));
        assertFalse(BranchDrugLicenseClient.isRoleAuthorizedToManage("CASHIER"));
        assertFalse(BranchDrugLicenseClient.isRoleAuthorizedToManage(null));
    }

    @Test
    void testLicenseEvaluationValidityAndWarnings() {
        // Active and healthy (>30 days)
        var healthy = BranchDrugLicenseClient.evaluateLicense(
            BranchDrugLicenseClient.LicenseStatus.ACTIVE, 120
        );
        assertTrue(healthy.isValid());
        assertEquals("ACTIVE", healthy.evaluationCode());

        // Expiring soon (<=30 days)
        var expiring = BranchDrugLicenseClient.evaluateLicense(
            BranchDrugLicenseClient.LicenseStatus.ACTIVE, 15
        );
        assertTrue(expiring.isValid());
        assertEquals("EXPIRING_SOON", expiring.evaluationCode());
        assertTrue(expiring.messageEn().contains("15 days"));
        assertTrue(expiring.messageBn().contains("15 দিন") || expiring.messageBn().contains("১৫ দিন"));

        // Expired without renewal
        var expired = BranchDrugLicenseClient.evaluateLicense(
            BranchDrugLicenseClient.LicenseStatus.EXPIRED, -5
        );
        assertFalse(expired.isValid());
        assertEquals("EXPIRED", expired.evaluationCode());

        // Expired but renewal pending
        var renewalPending = BranchDrugLicenseClient.evaluateLicense(
            BranchDrugLicenseClient.LicenseStatus.RENEWAL_PENDING, -10
        );
        assertTrue(renewalPending.isValid());
        assertEquals("RENEWAL_PENDING", renewalPending.evaluationCode());

        // Suspended or revoked
        var suspended = BranchDrugLicenseClient.evaluateLicense(
            BranchDrugLicenseClient.LicenseStatus.SUSPENDED, 90
        );
        assertFalse(suspended.isValid());
        assertEquals("SUSPENDED", suspended.evaluationCode());

        var revoked = BranchDrugLicenseClient.evaluateLicense(
            BranchDrugLicenseClient.LicenseStatus.REVOKED, 90
        );
        assertFalse(revoked.isValid());
        assertEquals("REVOKED", revoked.evaluationCode());
    }

    @Test
    void testPreflightValidation() {
        // Valid
        var valid = BranchDrugLicenseClient.validatePreflight(
            "DL-DHK-2024-99812",
            "DGDA Dhaka Division",
            "2024-01-01",
            "2026-12-31"
        );
        assertTrue(valid.isValid());
        assertNull(valid.errorCode());

        // Short license number
        var shortLic = BranchDrugLicenseClient.validatePreflight(
            "DL", "DGDA", "2024-01-01", "2026-12-31"
        );
        assertFalse(shortLic.isValid());
        assertEquals("ERR_INVALID_LICENSE_NO", shortLic.errorCode());

        // Invalid date ordering
        var invertedDates = BranchDrugLicenseClient.validatePreflight(
            "DL-DHK-2024-99812", "DGDA", "2026-12-31", "2024-01-01"
        );
        assertFalse(invertedDates.isValid());
        assertEquals("ERR_INVALID_DATES", invertedDates.errorCode());

        // Malformed date format
        var malformed = BranchDrugLicenseClient.validatePreflight(
            "DL-DHK-2024-99812", "DGDA", "invalid-date", "2026-12-31"
        );
        assertFalse(malformed.isValid());
        assertEquals("ERR_DATE_FORMAT", malformed.errorCode());
    }

    @Test
    void testLicenseTypeLocalization() {
        var retail = BranchDrugLicenseClient.LicenseType.FORM_20_RETAIL;
        assertTrue(retail.getLocalizedLabel("bn").contains("ফরম ২০"));
        assertTrue(retail.getLocalizedLabel("en").contains("Form 20"));
    }
}
