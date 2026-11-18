package com.pharmacy.pos.offline;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;

/**
 * OfflineSubscriptionLeaseEnforcer
 * Architecture Decision: ADR-0312 (P312).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Enforces signed offline subscription & role lease expiration inside the JavaFX POS SQLite store:
 * - Persists a monotonic clock high-water mark (`lease_clock_anchor`) so rolling the Windows/OS system
 *   clock backwards CANNOT extend an expired offline lease!
 * - Verifies HMAC-SHA256 lease signature before allowing offline operations.
 * - Even when expired or clock-quarantined, ALWAYS keeps `protectedSyncRecoveryAllowed = true` so
 *   queued `pending_offline_commands` can be safely flushed/exported without data loss.
 */
public class OfflineSubscriptionLeaseEnforcer {

    private static final long CLOCK_ROLLBACK_TOLERANCE_MS = 300_000L;

    public static final class LeaseCheckDecision {
        public final boolean newOfflineSalesAllowed;
        public final boolean protectedSyncRecoveryAllowed;
        public final String statusCode;
        public final String messageEn;
        public final String messageBn;

        public LeaseCheckDecision(
                boolean newOfflineSalesAllowed,
                boolean protectedSyncRecoveryAllowed,
                String statusCode,
                String messageEn,
                String messageBn
        ) {
            this.newOfflineSalesAllowed = newOfflineSalesAllowed;
            this.protectedSyncRecoveryAllowed = protectedSyncRecoveryAllowed;
            this.statusCode = statusCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }
    }

