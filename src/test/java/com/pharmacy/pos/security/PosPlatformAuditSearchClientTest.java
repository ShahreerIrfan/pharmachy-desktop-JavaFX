package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0078: JavaFX Desktop POS Platform Audit Search Client Tests")
class PosPlatformAuditSearchClientTest {

    @Test
    @DisplayName("Role RBAC: Platform Roles Allowed, Tenant Roles Strictly Blocked")
    void testRoleAccess() {
        assertTrue(PosPlatformAuditSearchClient.canSearchPlatformAudit("SUPERADMIN"));
        assertTrue(PosPlatformAuditSearchClient.canSearchPlatformAudit("SUPER_ADMIN"));
        assertTrue(PosPlatformAuditSearchClient.canSearchPlatformAudit("PLATFORM_OPERATOR"));
        assertTrue(PosPlatformAuditSearchClient.canSearchPlatformAudit("PLATFORM_AUDITOR"));

        assertFalse(PosPlatformAuditSearchClient.canSearchPlatformAudit("PHARMACY_OWNER"));
        assertFalse(PosPlatformAuditSearchClient.canSearchPlatformAudit("BRANCH_MANAGER"));
        assertFalse(PosPlatformAuditSearchClient.canSearchPlatformAudit("STAFF_PHARMACIST"));
        assertFalse(PosPlatformAuditSearchClient.canSearchPlatformAudit("CASHIER"));
        assertFalse(PosPlatformAuditSearchClient.canSearchPlatformAudit(null));
        assertFalse(PosPlatformAuditSearchClient.canSearchPlatformAudit(""));
    }

    @Test
    @DisplayName("Forbidden Payload Detection: Identifies credentials, secrets, PINs, and PHI")
    void testForbiddenPayloadKeyDetection() {
        assertTrue(PosPlatformAuditSearchClient.isForbiddenPayloadKey("password"));
        assertTrue(PosPlatformAuditSearchClient.isForbiddenPayloadKey("secret_key"));
        assertTrue(PosPlatformAuditSearchClient.isForbiddenPayloadKey("access_token"));
        assertTrue(PosPlatformAuditSearchClient.isForbiddenPayloadKey("otp"));
        assertTrue(PosPlatformAuditSearchClient.isForbiddenPayloadKey("bkash_pin"));
        assertTrue(PosPlatformAuditSearchClient.isForbiddenPayloadKey("clinical_notes"));
        assertTrue(PosPlatformAuditSearchClient.isForbiddenPayloadKey("prescription_image"));

        assertFalse(PosPlatformAuditSearchClient.isForbiddenPayloadKey("user_id"));
        assertFalse(PosPlatformAuditSearchClient.isForbiddenPayloadKey("tenant_id"));
        assertFalse(PosPlatformAuditSearchClient.isForbiddenPayloadKey("action"));
        assertFalse(PosPlatformAuditSearchClient.isForbiddenPayloadKey("status"));
    }

    @Test
    @DisplayName("Payload Sanitization: Masks forbidden keys with [REDACTED]")
    void testPayloadSanitization() {
        Map<String, Object> raw = Map.of(
                "action", "USER_PASSWORD_CHANGE",
                "user_id", "u-12345",
                "password", "PlaintextPass123!",
                "secret_key", "SuperSecretMFAKey",
                "meta", Map.of("otp", "654321", "client", "pos-terminal")
        );

        Map<String, Object> sanitized = PosPlatformAuditSearchClient.sanitizePayload(raw);

        assertEquals("USER_PASSWORD_CHANGE", sanitized.get("action"));
        assertEquals("u-12345", sanitized.get("user_id"));
        assertEquals("[REDACTED]", sanitized.get("password"));
        assertEquals("[REDACTED]", sanitized.get("secret_key"));

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) sanitized.get("meta");
        assertEquals("[REDACTED]", nested.get("otp"));
        assertEquals("pos-terminal", nested.get("client"));
    }

    @Test
    @DisplayName("Date Range Validation: Inverted or malformed dates rejected")
    void testDateRangeValidation() {
        assertTrue(PosPlatformAuditSearchClient.validateDateRange(
                "2026-11-01T00:00:00Z",
                "2026-11-01T12:00:00Z"
        ));
        assertTrue(PosPlatformAuditSearchClient.validateDateRange(
                "2026-11-01T00:00:00Z",
                "2026-11-01T00:00:00Z"
        ));
        assertFalse(PosPlatformAuditSearchClient.validateDateRange(
                "2026-11-02T00:00:00Z",
                "2026-11-01T00:00:00Z"
        ));
        assertFalse(PosPlatformAuditSearchClient.validateDateRange(
                "not-a-date",
                "2026-11-01T00:00:00Z"
        ));
    }

    @Test
    @DisplayName("URL Builder and Entry ID Validation")
    void testUrlsAndEntryId() {
        String validId = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d";
        assertTrue(PosPlatformAuditSearchClient.isValidEntryId(validId));
        assertFalse(PosPlatformAuditSearchClient.isValidEntryId("invalid-id"));

        assertEquals("/api/v2/platform/audit-search/", PosPlatformAuditSearchClient.getSearchUrl());
        assertEquals("/api/v2/platform/audit-search/" + validId + "/", PosPlatformAuditSearchClient.getDetailUrl(validId));
        assertEquals("/api/v2/platform/audit-search/export/", PosPlatformAuditSearchClient.getExportUrl());
    }

    @Test
    @DisplayName("UI Boundary Invariant: Platform audit search never visible in POS")
    void testUiBoundaryInvariant() {
        assertFalse(PosPlatformAuditSearchClient.isAuditSearchVisibleInPos());
    }
}
