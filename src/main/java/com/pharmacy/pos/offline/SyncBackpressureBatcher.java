package com.pharmacy.pos.offline;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SyncBackpressureBatcher
 * Architecture Decision: ADR-0317 (P317).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Implements client-side sync backpressure, payload size caps, adaptive batching,
 * exponential retry backoff with jitter, cancellation tokens, and POS non-starvation guards.
 */
public class SyncBackpressureBatcher {

    public static final int DEFAULT_MAX_BATCH_SIZE = 50;
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 512 * 1024; // 512 KB
    public static final long DEFAULT_BASE_DELAY_MS = 1000L;
    public static final long DEFAULT_MAX_DELAY_MS = 60000L;
    public static final int POS_CHECKOUT_CONSERVATIVE_BATCH_SIZE = 10;
    public static final long POS_CHECKOUT_COOPERATIVE_YIELD_MS = 250L;

    private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
    private int maxPayloadBytes = DEFAULT_MAX_PAYLOAD_BYTES;
    private long baseDelayMs = DEFAULT_BASE_DELAY_MS;
    private long maxDelayMs = DEFAULT_MAX_DELAY_MS;
    private final Random random = new Random();

    public SyncBackpressureBatcher() {}

    public SyncBackpressureBatcher(int maxBatchSize, int maxPayloadBytes, long baseDelayMs, long maxDelayMs) {
        if (maxBatchSize > 0) this.maxBatchSize = maxBatchSize;
        if (maxPayloadBytes > 0) this.maxPayloadBytes = maxPayloadBytes;
        if (baseDelayMs > 0) this.baseDelayMs = baseDelayMs;
        if (maxDelayMs > 0) this.maxDelayMs = maxDelayMs;
    }

    /**
     * Slices outbox records taking into account BOTH item count and payload byte size caps.
     * Prevents oversized payload exceptions and respects POS checkout priority.
     */
    public List<List<Map<String, Object>>> sliceBatches(
            List<Map<String, Object>> records,
            boolean isPosCheckoutActive,
            CancellationToken cancellationToken
    ) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }

        int effectiveMaxItems = isPosCheckoutActive
                ? Math.min(POS_CHECKOUT_CONSERVATIVE_BATCH_SIZE, Math.max(1, this.maxBatchSize / 4))
                : this.maxBatchSize;

        List<List<Map<String, Object>>> batches = new ArrayList<>();
        List<Map<String, Object>> currentBatch = new ArrayList<>();
        int currentBatchBytes = 0;

        for (Map<String, Object> record : records) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                break; // Stop slicing gracefully
            }

            int recordBytes = estimateRecordBytes(record);
            if (recordBytes > this.maxPayloadBytes) {
                throw new IllegalArgumentException("ERR_SINGLE_RECORD_EXCEEDS_PAYLOAD_CAP: Record size " +
                        recordBytes + " bytes exceeds max allowed " + this.maxPayloadBytes + " bytes.");
            }

            // If adding this record exceeds count or byte size cap, seal current batch
            if (currentBatch.size() >= effectiveMaxItems ||
                    (currentBatchBytes + recordBytes > this.maxPayloadBytes && !currentBatch.isEmpty())) {
                batches.add(new ArrayList<>(currentBatch));
                currentBatch.clear();
                currentBatchBytes = 0;
            }

            currentBatch.add(record);
            currentBatchBytes += recordBytes;
        }

        if (!currentBatch.isEmpty() && (cancellationToken == null || !cancellationToken.isCancelled())) {
            batches.add(currentBatch);
        }

        return batches;
    }

    /**
     * Estimates JSON payload byte length of an outbox record map.
     */
    public int estimateRecordBytes(Map<String, Object> record) {
        if (record == null) return 0;
        // Simple deterministic byte estimation
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append(";");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Computes exponential retry backoff with pseudo-random jitter.
     * backoff = min(maxDelay, baseDelay * 2^(failures - 1)) + jitter
     */
    public long computeBackoffDelayMs(int consecutiveFailures, double jitterFactor) {
        if (consecutiveFailures <= 0) {
            return 0L;
        }
        long factor = 1L << Math.min(20, consecutiveFailures - 1);
        long rawBackoff = Math.min(this.maxDelayMs, this.baseDelayMs * factor);

        long jitterRange = (long) (rawBackoff * Math.max(0.0, Math.min(1.0, jitterFactor)));
        long jitter = jitterRange > 0 ? (long) (random.nextDouble() * jitterRange) : 0L;

        return Math.min(this.maxDelayMs * 2, rawBackoff + jitter);
    }

    /**
     * Cooperative yield time recommendation to prevent POS cashier UI starvation.
     */
    public long getCooperativeYieldMs(boolean isPosCheckoutActive) {
        return isPosCheckoutActive ? POS_CHECKOUT_COOPERATIVE_YIELD_MS : 0L;
    }

    // Getters and Setters
    public int getMaxBatchSize() { return maxBatchSize; }
    public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }
    public int getMaxPayloadBytes() { return maxPayloadBytes; }
    public void setMaxPayloadBytes(int maxPayloadBytes) { this.maxPayloadBytes = maxPayloadBytes; }
    public long getBaseDelayMs() { return baseDelayMs; }
    public void setBaseDelayMs(long baseDelayMs) { this.baseDelayMs = baseDelayMs; }
    public long getMaxDelayMs() { return maxDelayMs; }
    public void setMaxDelayMs(long maxDelayMs) { this.maxDelayMs = maxDelayMs; }

    /**
     * Cooperative thread cancellation token.
     */
    public static class CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void reset() {
            cancelled.set(false);
        }
    }

    /**
     * Outbox sync progress indicator model.
     */
    public static class OutboxProgress {
        private final int totalEnqueued;
        private final int processedCount;
        private final int failedCount;
        private final boolean isThrottled;
        private final int retryAfterSeconds;

        public OutboxProgress(int totalEnqueued, int processedCount, int failedCount, boolean isThrottled, int retryAfterSeconds) {
            this.totalEnqueued = totalEnqueued;
            this.processedCount = processedCount;
            this.failedCount = failedCount;
            this.isThrottled = isThrottled;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int getTotalEnqueued() { return totalEnqueued; }
        public int getProcessedCount() { return processedCount; }
        public int getFailedCount() { return failedCount; }
        public int getRemainingCount() { return Math.max(0, totalEnqueued - processedCount); }
        public boolean isThrottled() { return isThrottled; }
        public int getRetryAfterSeconds() { return retryAfterSeconds; }

        public double getProgressPercentage() {
            if (totalEnqueued <= 0) return 100.0;
            return Math.round(((double) processedCount / totalEnqueued) * 10000.0) / 100.0;
        }
    }
}
