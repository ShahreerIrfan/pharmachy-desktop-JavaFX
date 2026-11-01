package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PosTenantOnboardingWizardClient Tests (ADR-0079)")
public class PosTenantOnboardingWizardClientTest {

    @Test
    @DisplayName("Should authorize Platform Super Admin and Platform Operator")
    void testAuthorization() {
        assertTrue(PosTenantOnboardingWizardClient.isAuthorizedForOnboarding("SUPERADMIN"));
        assertTrue(PosTenantOnboardingWizardClient.isAuthorizedForOnboarding("SUPER_ADMIN"));
        assertTrue(PosTenantOnboardingWizardClient.isAuthorizedForOnboarding("PLATFORM_OPERATOR"));

        assertFalse(PosTenantOnboardingWizardClient.isAuthorizedForOnboarding("PHARMACY_OWNER"));
        assertFalse(PosTenantOnboardingWizardClient.isAuthorizedForOnboarding("CASHIER"));
        assertFalse(PosTenantOnboardingWizardClient.isAuthorizedForOnboarding("STAFF_PHARMACIST"));
        assertFalse(PosTenantOnboardingWizardClient.isAuthorizedForOnboarding(null));
    }

    @Test
    @DisplayName("Should validate subdomains and reject reserved platform names")
    void testSubdomainValidation() {
        assertTrue(PosTenantOnboardingWizardClient.isSubdomainValid("dhanmondi-pharma"));
        assertTrue(PosTenantOnboardingWizardClient.isSubdomainValid("medicare-bd"));

        assertFalse(PosTenantOnboardingWizardClient.isSubdomainValid("admin"));
        assertFalse(PosTenantOnboardingWizardClient.isSubdomainValid("superadmin"));
        assertFalse(PosTenantOnboardingWizardClient.isSubdomainValid("api"));
        assertFalse(PosTenantOnboardingWizardClient.isSubdomainValid("-invalid-leading"));
        assertFalse(PosTenantOnboardingWizardClient.isSubdomainValid("ab")); // too short
        assertFalse(PosTenantOnboardingWizardClient.isSubdomainValid(null));
    }

    @Test
    @DisplayName("Should enforce DGDA drug license validity")
    void testDrugLicenseValidation() {
        assertTrue(PosTenantOnboardingWizardClient.isDrugLicenseValid("DGDA-DL-2026-9871"));
        assertTrue(PosTenantOnboardingWizardClient.isDrugLicenseValid("DL-12345"));

        assertFalse(PosTenantOnboardingWizardClient.isDrugLicenseValid(""));
        assertFalse(PosTenantOnboardingWizardClient.isDrugLicenseValid("  "));
        assertFalse(PosTenantOnboardingWizardClient.isDrugLicenseValid("a"));
        assertFalse(PosTenantOnboardingWizardClient.isDrugLicenseValid(null));
    }

    @Test
    @DisplayName("Should validate Bangladesh phone numbers")
    void testPhoneValidation() {
        assertTrue(PosTenantOnboardingWizardClient.isPhoneValid("+8801700000000")); // synthetic-fixture
        assertTrue(PosTenantOnboardingWizardClient.isPhoneValid("+8801912345678")); // synthetic-fixture

        assertFalse(PosTenantOnboardingWizardClient.isPhoneValid("01700000000"));
        assertFalse(PosTenantOnboardingWizardClient.isPhoneValid("+8801200000000")); // invalid operator prefix
        assertFalse(PosTenantOnboardingWizardClient.isPhoneValid(null));
    }

    @Test
    @DisplayName("Should validate complete payload correctly")
    void testCompletePayloadValidation() {
        assertTrue(PosTenantOnboardingWizardClient.validateCompletePayload(
            "Popular Pharmacy",
            "popular-pharma",
            "DGDA-DL-9988",
            "owner@popular.local",
            "+8801700000000", // synthetic-fixture
            "Secur3P@ssw0rd!",
            "STANDARD"
        ));

        // Invalid: missing drug license
        assertFalse(PosTenantOnboardingWizardClient.validateCompletePayload(
            "Popular Pharmacy",
            "popular-pharma",
            "",
            "owner@popular.local",
            "+8801700000000", // synthetic-fixture
            "Secur3P@ssw0rd!",
            "STANDARD"
        ));

        // Invalid: weak password
        assertFalse(PosTenantOnboardingWizardClient.validateCompletePayload(
            "Popular Pharmacy",
            "popular-pharma",
            "DGDA-DL-9988",
            "owner@popular.local",
            "+8801700000000", // synthetic-fixture
            "short",
            "STANDARD"
        ));
    }

    @Test
    @DisplayName("Should verify bilingual step descriptors and endpoint resolution")
    void testStepMetadataAndEndpoints() {
        PosTenantOnboardingWizardClient.WizardStep step1 = PosTenantOnboardingWizardClient.WizardStep.fromNumber(1);
        assertEquals("Pharmacy Details", step1.getTitleEn());
        assertEquals("ফার্মেসির বিবরণ", step1.getTitleBn());

        assertEquals("/api/v2/platform/onboarding-wizard/", PosTenantOnboardingWizardClient.resolveEndpoint("list", null));
        assertEquals("/api/v2/platform/onboarding-wizard/onb-123/complete/", PosTenantOnboardingWizardClient.resolveEndpoint("complete", "onb-123"));
        assertEquals("/api/v2/platform/onboarding-wizard/onb-123/abandon/", PosTenantOnboardingWizardClient.resolveEndpoint("abandon", "onb-123"));
    }
}

