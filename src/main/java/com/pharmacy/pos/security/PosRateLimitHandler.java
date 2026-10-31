package com.pharmacy.pos.security;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JavaFX Desktop POS Rate Limiting & Backoff Cooldown Handler.
 * Architecture Decision: ADR-0014, ADR-0047 & ADR-0048.
 * Intercepts HTTP 429 Too Many Requests, manages backoff windows,
 * and generates localized bilingual guidance for cashiers without Unicode emojis.
 */
public class PosRateLimitHandler {

    public record RateLimitEntry(
            String endpointKey,
            long waitSeconds,
            long throttledAtEpochMillis
    ) {
        public RateLimitEntry {
            Objects.requireNonNull(endpointKey, "endpointKey cannot be null");
            if (waitSeconds < 0) {
                throw new IllegalArgumentException("waitSeconds cannot be negative: " + waitSeconds);
            }
        }

        public boolean isCooldownActive() {
            long elapsedSeconds = (Instant.now().toEpochMilli() - throttledAtEpochMillis) / 1000;
            return elapsedSeconds < waitSeconds;
        }

        public long getRemainingSeconds() {
            long elapsedSeconds = (Instant.now().toEpochMilli() - throttledAtEpochMillis) / 1000;
            long remaining = waitSeconds - elapsedSeconds;
            return Math.max(0, remaining);
        }

        public String getBilingualCountdown() {
            long rem = getRemainingSeconds();
            return String.format("Request rate limit exceeded. Please wait %d second(s). / অনুরোধের সীমা অতিক্রম করেছে। অনুগ্রহ করে %d সেকেন্ড অপেক্ষা করুন।", rem, rem);
        }
    }

    private final Map<String, RateLimitEntry> activeThrottles = new ConcurrentHashMap<>();

    public void recordRateLimit(String endpointKey, long waitSeconds) {
        Objects.requireNonNull(endpointKey, "endpointKey cannot be null");
        activeThrottles.put(endpointKey, new RateLimitEntry(
                endpointKey,
                Math.max(1, waitSeconds),
                Instant.now().toEpochMilli()
        ));
    }

    public boolean isThrottled(String endpointKey) {
        RateLimitEntry entry = activeThrottles.get(endpointKey);
        if (entry == null) {
            return false;
        }
        if (!entry.isCooldownActive()) {
            activeThrottles.remove(endpointKey);
            return false;
        }
        return true;
    }

    public long getRemainingWaitSeconds(String endpointKey) {
        RateLimitEntry entry = activeThrottles.get(endpointKey);
        if (entry == null) {
            return 0;
        }
        long remaining = entry.getRemainingSeconds();
        if (remaining == 0) {
            activeThrottles.remove(endpointKey);
        }
        return remaining;
    }

    public String getStatusMessage(String endpointKey, String languageCode) {
        long rem = getRemainingWaitSeconds(endpointKey);
        if ("bn".equalsIgnoreCase(languageCode)) {
            return String.format("অনুরোধের সীমা অতিক্রম করেছে। অনুগ্রহ করে %d সেকেন্ড পর পুনরায় চেষ্টা করুন।", rem);
        }
        return String.format("Request rate limit exceeded. Please retry after %d seconds.", rem);
    }

    public void clearThrottle(String endpointKey) {
        activeThrottles.remove(endpointKey);
    }

    public void clearAll() {
        activeThrottles.clear();
    }
}
