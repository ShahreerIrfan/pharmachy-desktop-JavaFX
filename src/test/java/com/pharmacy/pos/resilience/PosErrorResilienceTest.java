package com.pharmacy.pos.resilience;

import com.pharmacy.pos.resilience.PosErrorResilienceClient.ApiResponseResult;
import com.pharmacy.pos.resilience.PosErrorResilienceClient.PosIncidentType;
import com.pharmacy.pos.resilience.PosErrorResilienceClient.PosRecoveryActionType;
import com.pharmacy.pos.resilience.PosErrorResilienceClient.StockAlternativeDto;
import com.pharmacy.pos.resilience.PosErrorResilienceClient.UiState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P239: Tests for Desktop JavaFX POS Error Resilience & Recovery Client.
 */
public class PosErrorResilienceTest {

    @Test
    @DisplayName("P239: Report and resolve scanner failure with manual search fallback")
    void testScannerFailureRecoveryWithManualSearchFallback() {
        PosErrorResilienceClient client = new PosErrorResilienceClient("http://localhost:8000", (method, path, body, token) -> {
            if ("POST".equals(method) && path.contains("/report/")) {
                return new ApiResponseResult(201, """
                    {
                        "incident_id": "inc-scan-001",
                        "incident_type": "SCANNER_FAILURE",
                        "error_code": "ERR_POS_SCANNER_FAILURE",
                        "correlation_id": "corr-scan-001",
                        "device_id": "SCANNER-USB-01",
                        "cashier_email": "cashier@test.com",
                        "message_en": "Barcode scanner unreadable. Use [F2] for manual search.",
                        "message_bn": "বারকোড স্ক্যানার পড়া যায়নি। ম্যানুয়াল সার্চের জন্য [F2] চাপুন।",
                        "status": "OPEN",
                        "is_retryable": true
                    }
                """);
            }
            if ("POST".equals(method) && path.contains("/resolve/")) {
                return new ApiResponseResult(200, """
                    {
                        "incident_id": "inc-scan-001",
                        "incident_type": "SCANNER_FAILURE",
                        "error_code": "ERR_POS_SCANNER_FAILURE",
                        "status": "RESOLVED",
                        "recovery_action_taken": "MANUAL_SEARCH_FALLBACK",
                        "is_retryable": true,
                        "resolved_at": "2026-11-17T10:00:00Z"
                    }
                """);
            }
            return new ApiResponseResult(404, "{\"error_code\":\"ERR_NOT_FOUND\"}");
        });

        // 1. Report scanner failure
        var reportOutcome = client.reportIncident(
            PosIncidentType.SCANNER_FAILURE,
            "ERR_POS_SCANNER_FAILURE",
            "SCANNER-USB-01",
            null,
            null,
            null,
            "mock-token"
        );
        assertTrue(reportOutcome.isSuccess());
        assertEquals("inc-scan-001", reportOutcome.data().incidentId());
        assertEquals("OPEN", reportOutcome.data().status());
        assertTrue(reportOutcome.data().isRetryable());
        assertTrue(reportOutcome.messageBn().contains("ম্যানুয়াল সার্চের জন্য [F2] চাপুন"));

        // 2. Resolve via Manual search fallback
        var resolveOutcome = client.resolveIncident(
            "inc-scan-001",
            PosRecoveryActionType.MANUAL_SEARCH_FALLBACK,
            "{\"focused_field\":\"search_sku\"}",
            "mock-token"
        );
        assertTrue(resolveOutcome.isSuccess());
        assertEquals("RESOLVED", resolveOutcome.data().status());
        assertEquals("MANUAL_SEARCH_FALLBACK", resolveOutcome.data().recoveryActionTaken());
    }

    @Test
    @DisplayName("P239: Printer disconnect fallback to format switch and spool")
    void testPrinterDisconnectFormatSwitchRecovery() {
        PosErrorResilienceClient client = new PosErrorResilienceClient("http://localhost:8000", (method, path, body, token) -> {
            if ("POST".equals(method) && path.contains("/resolve/")) {
                return new ApiResponseResult(200, """
                    {
                        "incident_id": "inc-print-001",
                        "incident_type": "PRINTER_DISCONNECTED",
                        "error_code": "ERR_POS_PRINTER_DISCONNECTED",
                        "status": "RESOLVED",
                        "recovery_action_taken": "SWITCH_PRINTER_FORMAT",
                        "is_retryable": true
                    }
                """);
            }
            return new ApiResponseResult(404, "{}");
        });

        var outcome = client.resolveIncident(
            "inc-print-001",
            PosRecoveryActionType.SWITCH_PRINTER_FORMAT,
            "{\"target_format\":\"A4_INVOICE\"}",
            "mock-token"
        );
        assertTrue(outcome.isSuccess());
        assertEquals("SWITCH_PRINTER_FORMAT", outcome.data().recoveryActionTaken());
    }

    @Test
    @DisplayName("P239: Stock conflict discovers non-expired alternative batches")
    void testStockConflictAlternativeBatchDiscovery() {
        PosErrorResilienceClient client = new PosErrorResilienceClient("http://localhost:8000", (method, path, body, token) -> {
            if ("GET".equals(method) && path.contains("/stock-alternatives/")) {
                return new ApiResponseResult(200, """
                    {
                        "product_id": "prod-101",
                        "alternatives": [
                            {
                                "batch_id": "bx-alt-01",
                                "batch_number": "BX-2026-A",
                                "expiry_date": "2027-06-30",
                                "available_units": 45,
                                "cost_unit": null,
                                "is_expired": false,
                                "can_fulfill_requested": true
                            },
                            {
                                "batch_id": "bx-alt-02",
                                "batch_number": "BX-2026-B",
                                "expiry_date": "2027-12-31",
                                "available_units": 100,
                                "cost_unit": null,
                                "is_expired": false,
                                "can_fulfill_requested": true
                            }
                        ]
                    }
                """);
            }
            return new ApiResponseResult(404, "{}");
        });

        var outcome = client.fetchStockAlternatives("prod-101", 10, "mock-token");
        assertTrue(outcome.isSuccess());
        List<StockAlternativeDto> list = outcome.data();
        assertEquals(2, list.size());
        assertEquals("bx-alt-01", list.get(0).batchId());
        assertEquals(45, list.get(0).availableUnits());
        assertTrue(list.get(0).canFulfillRequested());
        assertFalse(list.get(0).isExpired());
    }

    @Test
    @DisplayName("P239: Network timeout triggers offline state")
    void testNetworkTimeoutOfflineResilience() {
        PosErrorResilienceClient client = new PosErrorResilienceClient("http://localhost:8000", (method, path, body, token) -> {
            throw new IOException("Connection timed out after 5000ms");
        });

        var outcome = client.reportIncident(
            PosIncidentType.NETWORK_INTERRUPTED,
            "ERR_POS_NETWORK_INTERRUPTED",
            "POS-REG-01",
            "sale-001",
            null,
            null,
            "mock-token"
        );
        assertEquals(UiState.OFFLINE, outcome.uiState());
        assertEquals("ERR_NETWORK_UNREACHABLE", outcome.errorCode());
        assertTrue(outcome.messageBn().contains("লোকাল কিউতে জমা করা হয়েছে"));
    }
}
