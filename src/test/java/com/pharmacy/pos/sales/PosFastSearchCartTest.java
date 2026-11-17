package com.pharmacy.pos.sales;

import com.pharmacy.pos.controllers.PosController;
import com.pharmacy.pos.models.CartItem;
import com.pharmacy.pos.models.TenantSession;
import com.pharmacy.pos.sales.PosFastSearchCartClient.FastSearchCandidateDto;
import com.pharmacy.pos.sales.PosFastSearchCartClient.FastSearchResponseDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.ApiResponseResult;
import com.pharmacy.pos.sales.PosSaleDomainClient.ClientOutcome;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosSaleOrderDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.UiState;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P222 Fast Search & Keyboard-First Barcode Cart Desktop Tests.
 * Architecture Decision: ADR-0222 (P222).
 */
public class PosFastSearchCartTest {

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
    @DisplayName("1. Client parses prioritized search response, packaging options, Rx/safety tags & offline snapshot fallback")
    void testClientSearchAndOfflineSnapshotFallback() {
        String sampleJson = """
                {
                  "query": "880112233",
                  "tenant_id": "ten-a",
                  "branch_id": "br-a",
                  "total_matches": 1,
                  "latency_ms": 3.45,
                  "results": [
                    {
                      "product_id": "med-napa",
                      "variant_id": "var-napa-10",
                      "sku_code": "NAPA-500-10",
                      "display_name": "Napa 500mg (10s Strip)",
                      "brand_name": "Napa",
                      "brand_name_bn": "নাপা",
                      "generic_name": "Paracetamol",
                      "generic_name_bn": "প্যারাসিটামল",
                      "strength": "500mg",
                      "dosage_form": "Tablet",
                      "barcode": "880112233",
                      "shelf_location": "Rack A-2",
                      "resolution_source": "BARCODE_ALTERNATE",
                      "resolved_packaging_unit": "STRIP",
                      "resolved_conversion_factor": 10,
                      "unit_price_bdt": "12.00",
                      "base_unit_price_bdt": "1.20",
                      "unit_cost_bdt": null,
                      "sellable_base_units": 50,
                      "reserved_base_units": 0,
                      "available_base_units": 50,
                      "quarantined_base_units": 0,
                      "expired_base_units": 0,
                      "recalled_base_units": 0,
                      "earliest_batch_id": "bat-1",
                      "earliest_batch_number": "BN-01",
                      "earliest_expiry_date": "2028-06-30",
                      "requires_rx_verification": false,
                      "non_divisible_sealed_only": false,
                      "is_sellable": true,
                      "block_reason": "",
                      "snapshot_read_model_id": "skusnap-1",
                      "rank_score": 120,
                      "packaging_options": [
                        {"packaging_unit": "TABLET", "label_en": "Tablet", "label_bn": "ট্যাবলেট", "conversion_factor": 1, "unit_price_bdt": "1.20"},
                        {"packaging_unit": "STRIP", "label_en": "Strip (10u)", "label_bn": "স্ট্রিপ (10টি)", "conversion_factor": 10, "unit_price_bdt": "12.00"},
                        {"packaging_unit": "BOX", "label_en": "Box (100u)", "label_bn": "বক্স (100টি)", "conversion_factor": 100, "unit_price_bdt": "120.00"}
                      ]
                    }
                  ]
                }
                """;

        PosFastSearchCartClient onlineClient = new PosFastSearchCartClient("http://localhost:8000", (m, path, body, tok) -> {
            assertEquals("GET", m);
            assertTrue(path.contains("/api/v2/pos/search/"));
            return new ApiResponseResult(200, sampleJson);
        });

        ClientOutcome<FastSearchResponseDto> res = onlineClient.fastSearch("br-a", "880112233", 10, "tok-cashier");
        assertEquals(UiState.READY, res.uiState);
        assertNotNull(res.data);
        assertEquals(1, res.data.results.size());
        FastSearchCandidateDto item = res.data.results.get(0);
        assertEquals("BARCODE_ALTERNATE", item.resolutionSource);
        assertNull(item.unitCostBdt, "Cashier must not receive unit acquisition cost");
        assertEquals(new BigDecimal("12.00"), item.unitPriceBdt);
        assertEquals(3, item.packagingOptions.size());

        // Offline network exception falls back cleanly to branchSnapshotCache with OFFLINE state
        PosFastSearchCartClient offlineClient = new PosFastSearchCartClient("http://localhost:8000", (m, path, body, tok) -> {
            throw new RuntimeException("Network unreachable");
        });
        offlineClient.seedOfflineSnapshot("br-a", List.of(item));

        ClientOutcome<FastSearchResponseDto> offRes = offlineClient.fastSearch("br-a", "নাপা", 10, "tok-cashier");
        assertEquals(UiState.OFFLINE, offRes.uiState);
        assertTrue(offRes.data.fromOfflineCache);
        assertEquals(1, offRes.data.results.size());
        assertEquals("OFFLINE_SNAPSHOT", offRes.data.results.get(0).resolutionSource);
    }

