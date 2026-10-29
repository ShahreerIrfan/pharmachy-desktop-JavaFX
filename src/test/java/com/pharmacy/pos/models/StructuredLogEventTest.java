package com.pharmacy.pos.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JavaFX POS Structured Log Event & Correlation Header Tests (ADR-0035)")
class StructuredLogEventTest {

    @Test
    @DisplayName("Valid event produces compliant structured context and headers")
    void testValidLogEventProducesCorrelationHeaders() {
        StructuredLogEvent event = new StructuredLogEvent(
                "INFO",
                "com.pharmacy.pos.services.OfflineSyncEngine",
                "Transaction synced successfully",
                "req-pos-12345",
                "tenant-dhaka-central",
                "pos-term-01",
                "op-dispense-99",
                "usr-cashier-01"
        );

        assertNotNull(event.getTimestamp());
        assertEquals("INFO", event.getLevel());
        assertEquals("pharmacy-pos-desktop", event.getService());

        Map<String, Object> ctx = event.getContext();
        assertEquals("req-pos-12345", ctx.get("request_id"));
        assertEquals("tenant-dhaka-central", ctx.get("tenant_id"));
        assertEquals("pos-term-01", ctx.get("device_id"));
        assertEquals("op-dispense-99", ctx.get("operation_id"));

        Map<String, String> headers = event.toCorrelationHeaders();
        assertEquals("req-pos-12345", headers.get("X-Request-ID"));
        assertEquals("tenant-dhaka-central", headers.get("X-Tenant-ID"));
        assertEquals("pos-term-01", headers.get("X-Device-ID"));
        assertEquals("op-dispense-99", headers.get("X-Operation-ID"));
    }

    @Test
    @DisplayName("Null requestId is strictly rejected")
    void testNullRequestIdRejected() {
        assertThrows(NullPointerException.class, () -> new StructuredLogEvent(
                "INFO",
                "com.pharmacy.pos.MainApp",
                "Sample test message",
                null,
                "tenant-dhaka-central",
                "pos-term-01",
                "op-test",
                "usr-01"
        ));
    }
}

