package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosAuthenticationClientTest
 * Architecture Decision: ADR-0043
 * Unit test for PosAuthenticationClient, tokens expiration check, and bilingual errors.
 */
class PosAuthenticationClientTest {

    @Test
    @DisplayName("AuthTokens evaluates access token freshness and user session claims")
    void testAuthTokensSession() {
        PosUserIdentityService.PosUserSession user = new PosUserIdentityService.PosUserSession(
            "usr-test-1",
            "tenant-gulshan",
            "branch-1",
            "pharmacist@gulshanrx.bd",
            "+8801712345678",
            "Tanvir Hasan",
            "STAFF_PHARMACIST",
            "ACTIVE",
            false
        );

        PosAuthenticationClient.AuthTokens tokens = new PosAuthenticationClient.AuthTokens(
            "mock-access-token",
            "mock-refresh-token",
            "Bearer",
            900,
            user
        );

        assertEquals("mock-access-token", tokens.accessToken);
        assertEquals("mock-refresh-token", tokens.refreshToken);
        assertEquals("Bearer", tokens.tokenType);
        assertFalse(tokens.isAccessTokenExpired(), "New tokens must not be expired");
        assertEquals("tenant-gulshan", tokens.user.tenantId);
    }

    @Test
    @DisplayName("AuthError constructs client-safe bilingual messages")
    void testAuthErrorClientSafe() {
        PosAuthenticationClient.AuthError error = PosAuthenticationClient.parseError(
            "INVALID_CREDENTIALS",
            "Invalid email/phone or password.",
            "ভুল ইমেইল/ফোন অথবা পাসওয়ার্ড।"
        );

        assertTrue(error.isClientSafe());
        assertEquals("INVALID_CREDENTIALS", error.errorCode);
        assertEquals("Invalid email/phone or password.", error.messageEn);
        assertEquals("ভুল ইমেইল/ফোন অথবা পাসওয়ার্ড।", error.messageBn);
    }
}

