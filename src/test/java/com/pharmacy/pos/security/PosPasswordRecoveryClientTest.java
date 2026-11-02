package com.pharmacy.pos.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for P114 PosPasswordRecoveryClient (ADR-0114).
 */
public class PosPasswordRecoveryClientTest {

    private PosPasswordRecoveryClient client;

    @BeforeEach
    void setUp() {
        client = new PosPasswordRecoveryClient();
    }

    @Test
    @DisplayName("P114: Strong password passes all 5 complexity criteria")
    void testStrongPassword() {
        var res = client.evaluatePasswordStrength("SecurePass123!@#");
        assertTrue(res.isValid);
        assertEquals(5, res.score);
        assertTrue(res.missingRequirements.isEmpty());
    }

    @Test
    @DisplayName("P114: Weak password missing uppercase, number, or special char is identified")
    void testWeakPassword() {
        var res = client.evaluatePasswordStrength("weakpass");
        assertFalse(res.isValid);
        assertTrue(res.missingRequirements.contains("Must contain at least 1 uppercase letter"));
        assertTrue(res.missingRequirements.contains("Must contain at least 1 number"));
        assertTrue(res.missingRequirements.contains("Must contain at least 1 special character"));
    }

    @Test
    @DisplayName("P114: Blank reset identifier triggers client validation error")
    void testBlankIdentifier() {
        var res = client.validateInputForResetRequest("  ");
        assertEquals(PosPasswordRecoveryClient.UiStatus.ERROR, res.uiStatus);
        assertEquals("INVALID_PAYLOAD", res.errorCode);
    }

    @Test
    @DisplayName("P114: Password confirmation with valid token and strong password succeeds")
    void testValidConfirmation() {
        var res = client.validateConfirmation("valid-token-123", "StrongPass2026!#");
        assertEquals(PosPasswordRecoveryClient.UiStatus.SUCCESS, res.uiStatus);
        assertEquals("SUCCESS", res.status);
    }

    @Test
    @DisplayName("P114: Password confirmation with weak password fails with ERR_WEAK_PASSWORD")
    void testWeakPasswordConfirmationFails() {
        var res = client.validateConfirmation("valid-token-123", "1234");
        assertEquals(PosPasswordRecoveryClient.UiStatus.ERROR, res.uiStatus);
        assertEquals("ERR_WEAK_PASSWORD", res.errorCode);
    }
}

