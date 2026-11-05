package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * PosBatchMergeClient
 * Architecture Decision: ADR-0178 (P178).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Desktop POS client enforcing clinical and regulatory safety guards
 * against merging incompatible pharmaceutical batches.
 */
public class PosBatchMergeClient {

    public static final class BatchDto {
        public final String batchId;
        public final String tenantId;
        public final String branchId;
        public final String productId;
        public final String productName;
        public final String batchNumber;
        public final LocalDate expiryDate;
        public int quantityBaseUnits;
        public int reservedQuantityBaseUnits;
        public BigDecimal costPerUnit;
        public String status;
        public boolean isQuarantined;

        public BatchDto(
                String batchId,
                String tenantId,
                String branchId,
                String productId,
                String productName,
                String batchNumber,
                LocalDate expiryDate,
                int quantityBaseUnits,
                int reservedQuantityBaseUnits,
                BigDecimal costPerUnit,
                String status,
                boolean isQuarantined) {
            this.batchId = batchId != null ? batchId : "";
            this.tenantId = tenantId != null ? tenantId : "";
            this.branchId = branchId != null ? branchId : "";
            this.productId = productId != null ? productId : "";
            this.productName = productName != null ? productName : "";
            this.batchNumber = batchNumber != null ? batchNumber.trim().toUpperCase() : "";
            this.expiryDate = expiryDate;
            this.quantityBaseUnits = quantityBaseUnits;
            this.reservedQuantityBaseUnits = reservedQuantityBaseUnits;
            this.costPerUnit = costPerUnit != null ? costPerUnit.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.status = status != null ? status : "ACTIVE";
            this.isQuarantined = isQuarantined;
        }

        public int getAvailableQuantityBaseUnits() {
            return Math.max(0, quantityBaseUnits - reservedQuantityBaseUnits);
        }
    }

    public static final class BatchMergeRecordDto {
        public final String mergeId;
        public final String tenantId;
        public final String branchId;
        public final String productId;
        public final String productName;
        public final String sourceBatchId;
        public final String targetBatchId;
        public final String batchNumber;
        public final LocalDate expiryDate;
        public final int mergedQuantityBaseUnits;
        public final BigDecimal sourceCostPerUnitBefore;
        public final BigDecimal targetCostPerUnitBefore;
        public final BigDecimal targetCostPerUnitAfter;
        public final String reason;
        public final String mergedBy;
        public boolean isReversed;
        public String reversalReason;
        public Instant reversedAt;
        public final Instant createdAt;

        public BatchMergeRecordDto(
                String mergeId,
                String tenantId,
                String branchId,
                String productId,
                String productName,
                String sourceBatchId,
                String targetBatchId,
                String batchNumber,
                LocalDate expiryDate,
                int mergedQuantityBaseUnits,
                BigDecimal sourceCostPerUnitBefore,
                BigDecimal targetCostPerUnitBefore,
                BigDecimal targetCostPerUnitAfter,
                String reason,
                String mergedBy,
                boolean isReversed,
                String reversalReason,
                Instant reversedAt,
                Instant createdAt) {
            this.mergeId = mergeId != null ? mergeId : "";
            this.tenantId = tenantId != null ? tenantId : "";
            this.branchId = branchId != null ? branchId : "";
            this.productId = productId != null ? productId : "";
            this.productName = productName != null ? productName : "";
            this.sourceBatchId = sourceBatchId != null ? sourceBatchId : "";
            this.targetBatchId = targetBatchId != null ? targetBatchId : "";
            this.batchNumber = batchNumber != null ? batchNumber : "";
            this.expiryDate = expiryDate;
            this.mergedQuantityBaseUnits = mergedQuantityBaseUnits;
            this.sourceCostPerUnitBefore = sourceCostPerUnitBefore != null ? sourceCostPerUnitBefore.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.targetCostPerUnitBefore = targetCostPerUnitBefore != null ? targetCostPerUnitBefore.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.targetCostPerUnitAfter = targetCostPerUnitAfter != null ? targetCostPerUnitAfter.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.reason = reason != null ? reason : "";
            this.mergedBy = mergedBy != null ? mergedBy : "";
            this.isReversed = isReversed;
            this.reversalReason = reversalReason;
            this.reversedAt = reversedAt;
            this.createdAt = createdAt != null ? createdAt : Instant.now();
        }
    }

