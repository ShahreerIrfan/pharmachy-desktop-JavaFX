package com.pharmacy.pos.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P095: JavaFX Desktop POS Quota Usage Counter Client Tests")
class PosQuotaUsageCounterClientTest {

    @Test
    @DisplayName("Utilization percentage calculates accurately with single-decimal rounding")
    void testCalculateUtilizationPct() {
        assertEquals(0.0, PosQuotaUsageCounterClient.calculateUtilizationPct(0, 0));
        assertEquals(0.0, PosQuotaUsageCounterClient.calculateUtilizationPct(5, 0));
        assertEquals(50.0, PosQuotaUsageCounterClient.calculateUtilizationPct(1, 2));
        assertEquals(80.0, PosQuotaUsageCounterClient.calculateUtilizationPct(4, 5));
        assertEquals(66.7, PosQuotaUsageCounterClient.calculateUtilizationPct(2, 3));
        assertEquals(100.0, PosQuotaUsageCounterClient.calculateUtilizationPct(10, 10));
        assertEquals(120.0, PosQuotaUsageCounterClient.calculateUtilizationPct(12, 10));
    }

    @Test
    @DisplayName("Resource status evaluates thresholds correctly")
    void testEvaluateResourceStatus() {
        assertEquals("NORMAL", PosQuotaUsageCounterClient.evaluateResourceStatus(3, 10));
        assertEquals("WARNING", PosQuotaUsageCounterClient.evaluateResourceStatus(8, 10));
        assertEquals("LIMIT_REACHED", PosQuotaUsageCounterClient.evaluateResourceStatus(10, 10));
        assertEquals("OVER_QUOTA", PosQuotaUsageCounterClient.evaluateResourceStatus(11, 10));
    }

    @Test
    @DisplayName("Create counter populates remaining, utilization, and isOverQuota correctly")
    void testCreateCounter() {
        var counter = PosQuotaUsageCounterClient.createCounter("STAFF_SEATS", 4, 5);
        assertEquals("STAFF_SEATS", counter.resourceType);
        assertEquals(4, counter.currentUsed);
        assertEquals(5, counter.maxLimit);
        assertEquals(1, counter.remaining);
        assertEquals(80.0, counter.utilizationPct);
        assertFalse(counter.isOverQuota);
        assertEquals("WARNING", counter.status);

        var overCounter = PosQuotaUsageCounterClient.createCounter("DEVICES", 6, 5);
        assertEquals(0, overCounter.remaining);
        assertTrue(overCounter.isOverQuota);
        assertEquals("OVER_QUOTA", overCounter.status);
    }

    @Test
    @DisplayName("Summary evaluation accurately determines overall status and bilingual messages")
    void testEvaluateQuotaSummaryStatuses() {
        // All OK
        var okReport = PosQuotaUsageCounterClient.evaluateQuotaSummary(
                "tenant-101", "STANDARD", 1, 3, 2, 5, 2, 5
        );
        assertEquals("OK", okReport.overallStatus);
        assertTrue(okReport.headlineBn.contains("স্বাভাবিক রয়েছে"));
        assertTrue(okReport.headlineEn.contains("Good Standing"));

        // Near limit (Warning)
        var warnReport = PosQuotaUsageCounterClient.evaluateQuotaSummary(
                "tenant-101", "STANDARD", 1, 3, 4, 5, 2, 5 // 4/5 = 80%
        );
        assertEquals("NEAR_LIMIT", warnReport.overallStatus);
        assertTrue(warnReport.headlineBn.contains("কাছাকাছি পৌঁছেছে"));

        // Limit reached
        var limitReport = PosQuotaUsageCounterClient.evaluateQuotaSummary(
                "tenant-101", "STANDARD", 1, 3, 5, 5, 2, 5 // 5/5 = 100%
        );
        assertEquals("LIMIT_REACHED", limitReport.overallStatus);
        assertTrue(limitReport.headlineBn.contains("কোটা সীমা পূর্ণ হয়েছে"));

        // Over quota
        var overReport = PosQuotaUsageCounterClient.evaluateQuotaSummary(
                "tenant-101", "STANDARD", 1, 3, 6, 5, 2, 5 // 6/5 = over
        );
        assertEquals("OVER_QUOTA", overReport.overallStatus);
        assertTrue(overReport.headlineBn.contains("সাবস্ক্রিপশন কোটা অতিক্রম করেছে"));
        assertTrue(overReport.headlineEn.contains("Plan Quota Exceeded"));
    }
}

