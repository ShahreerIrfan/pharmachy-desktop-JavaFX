package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PosNonMedicineRetailClientTest {

    private PosNonMedicineRetailClient client;
    private final String tenant1 = "tn-banani-101";
    private final String tenant2 = "tn-gulshan-202";

    @BeforeEach
    void setUp() {
        client = new PosNonMedicineRetailClient();
    }

    @Test
    @DisplayName("P135-D1: Create valid non-medicine retail item with non-medicine invariants")
    void testCreateValidNonMedicineRetailItem() {
        var result = client.createItem(
                tenant1,
                "OneTouch Select Plus Test Strips 50s",
                "ওয়ানটাচ সিলেক্ট প্লাস স্ট্রিপস",
                PosNonMedicineRetailClient.NonMedicineCategory.DIAGNOSTIC_DEVICES,
                "OneTouch",
                "LifeScan Inc.",
                "8901112223334",
                "SKU-STRIP-50",
                1250.00,
                1050.00,
                5.00,
                "PACK",
                false,
                "Store in dry place",
                false,
                false,
                "BRANCH_MANAGER"
        );

        assertEquals(PosNonMedicineRetailClient.UiStatus.SUCCESS, result.status);
        assertNotNull(result.data);
        assertEquals(tenant1, result.data.tenantId);
        assertFalse(result.data.isMedicine);
        assertFalse(result.data.isPrescriptionOnly);
        assertFalse(result.data.isAntibiotic);
        assertFalse(result.data.isNarcotic);
        assertEquals(1, result.data.versionNumber);
        assertEquals(64, result.data.immutableHash.length());
    }

    @Test
    @DisplayName("P135-D2: Regulatory invariant blocks setting prescription or antibiotic flag on retail item")
    void testRegulatoryDispensingRestrictionsBlocked() {
        var result = client.createItem(
                tenant1,
                "Sterile Surgical Gauze Bandage",
                null,
                PosNonMedicineRetailClient.NonMedicineCategory.SURGICAL_WOUND_CARE,
                "BandagePlus",
                null,
                "8909990001112",
                null,
                45.00,
                30.00,
                0.00,
                "PIECE",
                false,
                null,
                true,  // Attempting prescription flag!
                false,
                "BRANCH_MANAGER"
        );

        assertEquals(PosNonMedicineRetailClient.UiStatus.ERROR, result.status);
        assertEquals("ERR_NON_MEDICINE_REGULATORY_RESTRICTION", result.errorCode);
    }

    @Test
    @DisplayName("P135-D3: Negative or zero MRP price rejected")
    void testInvalidNegativePriceRejected() {
        var result = client.createItem(
                tenant1,
                "Cotton Swab Pack",
                null,
                PosNonMedicineRetailClient.NonMedicineCategory.PERSONAL_HYGIENE,
                null,
                null,
                "8903334445556",
                null,
                -25.00,  // Invalid price
                15.00,
                0.00,
                "PACK",
                false,
                null,
                false,
                false,
                "BRANCH_MANAGER"
        );

        assertEquals(PosNonMedicineRetailClient.UiStatus.ERROR, result.status);
        assertEquals("ERR_INVALID_MRP", result.errorCode);
    }

    @Test
    @DisplayName("P135-D4: Cashier can search items for checkout but is denied from creating items")
    void testCashierCanSearchForPosCheckoutButDeniedFromCreating() {
        // Manager creates item
        var createRes = client.createItem(
                tenant1,
                "Pampers Baby Dry Diapers Large",
                null,
                PosNonMedicineRetailClient.NonMedicineCategory.BABY_MOTHER_CARE,
                "Pampers",
                "P&G",
                "8907778889990",
                null,
                1450.00,
                1200.00,
                5.00,
                "PACK",
                false,
                null,
                false,
                false,
                "BRANCH_MANAGER"
        );
        assertEquals(PosNonMedicineRetailClient.UiStatus.SUCCESS, createRes.status);

        // Cashier searches (Permitted)
        var searchRes = client.searchItems(tenant1, "Pampers", null);
        assertEquals(PosNonMedicineRetailClient.UiStatus.SUCCESS, searchRes.status);
        assertNotNull(searchRes.data);
        assertEquals(1, searchRes.data.size());

        // Cashier attempts create (Denied)
        var cashierCreate = client.createItem(
                tenant1,
                "Cashier Created Syringe",
                null,
                PosNonMedicineRetailClient.NonMedicineCategory.SURGICAL_WOUND_CARE,
                null,
                null,
                "8901230009998",
                null,
                12.00,
                8.00,
                0.00,
                "PIECE",
                false,
                null,
                false,
                false,
                "CASHIER"
        );
        assertEquals(PosNonMedicineRetailClient.UiStatus.PERMISSION_DENIED, cashierCreate.status);
        assertEquals("ERR_STAFF_ROLE_DENIED", cashierCreate.errorCode);
    }

    @Test
    @DisplayName("P135-D5: Cross-tenant mutation is blocked with ERR_CROSS_TENANT_ACCESS")
    void testCrossTenantMutationBlocked() {
        var createRes = client.createItem(
                tenant1,
                "Tenant 1 Thermometer",
                null,
                PosNonMedicineRetailClient.NonMedicineCategory.DIAGNOSTIC_DEVICES,
                null,
                null,
                "8906661112223",
                null,
                350.00,
                280.00,
                0.00,
                "PIECE",
                false,
                null,
                false,
                false,
                "BRANCH_MANAGER"
        );
        String itemId = createRes.data.itemId;

        // Tenant 2 attempts mutation
        var patchRes = client.updateItem(
                itemId,
                tenant2,
                "BRANCH_MANAGER",
                "Mutated Thermometer",
                400.00,
                null,
                false
        );
        assertEquals(PosNonMedicineRetailClient.UiStatus.PERMISSION_DENIED, patchRes.status);
        assertEquals("ERR_CROSS_TENANT_ACCESS", patchRes.errorCode);
    }

    @Test
    @DisplayName("P135-D6: Offline fallback serves cached retail items safely")
    void testOfflineModeServesCachedRetailItems() {
        var createRes = client.createItem(
                tenant1,
                "Accu-Chek Instant Meter",
                null,
                PosNonMedicineRetailClient.NonMedicineCategory.DIAGNOSTIC_DEVICES,
                "Accu-Chek",
                "Roche",
                "8904445556667",
                null,
                2100.00,
                1750.00,
                5.00,
                "PIECE",
                false,
                null,
                false,
                false,
                "BRANCH_MANAGER"
        );
        assertEquals(PosNonMedicineRetailClient.UiStatus.SUCCESS, createRes.status);

        // Disconnect to offline mode
        client.setOffline(true);
        assertTrue(client.isOffline());

        var searchRes = client.searchItems(tenant1, "Accu-Chek", null);
        assertEquals(PosNonMedicineRetailClient.UiStatus.OFFLINE, searchRes.status);
        assertNotNull(searchRes.data);
        assertEquals(1, searchRes.data.size());
        assertEquals("8904445556667", searchRes.data.get(0).barcode);
    }
}
