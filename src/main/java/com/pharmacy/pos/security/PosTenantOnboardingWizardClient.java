package com.pharmacy.pos.security;

import java.util.*;
import java.util.regex.Pattern;

/**
 * PosTenantOnboardingWizardClient
 * Architecture Decision: ADR-0079
 * JavaFX Desktop POS Client for Platform Tenant Onboarding Wizard:
 *   - Enforces Platform Super Admin role authorization
 *   - Validates multi-step wizard payloads (Pharmacy, License, Branch/Staff, Inventory, Plan)
 *   - Enforces DGDA drug license and Bangladesh E.164 phone invariants
 *   - Exposes bilingual EN/BN step metadata for native UI rendering
 */
public final class PosTenantOnboardingWizardClient {

    private static final Set<String> AUTHORIZED_ROLES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("SUPERADMIN", "SUPER_ADMIN", "PLATFORM_OPERATOR"))
    );

    private static final Set<String> RESERVED_SUBDOMAINS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("admin", "api", "system", "portal", "billing", "root", "app", "dev", "test", "stage", "prod", "superadmin"))
    );

    private static final Pattern SUBDOMAIN_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$");
    private static final Pattern BD_PHONE_PATTERN = Pattern.compile("^\\+8801[3-9]\\d{8}$");

    public enum WizardStep {
        PHARMACY_DETAILS(1, "Pharmacy Details", "ফার্মেসির বিবরণ"),
        REGULATORY_LICENSING(2, "Licensing & DGDA Compliance", "ড্রাগ লাইসেন্স ও নিয়মতান্ত্রিক স্বীকৃতি"),
        BRANCH_AND_STAFF(3, "Branch & Staff Setup", "শাখা ও প্রাথমিক কর্মী কনফিগারেশন"),
        INITIAL_INVENTORY(4, "Initial Inventory Baseline", "প্রাথমিক স্টক ও ক্যাটালগ নির্ধারণ"),
        PLAN_SELECTION(5, "Plan Purchase & Quotas", "সাবস্ক্রিপশন প্ল্যান ও কোটা নির্বাচন"),
        FINAL_REVIEW(6, "Review & Finalize", "পর্যালোচনা ও সক্রিয়করণ");

        private final int stepNumber;
        private final String titleEn;
        private final String titleBn;

        WizardStep(int stepNumber, String titleEn, String titleBn) {
            this.stepNumber = stepNumber;
            this.titleEn = titleEn;
            this.titleBn = titleBn;
        }

        public int getStepNumber() { return stepNumber; }
        public String getTitleEn() { return titleEn; }
        public String getTitleBn() { return titleBn; }

        public static WizardStep fromNumber(int num) {
            for (WizardStep s : values()) {
                if (s.stepNumber == num) return s;
            }
            throw new IllegalArgumentException("Unknown step number: " + num);
        }
    }

    private PosTenantOnboardingWizardClient() {}

    /**
     * Checks if operator role is authorized for platform onboarding wizard.
     */
    public static boolean isAuthorizedForOnboarding(String role) {
        if (role == null) return false;
        return AUTHORIZED_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Validates subdomain compliance.
     */
    public static boolean isSubdomainValid(String rawSubdomain) {
        if (rawSubdomain == null || rawSubdomain.isBlank()) return false;
        String clean = rawSubdomain.trim().toLowerCase();
        if (RESERVED_SUBDOMAINS.contains(clean)) return false;
        return SUBDOMAIN_PATTERN.matcher(clean).matches();
    }

    /**
     * Validates DGDA drug license number.
     */
    public static boolean isDrugLicenseValid(String drugLicense) {
        if (drugLicense == null || drugLicense.isBlank()) return false;
        return drugLicense.trim().length() >= 3;
    }

    /**
     * Validates Bangladesh mobile phone.
     */
    public static boolean isPhoneValid(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) return false;
        return BD_PHONE_PATTERN.matcher(rawPhone.trim()).matches();
    }

    /**
     * Validates full wizard submission payload.
     */
    public static boolean validateCompletePayload(
        String pharmacyName,
        String subdomain,
        String drugLicense,
        String ownerEmail,
        String ownerPhone,
        String ownerPassword,
        String planTier
    ) {
        if (pharmacyName == null || pharmacyName.trim().length() < 3) return false;
        if (!isSubdomainValid(subdomain)) return false;
        if (!isDrugLicenseValid(drugLicense)) return false;
        if (ownerEmail == null || !ownerEmail.contains("@")) return false;
        if (!isPhoneValid(ownerPhone)) return false;
        if (ownerPassword == null || ownerPassword.length() < 8) return false;
        if (planTier == null || !Arrays.asList("BASIC", "STANDARD", "PRO").contains(planTier.toUpperCase().trim())) return false;
        return true;
    }

    /**
     * Resolves API endpoint for onboarding wizard actions.
     */
    public static String resolveEndpoint(String action, String sessionId) {
        if ("list".equalsIgnoreCase(action) || "create".equalsIgnoreCase(action)) {
            return "/api/v2/platform/onboarding-wizard/";
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required for session-scoped actions.");
        }
        String cleanId = sessionId.trim();
        if ("complete".equalsIgnoreCase(action)) {
            return "/api/v2/platform/onboarding-wizard/" + cleanId + "/complete/";
        }
        if ("abandon".equalsIgnoreCase(action)) {
            return "/api/v2/platform/onboarding-wizard/" + cleanId + "/abandon/";
        }
        if ("update-step".equalsIgnoreCase(action)) {
            return "/api/v2/platform/onboarding-wizard/" + cleanId + "/update-step/";
        }
        return "/api/v2/platform/onboarding-wizard/" + cleanId + "/";
    }
}
