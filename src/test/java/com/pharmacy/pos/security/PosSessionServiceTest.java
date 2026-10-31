package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P045: Desktop POS Session Management & Token Rotation Tests")
class PosSessionServiceTest {

    @Test
    @DisplayName("Validates device binding match and mismatch")
    void testDeviceBindingValidation() {
        assertTrue(PosSessionService.validateDeviceBinding("pos-terminal-01", "pos-terminal-01"));
        assertTrue(PosSessionService.validateDeviceBinding("POS-TERMINAL-01", "pos-terminal-01"));

        assertFalse(PosSessionService.validateDeviceBinding("pos-terminal-01", "pos-terminal-02"));
        assertFalse(PosSessionService.validateDeviceBinding("pos-terminal-01", null));
        assertFalse(PosSessionService.validateDeviceBinding(null, "pos-terminal-01"));
    }

    @Test
    @DisplayName("Identifies token replay error code accurately")
    void testReplayErrorIdentification() {
        assertTrue(PosSessionService.isReplayError("TOKEN_REPLAY_DETECTED"));
        assertTrue(PosSessionService.isReplayError("token_replay_detected"));

        assertFalse(PosSessionService.isReplayError("INVALID_CREDENTIALS"));
        assertFalse(PosSessionService.isReplayError(null));
    }

    @Test
    @DisplayName("Parses PosSession from JSON payload conforming to ADR-0045")
    void testParseSessionPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("session_id", "45a0b123-0123-4567-89ab-cdef01234567");
        payload.put("user_id", "88b560ce-0123-4567-89ab-cdef01234567");
        payload.put("tenant_id", "tenant-dhanmondi-rx");
        payload.put("device_id", "pos-term-counter-1");
        payload.put("device_name", "Counter 1 Dell All-in-One");
        payload.put("client_platform", "DESKTOP_POS");
        payload.put("ip_address", "192.168.1.101");
        payload.put("is_active", true);
        payload.put("is_current", true);
        payload.put("last_seen_at", "2026-10-31T06:30:00Z");
        payload.put("created_at", "2026-10-31T06:00:00Z");

        PosSessionService.PosSession session = PosSessionService.parseSession(payload);
        assertEquals("45a0b123-0123-4567-89ab-cdef01234567", session.sessionId());
        assertEquals("tenant-dhanmondi-rx", session.tenantId());
        assertEquals("pos-term-counter-1", session.deviceId());
        assertTrue(session.isActive());
        assertTrue(session.isCurrent());
    }

    @Test
    @DisplayName("Produces accurate bilingual error messages for replay and cross-tenant access")
    void testBilingualErrorMessages() {
        Map<String, String> replayMsg = PosSessionService.getBilingualErrorMessage("TOKEN_REPLAY_DETECTED");
        assertNotNull(replayMsg.get("en"));
        assertNotNull(replayMsg.get("bn"));
        assertTrue(replayMsg.get("en").contains("Token replay attack detected"));
        assertTrue(replayMsg.get("bn").contains("টোকেন রিপ্লে আক্রমণ"));

        Map<String, String> crossTenantMsg = PosSessionService.getBilingualErrorMessage("CROSS_TENANT_SESSION_ACCESS_DENIED");
        assertTrue(crossTenantMsg.get("en").contains("Cross-tenant"));
        assertTrue(crossTenantMsg.get("bn").contains("ক্রস-টেন্যান্ট"));
    }
}

