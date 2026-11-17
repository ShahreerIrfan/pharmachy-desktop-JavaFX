package com.pharmacy.pos.sales;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P236 - Desktop JavaFX POS Discount Approval and Offline Boundaries Tests")
class PosSaleDiscountApprovalTest {

    private PosSaleDomainClient client;

    @BeforeEach
    void setUp() {
        client = new PosSaleDomainClient("http://127.0.0.1:8000", (method, path, body, token) -> {
            return new PosSaleDomainClient.ApiResponseResult(200, "{\"sale_id\":\"sale-001\",\"discount_rate_pct\":\"15.00\"}");
        });
    }

    @Test
    @DisplayName("approveDiscount rejects when reason is less than 3 characters")
    void testApproveDiscountRejectsShortReason() {
        PosSaleDomainClient.ClientOutcome<PosSaleDomainClient.PosSaleOrderDto> outcome = client.approveDiscount(
                "mock-token",
                "BRANCH_MANAGER",
                "ten-001",
                "br-001",
                "ten-001",
                "br-001",
                "sale-001",
                "ok",
                "10.00",
                null,
                Collections.emptyList(),
                false,
                false
        );

        assertEquals(PosSaleDomainClient.UiState.ERROR, outcome.uiState);
        assertEquals("ERR_REASON_REQUIRED", outcome.errorCode);
        assertNotNull(outcome.messageBn);
    }

    @Test
    @DisplayName("approveDiscount enforces offline boundary ceiling (maximum 20% offline)")
    void testApproveDiscountEnforcesOfflineCeiling() {
        PosSaleDomainClient.ClientOutcome<PosSaleDomainClient.PosSaleOrderDto> outcome = client.approveDiscount(
                "mock-token",
                "BRANCH_MANAGER",
                "ten-001",
                "br-001",
                "ten-001",
                "br-001",
                "sale-001",
                "Special holiday clearance discount",
                "25.00",
                null,
                Collections.emptyList(),
                true,  // isOffline
                false
        );

        assertEquals(PosSaleDomainClient.UiState.ERROR, outcome.uiState);
        assertEquals("ERR_OFFLINE_DISCOUNT_CEILING_EXCEEDED", outcome.errorCode);
    }

    @Test
    @DisplayName("approveDiscount succeeds for manager with valid 15% discount")
    void testApproveDiscountSucceedsForManager() {
        PosSaleDomainClient.ClientOutcome<PosSaleDomainClient.PosSaleOrderDto> outcome = client.approveDiscount(
                "mock-token",
                "BRANCH_MANAGER",
                "ten-001",
                "br-001",
                "ten-001",
                "br-001",
                "sale-001",
                "Doctor privilege discount",
                "15.00",
                null,
                Collections.emptyList(),
                false,
                false
        );

        assertEquals(PosSaleDomainClient.UiState.READY, outcome.uiState);
        assertNotNull(outcome.data);
    }
}
