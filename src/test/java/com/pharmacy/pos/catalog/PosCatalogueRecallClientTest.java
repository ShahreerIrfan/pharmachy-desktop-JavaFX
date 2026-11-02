package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PosCatalogueRecallClientTest {

    private PosCatalogueRecallClient client;
    private final String tenant1 = "tn-banani-101";
    private final String tenant2 = "tn-gulshan-202";

    @BeforeEach
    void setUp() {
        client = new PosCatalogueRecallClient();
    }

    @Test
    @DisplayName("P137-D1: Publish statutory DGDA safety recall hard-blocks dispensing and sets blocked flag")
    void testPublishStatutorySafetyRecall() {
        var res = client.publishNotice(
                tenant1,
                "med-paracip-120",
                "Paracip 120mg Syrup",
                PosCatalogueRecallClient.LifecycleStatus.RECALLED,
                PosCatalogueRecallClient.RecallScope.NATIONWIDE_ALL_BATCHES,
                List.of(),
                "DGDA",
                "DGDA/DR/2026/RC-901",
                "DEG impurity detected in oral liquid formulation.",
                "Immediately quarantine and halt all dispensing.",
                "STAFF_PHARMACIST"
        );

        assertEquals(PosCatalogueRecallClient.UiStatus.SUCCESS, res.status);
        assertNotNull(res.data);
        assertEquals("DGDA/DR/2026/RC-901", res.data.noticeNumber);
        assertEquals(PosCatalogueRecallClient.LifecycleStatus.RECALLED, res.data.lifecycleStatus);
        assertTrue(res.data.isDispensingBlocked);
        assertTrue(res.data.isActive);
        assertEquals(64, res.data.immutableHash.length());
    }

    @Test
    @DisplayName("P137-D2: Validate dispense returns error ERR_PRODUCT_RECALLED_DISPENSING_BLOCKED on recalled product")
    void testValidateDispenseRejection() {
        // Publish recall
        client.publishNotice(
                tenant1,
                "med-paracip-120",
                "Paracip 120mg Syrup",
                PosCatalogueRecallClient.LifecycleStatus.RECALLED,
                PosCatalogueRecallClient.RecallScope.NATIONWIDE_ALL_BATCHES,
                List.of(),
                "DGDA",
                "DGDA/DR/2026/RC-902",
                "Sub-potent active ingredient batches.",
                "Quarantine stock.",
                "BRANCH_MANAGER"
        );

        // Attempt cashier checkout dispense validation
        var dispenseRes = client.validateDispense(tenant1, "med-paracip-120", "BATCH-01");
        assertEquals(PosCatalogueRecallClient.UiStatus.ERROR, dispenseRes.status);
        assertEquals("ERR_PRODUCT_RECALLED_DISPENSING_BLOCKED", dispenseRes.errorCode);
    }

    @Test
    @DisplayName("P137-D3: Specific batch recall blocks only affected batch lots")
    void testSpecificBatchRecallScope() {
        client.publishNotice(
                tenant1,
                "med-cefix-200",
                "Cefix 200mg Capsule",
                PosCatalogueRecallClient.LifecycleStatus.RECALLED,
                PosCatalogueRecallClient.RecallScope.SPECIFIC_BATCHES,
                List.of("BATCH-CONTAM-X"),
                "Manufacturer QA",
                "MFR-RC-881",
                "Moisture ingress in batch packaging.",
                "Quarantine contaminated batch only.",
                "STAFF_PHARMACIST"
        );

        // Contaminated batch -> Blocked
        var contamCheck = client.checkRecallStatus(tenant1, "med-cefix-200", "BATCH-CONTAM-X");
        assertEquals(PosCatalogueRecallClient.UiStatus.SUCCESS, contamCheck.status);
        assertTrue(contamCheck.data.blocked);

        // Safe batch -> Allowed
        var safeCheck = client.checkRecallStatus(tenant1, "med-cefix-200", "BATCH-SAFE-Y");
        assertEquals(PosCatalogueRecallClient.UiStatus.SUCCESS, safeCheck.status);
        assertFalse(safeCheck.data.blocked);
    }

    @Test
    @DisplayName("P137-D4: Discontinued product flags item but allows depleting existing stock")
    void testDiscontinuedProductLifecycle() {
        client.publishNotice(
                tenant1,
                "med-oldbrand-500",
                "OldBrand 500mg",
                PosCatalogueRecallClient.LifecycleStatus.DISCONTINUED,
                PosCatalogueRecallClient.RecallScope.NATIONWIDE_ALL_BATCHES,
                List.of(),
                "Pharma Corp",
                "DISC-2026-001",
                "Brand discontinued; replaced by newer formulation.",
                "Deplete remaining branch inventory.",
                "BRANCH_MANAGER"
        );

        var checkRes = client.checkRecallStatus(tenant1, "med-oldbrand-500", null);
        assertEquals(PosCatalogueRecallClient.UiStatus.SUCCESS, checkRes.status);
        assertFalse(checkRes.data.blocked);
        assertEquals(PosCatalogueRecallClient.LifecycleStatus.DISCONTINUED, checkRes.data.lifecycleStatus);

        var valDispense = client.validateDispense(tenant1, "med-oldbrand-500", null);
        assertEquals(PosCatalogueRecallClient.UiStatus.SUCCESS, valDispense.status);
        assertFalse(valDispense.data.blocked);
    }

    @Test
    @DisplayName("P137-D5: Cashier can query recall status for checkout safety check but cannot publish or lift recalls")
    void testCashierPermissionsAndRecallChecks() {
        // Cashier check safe item -> Permitted
        var safeCheck = client.checkRecallStatus(tenant1, "med-safe-item", null);
        assertEquals(PosCatalogueRecallClient.UiStatus.SUCCESS, safeCheck.status);
        assertFalse(safeCheck.data.blocked);

        // Cashier attempts to publish notice -> Denied
        var cashierPub = client.publishNotice(
                tenant1,
                "med-some-item",
                "Some Item",
                PosCatalogueRecallClient.LifecycleStatus.RECALLED,
                PosCatalogueRecallClient.RecallScope.NATIONWIDE_ALL_BATCHES,
                List.of(),
                "Unauthorized Cashier",
                "UNAUTH-RC-01",
                "Unauthorized cashier attempt.",
                "None",
                "CASHIER"
        );
        assertEquals(PosCatalogueRecallClient.UiStatus.PERMISSION_DENIED, cashierPub.status);
        assertEquals("ERR_STAFF_ROLE_DENIED", cashierPub.errorCode);
    }

    @Test
    @DisplayName("P137-D6: Cross-tenant recall lift blocked with ERR_CROSS_TENANT_ACCESS and offline mode serves cached status")
    void testCrossTenantLiftAndOfflineMode() {
        var pubRes = client.publishNotice(
                tenant1,
                "med-hold-item",
                "Hold Item",
                PosCatalogueRecallClient.LifecycleStatus.QUARANTINED,
                PosCatalogueRecallClient.RecallScope.TENANT_QUARANTINE,
                List.of(),
                "Tenant 1 QA",
                "T1-HOLD-01",
                "Suspicious discoloration in batch lot.",
                "Hold in quarantine room.",
                "BRANCH_MANAGER"
        );
        String noticeId = pubRes.data.noticeId;

        // Tenant 2 attempts to lift Tenant 1's quarantine -> Denied
        var crossLift = client.liftNotice(noticeId, tenant2, "BRANCH_MANAGER", "Malicious lift");
        assertEquals(PosCatalogueRecallClient.UiStatus.PERMISSION_DENIED, crossLift.status);
        assertEquals("ERR_CROSS_TENANT_ACCESS", crossLift.errorCode);

        // Disconnect to offline mode
        client.setOffline(true);
        assertTrue(client.isOffline());

        // Offline status check serves cached recall status with OFFLINE ui state
        var offlineCheck = client.checkRecallStatus(tenant1, "med-hold-item", null);
        assertEquals(PosCatalogueRecallClient.UiStatus.OFFLINE, offlineCheck.status);
        assertNotNull(offlineCheck.data);
        assertTrue(offlineCheck.data.blocked);
        assertEquals("T1-HOLD-01", offlineCheck.data.noticeNumber);
    }
}
