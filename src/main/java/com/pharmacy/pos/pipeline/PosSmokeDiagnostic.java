package com.pharmacy.pos.pipeline;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * PosSmokeDiagnostic
 * Architecture Decision: ADR-0040
 * Diagnostic probe for JavaFX Desktop POS during automated build smoke verification.
 */
public class PosSmokeDiagnostic {

    public static final String COMPONENT_NAME = "DESKTOP_POS";

    public static final class DiagnosticResult {
        public final boolean isHealthy;
        public final String javaVersion;
        public final long maxMemoryBytes;
        public final long freeMemoryBytes;
        public final String operatingSystem;
        public final String timestamp;
        public final Map<String, String> details;

        public DiagnosticResult(
            boolean isHealthy,
            String javaVersion,
            long maxMemoryBytes,
            long freeMemoryBytes,
            String operatingSystem,
            String timestamp,
            Map<String, String> details
        ) {
            this.isHealthy = isHealthy;
            this.javaVersion = Objects.requireNonNull(javaVersion, "javaVersion required");
            this.maxMemoryBytes = maxMemoryBytes;
            this.freeMemoryBytes = freeMemoryBytes;
            this.operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem required");
            this.timestamp = Objects.requireNonNull(timestamp, "timestamp required");
            this.details = details != null ? details : new HashMap<>();
        }
    }

    /**
     * Executes the desktop POS self-diagnostic probe.
     */
    public DiagnosticResult runDiagnostic() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long freeMemory = runtime.freeMemory();
        String javaVersion = System.getProperty("java.version", "unknown");
        String osName = System.getProperty("os.name", "unknown");

        Map<String, String> details = new HashMap<>();
        details.put("component", COMPONENT_NAME);
        details.put("available_processors", String.valueOf(runtime.availableProcessors()));
        details.put("user_dir", System.getProperty("user.dir", ""));
        details.put("status", "HEALTHY");

        boolean healthy = maxMemory > 0 && !javaVersion.equals("unknown");

        return new DiagnosticResult(
            healthy,
            javaVersion,
            maxMemory,
            freeMemory,
            osName,
            Instant.now().toString(),
            details
        );
    }
}
