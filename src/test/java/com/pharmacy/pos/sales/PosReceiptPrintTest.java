package com.pharmacy.pos.sales;

import com.pharmacy.pos.sales.PosSaleDomainClient.ApiResponseResult;
import com.pharmacy.pos.sales.PosSaleDomainClient.ClientOutcome;
import com.pharmacy.pos.sales.PosSaleDomainClient.ReceiptPrintResponseDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.UiState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PosReceiptPrintTest {

    @Test
    void testOriginalReceiptPreviewAndPrint() {
        PosSaleDomainClient client = new PosSaleDomainClient("http://localhost:8000", (method, path, body, token) -> {
            if ("GET".equals(method) && path.contains("/receipt-preview/")) {
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-100",
                          "invoice_number": "INV-DHK-202611-0001",
                          "print_format": "THERMAL_80MM",
                          "reprint_count": 0,
                          "is_duplicate": false,
                          "watermark_text": "",
                          "raw_receipt_text": "=== PHARMACY RECEIPT ===\\nInvoice: INV-DHK-202611-0001"
                        }
                        """);
            }
            if ("POST".equals(method) && path.contains("/print-receipt/")) {
                return new ApiResponseResult(201, """
                        {
                          "print_id": "pr-100",
                          "sale_id": "sale-100",
                          "invoice_number": "INV-DHK-202611-0001",
                          "print_format": "THERMAL_80MM",
                          "reprint_count": 0,
                          "is_duplicate": false,
                          "printer_status": "READY",
                          "watermark_text": "",
                          "raw_receipt_text": "=== PHARMACY RECEIPT ===\\nInvoice: INV-DHK-202611-0001",
                          "printed_at": "2026-11-16T14:30:00Z"
                        }
                        """);
            }
            return new ApiResponseResult(404, "{\"error_code\":\"ERR_NOT_FOUND\"}");
        });

        // 1. Preview
        ClientOutcome<ReceiptPrintResponseDto> preview = client.previewReceipt("sale-100", "THERMAL_80MM");
        assertEquals(UiState.READY, preview.uiState);
        assertNotNull(preview.data);
        assertEquals("sale-100", preview.data.saleId);
        assertFalse(preview.data.isDuplicate);
        assertEquals(0, preview.data.reprintCount);
        assertTrue(preview.data.rawReceiptText.contains("PHARMACY RECEIPT"));

        // 2. Original Print
        ClientOutcome<ReceiptPrintResponseDto> print = client.printReceipt("sale-100", "THERMAL_80MM", "READY", null);
        assertEquals(UiState.READY, print.uiState);
        assertNotNull(print.data);
        assertEquals("pr-100", print.data.printId);
        assertEquals("READY", print.data.printerStatus);
        assertFalse(print.data.isDuplicate);
    }

    @Test
    void testDuplicateReceiptReprintWithWatermark() {
        PosSaleDomainClient client = new PosSaleDomainClient("http://localhost:8000", (method, path, body, token) -> {
            if ("POST".equals(method) && path.contains("/print-receipt/")) {
                if (body.contains("\"reprint_reason\"")) {
                    return new ApiResponseResult(201, """
                            {
                              "print_id": "pr-101",
                              "sale_id": "sale-100",
                              "invoice_number": "INV-DHK-202611-0001",
                              "print_format": "THERMAL_80MM",
                              "reprint_count": 1,
                              "is_duplicate": true,
                              "printer_status": "READY",
                              "reprint_reason": "Customer lost original",
                              "watermark_text": "*** DUPLICATE RECEIPT / পুনর্মুদ্রণ কপি #1 ***",
                              "raw_receipt_text": "*** DUPLICATE RECEIPT ***\\nInvoice: INV-DHK-202611-0001",
                              "printed_at": "2026-11-16T14:35:00Z"
                            }
                            """);
                } else {
                    return new ApiResponseResult(400, "{\"error_code\":\"ERR_VALIDATION_FAILED\",\"reprint_reason\":\"Reprint reason is mandatory when printing a duplicate receipt.\"}");
                }
            }
            return new ApiResponseResult(404, "{\"error_code\":\"ERR_NOT_FOUND\"}");
        });

        // 1. Missing reason fails
        ClientOutcome<ReceiptPrintResponseDto> failOutcome = client.printReceipt("sale-100", "THERMAL_80MM", "READY", "");
        assertEquals(UiState.ERROR, failOutcome.uiState);
        assertEquals("ERR_VALIDATION_FAILED", failOutcome.errorCode);

        // 2. With reason succeeds
        ClientOutcome<ReceiptPrintResponseDto> okOutcome = client.printReceipt("sale-100", "THERMAL_80MM", "READY", "Customer lost original");
        assertEquals(UiState.READY, okOutcome.uiState);
        assertNotNull(okOutcome.data);
        assertEquals(1, okOutcome.data.reprintCount);
        assertTrue(okOutcome.data.isDuplicate);
        assertTrue(okOutcome.data.watermarkText.contains("DUPLICATE RECEIPT"));
        assertEquals("Customer lost original", okOutcome.data.reprintReason);
    }
}

