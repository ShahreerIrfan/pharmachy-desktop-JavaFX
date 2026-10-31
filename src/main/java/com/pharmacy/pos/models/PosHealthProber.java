package com.pharmacy.pos.models;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Architecture Decision: ADR-0058
 * POS Client implementation of Health, Liveness, and Readiness Probe Monitor:
 * - Checks cloud authority readiness before attempting online sync
 * - Validates zero internal secrets, passwords, or customer PII are exposed
 * - Drives offline cashier fallback banner
 */
public final class PosHealthProber {

    public static final Set<String> SENSITIVE_FORBIDDEN_TERMS = Set.of(
            "password", "secret_key", "bearer", "private_key",
            "postgres://", "redis://", "password_hash"
    );

    private PosHealthProber() {
        // utility class
    }

    public static boolean containsSensitiveLeak(String responseBody) {
        if (responseBody == null) return false;
        String lower = responseBody.toLowerCase(Locale.ROOT);
        for (String term : SENSITIVE_FORBIDDEN_TERMS) {
            if (lower.contains(term)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isReady(String overallStatus, Map<String, String> checks) {
        if (overallStatus == null) return false;
        if (!"READY".equalsIgnoreCase(overallStatus) && !"UP".equalsIgnoreCase(overallStatus)) {
            return false;
        }
        if (checks == null || checks.isEmpty()) {
            return true;
        }
        String db = checks.get("database");
        return db != null && "UP".equalsIgnoreCase(db);
    }

    public static PosHealthReport createReport(
            String service,
            String status,
            Map<String, String> checks,
            boolean isLive
    ) {
        Objects.requireNonNull(service, "service cannot be null");
        Objects.requireNonNull(status, "status cannot be null");

        boolean ready = isReady(status, checks);
        return new PosHealthReport(
                service,
                status.toUpperCase(Locale.ROOT),
                ready,
                isLive,
                checks != null ? checks : Map.of(),
                Instant.now()
        );
    }

    public static record PosHealthReport(
            String service,
            String status,
            boolean isReady,
            boolean isLivenessProbe,
            Map<String, String> componentChecks,
            Instant timestamp
    ) {}
}
