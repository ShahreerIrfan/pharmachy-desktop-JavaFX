package com.pharmacy.pos.sales;

import com.pharmacy.pos.controllers.PosController;
import com.pharmacy.pos.models.CartItem;
import com.pharmacy.pos.models.TenantSession;
import com.pharmacy.pos.sales.PosFastSearchCartClient.FastSearchCandidateDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.ApiResponseResult;
import com.pharmacy.pos.sales.PosSaleDomainClient.ClientOutcome;
import com.pharmacy.pos.sales.PosSaleDomainClient.ExpireStaleResultDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.HeldCartListResultDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.LifecycleState;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosSaleOrderDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.ResumeCartResultDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.UiState;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URL;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P225 Held & Resumed Carts Desktop Tests.
 * Architecture Decision: ADR-0225 (P225).
 */
public class PosHeldResumedCartTest {

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
    @DisplayName("1. Client parses held list, stale prompt confirmation, expiry sweep, cancel hold & RBAC/scope guards")
    void testClientHoldListResumeExpireCancelAndGuards() {
        AtomicBoolean staleAcked = new AtomicBoolean(false);
        PosSaleDomainClient client = new PosSaleDomainClient("http://localhost:8000", (method, path, body, token) -> {
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/sale-h1/hold/")) {
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-h1",
                          "tenant_id": "ten-a",
                          "branch_id": "br-a",
                          "order_number": "POS-H1",
                          "lifecycle_state": "HELD",
                          "hold_reference": "ATM-WAIT",
                          "hold_note": "Patient withdrawing cash",
                          "hold_stock_reserved": true,
                          "hold_expires_at": "2026-11-09T12:00:00Z",
                          "hold_reservation_expires_at": "2026-11-09T11:45:00Z",
                          "hold_snapshot_grand_total_bdt": "150.00",
                          "grand_total_bdt": "150.00",
                          "version": 2,
                          "lines": [
                            {
                              "line_id": "line-1",
                              "product_id": "prod-monas",
                              "product_brand_name": "Monas 10mg",
                              "packaging_unit": "STRIP",
                              "quantity_units": 1,
                              "conversion_factor": 10,
                              "unit_price_bdt": "150.00",
                              "line_subtotal_bdt": "150.00",
                              "line_total_bdt": "150.00",
                              "unit_cost_bdt": null
                            }
                          ],
                          "staleness": {
                            "is_stale": false,
                            "held_grand_total_bdt": "150.00",
                            "live_grand_total_bdt": "150.00"
                          }
                        }
                        """);
            }
            if ("GET".equals(method) && path.startsWith("/api/v2/pos/sales/held/")) {
                return new ApiResponseResult(200, """
                        {
                          "count": 1,
                          "stale_count": 1,
                          "expired_count": 1,
                          "results": [
                            {
                              "sale_id": "sale-h1",
                              "order_number": "POS-H1",
                              "branch_id": "br-a",
                              "branch_name": "Dhanmondi Branch",
                              "lifecycle_state": "HELD",
                              "hold_reference": "ATM-WAIT",
                              "hold_stock_reserved": false,
                              "line_count": 1,
                              "held_grand_total_bdt": "150.00",
                              "live_grand_total_bdt": "180.00",
                              "version": 3,
                              "staleness": {
                                "is_stale": true,
                                "is_reservation_expired": true,
                                "has_price_change": true,
                                "stale_reasons": ["RESERVATION_EXPIRED", "PRICE_CHANGED"],
                                "held_grand_total_bdt": "150.00",
                                "live_grand_total_bdt": "180.00",
                                "price_delta_bdt": "30.00",
                                "user_prompt_en": "Stock reservation timed out and price increased.",
                                "user_prompt_bn": "স্টক রিজার্ভেশনের মেয়াদ শেষ এবং মূল্য পরিবর্তিত হয়েছে।",
                                "price_drift_items": [
                                  {
                                    "line_id": "line-1",
                                    "product_id": "prod-monas",
                                    "product_brand_name": "Monas 10mg",
                                    "packaging_unit": "STRIP",
                                    "held_unit_price_bdt": "150.00",
                                    "live_unit_price_bdt": "180.00"
                                  }
                                ],
                                "shortfall_items": [
                                  {
                                    "line_id": "line-1",
                                    "product_id": "prod-monas",
                                    "product_brand_name": "Monas 10mg",
                                    "required_base_units": 10,
                                    "sellable_available_base_units": 6,
                                    "shortfall_base_units": 4,
                                    "unsafe_batch_detected": false
                                  }
                                ]
                              }
                            }
                          ]
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/sale-h1/resume/")) {
                if (body.contains("\"acknowledge_stale\":false")) {
                    return new ApiResponseResult(400, """
                            {
                              "error_code": "ERR_HELD_CART_REQUIRES_CONFIRMATION",
                              "message_en": "Held cart requires cashier confirmation before resuming.",
                              "message_bn": "হোল্ড কার্ট রিজিউম করার আগে নিশ্চিতকরণ প্রয়োজন।",
                              "details": {
                                "requires_confirmation": true,
                                "is_stale": true,
                                "is_reservation_expired": true,
                                "has_price_change": true,
                                "stale_reasons": ["RESERVATION_EXPIRED", "PRICE_CHANGED"],
                                "held_grand_total_bdt": "150.00",
                                "live_grand_total_bdt": "180.00",
                                "price_delta_bdt": "30.00",
                                "user_prompt_en": "Stock reservation timed out and price increased.",
                                "user_prompt_bn": "স্টক রিজার্ভেশনের মেয়াদ শেষ এবং মূল্য পরিবর্তিত হয়েছে।"
                              }
                            }
                            """);
                }
                staleAcked.set(true);
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-h1",
                          "tenant_id": "ten-a",
                          "branch_id": "br-a",
                          "order_number": "POS-H1",
                          "lifecycle_state": "DRAFT",
                          "subtotal_bdt": "180.00",
                          "grand_total_bdt": "180.00",
                          "is_pricing_confirmed": false,
                          "version": 4,
                          "lines": []
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/expire-stale-holds/")) {
                return new ApiResponseResult(200, """
                        {
                          "expired_count": 1,
                          "released_base_units": 10,
                          "voided_count": 0,
                          "affected_sale_ids": ["sale-h1"],
                          "message_en": "Processed 1 stale held cart; released 10 units.",
                          "message_bn": "১টি মেয়াদোত্তীর্ণ হোল্ড মুক্ত হয়েছে।"
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/sale-h1/cancel-hold/")) {
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-h1",
                          "lifecycle_state": "VOIDED",
                          "version": 5,
                          "lines": []
                        }
                        """);
            }
            return new ApiResponseResult(404, "{}");
        });

