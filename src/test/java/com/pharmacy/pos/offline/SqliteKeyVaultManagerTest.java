package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqliteKeyVaultManagerTest (ADR-0302 / P302)
 * Verifies real AES-256-GCM DEK wrapping/unwrapping, wrong-passphrase tamper rejection,
 * sensitive payload encryption, SQLCipher PRAGMA formatting, and key zeroization.
 */
public class SqliteKeyVaultManagerTest {

    @Test
    void testDekWrapUnwrapTamperDetectionAndRotation() throws Exception {
        byte[] dekV1 = SqliteKeyVaultManager.generateDek256();
        assertEquals(32, dekV1.length);
        assertEquals(64, SqliteKeyVaultManager.sha256Hex(dekV1).length());

        char[] passphrase = "PharmacyDeviceMasterPass#2026".toCharArray();
        String wrapped = SqliteKeyVaultManager.wrapDekWithPassphrase(dekV1, passphrase);
        assertTrue(wrapped.startsWith("v1:"));

        // Valid unwrap restores exact 256-bit key
        byte[] unwrapped = SqliteKeyVaultManager.unwrapDekWithPassphrase(wrapped, "PharmacyDeviceMasterPass#2026".toCharArray());
        assertArrayEquals(dekV1, unwrapped);

        // Wrong passphrase throws AEADBadTagException
        assertThrows(AEADBadTagException.class, () ->
                SqliteKeyVaultManager.unwrapDekWithPassphrase(wrapped, "WrongPassphrase!".toCharArray())
        );

        // Encrypt & decrypt sensitive outbox payload
        String secretPayload = "{\"invoice\":\"OFF-001\",\"total_bdt\":\"450.00\"}";
        String enc = SqliteKeyVaultManager.encryptTextWithDek(secretPayload, dekV1);
        assertFalse(enc.contains("OFF-001"));
        assertEquals(secretPayload, SqliteKeyVaultManager.decryptTextWithDek(enc, unwrapped));

        // Key rotation produces distinct key & valid SQLCipher PRAGMA rekey
        byte[] dekV2 = SqliteKeyVaultManager.generateDek256();
        assertFalse(Arrays.equals(dekV1, dekV2));
        assertTrue(SqliteKeyVaultManager.formatSqlCipherPragmaRekey(dekV2).startsWith("PRAGMA rekey = \"x'"));

        // Zeroization clears memory
        SqliteKeyVaultManager.zeroize(dekV1);
        for (byte b : dekV1) {
            assertEquals(0, b);
        }
    }
}
