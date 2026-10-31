package com.pharmacy.pos.security;

import com.pharmacy.pos.models.PosAsyncIdempotencyHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0057: POS Async Task Idempotency & Retry Tests")
class PosAsyncIdempotencyHandlerTest {

    @Test
    @DisplayName("Should generate structured idempotency keys with custom prefix")
    void testGenerateIdempotencyKey() {
        String key1 = PosAsyncIdempotencyHandler.generateIdempotencyKey("invoice-sms");
        String key2 = PosAsyncIdempotencyHandler.generateIdempotencyKey("invoice-sms");

        assertNotNull(key1);
        assertTrue(key1.startsWith("invoice-sms-"));
        assertNotEquals(key1, key2);
    }

    @Test
    @DisplayName("Should create and inspect dispatched async task record")
    void testCreateDispatchedTaskRecord() {
        PosAsyncIdempotencyHandler.PosAsyncTaskRecord record = PosAsyncIdempotencyHandler.createDispatchedTask(
                "job-101",
                "tenant-dhaka",
                "idem-key-888",
                "NOTIFICATION",
                "SEND_CUSTOMER_INVOICE_SMS",
                "SUCCESS",
                false,
                false,
                0,
                3
        );

        assertEquals("job-101", record.jobId());
        assertEquals("tenant-dhaka", record.tenantId());
        assertEquals("idem-key-888", record.idempotencyKey());
        assertEquals("SUCCESS", record.status());
        assertFalse(record.isReplay());
        assertFalse(record.isCached());
        assertTrue(PosAsyncIdempotencyHandler.isExecutionComplete(record.status()));
    }

    @Test
    @DisplayName("Should detect replay response with cached results")
    void testReplayCachedDetection() {
        PosAsyncIdempotencyHandler.PosAsyncTaskRecord cachedRecord = PosAsyncIdempotencyHandler.createDispatchedTask(
                "job-101",
                "tenant-dhaka",
                "idem-key-888",
                "NOTIFICATION",
                "SEND_CUSTOMER_INVOICE_SMS",
                "SUCCESS",
                true,
                true,
                0,
                3
        );

        assertTrue(cachedRecord.isReplay());
        assertTrue(cachedRecord.isCached());
        assertEquals("SUCCESS", cachedRecord.status());
    }

    @Test
    @DisplayName("Should detect dead-lettered state correctly")
    void testDeadLetterDetection() {
        assertTrue(PosAsyncIdempotencyHandler.isDeadLettered("DEAD_LETTER"));
        assertTrue(PosAsyncIdempotencyHandler.isDeadLettered("dead_letter"));
        assertFalse(PosAsyncIdempotencyHandler.isDeadLettered("RETRYING"));
        assertFalse(PosAsyncIdempotencyHandler.isDeadLettered("SUCCESS"));
    }
}
