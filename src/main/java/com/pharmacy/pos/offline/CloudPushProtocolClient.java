package com.pharmacy.pos.offline;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * CloudPushProtocolClient
 * Architecture Decision: ADR-0305 (P305).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Implements HTTPS sync push protocol for JavaFX POS:
 * - Enforces HTTPS-only cloud endpoints (rejects plaintext http:// for non-localhost hosts).
 * - Slices outbox commands into bounded batches (<= 50 events/batch).
 * - Validates strictly monotonic ascending client_sequence & supported event_version.
 * - Signs every batch envelope with HMAC-SHA256 keyed by device_fingerprint:active_dek_fingerprint.
 */
public class CloudPushProtocolClient {

    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_BATCH_SIZE = 50;
    public static final Set<Integer> SUPPORTED_EVENT_VERSIONS = Set.of(1, 2);

    private final String baseUrl;
    private final String tenantId;
    private final String deviceId;
    private final String deviceFingerprintSha256;
    private final String activeDekFingerprintSha256;

    public CloudPushProtocolClient(
            String baseUrl,
            String tenantId,
            String deviceId,
            String deviceFingerprintSha256,
            String activeDekFingerprintSha256
    ) {
        assertHttpsTransport(baseUrl);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.tenantId = tenantId;
        this.deviceId = deviceId;
        this.deviceFingerprintSha256 = deviceFingerprintSha256 != null ? deviceFingerprintSha256.toLowerCase() : "";
        this.activeDekFingerprintSha256 = activeDekFingerprintSha256 != null ? activeDekFingerprintSha256.toLowerCase() : "";
    }

    public static void assertHttpsTransport(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_BASE_URL");
        }
        String lower = url.trim().toLowerCase();
        boolean isLocal = lower.startsWith("http://localhost") || lower.startsWith("http://127.0.0.1");
        if (!lower.startsWith("https://") && !isLocal) {
            throw new SecurityException("ERR_INSECURE_TRANSPORT: Cloud sync push requires HTTPS TLS 1.2+.");
        }
    }

    public String getExecutePushEndpoint() {
        return this.baseUrl + "/api/v2/offline-sync/push/execute/";
    }

    public List<List<Map<String, Object>>> sliceIntoBoundedBatches(List<Map<String, Object>> orderedEvents) {
        if (orderedEvents == null || orderedEvents.isEmpty()) {
            return Collections.emptyList();
        }
        List<List<Map<String, Object>>> batches = new ArrayList<>();
        for (int i = 0; i < orderedEvents.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, orderedEvents.size());
            batches.add(new ArrayList<>(orderedEvents.subList(i, end)));
        }
        return batches;
    }

    public Map<String, Object> buildSignedPushBatch(
            String clientBatchId,
            List<Map<String, Object>> events,
            String canonicalEventsJson
    ) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("ERR_EMPTY_PUSH_BATCH");
        }
        if (events.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("ERR_PUSH_BATCH_LIMIT_EXCEEDED: Max " + MAX_BATCH_SIZE + " events per batch.");
        }

        long prevSeq = 0;
        for (Map<String, Object> ev : events) {
            int evVer = ((Number) ev.getOrDefault("event_version", 0)).intValue();
            if (!SUPPORTED_EVENT_VERSIONS.contains(evVer)) {
                throw new IllegalArgumentException("ERR_UNSUPPORTED_EVENT_VERSION: " + evVer);
            }
            long seq = ((Number) ev.getOrDefault("client_sequence", 0)).longValue();
            if (seq <= prevSeq) {
                throw new IllegalArgumentException("ERR_UNORDERED_EVENT_BATCH: Non-monotonic sequence " + seq);
            }
            prevSeq = seq;
        }

        String pushedAt = Instant.now().toString();
        String eventsSha = sha256Hex(canonicalEventsJson);
        String signature = computeHmacSha256(
                this.tenantId + "|" + this.deviceId + "|" + clientBatchId + "|" + PROTOCOL_VERSION + "|" + pushedAt + "|" + eventsSha,
                this.deviceFingerprintSha256 + ":" + this.activeDekFingerprintSha256
        );

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("tenant_id", this.tenantId);
        envelope.put("device_id", this.deviceId);
        envelope.put("client_batch_id", clientBatchId);
        envelope.put("protocol_version", PROTOCOL_VERSION);
        envelope.put("pushed_at", pushedAt);
        envelope.put("signature_hmac_sha256", signature);
        envelope.put("events", events);
        return envelope;
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String computeHmacSha256(String message, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
