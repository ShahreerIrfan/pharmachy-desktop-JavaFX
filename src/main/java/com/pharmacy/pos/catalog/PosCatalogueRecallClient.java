package com.pharmacy.pos.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosCatalogueRecallClient
 * Architecture Decision: ADR-0137 (P137).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * JavaFX Desktop POS Client for Discontinued & Recalled Catalogue Governance:
 * Safety Recalls, Market Withdrawals, POS Dispensing Blocks, and Historical Integrity.
 */
public class PosCatalogueRecallClient {

    public enum UiStatus {
        SUCCESS,
        ERROR,
        PERMISSION_DENIED,
        EMPTY,
        OFFLINE
    }

    public enum LifecycleStatus {
        ACTIVE,
        DISCONTINUED,
        RECALLED,
        WITHDRAWN,
        QUARANTINED,
        LIFTED
    }

    public enum RecallScope {
        NATIONWIDE_ALL_BATCHES,
        SPECIFIC_BATCHES,
        TENANT_QUARANTINE
    }

    public static final class RecallNoticeRecord {
        public final String noticeId;
        public final String tenantId;
        public final String productId;
        public final String targetName;
        public LifecycleStatus lifecycleStatus;
        public final RecallScope recallScope;
        public final List<String> affectedBatchNumbers;
        public final String regulatoryAuthority;
        public final String noticeNumber;
        public final String reason;
        public final Instant effectiveDate;
        public String actionRequired;
        public boolean isActive;
        public final boolean isDispensingBlocked;
        public int versionNumber;
        public String immutableHash;
        public final Instant createdAt;

        public RecallNoticeRecord(
                String noticeId,
                String tenantId,
                String productId,
                String targetName,
                LifecycleStatus lifecycleStatus,
                RecallScope recallScope,
                List<String> affectedBatchNumbers,
                String regulatoryAuthority,
                String noticeNumber,
                String reason,
                Instant effectiveDate,
                String actionRequired,
                boolean isActive,
                int versionNumber,
                String immutableHash,
                Instant createdAt) {
            this.noticeId = noticeId;
            this.tenantId = tenantId != null ? tenantId : "platform";
            this.productId = productId;
            this.targetName = targetName != null ? targetName : "";
            this.lifecycleStatus = lifecycleStatus;
            this.recallScope = recallScope;
            this.affectedBatchNumbers = affectedBatchNumbers != null ? new ArrayList<>(affectedBatchNumbers) : new ArrayList<>();
            this.regulatoryAuthority = regulatoryAuthority != null ? regulatoryAuthority : "";
            this.noticeNumber = noticeNumber != null ? noticeNumber : "";
            this.reason = reason != null ? reason : "";
            this.effectiveDate = effectiveDate != null ? effectiveDate : Instant.now();
            this.actionRequired = actionRequired != null ? actionRequired : "";
            this.isActive = isActive;
            this.isDispensingBlocked = isActive && (lifecycleStatus == LifecycleStatus.RECALLED
                    || lifecycleStatus == LifecycleStatus.WITHDRAWN
                    || lifecycleStatus == LifecycleStatus.QUARANTINED);
            this.versionNumber = versionNumber;
            this.immutableHash = immutableHash;
            this.createdAt = createdAt != null ? createdAt : Instant.now();
        }
    }

    public static final class DispenseCheckResult {
        public final boolean blocked;
        public final LifecycleStatus lifecycleStatus;
        public final String noticeNumber;
        public final String reason;
        public final String actionRequired;
        public final List<String> affectedBatchNumbers;

        public DispenseCheckResult(
                boolean blocked,
                LifecycleStatus lifecycleStatus,
                String noticeNumber,
                String reason,
                String actionRequired,
                List<String> affectedBatchNumbers) {
            this.blocked = blocked;
            this.lifecycleStatus = lifecycleStatus;
            this.noticeNumber = noticeNumber != null ? noticeNumber : "";
            this.reason = reason != null ? reason : "";
            this.actionRequired = actionRequired != null ? actionRequired : "";
            this.affectedBatchNumbers = affectedBatchNumbers != null ? new ArrayList<>(affectedBatchNumbers) : new ArrayList<>();
        }
    }

