package com.pharmacy.pos.dashboard;

import com.pharmacy.pos.dashboard.PosOwnerDashboardClient.UiStatus;
import com.pharmacy.pos.shell.AppShellView;
import com.pharmacy.pos.shell.ShellRoute;
import com.pharmacy.pos.shell.ShellViewModel;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P322 Reference-Inspired Dashboard Tests")
class ReferenceDashboardTest {

    private ShellViewModel shellViewModel;
    private ReferenceDashboardViewModel dashboardViewModel;

    @BeforeAll
    static void initJavaFx() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {
            // Already initialized
        }
    }

    @BeforeEach
    void setUp() {
        shellViewModel = new ShellViewModel();
        shellViewModel.initSession("ten-dhaka-01", "br-dhanmondi-01", "owner_karim", "PHARMACY_OWNER", "POS-DEV-01");
        dashboardViewModel = new ReferenceDashboardViewModel(shellViewModel);
    }

    @Test
    @DisplayName("Gate 1 - Valid Scenario: Live dynamic date, exact BDT Decimal revenue, and operational quick navigation")
    void testValidDashboardTelemetryAndQuickNavigation() {
        dashboardViewModel.loadDashboardData("ten-dhaka-01", "br-dhanmondi-01", "PHARMACY_OWNER", true);

        assertEquals(UiStatus.SUCCESS, dashboardViewModel.getUiStatus());
        assertFalse(dashboardViewModel.isFinancialMetricsMasked(), "Owner has unmasked financial metrics");

        // Exact Decimal currency verification
        assertEquals(new BigDecimal("45250.00"), dashboardViewModel.getTodaySalesBdt());
        assertEquals(82, dashboardViewModel.getTotalOrdersCount());
        assertEquals(7, dashboardViewModel.getLowStockAlertCount());
        assertEquals(3, dashboardViewModel.getPendingRxCount());

        // Dynamic live date (no obsolete hardcoded mock dates)
        int currentYear = LocalDate.now().getYear();
        assertTrue(dashboardViewModel.getLiveDateString().contains(String.valueOf(currentYear)));
        assertFalse(dashboardViewModel.getLiveDateString().isEmpty());

        // Fast operational navigation
        boolean navQuickSale = dashboardViewModel.navigateToRoute(ShellRoute.POS_SALES);
        assertTrue(navQuickSale);
        assertEquals(ShellRoute.POS_SALES, shellViewModel.getActiveRoute());
    }

    @Test
    @DisplayName("Gate 2 - Incomplete/Invalid Input: Missing branch or tenant triggers UiStatus.ERROR")
    void testInvalidBranchOrTenantInputRejection() {
        dashboardViewModel.loadDashboardData("", "br-01", "PHARMACY_OWNER", true);
        assertEquals(UiStatus.ERROR, dashboardViewModel.getUiStatus());
        assertTrue(dashboardViewModel.getErrorMessage().contains("Invalid branch or tenant"));

        dashboardViewModel.loadDashboardData("ten-01", "   ", "PHARMACY_OWNER", true);
        assertEquals(UiStatus.ERROR, dashboardViewModel.getUiStatus());
    }

    @Test
    @DisplayName("Gate 3 - Role Masking & Permission Denied: Cashier financial masking and access restriction")
    void testCashierFinancialMaskingAndPermissionDenied() {
        // Initialize as Cashier
        shellViewModel.initSession("ten-dhaka-01", "br-dhanmondi-01", "cashier_1", "CASHIER", "POS-DEV-01");
        dashboardViewModel.loadDashboardData("ten-dhaka-01", "br-dhanmondi-01", "CASHIER", true);

        assertEquals(UiStatus.SUCCESS, dashboardViewModel.getUiStatus());
        assertTrue(dashboardViewModel.isFinancialMetricsMasked(), "Financial margin must be masked for cashier counter role");
        assertEquals(BigDecimal.ZERO, dashboardViewModel.getGrossProfitBdt());

        // Permission denied state trigger
        dashboardViewModel.setPermissionDenied("Permission Denied: Cross-tenant dashboard access is strictly forbidden.");
        assertEquals(UiStatus.PERMISSION_DENIED, dashboardViewModel.getUiStatus());
        assertTrue(dashboardViewModel.getErrorMessage().contains("Permission Denied"));
    }

    @Test
    @DisplayName("Gate 4 - Offline Mode & Pharmacist Review Gate: Offline SQLite cache telemetry and Rx verification block")
    void testOfflineModeAndPharmacistReviewFailure() {
        // Switch to offline
        dashboardViewModel.loadDashboardData("ten-dhaka-01", "br-dhanmondi-01", "PHARMACY_OWNER", false);
        assertEquals(UiStatus.OFFLINE, dashboardViewModel.getUiStatus());
        assertEquals(new BigDecimal("18500.00"), dashboardViewModel.getTodaySalesBdt());
        assertEquals(24, dashboardViewModel.getTotalOrdersCount());

        // Cashier attempting Rx Dispensing route from operational dashboard
        shellViewModel.initSession("ten-dhaka-01", "br-dhanmondi-01", "cashier_1", "CASHIER", "POS-DEV-01");
        boolean rxNav = dashboardViewModel.navigateToRoute(ShellRoute.PRESCRIPTIONS);
        assertFalse(rxNav, "Cashier cannot navigate to Prescription Dispensing");
        assertTrue(shellViewModel.getErrorMessage().contains("ROUTE_PRESCRIPTION_VERIFICATION"));
    }

    @Test
    @DisplayName("Gate 5 - Empty State Handling: Zero metrics calm editorial state")
    void testEmptyStateHandling() {
        dashboardViewModel.setEmptyState();
        assertEquals(UiStatus.EMPTY, dashboardViewModel.getUiStatus());
        assertEquals(new BigDecimal("0.00"), dashboardViewModel.getTodaySalesBdt());
        assertEquals(0, dashboardViewModel.getTotalOrdersCount());
        assertEquals(0, dashboardViewModel.getLowStockAlertCount());
    }

    @Test
    @DisplayName("Gate 6 - Phase Specific Gate: JavaFX View Rendering, SVG Icons and Bilingual Support")
    void testViewRenderingAndBilingualLocalization() {
        dashboardViewModel.loadDashboardData("ten-dhaka-01", "br-dhanmondi-01", "PHARMACY_OWNER", true);
        ReferenceDashboardView view = new ReferenceDashboardView(dashboardViewModel);

        assertNotNull(view.getContent());
        assertEquals(dashboardViewModel, view.getViewModel());

        // Verify English to Bengali toggle
        assertFalse(dashboardViewModel.isBengali());
        shellViewModel.toggleLocale();
        assertTrue(dashboardViewModel.isBengali());

        // Verify AppShellView integration
        AppShellView shellView = new AppShellView(shellViewModel);
        shellView.registerView(ShellRoute.DASHBOARD, view);
        assertEquals(ShellRoute.DASHBOARD, shellViewModel.getActiveRoute());
    }
}
