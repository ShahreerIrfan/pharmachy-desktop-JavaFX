package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PosBarcodeIdentifierClientTest {

    private PosBarcodeIdentifierClient client;
    private final String tenant1 = "tn-banani-101";
    private final String tenant2 = "tn-gulshan-202";

    @BeforeEach
    void setUp() {
        client = new PosBarcodeIdentifierClient();
    }

    @Test
    @DisplayName("P136-D1: Register primary EAN-13 and secondary box GTIN-14 identifiers across packaging tiers")
    void testRegisterPrimaryAndBoxBarcodes() {
        // 1. Primary retail unit blister barcode
        var resUnit = client.registerBarcode(
                tenant1,
                "med-napa-500",
                PosBarcodeIdentifierClient.TargetEntityType.MEDICINE,
                "Napa Extra 500mg",
                2.50,
                "8901234567890",
                PosBarcodeIdentifierClient.IdentifierType.PRIMARY_EAN13_GTIN,
                PosBarcodeIdentifierClient.PackagingLevel.UNIT,
                1,
                1,
                "Primary retail unit",
                "BRANCH_MANAGER"
        );

        assertEquals(PosBarcodeIdentifierClient.UiStatus.SUCCESS, resUnit.status);
        assertNotNull(resUnit.data);
        assertEquals("8901234567890", resUnit.data.barcodeValue);
        assertEquals(PosBarcodeIdentifierClient.PackagingLevel.UNIT, resUnit.data.packagingLevel);
        assertEquals(1, resUnit.data.priorityOrder);
        assertEquals(1, resUnit.data.packQuantityMultiplier);
        assertEquals(64, resUnit.data.immutableHash.length());

        // 2. Secondary outer trade box GTIN-14
        var resBox = client.registerBarcode(
                tenant1,
                "med-napa-500",
                PosBarcodeIdentifierClient.TargetEntityType.MEDICINE,
                "Napa Extra 500mg",
                250.00,
                "18901234567897",
                PosBarcodeIdentifierClient.IdentifierType.SECONDARY_BOX_GTIN14,
                PosBarcodeIdentifierClient.PackagingLevel.BOX,
                3,
                100,
                "Outer box (10 strips x 10 tablets)",
                "BRANCH_MANAGER"
        );

        assertEquals(PosBarcodeIdentifierClient.UiStatus.SUCCESS, resBox.status);
        assertNotNull(resBox.data);
        assertEquals("18901234567897", resBox.data.barcodeValue);
        assertEquals(PosBarcodeIdentifierClient.PackagingLevel.BOX, resBox.data.packagingLevel);
        assertEquals(3, resBox.data.priorityOrder);
        assertEquals(100, resBox.data.packQuantityMultiplier);
    }

    @Test
    @DisplayName("P136-D2: Contextual uniqueness blocks duplicate active barcode for another product")
    void testContextualUniquenessBlocksDuplicateBarcode() {
        var res1 = client.registerBarcode(
                tenant1,
                "med-napa-500",
                PosBarcodeIdentifierClient.TargetEntityType.MEDICINE,
                "Napa Extra 500mg",
                2.50,
                "8901112223334",
                PosBarcodeIdentifierClient.IdentifierType.PRIMARY_EAN13_GTIN,
                PosBarcodeIdentifierClient.PackagingLevel.UNIT,
                1,
                1,
                "Primary retail unit",
                "BRANCH_MANAGER"
        );
        assertEquals(PosBarcodeIdentifierClient.UiStatus.SUCCESS, res1.status);

        // Attempting to assign same barcode to Ace Plus 500mg in same tenant
        var res2 = client.registerBarcode(
                tenant1,
                "med-ace-500",
                PosBarcodeIdentifierClient.TargetEntityType.MEDICINE,
                "Ace Plus 500mg",
                2.50,
                "8901112223334",
                PosBarcodeIdentifierClient.IdentifierType.PRIMARY_EAN13_GTIN,
                PosBarcodeIdentifierClient.PackagingLevel.UNIT,
                1,
                1,
                "Duplicate barcode attempt",
                "BRANCH_MANAGER"
        );
        assertEquals(PosBarcodeIdentifierClient.UiStatus.ERROR, res2.status);
        assertEquals("ERR_BARCODE_ALREADY_ASSIGNED", res2.errorCode);
    }

    @Test
    @DisplayName("P136-D3: Scan prioritization resolves priority 1 unit barcode before secondary codes")
    void testScanPrioritizationResolvesHighestPriority() {
        // Register strip barcode (Priority 2)
        client.registerBarcode(
                tenant1,
                "med-napa-500",
                PosBarcodeIdentifierClient.TargetEntityType.MEDICINE,
                "Napa Extra 500mg",
                25.00,
                "8903334445556",
                PosBarcodeIdentifierClient.IdentifierType.INTERNAL_STORE_BARCODE,
                PosBarcodeIdentifierClient.PackagingLevel.STRIP,
                2,
                10,
                "Blister Strip",
                "BRANCH_MANAGER"
        );

        // Register box barcode (Priority 3)
        client.registerBarcode(
                tenant1,
                "med-napa-500",
                PosBarcodeIdentifierClient.TargetEntityType.MEDICINE,
                "Napa Extra 500mg",
                250.00,
                "18903334445553",
                PosBarcodeIdentifierClient.IdentifierType.SECONDARY_BOX_GTIN14,
                PosBarcodeIdentifierClient.PackagingLevel.BOX,
                3,
                100,
                "Commercial Box",
                "BRANCH_MANAGER"
        );

        // Scan the box barcode -> resolves BOX level with multiplier 100
        var scanBox = client.resolveBarcode(tenant1, "18903334445553");
        assertEquals(PosBarcodeIdentifierClient.UiStatus.SUCCESS, scanBox.status);
        assertTrue(scanBox.data.matched);
        assertEquals(PosBarcodeIdentifierClient.PackagingLevel.BOX, scanBox.data.resolvedPackagingLevel);
        assertEquals(100, scanBox.data.packQuantityMultiplier);

        // Scan strip barcode -> resolves STRIP level with multiplier 10
        var scanStrip = client.resolveBarcode(tenant1, "8903334445556");
        assertEquals(PosBarcodeIdentifierClient.UiStatus.SUCCESS, scanStrip.status);
        assertTrue(scanStrip.data.matched);
        assertEquals(PosBarcodeIdentifierClient.PackagingLevel.STRIP, scanStrip.data.resolvedPackagingLevel);
        assertEquals(10, scanStrip.data.packQuantityMultiplier);
    }

    @Test
    @DisplayName("P136-D4: Cashier lookup permitted for checkout; cashier registration denied with ERR_STAFF_ROLE_DENIED")
    void testCashierLookupPermittedRegistrationDenied() {
        // Manager registers barcode
        var regRes = client.registerBarcode(
                tenant1,
                "ret-thermometer-01",
                PosBarcodeIdentifierClient.TargetEntityType.NON_MEDICINE,
                "Omron Digital Thermometer",
                350.00,
                "8905556667778",
                PosBarcodeIdentifierClient.IdentifierType.PRIMARY_EAN13_GTIN,
                PosBarcodeIdentifierClient.PackagingLevel.UNIT,
                1,
                1,
                "Retail Unit",
                "BRANCH_MANAGER"
        );
        assertEquals(PosBarcodeIdentifierClient.UiStatus.SUCCESS, regRes.status);

        // Cashier scans at checkout -> Permitted
        var scanRes = client.resolveBarcode(tenant1, "8905556667778");
        assertEquals(PosBarcodeIdentifierClient.UiStatus.SUCCESS, scanRes.status);
        assertTrue(scanRes.data.matched);
        assertEquals(350.00, scanRes.data.resolvedMrp);

        // Cashier attempts to register a barcode -> Denied
        var cashierReg = client.registerBarcode(
                tenant1,
                "ret-thermometer-02",
                PosBarcodeIdentifierClient.TargetEntityType.NON_MEDICINE,
                "Digital Thermometer Gen 2",
                380.00,
                "8905556667779",
                PosBarcodeIdentifierClient.IdentifierType.PRIMARY_EAN13_GTIN,
                PosBarcodeIdentifierClient.PackagingLevel.UNIT,
                1,
                1,
                "Unauthorized Cashier Attempt",
                "CASHIER"
        );
        assertEquals(PosBarcodeIdentifierClient.UiStatus.PERMISSION_DENIED, cashierReg.status);
        assertEquals("ERR_STAFF_ROLE_DENIED", cashierReg.errorCode);
    }

    @Test
    @DisplayName("P136-D5: Cross-tenant barcode update blocked with ERR_CROSS_TENANT_ACCESS")
    void testCrossTenantBarcodeUpdateBlocked() {
        var regRes = client.registerBarcode(
                tenant1,
                "med-sergel-20",
                PosBarcodeIdentifierClient.TargetEntityType.MEDICINE,
                "Sergel 20mg Capsule",
                7.00,
                "8906667778889",
                PosBarcodeIdentifierClient.IdentifierType.INTERNAL_STORE_BARCODE,
                PosBarcodeIdentifierClient.PackagingLevel.UNIT,
                1,
                1,
                "Tenant 1 Internal Code",
                "BRANCH_MANAGER"
        );
        String identId = regRes.data.identifierId;

        // Manager of Tenant 2 attempts priority mutation on Tenant 1's barcode
        var crossMutate = client.updatePriority(identId, tenant2, "BRANCH_MANAGER", 5);
        assertEquals(PosBarcodeIdentifierClient.UiStatus.PERMISSION_DENIED, crossMutate.status);
        assertEquals("ERR_CROSS_TENANT_ACCESS", crossMutate.errorCode);

        // Manager of Tenant 2 attempts archival
        var crossArchive = client.archiveBarcode(identId, tenant2, "BRANCH_MANAGER", "Unauthorized");
        assertEquals(PosBarcodeIdentifierClient.UiStatus.PERMISSION_DENIED, crossArchive.status);
        assertEquals("ERR_CROSS_TENANT_ACCESS", crossArchive.errorCode);
    }

    @Test
    @DisplayName("P136-D6: Offline fallback serves cached barcode resolution with OFFLINE status")
    void testOfflineModeServesCachedBarcodeResolution() {
        var regRes = client.registerBarcode(
                tenant1,
                "med-fexo-120",
                PosBarcodeIdentifierClient.TargetEntityType.MEDICINE,
                "Fexo 120mg Tablet",
                8.00,
                "8908889990001",
                PosBarcodeIdentifierClient.IdentifierType.PRIMARY_EAN13_GTIN,
                PosBarcodeIdentifierClient.PackagingLevel.UNIT,
                1,
                1,
                "Fexo Unit Barcode",
                "BRANCH_MANAGER"
        );
        assertEquals(PosBarcodeIdentifierClient.UiStatus.SUCCESS, regRes.status);

        // Network goes down
        client.setOffline(true);
        assertTrue(client.isOffline());

        var offlineScan = client.resolveBarcode(tenant1, "8908889990001");
        assertEquals(PosBarcodeIdentifierClient.UiStatus.OFFLINE, offlineScan.status);
        assertNotNull(offlineScan.data);
        assertTrue(offlineScan.data.matched);
        assertEquals("Fexo 120mg Tablet", offlineScan.data.identifierRecord.targetName);
        assertEquals(8.00, offlineScan.data.resolvedMrp);
    }
}
