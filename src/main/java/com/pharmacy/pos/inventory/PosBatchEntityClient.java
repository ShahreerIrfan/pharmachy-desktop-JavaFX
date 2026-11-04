package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * PosBatchEntityClient
 * Architecture Decision: ADR-0166 (P166).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Client-side stable medicine batch entity, FEFO selection, and statutory recall
 * enforcement engine for JavaFX Desktop POS.
 */
public class PosBatchEntityClient {

    public static final class BatchRecord {
        public final String batchId;
        public final String tenantId;
        public final String branchId;
        public final String productId;
        public final String productBrandName;
        public final String batchNumber;
        public final String manufacturerName;
        public int quantityBaseUnits;
        public final BigDecimal costPerUnit;
        public final LocalDate manufacturingDate;
        public final LocalDate expiryDate;
        public String status;
        public final String storageCondition;
        public final String lotReference;
        public final int quarantineLeadDays;
        public boolean isQuarantined;
        public String recallNoticeId;
        public String recallReason;
        public boolean isActive;

        public BatchRecord(
                String batchId,
                String tenantId,
                String branchId,
                String productId,
                String productBrandName,
                String batchNumber,
                String manufacturerName,
                int quantityBaseUnits,
                BigDecimal costPerUnit,
                LocalDate manufacturingDate,
                LocalDate expiryDate,
                String status,
                String storageCondition,
                String lotReference,
                int quarantineLeadDays,
                boolean isQuarantined,
                String recallNoticeId,
                String recallReason,
                boolean isActive) {
            this.batchId = batchId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.productId = productId;
            this.productBrandName = productBrandName != null ? productBrandName : "";
            this.batchNumber = batchNumber != null ? batchNumber.trim() : "";
            this.manufacturerName = manufacturerName != null ? manufacturerName.trim() : "";
            this.quantityBaseUnits = Math.max(0, quantityBaseUnits);
            this.costPerUnit = costPerUnit != null ? costPerUnit : BigDecimal.ZERO;
            this.manufacturingDate = manufacturingDate;
            this.expiryDate = expiryDate;
            this.status = status != null ? status.toUpperCase() : "ACTIVE";
            this.storageCondition = storageCondition != null ? storageCondition : "ROOM_TEMPERATURE_15_25C";
            this.lotReference = lotReference != null ? lotReference : "";
            this.quarantineLeadDays = Math.max(0, quarantineLeadDays);
            this.isQuarantined = isQuarantined;
            this.recallNoticeId = recallNoticeId;
            this.recallReason = recallReason != null ? recallReason : "";
            this.isActive = isActive;
        }

        public boolean isDispensable(LocalDate today) {
            if (!isActive || !"ACTIVE".equals(status)) return false;
            if (isQuarantined || recallNoticeId != null || "RECALLED".equals(status)) return false;
            if (expiryDate != null && today != null && !expiryDate.isAfter(today)) return false;
            return quantityBaseUnits > 0;
        }
    }

    public static final class RecallNotice {
        public final String noticeId;
        public final String noticeNumber;
        public final String productId;
        public final String recallScope; // NATIONWIDE_ALL_BATCHES or BATCH_SPECIFIC
        public final Set<String> affectedBatchNumbers;
        public final String reason;
        public final boolean isActive;

        public RecallNotice(
                String noticeId,
                String noticeNumber,
                String productId,
                String recallScope,
                Collection<String> affectedBatchNumbers,
                String reason,
                boolean isActive) {
            this.noticeId = noticeId;
            this.noticeNumber = noticeNumber;
            this.productId = productId;
            this.recallScope = recallScope != null ? recallScope.toUpperCase() : "NATIONWIDE_ALL_BATCHES";
            this.affectedBatchNumbers = new HashSet<>();
            if (affectedBatchNumbers != null) {
                for (String b : affectedBatchNumbers) {
                    if (b != null) this.affectedBatchNumbers.add(b.trim());
                }
            }
            this.reason = reason != null ? reason : "";
            this.isActive = isActive;
        }

