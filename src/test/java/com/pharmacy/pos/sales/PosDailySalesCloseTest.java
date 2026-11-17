package com.pharmacy.pos.sales;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P237 - Desktop JavaFX POS Daily Sales Close Tests")
class PosDailySalesCloseTest {

    private PosSaleDomainClient client;

    @BeforeEach
    void setUp() {
        client = new PosSaleDomainClient("http://127.0.0.1:8000", (method, path, body, token) -> {
            if (path.contains("/submit/")) {
                return new PosSaleDomainClient.ApiResponseResult(201, """
                    {
                        "close_id": "dcl-001",
                        "close_number": "DCL-BR01-20261116-ABCD",
                        "status": "SUBMITTED",
                        "gross_sales_bdt": "10000.00",
                        "net_sales_bdt": "9500.00",
                        "cash_sales_bdt": "7000.00",
                        "digital_sales_bdt": "2500.00",
                        "due_sales_bdt": "0.00",
                        "expected_drawer_cash_bdt": "8000.00",
                        "actual_drawer_cash_bdt": "8000.00",
                        "cash_variance_bdt": "0.00",
                        "is_ledger_balanced": true
                    }
                    """);
            } else if (path.contains("/approve/")) {
                return new PosSaleDomainClient.ApiResponseResult(200, """
                    {
                        "close_id": "dcl-001",
                        "close_number": "DCL-BR01-20261116-ABCD",
                        "status": "APPROVED",
                        "sha256_hash": "a1b2c3d4e5f67890abcdef1234567890abcdef1234567890abcdef1234567890",
                        "is_ledger_balanced": true
                    }
                    """);
            }
            return new PosSaleDomainClient.ApiResponseResult(404, "{\"detail\":\"Not found\"}");
        });
    }

    @Test
    @DisplayName("submitDailyClose successfully parses submitted close totals and drawer balance")
    void testSubmitDailyClose() {
        PosSaleDomainClient.ClientOutcome<PosSaleDomainClient.PosDailySalesCloseDto> outcome = client.submitDailyClose(
                "2026-11-16",
                new BigDecimal("8000.00"),
                "Cashier EOD physical cash count",
                "mock-token"
        );

        assertEquals(PosSaleDomainClient.UiState.READY, outcome.uiState);
        assertNotNull(outcome.data);
        assertEquals("dcl-001", outcome.data.closeId);
        assertEquals("SUBMITTED", outcome.data.status);
        assertEquals(new BigDecimal("9500.00"), outcome.data.netSalesBdt);
        assertEquals(new BigDecimal("8000.00"), outcome.data.actualDrawerCashBdt);
        assertEquals(new BigDecimal("0.00"), outcome.data.cashVarianceBdt);
        assertTrue(outcome.data.isLedgerBalanced);
    }

    @Test
    @DisplayName("approveDailyClose seals record with immutable SHA-256 hash")
    void testApproveDailyClose() {
        PosSaleDomainClient.ClientOutcome<PosSaleDomainClient.PosDailySalesCloseDto> outcome = client.approveDailyClose(
                "dcl-001",
                "Manager verified physical drawer cash and journal totals",
                "mock-token"
        );

        assertEquals(PosSaleDomainClient.UiState.READY, outcome.uiState);
        assertNotNull(outcome.data);
        assertEquals("APPROVED", outcome.data.status);
        assertFalse(outcome.data.sha256Hash.isBlank());
        assertTrue(outcome.data.isLedgerBalanced);
    }
}
