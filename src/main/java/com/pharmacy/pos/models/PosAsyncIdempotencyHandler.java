package com.pharmacy.pos.models;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Architecture Decision: ADR-0057
 * POS Client implementation of Async Task Idempotency and Retry Controls:
 * - Deterministic / UUID idempotency key generation
 * - Replay interception handling (detects cached responses without re-executing)
 * - Dead-letter queue detection
 */
public final class PosAsyncIdempotencyHandler {

    public static final int DEFAULT_MAX_RETRIES = 3;

    private PosAsyncIdempotencyHandler() {
        // utility class
    }

    public static String generateIdempotencyKey(String prefix) {
        String p = prefix != null && !prefix.trim().isEmpty() ? prefix.trim() : "task";
        return p + "-" + UUID.randomUUID().toString().substring(0, 18);
    }

    public static PosAsyncTaskRecord createDispatchedTask(
            String jobId,
            String tenantId,
            String idempotencyKey,
            String category,
            String taskName,
            String status,
            boolean isReplay,
            boolean isCached,
            int retryCount,
            int maxRetries
    ) {
        Objects.requireNonNull(jobId, "jobId cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");

        return new PosAsyncTaskRecord(
                jobId,
                tenantId,
                idempotencyKey,
                category != null ? category : "NOTIFICATION",
                taskName != null ? taskName : "DEFAULT_TASK",
                status != null ? status : "PENDING",
                isReplay,
                isCached,
                retryCount,
                maxRetries,
                Instant.now()
        );
    }

    public static boolean isDeadLettered(String status) {
        return "DEAD_LETTER".equalsIgnoreCase(status);
    }

    public static boolean isExecutionComplete(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || isDeadLettered(status);
    }

    public static record PosAsyncTaskRecord(
            String jobId,
            String tenantId,
            String idempotencyKey,
            String category,
            String taskName,
            String status,
            boolean isReplay,
            boolean isCached,
            int retryCount,
            int maxRetries,
            Instant timestamp
    ) {}
}
