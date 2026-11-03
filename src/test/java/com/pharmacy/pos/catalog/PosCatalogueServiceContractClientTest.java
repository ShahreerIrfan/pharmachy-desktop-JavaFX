package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PosCatalogueServiceContractClientTest {

    private PosCatalogueServiceContractClient client;
    private final String tenant1 = "tn-dhanmondi-101";
    private final String tenant2 = "tn-gulshan-202";

    @BeforeEach
    void setUp() {
        client = new PosCatalogueServiceContractClient();
    }

    @Test
    @DisplayName("P140-D1: Valid contract suite audit runs successfully, generating 64-char hash and all 6 phase gates")
    void testRunAuditPassAndHashVerification() {
        var res = client.runAudit(
                tenant1,
                tenant1,
                "STAFF_PHARMACIST",
                "STANDARD",
                false,
                150,
                420
        );

        assertEquals(PosCatalogueServiceContractClient.UiStatus.SUCCESS, res.status);
        assertNotNull(res.data);
        assertEquals("PASSED", res.data.status);
        assertEquals(64, res.data.auditHash.length());
        assertEquals(6, res.data.phaseGates.size());
        assertTrue(res.data.phaseGates.get("historical_immutability"));
        assertTrue(res.data.phaseGates.get("multilingual_search"));
        assertTrue(res.data.phaseGates.get("deduplication"));
    }

    @Test
    @DisplayName("P140-D2: Verifies all nine required catalogue operations are certified PASS")
    void testAllNineOperationsCertified() {
        var res = client.runAudit(
                tenant1,
                tenant1,
                "PHARMACY_OWNER",
                "STANDARD",
                false,
                100,
                300
        );

        assertEquals(PosCatalogueServiceContractClient.UiStatus.SUCCESS, res.status);
        assertEquals(9, res.data.verifiedOperations.size());
        for (var op : res.data.verifiedOperations) {
            assertEquals("PASS", op.status);
        }
    }

    @Test
    @DisplayName("P140-D3: Cashier role is denied contract suite audit execution")
    void testCashierRoleBlocked() {
        var res = client.runAudit(
                tenant1,
                tenant1,
                "CASHIER",
                "STANDARD",
                false,
                50,
                120
        );

        assertEquals(PosCatalogueServiceContractClient.UiStatus.PERMISSION_DENIED, res.status);
        assertEquals("ERR_PERMISSION_DENIED", res.errorCode);
    }

    @Test
    @DisplayName("P140-D4: Unentitled Basic subscription plan is blocked from running audit")
    void testUnentitledBasicSubscriptionBlocked() {
        var res = client.runAudit(
                tenant1,
                tenant1,
                "PHARMACY_OWNER",
                "BASIC",
                false,
                50,
                120
        );

        assertEquals(PosCatalogueServiceContractClient.UiStatus.PERMISSION_DENIED, res.status);
        assertEquals("ERR_SUBSCRIPTION_UNENTITLED", res.errorCode);
    }

    @Test
    @DisplayName("P140-D5: Cross-tenant audit access is strictly prohibited")
    void testCrossTenantAuditBlocked() {
        var res = client.runAudit(
                tenant1,
                tenant2,
                "PHARMACY_OWNER",
                "STANDARD",
                false,
                50,
                120
        );

        assertEquals(PosCatalogueServiceContractClient.UiStatus.PERMISSION_DENIED, res.status);
        assertEquals("ERR_CROSS_TENANT_ACCESS", res.errorCode);
    }

    @Test
    @DisplayName("P140-D6: Offline client serves cached contract audit with UiStatus.OFFLINE")
    void testOfflineQueryServedFromCache() {
        var created = client.runAudit(
                tenant1,
                tenant1,
                "STAFF_PHARMACIST",
                "STANDARD",
                false,
                80,
                240
        );
        String suiteId = created.data.suiteId;

        client.setOffline(true);
        var offlineRes = client.queryAudit(suiteId);
        assertEquals(PosCatalogueServiceContractClient.UiStatus.OFFLINE, offlineRes.status);
        assertNotNull(offlineRes.data);
        assertEquals(suiteId, offlineRes.data.suiteId);
    }
}
