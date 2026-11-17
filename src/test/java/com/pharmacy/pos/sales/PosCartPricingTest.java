package com.pharmacy.pos.sales;

import com.pharmacy.pos.controllers.PosController;
import com.pharmacy.pos.models.CartItem;
import com.pharmacy.pos.models.TenantSession;
import com.pharmacy.pos.sales.PosSaleDomainClient.ApiResponseResult;
import com.pharmacy.pos.sales.PosSaleDomainClient.ClientOutcome;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosSaleLineDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosSaleOrderDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.UiState;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Label;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P224 Cart Prices and Line Totals Desktop Tests.
 * Architecture Decision: ADR-0224 (P224).
 */
public class PosCartPricingTest {

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
    @DisplayName("1. Client parses P224 line/header pricing, calls update-pricing & confirm-pricing, maps 403 & 400 mismatch")
    void testClientUpdateAndConfirmPricingOutcomes() {
        AtomicInteger step = new AtomicInteger(0);
        PosSaleDomainClient client = new PosSaleDomainClient("http://localhost:8000", (m, path, body, tok) -> {
            int idx = step.getAndIncrement();
            if (idx == 0) {
                assertEquals("POST", m);
                assertTrue(path.contains("/api/v2/pos/sales/sale-p224/update-pricing/"));
                assertTrue(body.contains("NEAREST_FIVE_BDT"));
                return new ApiResponseResult(200, """
                        {
                          "pricing_breakdown": {
                            "subtotal_bdt": "140.00",
                            "line_discount_total_bdt": "4.00",
                            "header_discount_amount_bdt": "10.00",
                            "discount_amount_bdt": "14.00",
                            "net_before_tax_bdt": "120.00",
                            "tax_mode": "INCLUSIVE",
                            "tax_rate_pct": "5.00",
                            "tax_amount_bdt": "6.00",
                            "rounding_mode": "NEAREST_FIVE_BDT",
                            "rounding_adjustment_bdt": "-1.00",
                            "grand_total_bdt": "125.00",
                            "calculated_pricing_hash": "hash-abc-123"
                          },
                          "sale": {
                            "sale_id": "sale-p224",
                            "tenant_id": "ten-a",
                            "branch_id": "br-a",
                            "order_number": "POS-224",
                            "lifecycle_state": "DRAFT",
                            "subtotal_bdt": "140.00",
                            "line_discount_total_bdt": "4.00",
                            "header_discount_amount_bdt": "10.00",
                            "discount_amount_bdt": "14.00",
                            "discount_type": "FLAT_BDT",
                            "discount_rate_pct": "0.00",
                            "net_before_tax_bdt": "120.00",
                            "tax_mode": "INCLUSIVE",
                            "tax_rate_pct": "5.00",
                            "tax_amount_bdt": "6.00",
                            "rounding_mode": "NEAREST_FIVE_BDT",
                            "rounding_adjustment_bdt": "-1.00",
                            "grand_total_bdt": "125.00",
                            "is_pricing_confirmed": false,
                            "current_pricing_hash": "hash-abc-123",
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
                                "unit_price_bdt": "20.00",
                                "price_source": "CANONICAL_MRP",
                                "discount_type": "PERCENTAGE",
                                "discount_rate_pct": "10.00",
                                "discount_amount_bdt": "4.00",
                                "tax_mode": "INCLUSIVE",
                                "tax_rate_pct": "5.00",
                                "net_before_tax_bdt": "34.29",
                                "line_subtotal_bdt": "40.00",
                                "line_tax_amount_bdt": "1.71",
                                "line_total_bdt": "36.00"
                              }
                            ]
                          }
                        }
                        """);
            }
            if (idx == 1) {
                assertEquals("POST", m);
                assertTrue(path.contains("/api/v2/pos/sales/sale-p224/confirm-pricing/"));
                assertTrue(body.contains("125.00"));
                assertTrue(body.contains("hash-abc-123"));
                return new ApiResponseResult(200, """
                        {
                          "pricing_confirmation": {
                            "pricing_confirmation_hash": "hash-abc-123",
                            "is_pricing_confirmed": true
                          },
                          "sale": {
                            "sale_id": "sale-p224",
                            "tenant_id": "ten-a",
                            "branch_id": "br-a",
                            "order_number": "POS-224",
                            "lifecycle_state": "DRAFT",
                            "subtotal_bdt": "140.00",
                            "line_discount_total_bdt": "4.00",
                            "header_discount_amount_bdt": "10.00",
                            "discount_amount_bdt": "14.00",
                            "net_before_tax_bdt": "120.00",
                            "tax_mode": "INCLUSIVE",
                            "tax_rate_pct": "5.00",
                            "tax_amount_bdt": "6.00",
                            "rounding_mode": "NEAREST_FIVE_BDT",
                            "rounding_adjustment_bdt": "-1.00",
                            "grand_total_bdt": "125.00",
                            "is_pricing_confirmed": true,
                            "pricing_confirmation_hash": "hash-abc-123",
                            "current_pricing_hash": "hash-abc-123",
                            "pricing_confirmed_at": "2026-11-09T10:30:00Z",
                            "version": 4,
                            "lines": []
                          }
                        }
                        """);
            }
            if (idx == 2) {
                return new ApiResponseResult(403, """
                        {
                          "error_code": "ERR_CASHIER_DISCOUNT_LIMIT_EXCEEDED",
                          "message_en": "Cashiers cannot apply discounts and negative rounding exceeding 5%.",
                          "message_bn": "ক্যাশিয়ার ৫%-এর বেশি ছাড় দিতে পারবেন না।"
                        }
                        """);
            }
            return new ApiResponseResult(400, """
                    {
                      "error_code": "ERR_PRICE_CONFIRMATION_MISMATCH",
                      "message_en": "Cart prices changed since your preview; refresh pricing breakdown before checkout.",
                      "message_bn": "কার্টের মূল্য পরিবর্তিত হয়েছে; অনুগ্রহ করে নতুন করে মূল্য নিশ্চিত করুন।"
                    }
                    """);
        });

        ClientOutcome<PosSaleOrderDto> upd = client.updateCartPricing(
                "tok-mgr", "BRANCH_MANAGER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-p224",
                Map.of("discount_type", "FLAT_BDT", "header_discount_amount_bdt", "10.00", "rounding_mode", "NEAREST_FIVE_BDT"),
                false, false
        );
        assertEquals(UiState.READY, upd.uiState);
        assertNotNull(upd.data);
        assertEquals(new BigDecimal("140.00"), upd.data.subtotalBdt);
        assertEquals(new BigDecimal("4.00"), upd.data.lineDiscountTotalBdt);
        assertEquals(new BigDecimal("10.00"), upd.data.headerDiscountAmountBdt);
        assertEquals(new BigDecimal("14.00"), upd.data.discountAmountBdt);
        assertEquals(new BigDecimal("120.00"), upd.data.netBeforeTaxBdt);
        assertEquals("INCLUSIVE", upd.data.taxMode);
        assertEquals("NEAREST_FIVE_BDT", upd.data.roundingMode);
        assertEquals(new BigDecimal("-1.00"), upd.data.roundingAdjustmentBdt);
        assertEquals(new BigDecimal("125.00"), upd.data.grandTotalBdt);
        assertFalse(upd.data.isPricingConfirmed);
        assertEquals("hash-abc-123", upd.data.currentPricingHash);

        PosSaleLineDto firstLine = upd.data.lines.get(0);
        assertEquals("CANONICAL_MRP", firstLine.priceSource);
        assertEquals("PERCENTAGE", firstLine.discountType);
        assertEquals(new BigDecimal("4.00"), firstLine.discountAmountBdt);
        assertEquals(new BigDecimal("34.29"), firstLine.netBeforeTaxBdt);
        assertEquals(new BigDecimal("36.00"), firstLine.lineTotalBdt);

        ClientOutcome<PosSaleOrderDto> confirmed = client.confirmCartPricing(
                "tok-mgr", "BRANCH_MANAGER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-p224", upd.data.grandTotalBdt, upd.data.currentPricingHash, upd.data.version,
                false, false
        );
        assertEquals(UiState.READY, confirmed.uiState);
        assertTrue(confirmed.data.isPricingConfirmed);
        assertEquals("hash-abc-123", confirmed.data.pricingConfirmationHash);

        ClientOutcome<PosSaleOrderDto> denied = client.updateCartPricing(
                "tok-cashier", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-p224",
                Map.of("discount_type", "PERCENTAGE", "discount_rate_pct", "15.00"),
                false, false
        );
        assertEquals(UiState.PERMISSION_DENIED, denied.uiState);
        assertEquals("ERR_CASHIER_DISCOUNT_LIMIT_EXCEEDED", denied.errorCode);
        assertTrue(denied.messageBn.contains("৫%"));

        ClientOutcome<PosSaleOrderDto> mismatch = client.confirmCartPricing(
                "tok-cashier", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-p224", new BigDecimal("99.00"), "stale-hash", 4,
                false, false
        );
        assertEquals(UiState.ERROR, mismatch.uiState);
        assertEquals("ERR_PRICE_CONFIRMATION_MISMATCH", mismatch.errorCode);
    }

