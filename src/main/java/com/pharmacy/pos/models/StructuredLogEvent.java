package com.pharmacy.pos.models;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Structured log event representation for JavaFX Desktop POS (ADR-0035).
 * Emits correlated JSON log payloads with request, tenant, device and operation IDs.
 */
public class StructuredLogEvent {
    private final String timestamp;
    private final String level;
    private final String logger;
    private final String message;
    private final String service;
    private final String environment;
    private final Map<String, Object> context;

    public StructuredLogEvent(
            String level,
            String logger,
            String message,
            String requestId,
            String tenantId,
            String deviceId,
            String operationId,
            String userId
    ) {
        this.timestamp = Instant.now().toString();
        this.level = Objects.requireNonNull(level, "level must not be null");
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.message = Objects.requireNonNull(message, "message must not be null");
        this.service = "pharmacy-pos-desktop";
        this.environment = "production";

        this.context = new HashMap<>();
        this.context.put("request_id", Objects.requireNonNull(requestId, "requestId must not be null"));
        this.context.put("tenant_id", tenantId);
        this.context.put("device_id", deviceId);
        this.context.put("operation_id", operationId);
        this.context.put("user_id", userId);
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getLevel() {
        return level;
    }

    public String getLogger() {
        return logger;
    }

    public String getMessage() {
        return message;
    }

    public String getService() {
        return service;
    }

    public String getEnvironment() {
        return environment;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public Map<String, String> toCorrelationHeaders() {
        Map<String, String> headers = new HashMap<>();
        if (context.get("request_id") != null) {
            headers.put("X-Request-ID", context.get("request_id").toString());
        }
        if (context.get("tenant_id") != null) {
            headers.put("X-Tenant-ID", context.get("tenant_id").toString());
        }
        if (context.get("device_id") != null) {
            headers.put("X-Device-ID", context.get("device_id").toString());
        }
        if (context.get("operation_id") != null) {
            headers.put("X-Operation-ID", context.get("operation_id").toString());
        }
        return headers;
    }
}

