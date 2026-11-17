package com.pharmacy.pos.sales;

import com.pharmacy.pos.models.SaleTransaction;
import com.pharmacy.pos.sales.PosSaleDomainClient.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P221 Desktop POS Domain Model Unit Tests.
 */
public class PosSaleDomainTest {

    @Test
    void testCreateAllocateAndBridgeLegacyTransaction() {
        String sampleResponse = """
                {
                  "sale_id": "sale-dt001122",
                  "tenant_id": "ten-a",
                  "branch_id": "br-a",
                  "order_number": "POS-20261109-A1",
                  "idempotency_key": "idem-dt-1",
                  "offline_device_namespace": "DT-POS-01",
                  "offline_local_sequence": 42,
                  "cashier_user_id": "usr-cashier",
                  "customer_name": "Md. Rahim",
                  "customer_phone": "+8801711002233",
                  "lifecycle_state": "DRAFT",
                  "sync_status": "CLOUD_CONFIRMED",
                  "discount_type": "FLAT_BDT",
                  "subtotal_bdt": "50.00",
                  "discount_amount_bdt": "2.50",
                  "tax_amount_bdt": "2.50",
                  "grand_total_bdt": "50.00",
                  "paid_amount_bdt": "0.00",
                  "due_amount_bdt": "0.00",
                  "change_returned_bdt": "0.00",
                  "requires_rx_verification": false,
                  "version": 1,
                  "lines": [
                    {
                      "line_id": "sline-1",
                      "line_number": 1,
                      "product_id": "med-napa",
                      "product_brand_name": "Napa Extra",
                      "product_generic_name": "Paracetamol",
                      "packaging_unit": "STRIP",
                      "quantity_units": 2,
                      "conversion_factor": 10,
                      "base_units_total": 20,
                      "unit_price_bdt": "25.00",
                      "tax_rate_pct": "5.00",
                      "line_subtotal_bdt": "50.00",
                      "line_tax_amount_bdt": "2.50",
                      "line_total_bdt": "52.50",
                      "unit_cost_bdt": null,
                      "requires_rx_verification": false
                    }
                  ],
                  "allocations": [],
                  "payment_attempts": []
                }
                """;

        PosSaleDomainClient client = new PosSaleDomainClient(
                "http://localhost:8000",
                (method, path, body, token) -> {
                    assertEquals("POST", method);
                    assertEquals("/api/v2/pos/sales/", path);
                    assertEquals("tok-cashier", token);
                    return new ApiResponseResult(201, sampleResponse);
                }
        );

        PosSaleLineDto line = new PosSaleLineDto();
        line.productId = "med-napa";
        line.packagingUnit = "STRIP";
        line.quantityUnits = 2;
        line.conversionFactor = 10;
        line.unitPriceBdt = new BigDecimal("25.00");
        line.taxRatePct = new BigDecimal("5.00");

        BigDecimal preview = PosSaleDomainClient.computePreviewGrandTotal(List.of(line), new BigDecimal("2.50"));
        assertEquals(new BigDecimal("50.00"), preview);

        ClientOutcome<PosSaleOrderDto> outcome = client.createSaleOrder(
                "tok-cashier", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "idem-dt-1", List.of(line), "FLAT_BDT", new BigDecimal("2.50"),
                false, false
        );

        assertEquals(UiState.READY, outcome.uiState);
        assertNotNull(outcome.data);
        assertEquals("sale-dt001122", outcome.data.saleId);
        assertEquals(LifecycleState.DRAFT, outcome.data.lifecycleState);
        assertNull(outcome.data.lines.get(0).unitCostBdt); // Redacted for Cashier

        SaleTransaction legacy = outcome.data.bridgeToLegacySaleTransaction();
        assertEquals(new BigDecimal("50.00"), legacy.getGrandTotal());
        assertFalse(legacy.isOfflineSyncPending());
    }

    @Test
    void testLifecycleTransitionsAndGuards() {
        assertTrue(LifecycleState.DRAFT.isMutable());
        assertTrue(LifecycleState.DRAFT.canTransitionTo(LifecycleState.HELD));
        assertTrue(LifecycleState.FINALIZED.canTransitionTo(LifecycleState.REVERSED));
        assertFalse(LifecycleState.FINALIZED.isMutable());
        assertFalse(LifecycleState.REVERSED.canTransitionTo(LifecycleState.FINALIZED));

        PosSaleDomainClient guardClient = new PosSaleDomainClient(
                "http://localhost:8000",
                (method, path, body, token) -> fail("Transport must not be invoked when client guard rejects")
        );

        // Cashier denied reversal
        ClientOutcome<PosSaleOrderDto> cashierRev = guardClient.reverseSale(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-1", "Customer return", false, false
        );
        assertEquals(UiState.PERMISSION_DENIED, cashierRev.uiState);
        assertEquals("ERR_PERMISSION_DENIED", cashierRev.errorCode);

        // Cross-branch denied
        ClientOutcome<PosSaleOrderDto> crossBr = guardClient.reverseSale(
                "tok", "BRANCH_MANAGER", "ten-a", "br-a", "ten-a", "br-other",
                "sale-1", "Return", false, false
        );
        assertEquals(UiState.PERMISSION_DENIED, crossBr.uiState);
        assertEquals("ERR_CROSS_BRANCH_ACCESS_DENIED", crossBr.errorCode);

        // Cross-tenant denied
        ClientOutcome<PosSaleOrderDto> crossTen = guardClient.reverseSale(
                "tok", "PHARMACY_OWNER", "ten-a", "br-a", "ten-b", "br-b",
                "sale-1", "Return", false, false
        );
        assertEquals(UiState.ERROR, crossTen.uiState);
        assertEquals("ERR_CROSS_TENANT_VIOLATION", crossTen.errorCode);

        // Suspended subscription denied
        ClientOutcome<PosSaleOrderDto> susp = guardClient.reverseSale(
                "tok", "PHARMACY_OWNER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-1", "Return", true, false
        );
        assertEquals(UiState.PERMISSION_DENIED, susp.uiState);
        assertEquals("ERR_SUBSCRIPTION_SUSPENDED", susp.errorCode);

        // Offline state
        ClientOutcome<PosSaleOrderDto> offline = guardClient.reverseSale(
                "tok", "PHARMACY_OWNER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-1", "Return", false, true
        );
        assertEquals(UiState.OFFLINE, offline.uiState);
    }

    @Test
    void testEmptyListAndServerHttp403ErrorHandling() {
        PosSaleDomainClient emptyClient = new PosSaleDomainClient(
                "http://localhost:8000",
                (method, path, body, token) -> new ApiResponseResult(200, "{\"results\":[]}")
        );
        ClientOutcome<List<PosSaleOrderDto>> emptyOut = emptyClient.listSales("br-a", "tok", false);
        assertEquals(UiState.EMPTY, emptyOut.uiState);
        assertTrue(emptyOut.data.isEmpty());

        PosSaleDomainClient forbiddenClient = new PosSaleDomainClient(
                "http://localhost:8000",
                (method, path, body, token) -> new ApiResponseResult(403, "{\"error_code\":\"ERR_PERMISSION_DENIED\"}")
        );
        ClientOutcome<PosSaleOrderDto> errOut = forbiddenClient.executeAction(
                "tok", "sale-1", "reverse", Map.of("reason", "test"), "OK", "ঠিক আছে"
        );
        assertEquals(UiState.PERMISSION_DENIED, errOut.uiState);
    }
}

