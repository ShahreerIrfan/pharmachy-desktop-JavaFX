package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P252: Pharmacy Personnel PCB Registration Policy Tests")
class PcbRegistrationPolicyTest {

    @Test
    @DisplayName("Should translate PCB qualification grades to English and Bengali")
    void testGradeTranslations() {
        assertEquals("Grade A (Graduate Pharmacist)", PcbRegistrationPolicy.getGradeDisplay("GRADE_A", "en"));
        assertEquals("এ-গ্রেড (গ্র্যাজুয়েট ফার্মাসিস্ট)", PcbRegistrationPolicy.getGradeDisplay("GRADE_A", "bn"));

        assertEquals("Grade B (Diploma Pharmacist)", PcbRegistrationPolicy.getGradeDisplay("GRADE_B", "en"));
        assertEquals("বি-গ্রেড (ডিপ্লোমা ফার্মাসিস্ট)", PcbRegistrationPolicy.getGradeDisplay("GRADE_B", "bn"));

        assertEquals("Grade C (Pharmacy Technician)", PcbRegistrationPolicy.getGradeDisplay("GRADE_C", "en"));
        assertEquals("সি-গ্রেড (ফার্মাসি টেকনিশিয়ান)", PcbRegistrationPolicy.getGradeDisplay("GRADE_C", "bn"));
    }

    @Test
    @DisplayName("Should translate PCB validity status to localized display strings")
    void testStatusTranslations() {
        assertEquals("Active Valid", PcbRegistrationPolicy.getStatusDisplay("ACTIVE", "en"));
        assertEquals("সক্রিয় ও বৈধ", PcbRegistrationPolicy.getStatusDisplay("ACTIVE", "bn"));

        assertEquals("Renewal Pending", PcbRegistrationPolicy.getStatusDisplay("PENDING_RENEWAL", "en"));
        assertEquals("নবায়ন প্রক্রিয়াধীন", PcbRegistrationPolicy.getStatusDisplay("PENDING_RENEWAL", "bn"));

        assertEquals("Expired", PcbRegistrationPolicy.getStatusDisplay("EXPIRED", "en"));
        assertEquals("মেয়াদ উত্তীর্ণ", PcbRegistrationPolicy.getStatusDisplay("EXPIRED", "bn"));
    }

    @Test
    @DisplayName("Should restrict confidential evidence access strictly to authorized roles or self")
    void testEvidenceAccessRestrictions() {
        // Superadmin and Owner always have access
        assertTrue(PcbRegistrationPolicy.canAccessEvidence("SUPERADMIN", false, false));
        assertTrue(PcbRegistrationPolicy.canAccessEvidence("PHARMACY_OWNER", false, false));

        // Branch Manager has access only for their own branch
        assertTrue(PcbRegistrationPolicy.canAccessEvidence("BRANCH_MANAGER", false, true));
        assertFalse(PcbRegistrationPolicy.canAccessEvidence("BRANCH_MANAGER", false, false));

        // Credential holder (self) always has access to their own document
        assertTrue(PcbRegistrationPolicy.canAccessEvidence("STAFF_PHARMACIST", true, false));
        assertTrue(PcbRegistrationPolicy.canAccessEvidence("CASHIER", true, false));

        // Cashier and unauthorized roles have NO access to another's evidence
        assertFalse(PcbRegistrationPolicy.canAccessEvidence("CASHIER", false, true));
        assertFalse(PcbRegistrationPolicy.canAccessEvidence("STAFF_PHARMACIST", false, true));
        assertFalse(PcbRegistrationPolicy.canAccessEvidence("INVENTORY_CLERK", false, true));
    }

    @Test
    @DisplayName("Should enforce Grade A requirement and active validity for clinical overrides")
    void testClinicalOverridePrivileges() {
        // Grade A Active -> Authorized
        assertTrue(PcbRegistrationPolicy.isAuthorizedForClinicalOverrides("GRADE_A", "ACTIVE"));
        assertTrue(PcbRegistrationPolicy.isAuthorizedForClinicalOverrides("GRADE_A", "PENDING_RENEWAL"));

        // Grade A Expired or Suspended -> Denied
        assertFalse(PcbRegistrationPolicy.isAuthorizedForClinicalOverrides("GRADE_A", "EXPIRED"));
        assertFalse(PcbRegistrationPolicy.isAuthorizedForClinicalOverrides("GRADE_A", "SUSPENDED"));

        // Grade B or C -> Denied even if Active
        assertFalse(PcbRegistrationPolicy.isAuthorizedForClinicalOverrides("GRADE_B", "ACTIVE"));
        assertFalse(PcbRegistrationPolicy.isAuthorizedForClinicalOverrides("GRADE_C", "ACTIVE"));
    }

    @Test
    @DisplayName("Should accurately calculate days until expiry and urgent renewal flags")
    void testDaysUntilExpiryAndUrgentRenewal() {
        LocalDate today = LocalDate.of(2026, 11, 17);
        LocalDate validFuture = LocalDate.of(2026, 11, 27); // 10 days left
        LocalDate expiredPast = LocalDate.of(2026, 11, 10); // -7 days

        long daysLeft = PcbRegistrationPolicy.daysUntilExpiry(validFuture, today);
        assertEquals(10, daysLeft);
        assertTrue(PcbRegistrationPolicy.isExpiringSoon(daysLeft));

        long expiredDays = PcbRegistrationPolicy.daysUntilExpiry(expiredPast, today);
        assertEquals(-7, expiredDays);
        assertFalse(PcbRegistrationPolicy.isExpiringSoon(expiredDays));
    }
}

