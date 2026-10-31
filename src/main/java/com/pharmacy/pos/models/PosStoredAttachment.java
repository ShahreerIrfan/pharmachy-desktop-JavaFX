package com.pharmacy.pos.models;

import java.util.Objects;

/**
 * Immutable File Storage Record DTO for JavaFX Desktop POS.
 * Architecture Decision: ADR-0051 (Private File Storage, Antivirus & Expiring URLs).
 */
public record PosStoredAttachment(
        String fileId,
        String tenantId,
        String branchId,
        String fileName,
        String category,
        String mimeType,
        long fileSizeBytes,
        String checksumSha256,
        String scanStatus,
        boolean isQuarantined,
        String quarantineReason,
        String signedDownloadUrl,
        String expiresAt,
        String createdAt
) {
    public PosStoredAttachment {
        Objects.requireNonNull(fileId, "fileId cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(fileName, "fileName cannot be null");
        Objects.requireNonNull(category, "category cannot be null");
        Objects.requireNonNull(mimeType, "mimeType cannot be null");
        Objects.requireNonNull(scanStatus, "scanStatus cannot be null");
    }

    public boolean isClean() {
        return "CLEAN".equalsIgnoreCase(scanStatus) && !isQuarantined;
    }
}
