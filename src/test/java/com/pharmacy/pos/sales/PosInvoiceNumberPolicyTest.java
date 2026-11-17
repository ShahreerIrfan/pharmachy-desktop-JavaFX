package com.pharmacy.pos.sales;

import com.pharmacy.pos.sales.PosSaleDomainClient.ApiResponseResult;
import com.pharmacy.pos.sales.PosSaleDomainClient.ClientOutcome;
import com.pharmacy.pos.sales.PosSaleDomainClient.NextInvoiceNumberPreviewDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.UiState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PosInvoiceNumberPolicyTest {

    @Test
    void invoiceNumberPolicyPreviewAndSequenceParity() {
        PosSaleDomainClient client = new PosSaleDomainClient("http://localhost:8000", (method, path, body, token) -> {
            if ("GET".equals(method) && path.contains("/api/v2/pos/invoice-policies/preview-next/")) {
                return new ApiResponseResult(200, """
                        {
                          "invoice_number": "INV-DHK01-REG01-20261116-0001",
                          "policy_id": "pol-dhk-01",
                          "sequence": 1,
                          "period_key": "20261116",
                          "branch_code": "DHK01",
                          "device_code": "REG01",
                          "is_reserved": false
                        }
                        """);
            }
            return new ApiResponseResult(404, "{\"error_code\":\"ERR_NOT_FOUND\"}");
        });

        ClientOutcome<NextInvoiceNumberPreviewDto> outcome = client.previewNextInvoiceNumber("br-dhk", "REG01");
        assertEquals(UiState.READY, outcome.uiState);
        assertNotNull(outcome.data);
        assertEquals("INV-DHK01-REG01-20261116-0001", outcome.data.invoiceNumber);
        assertEquals(1L, outcome.data.sequence);
        assertEquals("DHK01", outcome.data.branchCode);
        assertEquals("REG01", outcome.data.deviceCode);
        assertFalse(outcome.data.isReserved);
    }
}
