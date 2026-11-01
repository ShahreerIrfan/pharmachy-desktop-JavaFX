package com.pharmacy.pos.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P091: JavaFX Desktop POS Downgrade and Over-Quota Client Tests")
public class PosDowngradeAndQuotaClientTest {

    @Test
    @DisplayName("Downgrade eligibility admits lower tiers and annual-to-monthly cycles, rejects upgrades")
    void testDowngradeEligibility() {
        // Lower tiers
        assertTrue(PosDowngradeAndQuotaClient.isDowngradeEligible("PRO", "MONTHLY", "STANDARD", "MONTHLY"));
        assertTrue(PosDowngradeAndQuotaClient.isDowngradeEligible("PRO", "MONTHLY", "BASIC", "MONTHLY"));
        assertTrue(PosDowngradeAndQuotaClient.isDowngradeEligible("STANDARD", "MONTHLY", "BASIC", "MONTHLY"));

        // Same tier, annual to monthly
        assertTrue(PosDowngradeAndQuotaClient.isDowngradeEligible("STANDARD", "ANNUAL", "STANDARD", "MONTHLY"));

        // Upgrades rejected
        assertFalse(PosDowngradeAndQuotaClient.isDowngradeEligible("BASIC", "MONTHLY", "STANDARD", "MONTHLY"));
        assertFalse(PosDowngradeAndQuotaClient.isDowngradeEligible("STANDARD", "MONTHLY", "PRO", "MONTHLY"));
        assertFalse(PosDowngradeAndQuotaClient.isDowngradeEligible("STANDARD", "MONTHLY", "STANDARD", "ANNUAL"));

        // Identical rejected
        assertFalse(PosDowngradeAndQuotaClient.isDowngradeEligible("STANDARD", "MONTHLY", "STANDARD", "MONTHLY"));
    }

    @Test
    @DisplayName("Assessment accurately detects over-quota branches and partitions retained vs locked without data deletion")
    void testAssessDowngradeOverQuota() {
        // Tenant currently has 3 branches on STANDARD, downgrades to BASIC (quota = 1)
        List<String> branches = Arrays.asList("branch-main", "branch-north", "branch-east");

        PosDowngradeAndQuotaClient.DowngradeAssessmentResult assessment =
                PosDowngradeAndQuotaClient.assessDowngrade(
                        "STANDARD", "MONTHLY",
                        "BASIC", "MONTHLY",
                        branches,
                        1
                );

        assertTrue(assessment.isValid);
        assertTrue(assessment.isOverQuota);
        assertEquals(2, assessment.excessBranchesCount);
        assertEquals(1, assessment.targetQuota);

        // Retained: branch-main (1 branch)
        assertEquals(Collections.singletonList("branch-main"), assessment.retainedBranchIds);

        // Locked: branch-north, branch-east (2 branches)
        assertEquals(Arrays.asList("branch-north", "branch-east"), assessment.lockedBranchIds);

        // Zero data deleted guarantee must be true
        assertTrue(assessment.zeroDataDeleted);
    }

    @Test
    @DisplayName("Bilingual notices articulate zero data deletion and mutation gating properly")
    void testBilingualNotices() {
        assertEquals(
                "কোনো ব্রাঞ্চ, স্টাফ বা পূর্ববর্তী বিক্রয় ও স্টক ডেটা মুছে ফেলা হবে না।",
                PosDowngradeAndQuotaClient.getLocalizedNotice("DATA_RETENTION", true)
        );
        assertEquals(
                "Zero data deletion: branches, staff, transactions and stock remain intact.",
                PosDowngradeAndQuotaClient.getLocalizedNotice("DATA_RETENTION", false)
        );
        assertEquals(
                "কোটা অতিক্রম করায় নতুন কোনো সেলস ট্রানজ্যাকশন অনুমোদিত নয়।",
                PosDowngradeAndQuotaClient.getLocalizedNotice("MUTATION_GATED", true)
        );
    }
}
