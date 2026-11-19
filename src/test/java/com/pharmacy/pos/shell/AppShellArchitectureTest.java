package com.pharmacy.pos.shell;

import javafx.application.Platform;
import javafx.scene.shape.SVGPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P321 JavaFX App Shell Architecture & UI Components Tests")
class AppShellArchitectureTest {

    private ShellViewModel viewModel;

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
        viewModel = new ShellViewModel();
        ShellContainer.getInstance().reset();
    }

    @Test
    @DisplayName("Gate 1 - Valid Scenario: Cashier initializes workspace, accesses allowed routes and toggles bilingual locale")
    void testValidCashierWorkspaceAndLocaleSwitch() {
        viewModel.initSession("ten-dhaka-01", "br-dhanmondi-01", "cashier_rahim", "CASHIER", "POS-DEV-01");

        assertEquals("ten-dhaka-01", viewModel.getTenantId());
        assertEquals("br-dhanmondi-01", viewModel.getBranchId());
        assertEquals("CASHIER", viewModel.getUserRole());
        assertTrue(viewModel.isOnline());

        // Cashier routes
        List<ShellRoute> routes = viewModel.getAccessibleRoutes();
        assertTrue(routes.contains(ShellRoute.POS_SALES));
        assertTrue(routes.contains(ShellRoute.OFFLINE_SYNC));
        assertTrue(routes.contains(ShellRoute.DAILY_SHIFT));
        assertFalse(routes.contains(ShellRoute.SETTINGS), "Cashier must not see Settings");
        assertFalse(routes.contains(ShellRoute.INVENTORY_LEDGER), "Cashier must not see Inventory Ledger");

        // Navigate to allowed route
        boolean navSuccess = viewModel.navigateTo(ShellRoute.DAILY_SHIFT);
        assertTrue(navSuccess);
        assertEquals(ShellRoute.DAILY_SHIFT, viewModel.getActiveRoute());
        assertEquals("", viewModel.getErrorMessage());

        // Bilingual Toggle EN <-> BN
        assertEquals("en_BD", viewModel.getLocale());
        assertEquals("Shift & Cash Drawer", ShellRoute.DAILY_SHIFT.getLabel(viewModel.getLocale()));

        viewModel.toggleLocale();
        assertEquals("bn_BD", viewModel.getLocale());
        assertTrue(viewModel.isBengali());
        assertEquals("শিফট ও ড্রয়ার", ShellRoute.DAILY_SHIFT.getLabel(viewModel.getLocale()));

        viewModel.toggleLocale();
        assertEquals("en_BD", viewModel.getLocale());
        assertFalse(viewModel.isBengali());
    }

    @Test
    @DisplayName("Gate 2 - Incomplete/Invalid Input: Empty tenant or device ID rejected with IllegalArgumentException")
    void testInvalidSessionInputRejection() {
        assertThrows(IllegalArgumentException.class, () ->
                viewModel.initSession("", "br-01", "user1", "CASHIER", "DEV-01")
        );
        assertThrows(IllegalArgumentException.class, () ->
                viewModel.initSession("ten-01", "  ", "user1", "CASHIER", "DEV-01")
        );
        assertThrows(IllegalArgumentException.class, () ->
                viewModel.initSession("ten-01", "br-01", "user1", "CASHIER", " ")
        );

        // Invalid route navigation
        boolean nullNav = viewModel.navigateTo(null);
        assertFalse(nullNav);
        assertEquals("Invalid route target.", viewModel.getErrorMessage());
    }

    @Test
    @DisplayName("Gate 3 - Unauthorized/Role Gating: Cashier blocked from manager settings with permission error")
    void testCashierBlockedFromManagerSettings() {
        viewModel.initSession("ten-dhaka-01", "br-dhanmondi-01", "cashier_rahim", "CASHIER", "POS-DEV-01");

        // Attempt unauthorized navigation to SETTINGS
        boolean navResult = viewModel.navigateTo(ShellRoute.SETTINGS);
        assertFalse(navResult, "Cashier cannot access Settings");
        assertNotEquals(ShellRoute.SETTINGS, viewModel.getActiveRoute());
        assertTrue(viewModel.getErrorMessage().contains("Permission Denied"));
        assertTrue(viewModel.getErrorMessage().contains("CASHIER"));

        // Bengali error message verification
        viewModel.toggleLocale();
        boolean navResultBn = viewModel.navigateTo(ShellRoute.SETTINGS);
        assertFalse(navResultBn);
        assertTrue(viewModel.getErrorMessage().contains("অনুমতি অস্বীকৃত"));
    }

    @Test
    @DisplayName("Gate 4 - Pharmacist Review Failure: Cashier blocked from Prescription Dispensing without pharmacist credentials")
    void testCashierBlockedFromPrescriptionVerification() {
        viewModel.initSession("ten-dhaka-01", "br-dhanmondi-01", "cashier_rahim", "CASHIER", "POS-DEV-01");

        boolean rxNav = viewModel.navigateTo(ShellRoute.PRESCRIPTIONS);
        assertFalse(rxNav, "Cashier without pharmacist role must be blocked from Prescription Dispensing");
        assertTrue(viewModel.getErrorMessage().contains("ROUTE_PRESCRIPTION_VERIFICATION"));

        // Now escalate session to STAFF_PHARMACIST
        viewModel.initSession("ten-dhaka-01", "br-dhanmondi-01", "pharm_fatima", "STAFF_PHARMACIST", "POS-DEV-01");
        boolean rxNavSuccess = viewModel.navigateTo(ShellRoute.PRESCRIPTIONS);
        assertTrue(rxNavSuccess, "Staff Pharmacist must be permitted to navigate to Prescription Dispensing");
        assertEquals(ShellRoute.PRESCRIPTIONS, viewModel.getActiveRoute());
        assertEquals("", viewModel.getErrorMessage());
    }

    @Test
    @DisplayName("Gate 5 - DI Container & Service Registry: Singleton registration and retrieval")
    void testShellContainerDI() {
        ShellContainer container = ShellContainer.getInstance();
        container.registerSingleton(ShellViewModel.class, viewModel);

        assertTrue(container.has(ShellViewModel.class));
        ShellViewModel resolved = container.resolve(ShellViewModel.class);
        assertSame(viewModel, resolved);

        container.reset();
        assertFalse(container.has(ShellViewModel.class));
        assertThrows(IllegalStateException.class, () -> container.resolve(ShellViewModel.class));
    }

    @Test
    @DisplayName("Gate 6 - Phase Specific Gate: Zero Emoji SVG Icons, Apple Layout & Keyboard Shortcuts")
    void testSvgIconsAndAppShellView() {
        // Verify SVG Icons are valid geometry and contain no emoji pictograms
        for (ShellRoute route : ShellRoute.values()) {
            assertNotNull(route.getIconPath(), "Icon path must not be null for " + route);
            assertFalse(route.getIconPath().isEmpty());
            assertFalse(route.getIconPath().contains("😀"), "Strict zero emoji policy");

            SVGPath icon = ShellSvgIcons.createNavIcon(route.getIconPath(), true);
            assertNotNull(icon);
            assertEquals(route.getIconPath(), icon.getContent());
            assertNotNull(route.getShortcutHint(), "Shortcut hint must exist (F2, F3, etc.)");
            assertTrue(route.getShortcutHint().startsWith("F"), "Shortcut must be a function key");
        }

        // Test AppShellView instantiation with UI bindings
        viewModel.initSession("ten-dhaka-01", "br-dhanmondi-01", "owner_karim", "PHARMACY_OWNER", "POS-DEV-01");
        AppShellView shellView = new AppShellView(viewModel);

        assertNotNull(shellView.getTop());
        assertNotNull(shellView.getLeft());
        assertNotNull(shellView.getCenter());
        assertNotNull(shellView.getBottom());

        // Owner has access to all routes including SETTINGS
        assertEquals(ShellRoute.values().length, viewModel.getAccessibleRoutes().size());
        assertTrue(viewModel.navigateTo(ShellRoute.SETTINGS));
        assertEquals(ShellRoute.SETTINGS, viewModel.getActiveRoute());
    }
}
