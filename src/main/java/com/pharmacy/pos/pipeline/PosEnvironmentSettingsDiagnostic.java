package com.pharmacy.pos.pipeline;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * PosEnvironmentSettingsDiagnostic
 * Architecture Decision: ADR-0041
 * Inspects JavaFX Desktop POS cloud connection posture, transport encryption, and active environment profile.
 */
public class PosEnvironmentSettingsDiagnostic {

    public enum EnvironmentMode {
        DEVELOPMENT,
        TESTING,
        PRODUCTION
    }

    public static final class EnvironmentPosture {
        public final EnvironmentMode mode;
        public final String cloudApiBaseUrl;
        public final boolean requireHttps;
        public final boolean offlineCacheEnabled;
        public final String timestamp;
        public final Map<String, String> properties;

        public EnvironmentPosture(
            EnvironmentMode mode,
            String cloudApiBaseUrl,
            boolean requireHttps,
            boolean offlineCacheEnabled,
            String timestamp,
            Map<String, String> properties
        ) {
            this.mode = Objects.requireNonNull(mode, "mode required");
            this.cloudApiBaseUrl = Objects.requireNonNull(cloudApiBaseUrl, "cloudApiBaseUrl required");
            this.requireHttps = requireHttps;
            this.offlineCacheEnabled = offlineCacheEnabled;
            this.timestamp = Objects.requireNonNull(timestamp, "timestamp required");
            this.properties = properties != null ? properties : new HashMap<>();
        }
    }

    /**
     * Resolves the desktop client environment posture based on base URL.
     */
    public EnvironmentPosture evaluatePosture(String baseUrl) {
        String url = (baseUrl != null && !baseUrl.trim().isEmpty()) ? baseUrl.trim() : "http://localhost:8000";
        EnvironmentMode mode;
        boolean requireHttps;

        if (url.contains("localhost") || url.contains("127.0.0.1")) {
            mode = EnvironmentMode.DEVELOPMENT;
            requireHttps = false;
        } else if (url.contains("test") || url.contains("mock")) {
            mode = EnvironmentMode.TESTING;
            requireHttps = false;
        } else {
            mode = EnvironmentMode.PRODUCTION;
            requireHttps = true;
        }

        Map<String, String> props = new HashMap<>();
        props.put("target_authority", url);
        props.put("enforce_transport_tls", String.valueOf(requireHttps));
        props.put("sqlite_offline_fallback", "true");

        return new EnvironmentPosture(
            mode,
            url,
            requireHttps,
            true,
            Instant.now().toString(),
            props
        );
    }
}
