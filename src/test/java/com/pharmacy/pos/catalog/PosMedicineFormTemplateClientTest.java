package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class PosMedicineFormTemplateClientTest {

    private PosMedicineFormTemplateClient client;

    @BeforeEach
    void setUp() {
        client = new PosMedicineFormTemplateClient();
    }

    @Test
    @DisplayName("P131-D1: Pharmacist queries default TABLET template; returns 12-column grid and box/strip/unit hierarchy")
    void testTabletTemplateResolution() {
        var result = client.getTemplate("TABLET");

        assertEquals(PosMedicineFormTemplateClient.UiStatus.SUCCESS, result.status);
        assertNotNull(result.data);
        assertEquals("TABLET", result.data.dosageFormCode);
        assertEquals("TABLET_STANDARD", result.data.templateCode);
        assertEquals("v1.0", result.data.templateVersion);
        assertTrue(result.data.isDefault);
        assertEquals(12, result.data.layoutRules.gridColumns);

        // Verify packaging sections
        var sections = result.data.layoutRules.sections;
        assertTrue(sections.stream().anyMatch(s -> "packaging_hierarchy".equals(s.sectionId)));

        // Verify allowed values
        var allowed = result.data.allowedValues;
        assertTrue(allowed.strengthUnits.contains("mg"));
        assertTrue(allowed.containerTypes.contains("STRIP"));
        assertEquals(64, result.data.immutableVersionHash.length());
    }

    @Test
    @DisplayName("P131-D2: Querying SYRUP template includes liquid volume (mL) and bottle concentration units (mg/5mL)")
    void testSyrupTemplateResolution() {
        var result = client.getTemplate("SYRUP");

        assertEquals(PosMedicineFormTemplateClient.UiStatus.SUCCESS, result.status);
        assertNotNull(result.data);
        assertEquals("SYRUP", result.data.dosageFormCode);
        assertEquals("SYRUP_LIQUID", result.data.templateCode);

        // Verify liquid volume section and allowed concentration
        var sections = result.data.layoutRules.sections;
        assertTrue(sections.stream().anyMatch(s -> "liquid_volume_spec".equals(s.sectionId)));
        assertTrue(result.data.allowedValues.strengthUnits.contains("mg/5mL"));
        assertTrue(result.data.allowedValues.containerTypes.contains("PET_BOTTLE"));
    }

    @Test
    @DisplayName("P131-D3: Querying INJECTION template enforces cold-chain temperature thresholds and vial/ampoule packaging")
    void testInjectionTemplateResolution() {
        var result = client.getTemplate("INJECTION");

        assertEquals(PosMedicineFormTemplateClient.UiStatus.SUCCESS, result.status);
        assertNotNull(result.data);
        assertEquals("INJECTION", result.data.dosageFormCode);

        // Verify cold-chain section
        var sections = result.data.layoutRules.sections;
        assertTrue(sections.stream().anyMatch(s -> "cold_chain_spec".equals(s.sectionId)));

        // Verify parenteral routes and sterile containers
        var allowed = result.data.allowedValues;
        assertTrue(allowed.containerTypes.contains("VIAL"));
        assertTrue(allowed.containerTypes.contains("AMPOULE"));
        assertTrue(allowed.storageConditions.contains("COLD_CHAIN_2_8C"));
        assertTrue(allowed.administrationRoutes.contains("Intravenous (IV)"));
    }

    @Test
    @DisplayName("P131-D4: Dedicated allowed-values query returns valid strength units and container types for UI dropdowns")
    void testAllowedValuesResolution() {
        var result = client.getAllowedValues("TABLET");

        assertEquals(PosMedicineFormTemplateClient.UiStatus.SUCCESS, result.status);
        assertNotNull(result.data);
        assertTrue(result.data.strengthUnits.contains("mg"));
        assertTrue(result.data.strengthUnits.contains("mcg"));
        assertTrue(result.data.administrationRoutes.contains("Oral"));

        // Query non-existent dosage form returns empty
        var emptyRes = client.getAllowedValues("NON_EXISTENT_FORM");
        assertEquals(PosMedicineFormTemplateClient.UiStatus.EMPTY, emptyRes.status);
    }

    @Test
    @DisplayName("P131-D5: Tenant owner or cashier blocked from creating or mutating form templates")
    void testTenantRoleCannotCreateTemplate() {
        var dummyLayout = new PosMedicineFormTemplateClient.LayoutRules(
                12,
                Collections.singletonList(
                        new PosMedicineFormTemplateClient.LayoutSection(
                                "sec1", "Title", "শিরোনাম", false, true, 1, Collections.emptyList()
                        )
                )
        );
        var dummyAllowed = new PosMedicineFormTemplateClient.AllowedValues(
                Collections.singletonList("mg"),
                Collections.singletonList("Oral"),
                Collections.singletonList("STRIP"),
                Collections.singletonList("ROOM_TEMPERATURE"),
                Collections.singletonList("Tablet")
        );

        // Pharmacy Owner attempt
        var rOwner = client.createTemplate(
                "PHARMACY_OWNER",
                "TABLET",
                "TENANT_TAB",
                "v1.0",
                "Custom Tab",
                "কাস্টম ট্যাব",
                "Desc",
                false,
                dummyLayout,
                dummyAllowed
        );
        assertEquals(PosMedicineFormTemplateClient.UiStatus.PERMISSION_DENIED, rOwner.status);
        assertEquals("PLATFORM_CATALOG_OWNERSHIP_PROTECTED", rOwner.errorCode);
        assertNotNull(rOwner.messageBn);

        // Cashier attempt
        var rCashier = client.createTemplate(
                "CASHIER",
                "TABLET",
                "CASHIER_TAB",
                "v1.0",
                "Cashier Tab",
                "ক্যাশিয়ার ট্যাব",
                "Desc",
                false,
                dummyLayout,
                dummyAllowed
        );
        assertEquals(PosMedicineFormTemplateClient.UiStatus.PERMISSION_DENIED, rCashier.status);
        assertEquals("PLATFORM_CATALOG_OWNERSHIP_PROTECTED", rCashier.errorCode);
    }

    @Test
    @DisplayName("P131-D6: Offline fallback serves cached dosage-form template safely during network disconnection")
    void testOfflineFallbackServesCachedTemplate() {
        // Toggle offline mode
        client.setOfflineMode(true);
        assertTrue(client.isOfflineMode());

        var result = client.getTemplate("TABLET");
        assertEquals(PosMedicineFormTemplateClient.UiStatus.OFFLINE, result.status);
        assertNotNull(result.data);
        assertEquals("TABLET", result.data.dosageFormCode);
        assertEquals("STATUS_OFFLINE_CACHE", result.errorCode);

        // Unknown form offline returns empty
        var unknown = client.getTemplate("UNKNOWN_OFFLINE_999");
        assertEquals(PosMedicineFormTemplateClient.UiStatus.EMPTY, unknown.status);
    }
}
