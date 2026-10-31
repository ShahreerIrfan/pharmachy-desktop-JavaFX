package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0070: JavaFX Desktop POS Global Medicine Master Catalogue Client Tests")
class PosGlobalMedicineCatalogClientTest {

    @Test
    @DisplayName("Centralized master ownership: Only platform operators can mutate global catalog")
    void testCatalogOwnershipPermissions() {
        assertTrue(PosGlobalMedicineCatalogClient.canRoleModifyGlobalCatalog("SUPERADMIN"));
        assertTrue(PosGlobalMedicineCatalogClient.canRoleModifyGlobalCatalog("SUPER_ADMIN"));
        assertTrue(PosGlobalMedicineCatalogClient.canRoleModifyGlobalCatalog("PLATFORM_OPERATOR"));

        // Store-level roles are strictly read-only
        assertFalse(PosGlobalMedicineCatalogClient.canRoleModifyGlobalCatalog("PHARMACY_OWNER"));
        assertFalse(PosGlobalMedicineCatalogClient.canRoleModifyGlobalCatalog("BRANCH_MANAGER"));
        assertFalse(PosGlobalMedicineCatalogClient.canRoleModifyGlobalCatalog("STAFF_PHARMACIST"));
        assertFalse(PosGlobalMedicineCatalogClient.canRoleModifyGlobalCatalog("CASHIER"));
        assertFalse(PosGlobalMedicineCatalogClient.canRoleModifyGlobalCatalog(null));
    }

    @Test
    @DisplayName("Recalled product detection bars sales")
    void testRecalledProductDetection() {
        assertTrue(PosGlobalMedicineCatalogClient.isProductRecalled("RECALLED"));
        assertTrue(PosGlobalMedicineCatalogClient.isProductRecalled("recalled"));

        assertFalse(PosGlobalMedicineCatalogClient.isProductRecalled("APPROVED"));
        assertFalse(PosGlobalMedicineCatalogClient.isProductRecalled("UNDER_REVIEW"));
        assertFalse(PosGlobalMedicineCatalogClient.isProductRecalled("DRAFT"));
        assertFalse(PosGlobalMedicineCatalogClient.isProductRecalled(null));
    }

    @Test
    @DisplayName("Prescription requirement evaluates POM, narcotic, and antibiotic flags")
    void testPrescriptionMandatoryEvaluation() {
        assertTrue(PosGlobalMedicineCatalogClient.isPrescriptionMandatory(true, false, false));
        assertTrue(PosGlobalMedicineCatalogClient.isPrescriptionMandatory(false, true, false));
        assertTrue(PosGlobalMedicineCatalogClient.isPrescriptionMandatory(false, false, true));
        assertTrue(PosGlobalMedicineCatalogClient.isPrescriptionMandatory(true, true, true));

        assertFalse(PosGlobalMedicineCatalogClient.isPrescriptionMandatory(false, false, false));
    }

    @Test
    @DisplayName("Resolves curation status tone accurately")
    void testResolveStatusTone() {
        assertEquals("success", PosGlobalMedicineCatalogClient.resolveStatusTone("APPROVED"));
        assertEquals("warning", PosGlobalMedicineCatalogClient.resolveStatusTone("UNDER_REVIEW"));
        assertEquals("danger", PosGlobalMedicineCatalogClient.resolveStatusTone("RECALLED"));
        assertEquals("info", PosGlobalMedicineCatalogClient.resolveStatusTone("DRAFT"));
        assertEquals("neutral", PosGlobalMedicineCatalogClient.resolveStatusTone("DEPRECATED"));
        assertEquals("neutral", PosGlobalMedicineCatalogClient.resolveStatusTone(null));
    }

    @Test
    @DisplayName("Validates DGDA DAR number format")
    void testValidDarNumber() {
        assertTrue(PosGlobalMedicineCatalogClient.isValidDarNumber("DAR-024-0012"));
        assertTrue(PosGlobalMedicineCatalogClient.isValidDarNumber("DAR-NAPA-500"));
        assertTrue(PosGlobalMedicineCatalogClient.isValidDarNumber("DAR-10293"));

        assertFalse(PosGlobalMedicineCatalogClient.isValidDarNumber("INVALID-024"));
        assertFalse(PosGlobalMedicineCatalogClient.isValidDarNumber("DAR-"));
        assertFalse(PosGlobalMedicineCatalogClient.isValidDarNumber(""));
        assertFalse(PosGlobalMedicineCatalogClient.isValidDarNumber(null));
    }

    @Test
    @DisplayName("Builds catalog browse and detail endpoint URLs")
    void testBuildUrls() {
        assertEquals(
                "/api/v2/products/?page=1&page_size=20&search=Napa",
                PosGlobalMedicineCatalogClient.buildCatalogQueryUrl(1, 20, "Napa")
        );
        assertEquals(
                "/api/v2/products/?page=1&page_size=20",
                PosGlobalMedicineCatalogClient.buildCatalogQueryUrl(0, 0, "")
        );
        assertEquals(
                "/api/v2/products/prod-12345/",
                PosGlobalMedicineCatalogClient.buildProductDetailUrl("prod-12345")
        );

        assertThrows(IllegalArgumentException.class, () ->
                PosGlobalMedicineCatalogClient.buildProductDetailUrl("bad id!"));
        assertThrows(IllegalArgumentException.class, () ->
                PosGlobalMedicineCatalogClient.buildProductDetailUrl(""));
    }
}
