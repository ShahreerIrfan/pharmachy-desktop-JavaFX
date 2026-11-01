package com.pharmacy.pos.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * PosSignedOfflineEntitlementClient
 * Architecture Decision: ADR-0007, ADR-0082, ADR-0092 & ADR-0093
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P093)
 * Requirement Trace: FR-071 (SaaS Plans, Billing & Tenant Lifecycle)
 *
 * Provides cryptographic HMAC-SHA256 validation of offline entitlement leases,
 * device/branch binding checks, offline checkout dispensing gating, and bilingual status banners.
 */
public class PosSignedOfflineEntitlementClient {

    public static final class OfflineLeaseCertificate {
        public final String leaseId;
        public final String tenantId;
        public final String branchId;
        public final String deviceId;
        public final String roleSnapshot;
        public final String planTier;
        public final String policyVersion;
        public final int durationHours;
        public final String issuedAtIso;
        public final String expiresAtIso;
        public final Map<String, Boolean> capabilities;
        public final String signature;
        public final String status;

        public OfflineLeaseCertificate(
                String leaseId,
                String tenantId,
                String branchId,
                String deviceId,
                String roleSnapshot,
                String planTier,
                String policyVersion,
                int durationHours,
                String issuedAtIso,
                String expiresAtIso,
                Map<String, Boolean> capabilities,
                String signature,
                String status) {
            this.leaseId = leaseId != null ? leaseId.trim() : "";
            this.tenantId = tenantId != null ? tenantId.trim() : "";
            this.branchId = branchId != null ? branchId.trim() : "";
            this.deviceId = deviceId != null ? deviceId.trim() : "";
            this.roleSnapshot = roleSnapshot != null ? roleSnapshot.trim().toUpperCase() : "CASHIER";
            this.planTier = planTier != null ? planTier.trim().toUpperCase() : "BASIC";
            this.policyVersion = policyVersion != null ? policyVersion.trim() : "v2.0";
            this.durationHours = durationHours;
            this.issuedAtIso = issuedAtIso != null ? issuedAtIso.trim() : "";
            this.expiresAtIso = expiresAtIso != null ? expiresAtIso.trim() : "";
            this.capabilities = capabilities != null ? Collections.unmodifiableMap(new TreeMap<>(capabilities)) : Collections.emptyMap();
            this.signature = signature != null ? signature.trim().toLowerCase() : "";
            this.status = status != null ? status.trim().toUpperCase() : "ACTIVE";
        }
    }

    public static final class VerificationResult {
        public final boolean isValid;
        public final String reason;
        public final long hoursRemaining;
        public final boolean canDispenseOffline;
        public final String headlineEn;
        public final String headlineBn;
        public final String detailsEn;
        public final String detailsBn;

        public VerificationResult(
                boolean isValid,
                String reason,
                long hoursRemaining,
                boolean canDispenseOffline,
                String headlineEn,
                String headlineBn,
                String detailsEn,
                String detailsBn) {
            this.isValid = isValid;
            this.reason = reason;
            this.hoursRemaining = hoursRemaining;
            this.canDispenseOffline = canDispenseOffline;
            this.headlineEn = headlineEn;
            this.headlineBn = headlineBn;
            this.detailsEn = detailsEn;
            this.detailsBn = detailsBn;
        }
    }

