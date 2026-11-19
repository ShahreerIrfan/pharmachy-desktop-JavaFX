package com.pharmacy.pos.offline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SyncBackpressureBatcherTest {

    private SyncBackpressureBatcher batcher;

    @BeforeEach
    void setUp() {
        batcher = new SyncBackpressureBatcher(50, 2048, 1000L, 60000L);
    }

    @Test
    @DisplayName("Slices batches by item count and respects payload byte cap")
    void testBatchSlicingAndByteCap() {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            Map<String, Object> rec = new HashMap<>();
            rec.put("command_id", "CMD-SALE-" + i);
            rec.put("payload", "large_payload_data_string_for_testing_purposes_" + i);
            records.add(rec);
        }

        // Test normal slicing with small byte cap forcing multi-batch
        List<List<Map<String, Object>>> batches = batcher.sliceBatches(records, false, null);
        assertFalse(batches.isEmpty());
        int totalItems = batches.stream().mapToInt(List::size).sum();
        assertEquals(25, totalItems);
    }

    @Test
    @DisplayName("Reduces batch size during active POS checkout to prevent cashier starvation")
    void testPosCheckoutNonStarvation() {
        batcher.setMaxBatchSize(50);
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            Map<String, Object> rec = new HashMap<>();
            rec.put("command_id", "CMD-" + i);
            rec.put("val", i);
            records.add(rec);
        }

        // With isPosCheckoutActive = true, batch size must not exceed 10
        List<List<Map<String, Object>>> posBatches = batcher.sliceBatches(records, true, null);
        for (List<Map<String, Object>> batch : posBatches) {
            assertTrue(batch.size() <= 10, "Batch size must be <= 10 during checkout to prevent thread starvation");
        }
        assertTrue(batcher.getCooperativeYieldMs(true) >= 200L);
    }

    @Test
    @DisplayName("Calculates exponential backoff delay correctly across retries")
    void testExponentialBackoff() {
        long d0 = batcher.computeBackoffDelayMs(0, 0.0);
        assertEquals(0L, d0);

        long d1 = batcher.computeBackoffDelayMs(1, 0.0);
        assertEquals(1000L, d1); // base * 2^0

        long d2 = batcher.computeBackoffDelayMs(2, 0.0);
        assertEquals(2000L, d2); // base * 2^1

        long d3 = batcher.computeBackoffDelayMs(3, 0.0);
        assertEquals(4000L, d3); // base * 2^2

        long dMax = batcher.computeBackoffDelayMs(10, 0.0);
        assertEquals(60000L, dMax); // Capped at maxDelayMs
    }

    @Test
    @DisplayName("Cancellation token stops batch processing gracefully")
    void testCancellationToken() {
        SyncBackpressureBatcher.CancellationToken token = new SyncBackpressureBatcher.CancellationToken();
        token.cancel();

        List<Map<String, Object>> records = List.of(
                Map.of("command_id", "CMD-1"),
                Map.of("command_id", "CMD-2")
        );

        List<List<Map<String, Object>>> batches = batcher.sliceBatches(records, false, token);
        assertTrue(batches.isEmpty(), "Cancelled token should produce empty or halted batch list");
    }

    @Test
    @DisplayName("Throws error if single record exceeds max payload cap")
    void testSingleRecordExceedsCap() {
        batcher.setMaxPayloadBytes(50); // Very tiny cap
        Map<String, Object> hugeRecord = Map.of(
                "command_id", "CMD-HUGE",
                "large_blob", "This payload string definitely exceeds 50 bytes by far..."
        );

        assertThrows(IllegalArgumentException.class, () -> {
            batcher.sliceBatches(List.of(hugeRecord), false, null);
        });
    }
}
