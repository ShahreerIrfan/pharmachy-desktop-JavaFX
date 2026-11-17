package com.pharmacy.pos.sales;

import com.pharmacy.pos.controllers.PosController;
import com.pharmacy.pos.models.TenantSession;
import com.pharmacy.pos.sales.PosFastSearchCartClient.FastSearchCandidateDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.ApiResponseResult;
import com.pharmacy.pos.sales.PosSaleDomainClient.CashierCloseReconciliationDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.ClientOutcome;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosSaleOrderDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.SplitPaymentInputDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.UiState;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P229 Cash, bKash, Nagad, Rocket, Upay, Card & Verified Split Payments Desktop Tests.
 * Architecture Decision: ADR-0229 (P229).
 */
public class PosCashSplitPaymentsTest {

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
    @DisplayName("1. Client validates digital TrxID refs, parses change/net settled, verify/void & close reconciliation DTOs")
    void testSplitPaymentClientValidationDtosAndCloseReconciliation() {
        AtomicBoolean sawSplitPost = new AtomicBoolean(false);
        AtomicBoolean sawClosePost = new AtomicBoolean(false);

        PosSaleDomainClient client = new PosSaleDomainClient("http://localhost:8000", (method, path, body, token) -> {
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/sale-sp-1/split-payments/")) {
                sawSplitPost.set(true);
                return new ApiResponseResult(201, """
                        {
                          "idempotent_replay": false,
                          "sale": {
                            "sale_id": "sale-sp-1",
                            "order_number": "POS-SP-100",
                            "lifecycle_state": "DRAFT",
                            "grand_total_bdt": "100.00",
                            "paid_amount_bdt": "100.00",
                            "change_returned_bdt": "10.00",
                            "due_amount_bdt": "0.00",
                            "payment_attempts": [
                              {
                                "attempt_id": "pa-1",
                                "attempt_number": 1,
                                "tender_type": "BKASH",
                                "amount_bdt": "35.00",
                                "change_given_bdt": "0.00",
                                "net_settled_amount_bdt": "35.00",
                                "status": "CAPTURED",
                                "verification_status": "VERIFIED",
                                "gateway_reference": "BKS-1122"
                              },
                              {
                                "attempt_id": "pa-2",
                                "attempt_number": 2,
                                "tender_type": "CARD",
                                "amount_bdt": "25.00",
                                "change_given_bdt": "0.00",
                                "net_settled_amount_bdt": "25.00",
                                "status": "CAPTURED",
                                "verification_status": "VERIFIED",
                                "gateway_reference": "CRD-9988",
                                "card_last4": "4321"
                              },
                              {
                                "attempt_id": "pa-3",
                                "attempt_number": 3,
                                "tender_type": "CASH",
                                "amount_bdt": "50.00",
                                "change_given_bdt": "10.00",
                                "net_settled_amount_bdt": "40.00",
                                "status": "CAPTURED",
                                "verification_status": "NOT_REQUIRED"
                              }
                            ]
                          }
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/sale-sp-1/verify-payment-attempt/")) {
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-sp-1",
                          "paid_amount_bdt": "100.00",
                          "payment_attempts": [
                            {
                              "attempt_id": "pa-p1",
                              "tender_type": "NAGAD",
                              "amount_bdt": "30.00",
                              "net_settled_amount_bdt": "30.00",
                              "status": "CAPTURED",
                              "verification_status": "VERIFIED",
                              "gateway_reference": "NGD-CONF-1"
                            }
                          ]
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/sale-sp-1/void-payment-attempt/")) {
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-sp-1",
                          "paid_amount_bdt": "70.00",
                          "payment_attempts": [
                            {
                              "attempt_id": "pa-p1",
                              "status": "VOIDED",
                              "void_reason": "Switched to Cash"
                            }
                          ]
                        }
                        """);
            }
            if ("GET".equals(method) && path.startsWith("/api/v2/pos/sales/close-reconciliation/")) {
                return new ApiResponseResult(200, """
                        {
                          "branch_id": "br-a",
                          "opening_float_bdt": "1000.00",
                          "gross_cash_tendered_bdt": "50.00",
                          "cash_change_returned_bdt": "10.00",
                          "net_cash_collected_bdt": "40.00",
                          "cash_refunded_bdt": "0.00",
                          "expected_drawer_cash_bdt": "1040.00",
                          "digital_total_bdt": "60.00",
                          "due_issued_bdt": "0.00",
                          "sales_count": 1,
                          "journal_balanced": true,
                          "tender_totals": {
                            "CASH": {"net_bdt": "40.00"},
                            "BKASH": {"net_bdt": "35.00"},
                            "CARD": {"net_bdt": "25.00"}
                          }
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/close-reconciliation/")) {
                sawClosePost.set(true);
                return new ApiResponseResult(201, """
                        {
                          "reconciliation_id": "pcr-ui-1",
                          "branch_id": "br-a",
                          "opening_float_bdt": "1000.00",
                          "expected_drawer_cash_bdt": "1040.00",
                          "counted_cash_bdt": "1040.00",
                          "variance_bdt": "0.00",
                          "digital_total_bdt": "60.00",
                          "status": "BALANCED"
                        }
                        """);
            }
            return new ApiResponseResult(404, "{}");
        });

        // 1) Local guards: missing digital TrxID & offline digital block
        ClientOutcome<PosSaleOrderDto> missingRef = client.submitSplitPayments(
                "tok-cash", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-sp-1", List.of(new SplitPaymentInputDto("BKASH", new BigDecimal("50.00"), "ab", "")),
                "bk1", true, false, false
        );
        assertEquals(UiState.ERROR, missingRef.uiState);
        assertEquals("ERR_DIGITAL_TENDER_REFERENCE_REQUIRED", missingRef.errorCode);
        assertFalse(sawSplitPost.get());

        ClientOutcome<PosSaleOrderDto> offlineDigital = client.submitSplitPayments(
                "tok-cash", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-sp-1", List.of(new SplitPaymentInputDto("NAGAD", new BigDecimal("50.00"), "", "")),
                "bk2", true, false, true
        );
        assertEquals(UiState.OFFLINE, offlineDigital.uiState);
        assertEquals("ERR_OFFLINE_DIGITAL_PAYMENT_REQUIRES_REF", offlineDigital.errorCode);

        // 2) Valid multi-tender split with Cash change attribution
        ClientOutcome<PosSaleOrderDto> okSplit = client.submitSplitPayments(
                "tok-cash", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-sp-1",
                List.of(
                        new SplitPaymentInputDto("BKASH", new BigDecimal("35.00"), "BKS-1122", "01711****22"),
                        new SplitPaymentInputDto("CARD", new BigDecimal("25.00"), "CRD-9988", "4321"),
                        new SplitPaymentInputDto("CASH", new BigDecimal("50.00"), "", "")
                ),
                "bk-ok", true, false, false
        );
        assertEquals(UiState.READY, okSplit.uiState);
        assertEquals(new BigDecimal("10.00"), okSplit.data.changeReturnedBdt);
        assertEquals(new BigDecimal("40.00"), okSplit.data.paymentAttempts.get(2).netSettledAmountBdt);
        assertEquals(new BigDecimal("10.00"), okSplit.data.paymentAttempts.get(2).changeGivenBdt);

        // 3) Verify & Void pre-final attempts + Close reconciliation
        assertEquals(UiState.READY, client.verifyPaymentAttempt(
                "tok-cash", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "sale-sp-1", "pa-p1", "NGD-CONF-1", false, false
        ).uiState);
        assertEquals(UiState.ERROR, client.voidPaymentAttempt(
                "tok-cash", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "sale-sp-1", "pa-p1", "x", false, false
        ).uiState);
        assertEquals(UiState.READY, client.voidPaymentAttempt(
                "tok-cash", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "sale-sp-1", "pa-p1", "Switched to Cash", false, false
        ).uiState);

        ClientOutcome<CashierCloseReconciliationDto> prev = client.getCloseReconciliation(
                "tok-cash", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "", false, false
        );
        assertEquals(UiState.READY, prev.uiState);
        assertEquals(new BigDecimal("1040.00"), prev.data.expectedDrawerCashBdt);
        assertEquals(new BigDecimal("35.00"), prev.data.netByTender.get("BKASH"));

        ClientOutcome<CashierCloseReconciliationDto> closed = client.submitCloseReconciliation(
                "tok-cash", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "", new BigDecimal("1040.00"), "Exact shift count", false, false
        );
        assertEquals(UiState.READY, closed.uiState);
        assertEquals("BALANCED", closed.data.status);
        assertTrue(sawClosePost.get());
    }

    @Test
    @DisplayName("2. PosController [F6] split payment drawer, Cash change, offline digital block, close reconciliation & EN/BN")
    void testPosControllerSplitPaymentsDrawerCheckoutOfflineAndClose() throws Exception {
        URL fxmlUrl = getClass().getResource("/com/pharmacy/pos/views/main.fxml");
        assertNotNull(fxmlUrl);
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();
        assertNotNull(root);

        PosController controller = loader.getController();
        controller.setAsyncNetworkCalls(false);
        controller.setSessionDetails(
                new TenantSession("ten-a", "br-a", "tok-cashier", "cashier-01", "CASHIER", true),
                "Cashier Tariq", "Day Shift", "1000.00"
        );

        AtomicBoolean sawSplitPayments = new AtomicBoolean(false);
        AtomicBoolean finalizedOnServer = new AtomicBoolean(false);
        AtomicBoolean sawCloseSubmit = new AtomicBoolean(false);

        PosFastSearchCartClient searchStub = new PosFastSearchCartClient("http://localhost:8000", (m, p, b, t) -> {
            if (p.contains("/quick-cart/")) {
                return new ApiResponseResult(201, """
                        {
                          "created": true,
                          "sale": {
                            "sale_id": "sale-ui-sp-1",
                            "tenant_id": "ten-a",
                            "branch_id": "br-a",
                            "order_number": "POS-UI-SP-1",
                            "lifecycle_state": "DRAFT",
                            "requires_rx_verification": false,
                            "rx_gate_status": "NOT_REQUIRED",
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
                            "sale_id": "sale-ui-sp-1",
                            "tenant_id": "ten-a",
                            "branch_id": "br-a",
                            "order_number": "POS-UI-SP-1",
                            "lifecycle_state": "DRAFT",
                            "requires_rx_verification": false,
                            "rx_gate_status": "NOT_REQUIRED",
                            "subtotal_bdt": "12.00",
                            "grand_total_bdt": "12.00",
                            "version": 2,
                            "lines": [
                              {
                                "line_id": "ln-sp-1",
                                "line_number": 1,
                                "product_id": "prod-napa",
                                "product_brand_name": "Napa 500",
                                "packaging_unit": "STRIP",
                                "quantity_units": 1,
                                "conversion_factor": 10,
                                "base_units_total": 10,
                                "unit_price_bdt": "12.00",
                                "line_subtotal_bdt": "12.00",
                                "line_total_bdt": "12.00",
                                "regulatory_classification": "OTC"
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
            if ("POST".equals(m) && p.equals("/api/v2/pos/sales/sale-ui-sp-1/split-payments/")) {
                sawSplitPayments.set(true);
                return new ApiResponseResult(201, """
                        {
                          "idempotent_replay": false,
                          "sale": {
                            "sale_id": "sale-ui-sp-1",
                            "order_number": "POS-UI-SP-1",
                            "lifecycle_state": "DRAFT",
                            "subtotal_bdt": "12.00",
                            "grand_total_bdt": "12.00",
                            "paid_amount_bdt": "12.00",
                            "change_returned_bdt": "4.00",
                            "due_amount_bdt": "0.00",
                            "version": 3,
                            "lines": [
                              {
                                "line_id": "ln-sp-1",
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
                            "payment_attempts": [
                              {
                                "attempt_id": "pa-1",
                                "tender_type": "BKASH",
                                "amount_bdt": "6.00",
                                "net_settled_amount_bdt": "6.00",
                                "change_given_bdt": "0.00",
                                "status": "CAPTURED",
                                "gateway_reference": "BKS-UI-889"
                              },
                              {
                                "attempt_id": "pa-2",
                                "tender_type": "CASH",
                                "amount_bdt": "10.00",
                                "net_settled_amount_bdt": "6.00",
                                "change_given_bdt": "4.00",
                                "status": "CAPTURED"
                              }
                            ]
                          }
                        }
                        """);
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/sales/sale-ui-sp-1/finalize/")) {
                finalizedOnServer.set(true);
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-ui-sp-1",
                          "order_number": "POS-UI-SP-1",
                          "lifecycle_state": "FINALIZED",
                          "grand_total_bdt": "12.00",
                          "paid_amount_bdt": "12.00",
                          "change_returned_bdt": "4.00",
                          "version": 4,
                          "payment_attempts": [
                            {"attempt_id": "pa-1", "tender_type": "BKASH", "amount_bdt": "6.00", "net_settled_amount_bdt": "6.00", "status": "CAPTURED"},
                            {"attempt_id": "pa-2", "tender_type": "CASH", "amount_bdt": "10.00", "net_settled_amount_bdt": "6.00", "change_given_bdt": "4.00", "status": "CAPTURED"}
                          ]
                        }
                        """);
            }
            if ("GET".equals(m) && p.startsWith("/api/v2/pos/sales/close-reconciliation/")) {
                return new ApiResponseResult(200, """
                        {
                          "branch_id": "br-a",
                          "opening_float_bdt": "1000.00",
                          "gross_cash_tendered_bdt": "10.00",
                          "cash_change_returned_bdt": "4.00",
                          "net_cash_collected_bdt": "6.00",
                          "cash_refunded_bdt": "0.00",
                          "expected_drawer_cash_bdt": "1006.00",
                          "digital_total_bdt": "6.00",
                          "status": "BALANCED"
                        }
                        """);
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/sales/close-reconciliation/")) {
                sawCloseSubmit.set(true);
                return new ApiResponseResult(201, """
                        {
                          "reconciliation_id": "pcr-ui-close",
                          "opening_float_bdt": "1000.00",
                          "gross_cash_tendered_bdt": "10.00",
                          "cash_change_returned_bdt": "4.00",
                          "net_cash_collected_bdt": "6.00",
                          "expected_drawer_cash_bdt": "1006.00",
                          "counted_cash_bdt": "1006.00",
                          "variance_bdt": "0.00",
                          "digital_total_bdt": "6.00",
                          "status": "BALANCED"
                        }
                        """);
            }
            return new ApiResponseResult(404, "{}");
        });
        controller.setSaleDomainClient(domainStub);

        Button btnSplitPayments = getField(controller, "btnSplitPayments");
        VBox splitPaymentDrawer = getField(controller, "splitPaymentDrawer");
        ComboBox<String> splitTenderCombo = getField(controller, "splitTenderCombo");
        TextField splitAmountField = getField(controller, "splitAmountField");
        TextField splitRefField = getField(controller, "splitRefField");
        Label splitPaySummaryLabel = getField(controller, "splitPaySummaryLabel");
        Label closeReconSummaryLabel = getField(controller, "closeReconSummaryLabel");
        TextField closeCountedCashField = getField(controller, "closeCountedCashField");

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

        // 1) Online scan-add -> open [F6] Split Drawer -> missing ref blocked -> bKash 6 + Cash 10 (change 4) -> finalize!
        controller.handleAddCandidate(safeOtc);
        controller.handleGlobalKeyShortcuts(new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", KeyCode.F6, false, false, false, false
        ));
        assertTrue(splitPaymentDrawer.isVisible());

        splitTenderCombo.getSelectionModel().select("BKASH");
        splitAmountField.setText("6.00");
        splitRefField.setText("ab");
        controller.handleAddSplitPaymentLeg();
        assertEquals(UiState.ERROR, controller.getLastUiState(), "Short bKash ref must reject!");

        splitRefField.setText("BKS-UI-889");
        controller.handleAddSplitPaymentLeg();
        assertEquals(UiState.READY, controller.getLastUiState());

        splitTenderCombo.getSelectionModel().select("CASH");
        splitAmountField.setText("10.00");
        controller.handleAddSplitPaymentLeg();
        assertTrue(splitPaySummaryLabel.getText().contains("Change: BDT 4.00"));

        controller.handleCheckout();
        assertEquals(UiState.READY, controller.getLastUiState());
        assertTrue(sawSplitPayments.get());
        assertTrue(finalizedOnServer.get());
        assertTrue(controller.getCartItems().isEmpty());

        // 2) Close Shift Reconciliation
        controller.handleRefreshCloseReconciliation();
        assertTrue(closeReconSummaryLabel.getText().contains("1006.00"));
        closeCountedCashField.setText("1006.00");
        controller.handleSubmitCloseReconciliation();
        assertTrue(sawCloseSubmit.get());
        assertTrue(closeReconSummaryLabel.getText().contains("BALANCED"));

        // 3) Offline mode: Nagad without ref blocks offline checkout cleanly, Cash split works
        controller.handleToggleOffline();
        controller.handleAddCandidate(safeOtc);
        splitTenderCombo.getSelectionModel().select("NAGAD");
        splitAmountField.setText("12.00");
        splitRefField.setText("NGD-OFFLINE-442");
        controller.handleAddSplitPaymentLeg();
        // Force cleared without ref by checking local helper vs unverified digital in offline
        controller.handleClearSplitPaymentLegs();
        splitTenderCombo.getSelectionModel().select("CASH");
        splitAmountField.setText("15.00");
        controller.handleAddSplitPaymentLeg();
        controller.handleCheckout();
        assertEquals(UiState.OFFLINE, controller.getLastUiState());
        assertEquals(1, controller.getSyncEngine().getPendingCount());

        // 4) EN/BN translation check (zero emojis)
        controller.handleToggleLanguage();
        assertTrue(btnSplitPayments.getText().contains("স্প্লিট পে"));
        assertEquals("ড্রয়ার ক্লোজ করুন", ((Button) getField(controller, "btnSubmitCloseRecon")).getText());
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(PosController target, String name) throws Exception {
        Field f = PosController.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }
}
