package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JavaFX POS Secret Vault Tests (ADR-0037)")
class PosSecretVaultTest {

    @Test
    @DisplayName("Stores secret and generates masked preview safely")
    void testStoreSecretAndMaskedPreview() {
        PosSecretVault vault = new PosSecretVault("tenant-dhaka-central", "pos-term-01");
        vault.storeSecret("TERMINAL_API_KEY", "pos-sec-live-9876543210-xyz");

        assertTrue(vault.hasSecret("TERMINAL_API_KEY"));
        assertEquals("pos-sec-live-9876543210-xyz", vault.getSecret("TERMINAL_API_KEY"));

        String preview = vault.getMaskedPreview("TERMINAL_API_KEY");
        assertEquals("pos--****--xyz", preview);
        assertFalse(preview.contains("9876543210"));
    }

    @Test
    @DisplayName("Null parameters are strictly rejected")
    void testNullParametersRejected() {
        assertThrows(NullPointerException.class, () -> new PosSecretVault(null, "pos-term-01"));
        assertThrows(NullPointerException.class, () -> new PosSecretVault("tenant-dhaka-central", null));
    }
}

