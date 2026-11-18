package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OfflineSubscriptionLeaseEnforcerTest (P312 / ADR-0312)
 * Proves HMAC-signed lease validation, anti-device-clock-rollback quarantine,
 * server-anchored expiration, and protected safe sync recovery availability.
 */
class OfflineSubscriptionLeaseEnforcerTest {

    @Test
    void testSignedLeaseAntiClockRollbackAndProtectedRecovery(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("pos_p312_lease.db");
        String fp = "3".repeat(64);
        String secret = "test-lease-hmac-secret-key";

        long t0 = 1_800_000_000_000L;
        long exp = t0 + (24L * 3600L * 1000L); // +24 hours
        String canonical = "ten-sl-1|br-sl-1|DEV-SL-01|PRO|" + t0 + "|" + exp;
        String validSig = OfflineSubscriptionLeaseEnforcer.computeHmacSha256(secret, canonical);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            DeviceLocalDatabaseSchema.initializeAndBind(
                    conn, "DEV-SL-01", "ten-sl-1", "br-sl-1", fp, "CONTROLLED_RECONCILIATION");

            OfflineSubscriptionLeaseEnforcer.storeSignedLease(
                    conn, "sol-1", "ten-sl-1", "br-sl-1", "DEV-SL-01", "PRO", "{}", t0, exp, validSig);

            // 1. Valid forward time (+2 hours) -> ALLOWED
            var ok = OfflineSubscriptionLeaseEnforcer.evaluateOfflineLease(
                    conn, "sol-1", secret, t0 + (2L * 3600L * 1000L));
            assertTrue(ok.newOfflineSalesAllowed);
            assertTrue(ok.protectedSyncRecoveryAllowed);

            // 2. Tampered signature check (at forward clock t0 + 3h) -> FAILS CLOSED (recovery still protected)
            OfflineSubscriptionLeaseEnforcer.storeSignedLease(
                    conn, "sol-bad", "ten-sl-1", "br-sl-1", "DEV-SL-01", "PRO", "{}", t0, exp, "0".repeat(64));
            var badSig = OfflineSubscriptionLeaseEnforcer.evaluateOfflineLease(
                    conn, "sol-bad", secret, t0 + (3L * 3600L * 1000L));
            assertFalse(badSig.newOfflineSalesAllowed);
            assertTrue(badSig.protectedSyncRecoveryAllowed);
            assertEquals("ERR_LEASE_SIGNATURE_TAMPERED", badSig.statusCode);

            // 3. Expired time (+25 hours) -> NEW SALES BLOCKED, PROTECTED RECOVERY ALLOWED!
            var expired = OfflineSubscriptionLeaseEnforcer.evaluateOfflineLease(
                    conn, "sol-1", secret, t0 + (25L * 3600L * 1000L));
            assertFalse(expired.newOfflineSalesAllowed);
            assertTrue(expired.protectedSyncRecoveryAllowed);
            assertEquals("ERR_LEASE_EXPIRED_RECOVERY_ONLY", expired.statusCode);

            // 4. ANTI-CLOCK-BYPASS DRILL: User rolls Windows clock BACK to t0 + 1h to bypass expiry -> QUARANTINED!
            var rollback = OfflineSubscriptionLeaseEnforcer.evaluateOfflineLease(
                    conn, "sol-1", secret, t0 + (1L * 3600L * 1000L));
            assertFalse(rollback.newOfflineSalesAllowed);
            assertTrue(rollback.protectedSyncRecoveryAllowed);
            assertEquals("ERR_DEVICE_CLOCK_ROLLBACK_DETECTED", rollback.statusCode);
        }
    }
}
