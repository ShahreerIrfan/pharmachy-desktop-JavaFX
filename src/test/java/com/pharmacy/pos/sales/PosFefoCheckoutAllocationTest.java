package com.pharmacy.pos.sales;

import com.pharmacy.pos.controllers.PosController;
import com.pharmacy.pos.models.CartItem;
import com.pharmacy.pos.models.TenantSession;
import com.pharmacy.pos.sales.PosFastSearchCartClient.FastSearchCandidateDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.ApiResponseResult;
import com.pharmacy.pos.sales.PosSaleDomainClient.ClientOutcome;
import com.pharmacy.pos.sales.PosSaleDomainClient.FefoAllocationPlanDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosSaleOrderDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.UiState;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P228 FEFO & Atomic Lot Allocation at POS Checkout Desktop Tests.
 * Architecture Decision: ADR-0228 (P228).
 */
public class PosFefoCheckoutAllocationTest {

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
    @DisplayName("1. Client parses FefoAllocationPlanDto, rejects invalid overrides & unsafe batch errors, and posts override finalize")
    void testFefoClientDtosOverrideValidationAndErrorMappings() {
        AtomicReference<String> lastOverrideBody = new AtomicReference<>("");
        AtomicBoolean sawFinalizeWithOverride = new AtomicBoolean(false);

        PosSaleDomainClient client = new PosSaleDomainClient("http://localhost:8000", (method, path, body, token) -> {
            if ("GET".equals(method) && path.equals("/api/v2/pos/sales/sale-fefo-1/fefo-plan/")) {
                boolean isManager = token != null && token.contains("mgr");
                String estCost = isManager ? "\"28.0000\"" : "null";
                String unitCost = isManager ? "\"1.5000\"" : "null";
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-fefo-1",
                          "order_number": "POS-FEFO-101",
                          "fefo_allocation_status": "PENDING",
                          "fefo_ready": true,
                          "has_expired_block": true,
                          "has_recalled_block": false,
                          "has_quarantined_block": true,
                          "total_requested_base_units": 15,
                          "total_planned_base_units": 15,
                          "total_estimated_cost_bdt": %s,
                          "version": 2,
                          "lines": [
                            {
                              "line_id": "ln-1",
                              "line_number": 1,
                              "product_id": "med-napa",
                              "brand_name": "Napa Extra 500",
                              "packaging_unit": "TABLET",
                              "requested_base_units": 15,
                              "planned_base_units": 15,
                              "short_base_units": 0,
                              "is_fulfilled": true,
                              "allocation_mode": "FEFO_AUTO",
                              "planned_lots": [
                                {
                                  "fefo_sequence": 1,
                                  "batch_id": "bat-1",
                                  "batch_number": "LOT-A-EARLY",
                                  "expiry_date": "2026-12-10",
                                  "cost_lot_id": "lot-1a",
                                  "allocated_base_units": 7,
                                  "allocation_mode": "FEFO_AUTO",
                                  "unit_cost_bdt": %s
                                },
                                {
                                  "fefo_sequence": 2,
                                  "batch_id": "bat-1",
                                  "batch_number": "LOT-A-EARLY",
                                  "expiry_date": "2026-12-10",
                                  "cost_lot_id": "lot-1b",
                                  "allocated_base_units": 5,
                                  "allocation_mode": "FEFO_AUTO"
                                },
                                {
                                  "fefo_sequence": 3,
                                  "batch_id": "bat-2",
                                  "batch_number": "LOT-B-SECOND",
                                  "expiry_date": "2027-02-10",
                                  "cost_lot_id": "lot-2",
                                  "allocated_base_units": 3,
                                  "allocation_mode": "FEFO_AUTO"
                                }
                              ],
                              "blocked_lots": [
                                {
                                  "batch_id": "bat-q",
                                  "batch_number": "LOT-QUAR",
                                  "expiry_date": "2026-11-25",
                                  "block_category": "QUARANTINED",
                                  "quantity_base_units": 50,
                                  "reason": "Quarantined pending QA check."
                                }
                              ]
                            }
                          ]
                        }
                        """.formatted(estCost, unitCost));
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/sale-fefo-1/allocate/")) {
                lastOverrideBody.set(body != null ? body : "");
                if (token != null && token.contains("cashier") && body != null && body.contains("batch_overrides")) {
                    return new ApiResponseResult(403, """
                            {
                              "error_code": "ERR_FEFO_OVERRIDE_REQUIRES_APPROVAL",
                              "detail": "Manual FEFO batch override requires Staff Pharmacist or Branch Manager approval."
                            }
                            """);
                }
                if (body != null && body.contains("bat-expired")) {
                    return new ApiResponseResult(422, """
                            {
                              "error_code": "ERR_BATCH_EXPIRED",
                              "detail": "Batch 'LOT-EXP' expired on 2026-11-08 and cannot be dispensed."
                            }
                            """);
                }
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-fefo-1",
                          "order_number": "POS-FEFO-101",
                          "lifecycle_state": "CONFIRMED",
                          "fefo_allocation_status": "RESERVED",
                          "version": 3,
                          "allocations": [
                            {
                              "allocation_id": "alloc-1",
                              "sale_line_id": "ln-1",
                              "batch_id": "bat-late",
                              "batch_number": "LOT-B-LATE",
                              "fefo_sequence": 1,
                              "allocation_mode": "MANUAL_OVERRIDE",
                              "override_reason": "Ward requested long expiry",
                              "allocated_base_units": 15,
                              "status": "RESERVED"
                            }
                          ]
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/sale-fefo-1/finalize/")) {
                if (body != null && body.contains("bat-late") && body.contains("Ward requested long expiry")) {
                    sawFinalizeWithOverride.set(true);
                }
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-fefo-1",
                          "order_number": "POS-FEFO-101",
                          "lifecycle_state": "FINALIZED",
                          "fefo_allocation_status": "COMMITTED",
                          "version": 4,
                          "allocations": [
                            {
                              "allocation_id": "alloc-1",
                              "sale_line_id": "ln-1",
                              "batch_id": "bat-late",
                              "batch_number": "LOT-B-LATE",
                              "fefo_sequence": 1,
                              "allocation_mode": "MANUAL_OVERRIDE",
                              "allocated_base_units": 15,
                              "status": "COMMITTED"
                            }
                          ]
                        }
                        """);
            }
            return new ApiResponseResult(404, "{}");
        });

        // 1) Cashier cost masked vs Manager cost visible
        ClientOutcome<FefoAllocationPlanDto> cashPlan = client.getFefoPlan(
                "tok-cashier", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "sale-fefo-1", false, false
        );
        assertEquals(UiState.READY, cashPlan.uiState);
        assertNull(cashPlan.data.totalEstimatedCostBdt);
        assertEquals(3, cashPlan.data.lines.get(0).plannedLots.size());
        assertEquals(1, cashPlan.data.lines.get(0).blockedLots.size());
        assertTrue(cashPlan.data.hasQuarantinedBlock);

        ClientOutcome<FefoAllocationPlanDto> mgrPlan = client.getFefoPlan(
                "tok-mgr", "BRANCH_MANAGER", "ten-a", "br-a", "ten-a", "br-a", "sale-fefo-1", false, false
        );
        assertEquals(UiState.READY, mgrPlan.uiState);
        assertEquals(new BigDecimal("28.0000"), mgrPlan.data.totalEstimatedCostBdt);

        // 2) Short reason client validation & Cashier 403 override denied
        ClientOutcome<PosSaleOrderDto> shortReason = client.allocateStockFefo(
                "tok-mgr", "BRANCH_MANAGER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-fefo-1", Map.of("ln-1", "bat-late"), "x", 2, false, false
        );
        assertEquals(UiState.ERROR, shortReason.uiState);
        assertEquals("ERR_FEFO_OVERRIDE_REASON_REQUIRED", shortReason.errorCode);

        ClientOutcome<PosSaleOrderDto> cashOverride = client.allocateStockFefo(
                "tok-cashier", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-fefo-1", Map.of("ln-1", "bat-late"), "Customer wanted newer batch", 2, false, false
        );
        assertEquals(UiState.PERMISSION_DENIED, cashOverride.uiState);
        assertEquals("ERR_FEFO_OVERRIDE_REQUIRES_APPROVAL", cashOverride.errorCode);

        // 3) Expired batch 422 propagated & Manager override + finalize succeed cleanly
        ClientOutcome<PosSaleOrderDto> expErr = client.allocateStockFefo(
                "tok-mgr", "BRANCH_MANAGER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-fefo-1", Map.of("ln-1", "bat-expired"), "Trying expired batch", 2, false, false
        );
        assertEquals(UiState.ERROR, expErr.uiState);

        ClientOutcome<PosSaleOrderDto> mgrReserve = client.allocateStockFefo(
                "tok-mgr", "BRANCH_MANAGER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-fefo-1", Map.of("ln-1", "bat-late"), "Ward requested long expiry", 2, false, false
        );
        assertEquals(UiState.READY, mgrReserve.uiState);
        assertEquals("RESERVED", mgrReserve.data.fefoAllocationStatus);
        assertEquals("MANUAL_OVERRIDE", mgrReserve.data.allocations.get(0).allocationMode);

        ClientOutcome<PosSaleOrderDto> fin = client.finalizeSale(
                "tok-mgr", "BRANCH_MANAGER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-fefo-1", null, "", false, Map.of("ln-1", "bat-late"), "Ward requested long expiry", false, false
        );
        assertEquals(UiState.READY, fin.uiState);
        assertEquals("COMMITTED", fin.data.fefoAllocationStatus);
        assertTrue(sawFinalizeWithOverride.get());
    }

    @Test
    @DisplayName("2. PosController [F7] FEFO drawer, offline expired/quarantined safety blocks, override RBAC, checkout & EN/BN")
    void testPosControllerFefoDrawerOfflineBlocksOverrideAndCheckout() throws Exception {
        URL fxmlUrl = getClass().getResource("/com/pharmacy/pos/views/main.fxml");
        assertNotNull(fxmlUrl);
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();
        assertNotNull(root);

        PosController controller = loader.getController();
        controller.setAsyncNetworkCalls(false);
        controller.setSessionDetails(
                new TenantSession("ten-a", "br-a", "tok-cashier", "cashier-01", "CASHIER", true),
                "Cashier Tariq", "Day Shift", "500.00"
        );

        AtomicBoolean reservedOnServer = new AtomicBoolean(false);
        AtomicBoolean finalizedOnServer = new AtomicBoolean(false);
        AtomicBoolean sawOverrideInFinalize = new AtomicBoolean(false);

        PosFastSearchCartClient searchStub = new PosFastSearchCartClient("http://localhost:8000", (m, p, b, t) -> {
            if (p.contains("/quick-cart/")) {
                reservedOnServer.set(false);
                return new ApiResponseResult(201, """
                        {
                          "created": true,
                          "sale": {
                            "sale_id": "sale-ui-fefo-1",
                            "tenant_id": "ten-a",
                            "branch_id": "br-a",
                            "order_number": "POS-UI-FEFO-1",
                            "lifecycle_state": "DRAFT",
                            "requires_rx_verification": false,
                            "rx_gate_status": "NOT_REQUIRED",
                            "fefo_allocation_status": "PENDING",
                            "version": 1,
                            "lines": []
                          }
                        }
                        """);
            }
            if (p.contains("/scan-add/")) {
                return new ApiResponseResult(200, """
                        {
                          "sale": {
                            "sale_id": "sale-ui-fefo-1",
                            "tenant_id": "ten-a",
                            "branch_id": "br-a",
                            "order_number": "POS-UI-FEFO-1",
                            "lifecycle_state": "DRAFT",
                            "requires_rx_verification": false,
                            "rx_gate_status": "NOT_REQUIRED",
                            "fefo_allocation_status": "PENDING",
                            "subtotal_bdt": "12.00",
                            "grand_total_bdt": "12.00",
                            "version": 2,
                            "lines": [
                              {
                                "line_id": "ln-otc-1",
                                "line_number": 1,
                                "product_id": "prod-napa",
                                "product_brand_name": "Napa 500",
                                "product_generic_name": "Paracetamol",
                                "packaging_unit": "STRIP",
                                "quantity_units": 1,
                                "conversion_factor": 10,
                                "base_units_total": 10,
                                "unit_price_bdt": "12.00",
                                "line_subtotal_bdt": "12.00",
                                "line_total_bdt": "12.00",
                                "regulatory_classification": "OTC",
                                "requires_rx_verification": false
                              }
                            ]
                          }
                        }
                        """);
            }
            return new ApiResponseResult(404, "{}");
        });
        controller.setSearchCartClient(searchStub);

        PosSaleDomainClient domainStub = new PosSaleDomainClient("http://localhost:8000", (m, p, b, t) -> {
            if ("GET".equals(m) && p.equals("/api/v2/pos/sales/sale-ui-fefo-1/fefo-plan/")) {
                String statusStr = reservedOnServer.get() ? "RESERVED" : "PENDING";
                String batchNum = reservedOnServer.get() ? "LOT-B-NEWER" : "LOT-A-EARLIER";
                String batchId = reservedOnServer.get() ? "bat-newer" : "bat-earlier";
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-ui-fefo-1",
                          "order_number": "POS-UI-FEFO-1",
                          "fefo_allocation_status": "%s",
                          "fefo_ready": true,
                          "total_requested_base_units": 10,
                          "total_planned_base_units": 10,
                          "version": 2,
                          "lines": [
                            {
                              "line_id": "ln-otc-1",
                              "line_number": 1,
                              "product_id": "prod-napa",
                              "brand_name": "Napa 500",
                              "requested_base_units": 10,
                              "planned_base_units": 10,
                              "is_fulfilled": true,
                              "planned_lots": [
                                {
                                  "fefo_sequence": 1,
                                  "batch_id": "%s",
                                  "batch_number": "%s",
                                  "expiry_date": "2027-01-15",
                                  "allocated_base_units": 10,
                                  "allocation_mode": "FEFO_AUTO"
                                }
                              ],
                              "blocked_lots": [
                                {
                                  "batch_id": "bat-q1",
                                  "batch_number": "LOT-Q-HOLD",
                                  "block_category": "QUARANTINED",
                                  "quantity_base_units": 20,
                                  "reason": "Quarantined batch excluded"
                                }
                              ]
                            }
                          ]
                        }
                        """.formatted(statusStr, batchId, batchNum));
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/sales/sale-ui-fefo-1/allocate/")) {
                if (t != null && t.contains("cashier") && b != null && b.contains("bat-newer")) {
                    return new ApiResponseResult(403, """
                            {
                              "error_code": "ERR_FEFO_OVERRIDE_REQUIRES_APPROVAL",
                              "detail": "Manual FEFO batch override requires Staff Pharmacist or Branch Manager approval."
                            }
                            """);
                }
                reservedOnServer.set(true);
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-ui-fefo-1",
                          "order_number": "POS-UI-FEFO-1",
                          "lifecycle_state": "CONFIRMED",
                          "fefo_allocation_status": "RESERVED",
                          "subtotal_bdt": "12.00",
                          "grand_total_bdt": "12.00",
                          "version": 3,
                          "lines": [
                            {
                              "line_id": "ln-otc-1",
                              "line_number": 1,
                              "product_id": "prod-napa",
                              "product_brand_name": "Napa 500",
                              "packaging_unit": "STRIP",
                              "quantity_units": 1,
                              "conversion_factor": 10,
                              "base_units_total": 10,
                              "unit_price_bdt": "12.00",
                              "line_subtotal_bdt": "12.00",
                              "line_total_bdt": "12.00"
                            }
                          ],
                          "allocations": [
                            {
                              "allocation_id": "al-1",
                              "sale_line_id": "ln-otc-1",
                              "batch_id": "bat-newer",
                              "batch_number": "LOT-B-NEWER",
                              "allocation_mode": "MANUAL_OVERRIDE",
                              "allocated_base_units": 10,
                              "status": "RESERVED"
                            }
                          ]
                        }
                        """);
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/sales/sale-ui-fefo-1/record-payment/")) {
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-ui-fefo-1",
                          "order_number": "POS-UI-FEFO-1",
                          "lifecycle_state": "CONFIRMED",
                          "version": 3
                        }
                        """);
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/sales/sale-ui-fefo-1/finalize/")) {
                finalizedOnServer.set(true);
                if (b != null && b.contains("bat-newer")) {
                    sawOverrideInFinalize.set(true);
                }
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-ui-fefo-1",
                          "order_number": "POS-UI-FEFO-1",
                          "lifecycle_state": "FINALIZED",
                          "fefo_allocation_status": "COMMITTED",
                          "version": 4,
                          "allocations": [
                            {
                              "allocation_id": "al-1",
                              "sale_line_id": "ln-otc-1",
                              "batch_id": "bat-newer",
                              "batch_number": "LOT-B-NEWER",
                              "allocated_base_units": 10,
                              "status": "COMMITTED"
                            }
                          ]
                        }
                        """);
            }
            return new ApiResponseResult(404, "{}");
        });
        controller.setSaleDomainClient(domainStub);

        Button btnFefoLots = getField(controller, "btnFefoLots");
        VBox fefoPlanDrawer = getField(controller, "fefoPlanDrawer");
        Label fefoPlanStatusBadge = getField(controller, "fefoPlanStatusBadge");
        Label fefoPlanSummaryLabel = getField(controller, "fefoPlanSummaryLabel");
        TextField overrideBatchField = getField(controller, "fefoOverrideBatchField");
        TextField overrideReasonField = getField(controller, "fefoOverrideReasonField");

        // 1) Offline stock safety gate: expired cached batch blocks checkout cleanly!
        controller.handleToggleOffline();
        FastSearchCandidateDto safeOtc = FastSearchCandidateDto.fromSnapshotItemMap(Map.ofEntries(
                Map.entry("product_id", "prod-napa"),
                Map.entry("sku_code", "NAPA-500"),
                Map.entry("brand_name", "Napa 500"),
                Map.entry("generic_name", "Paracetamol"),
                Map.entry("barcode_tokens", "8940001"),
                Map.entry("unit_price_bdt", "1.20"),
                Map.entry("strip_conversion_factor", 10),
                Map.entry("sellable_base_units", 50),
                Map.entry("available_base_units", 50),
                Map.entry("requires_rx_verification", false),
                Map.entry("regulatory_classification", "OTC")
        ));
        controller.handleAddCandidate(safeOtc);
        // Simulate cached expiry transition before checkout while offline
        safeOtc.availableBaseUnits = 0;
        safeOtc.expiredBaseUnits = 50;
        controller.updateTotals();
        controller.handleCheckout();
        assertEquals(UiState.ERROR, controller.getLastUiState());
        assertEquals(0, controller.getSyncEngine().getPendingCount(), "Expired batch must NOT enqueue offline!");
        assertEquals(1, controller.getCartItems().size(), "Blocked offline cart must remain open!");
        assertTrue(fefoPlanDrawer.isVisible());
        assertEquals("BLOCKED", fefoPlanStatusBadge.getText());

        // Restore safe offline OTC stock -> offline provisional FEFO queues cleanly!
        safeOtc.expiredBaseUnits = 0;
        safeOtc.availableBaseUnits = 50;
        controller.updateTotals();
        assertEquals("OFFLINE_PROVISIONAL_FEFO", fefoPlanStatusBadge.getText());
        controller.handleCheckout();
        assertEquals(UiState.OFFLINE, controller.getLastUiState());
        assertEquals(1, controller.getSyncEngine().getPendingCount());
        assertTrue(controller.getCartItems().isEmpty());

        // 2) Online [F7] FEFO drawer + Cashier override 403 -> Manager override + finalize!
        controller.handleToggleOffline(); // back online
        controller.handleAddCandidate(safeOtc);
        assertEquals(1, controller.getCartItems().size());

        controller.handleGlobalKeyShortcuts(new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", KeyCode.F7, false, false, false, false
        ));
        assertTrue(fefoPlanDrawer.isVisible());
        assertTrue(fefoPlanSummaryLabel.getText().contains("LOT-A-EARLIER"));
        assertTrue(fefoPlanSummaryLabel.getText().contains("QUARANTINED"));

        overrideBatchField.setText("bat-newer");
        overrideReasonField.setText("Ward requested newer expiry");
        controller.handleReserveFefoLots();
        assertEquals(UiState.PERMISSION_DENIED, controller.getLastUiState(), "Cashier override must return 403!");

        controller.setSessionDetails(
                new TenantSession("ten-a", "br-a", "tok-mgr", "mgr-01", "BRANCH_MANAGER", true),
                "Manager Karim", "Day Shift", "500.00"
        );
        controller.handleReserveFefoLots();
        assertEquals(UiState.READY, controller.getLastUiState());
        assertEquals("RESERVED", fefoPlanStatusBadge.getText());
        assertTrue(fefoPlanSummaryLabel.getText().contains("LOT-B-NEWER"));

        controller.handleCheckout();
        assertEquals(UiState.READY, controller.getLastUiState());
        assertTrue(finalizedOnServer.get());
        assertTrue(sawOverrideInFinalize.get());
        assertTrue(controller.getCartItems().isEmpty());

        // 3) EN/BN language toggle with zero emojis
        controller.handleToggleLanguage();
        assertTrue(btnFefoLots.getText().contains("FEFO"));
        assertEquals("FEFO লট রিজার্ভ করুন", ((Button) getField(controller, "btnReserveFefoLots")).getText());
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(PosController target, String name) throws Exception {
        Field f = PosController.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }
}

