package com.pharmacy.pos.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P048: Desktop POS Rate Limiting & Backoff Cooldown Test Suite")
class PosRateLimitHandlerTest {

    private PosRateLimitHandler rateLimitHandler;

    @BeforeEach
    void setUp() {
        rateLimitHandler = new PosRateLimitHandler();
    }

    @Test
    @DisplayName("1. Recording a rate limit marks endpoint throttled with active wait seconds")
    void testRecordRateLimitActivatesCooldown() {
        rateLimitHandler.recordRateLimit("auth_login", 30);

        assertTrue(rateLimitHandler.isThrottled("auth_login"));
        long remaining = rateLimitHandler.getRemainingWaitSeconds("auth_login");
        assertTrue(remaining >= 29 && remaining <= 30);
    }

    @Test
    @DisplayName("2. Bilingual message resolution produces accurate English and Bangla countdowns")
    void testBilingualStatusMessages() {
        rateLimitHandler.recordRateLimit("sensitive_mutation", 45);

        String msgEn = rateLimitHandler.getStatusMessage("sensitive_mutation", "en");
        String msgBn = rateLimitHandler.getStatusMessage("sensitive_mutation", "bn");

        assertTrue(msgEn.contains("Request rate limit exceeded. Please retry after"));
        assertTrue(msgBn.contains("অনুরোধের সীমা অতিক্রম করেছে। অনুগ্রহ করে"));
        assertTrue(msgBn.contains("সেকেন্ড পর পুনরায় চেষ্টা করুন।"));
    }

    @Test
    @DisplayName("3. Unthrottled endpoints return false and 0 wait seconds")
    void testUnthrottledEndpointReturnsDefault() {
        assertFalse(rateLimitHandler.isThrottled("data_export"));
        assertEquals(0, rateLimitHandler.getRemainingWaitSeconds("data_export"));
    }

    @Test
    @DisplayName("4. Clearing throttle restores immediate access")
    void testClearThrottleRestoresAccess() {
        rateLimitHandler.recordRateLimit("checkout_mutation", 60);
        assertTrue(rateLimitHandler.isThrottled("checkout_mutation"));

        rateLimitHandler.clearThrottle("checkout_mutation");
        assertFalse(rateLimitHandler.isThrottled("checkout_mutation"));
        assertEquals(0, rateLimitHandler.getRemainingWaitSeconds("checkout_mutation"));
    }

    @Test
    @DisplayName("5. Negative wait duration throws IllegalArgumentException")
    void testNegativeWaitSecondsThrowsException() {
        assertThrows(IllegalArgumentException.class, () ->
                new PosRateLimitHandler.RateLimitEntry("test_route", -5, System.currentTimeMillis())
        );
    }
}