    public static final class ValidationResult {
        public final boolean canMerge;
        public final List<String> blockers;
        public final int projectedTargetQuantity;
        public final BigDecimal projectedTargetCostPerUnit;

        public ValidationResult(
                boolean canMerge,
                List<String> blockers,
                int projectedTargetQuantity,
                BigDecimal projectedTargetCostPerUnit) {
            this.canMerge = canMerge;
            this.blockers = blockers != null ? blockers : new ArrayList<>();
            this.projectedTargetQuantity = projectedTargetQuantity;
            this.projectedTargetCostPerUnit = projectedTargetCostPerUnit != null ? projectedTargetCostPerUnit.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
    }

    public static final class ClientResult<T> {
        public final boolean success;
        public final T data;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        private ClientResult(boolean success, T data, String errorCode, String messageEn, String messageBn) {
            this.success = success;
            this.data = data;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static <T> ClientResult<T> ok(T data, String msgEn, String msgBn) {
            return new ClientResult<>(true, data, null, msgEn, msgBn);
        }

        public static <T> ClientResult<T> error(String code, String msgEn, String msgBn) {
            return new ClientResult<>(false, null, code, msgEn, msgBn);
        }
    }

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, BatchMergeRecordDto> mergeStore;

    public PosBatchMergeClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline) {
        this(tenantId, branchId, userRole, userId, isOffline, new HashMap<>());
    }

    public PosBatchMergeClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, BatchMergeRecordDto> sharedStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "";
        this.userId = userId != null ? userId : "";
        this.isOffline = isOffline;
        this.mergeStore = sharedStore != null ? sharedStore : new HashMap<>();
    }

    private boolean isAuthorizedManager() {
        return "BRANCH_MANAGER".equalsIgnoreCase(userRole)
                || "PHARMACY_OWNER".equalsIgnoreCase(userRole)
                || "SUPERADMIN".equalsIgnoreCase(userRole);
    }

    /**
     * Inspects compatibility constraints between two batches without modifying data.
     */
    public ValidationResult validateMergeCompatibility(BatchDto source, BatchDto target) {
        List<String> blockers = new ArrayList<>();

        if (source == null || target == null) {
            blockers.add("ERR_BATCH_NOT_FOUND");
            return new ValidationResult(false, blockers, 0, BigDecimal.ZERO);
        }

        if (source.batchId.equals(target.batchId)) {
            blockers.add("ERR_CANNOT_MERGE_SAME_BATCH");
        }

        if (!source.tenantId.equals(target.tenantId)) {
            blockers.add("ERR_CROSS_TENANT_ACCESS");
        }

        if (!source.branchId.equals(target.branchId)) {
            blockers.add("ERR_BRANCH_MISMATCH");
        }

        if (!source.productId.equals(target.productId)) {
            blockers.add("ERR_PRODUCT_MISMATCH");
        }

        if (!source.batchNumber.equalsIgnoreCase(target.batchNumber)) {
            blockers.add("ERR_MANUFACTURER_BATCH_MISMATCH");
        }

        if (source.expiryDate != null && target.expiryDate != null && !source.expiryDate.equals(target.expiryDate)) {
            blockers.add("ERR_EXPIRY_DATE_MISMATCH");
        }

        LocalDate today = LocalDate.now();
        if (source.expiryDate != null && !source.expiryDate.isAfter(today)) {
            blockers.add("ERR_SOURCE_BATCH_EXPIRED");
        }
        if (target.expiryDate != null && !target.expiryDate.isAfter(today)) {
            blockers.add("ERR_TARGET_BATCH_EXPIRED");
        }

        if ("RECALLED".equalsIgnoreCase(source.status) || "RECALLED".equalsIgnoreCase(target.status)) {
            blockers.add("ERR_BATCH_RECALLED");
        }

        if (source.isQuarantined || target.isQuarantined || "QUARANTINED".equalsIgnoreCase(source.status) || "QUARANTINED".equalsIgnoreCase(target.status)) {
            blockers.add("ERR_BATCH_QUARANTINED");
        }

        if (source.quantityBaseUnits <= 0) {
            blockers.add("ERR_SOURCE_BATCH_EMPTY");
        }

        if (source.reservedQuantityBaseUnits > 0) {
            blockers.add("ERR_SOURCE_BATCH_HAS_RESERVATIONS");
        }

        boolean canMerge = blockers.isEmpty();
        int projectedQty = target.quantityBaseUnits + source.quantityBaseUnits;

        BigDecimal projectedCost = target.costPerUnit;
        if (projectedQty > 0) {
            BigDecimal targetTotal = target.costPerUnit.multiply(BigDecimal.valueOf(target.quantityBaseUnits));
            BigDecimal sourceTotal = source.costPerUnit.multiply(BigDecimal.valueOf(source.quantityBaseUnits));
            projectedCost = targetTotal.add(sourceTotal).divide(BigDecimal.valueOf(projectedQty), 4, RoundingMode.HALF_UP);
        }

        return new ValidationResult(canMerge, blockers, projectedQty, projectedCost);
    }