    @Test
    @DisplayName("2. PosController renders Decimal discount/rounding, enforces Cashier 5% local guard, handles offline & EN/BN")
    void testPosControllerPricingControlsAndGuards() throws Exception {
        URL fxmlUrl = getClass().getResource("/com/pharmacy/pos/views/main.fxml");
        assertNotNull(fxmlUrl);
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();
        assertNotNull(root);

        PosController controller = loader.getController();
        controller.setAsyncNetworkCalls(false);
        controller.setSessionDetails(
                new TenantSession("ten-a", "br-a", "", "cashier-01", "CASHIER", true),
                "Tariq",
                "Day",
                "500.00"
        );

        controller.addItem(new CartItem(
                "prod-napa", "Napa Extend", "Paracetamol", "STRIP",
                2, new BigDecimal("63.70"), "LOT-N1", LocalDate.now().plusYears(1), BigDecimal.ZERO
        ));
        // Subtotal = 127.40
        assertEquals("BDT 127.40", getLabelText(controller, "subtotalLabel"));
        assertEquals("BDT 127.40", getLabelText(controller, "grandTotalLabel"));

        // Cashier trying 10% local discount -> PERMISSION_DENIED
        controller.applyPricingParameters("PERCENTAGE", new BigDecimal("10.00"), "NONE");
        assertEquals(UiState.PERMISSION_DENIED, controller.getLastUiState());
        assertEquals("BDT 127.40", getLabelText(controller, "grandTotalLabel"));

        // Cashier applying 2.75% discount + HALF_UP_WHOLE_BDT -> 127.40 - 3.50 = 123.90 -> rounded 124.00 (+0.10)
        controller.applyPricingParameters("PERCENTAGE", new BigDecimal("2.75"), "HALF_UP_WHOLE_BDT");
        assertEquals(UiState.READY, controller.getLastUiState());
        assertEquals("BDT 3.50", getLabelText(controller, "discountLabel"));
        assertEquals("BDT 0.10", getLabelText(controller, "roundingLabel"));
        assertEquals("BDT 124.00", getLabelText(controller, "grandTotalLabel"));

        // Confirm pricing without cloud authority -> explicit OFFLINE state banner
        controller.handleConfirmPricing();
        assertEquals(UiState.OFFLINE, controller.getLastUiState());

        // Toggle EN -> BN updates payment summary labels cleanly
        controller.handleToggleLanguage();
        assertEquals("মূল্য নিশ্চিত করুন", getButtonText(controller, "btnConfirmPricing"));
        assertEquals("রাউন্ডিং সমন্বয়:", getLabelText(controller, "roundingTitleLabel"));
    }

    private String getLabelText(PosController controller, String fieldName) throws Exception {
        Field f = PosController.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        Label lbl = (Label) f.get(controller);
        return lbl != null ? lbl.getText() : "";
    }

    private String getButtonText(PosController controller, String fieldName) throws Exception {
        Field f = PosController.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        javafx.scene.control.Button btn = (javafx.scene.control.Button) f.get(controller);
        return btn != null ? btn.getText() : "";
    }
}

