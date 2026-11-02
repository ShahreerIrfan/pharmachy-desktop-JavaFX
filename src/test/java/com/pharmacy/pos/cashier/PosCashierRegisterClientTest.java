package com.pharmacy.pos.cashier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosCashierRegisterClientTest
 * Tests P117 cashier register assignment client logic.
 */
class PosCashierRegisterClientTest {

    private PosCashierRegisterClient client;
    private List<PosCashierRegisterClient.RegisterAssignmentRecord> assignments;

    @BeforeEach
    void setUp() {
        client = new PosCashierRegisterClient();
        assignments = new ArrayList<>();
    }

    @Test
    @DisplayName("Owner can assign unassigned register to cashier successfully")
    void testOwnerAssignRegisterSuccess() {
        var result = client.attemptRegisterAssignment(
                assignments,
                "PHARMACY_OWNER",
                "tenant-100",
                "branch-1",
                "staff-owner",
                "tenant-100",
                "branch-1",
                "REG-01",
                "staff-cashier-1",
                "Rahim Cashier",
                500.00
        );

        assertEquals(PosCashierRegisterClient.UiStatus.SUCCESS, result.uiStatus);
        assertNotNull(result.record);
        assertEquals("REG-01", result.record.registerId);
        assertEquals("ACTIVE", result.record.status);
        assertEquals(500.00, result.record.openingFloatAmount);
    }

    @Test
    @DisplayName("Reject assignment if register is already actively assigned")
    void testRegisterAlreadyAssignedCollisionRejected() {
        assignments.add(new PosCashierRegisterClient.RegisterAssignmentRecord(
                "assign-1", "tenant-100", "branch-1", "REG-01",
                "staff-cashier-1", "Rahim", 500.00, null, "ACTIVE", "2026-11-02T08:00:00Z"
        ));

        var result = client.attemptRegisterAssignment(
                assignments,
                "PHARMACY_OWNER",
                "tenant-100",
                "branch-1",
                "staff-owner",
                "tenant-100",
                "branch-1",
                "REG-01",
                "staff-cashier-2",
                "Karim",
                500.00
        );

        assertEquals(PosCashierRegisterClient.UiStatus.ERROR, result.uiStatus);
        assertEquals("ERR_REGISTER_ALREADY_ASSIGNED", result.errorCode);
        assertNull(result.record);
    }

    @Test
    @DisplayName("Reject assignment if cashier is already actively assigned elsewhere")
    void testCashierAlreadyAssignedElsewhereCollisionRejected() {
        assignments.add(new PosCashierRegisterClient.RegisterAssignmentRecord(
                "assign-1", "tenant-100", "branch-1", "REG-01",
                "staff-cashier-1", "Rahim", 500.00, null, "ACTIVE", "2026-11-02T08:00:00Z"
        ));

        var result = client.attemptRegisterAssignment(
                assignments,
                "PHARMACY_OWNER",
                "tenant-100",
                "branch-1",
                "staff-owner",
                "tenant-100",
                "branch-1",
                "REG-02",
                "staff-cashier-1",
                "Rahim",
                500.00
        );

        assertEquals(PosCashierRegisterClient.UiStatus.ERROR, result.uiStatus);
        assertEquals("ERR_CASHIER_ALREADY_ASSIGNED", result.errorCode);
        assertNull(result.record);
    }

    @Test
    @DisplayName("Branch Manager cannot assign registers across branches")
    void testBranchManagerCrossBranchRejected() {
        var result = client.attemptRegisterAssignment(
                assignments,
                "BRANCH_MANAGER",
                "tenant-100",
                "branch-1",
                "staff-mgr",
                "tenant-100",
                "branch-2",
                "REG-01",
                "staff-cashier-1",
                "Rahim",
                500.00
        );

        assertEquals(PosCashierRegisterClient.UiStatus.PERMISSION_DENIED, result.uiStatus);
        assertEquals("ERR_CROSS_BRANCH_VIOLATION", result.errorCode);
    }

    @Test
    @DisplayName("Cashier cannot assign register to another staff member")
    void testCashierAssignOtherStaffRejected() {
        var result = client.attemptRegisterAssignment(
                assignments,
                "CASHIER",
                "tenant-100",
                "branch-1",
                "staff-cashier-1",
                "tenant-100",
                "branch-1",
                "REG-01",
                "staff-cashier-2",
                "Karim",
                500.00
        );

        assertEquals(PosCashierRegisterClient.UiStatus.PERMISSION_DENIED, result.uiStatus);
        assertEquals("ERR_PERMISSION_DENIED", result.errorCode);
    }

    @Test
    @DisplayName("Cross-tenant register assignment rejected")
    void testCrossTenantAssignmentRejected() {
        var result = client.attemptRegisterAssignment(
                assignments,
                "PHARMACY_OWNER",
                "tenant-100",
                "branch-1",
                "staff-owner",
                "tenant-999",
                "branch-999",
                "REG-01",
                "staff-cashier-1",
                "Rahim",
                500.00
        );

        assertEquals(PosCashierRegisterClient.UiStatus.PERMISSION_DENIED, result.uiStatus);
        assertEquals("ERR_CROSS_TENANT_VIOLATION", result.errorCode);
    }
}
