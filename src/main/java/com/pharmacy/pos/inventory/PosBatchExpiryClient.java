package com.pharmacy.pos.inventory;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * PosBatchExpiryClient
 * Architecture Decision: ADR-0172 (P172).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Client-side engine providing configurable batch expiry alert windows,
 * statutory DGDA hard expiration barriers, and sale cutoff guards.
 */
public class PosBatchExpiryClient {

    public enum ExpiryTier {
        EXPIRED("Expired (Illegal to Sell)", "মেয়াদোত্তীর্ণ (বিক্রয় নিষিদ্ধ)", "DANGER", true),
        CUTOFF_BLOCKED("Sale Cutoff Reached", "বিক্রয় কাট-অফ উত্তীর্ণ", "DANGER", true),
        QUARANTINE_DISPOSITION("Urgent Disposition", "জরুরি স্থানান্তর / ফেরত", "WARNING_HIGH", false),
        NEAR_EXPIRY_CRITICAL("Near Expiry", "আসন্ন মেয়াদোত্তীর্ণ", "WARNING_MEDIUM", false),
        EARLY_WARNING("Early Warning", "প্রাথমিক সতর্কতা", "INFO", false),
        NORMAL_SAFE("Normal Safe", "নিরাপদ মেয়াদকাল", "SAFE", false);

        public final String labelEn;
        public final String labelBn;
        public final String severity;
        public final boolean defaultBlocked;

