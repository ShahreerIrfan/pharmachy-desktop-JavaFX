package com.pharmacy.pos.security;

import com.pharmacy.pos.contracts.PosApiResponseEnvelope;
import com.pharmacy.pos.contracts.PosApiResponseEnvelope.UiState;
import com.pharmacy.pos.security.PosGlobalExceptionResolver.ResolvedErrorInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("P054: Desktop POS Global Exception Resolver Tests (ADR-0054)")
public class PosGlobalExceptionResolverTest {

    @Test
    @DisplayName("1. Resolves Validation Error to UiState.ERROR with localized messages and field details")
    void testResolveValidationError() {
        PosApiResponseEnvelope<Object> envelope = PosApiResponseEnvelope.error("ERR_VALIDATION_FAILED", 422)
                .requestId("req-test-validation-01")
                .retryable(false)
                .recoveryAction("CORRECT_INPUT_FIELDS")
                .messageEn("One or more input fields failed validation.")
                .messageBn("এক বা একাধিক তথ্যের মান সঠিক নয়।")
                .fieldErrors(Map.of("dispense_quantity", List.of("Quantity must be greater than zero.")))
                .build();

        ResolvedErrorInfo infoEn = PosGlobalExceptionResolver.resolveEnvelope(envelope, "en");
        assertEquals(UiState.ERROR, infoEn.uiState());
        assertEquals("ERR_VALIDATION_FAILED", infoEn.errorCode());
        assertEquals("req-test-validation-01", infoEn.requestId());
        assertEquals(422, infoEn.httpStatus());
        assertFalse(infoEn.isRetryable());
        assertEquals("CORRECT_INPUT_FIELDS", infoEn.recoveryAction());
        assertEquals("One or more input fields failed validation.", infoEn.localizedMessage());
        assertTrue(infoEn.fieldErrors().containsKey("dispense_quantity"));

        ResolvedErrorInfo infoBn = PosGlobalExceptionResolver.resolveEnvelope(envelope, "bn");
        assertEquals("এক বা একাধিক তথ্যের মান সঠিক নয়।", infoBn.localizedMessage());
    }

    @Test
    @DisplayName("2. Resolves Cross-Tenant & Unauthorized to UiState.PERMISSION_DENIED")
    void testResolveCrossTenantAndUnauthorized() {
        PosApiResponseEnvelope<Object> crossTenantEnvelope = PosApiResponseEnvelope.error("ERR_CROSS_TENANT_VIOLATION", 403)
                .requestId("req-cross-tenant-02")
                .retryable(false)
                .recoveryAction("ABORT_SECURITY_INCIDENT")
                .messageEn("Access to foreign pharmacy tenant resources is strictly forbidden.")
                .messageBn("অন্য ফার্মেসির তথ্যে প্রবেশাধিকার কঠোরভাবে নিষিদ্ধ।")
                .build();

        ResolvedErrorInfo info = PosGlobalExceptionResolver.resolveEnvelope(crossTenantEnvelope, "bn");
        assertEquals(UiState.PERMISSION_DENIED, info.uiState());
        assertEquals("ERR_CROSS_TENANT_VIOLATION", info.errorCode());
        assertTrue(info.localizedMessage().contains("অন্য ফার্মেসির তথ্যে প্রবেশাধিকার"));

        ResolvedErrorInfo authExpired = PosGlobalExceptionResolver.resolveHttpStatus(401, "", "en", "req-auth-01");
        assertEquals(UiState.PERMISSION_DENIED, authExpired.uiState());
        assertEquals("ERR_UNAUTHORIZED", authExpired.errorCode());
    }

    @Test
    @DisplayName("3. Resolves Database Integrity Conflict to UiState.ERROR with safe sanitized description")
    void testResolveDatabaseIntegrityConflict() {
        PosApiResponseEnvelope<Object> envelope = PosApiResponseEnvelope.error("ERR_DATABASE_INTEGRITY", 409)
                .requestId("req-db-integrity-03")
                .retryable(false)
                .recoveryAction("CORRECT_INPUT_FIELDS")
                .messageEn("A database integrity constraint or duplicate record conflict occurred.")
                .messageBn("একটি ডেটাবেজ ইন্টিগ্রিটি বা ডুপ্লিকেট রেকর্ড দ্বন্দ্ব তৈরি হয়েছে।")
                .build();

        ResolvedErrorInfo info = PosGlobalExceptionResolver.resolveEnvelope(envelope, "bn");
        assertEquals(UiState.ERROR, info.uiState());
        assertEquals("ERR_DATABASE_INTEGRITY", info.errorCode());
        assertFalse(info.isRetryable());
        assertTrue(info.localizedMessage().contains("ডেটাবেজ ইন্টিগ্রিটি"));
    }

    @Test
    @DisplayName("4. Resolves Database Unavailable and Network Timeout to UiState.OFFLINE with retryable flag")
    void testResolveDatabaseUnavailableAndOffline() {
        PosApiResponseEnvelope<Object> envelope = PosApiResponseEnvelope.error("ERR_DATABASE_UNAVAILABLE", 503)
                .requestId("req-db-unavail-04")
                .retryable(true)
                .recoveryAction("RETRY_AFTER_BACKOFF")
                .messageEn("Database service is temporarily unavailable or locked. Safe to retry.")
                .messageBn("ডেটাবেজ সেবা সাময়িকভাবে অনুপলব্ধ বা লক করা আছে। কিছুক্ষণ পর পুনরায় চেষ্টা করুন।")
                .build();

        ResolvedErrorInfo info = PosGlobalExceptionResolver.resolveEnvelope(envelope, "en");
        assertEquals(UiState.OFFLINE, info.uiState());
        assertEquals("ERR_DATABASE_UNAVAILABLE", info.errorCode());
        assertTrue(info.isRetryable());
        assertEquals("RETRY_AFTER_BACKOFF", info.recoveryAction());

        ResolvedErrorInfo status503 = PosGlobalExceptionResolver.resolveHttpStatus(503, "", "bn", "req-503");
        assertEquals(UiState.OFFLINE, status503.uiState());
        assertTrue(status503.isRetryable());
    }

    @Test
    @DisplayName("5. Resolves Unhandled Server Error to UiState.ERROR while preserving correlation ID")
    void testResolveInternalServerError() {
        PosApiResponseEnvelope<Object> envelope = PosApiResponseEnvelope.error("ERR_INTERNAL_SERVER_ERROR", 500)
                .requestId("req-crash-trace-5555")
                .retryable(false)
                .recoveryAction("RETRY_AFTER_BACKOFF")
                .messageEn("An internal server error occurred. Please contact support with the request ID.")
                .messageBn("একটি অভ্যন্তরীণ সমস্যা দেখা দিয়েছে। অনুগ্রহ করে রিকোয়েস্ট আইডি সহ সাপোর্ট টিমের সাথে যোগাযোগ করুন।")
                .build();

        ResolvedErrorInfo info = PosGlobalExceptionResolver.resolveEnvelope(envelope, "bn");
        assertEquals(UiState.ERROR, info.uiState());
        assertEquals("ERR_INTERNAL_SERVER_ERROR", info.errorCode());
        assertEquals("req-crash-trace-5555", info.requestId());
        assertTrue(info.localizedMessage().contains("সাপোর্ট টিমের সাথে যোগাযোগ করুন"));
        assertNotNull(info.fieldErrors());
        assertTrue(info.fieldErrors().isEmpty());
    }
}