    /**
     * Executes an audited batch merge consolidating source into target.
     */
    public ClientResult<BatchMergeRecordDto> executeMerge(
            BatchDto source,
            BatchDto target,
            String reason) {

        if (!isAuthorizedManager()) {
            return ClientResult.error("ERR_PERMISSION_DENIED",
                    "Batch consolidation and merge authorization requires a branch manager or owner.",
                    "ব্যাচ একত্রীকরণ কেবল ব্রাঞ্চ ম্যানেজার বা মালিকের অনুমোদনসাপেক্ষ।");
        }

        if (reason == null || reason.trim().length() < 10) {
            return ClientResult.error("ERR_AUDIT_REASON_REQUIRED",
                    "A valid reason of at least 10 characters is mandatory for batch consolidation.",
                    "ব্যাচ একত্রীকরণের জন্য ন্যূনতম ১০ অক্ষরের সুনির্দিষ্ট কারণ উল্লেখ আবশ্যক।");
        }

        ValidationResult val = validateMergeCompatibility(source, target);
        if (!val.canMerge) {
            String firstBlocker = val.blockers.get(0);
            return ClientResult.error(firstBlocker,
                    "Batch merge blocked due to clinical/regulatory incompatibility: " + firstBlocker,
                    "ক্লিনিক্যাল বা রেগুলেটরি বিধিনিষেধের কারণে ব্যাচ একত্রীকরণ সম্ভব নয়: " + firstBlocker);
        }

        int mergedQty = source.quantityBaseUnits;
        BigDecimal sourceCostBefore = source.costPerUnit;
        BigDecimal targetCostBefore = target.costPerUnit;
        BigDecimal targetCostAfter = val.projectedTargetCostPerUnit;

        // Apply in-memory consolidation
        target.quantityBaseUnits += mergedQty;
        target.costPerUnit = targetCostAfter;

        source.quantityBaseUnits = 0;
        source.status = "DEPLETED";

        String mergeId = "bmerge-" + UUID.randomUUID().toString().substring(0, 12);
        BatchMergeRecordDto dto = new BatchMergeRecordDto(
                mergeId,
                tenantId,
                branchId,
                target.productId,
                target.productName,
                source.batchId,
                target.batchId,
                target.batchNumber,
                target.expiryDate,
                mergedQty,
                sourceCostBefore,
                targetCostBefore,
                targetCostAfter,
                reason.trim(),
                userId,
                false,
                null,
                null,
                Instant.now()
        );

        mergeStore.put(mergeId, dto);

        return ClientResult.ok(dto,
                "Batch merge executed successfully.",
                "ব্যাচ সফলভাবে একত্রীকরণ এবং কস্ট সমন্বয় করা হয়েছে।");
    }

