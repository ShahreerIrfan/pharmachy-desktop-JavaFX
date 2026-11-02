package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PosRegulatorySourceClientTest {

    private PosRegulatorySourceClient client;
    private final String productId = "med-insulin-100";

    @BeforeEach
    void setUp() {
        client = new PosRegulatorySourceClient();
        Map<String, Object> product = new HashMap<>();
        product.put("brand_name", "Insulin Glargine");
        product.put("generic_name", "Insulin Glargine");
        product.put("requires_cold_chain", false);
        product.put("is_prescription_only", false);
        client.registerProduct(productId, product);
    }

    @Test
    @DisplayName("P134-D1: Attach regulatory source with storage rules and synchronize cold chain bounds to product")
    void testAttachRegulatorySourceWithStorageRuleSync() {
        var storage = new PosRegulatorySourceClient.StorageRuleSpecs(
                true,
                2.0,
                8.0,
                true,
                60,
                "Store in refrigerator between 2-8 C"
        );
        var classification = new PosRegulatorySourceClient.ClassificationSpecs(
                "PRESCRIPTION_ONLY",
                false,
                false,
                "Cold-chain dispensed"
        );

        var result = client.attachSource(
                productId,
                PosRegulatorySourceClient.SourceType.DGDA_GAZETTE,
                PosRegulatorySourceClient.RuleTarget.CLASSIFICATION_AND_STORAGE,
                "DGDA/DL/Gazette-2026-088",
                "DGDA Bangladesh",
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 5, 10),
                24,
                LocalDate.of(2028, 6, 1),
                "https://dgda.gov.bd/glargine.pdf",
                storage,
                classification,
                "STAFF_PHARMACIST",
                "lead_pharmacist@pharma.local",
                "Verified against statutory cold chain list"
        );

        assertEquals(PosRegulatorySourceClient.UiStatus.SUCCESS, result.status);
        assertNotNull(result.data);
        assertEquals(productId, result.data.productId);
        assertEquals(1, result.data.versionNumber);
        assertTrue(result.data.isActive);
        assertEquals(64, result.data.immutableSourceHash.length());

        // Check synchronized parent product
        Map<String, Object> product = client.getProduct(productId);
        assertTrue((Boolean) product.get("requires_cold_chain"));
        assertEquals(2.0, product.get("min_temp_celsius"));
        assertEquals(8.0, product.get("max_temp_celsius"));
        assertTrue((Boolean) product.get("is_prescription_only"));
        assertEquals("DGDA/DL/Gazette-2026-088", product.get("regulatory_source"));
    }

    @Test
    @DisplayName("P134-D2: Cold chain rule violation (min_temp >= max_temp) rejected")
    void testColdChainInvertedTemperatureRejected() {
        var storage = new PosRegulatorySourceClient.StorageRuleSpecs(
                true,
                12.0,
                4.0,  // Inverted: min > max
                false,
                null,
                ""
        );

        var result = client.attachSource(
                productId,
                PosRegulatorySourceClient.SourceType.MANUFACTURER_MONOGRAPH,
                PosRegulatorySourceClient.RuleTarget.STORAGE_CONDITION,
                "INCEPTA-SMPC-001",
                "Incepta Regulatory",
                LocalDate.of(2026, 1, 1),
                null,
                12,
                null,
                "",
                storage,
                null,
                "STAFF_PHARMACIST",
                "pharmacist@pharma.local",
                ""
        );

        assertEquals(PosRegulatorySourceClient.UiStatus.ERROR, result.status);
        assertEquals("ERR_COLD_CHAIN_BOUNDARY_VIOLATION", result.errorCode);
    }

    @Test
    @DisplayName("P134-D3: next_review_due_date earlier than effective_date rejected")
    void testNextReviewDateEarlierThanEffectiveDateRejected() {
        var result = client.attachSource(
                productId,
                PosRegulatorySourceClient.SourceType.PHARMACOPOEIA,
                PosRegulatorySourceClient.RuleTarget.CLASSIFICATION,
                "BP-2026-MONO",
                "British Pharmacopoeia",
                LocalDate.of(2026, 6, 1),
                null,
                12,
                LocalDate.of(2025, 1, 1),  // Invalid review date
                "",
                null,
                null,
                "STAFF_PHARMACIST",
                "pharmacist@pharma.local",
                ""
        );

        assertEquals(PosRegulatorySourceClient.UiStatus.ERROR, result.status);
        assertEquals("ERR_INVALID_REVIEW_DATE", result.errorCode);
    }

    @Test
    @DisplayName("P134-D4: Multiple revisions of same document reference number increment version and generate distinct hashes")
    void testRevisionIncrementsVersionAndGeneratesNewHash() {
        String docRef = "DGDA-CIRCULAR-401";

        // Version 1
        var v1Res = client.attachSource(
                productId,
                PosRegulatorySourceClient.SourceType.DGDA_CIRCULAR,
                PosRegulatorySourceClient.RuleTarget.CLASSIFICATION,
                docRef,
                "DGDA",
                LocalDate.of(2026, 1, 1),
                null,
                12,
                null,
                "",
                null,
                null,
                "STAFF_PHARMACIST",
                "pharmacist@pharma.local",
                "Initial circular"
        );
        assertEquals(PosRegulatorySourceClient.UiStatus.SUCCESS, v1Res.status);
        assertEquals(1, v1Res.data.versionNumber);

        // Version 2 (Revised Effective Date)
        var v2Res = client.attachSource(
                productId,
                PosRegulatorySourceClient.SourceType.DGDA_CIRCULAR,
                PosRegulatorySourceClient.RuleTarget.CLASSIFICATION,
                docRef,
                "DGDA",
                LocalDate.of(2026, 7, 1),
                null,
                12,
                null,
                "",
                null,
                null,
                "STAFF_PHARMACIST",
                "pharmacist@pharma.local",
                "Re-filed extension"
        );
        assertEquals(PosRegulatorySourceClient.UiStatus.SUCCESS, v2Res.status);
        assertEquals(2, v2Res.data.versionNumber);
        assertNotEquals(v1Res.data.immutableSourceHash, v2Res.data.immutableSourceHash);
    }

    @Test
    @DisplayName("P134-D5: Cashier role denied from attaching or mutating regulatory sources")
    void testCashierRoleDeniedFromAttachingOrMutating() {
        var result = client.attachSource(
                productId,
                PosRegulatorySourceClient.SourceType.DGDA_GAZETTE,
                PosRegulatorySourceClient.RuleTarget.STORAGE_CONDITION,
                "DGDA-CASHIER-ATTEMPT",
                "DGDA",
                LocalDate.of(2026, 1, 1),
                null,
                12,
                null,
                "",
                null,
                null,
                "CASHIER",
                "cashier@pharma.local",
                ""
        );

        assertEquals(PosRegulatorySourceClient.UiStatus.PERMISSION_DENIED, result.status);
        assertEquals("ERR_STAFF_ROLE_DENIED", result.errorCode);
    }

    @Test
    @DisplayName("P134-D6: Offline fallback serves cached regulatory sources safely")
    void testOfflineModeServesCachedRegulatorySources() {
        var attachRes = client.attachSource(
                productId,
                PosRegulatorySourceClient.SourceType.WHO_AWARE,
                PosRegulatorySourceClient.RuleTarget.CLASSIFICATION,
                "WHO-AWARE-ACCESS-2026",
                "World Health Organization",
                LocalDate.of(2026, 3, 1),
                null,
                24,
                null,
                "",
                null,
                null,
                "STAFF_PHARMACIST",
                "pharmacist@pharma.local",
                ""
        );
        assertEquals(PosRegulatorySourceClient.UiStatus.SUCCESS, attachRes.status);

        // Disconnect
        client.setOffline(true);
        assertTrue(client.isOffline());

        var searchRes = client.searchAttachments(productId, "WHO-AWARE", null);
        assertEquals(PosRegulatorySourceClient.UiStatus.OFFLINE, searchRes.status);
        assertNotNull(searchRes.data);
        assertEquals(1, searchRes.data.size());
        assertEquals(attachRes.data.attachmentId, searchRes.data.get(0).attachmentId);
    }
}