    public static final class ClientResult<T> {
        public final UiStatus status;
        public final T data;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        private ClientResult(UiStatus status, T data, String errorCode, String messageEn, String messageBn) {
            this.status = status;
            this.data = data;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static <T> ClientResult<T> success(T data) {
            return new ClientResult<>(UiStatus.SUCCESS, data, null, "Operation completed successfully.", "অপারেশন সফলভাবে সম্পন্ন হয়েছে।");
        }

        public static <T> ClientResult<T> empty(String en, String bn) {
            return new ClientResult<>(UiStatus.EMPTY, null, "EMPTY_RESULT", en, bn);
        }

        public static <T> ClientResult<T> offline(T data) {
            return new ClientResult<>(UiStatus.OFFLINE, data, "STATUS_OFFLINE_CACHE", "Operating in offline cached mode.", "অফলাইন ক্যাশড মোড সক্রিয়।");
        }

        public static <T> ClientResult<T> permissionDenied(String code, String en, String bn) {
            return new ClientResult<>(UiStatus.PERMISSION_DENIED, null, code, en, bn);
        }

        public static <T> ClientResult<T> error(String code, String en, String bn) {
            return new ClientResult<>(UiStatus.ERROR, null, code, en, bn);
        }
    }

    private final Map<String, RecallNoticeRecord> noticeStore = new ConcurrentHashMap<>();
    private boolean isOffline = false;

    public void setOffline(boolean offline) {
        this.isOffline = offline;
    }

    public boolean isOffline() {
        return isOffline;
    }

    private boolean isAuthorizedManager(String role) {
        if (role == null) return false;
        String r = role.trim().toUpperCase();
        return r.equals("SUPERADMIN") || r.equals("PLATFORM_OPERATOR") ||
                r.equals("OWNER") || r.equals("PHARMACY_OWNER") ||
                r.equals("MANAGER") || r.equals("BRANCH_MANAGER") ||
                r.equals("PHARMACIST") || r.equals("STAFF_PHARMACIST");
    }

    public ClientResult<RecallNoticeRecord> publishNotice(
            String tenantId,
            String productId,
            String targetName,
            LifecycleStatus lifecycleStatus,
            RecallScope recallScope,
            List<String> affectedBatchNumbers,
            String regulatoryAuthority,
            String noticeNumber,
            String reason,
            String actionRequired,
            String userRole) {

        if (!isAuthorizedManager(userRole)) {
            return ClientResult.permissionDenied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot publish, modify, or lift recall notices.",
                    "ক্যাশিয়াররা রিকল নোটিশ প্রকাশ, পরিবর্তন বা প্রত্যাহার করতে পারবেন না।"
            );
        }

        if (productId == null || productId.trim().isEmpty()) {
            return ClientResult.error("ERR_INVALID_PRODUCT", "Product ID is required.", "ওষুধের আইডি প্রয়োজন।");
        }

        String cleanNoticeNum = noticeNumber != null ? noticeNumber.trim() : "";
        if (cleanNoticeNum.length() < 3) {
            return ClientResult.error("ERR_INVALID_NOTICE_NUMBER", "Notice number must be at least 3 characters.", "নোটিশ নম্বর ন্যূনতম ৩ অক্ষর হতে হবে।");
        }

        String cleanReason = reason != null ? reason.trim() : "";
        if (cleanReason.length() < 5) {
            return ClientResult.error("ERR_INVALID_REASON", "Reason must be at least 5 characters.", "রিকলের কারণ ন্যূনতম ৫ অক্ষর হতে হবে।");
        }

        String effTenant = tenantId != null ? tenantId.trim() : "platform";
        String noticeId = "rec-pos-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String hash = computeHash(noticeId, effTenant, productId, lifecycleStatus.name(), cleanNoticeNum, 1);

        RecallNoticeRecord record = new RecallNoticeRecord(
                noticeId,
                effTenant,
                productId,
                targetName,
                lifecycleStatus,
                recallScope != null ? recallScope : RecallScope.NATIONWIDE_ALL_BATCHES,
                affectedBatchNumbers,
                regulatoryAuthority,
                cleanNoticeNum,
                cleanReason,
                Instant.now(),
                actionRequired,
                true,
                1,
                hash,
                Instant.now()
        );

        noticeStore.put(noticeId, record);

        if (isOffline) {
            return ClientResult.offline(record);
        }
        return ClientResult.success(record);
    }

