package com.pharmacy.pos.dashboard;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

public class PosOwnerDashboardClientTest {

    @Test
    public void test01ProfitabilityAndSalesEvaluation() {
        PosOwnerDashboardClient.SalesKpi sales = new PosOwnerDashboardClient.SalesKpi(
                new BigDecimal("45250.00"),
                new BigDecimal("45250.00"),
                84,
                new BigDecimal("538.69")
        );
        assertEquals(84, sales.totalOrdersCount);
        assertEquals("৳45250.00", PosOwnerDashboardClient.formatBdt(sales.todaySalesBdt));

        PosOwnerDashboardClient.ProfitabilityKpi profit = new PosOwnerDashboardClient.ProfitabilityKpi(
                new BigDecimal("46000.00"),
                new BigDecimal("34500.00"),
                new BigDecimal("10750.00"),
                new BigDecimal("23.76"),
                new BigDecimal("750.00"),
                new BigDecimal("10000.00")
        );
        assertEquals(new BigDecimal("10750.00"), profit.grossProfitBdt);
        assertEquals("৳10750.00", PosOwnerDashboardClient.formatBdt(profit.grossProfitBdt));
        assertEquals(new BigDecimal("23.76"), profit.grossMarginPercent);
    }

    @Test
    public void test02InventoryAndExpiryAlerts() {
        PosOwnerDashboardClient.InventoryKpi inv = new PosOwnerDashboardClient.InventoryKpi(
                new BigDecimal("1250000.00"),
                340,
                5,
                1
        );
        assertTrue(inv.hasStockShortage());

        PosOwnerDashboardClient.ExpiryKpi expiry = new PosOwnerDashboardClient.ExpiryKpi(
                4,
                18,
                1,
                new BigDecimal("3200.00")
        );
        assertTrue(expiry.hasCriticalExpiryRisk());
        assertEquals("৳3200.00", PosOwnerDashboardClient.formatBdt(expiry.expiredStockValueBdt));
    }

    @Test
    public void test03SyncHealthAndStateHandling() {
        PosOwnerDashboardClient.SyncHealthKpi sync = new PosOwnerDashboardClient.SyncHealthKpi(
                3,
                2,
                0,
                "HEALTHY",
                "2026-11-01T15:00:00Z"
        );
        assertTrue(sync.isHealthy());

        PosOwnerDashboardClient.SyncHealthKpi degradedSync = new PosOwnerDashboardClient.SyncHealthKpi(
                3,
                1,
                4,
                "NEEDS_SYNC",
                "2026-11-01T15:00:00Z"
        );
        assertFalse(degradedSync.isHealthy());

        PosOwnerDashboardClient.OwnerDashboardUiState deniedState =
                PosOwnerDashboardClient.createPermissionDeniedState("CASHIER");
        assertEquals(PosOwnerDashboardClient.UiStatus.PERMISSION_DENIED, deniedState.status);
        assertTrue(deniedState.errorMessageEn.contains("Access denied for role 'CASHIER'"));
        assertTrue(deniedState.errorMessageBn.contains("এক্সিকিউটিভ ড্যাশবোর্ড দেখার অনুমতি নেই"));

        PosOwnerDashboardClient.OwnerDashboardUiState offlineState =
                PosOwnerDashboardClient.createOfflineState();
        assertEquals(PosOwnerDashboardClient.UiStatus.OFFLINE, offlineState.status);
        assertTrue(offlineState.errorMessageEn.contains("offline"));
        assertTrue(offlineState.errorMessageBn.contains("অফলাইনে আছে"));
    }
}
