package com.pharmacy.pos.security;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * JavaFX Desktop POS Session & Token Rotation Client Service.
 * Architecture Decision: ADR-0045 (Session & Token Rotation, Device Binding & Replay Detection).
 */
public class PosSessionService {

    public record PosSession(
            String sessionId,
            String userId,
            String tenantId,
            String deviceId,
            String deviceName,
            String clientPlatform,
            String ipAddress,
            boolean isActive,
            boolean isCurrent,
            String lastSeenAt,
            String createdAt
    ) {
        public PosSession {
            Objects.requireNonNull(sessionId, "sessionId cannot be null");
            Objects.requireNonNull(userId, "userId cannot be null");
            Objects.requireNonNull(tenantId, "tenantId cannot be null");
            Objects.requireNonNull(deviceId, "deviceId cannot be null");
        }
    }

    public static boolean validateDeviceBinding(String sessionDeviceId, String currentDeviceId) {
        if (sessionDeviceId == null || currentDeviceId == null) {
            return false;
        }
        return sessionDeviceId.trim().equalsIgnoreCase(currentDeviceId.trim());
    }

    public static boolean isReplayError(String errorCode) {
        return "TOKEN_REPLAY_DETECTED".equalsIgnoreCase(errorCode);
    }

    public static PosSession parseSession(Map<String, Object> map) {
        Objects.requireNonNull(map, "Session map cannot be null");
        String sessionId = (String) map.get("session_id");
        String userId = (String) map.get("user_id");
        String tenantId = (String) map.get("tenant_id");
        String deviceId = (String) map.get("device_id");
        String deviceName = (String) map.getOrDefault("device_name", "");
        String clientPlatform = (String) map.getOrDefault("client_platform", "DESKTOP_POS");
        String ipAddress = (String) map.get("ip_address");
        boolean isActive = Boolean.TRUE.equals(map.get("is_active"));
        boolean isCurrent = Boolean.TRUE.equals(map.get("is_current"));
        String lastSeenAt = (String) map.getOrDefault("last_seen_at", "");
        String createdAt = (String) map.getOrDefault("created_at", "");

        return new PosSession(
                sessionId,
                userId,
                tenantId,
                deviceId,
                deviceName,
                clientPlatform,
                ipAddress,
                isActive,
                isCurrent,
                lastSeenAt,
                createdAt
        );
    }

    public static Map<String, String> getBilingualErrorMessage(String errorCode) {
        Map<String, String> msg = new HashMap<>();
        if ("TOKEN_REPLAY_DETECTED".equalsIgnoreCase(errorCode)) {
            msg.put("en", "Token replay attack detected. All active sessions in this family have been revoked.");
            msg.put("bn", "টোকেন রিপ্লে আক্রমণ সনাক্ত হয়েছে। এই সেশনের সকল অ্যাক্টিভ টোকেন বাতিল করা হয়েছে।");
        } else if ("DEVICE_BINDING_MISMATCH".equalsIgnoreCase(errorCode)) {
            msg.put("en", "Device binding verification failed. Session is bound to a different terminal.");
            msg.put("bn", "ডিভাইস বাইন্ডিং যাচাইকরণ ব্যর্থ হয়েছে। সেশনটি অন্য টার্মিনালের সাথে যুক্ত।");
        } else if ("CROSS_TENANT_SESSION_ACCESS_DENIED".equalsIgnoreCase(errorCode)) {
            msg.put("en", "Cross-tenant session access is strictly denied.");
            msg.put("bn", "ক্রস-টেন্যান্ট সেশন অ্যাক্সেস কঠোরভাবে নিষিদ্ধ।");
        } else if ("SESSION_REVOKED".equalsIgnoreCase(errorCode)) {
            msg.put("en", "Session has been terminated. Please log in again.");
            msg.put("bn", "সেশন বাতিল করা হয়েছে। দয়া করে পুনরায় লগইন করুন।");
        } else {
            msg.put("en", "Authentication failure. Please contact support.");
            msg.put("bn", "অথেনটিকেশন ব্যর্থ হয়েছে। অনুগ্রহ করে সাপোর্টে যোগাযোগ করুন।");
        }
        return Collections.unmodifiableMap(msg);
    }
}

