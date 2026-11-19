package com.pharmacy.pos.offline;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * ProvisionalIdentifierManager
 * Architecture Decision: ADR-0315 (P315).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Client-side provisional identifier issuer and bidirectional mapping manager:
 * 1. Issues client-generated UUID provisional identifiers (PROV-INV-..., PROV-CUST-...)
 *    for offline sales, returns, and customers.
 * 2. Persists durable mappings in SQLite local_provisional_mappings upon receiving canonical acks.
 * 3. Provides transparent bidirectional resolution: receipt reprints, returns, and barcode lookups
 *    can query with either the local provisional ID or the authoritative cloud canonical ID.
 */
public class ProvisionalIdentifierManager {

    public enum ProvisionalEntityType {
        INVOICE,
        CUSTOMER,
        PAYMENT,
        PRESCRIPTION,
        RETURN_RECEIPT
    }

    public static final class ProvisionalMappingRecord {
        public final String mappingId;
        public final String tenantId;
        public final String branchId;
        public final ProvisionalEntityType entityType;
        public final String provisionalId;
        public final String canonicalId;
        public final String idempotencyKey;
        public final String reconciledAt;

        public ProvisionalMappingRecord(
                String mappingId,
                String tenantId,
                String branchId,
                ProvisionalEntityType entityType,
                String provisionalId,
                String canonicalId,
                String idempotencyKey,
                String reconciledAt
        ) {
            this.mappingId = mappingId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.entityType = entityType;
            this.provisionalId = provisionalId;
            this.canonicalId = canonicalId;
            this.idempotencyKey = idempotencyKey;
            this.reconciledAt = reconciledAt;
        }
    }

    public static String issueProvisionalId(ProvisionalEntityType type) {
        Objects.requireNonNull(type, "entityType cannot be null");
        String prefix = type.name().substring(0, Math.min(type.name().length(), 4));
        return "PROV-" + prefix + "-" + UUID.randomUUID().toString();
    }

    public static void storeProvisionalMapping(
            Connection conn,
            String tenantId,
            String branchId,
            ProvisionalEntityType type,
            String provisionalId,
            String canonicalId,
            String idempotencyKey
    ) throws SQLException {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(branchId, "branchId cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(provisionalId, "provisionalId cannot be null");
        Objects.requireNonNull(canonicalId, "canonicalId cannot be null");

        String mappingId = "pim-loc-" + UUID.randomUUID().toString().substring(0, 12);
        String sql = "INSERT OR REPLACE INTO local_provisional_mappings (" +
                "mapping_id, tenant_id, branch_id, entity_type, provisional_id, canonical_id, idempotency_key, reconciled_at" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, mappingId);
            ps.setString(2, tenantId);
            ps.setString(3, branchId);
            ps.setString(4, type.name());
            ps.setString(5, provisionalId);
            ps.setString(6, canonicalId);
            ps.setString(7, idempotencyKey != null ? idempotencyKey : "");
            ps.setString(8, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    public static Optional<String> lookupCanonical(
            Connection conn,
            String tenantId,
            ProvisionalEntityType type,
            String provisionalId
    ) throws SQLException {
        String sql = "SELECT canonical_id FROM local_provisional_mappings " +
                "WHERE tenant_id = ? AND entity_type = ? AND provisional_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, type.name());
            ps.setString(3, provisionalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("canonical_id"));
                }
            }
        }
        return Optional.empty();
    }

    public static Optional<String> lookupProvisional(
            Connection conn,
            String tenantId,
            ProvisionalEntityType type,
            String canonicalId
    ) throws SQLException {
        String sql = "SELECT provisional_id FROM local_provisional_mappings " +
                "WHERE tenant_id = ? AND entity_type = ? AND canonical_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, type.name());
            ps.setString(3, canonicalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("provisional_id"));
                }
            }
        }
        return Optional.empty();
    }

    public static Optional<ProvisionalMappingRecord> resolveAny(
            Connection conn,
            String tenantId,
            String anyId
    ) throws SQLException {
        if (anyId == null || anyId.trim().isEmpty()) {
            return Optional.empty();
        }
        String sql = "SELECT mapping_id, tenant_id, branch_id, entity_type, provisional_id, canonical_id, " +
                "idempotency_key, reconciled_at FROM local_provisional_mappings " +
                "WHERE tenant_id = ? AND (provisional_id = ? OR canonical_id = ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, anyId.trim());
            ps.setString(3, anyId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ProvisionalMappingRecord(
                            rs.getString("mapping_id"),
                            rs.getString("tenant_id"),
                            rs.getString("branch_id"),
                            ProvisionalEntityType.valueOf(rs.getString("entity_type")),
                            rs.getString("provisional_id"),
                            rs.getString("canonical_id"),
                            rs.getString("idempotency_key"),
                            rs.getString("reconciled_at")
                    ));
                }
            }
        }
        return Optional.empty();
    }
}
