package com.pharmacy.pos.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosDeviceRegistrationClient
 * Architecture Decision: ADR-0111 / P111
 * Phase 06: Milestone M03 - Offline-First Desktop POS & Owner Visibility.
 *
 * Client-side integration for JavaFX Desktop POS managing cryptographic hardware
 * device registration, branch assignment, public key enrollment (Ed25519/RSA-2048),
 * SHA-256 key fingerprinting, and maker-checker approval workflows.
 */
public class PosDeviceRegistrationClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        OFFLINE,
        ERROR,
        PERMISSION_DENIED
    }

    public enum DeviceRegistrationStatus {
        PENDING_APPROVAL,
        APPROVED,
        REJECTED,
        REVOKED
    }

    public enum KeyAlgorithm {
        ED25519,
        RSA_2048,
        ECDSA_P256
    }

    public static final class DeviceRegistrationRecord {
        public final String registrationId;
        public final String tenantId;
        public final String branchId;
        public final String branchName;
        public final String deviceId;
        public final String deviceLabel;
        public final String deviceType;
        public final String publicKeyPem;
        public final KeyAlgorithm keyAlgorithm;
        public final String keyFingerprint;
        public final String hardwareFingerprint;
        public final DeviceRegistrationStatus status;
        public final String requestedBy;
        public final String approvedBy;
        public final String approvedAt;
        public final String rejectedBy;
        public final String rejectedAt;
        public final String rejectionReason;
        public final String revokedBy;
        public final String revokedAt;
        public final String revocationReason;
        public final String approvalNotes;
        public final String createdAt;

        public DeviceRegistrationRecord(
                String registrationId,
                String tenantId,
                String branchId,
                String branchName,
                String deviceId,
                String deviceLabel,
                String deviceType,
                String publicKeyPem,
                KeyAlgorithm keyAlgorithm,
                String keyFingerprint,
                String hardwareFingerprint,
                DeviceRegistrationStatus status,
                String requestedBy,
                String approvedBy,
                String approvedAt,
                String rejectedBy,
                String rejectedAt,
                String rejectionReason,
                String revokedBy,
                String revokedAt,
                String revocationReason,
                String approvalNotes,
                String createdAt
        ) {
            this.registrationId = registrationId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.branchName = branchName;
            this.deviceId = deviceId;
            this.deviceLabel = deviceLabel;
            this.deviceType = deviceType;
            this.publicKeyPem = publicKeyPem;
            this.keyAlgorithm = keyAlgorithm;
            this.keyFingerprint = keyFingerprint;
            this.hardwareFingerprint = hardwareFingerprint;
            this.status = status;
            this.requestedBy = requestedBy;
            this.approvedBy = approvedBy;
            this.approvedAt = approvedAt;
            this.rejectedBy = rejectedBy;
            this.rejectedAt = rejectedAt;
            this.rejectionReason = rejectionReason;
            this.revokedBy = revokedBy;
            this.revokedAt = revokedAt;
            this.revocationReason = revocationReason;
            this.approvalNotes = approvalNotes;
            this.createdAt = createdAt;
        }
    }

    public static final class VerificationResult {
        public final boolean isTrusted;
        public final String status;
        public final String registrationId;
        public final String deviceId;
        public final String deviceLabel;
        public final String branchId;
        public final String branchName;
        public final String keyFingerprint;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public VerificationResult(
                boolean isTrusted,
                String status,
                String registrationId,
                String deviceId,
                String deviceLabel,
                String branchId,
                String branchName,
                String keyFingerprint,
                String errorCode,
                String messageEn,
                String messageBn
        ) {
            this.isTrusted = isTrusted;
            this.status = status;
            this.registrationId = registrationId;
            this.deviceId = deviceId;
            this.deviceLabel = deviceLabel;
            this.branchId = branchId;
            this.branchName = branchName;
            this.keyFingerprint = keyFingerprint;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static VerificationResult trusted(
                String registrationId,
                String deviceId,
                String deviceLabel,
                String branchId,
                String branchName,
                String keyFingerprint
        ) {
            return new VerificationResult(
                    true,
                    "APPROVED",
                    registrationId,
                    deviceId,
                    deviceLabel,
                    branchId,
                    branchName,
                    keyFingerprint,
                    null,
                    "Device is trusted and authorized for terminal operations.",
                    "ডিভাইসটি সফলভাবে যাচাইকৃত এবং টার্মিনাল ব্যবহারের জন্য অনুমোদিত।"
            );
        }

        public static VerificationResult rejected(String errorCode, String messageEn, String messageBn) {
            return new VerificationResult(
                    false,
                    "UNTRUSTED",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    errorCode,
                    messageEn,
                    messageBn
            );
        }
    }

    public static final class DeviceUiState {
        public final UiStatus uiStatus;
        public final String errorMessageEn;
        public final String errorMessageBn;
        public final List<DeviceRegistrationRecord> devices;
        public final DeviceRegistrationRecord selectedDevice;
        public final int offlineQueueCount;

        public DeviceUiState(
                UiStatus uiStatus,
                String errorMessageEn,
                String errorMessageBn,
                List<DeviceRegistrationRecord> devices,
                DeviceRegistrationRecord selectedDevice,
                int offlineQueueCount
        ) {
            this.uiStatus = uiStatus;
            this.errorMessageEn = errorMessageEn;
            this.errorMessageBn = errorMessageBn;
            this.devices = devices != null ? Collections.unmodifiableList(devices) : Collections.emptyList();
            this.selectedDevice = selectedDevice;
            this.offlineQueueCount = offlineQueueCount;
        }
    }

    private final String baseUrl;
    private final String tenantId;
    private final String userRole;
    private final String userBranchId;
    private final String username;

    private boolean isOffline = false;
    private final Map<String, DeviceRegistrationRecord> localCache = new ConcurrentHashMap<>();
    private final List<DeviceRegistrationRecord> offlineQueue = Collections.synchronizedList(new ArrayList<>());

    public PosDeviceRegistrationClient(
            String baseUrl,
            String tenantId,
            String userRole,
            String userBranchId,
            String username
    ) {
        this.baseUrl = baseUrl != null ? baseUrl : "http://localhost:8000/api/v2";
        this.tenantId = tenantId != null ? tenantId : "default-tenant";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userBranchId = userBranchId;
        this.username = username != null ? username : "operator";
    }

    public void importRegistration(DeviceRegistrationRecord record) {
        if (record != null && record.registrationId != null) {
            localCache.put(record.registrationId, record);
        }
    }

    public void setOffline(boolean offline) {
        this.isOffline = offline;
    }

    public boolean isOffline() {
        return isOffline;
    }

    public String computeFingerprint(String publicKeyPem) {
        if (publicKeyPem == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicKeyPem.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private boolean isOwnerOrSuperAdmin() {
        return "PHARMACY_OWNER".equals(userRole) || "SUPERADMIN".equals(userRole) || "SUPER_ADMIN".equals(userRole);
    }

    private void assertApprovalAuthority(DeviceRegistrationRecord record) {
        if (isOwnerOrSuperAdmin()) {
            return;
        }
        if ("BRANCH_MANAGER".equals(userRole)) {
            if (userBranchId != null && userBranchId.equals(record.branchId)) {
                return;
            }
            throw new SecurityException("ERR_CROSS_BRANCH_VIOLATION: Branch Manager cannot approve devices outside assigned branch.");
        }
        throw new SecurityException("ERR_PERMISSION_DENIED: Role " + userRole + " is not authorized to approve or manage devices.");
    }

    public DeviceRegistrationRecord registerDevice(
            String branchId,
            String deviceId,
            String deviceLabel,
            String publicKeyPem,
            KeyAlgorithm algorithm,
            String hardwareFingerprint,
            String macAddress
    ) {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            throw new IllegalArgumentException("deviceId is required");
        }
        if (deviceLabel == null || deviceLabel.trim().isEmpty()) {
            throw new IllegalArgumentException("deviceLabel is required");
        }
        if (publicKeyPem == null || publicKeyPem.trim().length() < 32) {
            throw new IllegalArgumentException("publicKeyPem must be at least 32 characters");
        }

        String fingerprint = computeFingerprint(publicKeyPem);
        String regId = "dreg-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String now = Instant.now().toString();

        DeviceRegistrationRecord record = new DeviceRegistrationRecord(
                regId,
                tenantId,
                branchId != null ? branchId : userBranchId,
                "Branch " + (branchId != null ? branchId : userBranchId),
                deviceId,
                deviceLabel,
                "DESKTOP_POS",
                publicKeyPem.trim(),
                algorithm != null ? algorithm : KeyAlgorithm.ED25519,
                fingerprint,
                hardwareFingerprint,
                DeviceRegistrationStatus.PENDING_APPROVAL,
                username,
                null, null,
                null, null, null,
                null, null, null,
                null,
                now
        );

        if (isOffline) {
            offlineQueue.add(record);
            localCache.put(record.registrationId, record);
            return record;
        }

        localCache.put(record.registrationId, record);
        return record;
    }

    public DeviceRegistrationRecord approveDevice(String registrationId, String notes) {
        DeviceRegistrationRecord record = localCache.get(registrationId);
        if (record == null) {
            throw new NoSuchElementException("Device registration " + registrationId + " not found.");
        }
        assertApprovalAuthority(record);

        String now = Instant.now().toString();
        DeviceRegistrationRecord updated = new DeviceRegistrationRecord(
                record.registrationId,
                record.tenantId,
                record.branchId,
                record.branchName,
                record.deviceId,
                record.deviceLabel,
                record.deviceType,
                record.publicKeyPem,
                record.keyAlgorithm,
                record.keyFingerprint,
                record.hardwareFingerprint,
                DeviceRegistrationStatus.APPROVED,
                record.requestedBy,
                username,
                now,
                null, null, null,
                null, null, null,
                notes,
                record.createdAt
        );
        localCache.put(registrationId, updated);
        return updated;
    }

    public DeviceRegistrationRecord rejectDevice(String registrationId, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_REASON_REQUIRED: A rejection reason is required.");
        }
        DeviceRegistrationRecord record = localCache.get(registrationId);
        if (record == null) {
            throw new NoSuchElementException("Device registration " + registrationId + " not found.");
        }
        assertApprovalAuthority(record);

        String now = Instant.now().toString();
        DeviceRegistrationRecord updated = new DeviceRegistrationRecord(
                record.registrationId,
                record.tenantId,
                record.branchId,
                record.branchName,
                record.deviceId,
                record.deviceLabel,
                record.deviceType,
                record.publicKeyPem,
                record.keyAlgorithm,
                record.keyFingerprint,
                record.hardwareFingerprint,
                DeviceRegistrationStatus.REJECTED,
                record.requestedBy,
                null, null,
                username,
                now,
                reason.trim(),
                null, null, null,
                null,
                record.createdAt
        );
        localCache.put(registrationId, updated);
        return updated;
    }

    public DeviceRegistrationRecord revokeDevice(String registrationId, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_REASON_REQUIRED: A revocation reason is required.");
        }
        DeviceRegistrationRecord record = localCache.get(registrationId);
        if (record == null) {
            throw new NoSuchElementException("Device registration " + registrationId + " not found.");
        }
        assertApprovalAuthority(record);

        String now = Instant.now().toString();
        DeviceRegistrationRecord updated = new DeviceRegistrationRecord(
                record.registrationId,
                record.tenantId,
                record.branchId,
                record.branchName,
                record.deviceId,
                record.deviceLabel,
                record.deviceType,
                record.publicKeyPem,
                record.keyAlgorithm,
                record.keyFingerprint,
                record.hardwareFingerprint,
                DeviceRegistrationStatus.REVOKED,
                record.requestedBy,
                record.approvedBy,
                record.approvedAt,
                record.rejectedBy,
                record.rejectedAt,
                record.rejectionReason,
                username,
                now,
                reason.trim(),
                record.approvalNotes,
                record.createdAt
        );
        localCache.put(registrationId, updated);
        return updated;
    }

    public VerificationResult verifyDevice(String deviceId, String publicKeyPem) {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            return VerificationResult.rejected(
                    "MISSING_DEVICE_ID",
                    "Device ID is required.",
                    "ডিভাইস আইডি প্রদান করা আবশ্যক।"
            );
        }

        DeviceRegistrationRecord found = null;
        for (DeviceRegistrationRecord rec : localCache.values()) {
            if (deviceId.equals(rec.deviceId)) {
                found = rec;
                break;
            }
        }

        if (found == null) {
            return VerificationResult.rejected(
                    "ERR_DEVICE_NOT_FOUND",
                    "Device '" + deviceId + "' is not registered with this pharmacy.",
                    "ডিভাইস '" + deviceId + "' এই ফার্মেসিতে নিবন্ধিত নয়।"
            );
        }

        if (found.status == DeviceRegistrationStatus.PENDING_APPROVAL) {
            return VerificationResult.rejected(
                    "ERR_DEVICE_NOT_APPROVED",
                    "Device '" + deviceId + "' is pending manager/owner approval.",
                    "ডিভাইস '" + deviceId + "' ব্যবস্থাপকের অনুমোদনের অপেক্ষায় রয়েছে।"
            );
        }

        if (found.status == DeviceRegistrationStatus.REJECTED) {
            return VerificationResult.rejected(
                    "ERR_DEVICE_REJECTED",
                    "Device '" + deviceId + "' registration was rejected: " + found.rejectionReason,
                    "ডিভাইস '" + deviceId + "'-এর রেজিস্ট্রেশন প্রত্যাখ্যান করা হয়েছে।"
            );
        }

        if (found.status == DeviceRegistrationStatus.REVOKED) {
            return VerificationResult.rejected(
                    "ERR_DEVICE_REVOKED",
                    "Device '" + deviceId + "' cryptographic trust has been revoked: " + found.revocationReason,
                    "ডিভাইস '" + deviceId + "'-এর ক্রিপ্টোগ্রাফিক অনুমোদন বাতিল করা হয়েছে।"
            );
        }

        if (publicKeyPem != null && !publicKeyPem.trim().isEmpty()) {
            String fp = computeFingerprint(publicKeyPem);
            if (!fp.equals(found.keyFingerprint)) {
                return VerificationResult.rejected(
                        "ERR_DEVICE_KEY_MISMATCH",
                        "Cryptographic public key does not match enrolled device key material.",
                        "প্রদানকৃত পাবলিক কি নিবন্ধিত ডিভাইসের কি ম্যাটেরিয়ালের সাথে মিলছে না।"
                );
            }
        }

        return VerificationResult.trusted(
                found.registrationId,
                found.deviceId,
                found.deviceLabel,
                found.branchId,
                found.branchName,
                found.keyFingerprint
        );
    }

    public List<DeviceRegistrationRecord> listRegistrations(String branchFilter, DeviceRegistrationStatus statusFilter) {
        List<DeviceRegistrationRecord> results = new ArrayList<>();
        for (DeviceRegistrationRecord rec : localCache.values()) {
            if (branchFilter != null && !branchFilter.isEmpty() && !branchFilter.equals(rec.branchId)) {
                continue;
            }
            if (statusFilter != null && rec.status != statusFilter) {
                continue;
            }
            results.add(rec);
        }
        return results;
    }

    public DeviceRegistrationRecord updateDeviceLabel(String registrationId, String newLabel) {
        if (newLabel == null || newLabel.trim().isEmpty()) {
            throw new IllegalArgumentException("newLabel cannot be empty");
        }
        DeviceRegistrationRecord record = localCache.get(registrationId);
        if (record == null) {
            throw new NoSuchElementException("Device registration " + registrationId + " not found.");
        }
        assertApprovalAuthority(record);

        DeviceRegistrationRecord updated = new DeviceRegistrationRecord(
                record.registrationId,
                record.tenantId,
                record.branchId,
                record.branchName,
                record.deviceId,
                newLabel.trim(),
                record.deviceType,
                record.publicKeyPem,
                record.keyAlgorithm,
                record.keyFingerprint,
                record.hardwareFingerprint,
                record.status,
                record.requestedBy,
                record.approvedBy,
                record.approvedAt,
                record.rejectedBy,
                record.rejectedAt,
                record.rejectionReason,
                record.revokedBy,
                record.revokedAt,
                record.revocationReason,
                record.approvalNotes,
                record.createdAt
        );
        localCache.put(registrationId, updated);
        return updated;
    }

    public int syncOfflineRegistrations() {
        int synced = offlineQueue.size();
        offlineQueue.clear();
        return synced;
    }

    public DeviceUiState getUiState(String branchFilter, DeviceRegistrationStatus statusFilter) {
        if (isOffline) {
            List<DeviceRegistrationRecord> cached = listRegistrations(branchFilter, statusFilter);
            return new DeviceUiState(
                    UiStatus.OFFLINE,
                    "Running in offline mode. Registration cached locally.",
                    "অফলাইন মোড সক্রিয় রয়েছে। রেজিস্ট্রেশন লোকালি সংরক্ষিত আছে।",
                    cached,
                    null,
                    offlineQueue.size()
            );
        }

        List<DeviceRegistrationRecord> items = listRegistrations(branchFilter, statusFilter);
        if (items.isEmpty()) {
            return new DeviceUiState(
                    UiStatus.EMPTY,
                    null,
                    null,
                    Collections.emptyList(),
                    null,
                    0
            );
        }

        return new DeviceUiState(
                UiStatus.SUCCESS,
                null,
                null,
                items,
                items.get(0),
                0
        );
    }
}