    @Test
    @DisplayName("2. Client handles HTTP 403 permission denied, HTTP 422 errors, scan-add & mutateCartLine")
    void testClientScanAddMutateAndErrorStates() {
        AtomicInteger step = new AtomicInteger(0);
        PosFastSearchCartClient client = new PosFastSearchCartClient("http://localhost:8000", (m, path, body, tok) -> {
            int cur = step.getAndIncrement();
            if (cur == 0) {
                return new ApiResponseResult(403, "{\"error_code\":\"ERR_PERMISSION_DENIED\",\"message_en\":\"Denied\"}");
            }
            if (cur == 1) {
                return new ApiResponseResult(200, """
                        {
                          "sale": {
                            "sale_id": "sale-q1",
                            "branch_id": "br-a",
                            "order_number": "POS-Q1",
                            "lifecycle_state": "DRAFT",
                            "subtotal_bdt": "12.00",
                            "tax_amount_bdt": "0.00",
                            "grand_total_bdt": "12.00",
                            "version": 2,
                            "lines": [
                              {
                                "line_id": "sline-1",
                                "line_number": 1,
                                "product_id": "med-napa",
                                "product_brand_name": "Napa",
                                "product_generic_name": "Paracetamol",
                                "packaging_unit": "STRIP",
                                "quantity_units": 1,
                                "conversion_factor": 10,
                                "base_units_total": 10,
                                "unit_price_bdt": "12.00",
                                "line_subtotal_bdt": "12.00",
                                "line_total_bdt": "12.00"
                              }
                            ]
                          }
                        }
                        """);
            }
            return new ApiResponseResult(422, "{\"error_code\":\"ERR_EXPIRED_OR_UNSELLABLE_STOCK\",\"message_en\":\"Expired batch blocked\"}");
        });

        ClientOutcome<FastSearchResponseDto> denied = client.fastSearch("br-other", "napa", 10, "tok");
        assertEquals(UiState.PERMISSION_DENIED, denied.uiState);
        assertEquals("ERR_PERMISSION_DENIED", denied.errorCode);

        ClientOutcome<PosSaleOrderDto> added = client.scanAdd("sale-q1", "880112233", "med-napa", "var-1", "STRIP", 1, 1, "tok");
        assertEquals(UiState.READY, added.uiState);
        assertEquals(2, added.data.version);
        assertEquals(new BigDecimal("12.00"), added.data.grandTotalBdt);

        ClientOutcome<PosSaleOrderDto> expiredBlocked = client.mutateCartLine("sale-q1", "sline-1", 1, null, null, false, 2, "tok");
        assertEquals(UiState.ERROR, expiredBlocked.uiState);
        assertEquals("ERR_EXPIRED_OR_UNSELLABLE_STOCK", expiredBlocked.errorCode);
    }

