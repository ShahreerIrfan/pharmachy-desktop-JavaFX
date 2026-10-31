package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosUserIdentityServiceTest
 * Architecture Decision: ADR-0042
 * Unit tests for Desktop POS user identity, Bangladesh phone normalization, and role permissions.
 */
class PosUserIdentityServiceTest {

    @Test
    @DisplayName("Normalizes valid Bangladesh phone numbers to E.164")
    void testNormalizeBdPhone() {
        assertEquals("+8801712345678", PosUserIdentityService.normalizeBdPhone("01712345678"));
        assertEquals("+8801812345678", PosUserIdentityService.normalizeBdPhone("+8801812345678"));
        assertEquals("+8801912345678", PosUserIdentityService.normalizeBdPhone("8801912345678"));
        assertEquals("+8801612345678", PosUserIdentityService.normalizeBdPhone("01612-345678"));
    }

    @Test
    @DisplayName("Rejects invalid phone numbers")
    void testRejectInvalidPhone() {
        assertThrows(IllegalArgumentException.class, () -> PosUserIdentityService.normalizeBdPhone("12345"));
        assertThrows(IllegalArgumentException.class, () -> PosUserIdentityService.normalizeBdPhone("01234567890")); // invalid operator prefix 012
        assertThrows(IllegalArgumentException.class, () -> PosUserIdentityService.normalizeBdPhone(""));
    }

    @Test
    @DisplayName("PosUserSession enforces role boundaries and safe deletion state")
    void testPosUserSessionPermissions() {
        PosUserIdentityService.PosUserSession cashierSession = new PosUserIdentityService.PosUserSession(
            "usr-1",
            "tenant-gulshan",
            "branch-1",
            "cashier@pharmacy.bd",
            "+8801712345678",
            "Rahim Uddin",
            "CASHIER",
            "ACTIVE",
            false
        );

        assertTrue(cashierSession.canPerformCheckout(), "Active cashier can perform checkout");
        assertFalse(cashierSession.canManageStaff(), "Cashier is forbidden from managing staff");

        PosUserIdentityService.PosUserSession archivedOwner = new PosUserIdentityService.PosUserSession(
            "usr-2",
            "tenant-gulshan",
            "branch-1",
            "owner@pharmacy.bd",
            "+8801812345678",
            "Dr. Karim",
            "PHARMACY_OWNER",
            "DEACTIVATED",
            true
        );

        assertFalse(archivedOwner.canPerformCheckout(), "Archived user cannot perform checkout");
        assertFalse(archivedOwner.canManageStaff(), "Archived user cannot manage staff");
    }
}