    /**
     * Builds deterministic sorted JSON canonical string matching the server's payload.
     */
    public static String generateCanonicalPayload(OfflineLeaseCertificate cert) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"branch_id\":\"").append(cert.branchId).append("\",");

        // sorted capabilities
        sb.append("\"capabilities_snapshot\":{");
        TreeMap<String, Boolean> sortedCaps = new TreeMap<>(cert.capabilities);
        boolean first = true;
        for (Map.Entry<String, Boolean> entry : sortedCaps.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }
        sb.append("},");

        sb.append("\"device_id\":\"").append(cert.deviceId).append("\",");
        sb.append("\"duration_hours\":").append(cert.durationHours).append(",");
        sb.append("\"expires_at\":\"").append(cert.expiresAtIso).append("\",");
        sb.append("\"issued_at\":\"").append(cert.issuedAtIso).append("\",");
        sb.append("\"lease_id\":\"").append(cert.leaseId).append("\",");
        sb.append("\"plan_tier\":\"").append(cert.planTier).append("\",");
        sb.append("\"policy_version\":\"").append(cert.policyVersion).append("\",");
        sb.append("\"role_snapshot\":\"").append(cert.roleSnapshot).append("\",");
        sb.append("\"tenant_id\":\"").append(cert.tenantId).append("\"");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Computes HMAC-SHA256 signature over string payload.
     */
    public static String computeHmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hmacBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Cryptographic HMAC-SHA256 computation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies cryptographic signature in constant time.
     */
    public static boolean verifySignature(OfflineLeaseCertificate cert, String signingKey) {
        if (cert == null || cert.signature == null || cert.signature.isEmpty() || signingKey == null) {
            return false;
        }
        String canonical = generateCanonicalPayload(cert);
        String expectedHex = computeHmacSha256(canonical, signingKey);
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                cert.signature.toLowerCase().getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Full offline verification against local hardware context and timestamp.
     */
    public static VerificationResult verifyOfflineLease(
            OfflineLeaseCertificate cert,
            String localTenantId,
            String localBranchId,
            String localDeviceId,
            Instant currentInstant,
            String signingKey) {

        if (cert == null) {
            return new VerificationResult(
                    false,
                    "NULL_CERTIFICATE",
                    0,
                    false,
                    "No Offline Lease Found",
                    "কোন অফলাইন ইজারা পাওয়া যায়নি",
                    "Connect terminal to internet to download an authorized lease certificate.",
                    "টার্মিনালে অনুমোদিত ইজারা ডাউনলোড করতে ইন্টারনেটে যুক্ত হোন।"
            );
        }

        // 1. Signature Verification
        if (signingKey != null && !signingKey.isEmpty()) {
            if (!verifySignature(cert, signingKey)) {
                return new VerificationResult(
                        false,
                        "INVALID_SIGNATURE",
                        0,
                        false,
                        "Tampered Lease Signature",
                        "অফলাইন ইজারার ক্রিপ্টোগ্রাফিক স্বাক্ষর অবৈধ",
                        "HMAC signature verification failed. The offline lease certificate cannot be trusted.",
                        "এইচএমএসি স্বাক্ষর যাচাইকরণ ব্যর্থ হয়েছে। অফলাইন ইজারাটি বিশ্বাসযোগ্য নয়।"
                );
            }
        }

        // 2. Hardware and Isolation Binding
        if (localTenantId != null && !cert.tenantId.equalsIgnoreCase(localTenantId.trim())) {
            return new VerificationResult(
                    false,
                    "TENANT_MISMATCH",
                    0,
                    false,
                    "Unauthorized Tenant Binding",
                    "অননুমোদিত ফার্মেসি প্রতিষ্ঠান",
                    "Lease belongs to a different tenant and cannot be loaded on this POS instance.",
                    "ইজারাটি ভিন্ন প্রতিষ্ঠানের এবং এই টার্মিনালে ব্যবহারযোগ্য নয়।"
            );
        }

        if (localBranchId != null && !cert.branchId.equalsIgnoreCase(localBranchId.trim())) {
            return new VerificationResult(
                    false,
                    "BRANCH_MISMATCH",
                    0,
                    false,
                    "Unauthorized Branch Terminal",
                    "অননুমোদিত শাখা টার্মিনাল",
                    "Lease is locked to another pharmacy branch location.",
                    "ইজারাটি অন্য শাখার জন্য নির্ধারিত।"
            );
        }

        if (localDeviceId != null && !cert.deviceId.equalsIgnoreCase(localDeviceId.trim())) {
            return new VerificationResult(
                    false,
                    "DEVICE_MISMATCH",
                    0,
                    false,
                    "Hardware Identity Mismatch",
                    "হার্ডওয়্যার পরিচিতি মেলেনি",
                    "Lease is cryptographically bound to a different POS workstation.",
                    "ইজারাটি অন্য পিওএস ওয়ার্কস্টেশনের সাথে যুক্ত।"
            );
        }

        // 3. Status checks
        if ("REVOKED".equalsIgnoreCase(cert.status)) {
            return new VerificationResult(
                    false,
                    "REVOKED",
                    0,
                    false,
                    "Offline Lease Revoked",
                    "অফলাইন ইজারা বাতিল করা হয়েছে",
                    "Terminal has been revoked by pharmacy owner. Reconnect online to investigate.",
                    "ফার্মেসি মালিক কর্তৃক এই টার্মিনালের অনুমোদন প্রত্যাহার করা হয়েছে।"
            );
        }

        // 4. Expiration check
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(cert.expiresAtIso);
        } catch (Exception e) {
            return new VerificationResult(
                    false,
                    "INVALID_EXPIRY_TIMESTAMP",
                    0,
                    false,
                    "Invalid Expiration Date",
                    "অবৈধ মেয়াদ উত্তীর্ণ তারিখ",
                    "Failed to parse expiration date.",
                    "মেয়াদ উত্তীর্ণের তারিখ বিশ্লেষণ করা যায়নি।"
            );
        }

        if (currentInstant.isAfter(expiresAt) || currentInstant.equals(expiresAt)) {
            return new VerificationResult(
                    false,
                    "EXPIRED",
                    0,
                    false,
                    "Offline Lease Expired — Reconnect to Renew",
                    "অফলাইন ইজারার মেয়াদ শেষ — রিনিউ করতে অনলাইনে যুক্ত হোন",
                    "The maximum allowed offline window has passed. Connect to cloud to continue sales.",
                    "সর্বোচ্চ অফলাইন সময়সীমা অতিক্রান্ত। বিক্রয় চালিয়ে যেতে ইন্টারনেটে যুক্ত হোন।"
            );
        }

        long secondsRemaining = Math.max(0, ChronoUnit.SECONDS.between(currentInstant, expiresAt));
        long hoursRemaining = Math.max(1, secondsRemaining / 3600);

        boolean checkoutDispensing = cert.capabilities.getOrDefault("CHECKOUT_DISPENSING", Boolean.TRUE);

        return new VerificationResult(
                true,
                "VALID",
                hoursRemaining,
                checkoutDispensing,
                "Signed Offline Lease Active (" + hoursRemaining + " Hours Remaining)",
                "স্বাক্ষরিত অফলাইন ইজারা সক্রিয় (অবশিষ্ট " + hoursRemaining + " ঘণ্টা)",
                "Local counter sales permitted offline. Records queued in outbox for automatic sync.",
                "অফলাইনে কাউন্টার বিক্রয় অনুমোদিত। সকল ডাটা স্বয়ংক্রিয় সিঙ্কের জন্য আউটবক্সে সংরক্ষিত রয়েছে।"
        );
    }
}