    @Test
    @DisplayName("3. FXML loads cleanly and PosController keyboard scan/add, +/-/U/Del, FEFO safety block & EN/BN work")
    void testPosControllerKeyboardWorkflowAndFefoBlocking() throws Exception {
        URL fxmlUrl = getClass().getResource("/com/pharmacy/pos/views/main.fxml");
        assertNotNull(fxmlUrl, "main.fxml must be loadable");

        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();
        assertNotNull(root);

        PosController controller = loader.getController();
        assertNotNull(controller);
        controller.setAsyncNetworkCalls(false);
        controller.setSessionDetails(
                new TenantSession("ten-a", "br-a", "", "cashier-01", "CASHIER", true),
                "Tanvir Ahmed",
                "Morning Shift",
                "5000.00"
        );

        // Seed branch offline snapshot with one sellable item (qty=15 base units) and one expired-only blocked item
        FastSearchCandidateDto sellableNapa = FastSearchCandidateDto.fromSnapshotItemMap(java.util.Map.ofEntries(
                java.util.Map.entry("read_model_id", "skusnap-napa"),
                java.util.Map.entry("product_id", "med-napa"),
                java.util.Map.entry("sku_code", "NAPA-500"),
                java.util.Map.entry("brand_name", "Napa"),
                java.util.Map.entry("brand_name_bn", "নাপা"),
                java.util.Map.entry("generic_name", "Paracetamol"),
                java.util.Map.entry("generic_name_bn", "প্যারাসিটামল"),
                java.util.Map.entry("strength", "500mg"),
                java.util.Map.entry("dosage_form", "Tablet"),
                java.util.Map.entry("barcode_tokens", "880111222"),
                java.util.Map.entry("unit_price_bdt", "1.20"),
                java.util.Map.entry("strip_conversion_factor", 10),
                java.util.Map.entry("box_conversion_factor", 100),
                java.util.Map.entry("sellable_base_units", 15),
                java.util.Map.entry("reserved_base_units", 0),
                java.util.Map.entry("available_base_units", 15),
                java.util.Map.entry("expired_base_units", 0),
                java.util.Map.entry("shelf_location", "Rack A-1"),
                java.util.Map.entry("non_divisible_sealed_only", false)
        ));

        FastSearchCandidateDto expiredInsulin = FastSearchCandidateDto.fromSnapshotItemMap(java.util.Map.ofEntries(
                java.util.Map.entry("read_model_id", "skusnap-exp"),
                java.util.Map.entry("product_id", "med-exp"),
                java.util.Map.entry("sku_code", "EXP-999"),
                java.util.Map.entry("brand_name", "ExpiredMed"),
                java.util.Map.entry("barcode_tokens", "999000"),
                java.util.Map.entry("unit_price_bdt", "50.00"),
                java.util.Map.entry("sellable_base_units", 0),
                java.util.Map.entry("available_base_units", 0),
                java.util.Map.entry("expired_base_units", 20),
                java.util.Map.entry("non_divisible_sealed_only", true)
        ));

        PosFastSearchCartClient offlineStubClient = new PosFastSearchCartClient("http://localhost:8000", (m, p, b, t) -> {
            throw new RuntimeException("Offline counter");
        });
        offlineStubClient.seedOfflineSnapshot("br-a", List.of(sellableNapa, expiredInsulin));
        controller.setSearchCartClient(offlineStubClient);

        TextField input = getField(controller, "barcodeInputField");
        Label totalLbl = getField(controller, "grandTotalLabel");
        Label stateLbl = getField(controller, "stateStatusLabel");

        // Scan barcode -> adds 1 TABLET (1.20 BDT)
        input.setText("880111222");
        controller.handleScanSubmit();
        assertEquals(1, controller.getCartItems().size());
        assertEquals("TABLET", controller.getCartItems().get(0).getPackagingUnit());
        assertEquals("BDT 1.20", totalLbl.getText());

        // Keyboard '+' increments quantity -> 2 TABLETs (2.40 BDT)
        controller.handleCartTableKeys(new KeyEvent(KeyEvent.KEY_PRESSED, "+", "+", KeyCode.PLUS, false, false, false, false));
        assertEquals(2, controller.getCartItems().get(0).getQuantity());
        assertEquals("BDT 2.40", totalLbl.getText());

        // Cycle unit ('U') from TABLET -> STRIP (20 units requested > 15 avail FEFO -> must BLOCK!)
        controller.handleCartTableKeys(new KeyEvent(KeyEvent.KEY_PRESSED, "u", "u", KeyCode.U, false, false, false, false));
        assertEquals("TABLET", controller.getCartItems().get(0).getPackagingUnit(), "Exceeding FEFO 15u stock with 2 STRIPs (20u) must be blocked");
        assertEquals(UiState.ERROR, controller.getLastUiState());

        // Decrement ('-') to 1 TABLET, then cycle unit ('U') to STRIP (10u <= 15u FEFO -> succeeds, 12.00 BDT!)
        controller.handleCartTableKeys(new KeyEvent(KeyEvent.KEY_PRESSED, "-", "-", KeyCode.MINUS, false, false, false, false));
        assertEquals(1, controller.getCartItems().get(0).getQuantity());
        controller.handleCartTableKeys(new KeyEvent(KeyEvent.KEY_PRESSED, "u", "u", KeyCode.U, false, false, false, false));
        assertEquals("STRIP", controller.getCartItems().get(0).getPackagingUnit());
        assertEquals("BDT 12.00", totalLbl.getText());

        // Expired stock scan must be blocked cleanly and never added to cart
        input.setText("999000");
        controller.handleScanSubmit();
        assertEquals(1, controller.getCartItems().size(), "Expired stock must never enter cart");
        assertEquals(UiState.ERROR, controller.getLastUiState());
        assertTrue(stateLbl.getText().contains("EXPIRED_STOCK_BLOCKED") || stateLbl.getText().contains("Blocked"));

        // Bilingual EN <-> BN toggle updates labels cleanly
        controller.handleToggleLanguage();
        assertTrue(controller.isBangla());
        Button checkoutBtn = getField(controller, "checkoutButton");
        assertTrue(checkoutBtn.getText().contains("চেকআউট"));

        // Delete selected line clears cart back to 0.00
        controller.handleCartTableKeys(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DELETE, false, false, false, false));
        assertEquals(0, controller.getCartItems().size());
        assertEquals("BDT 0.00", totalLbl.getText());
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(PosController target, String name) throws Exception {
        Field f = PosController.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }
}

