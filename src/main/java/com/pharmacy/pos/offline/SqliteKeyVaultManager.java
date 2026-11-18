package com.pharmacy.pos.offline;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * SqliteKeyVaultManager
 * Architecture Decision: ADR-0302 (P302).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Manages local SQLite Data Encryption Keys (DEK) with standard JCA primitives:
 * - 256-bit cryptographically random DEK generation via SecureRandom.
 * - Key Wrapping (KEK derivation via PBKDF2WithHmacSHA256 120,000 iterations + AES-256-GCM authenticated wrapping).
 * - Sensitive column payload encryption/decryption (AES/GCM/NoPadding, 96-bit IV, 128-bit auth tag).
 * - SQLCipher PRAGMA key / PRAGMA rekey hex literal formatting for native SQLCipher drivers.
 * - Explicit in-memory zeroing of sensitive byte arrays after use.
 */
public class SqliteKeyVaultManager {

    private static final int DEK_BYTES = 32; // 256-bit
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static byte[] generateDek256() {
        byte[] dek = new byte[DEK_BYTES];
        SECURE_RANDOM.nextBytes(dek);
        return dek;
    }

    public static byte[] generateSalt16() {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        return salt;
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return toHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static SecretKey deriveKek(char[] passphrase, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, 256);
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] encoded = skf.generateSecret(spec).getEncoded();
            return new SecretKeySpec(encoded, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * Wraps a 256-bit DEK into a self-contained Base64 AES-256-GCM envelope (v1:salt:iv:ciphertext).
     */
    public static String wrapDekWithPassphrase(byte[] dek, char[] passphrase) throws GeneralSecurityException {
        if (dek == null || dek.length != DEK_BYTES) {
            throw new IllegalArgumentException("ERR_INVALID_DEK_LENGTH: Expected 32-byte (256-bit) DEK.");
        }
        byte[] salt = generateSalt16();
        byte[] iv = new byte[GCM_IV_BYTES];
        SECURE_RANDOM.nextBytes(iv);

        SecretKey kek = deriveKek(passphrase, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(dek);

        Base64.Encoder enc = Base64.getEncoder();
        return "v1:" + enc.encodeToString(salt) + ":" + enc.encodeToString(iv) + ":" + enc.encodeToString(ciphertext);
    }

    /**
     * Unwraps a v1 AES-256-GCM DEK envelope; throws AEADBadTagException on wrong passphrase or tampering.
     */
    public static byte[] unwrapDekWithPassphrase(String wrappedBlob, char[] passphrase) throws GeneralSecurityException {
        if (wrappedBlob == null || !wrappedBlob.startsWith("v1:")) {
            throw new IllegalArgumentException("ERR_INVALID_WRAPPED_ENVELOPE: Unsupported envelope format.");
        }
        String[] parts = wrappedBlob.split(":");
        if (parts.length != 4) {
            throw new IllegalArgumentException("ERR_MALFORMED_ENVELOPE: Expected 4 envelope segments.");
        }
        Base64.Decoder dec = Base64.getDecoder();
        byte[] salt = dec.decode(parts[1]);
        byte[] iv = dec.decode(parts[2]);
        byte[] ciphertext = dec.decode(parts[3]);

        SecretKey kek = deriveKek(passphrase, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    public static String encryptTextWithDek(String plaintext, byte[] dek) throws GeneralSecurityException {
        byte[] iv = new byte[GCM_IV_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder enc = Base64.getEncoder();
        return "enc1:" + enc.encodeToString(iv) + ":" + enc.encodeToString(ct);
    }

    public static String decryptTextWithDek(String ciphertextBlob, byte[] dek) throws GeneralSecurityException {
        String[] parts = ciphertextBlob.split(":");
        if (parts.length != 3 || !"enc1".equals(parts[0])) {
            throw new IllegalArgumentException("ERR_INVALID_CIPHERTEXT_BLOB");
        }
        Base64.Decoder dec = Base64.getDecoder();
        byte[] iv = dec.decode(parts[1]);
        byte[] ct = dec.decode(parts[2]);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    public static String formatSqlCipherPragmaKey(byte[] dek) {
        return "PRAGMA key = \"x'" + toHex(dek) + "'\";";
    }

    public static String formatSqlCipherPragmaRekey(byte[] newDek) {
        return "PRAGMA rekey = \"x'" + toHex(newDek) + "'\";";
    }

    public static void zeroize(byte[] sensitive) {
        if (sensitive != null) {
            Arrays.fill(sensitive, (byte) 0);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
