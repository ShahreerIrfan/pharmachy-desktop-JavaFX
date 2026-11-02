package com.pharmacy.pos.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PosStaffOfflineAuthClient
 * Architecture Decision: ADR-0113 (P113).
 * Phase 06: Milestone M03 - Offline-First Desktop POS & Owner Visibility.
 *
 * Client-side integration for JavaFX Desktop POS authorizing offline pharmacy staff
 * without plaintext passwords, using salted credential hashes, signed role snapshots,
 * strict expiry, and risk-limited action boundaries.
 */
public class PosStaffOfflineAuthClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        OFFLINE,
        ERROR,
        PERMISSION_DENIED
    }

    public static final class RiskLimits {
        public final double maxOfflineDiscountPercent;
        public final double maxOfflineSaleAmount;
        public final boolean allowCreditSales;
        public final boolean requireSupervisorRefund;

        public RiskLimits(
                double maxOfflineDiscountPercent,
                double maxOfflineSaleAmount,
                boolean allowCreditSales,
                boolean requireSupervisorRefund) {
            this.maxOfflineDiscountPercent = maxOfflineDiscountPercent;
            this.maxOfflineSaleAmount = maxOfflineSaleAmount;
            this.allowCreditSales = allowCreditSales;
            this.requireSupervisorRefund = requireSupervisorRefund;
        }
    }

    public static final class StaffOfflineRecord {
        public final String authorizationId;
        public final String tenantId;
        public final String branchId;
        public final String staffId;
        public final String username;
        public final String fullName;
        public final String role;
        public final String deviceId;
        public final String credentialSalt;
        public final String credentialHash;
        public final List<String> permittedActions;
        public final List<String> deniedActions;
        public final RiskLimits riskLimits;
        public final Instant issuedAt;
        public final Instant expiresAt;
        public final String signature;
        public final boolean isRevoked;

        public StaffOfflineRecord(
                String authorizationId,
                String tenantId,
                String branchId,
                String staffId,
                String username,
                String fullName,
                String role,
                String deviceId,
                String credentialSalt,
                String credentialHash,
                List<String> permittedActions,
                List<String> deniedActions,
                RiskLimits riskLimits,
                Instant issuedAt,
                Instant expiresAt,
                String signature,
                boolean isRevoked) {
            this.authorizationId = authorizationId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.staffId = staffId;
            this.username = username;
            this.fullName = fullName;
            this.role = role;
            this.deviceId = deviceId;
            this.credentialSalt = credentialSalt;
            this.credentialHash = credentialHash;
            this.permittedActions = permittedActions != null ? List.copyOf(permittedActions) : List.of();
            this.deniedActions = deniedActions != null ? List.copyOf(deniedActions) : List.of();
            this.riskLimits = riskLimits;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
            this.signature = signature;
            this.isRevoked = isRevoked;
        }
    }

    public static final class OfflineVerificationResult {
        public final boolean isAuthorized;
        public final String staffId;
        public final String username;
        public final String role;
        public final List<String> permittedActions;
        public final RiskLimits riskLimits;
        public final UiStatus uiStatus;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public OfflineVerificationResult(
                boolean isAuthorized,
                String staffId,
                String username,
                String role,
                List<String> permittedActions,
                RiskLimits riskLimits,
                UiStatus uiStatus,
                String errorCode,
                String messageEn,
                String messageBn) {
            this.isAuthorized = isAuthorized;
            this.staffId = staffId;
            this.username = username;
            this.role = role;
            this.permittedActions = permittedActions != null ? List.copyOf(permittedActions) : List.of();
            this.riskLimits = riskLimits;
            this.uiStatus = uiStatus;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static OfflineVerificationResult success(StaffOfflineRecord record) {
            return new OfflineVerificationResult(
                    true,
                    record.staffId,
                    record.username,
                    record.role,
                    record.permittedActions,
                    record.riskLimits,
                    UiStatus.OFFLINE,
                    null,
                    "Offline staff authorization successful.",
                    "অফলাইন স্টাফ অনুমোদন সফল হয়েছে।"
            );
        }

        public static OfflineVerificationResult error(UiStatus status, String errorCode, String msgEn, String msgBn) {
            return new OfflineVerificationResult(
                    false, null, null, null, List.of(), null, status, errorCode, msgEn, msgBn
            );
        }
    }

    private final String localDeviceId;
    private final Map<String, StaffOfflineRecord> cachedRecords = new ConcurrentHashMap<>();

    public PosStaffOfflineAuthClient(String localDeviceId) {
        this.localDeviceId = localDeviceId;
    }

    public void cacheAuthorization(StaffOfflineRecord record) {
        if (record != null && record.staffId != null) {
            cachedRecords.put(record.staffId, record);
        }
    }

    public int getCachedRecordsCount() {
        return cachedRecords.size();
    }

    public static String computePbkdf2Hash(String pin, String saltHex) {
        try {
            byte[] salt = hexToBytes(saltHex);
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, 100_000, 256);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = skf.generateSecret(spec).getEncoded();
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute PBKDF2 credential hash", e);
        }
    }

    public OfflineVerificationResult verifyOffline(String staffId, String pin, Instant verificationTime) {
        if (staffId == null || staffId.isBlank() || pin == null || pin.isBlank()) {
            return OfflineVerificationResult.error(
                    UiStatus.ERROR,
                    "ERR_INVALID_OFFLINE_AUTH_INPUT",
                    "Missing staff ID or offline PIN.",
                    "স্টাফ আইডি অথবা অফলাইন পিন প্রদান করা হয়নি।"
            );
        }

        StaffOfflineRecord record = cachedRecords.get(staffId);
        if (record == null) {
            return OfflineVerificationResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_STAFF_NOT_FOUND_OFFLINE",
                    "No cached offline authorization found for this staff member.",
                    "এই স্টাফের জন্য কোনো সংরক্ষিত অফলাইন অনুমোদন পাওয়া যায়নি।"
            );
        }

        if (record.isRevoked) {
            return OfflineVerificationResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_STAFF_ROLE_DENIED",
                    "This offline staff authorization has been revoked.",
                    "এই অফলাইন স্টাফ অনুমোদনটি বাতিল করা হয়েছে।"
            );
        }

        // 1. Device check
        if (!Objects.equals(record.deviceId, this.localDeviceId)) {
            return OfflineVerificationResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_OFFLINE_DEVICE_MISMATCH",
                    "Enrolled device mismatch. Offline lease is bound to device " + record.deviceId,
                    "ডিভাইস অমিল। এই অফলাইন অনুমোদনটি অন্য ডিভাইসের জন্য নির্ধারিত।"
            );
        }

        // 2. Expiry check
        Instant now = verificationTime != null ? verificationTime : Instant.now();
        if (now.isAfter(record.expiresAt)) {
            return OfflineVerificationResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_OFFLINE_AUTH_EXPIRED",
                    "Offline authorization expired at " + record.expiresAt,
                    "অফলাইন অনুমোদনের মেয়াদ উত্তীর্ণ হয়েছে।"
            );
        }

        // 3. Salted hash check (Never plaintext)
        String derivedHash = computePbkdf2Hash(pin, record.credentialSalt);
        if (!MessageDigest.isEqual(derivedHash.getBytes(StandardCharsets.UTF_8), record.credentialHash.getBytes(StandardCharsets.UTF_8))) {
            return OfflineVerificationResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_INVALID_CREDENTIALS",
                    "Invalid offline PIN or passcode.",
                    "ভুল অফলাইন পিন বা পাসকোড।"
            );
        }

        return OfflineVerificationResult.success(record);
    }

    public boolean canPerformAction(String staffId, String action) {
        StaffOfflineRecord record = cachedRecords.get(staffId);
        if (record == null || record.isRevoked) {
            return false;
        }
        if (record.permittedActions.contains("*")) {
            return true;
        }
        if (record.deniedActions.contains(action)) {
            return false;
        }
        return record.permittedActions.contains(action);
    }

    public boolean isSaleAmountPermitted(String staffId, double amount) {
        StaffOfflineRecord record = cachedRecords.get(staffId);
        if (record == null || record.riskLimits == null) {
            return false;
        }
        return amount <= record.riskLimits.maxOfflineSaleAmount;
    }

    public boolean isDiscountPermitted(String staffId, double discountPercent) {
        StaffOfflineRecord record = cachedRecords.get(staffId);
        if (record == null || record.riskLimits == null) {
            return false;
        }
        return discountPercent <= record.riskLimits.maxOfflineDiscountPercent;
    }

    private static byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

