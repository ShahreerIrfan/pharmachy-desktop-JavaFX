package com.pharmacy.pos.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

public class PlatformBillingReportClientTest {

    @Test
    public void test01MrrAndArrCalculation() {
        PlatformBillingReportClient.MrrSummary mrr = new PlatformBillingReportClient.MrrSummary(
                new BigDecimal("50000.00"),
                new BigDecimal("600000.00"),
                new BigDecimal("2500.00"),
                20,
                20,
                new BigDecimal("10000.00"),
                new BigDecimal("20000.00"),
                new BigDecimal("20000.00")
        );

        assertEquals("৳50000.00", PlatformBillingReportClient.formatBdt(mrr.totalMrrBdt));
        assertEquals("৳600000.00", PlatformBillingReportClient.formatBdt(mrr.totalArrBdt));
        assertEquals("৳2500.00", PlatformBillingReportClient.formatBdt(mrr.arpuBdt));
        assertEquals(20, mrr.activeSubscriptionsCount);
        assertEquals(20, mrr.payingTenantsCount);
    }

    @Test
    public void test02OutstandingAgingRiskEvaluation() {
        PlatformBillingReportClient.OutstandingSummary healthySummary = new PlatformBillingReportClient.OutstandingSummary(
                new BigDecimal("5000.00"),
                2,
                new BigDecimal("5000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0
        );
        assertFalse(healthySummary.hasHighRiskAr());

        PlatformBillingReportClient.OutstandingSummary delinquentSummary = new PlatformBillingReportClient.OutstandingSummary(
                new BigDecimal("15000.00"),
                5,
                new BigDecimal("5000.00"),
                new BigDecimal("3000.00"),
                new BigDecimal("4000.00"),
                new BigDecimal("2000.00"),
                new BigDecimal("1000.00"),
                2
        );
        assertTrue(delinquentSummary.hasHighRiskAr());
    }

    @Test
    public void test03PermissionDeniedAndOfflineStates() {
        PlatformBillingReportClient.BillingReportUiState deniedState =
                PlatformBillingReportClient.createPermissionDeniedState("CASHIER");
        assertEquals(PlatformBillingReportClient.UiStatus.PERMISSION_DENIED, deniedState.status);
        assertTrue(deniedState.errorMessageEn.contains("Access denied for role 'CASHIER'"));
        assertTrue(deniedState.errorMessageBn.contains("বিলিং রিপোর্ট দেখার অনুমতি নেই"));

        PlatformBillingReportClient.BillingReportUiState offlineState =
                PlatformBillingReportClient.createOfflineState();
        assertEquals(PlatformBillingReportClient.UiStatus.OFFLINE, offlineState.status);
        assertTrue(offlineState.errorMessageEn.contains("offline"));
        assertTrue(offlineState.errorMessageBn.contains("অফলাইনে আছে"));
    }
}

