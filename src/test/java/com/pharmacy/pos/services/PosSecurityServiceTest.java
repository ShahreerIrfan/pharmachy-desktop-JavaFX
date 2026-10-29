package com.pharmacy.pos.services;

import com.pharmacy.pos.models.TenantSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for POS Security and RBAC Boundaries.
 * Architecture Decision: ADR-0005, ADR-0022 & ADR-0023.
 */
class PosSecurityServiceTest {

    private PosSecurityService securityService;

    @BeforeEach
    void setUp() {
        securityService = new PosSecurityService();
    }

    @Test
    @DisplayName("Enrolled Cashier can checkout successfully")
    void testEnrolledCashierCanCheckout() {
        TenantSession session = new TenantSession(
                "tenant-dhaka-01", "branch-dhanmondi", "token-xyz-123",
                "user-cashier-01", "CASHIER", true
        );

        assertDoesNotThrow(() -> securityService.assertCanCheckout(session));
    }

    @Test
    @DisplayName("Unenrolled terminal is strictly rejected from checkout")
    void testUnenrolledTerminalRejected() {
        TenantSession unenrolledSession = new TenantSession(
                "tenant-dhaka-01", "branch-dhanmondi", "token-unverified",
                "user-cashier-01", "CASHIER", false
        );

        SecurityException ex = assertThrows(SecurityException.class,
                () -> securityService.assertCanCheckout(unenrolledSession));
        assertTrue(ex.getMessage().contains("Terminal is not enrolled"));
    }

    @Test
    @DisplayName("Cashier is forbidden from exporting raw POS databases")
    void testCashierForbiddenFromDatabaseExport() {
        TenantSession session = new TenantSession(
                "tenant-dhaka-01", "branch-dhanmondi", "token-xyz-123",
                "user-cashier-01", "CASHIER", true
        );

        SecurityException ex = assertThrows(SecurityException.class,
                () -> securityService.assertCanExportDatabase(session));
        assertTrue(ex.getMessage().contains("strictly forbidden from exporting raw POS databases"));
    }

    @Test
    @DisplayName("Platform Super Admin is authorized to export database")
    void testSuperAdminCanExportDatabase() {
        TenantSession adminSession = new TenantSession(
                "platform-system", "branch-central", "token-admin-999",
                "user-super-admin", "PLATFORM_SUPER_ADMIN", true
        );

        assertDoesNotThrow(() -> securityService.assertCanExportDatabase(adminSession));
    }

    @Test
    @DisplayName("Cross-tenant session access is blocked")
    void testCrossTenantSessionAccessBlocked() {
        TenantSession session = new TenantSession(
                "tenant-dhaka-01", "branch-dhanmondi", "token-xyz-123",
                "user-cashier-01", "CASHIER", true
        );

        SecurityException ex = assertThrows(SecurityException.class,
                () -> securityService.assertTenantBranchMatch(session, "foreign-tenant-02", "branch-dhanmondi"));
        assertTrue(ex.getMessage().contains("Cross-Tenant Violation"));
    }

    @Test
    @DisplayName("Cross-branch session access within same tenant is blocked")
    void testCrossBranchSessionAccessBlocked() {
        TenantSession session = new TenantSession(
                "tenant-dhaka-01", "branch-dhanmondi", "token-xyz-123",
                "user-cashier-01", "CASHIER", true
        );

        SecurityException ex = assertThrows(SecurityException.class,
                () -> securityService.assertTenantBranchMatch(session, "tenant-dhaka-01", "foreign-branch-uttara"));
        assertTrue(ex.getMessage().contains("Cross-Branch Violation"));
    }
}

