package com.pharmacy.pos.models;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Architecture Decision: ADR-0056
 * POS Client implementation of Archival and Soft-Deletion Policy:
 * - Prevents UI from issuing destructive physical deletes on posted sales,
 *   prescriptions, audit events, and payments.
 * - Manages soft-deletion status transitions for catalog and config items.
 */
public final class PosArchivalPolicyManager {

    public static final Set<String> IMMUTABLE_TYPES = Set.of(
            "SALE_INVOICE",
            "PRESCRIPTION_RECORD",
            "AUDIT_LOG_ENTRY",
            "PAYMENT_TRANSACTION"
    );

    public static final Set<String> SOFT_ARCHIVABLE_TYPES = Set.of(
            "MEDICINE_CATALOG",
            "STAFF_MEMBER",
            "BRANCH_CONFIG"
    );

    private PosArchivalPolicyManager() {
        // utility class
    }

    public static boolean isImmutableRecord(String resourceType) {
        if (resourceType == null) return false;
        return IMMUTABLE_TYPES.contains(resourceType.toUpperCase().trim());
    }

    public static boolean isSoftArchivable(String resourceType) {
        if (resourceType == null) return false;
        return SOFT_ARCHIVABLE_TYPES.contains(resourceType.toUpperCase().trim());
    }

    public static boolean canDestructivelyDelete(String resourceType) {
        // Under ADR-0056, destructive physical deletion is forbidden for all domain records
        return false;
    }

    public static void validateDeletionPermitted(String resourceType, String resourceId) {
        Objects.requireNonNull(resourceType, "resourceType cannot be null");
        Objects.requireNonNull(resourceId, "resourceId cannot be null");

        String upperType = resourceType.toUpperCase().trim();
        if (isImmutableRecord(upperType)) {
            throw new IllegalStateException(
                    "Destructive deletion is strictly prohibited for immutable record '" +
                            upperType + ":" + resourceId + "'. Use audited reversal or credit note."
            );
        }
    }

    public static PosArchivedResource createArchivalRecord(
            String resourceType,
            String resourceId,
            String userId,
            String reason
    ) {
        validateDeletionPermitted(resourceType, resourceId);
        Objects.requireNonNull(reason, "reason cannot be null");
        if (reason.trim().length() < 5) {
            throw new IllegalArgumentException("Archival reason must be at least 5 characters.");
        }

        return new PosArchivedResource(
                resourceType.toUpperCase().trim(),
                resourceId.trim(),
                true,
                Instant.now(),
                userId,
                reason.trim()
        );
    }

    public static record PosArchivedResource(
            String resourceType,
            String resourceId,
            boolean isArchived,
            Instant archivedAt,
            String archivedBy,
            String archivalReason
    ) {}
}