    /**
     * Reverses an executed batch merge.
     */
    public ClientResult<BatchMergeRecordDto> reverseMerge(
            String mergeId,
            BatchDto source,
            BatchDto target,
            String reason) {

        if (!isAuthorizedManager()) {
            return ClientResult.error("ERR_PERMISSION_DENIED",
                    "Only branch managers or owners can reverse a batch merge.",
                    "কেবল ব্রাঞ্চ ম্যানেজার বা মালিক ব্যাচ একত্রীকরণ বাতিল করতে পারেন।");
        }

        if (reason == null || reason.trim().length() < 10) {
            return ClientResult.error("ERR_AUDIT_REASON_REQUIRED",
                    "A valid reason of at least 10 characters is required for merge reversal.",
                    "একত্রীকরণ বাতিলের জন্য ন্যূনতম ১০ অক্ষরের সুস্পষ্ট কারণ উল্লেখ আবশ্যক।");
        }

        BatchMergeRecordDto existing = mergeStore.get(mergeId);
        if (existing == null) {
            return ClientResult.error("ERR_MERGE_RECORD_NOT_FOUND",
                    "Batch merge record not found.",
                    "ব্যাচ একত্রীকরণ রেকর্ড পাওয়া যায়নি।");
        }

        if (!existing.tenantId.equals(tenantId) || !existing.branchId.equals(branchId)) {
            return ClientResult.error("ERR_CROSS_BRANCH_ACCESS",
                    "Cannot reverse merge record from another branch or tenant.",
                    "ভিন্ন শাখা বা প্রতিষ্ঠানের রেকর্ড বাতিল করা নিষিদ্ধ।");
        }

        if (existing.isReversed) {
            return ClientResult.error("ERR_ALREADY_REVERSED",
                    "This batch merge has already been reversed.",
                    "এই ব্যাচ একত্রীকরণটি পূর্বেই বাতিল করা হয়েছে।");
        }

        int mergedQty = existing.mergedQuantityBaseUnits;
        if (target.getAvailableQuantityBaseUnits() < mergedQty) {
            return ClientResult.error("ERR_INSUFFICIENT_STOCK_FOR_REVERSAL",
                    "Target batch does not have sufficient unreserved stock to restore source batch.",
                    "টার্গেট ব্যাচে পর্যাপ্ত মুক্ত স্টক না থাকায় একত্রীকরণ বাতিল করা সম্ভব নয়।");
        }

        // Revert in-memory
        target.quantityBaseUnits -= mergedQty;
        target.costPerUnit = existing.targetCostPerUnitBefore;

        source.quantityBaseUnits = mergedQty;
        source.status = "ACTIVE";
        source.costPerUnit = existing.sourceCostPerUnitBefore;

        existing.isReversed = true;
        existing.reversalReason = reason.trim();
        existing.reversedAt = Instant.now();

        return ClientResult.ok(existing,
                "Batch merge reversed successfully.",
                "ব্যাচ একত্রীকরণ সফলভাবে বাতিল করা হয়েছে এবং স্টক পুনরুদ্ধার করা হয়েছে।");
    }

    public ClientResult<List<BatchMergeRecordDto>> listMerges() {
        List<BatchMergeRecordDto> list = new ArrayList<>();
        for (BatchMergeRecordDto rec : mergeStore.values()) {
            if (rec.tenantId.equals(tenantId) && rec.branchId.equals(branchId)) {
                list.add(rec);
            }
        }
        return ClientResult.ok(list, "Merge records retrieved.", "একত্রীকরণ তালিকা প্রাপ্ত হয়েছে।");
    }

    public ClientResult<BatchMergeRecordDto> getMerge(String mergeId) {
        BatchMergeRecordDto rec = mergeStore.get(mergeId);
        if (rec == null || !rec.tenantId.equals(tenantId) || !rec.branchId.equals(branchId)) {
            return ClientResult.error("ERR_MERGE_RECORD_NOT_FOUND", "Merge record not found.", "রেকর্ড পাওয়া যায়নি।");
        }
        return ClientResult.ok(rec, "Merge record found.", "রেকর্ড প্রাপ্ত হয়েছে।");
    }
}

