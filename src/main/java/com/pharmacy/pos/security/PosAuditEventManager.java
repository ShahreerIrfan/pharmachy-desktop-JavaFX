package com.pharmacy.pos.security;

import com.pharmacy.pos.contracts.PosApiResponseEnvelope.UiState;
import com.pharmacy.pos.models.PosAuditEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * JavaFX Desktop POS Audit Event Manager and Validator.
 * Architecture Decision: ADR-0050 (Audit Event Foundation & Protected Retention).
 * Manages append-only event creation, before/after delta tracking, and DGDA retention rules.
 */
public class PosAuditEventManager {

    /**
     * Builds an immutable local PosAuditEvent with 730-day (2-year) protected retention.
     */
    public static PosAuditEvent createEvent(
            String tenantId,
            String branchId,
            String userId,
            String actorRole,
            String deviceId,
            String action,
            String resourceType,
            String resourceId,
            String status,
            Map<String, Object> beforeState,
            Map<String, Object> afterState
    ) {
        String entryId = UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();
        String retentionUntil = Instant.now().plus(730, ChronoUnit.DAYS).toString();

        return new PosAuditEvent(
                entryId,
                timestamp,
                tenantId,
                branchId,
                userId,
                actorRole,
                deviceId,
                action,
                resourceType,
                resourceId,
                status != null ? status : "SUCCESS",
                beforeState,
                afterState,
                retentionUntil,
                true
        );
    }

    /**
     * Parses server response JSON into PosAuditEvent.
     */
    @SuppressWarnings("unchecked")
    public static PosAuditEvent parseEvent(Map<String, Object> map) {
        Objects.requireNonNull(map, "Audit event map cannot be null");

        String entryId = (String) map.get("entry_id");
        String timestamp = (String) map.get("timestamp");
        String tenantId = (String) map.get("tenant_id");
        String branchId = (String) map.get("branch_id");
        String userId = (String) map.get("user_id");
        String actorRole = (String) map.getOrDefault("actor_role", "CASHIER");
        String deviceId = (String) map.get("device_id");
        String action = (String) map.get("action");
        String resourceType = (String) map.get("resource_type");
        String resourceId = (String) map.get("resource_id");
        String status = (String) map.getOrDefault("status", "SUCCESS");
        Map<String, Object> beforeState = (Map<String, Object>) map.getOrDefault("before_state", Collections.emptyMap());
        Map<String, Object> afterState = (Map<String, Object>) map.getOrDefault("after_state", Collections.emptyMap());
        String retentionUntil = (String) map.get("retention_until");
        boolean isImmutable = Boolean.TRUE.equals(map.getOrDefault("is_immutable", true));

        return new PosAuditEvent(
                entryId,
                timestamp,
                tenantId,
                branchId,
                userId,
                actorRole,
                deviceId,
                action,
                resourceType,
                resourceId,
                status,
                beforeState,
                afterState,
                retentionUntil,
                isImmutable
        );
    }

    /**
     * Validates that an audit event complies with append-only requirements.
     */
    public static boolean validateEventIntegrity(PosAuditEvent event) {
        if (event == null) return false;
        if (event.tenantId() == null || event.tenantId().isBlank()) return false;
        if (event.action() == null || event.action().isBlank()) return false;
        if (event.resourceType() == null || event.resourceType().isBlank()) return false;
        return event.isImmutable();
    }

    /**
     * Resolves bilingual error message conforming to ADR-0050.
     */
    public static Map<String, String> getBilingualErrorMessage(String errorCode) {
        Map<String, String> messages = new HashMap<>();
        if ("ERR_AUDIT_IMMUTABLE".equalsIgnoreCase(errorCode)) {
            messages.put("en", "Audit log entries are immutable and append-only. Modification or deletion is forbidden.");
            messages.put("bn", "অডিট লগ রেকর্ড অপরিবর্তনযোগ্য। পরিবর্তন বা মুছে ফেলা কঠোরভাবে নিষিদ্ধ।");
        } else if ("ERR_CROSS_TENANT_VIOLATION".equalsIgnoreCase(errorCode)) {
            messages.put("en", "Cross-tenant audit query is strictly forbidden.");
            messages.put("bn", "অন্য ফার্মেসির অডিট লগ অনুসন্ধান কঠোরভাবে নিষিদ্ধ।");
        } else if ("ERR_PERMISSION_DENIED".equalsIgnoreCase(errorCode)) {
            messages.put("en", "Access denied: Audit log inspection requires Manager or Owner privileges.");
            messages.put("bn", "প্রবেশাধিকার প্রত্যাখ্যাত: অডিট লগ দেখার জন্য ম্যানেজার বা মালিকের অনুমতি প্রয়োজন।");
        } else {
            messages.put("en", "Audit logging operation failed. Please check connection.");
            messages.put("bn", "অডিট লগ পরিচালনায় ত্রুটি দেখা দিয়েছে। সংযোগ পরীক্ষা করুন।");
        }
        return Collections.unmodifiableMap(messages);
    }

    /**
     * Resolves UI State for JavaFX controllers handling audit errors.
     */
    public static UiState resolveUiState(String errorCode) {
        if ("ERR_AUDIT_IMMUTABLE".equalsIgnoreCase(errorCode)
                || "ERR_CROSS_TENANT_VIOLATION".equalsIgnoreCase(errorCode)
                || "ERR_PERMISSION_DENIED".equalsIgnoreCase(errorCode)) {
            return UiState.PERMISSION_DENIED;
        }
        return UiState.ERROR;
    }
}