        public boolean affectsBatch(String candidateBatchNumber) {
            if (!isActive) return false;
            if ("BATCH_SPECIFIC".equals(recallScope)) {
                return affectedBatchNumbers.contains(candidateBatchNumber);
            }
            return true; // Applies to all batches of the product
        }
    }

    public static final class BatchDispenseResult {
        public final boolean isDispensable;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;
        public final String batchId;
        public final int availableUnits;

        public BatchDispenseResult(
                boolean isDispensable,
                String errorCode,
                String messageEn,
                String messageBn,
                String batchId,
                int availableUnits) {
            this.isDispensable = isDispensable;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
            this.batchId = batchId;
            this.availableUnits = availableUnits;
        }

        public static BatchDispenseResult success(String batchId, int availableUnits) {
            return new BatchDispenseResult(
                    true,
                    null,
                    "Batch verified and clear for dispensing.",
                    "ব্যাচ যাচাই সফল এবং বিক্রয়ের জন্য প্রস্তুত।",
                    batchId,
                    availableUnits);
        }

        public static BatchDispenseResult failed(String errorCode, String messageEn, String messageBn, String batchId, int availableUnits) {
            return new BatchDispenseResult(false, errorCode, messageEn, messageBn, batchId, availableUnits);
        }
    }

    public static final class BatchValidationResult {
        public final boolean isValid;
        public final String errorCode;
        public final String message;

        public BatchValidationResult(boolean isValid, String errorCode, String message) {
            this.isValid = isValid;
            this.errorCode = errorCode;
            this.message = message;
        }

        public static BatchValidationResult valid() {
            return new BatchValidationResult(true, null, "Validation passed.");
        }

        public static BatchValidationResult error(String errorCode, String message) {
            return new BatchValidationResult(false, errorCode, message);
        }
    }

    /**
     * Validates batch dates against manufacturing chronology and current date.
     */
    public static BatchValidationResult validateBatchDates(LocalDate mfgDate, LocalDate expiryDate, LocalDate today) {
        if (expiryDate == null) {
            return BatchValidationResult.error("ERR_MISSING_EXPIRY_DATE", "Expiry date is mandatory.");
        }
        if (mfgDate != null && !expiryDate.isAfter(mfgDate)) {
            return BatchValidationResult.error("ERR_EXPIRY_BEFORE_MANUFACTURING", "Expiry date must be strictly after manufacturing date.");
        }
        if (today != null && !expiryDate.isAfter(today)) {
            return BatchValidationResult.error("ERR_BATCH_EXPIRED_ON_CREATION", "Batch is expired and cannot be registered as active.");
        }
        return BatchValidationResult.valid();
    }

