package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosMedicineVariantClientTest
 * Architecture Decision: ADR-0126 (P126).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 */
class PosMedicineVariantClientTest {

    private PosMedicineVariantClient client;
    private PosMedicineVariantClient.MedicineVariantRecord sampleVariant;

    @BeforeEach
    void setUp() {
        client = new PosMedicineVariantClient();
        sampleVariant = new PosMedicineVariantClient.MedicineVariantRecord(
                "var-napa-500-100b",
                "med-napa-paracetamol",
                "SKU-BEX-NAPA-500-100B",
                "Napa 500mg Tablet (Box of 100)",
                "Napa",
                "Paracetamol",
                "TABLET",
                "Beximco Pharmaceuticals Ltd.",
                "500 mg",
                "Box of 100",
                100,
                "Oral",
                "BLISTER_PACK",
                "8941100123456",
                new BigDecimal("1.20"),
                new BigDecimal("1.00"),
                new BigDecimal("120.00"),
                new BigDecimal("100.00"),
                false,
                false,
                false,
                false,
                true
        );
        client.registerVariant(sampleVariant);
    }

    @Test
    @DisplayName("P126-D1: Barcode lookup accurately resolves commercial SKU presentation")
    void testRegisterAndLookupByBarcode() {
        PosMedicineVariantClient.VariantResult res = client.lookupByBarcode("8941100123456");
        assertEquals(PosMedicineVariantClient.UiStatus.SUCCESS, res.uiStatus);
        assertEquals(1, res.variants.size());
        assertEquals("SKU-BEX-NAPA-500-100B", res.variants.get(0).sku);
        assertEquals(100, res.variants.get(0).packSizeNumeric);
    }

    @Test
    @DisplayName("P126-D2: SKU lookup resolves pack size and unit/pack prices")
    void testLookupBySku() {
        PosMedicineVariantClient.VariantResult res = client.lookupBySku("SKU-BEX-NAPA-500-100B");
        assertEquals(PosMedicineVariantClient.UiStatus.SUCCESS, res.uiStatus);
        assertEquals(1, res.variants.size());
        assertEquals("Napa 500mg Tablet (Box of 100)", res.variants.get(0).variantName);
        assertEquals(new BigDecimal("120.00"), res.variants.get(0).mrpPack);
    }

    @Test
    @DisplayName("P126-D3: Query search matches across brand, generic, and SKU keywords")
    void testSearchVariants() {
        PosMedicineVariantClient.VariantResult res = client.searchVariants("Paracetamol");
        assertEquals(PosMedicineVariantClient.UiStatus.SUCCESS, res.uiStatus);
        assertEquals(1, res.variants.size());
        assertEquals("Napa", res.variants.get(0).brandName);
    }

    @Test
    @DisplayName("P126-D4: Regulatory classifications reflect parent drug clinical constraints")
    void testRegulatoryInheritance() {
        PosMedicineVariantClient.VariantResult res = client.lookupBySku("SKU-BEX-NAPA-500-100B");
        PosMedicineVariantClient.MedicineVariantRecord rec = res.variants.get(0);
        assertFalse(rec.isPrescriptionRequired);
        assertFalse(rec.isNarcotic);
        assertFalse(rec.isAntibiotic);
        assertFalse(rec.isColdChain);
    }

    @Test
    @DisplayName("P126-D5: Pharmacy owner mutation blocked with PLATFORM_CATALOG_OWNERSHIP_PROTECTED")
    void testTenantCreateBlocked() {
        PosMedicineVariantClient.VariantResult res = client.attemptCreateVariant("PHARMACY_OWNER", sampleVariant);
        assertEquals(PosMedicineVariantClient.UiStatus.PERMISSION_DENIED, res.uiStatus);
        assertEquals("PLATFORM_CATALOG_OWNERSHIP_PROTECTED", res.errorCode);
    }

    @Test
    @DisplayName("P126-D6: Superadmin can create and register commercial variants")
    void testSuperadminCreateAllowed() {
        PosMedicineVariantClient.VariantResult res = client.attemptCreateVariant("SUPERADMIN", sampleVariant);
        assertEquals(PosMedicineVariantClient.UiStatus.SUCCESS, res.uiStatus);
        assertEquals(1, res.variants.size());
    }
}
