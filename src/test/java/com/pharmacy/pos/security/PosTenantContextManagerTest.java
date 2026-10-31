package com.pharmacy.pos.security;

import com.pharmacy.pos.contracts.PosApiResponseEnvelope.UiState;
import com.pharmacy.pos.security.PosTenantContextManager.PosTenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P049: Desktop POS Tenant Context & Trusted Claims Test Suite")
class PosTenantContextManagerTest {

    @BeforeEach
    void setUp() {
        PosTenantContextManager.clear();
    }

    @Test
    @DisplayName("1. Parses server context response into PosTenantContext with trusted claims")
    void testParseContextFromClaims() {
        Map<String, Object> map = new HashMap<>();
        map.put("tenant_id", "tenant-dhanmondi-care");
        map.put("tenant_name", "Dhanmondi Care Pharmacy");
        map.put("subdomain", "dhanmondi-care");
        map.put("plan_tier", "PRO");
        map.put("branch_id", "b-dhanmondi-01");
        map.put("branch_name", "Main Branch");
        map.put("user_id", "usr-12345");
        map.put("user_role", "PHARMACY_OWNER");
        map.put("resolution_source", "JWT_SIGNED_CLAIMS");
        map.put("is_active", true);

        PosTenantContext context = PosTenantContextManager.parseContext(map);

        assertNotNull(context);
        assertEquals("tenant-dhanmondi-care", context.tenantId());
        assertEquals("Dhanmondi Care Pharmacy", context.tenantName());
        assertEquals("dhanmondi-care", context.subdomain());
        assertEquals("PRO", context.planTier());
        assertEquals("b-dhanmondi-01", context.branchId());
        assertEquals("Main Branch", context.branchName());
        assertEquals("usr-12345", context.userId());
        assertEquals("PHARMACY_OWNER", context.userRole());
        assertEquals("JWT_SIGNED_CLAIMS", context.resolutionSource());
        assertTrue(context.isActive());
    }

    @Test
    @DisplayName("2. Validates tenant integrity: matching tenant succeeds, mismatch fails")
    void testTenantIntegrityValidation() {
        PosTenantContext context = new PosTenantContext(
                "tenant-dhanmondi-care",
                "Dhanmondi Care Pharmacy",
                "dhanmondi-care",
                "STANDARD",
                "b-dhanmondi-01",
                "Main Branch",
                "usr-1",
                "CASHIER",
                "JWT_SIGNED_CLAIMS",
                true
        );

        assertTrue(PosTenantContextManager.validateTenantIntegrity(context, "tenant-dhanmondi-care"));
        assertTrue(PosTenantContextManager.validateTenantIntegrity(context, "TENANT-DHANMONDI-CARE"));
        assertFalse(PosTenantContextManager.validateTenantIntegrity(context, "tenant-uttara-pharma"));
        assertFalse(PosTenantContextManager.validateTenantIntegrity(context, null));
    }

    @Test
    @DisplayName("3. Validates branch integrity against active tenant context")
    void testBranchIntegrityValidation() {
        PosTenantContext context = new PosTenantContext(
                "tenant-dhanmondi-care",
                "Dhanmondi Care Pharmacy",
                "dhanmondi-care",
                "STANDARD",
                "b-dhanmondi-01",
                "Main Branch",
                "usr-1",
                "CASHIER",
                "JWT_SIGNED_CLAIMS",
                true
        );

        assertTrue(PosTenantContextManager.validateBranchIntegrity(context, "b-dhanmondi-01"));
        assertFalse(PosTenantContextManager.validateBranchIntegrity(context, "b-uttara-01"));
        assertFalse(PosTenantContextManager.validateBranchIntegrity(context, null));
    }

    @Test
    @DisplayName("4. Bilingual error message resolution for cross-tenant and inactive tenant violations")
    void testBilingualErrorMessageResolution() {
        Map<String, String> crossTenantMsg = PosTenantContextManager.getBilingualErrorMessage("ERR_CROSS_TENANT_VIOLATION");
        assertNotNull(crossTenantMsg);
        assertTrue(crossTenantMsg.get("en").contains("Cross-tenant access violation"));
        assertTrue(crossTenantMsg.get("bn").contains("অন্য ফার্মেসির তথ্যে প্রবেশাধিকার"));

        Map<String, String> inactiveMsg = PosTenantContextManager.getBilingualErrorMessage("ERR_TENANT_INACTIVE");
        assertNotNull(inactiveMsg);
        assertTrue(inactiveMsg.get("en").contains("inactive or suspended"));
        assertTrue(inactiveMsg.get("bn").contains("নিষ্ক্রিয় বা স্থগিত"));
    }

    @Test
    @DisplayName("5. Maps security error codes to UI PERMISSION_DENIED state")
    void testResolveUiState() {
        assertEquals(UiState.PERMISSION_DENIED, PosTenantContextManager.resolveUiState("ERR_CROSS_TENANT_VIOLATION"));
        assertEquals(UiState.PERMISSION_DENIED, PosTenantContextManager.resolveUiState("ERR_TENANT_INACTIVE"));
        assertEquals(UiState.PERMISSION_DENIED, PosTenantContextManager.resolveUiState("ERR_BRANCH_NOT_FOUND"));
        assertEquals(UiState.ERROR, PosTenantContextManager.resolveUiState("ERR_UNKNOWN"));
    }
}
