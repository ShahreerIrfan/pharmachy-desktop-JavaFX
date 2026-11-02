package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosMedicineAutocompleteClientTest
 * Architecture Decision: ADR-0129 (P129).
 * Unit test suite verifying medicine search autocomplete, barcode prioritization,
 * bilingual transliteration, store overrides, latency target, and tenant isolation.
 */
public class PosMedicineAutocompleteClientTest {

    private PosMedicineAutocompleteClient client;

    @BeforeEach
    void setUp() {
        client = new PosMedicineAutocompleteClient();

        // 1. Napa 500mg (OTC)
        var napa = new PosMedicineAutocompleteClient.CatalogItem(
                "prod-napa-500",
                "Napa",
                "নাপা",
                "Paracetamol",
                "Tablet",
                "500mg",
                "Beximco Pharmaceuticals Ltd.",
                "890123400001",
                new BigDecimal("1.20"),
                false
        );
        napa.localBrandName = "নাপা ৫০০ (স্টোর স্পেশাল)";
        napa.customMrpUnit = new BigDecimal("1.50");
        napa.localSku = "SKU-NAPA-01";
        client.addCatalogItem("tenant-dhanmondi", napa);

        // 2. Seclo 20mg (POM)
        var seclo = new PosMedicineAutocompleteClient.CatalogItem(
                "prod-seclo-20",
                "Seclo",
                "সেকলো",
                "Omeprazole",
                "Capsule",
                "20mg",
                "Square Pharmaceuticals PLC",
                "890123400002",
                new BigDecimal("6.00"),
                true
        );
        client.addCatalogItem("tenant-dhanmondi", seclo);

        // 3. Hidden Medicine (is_visible = false)
        var hidden = new PosMedicineAutocompleteClient.CatalogItem(
                "prod-special-injection",
                "Special Injection",
                "স্পেশাল ইনজেকশন",
                "Morphine",
                "Injection",
                "10mg",
                "Gonoshokti",
                "890123400003",
                new BigDecimal("50.00"),
                true
        );
        hidden.isVisible = false; // Store hides it from front-counter POS
        client.addCatalogItem("tenant-dhanmondi", hidden);
    }

    @Test
    @DisplayName("P129-D1: Exact barcode scan match ranks #1 with score 100")
    void testExactBarcodeMatchRankedTop() {
        var res = client.autocomplete("tenant-dhanmondi", "tenant-dhanmondi", "890123400001", 10, true);

        assertEquals(PosMedicineAutocompleteClient.UiStatus.SUCCESS, res.status);
        assertFalse(res.matches.isEmpty());
        var top = res.matches.get(0);
        assertEquals("prod-napa-500", top.productId);
        assertEquals(100, top.rankScore);
        assertEquals("BARCODE_EXACT", top.matchType);
        // Effective override pricing and store brand applied
        assertEquals(new BigDecimal("1.50"), top.effectiveMrpUnit);
        assertTrue(top.displayName.contains("নাপা ৫০০ (স্টোর স্পেশাল)"));
    }

    @Test
    @DisplayName("P129-D2: Brand prefix match ranks high with score 90")
    void testBrandPrefixMatch() {
        var res = client.autocomplete("tenant-dhanmondi", "tenant-dhanmondi", "sec", 10, true);

        assertEquals(PosMedicineAutocompleteClient.UiStatus.SUCCESS, res.status);
        assertFalse(res.matches.isEmpty());
        var match = res.matches.get(0);
        assertEquals("prod-seclo-20", match.productId);
        assertEquals(90, match.rankScore);
        assertEquals("BRAND_PREFIX", match.matchType);
        assertTrue(match.effectiveRequiresPrescription);
    }

    @Test
    @DisplayName("P129-D3: Bengali transliteration query matches English brand with score 75")
    void testBengaliTransliterationMatch() {
        var res = client.autocomplete("tenant-dhanmondi", "tenant-dhanmondi", "নাপা", 10, true);

        assertEquals(PosMedicineAutocompleteClient.UiStatus.SUCCESS, res.status);
        assertFalse(res.matches.isEmpty());
        var match = res.matches.get(0);
        assertEquals("prod-napa-500", match.productId);
        assertTrue(match.rankScore >= 75);
    }

    @Test
    @DisplayName("P129-D4: Excludes hidden items where is_visible == false")
    void testExcludesHiddenProducts() {
        var res = client.autocomplete("tenant-dhanmondi", "tenant-dhanmondi", "Special", 10, true);

        assertEquals(PosMedicineAutocompleteClient.UiStatus.SUCCESS, res.status);
        // Hidden product shouldn't show up in POS search
        assertTrue(res.matches.stream().noneMatch(m -> m.productId.equals("prod-special-injection")));
    }

    @Test
    @DisplayName("P129-D5: Cross-tenant search is strictly blocked with ERR_CROSS_TENANT_ACCESS")
    void testCrossTenantAccessBlocked() {
        var res = client.autocomplete("tenant-gulshan", "tenant-dhanmondi", "napa", 10, true);

        assertEquals(PosMedicineAutocompleteClient.UiStatus.PERMISSION_DENIED, res.status);
        assertEquals("ERR_CROSS_TENANT_ACCESS", res.errorCode);
    }

    @Test
    @DisplayName("P129-D6: Offline index search resolves results within sub-50ms latency target")
    void testOfflineSearchLatencyTarget() {
        var res = client.autocomplete("tenant-dhanmondi", "tenant-dhanmondi", "napa", 10, false);

        assertEquals(PosMedicineAutocompleteClient.UiStatus.OFFLINE, res.status);
        assertFalse(res.matches.isEmpty());
        // Performance SLO: local POS search should complete in < 50ms
        assertTrue(res.latencyMs < 50.0, "Search took " + res.latencyMs + "ms, expected < 50ms");
    }
}
