package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PcbRegistrationTrackingClientTest {

    @Test
    void testRoleAuthorizationAndEvidenceAccessRestrictions() {
        // Management authorization
        assertTrue(PcbRegistrationTrackingClient.isRoleAuthorizedToManage("SUPERADMIN"));
        assertTrue(PcbRegistrationTrackingClient.isRoleAuthorizedToManage("PHARMACY_OWNER"));
        assertTrue(PcbRegistrationTrackingClient.isRoleAuthorizedToManage("BRANCH_MANAGER"));
        assertFalse(PcbRegistrationTrackingClient.isRoleAuthorizedToManage("STAFF_PHARMACIST"));
        assertFalse(PcbRegistrationTrackingClient.isRoleAuthorizedToManage("CASHIER"));

        // Confidential Evidence Access Control
        assertTrue(PcbRegistrationTrackingClient.canViewEvidence("SUPERADMIN", false));
        assertTrue(PcbRegistrationTrackingClient.canViewEvidence("PHARMACY_OWNER", false));
        assertTrue(PcbRegistrationTrackingClient.canViewEvidence("BRANCH_MANAGER", false));
        assertFalse(PcbRegistrationTrackingClient.canViewEvidence("CASHIER", false));
        assertFalse(PcbRegistrationTrackingClient.canViewEvidence("STAFF_PHARMACIST", false));
        // Credential holder inspecting own record
        assertTrue(PcbRegistrationTrackingClient.canViewEvidence("STAFF_PHARMACIST", true));
    }

    @Test
    void testGradeHierarchyAuthorization() {
        var gradeA = PcbRegistrationTrackingClient.PcbGrade.GRADE_A;
        var gradeB = PcbRegistrationTrackingClient.PcbGrade.GRADE_B;
        var gradeC = PcbRegistrationTrackingClient.PcbGrade.GRADE_C;

        // Grade A can perform Grade A, B, and C actions
        assertTrue(PcbRegistrationTrackingClient.isGradeAuthorized(gradeA, gradeA));
        assertTrue(PcbRegistrationTrackingClient.isGradeAuthorized(gradeA, gradeB));
        assertTrue(PcbRegistrationTrackingClient.isGradeAuthorized(gradeA, gradeC));

        // Grade B can perform Grade B and C actions, but NOT Grade A actions
        assertFalse(PcbRegistrationTrackingClient.isGradeAuthorized(gradeB, gradeA));
        assertTrue(PcbRegistrationTrackingClient.isGradeAuthorized(gradeB, gradeB));
        assertTrue(PcbRegistrationTrackingClient.isGradeAuthorized(gradeB, gradeC));

        // Grade C can ONLY perform Grade C actions
        assertFalse(PcbRegistrationTrackingClient.isGradeAuthorized(gradeC, gradeA));
        assertFalse(PcbRegistrationTrackingClient.isGradeAuthorized(gradeC, gradeB));
        assertTrue(PcbRegistrationTrackingClient.isGradeAuthorized(gradeC, gradeC));
    }

    @Test
    void testRegistrationValidityEvaluation() {
        // Active
        var active = PcbRegistrationTrackingClient.evaluateRegistration(
            PcbRegistrationTrackingClient.PcbStatus.ACTIVE, 100
        );
        assertTrue(active.isValid());
        assertEquals("ACTIVE", active.evaluationCode());

        // Expiring soon (<= 30 days)
        var expiring = PcbRegistrationTrackingClient.evaluateRegistration(
            PcbRegistrationTrackingClient.PcbStatus.ACTIVE, 15
        );
        assertTrue(expiring.isValid());
        assertEquals("EXPIRING_SOON", expiring.evaluationCode());
        assertTrue(expiring.messageEn().contains("15 days"));
        assertTrue(expiring.messageBn().contains("15 দিন") || expiring.messageBn().contains("১৫ দিন"));

        // Expired without renewal
        var expired = PcbRegistrationTrackingClient.evaluateRegistration(
            PcbRegistrationTrackingClient.PcbStatus.EXPIRED, -5
        );
        assertFalse(expired.isValid());
        assertEquals("EXPIRED", expired.evaluationCode());

        // Expired but pending renewal
        var renewal = PcbRegistrationTrackingClient.evaluateRegistration(
            PcbRegistrationTrackingClient.PcbStatus.PENDING_RENEWAL, -10
        );
        assertTrue(renewal.isValid());
        assertEquals("PENDING_RENEWAL", renewal.evaluationCode());

        // Suspended or revoked
        var suspended = PcbRegistrationTrackingClient.evaluateRegistration(
            PcbRegistrationTrackingClient.PcbStatus.SUSPENDED, 50
        );
        assertFalse(suspended.isValid());
        assertEquals("SUSPENDED", suspended.evaluationCode());
    }

    @Test
    void testPreflightValidation() {
        // Valid
        var valid = PcbRegistrationTrackingClient.validatePreflight(
            "A-12845",
            "Md. Tanvir Hasan",
            "2020-01-01",
            "2026-12-31"
        );
        assertTrue(valid.isValid());
        assertNull(valid.errorCode());

        // Short registration number (<3 chars)
        var shortReg = PcbRegistrationTrackingClient.validatePreflight(
            "A", "Md. Tanvir", "2020-01-01", "2026-12-31"
        );
        assertFalse(shortReg.isValid());
        assertEquals("ERR_INVALID_REG_NO", shortReg.errorCode());

        // Inverted dates (valid_until < issue_date)
        var inverted = PcbRegistrationTrackingClient.validatePreflight(
            "A-12845", "Md. Tanvir", "2026-12-31", "2020-01-01"
        );
        assertFalse(inverted.isValid());
        assertEquals("ERR_INVALID_DATES", inverted.errorCode());
    }

    @Test
    void testGradeLocalization() {
        var gradeA = PcbRegistrationTrackingClient.PcbGrade.GRADE_A;
        assertTrue(gradeA.getLocalizedLabel("bn").contains("এ-গ্রেড"));
        assertTrue(gradeA.getLocalizedLabel("en").contains("Grade A"));
    }
}

