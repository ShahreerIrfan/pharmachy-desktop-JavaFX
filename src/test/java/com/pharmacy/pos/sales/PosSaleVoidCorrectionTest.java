package com.pharmacy.pos.sales;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P235 - Desktop JavaFX POS Void and Correction Tests")
class PosSaleVoidCorrectionTest {

    private PosSaleDomainClient client;

    @BeforeEach
    void setUp() {
        client = new PosSaleDomainClient("http://127.0.0.1:8000", (method, path, body, token) -> {
            return new PosSaleDomainClient.ApiResponseResult(200, "{\"sale_id\":\"sale-001\",\"lifecycle_state\":\"VOIDED\"}");
        });
    }

    @Test
    @DisplayName("voidSale rejects execution when documented reason is blank")
    void testVoidSaleRejectsEmptyReason() {
        PosSaleDomainClient.ClientOutcome<PosSaleDomainClient.PosSaleOrderDto> outcome = client.voidSale(
                "mock-token",
                "CASHIER",
                "ten-001",
                "br-001",
                "ten-001",
                "br-001",
                "sale-001",
                "   ",
                false,
                false
        );

        assertEquals(PosSaleDomainClient.UiState.ERROR, outcome.uiState);
        assertEquals("ERR_REASON_REQUIRED", outcome.errorCode);
        assertNotNull(outcome.messageBn);
    }

    @Test
    @DisplayName("correctSale rejects execution when documented reason is missing")
    void testCorrectSaleRejectsEmptyReason() {
        PosSaleDomainClient.ClientOutcome<PosSaleDomainClient.PosSaleOrderDto> outcome = client.correctSale(
                "mock-token",
                "CASHIER",
                "ten-001",
                "br-001",
                "ten-001",
                "br-001",
                "sale-001",
                "",
                "CART_CORRECTION",
                "line-001",
                2,
                false,
                false
        );

        assertEquals(PosSaleDomainClient.UiState.ERROR, outcome.uiState);
        assertEquals("ERR_REASON_REQUIRED", outcome.errorCode);
    }

    @Test
    @DisplayName("voidSale rejects execution when user belongs to different tenant")
    void testVoidSaleRejectsCrossTenant() {
        PosSaleDomainClient.ClientOutcome<PosSaleDomainClient.PosSaleOrderDto> outcome = client.voidSale(
                "mock-token",
                "CASHIER",
                "ten-attacker",
                "br-001",
                "ten-victim",
                "br-001",
                "sale-001",
                "Valid reason",
                false,
                false
        );

        assertEquals(PosSaleDomainClient.UiState.ERROR, outcome.uiState);
        assertEquals("ERR_CROSS_TENANT_VIOLATION", outcome.errorCode);
    }
}
