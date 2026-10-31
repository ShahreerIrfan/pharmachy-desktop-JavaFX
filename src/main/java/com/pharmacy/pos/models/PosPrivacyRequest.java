package com.pharmacy.pos.models;

import java.util.Objects;

/**
 * Immutable Data Subject Request (DSR) DTO for JavaFX Desktop POS.
 * Architecture Decision: ADR-0052 (Data Privacy Operations & Retention Schedule).
 */
public record PosPrivacyRequest(
        String requestId,
        String tenantId,
        String branchId,
        String subjectType,
        String subjectIdentifier,
        String requestType,
        String status,
        String reason,
        boolean retentionConflictDetected,
        int retentionHorizonDays,
        String exportArtifactUrl,
        String assessedByEmail,
        String assessedAt,
        String createdAt
) {
    public PosPrivacyRequest {
        Objects.requireNonNull(requestId, "requestId cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(subjectIdentifier, "subjectIdentifier cannot be null");
        Objects.requireNonNull(requestType, "requestType cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
    }

    public boolean hasRetentionHold() {
        return retentionConflictDetected;
    }
}

