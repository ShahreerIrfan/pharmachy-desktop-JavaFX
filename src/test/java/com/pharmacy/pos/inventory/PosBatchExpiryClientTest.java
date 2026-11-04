package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosBatchExpiryClientTest
 * Tests for P172 configurable expiry alert windows, DGDA hard expiration barriers,
 * and POS sale cutoff guards.
 */
public class PosBatchExpiryClientTest {

    @Test
    @DisplayName("Test 1: Tier classification across all threshold windows")
    void testTierClassification() {
        LocalDate today = LocalDate.of(2026, 11, 4);
        PosBatchExpiryClient client = new PosBatchExpiryClient(
                "tenant-dhanmondi", "branch-main", "CASHIER", false
        );
        client.setPolicy(new PosBatchExpiryClient.ExpiryPolicy(90, 30, 15, 7, true, false));

        // 1. Safe: 120 days
        var safe = client.evaluateBatch("b1", "BAT-120", "Napa", today.plusDays(120), today);
        assertEquals(PosBatchExpiryClient.ExpiryTier.NORMAL_SAFE, safe.tier);
        assertFalse(safe.isSaleBlocked);

        // 2. Early Warning: 60 days
        var early = client.evaluateBatch("b2", "BAT-60", "Napa", today.plusDays(60), today);
        assertEquals(PosBatchExpiryClient.ExpiryTier.EARLY_WARNING, early.tier);
        assertFalse(early.isSaleBlocked);

        // 3. Near Expiry Critical: 25 days
        var critical = client.evaluateBatch("b3", "BAT-25", "Napa", today.plusDays(25), today);
        assertEquals(PosBatchExpiryClient.ExpiryTier.NEAR_EXPIRY_CRITICAL, critical.tier);
        assertFalse(critical.isSaleBlocked);

        // 4. Urgent Quarantine Disposition: 10 days
        var quar = client.evaluateBatch("b4", "BAT-10", "Napa", today.plusDays(10), today);
        assertEquals(PosBatchExpiryClient.ExpiryTier.QUARANTINE_DISPOSITION, quar.tier);

        // 5. Cutoff Blocked: 5 days
        var cutoff = client.evaluateBatch("b5", "BAT-5", "Napa", today.plusDays(5), today);
        assertEquals(PosBatchExpiryClient.ExpiryTier.CUTOFF_BLOCKED, cutoff.tier);
        assertTrue(cutoff.isSaleBlocked);

        // 6. Expired: -2 days
        var exp = client.evaluateBatch("b6", "BAT-EXP", "Napa", today.minusDays(2), today);
        assertEquals(PosBatchExpiryClient.ExpiryTier.EXPIRED, exp.tier);
        assertTrue(exp.isSaleBlocked);
    }

    @Test
    @DisplayName("Test 2: Hard block on expired batches (Cannot be overridden)")
    void testHardBlockOnExpiredBatches() {
        LocalDate today = LocalDate.of(2026, 11, 4);
        PosBatchExpiryClient client = new PosBatchExpiryClient(
                "tenant-dhanmondi", "branch-main", "BRANCH_MANAGER", false
        );

        var expiredBatch = client.evaluateBatch("b-exp", "BAT-EXP", "Ace", today.minusDays(1), today);
        assertEquals(PosBatchExpiryClient.ExpiryTier.EXPIRED, expiredBatch.tier);

        // Even manager authorization must fail for expired medicine (FR-019)
        var res = client.verifyBatchForSale(expiredBatch, true);
        assertFalse(res.success);
        assertEquals("ERR_BATCH_EXPIRED", res.errorCode);
    }

    @Test
    @DisplayName("Test 3: Sale cutoff guard and manager override behaviour")
    void testSaleCutoffAndOverride() {
        LocalDate today = LocalDate.of(2026, 11, 4);

        // Policy without override allowed
        PosBatchExpiryClient clientStrict = new PosBatchExpiryClient(
                "tenant-dhanmondi", "branch-main", "CASHIER", false
        );
        clientStrict.setPolicy(new PosBatchExpiryClient.ExpiryPolicy(90, 30, 15, 7, true, false));

        var cutoffBatch = clientStrict.evaluateBatch("b-cut", "BAT-CUT", "Seclo", today.plusDays(4), today);
        var resBlocked = clientStrict.verifyBatchForSale(cutoffBatch, false);
        assertFalse(resBlocked.success);
        assertEquals("ERR_SALE_CUTOFF_BREACHED", resBlocked.errorCode);

        // Policy with override enabled
        PosBatchExpiryClient clientOverride = new PosBatchExpiryClient(
                "tenant-dhanmondi", "branch-main", "BRANCH_MANAGER", false
        );
        clientOverride.setPolicy(new PosBatchExpiryClient.ExpiryPolicy(90, 30, 15, 7, true, true));

        var resApproved = clientOverride.verifyBatchForSale(cutoffBatch, true);
        assertTrue(resApproved.success);
    }

    @Test
    @DisplayName("Test 4: RBAC and suspended tenant enforcement")
    void testRbacAndSuspendedTenant() {
        LocalDate today = LocalDate.of(2026, 11, 4);
        PosBatchExpiryClient suspendedClient = new PosBatchExpiryClient(
                "tenant-dhanmondi", "branch-main", "CASHIER", true
        );
        var safeBatch = suspendedClient.evaluateBatch("b-safe", "BAT-100", "Napa", today.plusDays(100), today);

        var resSusp = suspendedClient.verifyBatchForSale(safeBatch, false);
        assertFalse(resSusp.success);
        assertEquals("ERR_TENANT_SUSPENDED", resSusp.errorCode);

        PosBatchExpiryClient unauthClient = new PosBatchExpiryClient(
                "tenant-dhanmondi", "branch-main", "DELIVERY_RIDER", false
        );
        var resUnauth = unauthClient.verifyBatchForSale(safeBatch, false);
        assertFalse(resUnauth.success);
        assertEquals("ERR_ROLE_UNAUTHORIZED", resUnauth.errorCode);
    }
}