    public static void ensureClockAnchorTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                    "CREATE TABLE IF NOT EXISTS lease_clock_anchor (" +
                            "singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1), " +
                            "max_observed_epoch_ms INTEGER NOT NULL DEFAULT 0, " +
                            "clock_tamper_flagged INTEGER NOT NULL DEFAULT 0 CHECK (clock_tamper_flagged IN (0, 1)), " +
                            "updated_at TEXT NOT NULL)"
            );
            st.execute(
                    "INSERT OR IGNORE INTO lease_clock_anchor (singleton_id, max_observed_epoch_ms, clock_tamper_flagged, updated_at) " +
                            "VALUES (1, 0, 0, '" + Instant.now() + "')"
            );
        }
    }

    public static String computeHmacSha256(String secretKey, String canonicalPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }

    public static void storeSignedLease(
            Connection conn,
            String leaseId,
            String tenantId,
            String branchId,
            String deviceId,
            String planTier,
            String entitlementsJson,
            long issuedEpochMs,
            long expiresEpochMs,
            String signatureHmacSha256
    ) throws SQLException {
        ensureClockAnchorTable(conn);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO subscription_leases " +
                        "(lease_id, tenant_id, branch_id, device_id, plan_tier, entitlements_json, issued_at, expires_at, signature_sha256, status) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')")) {
            ps.setString(1, leaseId);
            ps.setString(2, tenantId);
            ps.setString(3, branchId);
            ps.setString(4, deviceId);
            ps.setString(5, planTier);
            ps.setString(6, entitlementsJson);
            ps.setString(7, String.valueOf(issuedEpochMs));
            ps.setString(8, String.valueOf(expiresEpochMs));
            ps.setString(9, signatureHmacSha256.toLowerCase());
            ps.executeUpdate();
        }
        try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE lease_clock_anchor SET max_observed_epoch_ms = MAX(max_observed_epoch_ms, ?), " +
                        "clock_tamper_flagged = 0, updated_at = ? WHERE singleton_id = 1")) {
            upd.setLong(1, issuedEpochMs);
            upd.setString(2, Instant.now().toString());
            upd.executeUpdate();
        }
    }

    /**
     * Evaluates lease validity using monotonic high-water clock anchor (no device-clock rollback bypass!).
     */
    public static LeaseCheckDecision evaluateOfflineLease(
            Connection conn,
            String leaseId,
            String secretKey,
            long currentDeviceEpochMs
    ) throws SQLException {
        ensureClockAnchorTable(conn);

        long maxObserved = 0L;
        boolean tamperFlagged = false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT max_observed_epoch_ms, clock_tamper_flagged FROM lease_clock_anchor WHERE singleton_id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                maxObserved = rs.getLong(1);
                tamperFlagged = rs.getInt(2) == 1;
            }
        }

        // Detect device-clock rollback attempt!
        if (tamperFlagged || (maxObserved > 0 && currentDeviceEpochMs < (maxObserved - CLOCK_ROLLBACK_TOLERANCE_MS))) {
            try (PreparedStatement flag = conn.prepareStatement(
                    "UPDATE lease_clock_anchor SET clock_tamper_flagged = 1, updated_at = ? WHERE singleton_id = 1")) {
                flag.setString(1, Instant.now().toString());
                flag.executeUpdate();
            }
            return new LeaseCheckDecision(
                    false,
                    true, // Protected safe sync recovery remains available!
                    "ERR_DEVICE_CLOCK_ROLLBACK_DETECTED",
                    "Device clock rollback detected — offline sales blocked; sync recovery protected",
                    "ডিভাইসের ঘড়ি পেছানো শনাক্ত হয়েছে — নতুন অফলাইন বিক্রয় বন্ধ, সিঙ্ক রিকভারি সুরক্ষিত"
            );
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT tenant_id, branch_id, device_id, plan_tier, issued_at, expires_at, signature_sha256, status " +
                        "FROM subscription_leases WHERE lease_id = ?")) {
            ps.setString(1, leaseId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new LeaseCheckDecision(false, true, "ERR_LEASE_MISSING",
                            "No offline lease found — recovery sync only", "অফলাইন লিজ নেই — শুধু রিকভারি সিঙ্ক চালু");
                }
                String tid = rs.getString(1);
                String bid = rs.getString(2);
                String did = rs.getString(3);
                String plan = rs.getString(4);
                long issuedMs = Long.parseLong(rs.getString(5));
                long expiresMs = Long.parseLong(rs.getString(6));
                String sig = rs.getString(7);
                String st = rs.getString(8);

                String canonical = tid + "|" + bid + "|" + did + "|" + plan + "|" + issuedMs + "|" + expiresMs;
                if (!computeHmacSha256(secretKey, canonical).equalsIgnoreCase(sig)) {
                    return new LeaseCheckDecision(false, true, "ERR_LEASE_SIGNATURE_TAMPERED",
                            "Invalid offline lease signature — recovery sync only", "অবৈধ লিজ সিগনেচার — শুধু রিকভারি সিঙ্ক চালু");
                }
                if (!"ACTIVE".equalsIgnoreCase(st)) {
                    return new LeaseCheckDecision(false, true, "ERR_LEASE_REVOKED",
                            "Lease revoked — recovery sync only", "লিজ বাতিলকৃত — শুধু রিকভারি সিঙ্ক চালু");
                }

                long effectiveMs = Math.max(currentDeviceEpochMs, maxObserved);
                if (effectiveMs >= expiresMs) {
                    return new LeaseCheckDecision(false, true, "ERR_LEASE_EXPIRED_RECOVERY_ONLY",
                            "Offline lease expired — new sales blocked, pending sync recovery protected",
                            "অফলাইন লিজের মেয়াদ শেষ — নতুন বিক্রয় বন্ধ, পেন্ডিং সিঙ্ক রিকভারি সুরক্ষিত");
                }

                // Advance monotonic high-water mark
                if (currentDeviceEpochMs > maxObserved) {
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE lease_clock_anchor SET max_observed_epoch_ms = ?, updated_at = ? WHERE singleton_id = 1")) {
                        upd.setLong(1, currentDeviceEpochMs);
                        upd.setString(2, Instant.now().toString());
                        upd.executeUpdate();
                    }
                }

                return new LeaseCheckDecision(true, true, "OK_LEASE_ACTIVE",
                        "Signed offline lease active", "স্বাক্ষরিত অফলাইন লিজ সক্রিয়");
            }
        }
    }
}
