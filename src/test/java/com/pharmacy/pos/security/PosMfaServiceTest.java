package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P044: Desktop POS MFA Service & Privilege Guard Tests")
class PosMfaServiceTest {

    @Test
    @DisplayName("Privileged roles correctly require mandatory MFA")
    void testPrivilegedRoleIdentification() {
        assertTrue(PosMfaService.isPrivilegedRole("SUPERADMIN"), "SUPERADMIN must be privileged");
        assertTrue(PosMfaService.isPrivilegedRole("PHARMACY_OWNER"), "PHARMACY_OWNER must be privileged");
        assertTrue(PosMfaService.isPrivilegedRole("BRANCH_MANAGER"), "BRANCH_MANAGER must be privileged");
        assertTrue(PosMfaService.isPrivilegedRole("FINANCE_OFFICER"), "FINANCE_OFFICER must be privileged");
        assertTrue(PosMfaService.isPrivilegedRole("PLATFORM_OPERATOR"), "PLATFORM_OPERATOR must be privileged");

        assertFalse(PosMfaService.isPrivilegedRole("CASHIER"), "CASHIER must not be classified as privileged");
        assertFalse(PosMfaService.isPrivilegedRole("INVENTORY_CLERK"), "INVENTORY_CLERK must not be privileged");
        assertFalse(PosMfaService.isPrivilegedRole(null), "Null role returns false");
    }

    @Test
    @DisplayName("Validates 6-digit TOTP format and rejects malformed inputs")
    void testTotpFormatValidation() {
        assertTrue(PosMfaService.validateTotpFormat("123456"));
        assertTrue(PosMfaService.validateTotpFormat("000000"));
        assertTrue(PosMfaService.validateTotpFormat("999999"));

        assertFalse(PosMfaService.validateTotpFormat("12345"), "5 digits must be rejected");
        assertFalse(PosMfaService.validateTotpFormat("1234567"), "7 digits must be rejected");
        assertFalse(PosMfaService.validateTotpFormat("12345a"), "Non-digits must be rejected");
        assertFalse(PosMfaService.validateTotpFormat(""), "Empty code rejected");
        assertFalse(PosMfaService.validateTotpFormat(null), "Null code rejected");
    }

    @Test
    @DisplayName("Validates backup recovery code format (XXXX-XXXX)")
    void testRecoveryCodeFormatValidation() {
        assertTrue(PosMfaService.validateRecoveryCodeFormat("ABCD-1234"));
        assertTrue(PosMfaService.validateRecoveryCodeFormat("1A2B-3C4D"));
        assertTrue(PosMfaService.validateRecoveryCodeFormat("ffff-0000"));

        assertFalse(PosMfaService.validateRecoveryCodeFormat("ABCD1234"), "Missing hyphen rejected");
        assertFalse(PosMfaService.validateRecoveryCodeFormat("ABC-1234"), "Too short rejected");
        assertFalse(PosMfaService.validateRecoveryCodeFormat("ABCDE-12345"), "Too long rejected");
        assertFalse(PosMfaService.validateRecoveryCodeFormat(null), "Null code rejected");
    }

    @Test
    @DisplayName("Lockout evaluated at 5 or more failed attempts")
    void testLockoutEvaluation() {
        assertFalse(PosMfaService.isLockedOut(0));
        assertFalse(PosMfaService.isLockedOut(4));
        assertTrue(PosMfaService.isLockedOut(5));
        assertTrue(PosMfaService.isLockedOut(6));
    }

    @Test
    @DisplayName("Parses MFA challenge payload conforming to ADR-0044")
    void testParseChallengePayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("mfa_required", true);
        payload.put("challenge_token", "sec_tok_99182736451234567890abcdef12");
        payload.put("expires_in", 300);
        payload.put("method", "TOTP");
        payload.put("user_id", "4b104dc2-6b99-4d69-b4b1-e2cf4b971a81");
        payload.put("email", "owner@dhanmondirx.bd");
        payload.put("role", "PHARMACY_OWNER");
        payload.put("remaining_attempts", 5);

        PosMfaService.MfaChallenge challenge = PosMfaService.parseChallenge(payload);
        assertTrue(challenge.mfaRequired());
        assertEquals("sec_tok_99182736451234567890abcdef12", challenge.challengeToken());
        assertEquals(300, challenge.expiresIn());
        assertEquals("owner@dhanmondirx.bd", challenge.email());
        assertEquals("PHARMACY_OWNER", challenge.role());
        assertEquals(5, challenge.remainingAttempts());
    }

    @Test
    @DisplayName("Produces accurate bilingual error messages")
    void testBilingualErrorMessages() {
        Map<String, String> lockoutMsg = PosMfaService.getBilingualErrorMessage("MFA_LOCKED_OUT");
        assertNotNull(lockoutMsg.get("en"));
        assertNotNull(lockoutMsg.get("bn"));
        assertTrue(lockoutMsg.get("bn").contains("লক"));

        Map<String, String> mandatoryMsg = PosMfaService.getBilingualErrorMessage("MFA_MANDATORY_FOR_PRIVILEGED_ROLE");
        assertTrue(mandatoryMsg.get("en").contains("mandatory"));
        assertTrue(mandatoryMsg.get("bn").contains("বাধ্যতামূলক"));
    }
}

