package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PosMedicineDetailsPreviewClientTest {

    private PosMedicineDetailsPreviewClient client;
    private final String tenant1 = "tn-banani-101";
    private final String tenant2 = "tn-gulshan-202";

    @BeforeEach
    void setUp() {
        client = new PosMedicineDetailsPreviewClient();

        // 1. Seed standard oral OTC medicine
        var napaTiers = List.of(
                new PosMedicineDetailsPreviewClient.PackagingTierRecord("UNIT", 1, "8901112223334", 1),
                new PosMedicineDetailsPreviewClient.PackagingTierRecord("STRIP", 10, "", 2),
                new PosMedicineDetailsPreviewClient.PackagingTierRecord("BOX", 100, "18901112223331", 3)
        );
        var napaVariants = List.of(
                new PosMedicineDetailsPreviewClient.VariantRecord(
                        "var-napa-100", "SKU-NAPA-100", "Napa 500mg Box 100s",
                        "500 mg", "Box of 100", 100, "BLISTER_PACK", 120.00, 95.00, "18901112223331"
                )
        );

        var napa = new PosMedicineDetailsPreviewClient.MedicinePreviewRecord(
                "med-napa-500", "Napa 500mg", "নাপা ৫০০ মি.গ্রা.",
                "Paracetamol", "প্যারাসিটামল", "Beximco Pharmaceuticals Ltd.",
                "DAR-012-334", "APPROVED", "Tablet", "500 mg", 500.0, "mg", "MASS",
                "Oral", "8901112223334", 1.20, 0.95, 1.20, false, "", "",
                false, false, false, "NONE", "Over-The-Counter (OTC)", "ওভার-দ্য-কাউন্টার (OTC)",
                false, 15.0, 30.0,
                "Store below 30°C in a dry place.", "৩০°সে এর নিচে শুষ্ক স্থানে সংরক্ষণ করুন।",
                napaTiers, napaVariants, false, false, false, null, "hash-dummy-01"
        );
        client.registerMedicine(napa);

        // 2. Seed cold-chain insulin
        var insulin = new PosMedicineDetailsPreviewClient.MedicinePreviewRecord(
                "med-insulin-100", "Glaritus 100 IU", "গ্ল্যারিটাস ১০০ আইইউ",
                "Insulin Glargine", "ইনসুলিন গ্লার্জিন", "Wockhardt Ltd.",
                "DAR-INS-901", "APPROVED", "Injection", "100 IU/mL", 100.0, "IU/mL", "BIOLOGICAL",
                "Subcutaneous", "8904445556667", 450.00, 380.00, 450.00, false, "", "",
                true, false, false, "NONE", "Prescription-Only Medicine (POM)", "প্রেসক্রিপশন আবশ্যক (POM)",
                true, 2.0, 8.0,
                "Store in refrigerator between 2.0°C and 8.0°C. Do not freeze.",
                "২.০°সে থেকে ৮.০°সে তাপমাত্রায় রেফ্রিজারেটরে সংরক্ষণ করুন।",
                List.of(), List.of(), false, false, false, null, "hash-dummy-02"
        );
        client.registerMedicine(insulin);
    }

    @Test
    @DisplayName("P138-D1: Complete medicine preview returns dosage form, strength, route, and packaging hierarchy")
    void testCompleteMedicinePreviewAttributes() {
        var res = client.getMedicinePreview(tenant1, tenant1, "med-napa-500", "BRANCH_MANAGER");
        assertEquals(PosMedicineDetailsPreviewClient.UiStatus.SUCCESS, res.status);
        assertNotNull(res.data);
        assertEquals("Napa 500mg", res.data.brandName);
        assertEquals("Tablet", res.data.dosageForm);
        assertEquals("500 mg", res.data.strength);
        assertEquals("Oral", res.data.route);
        assertEquals(1.20, res.data.mrpUnit);
        assertEquals(3, res.data.packagingHierarchy.size());
        assertEquals(1, res.data.variants.size());
        assertEquals(64, res.data.immutablePreviewHash.length());
    }

    @Test
    @DisplayName("P138-D2: Cold chain injection displays temperature bounds (2-8 C) and refrigerator instructions")
    void testColdChainTemperatureBoundsAndStorage() {
        var res = client.getMedicinePreview(tenant1, tenant1, "med-insulin-100", "STAFF_PHARMACIST");
        assertEquals(PosMedicineDetailsPreviewClient.UiStatus.SUCCESS, res.status);
        assertTrue(res.data.requiresColdChain);
        assertEquals(2.0, res.data.minTempCelsius);
        assertEquals(8.0, res.data.maxTempCelsius);
        assertTrue(res.data.isPrescriptionOnly);
        assertTrue(res.data.storageInstructions.contains("refrigerator"));
        assertTrue(res.data.storageInstructionsBn.contains("রেফ্রিজারেটরে"));
    }

    @Test
    @DisplayName("P138-D3: Prescription and antimicrobial classification indicators accurately represented")
    void testPrescriptionAndAntimicrobialStatus() {
        var res = client.getMedicinePreview(tenant1, tenant1, "med-insulin-100", "STAFF_PHARMACIST");
        assertEquals(PosMedicineDetailsPreviewClient.UiStatus.SUCCESS, res.status);
        assertTrue(res.data.isPrescriptionOnly);
        assertEquals("Prescription-Only Medicine (POM)", res.data.rxClassificationLabel);
        assertFalse(res.data.isNarcotic);
    }

    @Test
    @DisplayName("P138-D4: Tenant store overrides and shelf location accurately integrated")
    void testTenantStoreOverridesAndShelfLocation() {
        client.setTenantOverride(tenant1, "med-napa-500", 1.50, "Fast Action Napa", "Rack B-2");

        // Tenant 1 preview reflects override
        var res1 = client.getMedicinePreview(tenant1, tenant1, "med-napa-500", "BRANCH_MANAGER");
        assertEquals(PosMedicineDetailsPreviewClient.UiStatus.SUCCESS, res1.status);
        assertTrue(res1.data.hasTenantOverride);
        assertEquals(1.50, res1.data.effectiveMrpUnit);
        assertEquals("Fast Action Napa", res1.data.localBrandName);
        assertEquals("Rack B-2", res1.data.shelfLocation);

        // Tenant 2 preview shows standard master price without Tenant 1 override
        var res2 = client.getMedicinePreview(tenant2, tenant2, "med-napa-500", "BRANCH_MANAGER");
        assertEquals(PosMedicineDetailsPreviewClient.UiStatus.SUCCESS, res2.status);
        assertFalse(res2.data.hasTenantOverride);
        assertEquals(1.20, res2.data.effectiveMrpUnit);
    }

    @Test
    @DisplayName("P138-D5: Cashier can preview medicine details, but wholesale cost price is masked to null")
    void testCashierCostPriceMasking() {
        // Cashier role -> costUnit is null
        var cashierRes = client.getMedicinePreview(tenant1, tenant1, "med-napa-500", "CASHIER");
        assertEquals(PosMedicineDetailsPreviewClient.UiStatus.SUCCESS, cashierRes.status);
        assertNull(cashierRes.data.costUnit);
        assertEquals(1.20, cashierRes.data.mrpUnit);

        // Manager role -> costUnit is visible
        var mgrRes = client.getMedicinePreview(tenant1, tenant1, "med-napa-500", "BRANCH_MANAGER");
        assertEquals(PosMedicineDetailsPreviewClient.UiStatus.SUCCESS, mgrRes.status);
        assertEquals(0.95, mgrRes.data.costUnit);
    }

    @Test
    @DisplayName("P138-D6: Cross-tenant override query blocked with ERR_CROSS_TENANT_ACCESS and offline mode serves cached preview")
    void testCrossTenantQueryBlockedAndOfflineCache() {
        // Tenant 2 attempts to query Tenant 1's overrides -> Denied
        var crossRes = client.getMedicinePreview(tenant1, tenant2, "med-napa-500", "BRANCH_MANAGER");
        assertEquals(PosMedicineDetailsPreviewClient.UiStatus.PERMISSION_DENIED, crossRes.status);
        assertEquals("ERR_CROSS_TENANT_ACCESS", crossRes.errorCode);

        // Enter offline mode
        client.setOffline(true);
        assertTrue(client.isOffline());

        var offlineRes = client.getMedicinePreview(tenant1, tenant1, "med-napa-500", "BRANCH_MANAGER");
        assertEquals(PosMedicineDetailsPreviewClient.UiStatus.OFFLINE, offlineRes.status);
        assertNotNull(offlineRes.data);
        assertEquals("Napa 500mg", offlineRes.data.brandName);
    }
}