    public ClientResult<DispenseCheckResult> checkRecallStatus(String tenantId, String productId, String batchNumber) {
        if (productId == null || productId.trim().isEmpty()) {
            return ClientResult.empty("No product specified.", "কোন ওষুধ নির্দিষ্ট করা হয়নি।");
        }

        String cleanProd = productId.trim();
        String cleanBatch = batchNumber != null ? batchNumber.trim() : "";

        for (RecallNoticeRecord r : noticeStore.values()) {
            if (!r.isActive) continue;
            if (tenantId != null && !r.tenantId.equals("platform") && !r.tenantId.equals(tenantId)) {
                continue;
            }
            if (!r.productId.equals(cleanProd)) continue;

            if (r.isDispensingBlocked) {
                if (r.recallScope == RecallScope.SPECIFIC_BATCHES) {
                    if (!cleanBatch.isEmpty()) {
                        for (String b : r.affectedBatchNumbers) {
                            if (b.equalsIgnoreCase(cleanBatch)) {
                                DispenseCheckResult res = new DispenseCheckResult(
                                        true, r.lifecycleStatus, r.noticeNumber, r.reason, r.actionRequired, r.affectedBatchNumbers
                                );
                                return isOffline ? ClientResult.offline(res) : ClientResult.success(res);
                            }
                        }
                    }
                } else {
                    DispenseCheckResult res = new DispenseCheckResult(
                            true, r.lifecycleStatus, r.noticeNumber, r.reason, r.actionRequired, r.affectedBatchNumbers
                    );
                    return isOffline ? ClientResult.offline(res) : ClientResult.success(res);
                }
            } else if (r.lifecycleStatus == LifecycleStatus.DISCONTINUED) {
                DispenseCheckResult res = new DispenseCheckResult(
                        false, LifecycleStatus.DISCONTINUED, r.noticeNumber, r.reason, r.actionRequired, Collections.emptyList()
                );
                return isOffline ? ClientResult.offline(res) : ClientResult.success(res);
            }
        }

        DispenseCheckResult safe = new DispenseCheckResult(
                false, LifecycleStatus.ACTIVE, "", "", "", Collections.emptyList()
        );
        return isOffline ? ClientResult.offline(safe) : ClientResult.success(safe);
    }

    public ClientResult<DispenseCheckResult> validateDispense(String tenantId, String productId, String batchNumber) {
        ClientResult<DispenseCheckResult> check = checkRecallStatus(tenantId, productId, batchNumber);
        if (check.data != null && check.data.blocked) {
            return ClientResult.error(
                    "ERR_PRODUCT_RECALLED_DISPENSING_BLOCKED",
                    "Dispensing blocked: Product or batch has an active statutory safety recall (" + check.data.noticeNumber + ").",
                    "ওষুধ প্রদান নিষিদ্ধ: পণ্য বা ব্যাচটিতে সক্রিয় সরকারি সেফটি রিকল রয়েছে।"
            );
        }
        return check;
    }

    public ClientResult<RecallNoticeRecord> liftNotice(String noticeId, String actorTenantId, String userRole, String resolutionNotes) {
        if (!isAuthorizedManager(userRole)) {
            return ClientResult.permissionDenied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot lift recall notices.",
                    "ক্যাশিয়াররা রিকল নোটিশ প্রত্যাহার করতে পারবেন না।"
            );
        }

        RecallNoticeRecord r = noticeStore.get(noticeId);
        if (r == null) {
            return ClientResult.error("ERR_NOTICE_NOT_FOUND", "Recall notice not found.", "রিকল নোটিশ পাওয়া যায়নি।");
        }

        boolean isSuperadmin = userRole.trim().equalsIgnoreCase("SUPERADMIN");
        if (!isSuperadmin && !r.tenantId.equals("platform") && !r.tenantId.equals(actorTenantId)) {
            return ClientResult.permissionDenied(
                    "ERR_CROSS_TENANT_ACCESS",
                    "Cannot lift recall notice belonging to another tenant.",
                    "অন্য ফার্মেসির রিকল নোটিশ প্রত্যাহার করা নিষিদ্ধ।"
            );
        }

        r.isActive = false;
        r.lifecycleStatus = LifecycleStatus.LIFTED;
        r.actionRequired = "RECALL LIFTED: " + (resolutionNotes != null ? resolutionNotes.trim() : "");
        r.versionNumber += 1;
        r.immutableHash = computeHash(r.noticeId, r.tenantId, r.productId, r.lifecycleStatus.name(), r.noticeNumber, r.versionNumber);

        if (isOffline) {
            return ClientResult.offline(r);
        }
        return ClientResult.success(r);
    }

    private String computeHash(String noticeId, String tenantId, String productId, String status, String noticeNum, int version) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = String.format("%s|%s|%s|%s|%s|%d", noticeId, tenantId, productId, status, noticeNum, version);
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
