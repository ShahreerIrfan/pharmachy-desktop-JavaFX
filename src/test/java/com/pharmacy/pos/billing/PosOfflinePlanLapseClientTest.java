package com.pharmacy.pos.billing;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P094: JavaFX Desktop POS Offline Plan Lapse Client Tests")
class PosOfflinePlanLapseClientTest {

    @Test
    @DisplayName("Active subscription with valid lease permits dispensing and reports operational status")
    void testActiveSubscriptionPermitsDispensing() {
        var state = PosOfflinePlanLapseClient.evaluatePlanLapseState(
                "ACTIVE",
                false,
                0,
                BigDecimal.ZERO
        );

        assertFalse(state.isMutationPaused);
        assertEquals(0, state.pendingHeldCount);
        assertTrue(state.zeroDataLossPreserved);
        assertEquals("NONE", state.actionPrompt);
        assertTrue(state.headlineBn.contains("টার্মিনাল সচল রয়েছে"));
    }

    @Test
    @DisplayName("Expired lease pauses local counter mutations while preserving queued transactions")
    void testExpiredLeasePausesMutationsAndPreservesOutbox() {
        BigDecimal totalHeld = new BigDecimal("4850.50");
        int heldCount = 14;

        var state = PosOfflinePlanLapseClient.evaluatePlanLapseState(
                "ACTIVE",
                true, // lease expired!
                heldCount,
                totalHeld
        );

        assertTrue(state.isMutationPaused);
        assertEquals(heldCount, state.pendingHeldCount);
        assertEquals(new BigDecimal("4850.50"), state.pendingTotalBdt);
        assertTrue(state.zeroDataLossPreserved);
        assertEquals("SETTLE_RENEWAL", state.actionPrompt);
        assertTrue(state.headlineBn.contains("কাউন্টার বিক্রয় স্থগিত"));
        assertTrue(state.headlineBn.contains("14 টি লেনদেন"));
        assertTrue(state.detailsBn.contains("4850.50"));
    }

    @Test
    @DisplayName("Expired subscription status triggers deterministic mutation pause")
    void testExpiredSubscriptionStatusTriggersPause() {
        var state = PosOfflinePlanLapseClient.evaluatePlanLapseState(
                "EXPIRED",
                false,
                5,
                new BigDecimal("1200.00")
        );

        assertTrue(state.isMutationPaused);
        assertEquals(5, state.pendingHeldCount);
        assertTrue(state.headlineEn.contains("5 Transactions Held"));
        assertTrue(state.headlineBn.contains("৫ টি লেনদেন জমা রয়েছে") || state.headlineBn.contains("5 টি লেনদেন"));
    }

    @Test
    @DisplayName("Zero data loss invariant remains true under all plan lapse states")
    void testZeroDataLossInvariant() {
        for (String status : new String[]{"EXPIRED", "CANCELLED", "SUSPENDED", "TRIAL", "ACTIVE"}) {
            var state = PosOfflinePlanLapseClient.evaluatePlanLapseState(status, true, 2, BigDecimal.TEN);
            assertTrue(state.zeroDataLossPreserved, "Zero data loss must be true for " + status);
        }
    }
}
