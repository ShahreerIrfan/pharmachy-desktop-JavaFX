package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0071: JavaFX Desktop POS Tenant Medicine Config Client Tests")
class PosTenantMedicineConfigClientTest {

    @Test
    @DisplayName("Role RBAC: Pharmacy owner, manager, pharmacist can modify; Cashier cannot")
    void testCanRoleModifyTenantConfig() {
        assertTrue(PosTenantMedicineConfigClient.canRoleModifyTenantConfig("SUPERADMIN"));
        assertTrue(PosTenantMedicineConfigClient.canRoleModifyTenantConfig("PHARMACY_OWNER"));
        assertTrue(PosTenantMedicineConfigClient.canRoleModifyTenantConfig("BRANCH_MANAGER"));
        assertTrue(PosTenantMedicineConfigClient.canRoleModifyTenantConfig("STAFF_PHARMACIST"));

        assertFalse(PosTenantMedicineConfigClient.canRoleModifyTenantConfig("CASHIER"));
        assertFalse(PosTenantMedicineConfigClient.canRoleModifyTenantConfig("GUEST"));
        assertFalse(PosTenantMedicineConfigClient.canRoleModifyTenantConfig(null));
    }

    @Test
    @DisplayName("Resolves effective brand name with local override fallback")
    void testResolveEffectiveBrandName() {
        assertEquals("নাপা ৫০০", PosTenantMedicineConfigClient.resolveEffectiveBrandName("নাপা ৫০০", "Napa"));
        assertEquals("Napa", PosTenantMedicineConfigClient.resolveEffectiveBrandName("", "Napa"));
        assertEquals("Napa", PosTenantMedicineConfigClient.resolveEffectiveBrandName(null, "Napa"));
        assertEquals("Napa", PosTenantMedicineConfigClient.resolveEffectiveBrandName("   ", "Napa"));
    }

    @Test
    @DisplayName("Resolves effective retail price with custom MRP fallback")
    void testResolveEffectivePrice() {
        assertEquals("3.50", PosTenantMedicineConfigClient.resolveEffectivePrice("3.50", "2.50"));
        assertEquals("2.50", PosTenantMedicineConfigClient.resolveEffectivePrice("", "2.50"));
        assertEquals("2.50", PosTenantMedicineConfigClient.resolveEffectivePrice(null, "2.50"));
    }

    @Test
    @DisplayName("Regulatory Invariant: Global POM cannot be relaxed by tenant override")
    void testIsPrescriptionMandatory() {
        // 1. Master POM -> Always True even if tenant attempts False!
        assertTrue(PosTenantMedicineConfigClient.isPrescriptionMandatory(true, false, false, false));
        // 2. Master Narcotic -> Always True
        assertTrue(PosTenantMedicineConfigClient.isPrescriptionMandatory(false, true, false, false));
        // 3. Master Antibiotic -> Always True
        assertTrue(PosTenantMedicineConfigClient.isPrescriptionMandatory(false, false, true, false));
        // 4. Master OTC, but tenant enforces strict Rx -> True
        assertTrue(PosTenantMedicineConfigClient.isPrescriptionMandatory(false, false, false, true));
        // 5. Master OTC, no override -> False
        assertFalse(PosTenantMedicineConfigClient.isPrescriptionMandatory(false, false, false, false));
        assertFalse(PosTenantMedicineConfigClient.isPrescriptionMandatory(false, false, false, null));
    }

    @Test
    @DisplayName("Validates custom MRP decimal bounds")
    void testIsValidCustomMrp() {
        assertTrue(PosTenantMedicineConfigClient.isValidCustomMrp("0.01"));
        assertTrue(PosTenantMedicineConfigClient.isValidCustomMrp("50.00"));
        assertTrue(PosTenantMedicineConfigClient.isValidCustomMrp(""));
        assertTrue(PosTenantMedicineConfigClient.isValidCustomMrp(null));

        assertFalse(PosTenantMedicineConfigClient.isValidCustomMrp("0.00"));
        assertFalse(PosTenantMedicineConfigClient.isValidCustomMrp("-5.00"));
        assertFalse(PosTenantMedicineConfigClient.isValidCustomMrp("invalid"));
    }

    @Test
    @DisplayName("Builds endpoint URLs with validation guardrails")
    void testBuildUrls() {
        assertEquals(
                "/api/v2/tenants/medicine-configs/?page=1&page_size=20&search=Napa&is_visible=true",
                PosTenantMedicineConfigClient.buildConfigListUrl(1, 20, "Napa", true)
        );
        assertEquals(
                "/api/v2/tenants/medicine-configs/?page=1&page_size=20",
                PosTenantMedicineConfigClient.buildConfigListUrl(0, 0, "", null)
        );
        assertEquals(
                "/api/v2/tenants/medicine-configs/by-product/prod-azithro-100/",
                PosTenantMedicineConfigClient.buildConfigByProductUrl("prod-azithro-100")
        );
        assertEquals(
                "/api/v2/tenants/medicine-configs/tmc-12345678/",
                PosTenantMedicineConfigClient.buildConfigDetailUrl("tmc-12345678")
        );

        assertThrows(IllegalArgumentException.class, () ->
                PosTenantMedicineConfigClient.buildConfigByProductUrl("invalid product!"));
        assertThrows(IllegalArgumentException.class, () ->
                PosTenantMedicineConfigClient.buildConfigDetailUrl("invalid-config-id"));
    }
}

