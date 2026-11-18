package com.pharmacy.pos.offline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;

/**
 * OfflinePrescriptionPolicyGuard
 * Architecture Decision: ADR-0311 (P311).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Fail-closed offline prescription guard for JavaFX POS terminals:
 * - Caches ONLY policy-approved, minimal-PHI SHA-256 signed authorization tokens (`cached_rx_auth_tokens`).
 * - Unconditionally refuses CONTROLLED_NARCOTIC items offline.
 * - Fails CLOSED whenever a required prescription token is missing, expired, revoked, signature-tampered,
 *   or requested quantity exceeds remaining authorized units.
 * - Immediately invalidates revoked tokens on P307 tombstone application.
 */
public class OfflinePrescriptionPolicyGuard {

    public static final class RxCheckResult {
        public final boolean allowed;
        public final boolean antimicrobialRegisterRequired;
        public final String reasonCode;
        public final String messageEn;
        public final String messageBn;

        public RxCheckResult(boolean allowed, boolean antimicrobialRegisterRequired, String reasonCode, String messageEn, String messageBn) {
            this.allowed = allowed;
            this.antimicrobialRegisterRequired = antimicrobialRegisterRequired;
            this.reasonCode = reasonCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }
    }

    public static void ensureRxTokenTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                    "CREATE TABLE IF NOT EXISTS cached_rx_auth_tokens (" +
                            "token_id TEXT PRIMARY KEY, " +
                            "tenant_id TEXT NOT NULL, " +
                            "branch_id TEXT NOT NULL, " +
                            "device_id TEXT NOT NULL, " +
                            "product_id TEXT NOT NULL, " +
                            "rx_category TEXT NOT NULL CHECK (rx_category IN ('STANDARD_RX', 'ANTIMICROBIAL_RX')), " +
                            "prescription_ref_hash_sha256 TEXT NOT NULL, " +
                            "approved_max_units INTEGER NOT NULL CHECK (approved_max_units >= 1), " +
                            "dispensed_units INTEGER NOT NULL DEFAULT 0 CHECK (dispensed_units >= 0), " +
                            "token_signature_sha256 TEXT NOT NULL, " +
                            "expires_at TEXT NOT NULL, " +
                            "status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CONSUMED', 'REVOKED', 'EXPIRED')), " +
                            "updated_at TEXT NOT NULL)"
            );
        }
    }

    public static String computeSignature(
            String tenantId,
            String branchId,
            String deviceId,
            String productId,
            String rxCategory,
            String rxRefHash,
            int approvedMaxUnits,
            String expiresAtIso
    ) {
        String raw = "orxtok|" + tenantId + "|" + branchId + "|" + deviceId + "|" + productId + "|" +
                rxCategory + "|" + rxRefHash + "|" + approvedMaxUnits + "|" + expiresAtIso;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static void cachePolicyApprovedToken(
            Connection conn,
            String tokenId,
            String tenantId,
            String branchId,
            String deviceId,
            String productId,
            String rxCategory,
            String rxRefHashSha256,
            int approvedMaxUnits,
            String tokenSignatureSha256,
            String expiresAtIso
    ) throws SQLException {
        ensureRxTokenTable(conn);
        if ("CONTROLLED_NARCOTIC".equalsIgnoreCase(rxCategory)) {
            throw new SecurityException("ERR_CONTROLLED_NARCOTIC_OFFLINE_FORBIDDEN: Controlled/narcotic tokens cannot be cached offline.");
        }
        String expectedSig = computeSignature(
                tenantId, branchId, deviceId, productId, rxCategory, rxRefHashSha256, approvedMaxUnits, expiresAtIso);
        if (!expectedSig.equalsIgnoreCase(tokenSignatureSha256)) {
            throw new SecurityException("ERR_RX_TOKEN_SIGNATURE_TAMPERED: Refusing to cache invalidly signed Rx token.");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO cached_rx_auth_tokens " +
                        "(token_id, tenant_id, branch_id, device_id, product_id, rx_category, " +
                        "prescription_ref_hash_sha256, approved_max_units, dispensed_units, " +
                        "token_signature_sha256, expires_at, status, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 'ACTIVE', ?)")) {
            ps.setString(1, tokenId);
            ps.setString(2, tenantId);
            ps.setString(3, branchId);
            ps.setString(4, deviceId);
            ps.setString(5, productId);
            ps.setString(6, rxCategory);
            ps.setString(7, rxRefHashSha256);
            ps.setInt(8, approvedMaxUnits);
            ps.setString(9, tokenSignatureSha256.toLowerCase());
            ps.setString(10, expiresAtIso);
            ps.setString(11, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    public static void revokeCachedToken(Connection conn, String tenantId, String tokenId) throws SQLException {
        ensureRxTokenTable(conn);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE cached_rx_auth_tokens SET status = 'REVOKED', updated_at = ? WHERE tenant_id = ? AND token_id = ?")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, tenantId);
            ps.setString(3, tokenId);
            ps.executeUpdate();
        }
    }

    /**
     * Fail-closed offline Rx authorization check & atomic local consumption.
     */
    public static RxCheckResult verifyAndConsumeOfflineRx(
            Connection conn,
            String tenantId,
            String branchId,
            String deviceId,
            String productId,
            String rxCategory,
            String tokenId,
            int requestedUnits,
            Instant now
    ) throws SQLException {
        ensureRxTokenTable(conn);
        if ("OTC".equalsIgnoreCase(rxCategory)) {
            return new RxCheckResult(true, false, "OK_OTC", "OTC item permitted offline", "OTC ওষুধ অফলাইনে অনুমোদিত");
        }
        if ("CONTROLLED_NARCOTIC".equalsIgnoreCase(rxCategory)) {
            return new RxCheckResult(
                    false, false, "ERR_CONTROLLED_NARCOTIC_OFFLINE_FORBIDDEN",
                    "Blocked: Controlled/narcotic dispensing requires online verification",
                    "ব্লকড: নিয়ন্ত্রিত/নারকোটিক ওষুধ অফলাইনে বিক্রয় নিষিদ্ধ"
            );
        }
        if (tokenId == null || tokenId.isBlank() || requestedUnits < 1) {
            return new RxCheckResult(
                    false, false, "ERR_FAIL_CLOSED_UNVERIFIED_RX",
                    "Blocked: Pre-verified prescription token missing",
                    "ব্লকড: পূর্ব-যাচাইকৃত প্রেসক্রিপশন টোকেন পাওয়া যায়নি"
            );
        }

        boolean origAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT rx_category, prescription_ref_hash_sha256, approved_max_units, dispensed_units, " +
                            "token_signature_sha256, expires_at, status FROM cached_rx_auth_tokens " +
                            "WHERE token_id = ? AND tenant_id = ? AND branch_id = ? AND device_id = ? AND product_id = ?")) {
                ps.setString(1, tokenId);
                ps.setString(2, tenantId);
                ps.setString(3, branchId);
                ps.setString(4, deviceId);
                ps.setString(5, productId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return new RxCheckResult(
                                false, false, "ERR_FAIL_CLOSED_TOKEN_MISSING",
                                "Blocked: Offline Rx verification could not be established",
                                "ব্লকড: অফলাইন প্রেসক্রিপশন যাচাই নিশ্চিত করা যায়নি"
                        );
                    }
                    String cat = rs.getString(1);
                    String refHash = rs.getString(2);
                    int maxUnits = rs.getInt(3);
                    int dispensed = rs.getInt(4);
                    String sig = rs.getString(5);
                    String expIso = rs.getString(6);
                    String st = rs.getString(7);

                    if (!"ACTIVE".equalsIgnoreCase(st)) {
                        conn.rollback();
                        return new RxCheckResult(false, false, "ERR_RX_TOKEN_INACTIVE", "Blocked: Token is " + st, "ব্লকড: টোকেন বাতিল বা ব্যবহৃত");
                    }
                    if (!Instant.parse(expIso).isAfter(now)) {
                        conn.rollback();
                        return new RxCheckResult(false, false, "ERR_RX_TOKEN_EXPIRED", "Blocked: Rx authorization expired", "ব্লকড: প্রেসক্রিপশন টোকেনের মেয়াদ শেষ");
                    }
                    String recomputed = computeSignature(tenantId, branchId, deviceId, productId, cat, refHash, maxUnits, expIso);
                    if (!recomputed.equalsIgnoreCase(sig)) {
                        conn.rollback();
                        return new RxCheckResult(false, false, "ERR_RX_TOKEN_SIGNATURE_TAMPERED", "Blocked: Tampered Rx token signature", "ব্লকড: অবৈধ টোকেন সিগনেচার");
                    }
                    if (dispensed + requestedUnits > maxUnits) {
                        conn.rollback();
                        return new RxCheckResult(false, false, "ERR_RX_APPROVED_QTY_EXCEEDED", "Blocked: Exceeds authorized Rx quantity", "ব্লকড: অনুমোদিত পরিমাণের চেয়ে বেশি");
                    }

                    int nextDisp = dispensed + requestedUnits;
                    String nextStatus = nextDisp >= maxUnits ? "CONSUMED" : "ACTIVE";
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE cached_rx_auth_tokens SET dispensed_units = ?, status = ?, updated_at = ? WHERE token_id = ?")) {
                        upd.setInt(1, nextDisp);
                        upd.setString(2, nextStatus);
                        upd.setString(3, now.toString());
                        upd.setString(4, tokenId);
                        upd.executeUpdate();
                    }
                    conn.commit();
                    boolean abx = "ANTIMICROBIAL_RX".equalsIgnoreCase(cat);
                    return new RxCheckResult(
                            true, abx, "OK_RX_VERIFIED",
                            abx ? "Verified offline antimicrobial Rx (Antibiotic register required)" : "Verified offline prescription token",
                            abx ? "অফলাইন অ্যান্টিবায়োটিক প্রেসক্রিপশন যাচাইকৃত (রেজিস্টার বাধ্যতামূলক)" : "অফলাইন প্রেসক্রিপশন টোকেন যাচাইকৃত"
                    );
                }
            }
        } catch (Exception ex) {
            conn.rollback();
            if (ex instanceof SQLException) throw (SQLException) ex;
            throw new SQLException(ex);
        } finally {
            conn.setAutoCommit(origAuto);
        }
    }
}
