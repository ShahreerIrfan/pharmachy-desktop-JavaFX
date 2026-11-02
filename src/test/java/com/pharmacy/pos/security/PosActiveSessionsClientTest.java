package com.pharmacy.pos.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P112: PosActiveSessionsClient JUnit 5 Tests.
 * Architecture Decision: ADR-0045, ADR-0112.
 * Phase 06: Milestone M03 - Offline-First Desktop POS & Owner Visibility.
 *
 * Verifies active session multi-device visibility, role-based scoping (Owner/Manager/Staff),
 * instant online revocation, cross-branch and cross-tenant guards, and offline queue handling.
 */
public class PosActiveSessionsClientTest {

    private PosActiveSessionsClient ownerClient;
    private PosActiveSessionsClient managerDhanmondiClient;
    private PosActiveSessionsClient cashierClient;

    private PosActiveSessionsClient.ActiveSessionRecord ownerSession;
    private PosActiveSessionsClient.ActiveSessionRecord dhanmondiStaffSession;
    private PosActiveSessionsClient.ActiveSessionRecord gulshanStaffSession;
    private PosActiveSessionsClient.ActiveSessionRecord foreignTenantSession;

    @BeforeEach
    void setUp() {
        ownerClient = new PosActiveSessionsClient(
                "http://localhost:8000/api/v2",
                "tn-lazz-pharma",
                "PHARMACY_OWNER",
                null,
                "usr-owner-01",
                "owner@lazz.local",
                "sess-owner-current"
        );

        managerDhanmondiClient = new PosActiveSessionsClient(
                "http://localhost:8000/api/v2",
                "tn-lazz-pharma",
                "BRANCH_MANAGER",
                "br-dhanmondi-01",
                "usr-mgr-01",
                "mgr@lazz.local",
                "sess-mgr-current"
        );

        cashierClient = new PosActiveSessionsClient(
                "http://localhost:8000/api/v2",
                "tn-lazz-pharma",
                "CASHIER",
                "br-dhanmondi-01",
                "usr-csh-01",
                "csh@lazz.local",
                "sess-csh-current"
        );

        ownerSession = new PosActiveSessionsClient.ActiveSessionRecord(
                "sess-owner-current",
                "usr-owner-01",
                "owner@lazz.local",
                "Kabir Ahmed",
                "PHARMACY_OWNER",
                "tn-lazz-pharma",
                null,
                "dev-owner-macbook",
                "Owner MacBook Pro",
                "WEB_ADMIN",
                "192.168.1.10",
                true,
                true,
                null,
                null,
                "2026-11-02T10:00:00Z",
                "2026-11-02T08:00:00Z"
        );

        dhanmondiStaffSession = new PosActiveSessionsClient.ActiveSessionRecord(
                "sess-dhanmondi-csh",
                "usr-csh-01",
                "csh@lazz.local",
                "Rahim Uddin",
                "CASHIER",
                "tn-lazz-pharma",
                "br-dhanmondi-01",
                "pos-counter-01",
                "Counter 1 Desk",
                "DESKTOP_POS",
                "192.168.1.50",
                true,
                false,
                null,
                null,
                "2026-11-02T10:05:00Z",
                "2026-11-02T08:30:00Z"
        );

        gulshanStaffSession = new PosActiveSessionsClient.ActiveSessionRecord(
                "sess-gulshan-csh",
                "usr-csh-gulshan",
                "csh_gulshan@lazz.local",
                "Karim Sheikh",
                "CASHIER",
                "tn-lazz-pharma",
                "br-gulshan-02",
                "pos-counter-gulshan",
                "Gulshan Counter Desk",
                "DESKTOP_POS",
                "192.168.2.50",
                true,
                false,
                null,
                null,
                "2026-11-02T10:02:00Z",
                "2026-11-02T09:00:00Z"
        );

        foreignTenantSession = new PosActiveSessionsClient.ActiveSessionRecord(
                "sess-foreign-tenant",
                "usr-foreign-01",
                "foreign@popular.local",
                "Foreign User",
                "PHARMACY_OWNER",
                "tn-popular-pharma",
                "br-foreign-01",
                "pos-foreign-01",
                "Popular Counter",
                "DESKTOP_POS",
                "10.0.0.1",
                true,
                false,
                null,
                null,
                "2026-11-02T10:00:00Z",
                "2026-11-02T08:00:00Z"
        );

        // Seed caches
        for (PosActiveSessionsClient c : List.of(ownerClient, managerDhanmondiClient, cashierClient)) {
            c.importSession(ownerSession);
            c.importSession(dhanmondiStaffSession);
            c.importSession(gulshanStaffSession);
            c.importSession(foreignTenantSession);
        }
    }

    @Test
    @DisplayName("P112: Pharmacy Owner sees all active sessions across branches in their tenant")
    void testOwnerListActiveSessions() {
        List<PosActiveSessionsClient.ActiveSessionRecord> sessions = ownerClient.listSessions(null, null, true);
        assertEquals(3, sessions.size()); // Owner, Dhanmondi Staff, Gulshan Staff (Foreign excluded)

        List<String> userEmails = sessions.stream().map(s -> s.userEmail).toList();
        assertTrue(userEmails.contains("owner@lazz.local"));
        assertTrue(userEmails.contains("csh@lazz.local"));
        assertTrue(userEmails.contains("csh_gulshan@lazz.local"));
    }

