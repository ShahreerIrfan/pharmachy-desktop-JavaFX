package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NoAutomatedSubstitutionPolicyTest {

    @Test
    void testStatutoryDisclaimersAndRoleAuthorization() {
        String enDisclaimer = NoAutomatedSubstitutionPolicy.getDisclaimer("en");
        assertNotNull(enDisclaimer);
        assertTrue(enDisclaimer.contains("administrative workflow system"));
        assertTrue(enDisclaimer.contains("does not provide medical diagnoses"));

        String bnDisclaimer = NoAutomatedSubstitutionPolicy.getDisclaimer("bn");
        assertNotNull(bnDisclaimer);
        assertTrue(bnDisclaimer.contains("প্রশাসনিক ওয়ার্কফ্লো"));
        assertTrue(bnDisclaimer.contains("স্বয়ংক্রিয় ওষুধ পরিবর্তন"));

        // Role authorization checks
        assertTrue(NoAutomatedSubstitutionPolicy.isRoleAuthorized("STAFF_PHARMACIST"));
        assertTrue(NoAutomatedSubstitutionPolicy.isRoleAuthorized("PHARMACY_OWNER"));
        assertTrue(NoAutomatedSubstitutionPolicy.isRoleAuthorized("BRANCH_MANAGER"));
        assertFalse(NoAutomatedSubstitutionPolicy.isRoleAuthorized("CASHIER"));
        assertFalse(NoAutomatedSubstitutionPolicy.isRoleAuthorized("ACCOUNTANT"));
        assertFalse(NoAutomatedSubstitutionPolicy.isRoleAuthorized(null));
    }

    @Test
    void testDrugEligibilityForSubstitution() {
        // Standard medicine is eligible
        assertTrue(NoAutomatedSubstitutionPolicy.isEligibleForSubstitution(false, false));

        // Narcotics strictly forbidden
        assertFalse(NoAutomatedSubstitutionPolicy.isEligibleForSubstitution(true, false));

        // Recalled / banned medicines strictly forbidden
        assertFalse(NoAutomatedSubstitutionPolicy.isEligibleForSubstitution(false, true));

        // Both narcotic and recalled
        assertFalse(NoAutomatedSubstitutionPolicy.isEligibleForSubstitution(true, true));
    }

    @Test
    void testAutonomousSubstitutionStrictlyRejected() {
        // Attempt autonomous machine substitution
        var res = NoAutomatedSubstitutionPolicy.validateManualSubstitution(
            "prod-brand-a",
            "prod-brand-b",
            "PCB-12345",
            "Manual substitution for out-of-stock brand",
            true // Autonomous machine flag
        );
        assertFalse(res.isValid());
        assertEquals("ERR_AUTOMATED_SUBSTITUTION_FORBIDDEN", res.errorCode());
        assertTrue(res.errorMessage().contains("Autonomous brand substitution is prohibited"));
    }

    @Test
    void testManualSubstitutionPreflightValidation() {
        // Valid manual substitution by credentialed pharmacist
        var valid = NoAutomatedSubstitutionPolicy.validateManualSubstitution(
            "prod-brand-a",
            "prod-brand-b",
            "PCB-99412",
            "Brand out of stock, identical 500mg Paracetamol generic dispensed with patient consent",
            false
        );
        assertTrue(valid.isValid());
        assertNull(valid.errorCode());

        // Incomplete / invalid inputs
        var shortPcb = NoAutomatedSubstitutionPolicy.validateManualSubstitution(
            "prod-brand-a", "prod-brand-b", "PCB", "Brand out of stock", false
        );
        assertFalse(shortPcb.isValid());
        assertEquals("ERR_INVALID_PCB", shortPcb.errorCode());

        var shortRationale = NoAutomatedSubstitutionPolicy.validateManualSubstitution(
            "prod-brand-a", "prod-brand-b", "PCB-99412", "swap", false
        );
        assertFalse(shortRationale.isValid());
        assertEquals("ERR_INVALID_RATIONALE", shortRationale.errorCode());

        var identical = NoAutomatedSubstitutionPolicy.validateManualSubstitution(
            "prod-brand-a", "prod-brand-a", "PCB-99412", "Same brand replacement", false
        );
        assertFalse(identical.isValid());
        assertEquals("ERR_IDENTICAL_PRODUCTS", identical.errorCode());
    }
}

