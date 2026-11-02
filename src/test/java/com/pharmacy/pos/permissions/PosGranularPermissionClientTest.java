package com.pharmacy.pos.permissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PosGranularPermissionClient Test (P104 / ADR-0104)")
class PosGranularPermissionClientTest {

    @Test
    @DisplayName("Pharmacy Owner and Superadmin have full 9 actions")
    void testOwnerAndSuperadminFullAuthority() {
        var ownerState = PosGranularPermissionClient.createOfflineFallback("usr-1", "tn-1", "PHARMACY_OWNER");
        assertTrue(ownerState.allowsSale());
        assertTrue(ownerState.allowsRefund());
        assertTrue(ownerState.allowsDiscount());
        assertTrue(ownerState.allowsCostVisibility());
        assertTrue(ownerState.allowsStockChange());
        assertTrue(ownerState.allowsPurchasing());
        assertTrue(ownerState.allowsExpenseApproval());
        assertTrue(ownerState.allowsExport());
        assertTrue(ownerState.allowsRxReview());

        var adminState = PosGranularPermissionClient.createOfflineFallback("usr-2", "tn-1", "SUPERADMIN");
        assertTrue(adminState.allowsCostVisibility());
        assertTrue(adminState.allowsExport());
    }

    @Test
    @DisplayName("Cashier is restricted to sale only")
    void testCashierRestrictedToSale() {
        var cashier = PosGranularPermissionClient.createOfflineFallback("usr-c", "tn-1", "CASHIER");
        assertTrue(cashier.allowsSale());
        assertFalse(cashier.allowsRefund());
        assertFalse(cashier.allowsDiscount());
        assertFalse(cashier.allowsCostVisibility());
        assertFalse(cashier.allowsStockChange());
        assertFalse(cashier.allowsPurchasing());
        assertFalse(cashier.allowsExpenseApproval());
        assertFalse(cashier.allowsExport());
        assertFalse(cashier.allowsRxReview());

        var evalRefund = PosGranularPermissionClient.evaluateLocalAction("CASHIER", "refund", "br-1", "br-1");
        assertFalse(evalRefund.isAllowed);
        assertEquals("ERR_GRANULAR_PERMISSION_DENIED", evalRefund.errorCode);
    }

    @Test
    @DisplayName("Pharmacist has sale and rx_review authority but no cost visibility")
    void testPharmacistPermissions() {
        var pharmacist = PosGranularPermissionClient.createOfflineFallback("usr-p", "tn-1", "STAFF_PHARMACIST");
        assertTrue(pharmacist.allowsSale());
        assertTrue(pharmacist.allowsRxReview());
        assertFalse(pharmacist.allowsCostVisibility());
        assertFalse(pharmacist.allowsStockChange());
        assertFalse(pharmacist.allowsExpenseApproval());
    }

    @Test
    @DisplayName("Branch Manager has branch-scoped actions and branch isolation")
    void testBranchManagerBranchIsolation() {
        var sameBranch = PosGranularPermissionClient.evaluateLocalAction("BRANCH_MANAGER", "refund", "branch-north", "branch-north");
        assertTrue(sameBranch.isAllowed);

        var crossBranch = PosGranularPermissionClient.evaluateLocalAction("BRANCH_MANAGER", "refund", "branch-north", "branch-south");
        assertFalse(crossBranch.isAllowed);
        assertTrue(crossBranch.messageEn.contains("Cross-branch"));
    }

    @Test
    @DisplayName("Cost price masking strips wholesale cost fields when cost_visibility is false")
    void testCostPriceMasking() {
        Map<String, Object> med = new HashMap<>();
        med.put("product_id", "med-001");
        med.put("brand_name", "Napa Extra");
        med.put("mrp_unit", "3.00");
        med.put("cost_unit", "2.10");
        med.put("cogs_bdt", "2.10");

        // Masked for Cashier
        Map<String, Object> masked = PosGranularPermissionClient.maskMedicineCost(med, false);
        assertNull(masked.get("cost_unit"));
        assertNull(masked.get("cogs_bdt"));
        assertEquals("3.00", masked.get("mrp_unit"));

        // Unmasked for Manager
        Map<String, Object> visible = PosGranularPermissionClient.maskMedicineCost(med, true);
        assertEquals("2.10", visible.get("cost_unit"));
    }

    @Test
    @DisplayName("ViewState transitions properly across states")
    void testViewStateTransitions() {
        var loading = PosGranularPermissionClient.GranularViewState.loading();
        assertEquals(PosGranularPermissionClient.UiStatus.LOADING, loading.status);

        var denied = PosGranularPermissionClient.GranularViewState.denied("Denied", "অননুমোদিত");
        assertEquals(PosGranularPermissionClient.UiStatus.PERMISSION_DENIED, denied.status);
    }
}
