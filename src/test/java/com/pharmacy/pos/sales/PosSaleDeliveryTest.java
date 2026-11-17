package com.pharmacy.pos.sales;

import com.pharmacy.pos.controllers.PosController;
import com.pharmacy.pos.models.CartItem;
import com.pharmacy.pos.models.TenantSession;
import com.pharmacy.pos.sales.PosFastSearchCartClient.FastSearchCandidateDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.AddressRevealResultDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.ApiResponseResult;
import com.pharmacy.pos.sales.PosSaleDomainClient.ClientOutcome;
import com.pharmacy.pos.sales.PosSaleDomainClient.DeliveryListResultDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosDeliveryOrderDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.UiState;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P226 Sales Preorders, Delivery Fulfillment & Address Privacy Desktop Tests.
 * Architecture Decision: ADR-0226 (P226).
 */
public class PosSaleDeliveryTest {

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
    @DisplayName("1. Client handles delivery DTOs, address privacy reveal, lifecycle transitions & RBAC/scope guards")
    void testDeliveryClientDtosPrivacyAndGuards() {
        AtomicBoolean revealedCalled = new AtomicBoolean(false);
        AtomicReference<String> lastCodTender = new AtomicReference<>("");

        PosSaleDomainClient client = new PosSaleDomainClient("http://localhost:8000", (method, path, body, token) -> {
            if ("GET".equals(method) && path.startsWith("/api/v2/pos/deliveries/")) {
                return new ApiResponseResult(200, """
                        {
                          "count": 1,
                          "results": [
                            {
                              "delivery_id": "dlv-101",
                              "tenant_id": "ten-a",
                              "branch_id": "br-a",
                              "sale_id": "sale-d1",
                              "order_number": "POS-D101",
                              "sale_lifecycle_state": "DRAFT",
                              "sale_grand_total_bdt": "210.00",
                              "delivery_number": "DLV-2026-0101",
                              "fulfillment_mode": "HOME_DELIVERY",
                              "delivery_method": "OWN_RIDER",
                              "fulfillment_state": "PREORDER_RESERVED",
                              "stock_reserved": true,
                              "recipient_name": "N**** I****",
                              "recipient_phone": "+88018******78",
                              "delivery_address_line": "[MASKED - Reveal to Dispatch]",
                              "delivery_area": "Dhanmondi",
                              "delivery_city": "Dhaka",
                              "_address_privacy_masked": true,
                              "delivery_fee_bdt": "60.00",
                              "cod_expected_bdt": "210.00",
                              "version": 1
                            }
                          ]
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/deliveries/")) {
                return new ApiResponseResult(201, """
                        {
                          "delivery_id": "dlv-102",
                          "tenant_id": "ten-a",
                          "branch_id": "br-a",
                          "sale_id": "sale-d2",
                          "order_number": "POS-D102",
                          "sale_grand_total_bdt": "240.00",
                          "delivery_number": "DLV-2026-0102",
                          "fulfillment_mode": "PREORDER_PICKUP",
                          "delivery_method": "BRANCH_PICKUP",
                          "fulfillment_state": "PREORDER_RESERVED",
                          "stock_reserved": true,
                          "recipient_name": "K**** H****",
                          "recipient_phone": "+88017******44",
                          "delivery_area": "Dhanmondi",
                          "_address_privacy_masked": true,
                          "delivery_fee_bdt": "0.00",
                          "version": 1
                        }
                        """);
            }
            if ("PATCH".equals(method) && path.equals("/api/v2/pos/deliveries/dlv-101/")) {
                return new ApiResponseResult(200, """
                        {
                          "delivery_id": "dlv-101",
                          "delivery_number": "DLV-2026-0101",
                          "fulfillment_state": "PREORDER_RESERVED",
                          "delivery_fee_bdt": "80.00",
                          "sale_grand_total_bdt": "230.00",
                          "cod_expected_bdt": "230.00",
                          "version": 2
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/deliveries/dlv-101/reveal-address/")) {
                revealedCalled.set(true);
                return new ApiResponseResult(200, """
                        {
                          "delivery_id": "dlv-101",
                          "delivery_number": "DLV-2026-0101",
                          "recipient_name": "Nadia Islam",
                          "recipient_phone": "+8801812345678",
                          "delivery_address_line": "Flat 4B, House 19, Road 8A",
                          "delivery_area": "Dhanmondi",
                          "delivery_city": "Dhaka",
                          "_address_privacy_masked": false,
                          "audit_id": "aud-dlv-9001"
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/deliveries/dlv-101/deliver/")) {
                if (body != null && body.contains("CASH")) {
                    lastCodTender.set("CASH");
                }
                return new ApiResponseResult(200, """
                        {
                          "delivery_id": "dlv-101",
                          "delivery_number": "DLV-2026-0101",
                          "fulfillment_state": "DELIVERED",
                          "sale_lifecycle_state": "FINALIZED",
                          "proof_of_delivery_ref": "POD-8821",
                          "cod_collected_bdt": "230.00",
                          "cod_expected_bdt": "0.00",
                          "version": 5
                        }
                        """);
            }
            if ("DELETE".equals(method) && path.startsWith("/api/v2/pos/deliveries/dlv-102/")) {
                return new ApiResponseResult(200, """
                        {
                          "delivery_id": "dlv-102",
                          "is_archived": true
                        }
                        """);
            }
            return new ApiResponseResult(404, "{}");
        });

        // Scope, RBAC & subscription checks
        assertEquals(UiState.PERMISSION_DENIED, client.createDeliveryOrder(
                "tok", "AUDITOR", "ten-a", "br-a", "ten-a", "br-a",
                Map.of("fulfillment_mode", "PREORDER_PICKUP", "recipient_name", "X", "recipient_phone", "01711111111"),
                false, false
        ).uiState);
        ClientOutcome<DeliveryListResultDto> crossTenant = client.listDeliveryOrders(
                "tok", "CASHIER", "ten-a", "br-a", "ten-b", "br-a", "", "", false, false
        );
        assertEquals(UiState.ERROR, crossTenant.uiState);
        assertEquals("ERR_CROSS_TENANT_VIOLATION", crossTenant.errorCode);
        assertEquals(UiState.PERMISSION_DENIED, client.listDeliveryOrders(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-b", "", "", false, false
        ).uiState);
        assertEquals(UiState.PERMISSION_DENIED, client.listDeliveryOrders(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "", "", true, false
        ).uiState);
        assertEquals(UiState.OFFLINE, client.listDeliveryOrders(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "", "", false, true
        ).uiState);

        // Input validation guards
        assertEquals(UiState.ERROR, client.createDeliveryOrder(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                Map.of("fulfillment_mode", "HOME_DELIVERY", "recipient_name", "Nadia", "recipient_phone", "01812345678"),
                false, false
        ).uiState);
        assertEquals(UiState.ERROR, client.revealDeliveryAddress(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "dlv-101", "ab", false, false
        ).uiState);

        // Authorized list & privacy mask parsing
        ClientOutcome<DeliveryListResultDto> listed = client.listDeliveryOrders(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "", "", false, false
        );
        assertEquals(UiState.READY, listed.uiState);
        assertEquals(1, listed.data.count);
        PosDeliveryOrderDto first = listed.data.results.get(0);
        assertTrue(first.addressPrivacyMasked);
        assertEquals("N**** I****", first.recipientName);
        assertEquals(new BigDecimal("60.00"), first.deliveryFeeBdt);

        // Reveal unmasked address with mandatory reason
        ClientOutcome<AddressRevealResultDto> revealed = client.revealDeliveryAddress(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "dlv-101", "Doorstep rider phone check", false, false
        );
        assertEquals(UiState.READY, revealed.uiState);
        assertTrue(revealedCalled.get());
        assertEquals("Flat 4B, House 19, Road 8A", revealed.data.deliveryAddressLine);
        assertEquals("aud-dlv-9001", revealed.data.auditId);

        // PATCH fee update
        ClientOutcome<PosDeliveryOrderDto> patched = client.updateDeliveryOrder(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "dlv-101",
                Map.of("delivery_fee_bdt", "80.00", "expected_version", 1), false, false
        );
        assertEquals(UiState.READY, patched.uiState);
        assertEquals(new BigDecimal("230.00"), patched.data.saleGrandTotalBdt);

        // Deliver transition with COD collection
        ClientOutcome<PosDeliveryOrderDto> delivered = client.transitionDelivery(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "dlv-101", "deliver",
                Map.of("proof_of_delivery_ref", "POD-8821", "cod_collected_bdt", "230.00", "cod_tender_type", "CASH"),
                "Delivered", "সম্পন্ন", false, false
        );
        assertEquals(UiState.READY, delivered.uiState);
        assertEquals("DELIVERED", delivered.data.fulfillmentState);
        assertEquals("CASH", lastCodTender.get());

        // Soft archive
        ClientOutcome<PosDeliveryOrderDto> archived = client.archiveDeliveryOrder(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "dlv-102", "Completed cleanup", false, false
        );
        assertEquals(UiState.READY, archived.uiState);
        assertTrue(archived.data.isArchived);
    }

    @Test
    @DisplayName("2. PosController loads main.fxml, creates delivery, reveals PII, packs/dispatches/delivers, queues offline & translates EN/BN")
    void testPosControllerDeliveryWorkflowAndOfflineAndLanguage() throws Exception {
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

        AtomicReference<String> serverState = new AtomicReference<>("NONE");
        AtomicBoolean sawCodTenderKey = new AtomicBoolean(false);

        PosFastSearchCartClient fastSearchStub = new PosFastSearchCartClient("http://localhost:8000", (m, p, b, t) -> {
            if (p.contains("/quick-cart/")) {
                return new ApiResponseResult(201, """
                        {
                          "created": true,
                          "sale": {
                            "sale_id": "sale-dlv-ui-1",
                            "tenant_id": "ten-a",
                            "branch_id": "br-a",
                            "order_number": "POS-UI-1",
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
                            "sale_id": "sale-dlv-ui-1",
                            "tenant_id": "ten-a",
                            "branch_id": "br-a",
                            "order_number": "POS-UI-1",
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
            if ("POST".equals(m) && p.equals("/api/v2/pos/deliveries/")) {
                serverState.set("PREORDER_RESERVED");
                return new ApiResponseResult(201, """
                        {
                          "delivery_id": "dlv-ui-1",
                          "tenant_id": "ten-a",
                          "branch_id": "br-a",
                          "sale_id": "sale-dlv-ui-1",
                          "order_number": "POS-UI-1",
                          "sale_lifecycle_state": "DRAFT",
                          "sale_grand_total_bdt": "210.00",
                          "delivery_number": "DLV-UI-001",
                          "fulfillment_mode": "HOME_DELIVERY",
                          "delivery_method": "OWN_RIDER",
                          "fulfillment_state": "PREORDER_RESERVED",
                          "stock_reserved": true,
                          "recipient_name": "F**** A****",
                          "recipient_phone": "+88019******55",
                          "delivery_address_line": "[MASKED - Reveal to Dispatch]",
                          "delivery_area": "Banani",
                          "delivery_city": "Dhaka",
                          "_address_privacy_masked": true,
                          "delivery_fee_bdt": "60.00",
                          "cod_expected_bdt": "210.00",
                          "version": 1
                        }
                        """);
            }
            if ("GET".equals(m) && p.startsWith("/api/v2/pos/deliveries/")) {
                if ("NONE".equals(serverState.get())) {
                    return new ApiResponseResult(200, "{\"count\":0,\"results\":[]}");
                }
                return new ApiResponseResult(200, """
                        {
                          "count": 1,
                          "results": [
                            {
                              "delivery_id": "dlv-ui-1",
                              "delivery_number": "DLV-UI-001",
                              "fulfillment_mode": "HOME_DELIVERY",
                              "fulfillment_state": "%s",
                              "recipient_name": "F**** A****",
                              "recipient_phone": "+88019******55",
                              "delivery_address_line": "[MASKED - Reveal to Dispatch]",
                              "delivery_area": "Banani",
                              "_address_privacy_masked": true,
                              "delivery_fee_bdt": "60.00",
                              "sale_grand_total_bdt": "210.00",
                              "cod_expected_bdt": "210.00",
                              "version": 1
                            }
                          ]
                        }
                        """.formatted(serverState.get()));
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/deliveries/dlv-ui-1/reveal-address/")) {
                return new ApiResponseResult(200, """
                        {
                          "delivery_id": "dlv-ui-1",
                          "delivery_number": "DLV-UI-001",
                          "recipient_name": "Farhana Akter",
                          "recipient_phone": "+8801911223355",
                          "delivery_address_line": "House 14, Road 11, Banani",
                          "delivery_area": "Banani",
                          "delivery_city": "Dhaka",
                          "_address_privacy_masked": false,
                          "audit_id": "aud-ui-777"
                        }
                        """);
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/deliveries/dlv-ui-1/pack/")) {
                serverState.set("PACKED");
                return new ApiResponseResult(200, """
                        {"delivery_id":"dlv-ui-1","delivery_number":"DLV-UI-001","fulfillment_state":"PACKED","delivery_fee_bdt":"60.00","sale_grand_total_bdt":"210.00","cod_expected_bdt":"210.00","version":2}
                        """);
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/deliveries/dlv-ui-1/dispatch/")) {
                serverState.set("DISPATCHED");
                return new ApiResponseResult(200, """
                        {"delivery_id":"dlv-ui-1","delivery_number":"DLV-UI-001","fulfillment_state":"DISPATCHED","rider_name":"Kamal Rider","delivery_fee_bdt":"60.00","sale_grand_total_bdt":"210.00","cod_expected_bdt":"210.00","version":3}
                        """);
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/deliveries/dlv-ui-1/deliver/")) {
                sawCodTenderKey.set(b != null && b.contains("\"cod_tender_type\":\"CASH\""));
                serverState.set("DELIVERED");
                return new ApiResponseResult(200, """
                        {"delivery_id":"dlv-ui-1","delivery_number":"DLV-UI-001","fulfillment_state":"DELIVERED","sale_lifecycle_state":"FINALIZED","proof_of_delivery_ref":"POD-77","delivery_fee_bdt":"60.00","sale_grand_total_bdt":"210.00","cod_expected_bdt":"0.00","cod_collected_bdt":"210.00","version":4}
                        """);
            }
            return new ApiResponseResult(404, "{}");
        });
        controller.setSaleDomainClient(domainStub);

        // Open delivery drawer with [F11]
        VBox deliveryDrawer = getField(controller, "deliveryDrawer");
        Button btnDeliveries = getField(controller, "btnDeliveries");
        Label deliveryDetailLabel = getField(controller, "deliveryDetailLabel");
        ComboBox<String> deliveryModeCombo = getField(controller, "deliveryModeCombo");
        TextField recipientField = getField(controller, "deliveryRecipientField");
        TextField phoneField = getField(controller, "deliveryPhoneField");
        TextField addressField = getField(controller, "deliveryAddressField");
        TextField areaField = getField(controller, "deliveryAreaField");
        TextField feeField = getField(controller, "deliveryFeeField");
        CheckBox reserveCheck = getField(controller, "reserveDeliveryStockCheck");
        TextField riderField = getField(controller, "deliveryRiderField");
        TextField podField = getField(controller, "deliveryPodField");

        controller.handleGlobalKeyShortcuts(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F11, false, false, false, false));
        assertTrue(deliveryDrawer.isVisible());

        // Add product to cart
        FastSearchCandidateDto monas = FastSearchCandidateDto.fromSnapshotItemMap(Map.ofEntries(
                Map.entry("product_id", "prod-monas"),
                Map.entry("sku_code", "MONAS-10"),
                Map.entry("brand_name", "Monas 10mg"),
                Map.entry("generic_name", "Montelukast"),
                Map.entry("barcode_tokens", "894111"),
                Map.entry("unit_price_bdt", "15.00"),
                Map.entry("strip_conversion_factor", 10),
                Map.entry("sellable_base_units", 100),
                Map.entry("available_base_units", 100),
                Map.entry("non_divisible_sealed_only", false)
        ));
        controller.handleAddCandidate(monas);
        assertEquals(1, controller.getCartItems().size());

        // Missing street address blocks HOME_DELIVERY creation
        deliveryModeCombo.setValue("HOME_DELIVERY");
        recipientField.setText("Farhana Akter");
        phoneField.setText("01911223355");
        addressField.setText("");
        controller.handleCreateDeliveryFromCart();
        assertEquals(UiState.ERROR, controller.getLastUiState());

        // Valid HOME_DELIVERY creates masked order, updates badge & clears active counter
        addressField.setText("House 14, Road 11, Banani");
        areaField.setText("Banani");
        feeField.setText("60.00");
        reserveCheck.setSelected(true);
        controller.handleCreateDeliveryFromCart();
        assertEquals(UiState.READY, controller.getLastUiState());
        assertEquals(1, controller.getDeliveryEntries().size());
        assertTrue(controller.getDeliveryEntries().get(0).addressPrivacyMasked);
        assertTrue(controller.getCartItems().isEmpty());
        assertTrue(btnDeliveries.getText().contains("1"));
        assertTrue(deliveryDetailLabel.getText().contains("F**** A****"));

        // Operational Reveal Address shows full PII and audit ID
        controller.handleRevealSelectedDeliveryAddress();
        assertEquals(UiState.READY, controller.getLastUiState());
        assertFalse(controller.getDeliveryEntries().get(0).addressPrivacyMasked);
        assertTrue(deliveryDetailLabel.getText().contains("House 14, Road 11, Banani"));

        // Pack -> Dispatch -> Deliver with POD & COD tender
        controller.handlePackSelectedDelivery();
        assertEquals("PACKED", controller.getDeliveryEntries().get(0).fulfillmentState);
        riderField.setText("Kamal Rider");
        controller.handleDispatchSelectedDelivery();
        assertEquals("DISPATCHED", controller.getDeliveryEntries().get(0).fulfillmentState);
        podField.setText("POD-77");
        controller.handleDeliverSelectedOrder();
        assertEquals("DELIVERED", controller.getDeliveryEntries().get(0).fulfillmentState);
        assertTrue(sawCodTenderKey.get(), "COD cash tender key must use cod_tender_type=CASH");

        // Offline local delivery queueing & cancellation
        controller.handleToggleOffline();
        controller.getCartItems().add(new CartItem(
                "prod-monas", "Monas 10mg", "Montelukast", "STRIP", 1,
                new BigDecimal("150.00"), "LOT-1", LocalDate.now().plusYears(1), BigDecimal.ZERO
        ));
        deliveryModeCombo.setValue("PREORDER_PICKUP");
        recipientField.setText("Rafiq Offline");
        phoneField.setText("01712000111");
        addressField.setText("");
        feeField.setText("0.00");
        controller.handleCreateDeliveryFromCart();
        assertEquals(UiState.OFFLINE, controller.getLastUiState());
        assertEquals(1, controller.getOfflineDeliveryOrders().size());
        assertEquals(2, controller.getDeliveryEntries().size());

        // Select offline entry & cancel removes locally
        PosDeliveryOrderDto offEntry = controller.getOfflineDeliveryOrders().get(0);
        javafx.scene.control.ListView<PosDeliveryOrderDto> listView = getField(controller, "deliveriesList");
        listView.getSelectionModel().select(offEntry);
        controller.handleCancelSelectedDelivery();
        assertTrue(controller.getOfflineDeliveryOrders().isEmpty());

        // EN -> BN toggle translates P226 delivery controls
        controller.handleToggleLanguage();
        assertTrue(btnDeliveries.getText().contains("ডেলিভারি"));
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(PosController target, String name) throws Exception {
        Field f = PosController.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }
}
