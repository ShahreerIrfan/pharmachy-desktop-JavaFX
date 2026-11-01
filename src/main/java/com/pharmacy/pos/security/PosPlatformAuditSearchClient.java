package com.pharmacy.pos.security;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Architecture Decision: ADR-0078
 * JavaFX Desktop POS Client for Platform Admin Audit Search:
 * - Role-based authorization checking (SUPERADMIN, PLATFORM_OPERATOR, PLATFORM_AUDITOR)
 * - Forbidden payload filtering & redaction verification
 * - Query criteria formatting & URL builders
 */
public final class PosPlatformAuditSearchClient {

    private static final Set<String> AUTHORIZED_PLATFORM_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR",
            "PLATFORM_AUDITOR"
    );

    private static final Set<String> FORBIDDEN_PAYLOAD_KEYS = Set.of(
            "password",
            "secret",
            "token",
            "authorization",
            "access_token",
            "refresh_token",
            "secret_key",
            "otp",
            "pin",
            "cvv",
            "card_number",
            "diagnosis",
            "clinical_notes",
            "prescription_image"
    );

    private static final Pattern ENTRY_ID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    private PosPlatformAuditSearchClient() {
        // Prevent instantiation
    }

    /**
     * Checks if a user role is permitted to execute platform-wide audit search.
     */
    public static boolean canSearchPlatformAudit(String role) {
        if (role == null) {
            return false;
        }
        return AUTHORIZED_PLATFORM_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Architectural Invariant: Platform audit search controls are never rendered
     * inside the cashier POS interface.
     */
    public static boolean isAuditSearchVisibleInPos() {
        return false;
    }

    /**
     * Validates whether a payload key represents a forbidden credential or PHI field.
     */
    public static boolean isForbiddenPayloadKey(String key) {
        if (key == null) {
            return false;
        }
        String lowerKey = key.toLowerCase().trim();
        for (String forbidden : FORBIDDEN_PAYLOAD_KEYS) {
            if (lowerKey.contains(forbidden)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates date range constraints (dateTo >= dateFrom).
     */
    public static boolean validateDateRange(String dateFrom, String dateTo) {
        if (dateFrom == null || dateTo == null) {
            return true;
        }
        try {
            Instant from = Instant.parse(dateFrom.trim());
            Instant to = Instant.parse(dateTo.trim());
            return !to.isBefore(from);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sanitizes a payload map by redacting forbidden keys.
     */
    public static Map<String, Object> sanitizePayload(Map<String, Object> input) {
        if (input == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> sanitized = new HashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (isForbiddenPayloadKey(entry.getKey())) {
                sanitized.put(entry.getKey(), "[REDACTED]");
            } else if (entry.getValue() instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) nested;
                sanitized.put(entry.getKey(), sanitizePayload(casted));
            } else {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(sanitized);
    }

    public static boolean isValidEntryId(String entryId) {
        if (entryId == null) {
            return false;
        }
        return ENTRY_ID_PATTERN.matcher(entryId.trim()).matches();
    }

    public static String getSearchUrl() {
        return "/api/v2/platform/audit-search/";
    }

    public static String getDetailUrl(String entryId) {
        if (!isValidEntryId(entryId)) {
            throw new IllegalArgumentException("Invalid audit entry ID format: " + entryId);
        }
        return "/api/v2/platform/audit-search/" + entryId.trim() + "/";
    }

    public static String getExportUrl() {
        return "/api/v2/platform/audit-search/export/";
    }
}