    /**
     * Evaluates dispensability of a batch considering expiry, quarantine, stock, and statutory recalls.
     */
    public static BatchDispenseResult evaluateDispensability(
            BatchRecord batch,
            RecallNotice recallNotice,
            int requestedUnits,
            LocalDate today) {
        if (batch == null) {
            return BatchDispenseResult.failed(
                    "ERR_BATCH_NOT_FOUND",
                    "Batch not found in local POS cache.",
                    "পিওএস ক্যাশে ব্যাচ খুঁজে পাওয়া যায়নি।",
                    null,
                    0);
        }

        // Check external or attached statutory recall notice
        if (recallNotice != null && recallNotice.affectsBatch(batch.batchNumber)) {
            return BatchDispenseResult.failed(
                    "ERR_BATCH_RECALLED",
                    "Batch " + batch.batchNumber + " is under active statutory recall: " + recallNotice.reason,
                    "ঔষধের ব্যাচ " + batch.batchNumber + " ডিজিডিএ/প্রস্তুতকারক কর্তৃক প্রত্যাহার করা হয়েছে। বিক্রয় নিষিদ্ধ।",
                    batch.batchId,
                    batch.quantityBaseUnits);
        }

        if ("RECALLED".equals(batch.status) || batch.recallNoticeId != null) {
            return BatchDispenseResult.failed(
                    "ERR_BATCH_RECALLED",
                    "Batch " + batch.batchNumber + " is recalled. Dispensing blocked.",
                    "ঔষধের ব্যাচ " + batch.batchNumber + " প্রত্যাহারকৃত। বিক্রয় সম্পূর্ণ নিষিদ্ধ।",
                    batch.batchId,
                    batch.quantityBaseUnits);
        }

        // Expiry check
        if ("EXPIRED".equals(batch.status) || (today != null && batch.expiryDate != null && !batch.expiryDate.isAfter(today))) {
            return BatchDispenseResult.failed(
                    "ERR_BATCH_EXPIRED",
                    "Batch " + batch.batchNumber + " expired on " + batch.expiryDate + ". Dispensing prohibited.",
                    "ঔষধের ব্যাচ " + batch.batchNumber + " এর মেয়াদ উত্তীর্ণ হয়ে গেছে (" + batch.expiryDate + ")। বিক্রয় নিষিদ্ধ।",
                    batch.batchId,
                    batch.quantityBaseUnits);
        }

        // Quarantine check
        if (batch.isQuarantined || "QUARANTINED".equals(batch.status)) {
            return BatchDispenseResult.failed(
                    "ERR_BATCH_QUARANTINED",
                    "Batch " + batch.batchNumber + " is quarantined. Clinical clearance required.",
                    "ঔষধের ব্যাচ " + batch.batchNumber + " কোয়ারেন্টাইনে রয়েছে। ফার্মাসিস্টের অনুমোদন প্রয়োজন।",
                    batch.batchId,
                    batch.quantityBaseUnits);
        }

        // Archived check
        if (!batch.isActive || "ARCHIVED".equals(batch.status)) {
            return BatchDispenseResult.failed(
                    "ERR_BATCH_ARCHIVED",
                    "Batch " + batch.batchNumber + " is archived.",
                    "ঔষধের ব্যাচ " + batch.batchNumber + " আর্কাইভ করা হয়েছে।",
                    batch.batchId,
                    batch.quantityBaseUnits);
        }

        // Stock quantity check
        if (batch.quantityBaseUnits < requestedUnits) {
            return BatchDispenseResult.failed(
                    "ERR_INSUFFICIENT_STOCK",
                    "Insufficient stock for batch " + batch.batchNumber + ": requested " + requestedUnits + ", available " + batch.quantityBaseUnits + ".",
                    "ব্যাচ " + batch.batchNumber + "-এ পর্যাপ্ত স্টক নেই: চাওয়া হয়েছে " + requestedUnits + ", আছে " + batch.quantityBaseUnits + "।",
                    batch.batchId,
                    batch.quantityBaseUnits);
        }

        return BatchDispenseResult.success(batch.batchId, batch.quantityBaseUnits);
    }

    /**
     * Selects best batch per FEFO (First-Expired, First-Out) rule among candidate batches.
     */
    public static BatchRecord selectFefoBatch(
            List<BatchRecord> batches,
            String productId,
            int requestedUnits,
            LocalDate today) {
        if (batches == null || batches.isEmpty()) return null;

        List<BatchRecord> validBatches = new ArrayList<>();
        for (BatchRecord b : batches) {
            if (productId != null && !productId.equals(b.productId)) continue;
            BatchDispenseResult res = evaluateDispensability(b, null, requestedUnits, today);
            if (res.isDispensable) {
                validBatches.add(b);
            }
        }

        if (validBatches.isEmpty()) return null;

        // Sort by expiry_date ascending, then batch_number
        validBatches.sort(Comparator.comparing((BatchRecord b) -> b.expiryDate)
                .thenComparing(b -> b.batchNumber));

        return validBatches.get(0);
    }

    /**
     * Atomic in-memory integer stock reduction.
     */
    public static boolean deductBatchStock(BatchRecord batch, int units) {
        if (batch == null || units <= 0) return false;
        if (batch.quantityBaseUnits < units) return false;
        batch.quantityBaseUnits -= units;
        if (batch.quantityBaseUnits == 0) {
            batch.status = "DEPLETED";
        }
        return true;
    }
}

