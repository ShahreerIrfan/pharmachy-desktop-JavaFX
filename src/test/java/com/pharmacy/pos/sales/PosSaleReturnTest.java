package com.pharmacy.pos.sales;

import com.pharmacy.pos.sales.PosSaleDomainClient.ApiResponseResult;
import com.pharmacy.pos.sales.PosSaleDomainClient.ClientOutcome;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosSaleReturnDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.UiState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PosSaleReturnTest {

    @Test
    void testProcessReturnWithRestockableDisposition() {
        PosSaleDomainClient client = new PosSaleDomainClient("http://localhost:8000", (method, path, body, token) -> {
            if ("POST".equals(method) && path.contains("/api/v2/pos/returns/")) {
                return new ApiResponseResult(201, """
                        {
                          "return_id": "ret-501",
                          "return_number": "RET-DHK01-20261116-0001",
                          "original_sale_id": "sale-101",
                          "original_order_number": "ORD-DHK01-0001",
                          "shift_id": "shf-101",
                          "status": "COMPLETED",
                          "refund_method": "CASH",
                          "total_refund_amount_bdt": "25.00",
                          "reason": "Customer bought wrong medicine variant",
                          "customer_name": "Rahim Ahmed",
                          "customer_phone": "01711112233",
                          "created_at": "2026-11-16T15:00:00Z",
                          "lines": [
                            {
                              "return_line_id": "rtl-001",
                              "sale_line_id": "line-001",
                              "product_id": "prod-para",
                              "product_brand_name": "Napa Extra",
                              "batch_number": "B-2026-N1",
                              "packaging_unit": "TABLET",
                              "quantity_units": 10,
                              "refund_amount_bdt": "25.00",
                              "safety_disposition": "RESTOCKABLE",
                              "disposition_notes": "Packaging intact, seal verified"
                            }
                          ]
                        }
                        """);
            }
            return new ApiResponseResult(404, "{\"error_code\":\"ERR_NOT_FOUND\"}");
        });

        Map<String, Object> returnItem = new LinkedHashMap<>();
        returnItem.put("sale_line_id", "line-001");
        returnItem.put("quantity_units", 10);
        returnItem.put("safety_disposition", "RESTOCKABLE");
        returnItem.put("disposition_notes", "Packaging intact, seal verified");

        ClientOutcome<PosSaleReturnDto> outcome = client.processReturn(
                "sale-101",
                List.of(returnItem),
                "CASH",
                "Customer bought wrong medicine variant",
                "Rahim Ahmed",
                "01711112233",
                null
        );

        assertEquals(UiState.READY, outcome.uiState);
        assertNotNull(outcome.data);
        assertEquals("ret-501", outcome.data.returnId);
        assertEquals("RET-DHK01-20261116-0001", outcome.data.returnNumber);
        assertEquals("COMPLETED", outcome.data.status);
        assertEquals(new BigDecimal("25.00"), outcome.data.totalRefundAmountBdt);
        assertEquals(1, outcome.data.lines.size());
        assertEquals("RESTOCKABLE", outcome.data.lines.get(0).safetyDisposition);
        assertEquals("B-2026-N1", outcome.data.lines.get(0).batchNumber);
    }
}