    @Test
    @DisplayName("P112: Branch Manager is strictly scoped to assigned branch sessions")
    void testBranchManagerListScopedToBranch() {
        List<PosActiveSessionsClient.ActiveSessionRecord> sessions = managerDhanmondiClient.listSessions(null, null, true);
        assertEquals(1, sessions.size());
        assertEquals("sess-dhanmondi-csh", sessions.get(0).sessionId);
        assertEquals("br-dhanmondi-01", sessions.get(0).branchId);
    }

    @Test
    @DisplayName("P112: Cashier is strictly restricted to self-only session listing")
    void testCashierListScopedToSelf() {
        List<PosActiveSessionsClient.ActiveSessionRecord> sessions = cashierClient.listSessions(null, null, true);
        assertEquals(1, sessions.size());
        assertEquals("usr-csh-01", sessions.get(0).userId);
        assertEquals("csh@lazz.local", sessions.get(0).userEmail);
    }

    @Test
    @DisplayName("P112: Pharmacy Owner can revoke any staff session in their tenant")
    void testOwnerRevokeStaffSession() {
        PosActiveSessionsClient.SessionRevocationResult res = ownerClient.revokeSession(
                "sess-dhanmondi-csh",
                "Terminated cashier station"
        );
        assertTrue(res.revoked);
        assertEquals("SUCCESS", res.status);

        List<PosActiveSessionsClient.ActiveSessionRecord> active = ownerClient.listSessions(null, null, true);
        assertFalse(active.stream().anyMatch(s -> s.sessionId.equals("sess-dhanmondi-csh")));
    }

    @Test
    @DisplayName("P112: Branch Manager can revoke session of staff in their branch")
    void testBranchManagerRevokeBranchStaffSession() {
        PosActiveSessionsClient.SessionRevocationResult res = managerDhanmondiClient.revokeSession(
                "sess-dhanmondi-csh",
                "Shift closed by Dhanmondi manager"
        );
        assertTrue(res.revoked);
        assertEquals("SUCCESS", res.status);
    }

    @Test
    @DisplayName("P112: Branch Manager cannot revoke session in another branch (ERR_CROSS_BRANCH_VIOLATION)")
    void testBranchManagerCrossBranchRevocationBlocked() {
        SecurityException ex = assertThrows(SecurityException.class, () ->
                managerDhanmondiClient.revokeSession("sess-gulshan-csh", "Unauthorized cross-branch revoke")
        );
        assertTrue(ex.getMessage().contains("ERR_CROSS_BRANCH_VIOLATION"));
    }

    @Test
    @DisplayName("P112: Cashier cannot revoke another user's session (ERR_PERMISSION_DENIED)")
    void testCashierRevokeAnotherUserSessionBlocked() {
        SecurityException ex = assertThrows(SecurityException.class, () ->
                cashierClient.revokeSession("sess-owner-current", "Rogue cashier revoking owner")
        );
        assertTrue(ex.getMessage().contains("ERR_PERMISSION_DENIED"));
    }

    @Test
    @DisplayName("P112: Cross-tenant session revocation is strictly denied (ERR_CROSS_TENANT_VIOLATION)")
    void testCrossTenantRevocationBlocked() {
        SecurityException ex = assertThrows(SecurityException.class, () ->
                ownerClient.revokeSession("sess-foreign-tenant", "Cross-tenant intrusion attempt")
        );
        assertTrue(ex.getMessage().contains("ERR_CROSS_TENANT_VIOLATION"));
    }

    @Test
    @DisplayName("P112: Owner bulk-revokes all active sessions for a staff member")
    void testBulkRevokeUserSessions() {
        PosActiveSessionsClient.BulkRevocationResult res = ownerClient.revokeAllSessions(
                "usr-csh-01",
                "Compromised staff credential revoked"
        );
        assertEquals("SUCCESS", res.status);
        assertEquals(1, res.revokedCount);
        assertEquals("usr-csh-01", res.targetUserId);
    }

    @Test
    @DisplayName("P112: Offline mode queues revocations locally, reports OFFLINE UI status, and syncs upon reconnection")
    void testOfflineRevocationAndUiState() {
        PosActiveSessionsClient offlineClient = new PosActiveSessionsClient(
                "http://localhost:8000/api/v2",
                "tn-lazz-pharma",
                "PHARMACY_OWNER",
                null,
                "usr-owner-01",
                "owner@lazz.local",
                "sess-owner-current"
        );
        offlineClient.importSession(dhanmondiStaffSession);
        offlineClient.setOffline(true);

        PosActiveSessionsClient.SessionRevocationResult res = offlineClient.revokeSession(
                "sess-dhanmondi-csh",
                "Offline revoke"
        );
        assertTrue(res.revoked);
        assertEquals("OFFLINE_QUEUED", res.status);

        PosActiveSessionsClient.ActiveSessionsUiState uiState = offlineClient.getUiState(null, null, null);
        assertEquals(PosActiveSessionsClient.UiStatus.OFFLINE, uiState.uiStatus);
        assertEquals(1, uiState.offlineQueueCount);

        int synced = offlineClient.syncOfflineRevocations();
        assertEquals(1, synced);

        offlineClient.setOffline(false);
        PosActiveSessionsClient.ActiveSessionsUiState onlineState = offlineClient.getUiState(null, null, null);
        assertEquals(PosActiveSessionsClient.UiStatus.SUCCESS, onlineState.uiStatus);
        assertEquals(0, onlineState.offlineQueueCount);
    }
}

