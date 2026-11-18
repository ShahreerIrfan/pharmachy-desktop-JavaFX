package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OfflinePrescriptionPolicyGuardTest (P311 / ADR-0311)
 * Verifies fail-closed offline prescription checking, controlled/narcotic blocking,
 * minimal-PHI signed token caching, over-quantity blocking, and instant tombstone revocation.
 */
class OfflinePrescriptionPolicyGuardTest {

    @Test
    void testFailClosedRxVerificationNarcoticBlockAndRevocation(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("pos_p311_rx.db");
        String fp = "4".repeat(64);
        String rxHash = "a".repeat(64);
        Instant now = Instant.parse("2026-11-18T12:00:00Z");
        String expIso = now.plus(6, ChronoUnit.HOURS).toString();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            DeviceLocalDatabaseSchema.initializeAndBind(
                    conn, "DEV-RX-01", "ten-rx-1", "br-rx-1", fp, "CONTROLLED_RECONCILIATION");

            // 1. Controlled narcotic ALWAYS fails closed
            var narcRes = OfflinePrescriptionPolicyGuard.verifyAndConsumeOfflineRx(
                    conn, "ten-rx-1", "br-rx-1", "DEV-RX-01", "med-narc", "CONTROLLED_NARCOTIC", "tok-any", 1, now);
            assertFalse(narcRes.allowed);
            assertEquals("ERR_CONTROLLED_NARCOTIC_OFFLINE_FORBIDDEN", narcRes.reasonCode);

            // 2. Unverified / missing token fails closed
            var missRes = OfflinePrescriptionPolicyGuard.verifyAndConsumeOfflineRx(
                    conn, "ten-rx-1", "br-rx-1", "DEV-RX-01", "med-abx", "ANTIMICROBIAL_RX", "tok-missing", 2, now);
            assertFalse(missRes.allowed);
            assertEquals("ERR_FAIL_CLOSED_TOKEN_MISSING", missRes.reasonCode);

            // 3. Invalidly signed token refused from local cache
            assertThrows(SecurityException.class, () ->
                    OfflinePrescriptionPolicyGuard.cachePolicyApprovedToken(
                            conn, "orxtok-1", "ten-rx-1", "br-rx-1", "DEV-RX-01", "med-abx",
                            "ANTIMICROBIAL_RX", rxHash, 6, "0".repeat(64), expIso));

            // 4. Cache valid signed antimicrobial token & consume 4 of 6 units
            String validSig = OfflinePrescriptionPolicyGuard.computeSignature(
                    "ten-rx-1", "br-rx-1", "DEV-RX-01", "med-abx", "ANTIMICROBIAL_RX", rxHash, 6, expIso);
            OfflinePrescriptionPolicyGuard.cachePolicyApprovedToken(
                    conn, "orxtok-1", "ten-rx-1", "br-rx-1", "DEV-RX-01", "med-abx",
                    "ANTIMICROBIAL_RX", rxHash, 6, validSig, expIso);

            var ok = OfflinePrescriptionPolicyGuard.verifyAndConsumeOfflineRx(
                    conn, "ten-rx-1", "br-rx-1", "DEV-RX-01", "med-abx", "ANTIMICROBIAL_RX", "orxtok-1", 4, now);
            assertTrue(ok.allowed);
            assertTrue(ok.antimicrobialRegisterRequired);

            // 5. Over-quantity (3 > remaining 2) blocked
            var over = OfflinePrescriptionPolicyGuard.verifyAndConsumeOfflineRx(
                    conn, "ten-rx-1", "br-rx-1", "DEV-RX-01", "med-abx", "ANTIMICROBIAL_RX", "orxtok-1", 3, now);
            assertFalse(over.allowed);
            assertEquals("ERR_RX_APPROVED_QTY_EXCEEDED", over.reasonCode);

            // 6. Expired timestamp blocked
            var exp = OfflinePrescriptionPolicyGuard.verifyAndConsumeOfflineRx(
                    conn, "ten-rx-1", "br-rx-1", "DEV-RX-01", "med-abx", "ANTIMICROBIAL_RX", "orxtok-1", 1,
                    now.plus(7, ChronoUnit.HOURS));
            assertFalse(exp.allowed);
            assertEquals("ERR_RX_TOKEN_EXPIRED", exp.reasonCode);

            // 7. Revocation tombstone blocks remaining units immediately
            OfflinePrescriptionPolicyGuard.revokeCachedToken(conn, "ten-rx-1", "orxtok-1");
            var rev = OfflinePrescriptionPolicyGuard.verifyAndConsumeOfflineRx(
                    conn, "ten-rx-1", "br-rx-1", "DEV-RX-01", "med-abx", "ANTIMICROBIAL_RX", "orxtok-1", 1, now);
            assertFalse(rev.allowed);
            assertEquals("ERR_RX_TOKEN_INACTIVE", rev.reasonCode);
        }
    }
}