        // Guard checks
        ClientOutcome<PosSaleOrderDto> badTtl = client.holdCart(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-h1", "REF", "", 0, false, null, 1, false, false
        );
        assertEquals(UiState.ERROR, badTtl.uiState);
        assertEquals("ERR_INVALID_HOLD_TTL", badTtl.errorCode);

        ClientOutcome<PosSaleOrderDto> clerkDenied = client.holdCart(
                "tok", "INVENTORY_CLERK", "ten-a", "br-a", "ten-a", "br-a",
                "sale-h1", "REF", "", 30, false, null, 1, false, false
        );
        assertEquals(UiState.PERMISSION_DENIED, clerkDenied.uiState);

        ClientOutcome<HeldCartListResultDto> clerkReadDenied = client.listHeldCarts(
                "tok", "INVENTORY_CLERK", "ten-a", "br-a", "ten-a", "br-a",
                true, false, false
        );
        assertEquals(UiState.PERMISSION_DENIED, clerkReadDenied.uiState);

        // Finance Officer IS allowed read-only inspection
        ClientOutcome<HeldCartListResultDto> financeList = client.listHeldCarts(
                "tok", "FINANCE_OFFICER", "ten-a", "br-a", "ten-a", "br-a",
                true, false, false
        );
        assertEquals(UiState.READY, financeList.uiState);
        assertEquals(1, financeList.data.count);
        assertEquals(1, financeList.data.staleCount);
        assertEquals(new BigDecimal("180.00"), financeList.data.results.get(0).liveGrandTotalBdt);
        assertEquals(4, financeList.data.results.get(0).staleness.shortfallItems.get(0).shortfallBaseUnits);

        // Valid hold
        ClientOutcome<PosSaleOrderDto> heldOk = client.holdCart(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-h1", "ATM-WAIT", "cashier wait", 30, true, 15, 1, false, false
        );
        assertEquals(UiState.READY, heldOk.uiState);
        assertEquals(LifecycleState.HELD, heldOk.data.lifecycleState);
        assertTrue(heldOk.data.holdStockReserved);

