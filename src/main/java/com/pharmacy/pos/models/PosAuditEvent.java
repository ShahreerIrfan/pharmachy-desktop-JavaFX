package com.pharmacy.pos.models;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable Audit Event DTO for JavaFX Desktop POS.
 * Architecture Decision: ADR-0050 (Audit Event Foundation & Protected Retention).
 */
public record PosAuditEvent(
        String entryId,
        String timestamp,
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
        Map<String, Object> afterState,
        String retentionUntil,
        boolean isImmutable
) {
    public PosAuditEvent {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(action, "action cannot be null");
        Objects.requireNonNull(resourceType, "resourceType cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        beforeState = beforeState != null ? Collections.unmodifiableMap(beforeState) : Collections.emptyMap();
        afterState = afterState != null ? Collections.unmodifiableMap(afterState) : Collections.emptyMap();
    }
}
