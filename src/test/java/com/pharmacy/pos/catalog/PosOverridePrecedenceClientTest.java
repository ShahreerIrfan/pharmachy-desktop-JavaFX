package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosOverridePrecedenceClientTest
 * Architecture Decision: ADR-0128 (P128).
 * Unit test suite verifying global vs tenant precedence resolution,
 * anti-gouging moderation fallback, regulatory invariants, and RBAC guards.
 */
public class PosOverridePrecedenceClientTest {

    private PosOverridePrecedenceClient client;

    @BeforeEach
    void setUp() {
        client = new PosOverridePrecedenceClient();

        // 1. Register Global Master Medicines
        // Napa 500mg (OTC, standard MRP 1.20 BDT)
        client.registerMaster(new PosOverridePrecedenceClient.MasterMedicine(
                "prod-napa-500",
                "Napa 500mg",
                "Paracetamol",
                "Tablet",
                "500mg",
                new BigDecimal("1.20"),
                new BigDecimal("0.90"),
                false, // OTC
                false,
                false
        ));

        // Seclo 20mg (Prescription-only POM, standard MRP 6.00 BDT)
        client.registerMaster(new PosOverridePrecedenceClient.MasterMedicine(
                "prod-seclo-20",
                "Seclo 20mg",
                "Omeprazole",
                "Capsule",
                "20mg",
                new BigDecimal("6.00"),
                new BigDecimal("4.80"),
                true, // POM
                false,
                false
        ));
    }

    @Test
    @DisplayName("P128-D1: Master values take effect when no tenant override exists")
    void testMasterDefaultsWhenNoOverride() {
        var res = client.resolvePrecedence("tenant-dhanmondi", "tenant-dhanmondi", "prod-napa-500", true);

        assertEquals(PosOverridePrecedenceClient.UiStatus.SUCCESS, res.status);
        assertNotNull(res.data);
        assertEquals("Napa 500mg", res.data.effectiveBrandName);
        assertEquals(new BigDecimal("1.20"), res.data.effectiveMrpUnit);
        assertFalse(res.data.isOverridden);
        assertEquals("GLOBAL_MASTER", res.data.provenance.get("brand_name").source);
        assertEquals("GLOBAL_MASTER", res.data.provenance.get("mrp_unit").source);
        assertEquals("MASTER_DEFAULT", res.data.provenance.get("mrp_unit").rule);
    }

    @Test
    @DisplayName("P128-D2: Approved tenant brand and MRP override take precedence")
    void testApprovedTenantOverridePrecedence() {
        // Propose standard override (MRP 1.50 <= 1.20 * 1.5 = 1.80)
        var res = client.proposeOverride(
                "tenant-dhanmondi",
                "tenant-dhanmondi",
                "PHARMACY_OWNER",
                "prod-napa-500",
                "নাপা ৫০০ মিগ্রা (স্টোর স্পেশাল)",
                new BigDecimal("1.50"),
                null
        );

        assertEquals(PosOverridePrecedenceClient.UiStatus.SUCCESS, res.status);
        assertNotNull(res.data);
        assertEquals("নাপা ৫০০ মিগ্রা (স্টোর স্পেশাল)", res.data.effectiveBrandName);
        assertEquals(new BigDecimal("1.50"), res.data.effectiveMrpUnit);
        assertTrue(res.data.isOverridden);
        assertEquals("APPROVED", res.data.moderationStatus);
        assertEquals("TENANT_OVERRIDE", res.data.provenance.get("brand_name").source);
        assertEquals("TENANT_OVERRIDE", res.data.provenance.get("mrp_unit").source);
    }

    @Test
    @DisplayName("P128-D3: Excessive price hike (>50%) is placed in PENDING_REVIEW and falls back to GLOBAL_MASTER MRP")
    void testExcessivePriceHikeTriggersPendingReviewAndMasterFallback() {
        // Master MRP is 1.20. Propose 3.00 (> 1.20 * 1.50 = 1.80)
        var res = client.proposeOverride(
                "tenant-dhanmondi",
                "tenant-dhanmondi",
                "BRANCH_MANAGER",
                "prod-napa-500",
                "Napa Premium",
                new BigDecimal("3.00"),
                null
        );

        assertEquals(PosOverridePrecedenceClient.UiStatus.SUCCESS, res.status);
        assertNotNull(res.data);
        assertTrue(res.data.isOverridden);
        assertEquals("PENDING_REVIEW", res.data.moderationStatus);
        // Precedence fallback: unapproved price cannot be sold; falls back to master 1.20!
        assertEquals(new BigDecimal("1.20"), res.data.effectiveMrpUnit);
        assertEquals("GLOBAL_MASTER", res.data.provenance.get("mrp_unit").source);
        assertEquals("MODERATION_PENDING_FALLBACK", res.data.provenance.get("mrp_unit").rule);
    }

    @Test
    @DisplayName("P128-D4: Regulatory prescription non-relaxation is strictly rejected")
    void testRegulatoryRelaxationForbidden() {
        // Seclo 20 is Prescription-Only. Propose requiresPrescriptionOverride = False
        var res = client.proposeOverride(
                "tenant-dhanmondi",
                "tenant-dhanmondi",
                "PHARMACY_OWNER",
                "prod-seclo-20",
                "Seclo OTC",
                new BigDecimal("6.00"),
                false // Attempt to relax POM
        );

        assertEquals(PosOverridePrecedenceClient.UiStatus.ERROR, res.status);
        assertEquals("ERR_REGULATORY_RELAXATION_FORBIDDEN", res.errorCode);
    }

    @Test
    @DisplayName("P128-D5: Cashier role is denied override mutation with ERR_STAFF_ROLE_DENIED")
    void testCashierRoleDenied() {
        var res = client.proposeOverride(
                "tenant-dhanmondi",
                "tenant-dhanmondi",
                "CASHIER",
                "prod-napa-500",
                "Cashier Brand",
                new BigDecimal("1.30"),
                null
        );

        assertEquals(PosOverridePrecedenceClient.UiStatus.PERMISSION_DENIED, res.status);
        assertEquals("ERR_STAFF_ROLE_DENIED", res.errorCode);
    }

    @Test
    @DisplayName("P128-D6: Cross-tenant resolution or mutation is denied with ERR_CROSS_TENANT_ACCESS")
    void testCrossTenantAccessDenied() {
        var res = client.resolvePrecedence(
                "tenant-gulshan",
                "tenant-dhanmondi", // Foreign tenant
                "prod-napa-500",
                true
        );

        assertEquals(PosOverridePrecedenceClient.UiStatus.PERMISSION_DENIED, res.status);
        assertEquals("ERR_CROSS_TENANT_ACCESS", res.errorCode);
    }
}