        ExpiryTier(String labelEn, String labelBn, String severity, boolean defaultBlocked) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
            this.severity = severity;
            this.defaultBlocked = defaultBlocked;
        }
    }

    public static final class ExpiryPolicy {
        public final int warningLeadDays;
        public final int criticalLeadDays;
        public final int quarantineLeadDays;
        public final int saleCutoffLeadDays;
        public final boolean autoQuarantineOnCutoff;
        public final boolean allowManagerOverrideNearExpiry;

        public ExpiryPolicy(
                int warningLeadDays,
                int criticalLeadDays,
                int quarantineLeadDays,
                int saleCutoffLeadDays,
                boolean autoQuarantineOnCutoff,
                boolean allowManagerOverrideNearExpiry) {
            this.warningLeadDays = Math.max(1, warningLeadDays);
            this.criticalLeadDays = Math.max(1, criticalLeadDays);
            this.quarantineLeadDays = Math.max(0, quarantineLeadDays);
            this.saleCutoffLeadDays = Math.max(0, saleCutoffLeadDays);
            this.autoQuarantineOnCutoff = autoQuarantineOnCutoff;
            this.allowManagerOverrideNearExpiry = allowManagerOverrideNearExpiry;
        }

        public static ExpiryPolicy createDefault() {
            return new ExpiryPolicy(90, 30, 15, 7, true, false);
        }
    }

    public static final class BatchExpiryEvaluation {
        public final String batchId;
        public final String batchNumber;
        public final String productName;
        public final LocalDate expiryDate;
        public final long daysRemaining;
        public final ExpiryTier tier;
        public final boolean isSaleBlocked;
        public final boolean requiresManagerOverride;

        public BatchExpiryEvaluation(
                String batchId,
                String batchNumber,
                String productName,
                LocalDate expiryDate,
                long daysRemaining,
                ExpiryTier tier,
                boolean isSaleBlocked,
                boolean requiresManagerOverride) {
            this.batchId = batchId != null ? batchId : "";
            this.batchNumber = batchNumber != null ? batchNumber : "";
            this.productName = productName != null ? productName : "";
            this.expiryDate = expiryDate;
            this.daysRemaining = daysRemaining;
            this.tier = tier;
            this.isSaleBlocked = isSaleBlocked;
            this.requiresManagerOverride = requiresManagerOverride;
        }
    }

    public static final class ClientResponse<T> {
        public final boolean success;
        public final T data;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        private ClientResponse(boolean success, T data, String errorCode, String messageEn, String messageBn) {
            this.success = success;
            this.data = data;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static <T> ClientResponse<T> ok(T data, String messageEn, String messageBn) {
            return new ClientResponse<>(true, data, null, messageEn, messageBn);
        }

        public static <T> ClientResponse<T> failure(String errorCode, String messageEn, String messageBn) {
            return new ClientResponse<>(false, null, errorCode, messageEn, messageBn);
        }
    }

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final boolean isTenantSuspended;
    private ExpiryPolicy policy;

    public PosBatchExpiryClient(String tenantId, String branchId, String userRole, boolean isTenantSuspended) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.isTenantSuspended = isTenantSuspended;
        this.policy = ExpiryPolicy.createDefault();
    }

    public void setPolicy(ExpiryPolicy policy) {
        if (policy != null) {
            this.policy = policy;
        }
    }

    public ExpiryPolicy getPolicy() {
        return this.policy;
    }

    private boolean isRoleAuthorized() {
        return switch (this.userRole.toUpperCase()) {
            case "CASHIER", "STAFF_PHARMACIST", "BRANCH_MANAGER", "PHARMACY_OWNER", "INVENTORY_CLERK", "AUDITOR", "SUPERADMIN" -> true;
            default -> false;
        };
    }

    public BatchExpiryEvaluation evaluateBatch(
            String batchId,
            String batchNumber,
            String productName,
            LocalDate expiryDate,
            LocalDate asOfDate) {
        LocalDate today = asOfDate != null ? asOfDate : LocalDate.now();
        long days = ChronoUnit.DAYS.between(today, expiryDate);

        ExpiryTier tier;
        boolean isBlocked;
        boolean reqOverride = false;

        if (days <= 0) {
            tier = ExpiryTier.EXPIRED;
            isBlocked = true;
        } else if (days <= policy.saleCutoffLeadDays) {
            tier = ExpiryTier.CUTOFF_BLOCKED;
            isBlocked = true;
            reqOverride = policy.allowManagerOverrideNearExpiry;
        } else if (days <= policy.quarantineLeadDays) {
            tier = ExpiryTier.QUARANTINE_DISPOSITION;
            isBlocked = false;
        } else if (days <= policy.criticalLeadDays) {
            tier = ExpiryTier.NEAR_EXPIRY_CRITICAL;
            isBlocked = false;
        } else if (days <= policy.warningLeadDays) {
            tier = ExpiryTier.EARLY_WARNING;
            isBlocked = false;
        } else {
            tier = ExpiryTier.NORMAL_SAFE;
            isBlocked = false;
        }

        return new BatchExpiryEvaluation(batchId, batchNumber, productName, expiryDate, days, tier, isBlocked, reqOverride);
    }

    public ClientResponse<BatchExpiryEvaluation> verifyBatchForSale(
            BatchExpiryEvaluation eval,
            boolean isManagerAuthorized) {
        if (isTenantSuspended) {
            return ClientResponse.failure(
                    "ERR_TENANT_SUSPENDED",
                    "Tenant subscription is suspended.",
                    "গ্রাহক সাবস্ক্রিপশন স্থগিত রয়েছে।"
            );
        }
        if (!isRoleAuthorized()) {
            return ClientResponse.failure(
                    "ERR_ROLE_UNAUTHORIZED",
                    "Role is not authorized to verify batch expiry.",
                    "এই রোল ব্যাচ এক্সপায়ারি যাচাই করতে অননুমোদিত।"
            );
        }

        if (eval.tier == ExpiryTier.EXPIRED) {
            return ClientResponse.failure(
                    "ERR_BATCH_EXPIRED",
                    "Batch has expired. Sale is strictly illegal under DGDA regulations.",
                    "ওষুধটির মেয়াদ শেষ হয়ে গেছে। ডিজিডিএ নিয়ম অনুযায়ী এটি বিক্রয় সম্পূর্ণ নিষিদ্ধ।"
            );
        }

        if (eval.tier == ExpiryTier.CUTOFF_BLOCKED) {
            if (policy.allowManagerOverrideNearExpiry && isManagerAuthorized) {
                return ClientResponse.ok(
                        eval,
                        "Sale approved under authorized manager override.",
                        "ম্যানেজারের অনুমোদনে বিক্রয় সম্পন্ন করা হয়েছে।"
                );
            }
            return ClientResponse.failure(
                    "ERR_SALE_CUTOFF_BREACHED",
                    "Batch has breached sale cutoff (" + policy.saleCutoffLeadDays + " days). Dispensing barred.",
                    "ওষুধটি ব্রাঞ্চ বিক্রয় কাট-অফ অতিক্রম করেছে। বিক্রয় বন্ধ করা হয়েছে।"
            );
        }

        return ClientResponse.ok(eval, "Batch verified and approved for sale.", "ব্যাচটি বিক্রির জন্য নিরাপদ ও অনুমোদিত।");
    }
}

