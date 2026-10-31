package com.pharmacy.pos.governance;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * PosBugReportService
 * Architecture Decision: ADR-0039
 * Captures sanitized desktop POS runtime telemetry and structures bug reproduction fields.
 */
public class PosBugReportService {

    public static final String CLIENT_PLATFORM = "DESKTOP";
    public static final String DEFAULT_VERSION = "1.2.0";

    public static final class PosBugReport {
        public final String tenantId;
        public final String title;
        public final String severity;
        public final String reproductionSteps;
        public final String expectedBehavior;
        public final String observedBehavior;
        public final String clientVersion;
        public final String osName;
        public final String javaVersion;
        public final int pendingOfflineOutboxCount;
        public final String timestamp;

        public PosBugReport(
            String tenantId,
            String title,
            String severity,
            String reproductionSteps,
            String expectedBehavior,
            String observedBehavior,
            String clientVersion,
            String osName,
            String javaVersion,
            int pendingOfflineOutboxCount,
            String timestamp
        ) {
            this.tenantId = Objects.requireNonNull(tenantId, "tenantId required");
            this.title = Objects.requireNonNull(title, "title required");
            this.severity = Objects.requireNonNull(severity, "severity required");
            this.reproductionSteps = Objects.requireNonNull(reproductionSteps, "reproductionSteps required");
            this.expectedBehavior = Objects.requireNonNull(expectedBehavior, "expectedBehavior required");
            this.observedBehavior = Objects.requireNonNull(observedBehavior, "observedBehavior required");
            this.clientVersion = clientVersion;
            this.osName = osName;
            this.javaVersion = javaVersion;
            this.pendingOfflineOutboxCount = pendingOfflineOutboxCount;
            this.timestamp = timestamp;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("tenant_id", tenantId);
            map.put("title", title);
            map.put("category", "BUG");
            map.put("severity", severity);
            map.put("client_platform", CLIENT_PLATFORM);
            map.put("client_version", clientVersion);
            map.put("environment", "DEVELOPMENT");
            map.put("connectivity_state", pendingOfflineOutboxCount > 0 ? "OFFLINE" : "ONLINE");
            map.put("reproduction_steps", reproductionSteps);
            map.put("expected_behavior", expectedBehavior);
            map.put("observed_behavior", observedBehavior);
            map.put("sanitized_logs", String.format("OS: %s | Java: %s | Outbox: %d", osName, javaVersion, pendingOfflineOutboxCount));
            return map;
        }
    }

    public static PosBugReport createReport(
        String tenantId,
        String title,
        String severity,
        String reproductionSteps,
        String expectedBehavior,
        String observedBehavior,
        int pendingOfflineCount
    ) {
        if (reproductionSteps == null || reproductionSteps.trim().length() < 10) {
            throw new IllegalArgumentException("Reproduction steps must contain at least 10 characters");
        }

        return new PosBugReport(
            tenantId,
            title,
            severity,
            reproductionSteps,
            expectedBehavior,
            observedBehavior,
            DEFAULT_VERSION,
            System.getProperty("os.name", "Unknown OS"),
            System.getProperty("java.version", "17"),
            pendingOfflineCount,
            Instant.now().toString()
        );
    }
}