        // Unacknowledged stale resume prompts cleanly
        ClientOutcome<ResumeCartResultDto> promptRes = client.resumeCart(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-h1", false, true, false, false, 3, "", false, false
        );
        assertEquals(UiState.ERROR, promptRes.uiState);
        assertEquals("ERR_HELD_CART_REQUIRES_CONFIRMATION", promptRes.errorCode);
        assertNotNull(promptRes.data);
        assertTrue(promptRes.data.requiresConfirmation);
        assertTrue(promptRes.data.staleness.hasPriceChange);
        assertEquals(new BigDecimal("30.00"), promptRes.data.staleness.priceDeltaBdt);

        // Acknowledged stale resume completes cleanly
        ClientOutcome<ResumeCartResultDto> ackRes = client.resumeCart(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-h1", true, true, false, false, 3, "", false, false
        );
        assertEquals(UiState.READY, ackRes.uiState);
        assertTrue(staleAcked.get());
        assertEquals(LifecycleState.DRAFT, ackRes.data.sale.lifecycleState);
        assertEquals(new BigDecimal("180.00"), ackRes.data.sale.grandTotalBdt);

        // Expire stale sweep + cancel hold
        ClientOutcome<ExpireStaleResultDto> sweep = client.expireStaleHolds(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                false, "sweep", false, false
        );
        assertEquals(UiState.READY, sweep.uiState);
        assertEquals(10, sweep.data.releasedBaseUnits);

