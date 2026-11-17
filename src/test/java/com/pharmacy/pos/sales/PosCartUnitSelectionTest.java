package com.pharmacy.pos.sales;

import com.pharmacy.pos.controllers.PosController;
import com.pharmacy.pos.models.TenantSession;
import com.pharmacy.pos.sales.PosFastSearchCartClient.FastSearchCandidateDto;
import com.pharmacy.pos.sales.PosFastSearchCartClient.PackagingOptionDto;
import com.pharmacy.pos.sales.PosFastSearchCartClient.UnitOptionsResponseDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.ApiResponseResult;
import com.pharmacy.pos.sales.PosSaleDomainClient.ClientOutcome;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosSaleLineDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosSaleOrderDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.UiState;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P223 Cart Item Unit Selection Desktop Tests.
 * Architecture Decision: ADR-0223 (P223).
 */
public class PosCartUnitSelectionTest {

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyStarted) {
            latch.countDown();
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "JavaFX toolkit must start");
    }

    @Test
    @DisplayName("1. Client parses unit-options, posts select-unit conversion, and maps PERMISSION_DENIED/ERROR/OFFLINE")
    void testClientUnitOptionsAndSelectUnitStates() {
        AtomicInteger callIdx = new AtomicInteger(0);
        PosFastSearchCartClient client = new PosFastSearchCartClient("http://localhost:8000", (m, path, body, tok) -> {
            int idx = callIdx.getAndIncrement();
            if (idx == 0) {
                assertEquals("GET", m);
                assertTrue(path.contains("/api/v2/pos/sales/sale-u1/unit-options/"));
                return new ApiResponseResult(200, """
                        {
                          "tenant_id": "ten-a",
                          "branch_id": "br-a",
                          "sale_id": "sale-u1",
                          "line_id": "line-1",
                          "current_packaging_unit": "BOX",
                          "current_conversion_factor": 10,
                          "current_quantity_units": 1,
                          "current_base_units_total": 10,
                          "product_id": "prod-vial",
                          "brand_name": "Ceftron 1g",
                          "dosage_form": "Injection",
                          "normalized_dosage_form": "INJECTION",
                          "primary_base_unit": "VIAL",
                          "available_base_units": 40,
                          "conversion_source": "PRODUCT_DEFAULT",
                          "non_divisible_sealed_only": false,
                          "blocked_units": ["VIAL"],
                          "packaging_options": [
                            {
                              "packaging_unit": "VIAL",
                              "label_en": "Vial",
                              "label_bn": "ভায়াল",
                              "conversion_factor": 1,
                              "unit_price_bdt": "180.00",
                              "conversion_source": "PRODUCT_DEFAULT",
                              "container_policy_status": "PHARMACIST_APPROVAL_REQUIRED",
                              "is_allowed": false,
                              "requires_pharmacist_approval": true,
                              "block_error_code": "ERR_PHARMACIST_SPLIT_APPROVAL_REQUIRED",
                              "block_reason_en": "Requires Pharmacist approval to break vial box.",
                              "block_reason_bn": "ভায়াল বক্স ভাঙতে ফার্মাসিস্টের অনুমোদন প্রয়োজন।"
                            },
                            {
                              "packaging_unit": "BOX",
                              "label_en": "Box (10u)",
                              "label_bn": "বক্স (১০টি)",
                              "conversion_factor": 10,
                              "unit_price_bdt": "1750.00",
                              "conversion_source": "PRODUCT_DEFAULT",
                              "container_policy_status": "SEALED_INTACT_ALLOWED",
                              "is_allowed": true,
                              "requires_pharmacist_approval": false,
                              "block_error_code": "",
                              "block_reason_en": "",
                              "block_reason_bn": ""
                            }
                          ]
                        }
                        """);
            }
            if (idx == 1) {
                assertEquals("POST", m);
                assertTrue(path.contains("/api/v2/pos/sales/sale-u1/select-unit/"));
                assertTrue(body.contains("CONVERT_PRESERVE_BASE"));
                return new ApiResponseResult(200, """
                        {
                          "unit_audit": {"unit_audit_id": "cusa-1", "to_unit": "STRIP"},
                          "sale": {
                            "sale_id": "sale-u1",
                            "branch_id": "br-a",
                            "order_number": "POS-U1",
                            "lifecycle_state": "DRAFT",
                            "subtotal_bdt": "38.00",
                            "tax_amount_bdt": "0.00",
                            "grand_total_bdt": "38.00",
                            "version": 3,
                            "lines": [
                              {
                                "line_id": "line-1",
                                "line_number": 1,
                                "product_id": "prod-napa",
                                "product_brand_name": "Napa Extend",
                                "packaging_unit": "STRIP",
                                "quantity_units": 2,
                                "conversion_factor": 10,
                                "base_units_total": 20,
                                "dosage_form_snapshot": "Tablet",
                                "container_policy_applied": "PHARMACIST_OVERRIDE_APPROVED",
                                "unit_conversion_source": "PACKAGING_HIERARCHY",
                                "container_override_approved_by_email": "pharm@pharmacy.bd",
                                "container_override_reason": "Emergency dose",
                                "unit_price_bdt": "19.00",
                                "line_subtotal_bdt": "38.00",
                                "line_total_bdt": "38.00"
                              }
                            ]
                          }
                        }
                        """);
            }
            if (idx == 2) {
                return new ApiResponseResult(403, """
                        {
                          "error_code": "ERR_PHARMACIST_SPLIT_APPROVAL_REQUIRED",
                          "message_en": "Pharmacist split approval required."
                        }
                        """);
            }
            return new ApiResponseResult(400, """
                    {
                      "error_code": "ERR_NON_DIVISIBLE_PACK",
                      "message_en": "Sealed container cannot be split.",
                      "message_bn": "সিলড কনটেইনার ভাঙা নিষিদ্ধ।"
                    }
                    """);
        });

        ClientOutcome<UnitOptionsResponseDto> opts = client.fetchUnitOptions(
                "sale-u1", "line-1", "br-a", "prod-vial", null, "tok-cashier"
        );
        assertEquals(UiState.READY, opts.uiState);
        assertNotNull(opts.data);
        assertEquals(2, opts.data.packagingOptions.size());
        PackagingOptionDto vialOpt = opts.data.packagingOptions.get(0);
        assertFalse(vialOpt.isAllowed);
        assertTrue(vialOpt.requiresPharmacistApproval);
        assertEquals("ERR_PHARMACIST_SPLIT_APPROVAL_REQUIRED", vialOpt.blockErrorCode);

        ClientOutcome<PosSaleOrderDto> selected = client.selectCartLineUnit(
                "sale-u1", "line-1", "STRIP", null, 10, "CONVERT_PRESERVE_BASE", 2, "Emergency dose", "tok-pharm"
        );
        assertEquals(UiState.READY, selected.uiState);
        PosSaleLineDto updLine = selected.data.lines.get(0);
        assertEquals("STRIP", updLine.packagingUnit);
        assertEquals(20, updLine.baseUnitsTotal);
        assertEquals("PHARMACIST_OVERRIDE_APPROVED", updLine.containerPolicyApplied);
        assertEquals("pharm@pharmacy.bd", updLine.containerOverrideApprovedByEmail);

        ClientOutcome<PosSaleOrderDto> denied = client.selectCartLineUnit(
                "sale-u1", "line-1", "VIAL", 2, 1, "KEEP_QUANTITY", 3, "", "tok-cashier"
        );
        assertEquals(UiState.PERMISSION_DENIED, denied.uiState);
        assertEquals("ERR_PHARMACIST_SPLIT_APPROVAL_REQUIRED", denied.errorCode);

        ClientOutcome<PosSaleOrderDto> blocked = client.selectCartLineUnit(
                "sale-u1", "line-1", "TABLET", 1, 1, "KEEP_QUANTITY", 3, "", "tok-pharm"
        );
        assertEquals(UiState.ERROR, blocked.uiState);
        assertEquals("ERR_NON_DIVISIBLE_PACK", blocked.errorCode);
        assertTrue(blocked.messageBn.contains("সিলড"));
    }

    @Test
    @DisplayName("2. PosController unit selector combo, base-unit pill, container block & CONVERT_PRESERVE_BASE workflow")
    void testPosControllerUnitSelectorAndContainerBlocking() throws Exception {
        URL fxmlUrl = getClass().getResource("/com/pharmacy/pos/views/main.fxml");
        assertNotNull(fxmlUrl);
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();
        assertNotNull(root);

        PosController controller = loader.getController();
        controller.setAsyncNetworkCalls(false);
        controller.setSessionDetails(
                new TenantSession("ten-a", "br-a", "", "cashier-01", "CASHIER", true),
                "Cashier",
                "Day",
                "1000.00"
        );

        FastSearchCandidateDto napa = FastSearchCandidateDto.fromSnapshotItemMap(Map.ofEntries(
                Map.entry("read_model_id", "snap-napa"),
                Map.entry("product_id", "prod-napa"),
                Map.entry("sku_code", "NAPA-665"),
                Map.entry("brand_name", "Napa Extend"),
                Map.entry("brand_name_bn", "নাপা এক্সটেন্ড"),
                Map.entry("generic_name", "Paracetamol"),
                Map.entry("strength", "665mg"),
                Map.entry("dosage_form", "Tablet"),
                Map.entry("barcode_tokens", "8941100"),
                Map.entry("unit_price_bdt", "2.00"),
                Map.entry("strip_conversion_factor", 10),
                Map.entry("box_conversion_factor", 100),
                Map.entry("sellable_base_units", 25),
                Map.entry("available_base_units", 25),
                Map.entry("non_divisible_sealed_only", false)
        ));

        FastSearchCandidateDto sealedInsulin = FastSearchCandidateDto.fromSnapshotItemMap(Map.ofEntries(
                Map.entry("read_model_id", "snap-ins"),
                Map.entry("product_id", "prod-ins"),
                Map.entry("sku_code", "INS-100"),
                Map.entry("brand_name", "Lantus Box"),
                Map.entry("dosage_form", "Injection"),
                Map.entry("barcode_tokens", "8949900"),
                Map.entry("unit_price_bdt", "900.00"),
                Map.entry("strip_conversion_factor", 5),
                Map.entry("box_conversion_factor", 5),
                Map.entry("sellable_base_units", 10),
                Map.entry("available_base_units", 10),
                Map.entry("non_divisible_sealed_only", true)
        ));

        PosFastSearchCartClient offlineClient = new PosFastSearchCartClient("http://localhost:8000", (m, p, b, t) -> {
            throw new RuntimeException("Offline test");
        });
        offlineClient.seedOfflineSnapshot("br-a", List.of(napa, sealedInsulin));
        controller.setSearchCartClient(offlineClient);

        TextField input = getField(controller, "barcodeInputField");
        ComboBox<PackagingOptionDto> combo = getField(controller, "unitSelectorCombo");
        Label basePill = getField(controller, "selectedLineBaseUnitsLabel");
        Label totalLbl = getField(controller, "grandTotalLabel");

        // Scan 1 TABLET Napa -> increment to 2 TABLET -> switch via combo/controller to STRIP (20u <= 25u)
        input.setText("8941100");
        controller.handleScanSubmit();
        controller.handleIncrementSelectedLine();
        assertEquals(2, controller.getCartItems().get(0).getQuantity());
        assertEquals(3, combo.getItems().size());
        assertEquals("Base: 2 TABLET x 1 = 2u", basePill.getText());

        PackagingOptionDto stripOpt = combo.getItems().stream()
                .filter(o -> "STRIP".equals(o.packagingUnit)).findFirst().orElseThrow();
        controller.applyUnitSelectionToSelectedLine(0, stripOpt, "KEEP_QUANTITY", "");
        assertEquals("STRIP", controller.getCartItems().get(0).getPackagingUnit());
        assertEquals("Base: 2 STRIP x 10 = 20u", basePill.getText());
        assertEquals("BDT 40.00", totalLbl.getText());

        // Switching 2 STRIP (20u) -> 20 TABLET via CONVERT_PRESERVE_BASE converts cleanly back to 20 x TABLET!
        PackagingOptionDto tabOpt = combo.getItems().stream()
                .filter(o -> "TABLET".equals(o.packagingUnit)).findFirst().orElseThrow();
        controller.applyUnitSelectionToSelectedLine(0, tabOpt, "CONVERT_PRESERVE_BASE", "");
        assertEquals("TABLET", controller.getCartItems().get(0).getPackagingUnit());
        assertEquals(20, controller.getCartItems().get(0).getQuantity());
        assertEquals("Base: 20 TABLET x 1 = 20u", basePill.getText());

        // Switching to BOX (100u > 25u available) is blocked with ERROR
        PackagingOptionDto boxOpt = combo.getItems().stream()
                .filter(o -> "BOX".equals(o.packagingUnit)).findFirst().orElseThrow();
        controller.applyUnitSelectionToSelectedLine(0, boxOpt, "KEEP_QUANTITY", "");
        assertEquals(UiState.ERROR, controller.getLastUiState());
        assertEquals("TABLET", controller.getCartItems().get(0).getPackagingUnit());

        // Add sealed insulin pack -> TABLET option must be marked blocked (isAllowed=false) & refused on selection
        input.setText("8949900");
        controller.handleScanSubmit();
        assertEquals(2, controller.getCartItems().size());
        PackagingOptionDto sealedTab = combo.getItems().stream()
                .filter(o -> "TABLET".equals(o.packagingUnit)).findFirst().orElseThrow();
        assertFalse(sealedTab.isAllowed);
        assertEquals("ERR_NON_DIVISIBLE_PACK", sealedTab.blockErrorCode);
        controller.applyUnitSelectionToSelectedLine(1, sealedTab, "KEEP_QUANTITY", "");
        assertEquals(UiState.ERROR, controller.getLastUiState());
        assertNotEquals("TABLET", controller.getCartItems().get(1).getPackagingUnit());

        // EN -> BN toggle translates base pill cleanly
        controller.handleToggleLanguage();
        assertTrue(basePill.getText().startsWith("বেস:"));
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(PosController target, String name) throws Exception {
        Field f = PosController.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }
}

