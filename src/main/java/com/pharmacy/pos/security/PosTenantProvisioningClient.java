package com.pharmacy.pos.security;

import java.util.*;
import java.util.regex.Pattern;

/**
 * PosTenantProvisioningClient
 * Architecture Decision: ADR-0062
 * Validates tenant onboarding inputs, reserved subdomain blacklist,
 * and Super Admin authorization on desktop client:
 *   - Subdomain validation (3-64 chars, kebab-case, no reserved words)
 *   - Bangladesh E.164 phone validation
 *   - Password complexity
 *   - Super Admin RBAC guard
 */
public final class PosTenantProvisioningClient {

    private static final Set<String> RESERVED_SUBDOMAINS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("admin", "api", "system", "portal", "billing", "root", "app", "dev", "test", "stage", "prod", "superadmin"))
    );

    private static final Pattern SUBDOMAIN_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$");
    private static final Pattern BD_PHONE_PATTERN = Pattern.compile("^\\+8801[3-9]\\d{8}$");

    private PosTenantProvisioningClient() {}

    /**
     * Checks if a subdomain is valid and not a reserved platform identifier.
     */
    public static boolean isSubdomainValid(String rawSubdomain) {
        if (rawSubdomain == null || rawSubdomain.isBlank()) {
            return false;
        }
        String clean = rawSubdomain.trim().toLowerCase();
        if (RESERVED_SUBDOMAINS.contains(clean)) {
            return false;
        }
        return SUBDOMAIN_PATTERN.matcher(clean).matches();
    }

    /**
     * Validates Bangladesh mobile phone format.
     */
    public static boolean isOwnerPhoneValid(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return false;
        }
        return BD_PHONE_PATTERN.matcher(rawPhone.trim()).matches();
    }

    /**
     * Checks if the operator holds Super Admin provisioning privilege.
     */
    public static boolean isProvisioningAuthorized(String role) {
        if (role == null) return false;
        String clean = role.toUpperCase().trim();
        return "SUPERADMIN".equals(clean) || "PLATFORM_OPERATOR".equals(clean);
    }

    /**
     * Validates owner password minimum complexity.
     */
    public static boolean isPasswordSufficient(String password) {
        return password != null && password.length() >= 8;
    }
}