        ClientOutcome<PosSaleOrderDto> cancelled = client.cancelHold(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-h1", "No longer needed", 4, false, false
        );
        assertEquals(UiState.READY, cancelled.uiState);
        assertEquals(LifecycleState.VOIDED, cancelled.data.lifecycleState);
    }

    @Test
    @DisplayName("2. PosController online hold clears counter, [F10] drawer shows stale prompt & acks, offline parks locally")
    void testPosControllerHoldDrawerStalePromptAndOfflineFallback() throws Exception {
        URL fxmlUrl = getClass().getResource("/com/pharmacy/pos/views/main.fxml");
        assertNotNull(fxmlUrl);
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();
        assertNotNull(root);

        PosController controller = loader.getController();
        controller.setAsyncNetworkCalls(false);
        controller.setSessionDetails(
                new TenantSession("ten-a", "br-a", "tok-cashier", "cashier-01", "CASHIER", true),
                "Tanvir", "Day", "500.00"
        );

        AtomicBoolean isHeldOnServer = new AtomicBoolean(false);
        AtomicBoolean isCancelled = new AtomicBoolean(false);

        PosFastSearchCartClient fastSearchStub = new PosFastSearchCartClient("http://localhost:8000", (m, p, b, t) -> {
            if (p.contains("/quick-cart/")) {
                return new ApiResponseResult(201, """
                        {
                          "created": true,
                          "sale": {
                            "sale_id": "sale-live-1",
                            "tenant_id": "ten-a",
                            "branch_id": "br-a",
                            "order_number": "POS-LIVE-1",
                            "lifecycle_state": "DRAFT",
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
                            "sale_id": "sale-live-1",
                            "tenant_id": "ten-a",
                            "branch_id": "br-a",
                            "order_number": "POS-LIVE-1",
                            "lifecycle_state": "DRAFT",
                            "subtotal_bdt": "150.00",
                            "grand_total_bdt": "150.00",
                            "version": 2,
                            "lines": [
                              {
                                "line_id": "ln-1",
                                "line_number": 1,
                                "product_id": "prod-monas",
                                "product_brand_name": "Monas 10mg",
                                "product_generic_name": "Montelukast",
                                "packaging_unit": "STRIP",
                                "quantity_units": 1,
                                "conversion_factor": 10,
                                "base_units_total": 10,
                                "unit_price_bdt": "150.00",
                                "line_subtotal_bdt": "150.00",
                                "line_total_bdt": "150.00"
                              }
                            ]
                          }
                        }
                        """);
            }
            return new ApiResponseResult(404, "{}");
        });
        controller.setSearchCartClient(fastSearchStub);

        PosSaleDomainClient domainStub = new PosSaleDomainClient("http://localhost:8000", (m, p, b, t) -> {
            if ("POST".equals(m) && p.equals("/api/v2/pos/sales/sale-live-1/hold/")) {
                isHeldOnServer.set(true);
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-live-1",
                          "tenant_id": "ten-a",
                          "branch_id": "br-a",
                          "order_number": "POS-LIVE-1",
                          "lifecycle_state": "HELD",
                          "hold_reference": "VIP-WAIT",
                          "hold_stock_reserved": true,
                          "hold_snapshot_grand_total_bdt": "150.00",
                          "grand_total_bdt": "150.00",
                          "version": 3,
                          "lines": [
                            {
                              "line_id": "ln-1",
                              "product_id": "prod-monas",
                              "product_brand_name": "Monas 10mg",
                              "packaging_unit": "STRIP",
                              "quantity_units": 1,
                              "conversion_factor": 10,
                              "unit_price_bdt": "150.00",
                              "line_subtotal_bdt": "150.00",
                              "line_total_bdt": "150.00"
                            }
                          ]
                        }
                        """);
            }
            if ("GET".equals(m) && p.startsWith("/api/v2/pos/sales/held/")) {
                if (!isHeldOnServer.get() || isCancelled.get()) {
                    return new ApiResponseResult(200, "{\"count\":0,\"stale_count\":0,\"expired_count\":0,\"results\":[]}");
                }
                return new ApiResponseResult(200, """
                        {
                          "count": 1,
                          "stale_count": 1,
                          "expired_count": 0,
                          "results": [
                            {
                              "sale_id": "sale-live-1",
                              "order_number": "POS-LIVE-1",
                              "branch_id": "br-a",
                              "lifecycle_state": "HELD",
                              "hold_reference": "VIP-WAIT",
                              "hold_stock_reserved": true,
                              "line_count": 1,
                              "held_grand_total_bdt": "150.00",
                              "live_grand_total_bdt": "180.00",
                              "version": 3,
                              "staleness": {
                                "is_stale": true,
                                "has_price_change": true,
                                "stale_reasons": ["PRICE_CHANGED"],
                                "held_grand_total_bdt": "150.00",
                                "live_grand_total_bdt": "180.00",
                                "price_delta_bdt": "30.00",
                                "user_prompt_en": "MRP changed from 150.00 to 180.00 BDT.",
                                "user_prompt_bn": "দাম ১৫০.০০ থেকে ১৮০.০০ টাকা হয়েছে।"
                              }
                            }
                          ]
                        }
                        """);
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/sales/sale-live-1/resume/")) {
                if (b.contains("\"acknowledge_stale\":false")) {
                    return new ApiResponseResult(400, """
                            {
                              "error_code": "ERR_HELD_CART_REQUIRES_CONFIRMATION",
                              "message_en": "Price updated while cart was held.",
                              "message_bn": "হোল্ড থাকাকালীন মূল্য পরিবর্তিত হয়েছে।",
                              "details": {
                                "requires_confirmation": true,
                                "is_stale": true,
                                "has_price_change": true,
                                "stale_reasons": ["PRICE_CHANGED"],
                                "held_grand_total_bdt": "150.00",
                                "live_grand_total_bdt": "180.00",
                                "price_delta_bdt": "30.00",
                                "user_prompt_en": "MRP changed from 150.00 to 180.00 BDT.",
                                "user_prompt_bn": "দাম ১৫০.০০ থেকে ১৮০.০০ টাকা হয়েছে।"
                              }
                            }
                            """);
                }
                isHeldOnServer.set(false);
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-live-1",
                          "tenant_id": "ten-a",
                          "branch_id": "br-a",
                          "order_number": "POS-LIVE-1",
                          "lifecycle_state": "DRAFT",
                          "subtotal_bdt": "180.00",
                          "grand_total_bdt": "180.00",
                          "is_pricing_confirmed": false,
                          "version": 4,
                          "lines": [
                            {
                              "line_id": "ln-1",
                              "line_number": 1,
                              "product_id": "prod-monas",
                              "product_brand_name": "Monas 10mg",
                              "product_generic_name": "Montelukast",
                              "packaging_unit": "STRIP",
                              "quantity_units": 1,
                              "conversion_factor": 10,
                              "base_units_total": 10,
                              "unit_price_bdt": "180.00",
                              "line_subtotal_bdt": "180.00",
                              "line_total_bdt": "180.00"
                            }
                          ]
                        }
                        """);
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/sales/expire-stale-holds/")) {
                return new ApiResponseResult(200, """
                        {"expired_count": 1, "released_base_units": 10, "voided_count": 0, "affected_sale_ids": ["sale-live-1"]}
                        """);
            }
            return new ApiResponseResult(404, "{}");
        });
        controller.setSaleDomainClient(domainStub);

        // Add sellable item via FastSearchCandidateDto
        FastSearchCandidateDto monas = FastSearchCandidateDto.fromSnapshotItemMap(java.util.Map.ofEntries(
                java.util.Map.entry("product_id", "prod-monas"),
                java.util.Map.entry("sku_code", "MONAS-10"),
                java.util.Map.entry("brand_name", "Monas 10mg"),
                java.util.Map.entry("generic_name", "Montelukast"),
                java.util.Map.entry("barcode_tokens", "894111"),
                java.util.Map.entry("unit_price_bdt", "15.00"),
                java.util.Map.entry("strip_conversion_factor", 10),
                java.util.Map.entry("sellable_base_units", 100),
                java.util.Map.entry("available_base_units", 100),
                java.util.Map.entry("non_divisible_sealed_only", false)
        ));
        controller.handleAddCandidate(monas);
        assertEquals(1, controller.getCartItems().size());
        assertNotNull(controller.getActiveServerSale());

        TextField holdRefField = getField(controller, "holdReferenceField");
        TextField holdTtlField = getField(controller, "holdTtlField");
        CheckBox reserveCheck = getField(controller, "reserveHoldStockCheck");
        Button btnHeldCarts = getField(controller, "btnHeldCarts");
        VBox heldDrawer = getField(controller, "heldCartsDrawer");
        HBox staleBanner = getField(controller, "staleConfirmationBanner");
        Label grandTotalLbl = getField(controller, "grandTotalLabel");

        // Invalid TTL rejects cleanly without clearing cart
        holdTtlField.setText("0");
        controller.handleHoldCart();
        assertEquals(UiState.ERROR, controller.getLastUiState());
        assertEquals(1, controller.getCartItems().size());

        // Valid hold clears counter cleanly for next walk-in customer
        holdRefField.setText("VIP-WAIT");
        holdTtlField.setText("45");
        reserveCheck.setSelected(true);
        controller.handleHoldCart();
        assertEquals(UiState.READY, controller.getLastUiState());
        assertTrue(isHeldOnServer.get());
        assertTrue(controller.getCartItems().isEmpty());
        assertNull(controller.getActiveServerSale());
        assertEquals("BDT 0.00", grandTotalLbl.getText());
        assertTrue(btnHeldCarts.getText().contains("1"));

        // Press [F10] to toggle open held drawer & load held baskets
        controller.handleGlobalKeyShortcuts(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F10, false, false, false, false));
        assertTrue(heldDrawer.isVisible());
        assertEquals(1, controller.getHeldCartEntries().size());

        // Expire stale sweep works cleanly
        controller.handleExpireStaleHolds();
        assertEquals(UiState.READY, controller.getLastUiState());

        // Attempting blind resume triggers stale confirmation banner
        controller.handleResumeSelectedHeldCart();
        assertTrue(staleBanner.isVisible(), "Stale price change prompt must appear before blind resume");
        assertNotNull(controller.getPendingStaleDiagnostics());
        assertTrue(controller.getPendingStaleDiagnostics().hasPriceChange);

        // Cashier confirms stale prompt -> repriced to 180.00, drawer & prompt close cleanly
        controller.handleAcknowledgeStaleResume();
        assertEquals(UiState.READY, controller.getLastUiState());
        assertFalse(staleBanner.isVisible());
        assertFalse(heldDrawer.isVisible());
        assertEquals(1, controller.getCartItems().size());
        assertEquals("BDT 180.00", grandTotalLbl.getText());

        // Test offline local park & resume fallback (honest OFFLINE status)
        controller.handleToggleOffline();
        assertFalse(controller.getSyncEngine().isOnline());
        holdRefField.setText("OFFLINE-CUST");
        controller.handleHoldCart();
        assertEquals(UiState.OFFLINE, controller.getLastUiState());
        assertEquals(1, controller.getOfflineHeldCarts().size());
        assertTrue(controller.getCartItems().isEmpty());

        controller.resumeHeldCart(controller.getOfflineHeldCarts().get(0), false);
        assertEquals(UiState.OFFLINE, controller.getLastUiState());
        assertEquals(1, controller.getCartItems().size());
        assertTrue(controller.getOfflineHeldCarts().isEmpty());

        // Bilingual EN -> BN toggle updates new P225 held UI labels
        controller.handleToggleLanguage();
        assertTrue(btnHeldCarts.getText().contains("হোল্ড"));
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(PosController target, String name) throws Exception {
        Field f = PosController.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }
}

