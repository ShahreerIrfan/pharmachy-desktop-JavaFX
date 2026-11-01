package com.pharmacy.pos.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P086: JavaFX Desktop POS Trial Policy Client Tests")
public class PosTrialPolicyClientTest {

    @Test
    @DisplayName("Active trial with > 3 days returns ACTIVE_TRIAL with writes permitted")
    void testActiveTrialEvaluation() {
        Instant now = Instant.parse("2026-11-01T12:00:00Z");
        Instant trialEnd = now.plus(10, ChronoUnit.DAYS);

        PosTrialPolicyClient.TrialEvaluationResult res = PosTrialPolicyClient.evaluate(
            "t-desk-trial-01",
            true,
            trialEnd,
            now
        );

        assertTrue(res.isTrial);
        assertEquals(PosTrialPolicyClient.TrialState.ACTIVE_TRIAL, res.trialState);
        assertEquals(10, res.daysRemaining);
        assertFalse(res.isExpired);
        assertTrue(res.isWritePermitted);
        assertTrue(res.nonDestructiveGuarantee);
        assertEquals(1, res.maxBranches);
        assertEquals(2, res.maxDevices);
    }

    @Test
    @DisplayName("Trial expiring within 1-3 days returns EXPIRING_SOON warning")
    void testExpiringSoonTrialEvaluation() {
        Instant now = Instant.parse("2026-11-01T12:00:00Z");
        Instant trialEnd = now.plus(2, ChronoUnit.DAYS);

        PosTrialPolicyClient.TrialEvaluationResult res = PosTrialPolicyClient.evaluate(
            "t-desk-trial-02",
            true,
            trialEnd,
            now
        );

        assertTrue(res.isTrial);
        assertEquals(PosTrialPolicyClient.TrialState.EXPIRING_SOON, res.trialState);
        assertEquals(2, res.daysRemaining);
        assertFalse(res.isExpired);
        assertTrue(res.isWritePermitted);
        assertTrue(res.noticeEn.contains("Urgent"));
    }

    @Test
    @DisplayName("Expired trial returns EXPIRED, locks writes, and guarantees non-destructive retention")
    void testExpiredTrialEvaluation() {
        Instant now = Instant.parse("2026-11-01T12:00:00Z");
        Instant trialEnd = now.minus(1, ChronoUnit.DAYS);

        PosTrialPolicyClient.TrialEvaluationResult res = PosTrialPolicyClient.evaluate(
            "t-desk-trial-03",
            true,
            trialEnd,
            now
        );

        assertTrue(res.isTrial);
        assertEquals(PosTrialPolicyClient.TrialState.EXPIRED, res.trialState);
        assertEquals(0, res.daysRemaining);
        assertTrue(res.isExpired);
        assertFalse(res.isWritePermitted);
        assertTrue(res.nonDestructiveGuarantee);
        assertTrue(res.noticeEn.contains("locked"));
    }

    @Test
    @DisplayName("Commercial non-trial tenant returns NON_TRIAL with standard quotas")
    void testNonTrialEvaluation() {
        Instant now = Instant.now();
        PosTrialPolicyClient.TrialEvaluationResult res = PosTrialPolicyClient.evaluate(
            "t-desk-commercial-01",
            false,
            null,
            now
        );

        assertFalse(res.isTrial);
        assertEquals(PosTrialPolicyClient.TrialState.NON_TRIAL, res.trialState);
        assertTrue(res.isWritePermitted);
        assertFalse(res.isExpired);
    }
}
