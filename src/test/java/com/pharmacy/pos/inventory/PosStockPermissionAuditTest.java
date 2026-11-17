package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStockPermissionAuditTest
 * Architecture Decision: ADR-0219 (P219).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 */
public class PosStockPermissionAuditTest {

    private PosStockPermissionAuditClient client;

    @BeforeEach
    void setUp() {
        client = new PosStockPermissionAuditClient();
    }

    @Test
    void testValidRolePermissionsAndTenantWidePrivileges() {
        // 1. Pharmacist permitted for quarantine release and stock adjustment in assigned branch
        var phRelease = client.assertAuthorized(
                "STAFF_PHARMACIST", "ten-a", "br-a", "ten-a", "br-a",
                PosStockPermissionAuditClient.StockActionScope.QUARANTINE_RELEASE, "bat-001"
        );
        assertEquals(PosStockPermissionAuditClient.PermissionDecision.ALLOWED, phRelease.getDecision());
        assertEquals("", phRelease.getErrorCode());

        // 2. Inventory clerk permitted for physical stock counting in assigned branch
        var clkCount = client.assertAuthorized(
                "INVENTORY_CLERK", "ten-a", "br-a", "ten-a", "br-a",
                PosStockPermissionAuditClient.StockActionScope.STOCK_COUNTING, "cnt-001"
        );
        assertEquals(PosStockPermissionAuditClient.PermissionDecision.ALLOWED, clkCount.getDecision());

        // 3. Tenant-wide roles (PHARMACY_OWNER & FINANCE_OFFICER) permitted across branches of same tenant
        var ownerTransfer = client.assertAuthorized(
                "PHARMACY_OWNER", "ten-a", "br-a", "ten-a", "br-a2",
                PosStockPermissionAuditClient.StockActionScope.INTERBRANCH_TRANSFER, "trf-001"
        );
        assertEquals(PosStockPermissionAuditClient.PermissionDecision.ALLOWED, ownerTransfer.getDecision());

        var financeVal = client.assertAuthorized(
                "FINANCE_OFFICER", "ten-a", "br-a", "ten-a", "br-a2",
                PosStockPermissionAuditClient.StockActionScope.VALUATION_DATA, "val-001"
        );
        assertEquals(PosStockPermissionAuditClient.PermissionDecision.ALLOWED, financeVal.getDecision());
    }

    @Test
    void testRoleBasedDenialsLoggedAndRejected() {
        // Inventory clerk denied quarantine release & valuation cost data
        var clkQuarantine = client.evaluatePermission(
                "INVENTORY_CLERK", "ten-a", "br-a", "ten-a", "br-a",
                PosStockPermissionAuditClient.StockActionScope.QUARANTINE_RELEASE, "bat-002"
        );
        assertEquals(PosStockPermissionAuditClient.PermissionDecision.DENIED, clkQuarantine.getDecision());
        assertEquals("ERR_ROLE_NOT_AUTHORIZED", clkQuarantine.getErrorCode());
        assertTrue(clkQuarantine.getReasonBn().contains("নিষিদ্ধ"));

        assertThrows(SecurityException.class, () -> client.assertAuthorized(
                "INVENTORY_CLERK", "ten-a", "br-a", "ten-a", "br-a",
                PosStockPermissionAuditClient.StockActionScope.VALUATION_DATA, "val-002"
        ));

        // Cashier denied stock counting & stock adjustment
        assertThrows(SecurityException.class, () -> client.assertAuthorized(
                "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                PosStockPermissionAuditClient.StockActionScope.STOCK_COUNTING, "cnt-002"
        ));
        assertThrows(SecurityException.class, () -> client.assertAuthorized(
                "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                PosStockPermissionAuditClient.StockActionScope.STOCK_ADJUSTMENT, "adj-002"
        ));

        // Finance officer cannot release quarantine stock
        var finQuarantine = client.evaluatePermission(
                "FINANCE_OFFICER", "ten-a", "br-a", "ten-a", "br-a",
                PosStockPermissionAuditClient.StockActionScope.QUARANTINE_RELEASE, "bat-003"
        );
        assertEquals(PosStockPermissionAuditClient.PermissionDecision.DENIED, finQuarantine.getDecision());
        assertEquals("ERR_ROLE_NOT_AUTHORIZED", finQuarantine.getErrorCode());
    }

    @Test
    void testCrossBranchIsolationForBranchBoundStaff() {
        var entry = client.evaluatePermission(
                "STAFF_PHARMACIST", "ten-a", "br-a", "ten-a", "br-a2",
                PosStockPermissionAuditClient.StockActionScope.STOCK_ADJUSTMENT, "adj-x1"
        );
        assertEquals(PosStockPermissionAuditClient.PermissionDecision.DENIED, entry.getDecision());
        assertEquals("ERR_CROSS_BRANCH_ACCESS_DENIED", entry.getErrorCode());
        assertTrue(entry.getReasonBn().contains("শাখার সীমানা লঙ্ঘন"));

        SecurityException ex = assertThrows(SecurityException.class, () -> client.assertAuthorized(
                "BRANCH_MANAGER", "ten-a", "br-a", "ten-a", "br-a2",
                PosStockPermissionAuditClient.StockActionScope.STOCK_COUNTING, "cnt-x2"
        ));
        assertTrue(ex.getMessage().contains("ERR_CROSS_BRANCH_ACCESS_DENIED"));
    }

    @Test
    void testCrossTenantIsolationAndAuditImmutability() {
        // Cross-tenant attempt blocked
        var tenantDenied = client.evaluatePermission(
                "PHARMACY_OWNER", "ten-b", "br-b", "ten-a", "br-a",
                PosStockPermissionAuditClient.StockActionScope.VALUATION_DATA, "val-x"
        );
        assertEquals(PosStockPermissionAuditClient.PermissionDecision.DENIED, tenantDenied.getDecision());
        assertEquals("ERR_CROSS_TENANT_ACCESS_DENIED", tenantDenied.getErrorCode());

        // Audit logs capture every evaluation and cannot be mutated externally
        List<PosStockPermissionAuditClient.AuditEntry> logs = client.getAuditLogs();
        assertEquals(1, logs.size());
        assertThrows(UnsupportedOperationException.class, () -> logs.remove(0));
    }
}
