package com.pharmacy.pos.billing;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P097: JavaFX Desktop POS Customer Portal Client Tests")
class PosCustomerPortalClientTest {

    @Test
    @DisplayName("evaluatePortalOverview evaluates active package, renewal schedule, and bilingual text")
    void testActivePortalOverview() {
        var overview = PosCustomerPortalClient.evaluatePortalOverview(
                "tenant-101",
                "PLAN_STANDARD",
                "STANDARD",
                "Standard Pharmacy Plan",
                "ACTIVE",
                new BigDecimal("3500.00"),
                "2026-12-01",
                30,
                true,
                3,
                2,
                "OK"
        );

        assertEquals("tenant-101", overview.tenantId);
        assertTrue(overview.activePackage.isActive());
        assertFalse(overview.activePackage.isGracePeriod());
        assertEquals("STANDARD", overview.activePackage.planTier);
        assertEquals(new BigDecimal("3500.00"), overview.activePackage.priceBdt);
        assertTrue(overview.activePackage.autoRenew);

        assertFalse(overview.nextRenewal.isDueSoon());
        assertEquals(30, overview.nextRenewal.daysRemaining);
        assertEquals("2026-12-01", overview.nextRenewal.renewalDate);

        assertEquals(3, overview.invoicesCount);
        assertEquals(2, overview.paymentsCount);
        assertEquals("OK", overview.quotaStatus);

        assertTrue(overview.headlineEn.contains("Subscription Active"));
        assertTrue(overview.headlineBn.contains("সাবস্ক্রিপশন সক্রিয়"));
        assertTrue(overview.detailsBn.contains("30 দিন"));
    }

    @Test
    @DisplayName("NextRenewal detects when renewal is due soon (<= 7 days)")
    void testRenewalDueSoon() {
        var dueSoon = PosCustomerPortalClient.evaluatePortalOverview(
                "tenant-101", "PLAN_BASIC", "BASIC", "Basic Plan", "ACTIVE",
                new BigDecimal("1500.00"), "2026-11-05", 4, true, 1, 1, "OK"
        );

        assertTrue(dueSoon.nextRenewal.isDueSoon());
        assertEquals(4, dueSoon.nextRenewal.daysRemaining);
    }

    @Test
    @DisplayName("Evaluates grace period and expired subscription banners accurately")
    void testGracePeriodAndExpiredStates() {
        // Grace Period
        var grace = PosCustomerPortalClient.evaluatePortalOverview(
                "tenant-102", "PLAN_PRO", "PRO", "Pro Plan", "GRACE_PERIOD",
                new BigDecimal("8000.00"), "2026-11-01", 0, false, 5, 4, "NEAR_LIMIT"
        );
        assertTrue(grace.activePackage.isGracePeriod());
        assertTrue(grace.headlineEn.contains("Grace Period"));
        assertTrue(grace.headlineBn.contains("গ্রেস পিরিয়ডে রয়েছে"));

        // Expired
        var expired = PosCustomerPortalClient.evaluatePortalOverview(
                "tenant-103", "PLAN_BASIC", "BASIC", "Basic Plan", "EXPIRED",
                new BigDecimal("1500.00"), "2026-10-31", 0, false, 2, 2, "OVER_QUOTA"
        );
        assertFalse(expired.activePackage.isActive());
        assertTrue(expired.headlineEn.contains("Subscription Expired"));
        assertTrue(expired.headlineBn.contains("মেয়াদ উত্তীর্ণ হয়েছে"));
    }
}

