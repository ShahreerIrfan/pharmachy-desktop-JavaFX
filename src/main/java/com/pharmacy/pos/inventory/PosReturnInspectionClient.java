package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * PosReturnInspectionClient
 * Architecture Decision: ADR-0177 (P177).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Desktop POS client handling customer sales return safety inspections,
 * clinical quarantine routing, and exact lot/batch restocking.
 */
public class PosReturnInspectionClient {

    public enum Disposition {
        RESTOCK_APPROVED("Restock to Original Batch & Lot", "মূল ব্যাচ ও লটে পুনর্বহাল"),
        QUARANTINE_HOLD("Quarantine Hold for QA Investigation", "কোয়ারেন্টাইন স্থিতি (বিতরণ নিষিদ্ধ)"),
        DAMAGED_DESTROY("Damaged / Disposed - Do Not Restock", "নষ্ট বা বিনষ্ট / স্টক থেকে বাতিল");

        public final String labelEn;
        public final String labelBn;

        Disposition(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static final class SaleAllocationLookupDto {
        public final String allocationId;
        public final String tenantId;
        public final String branchId;
        public final String invoiceId;
        public final String productId;
        public final String productName;
        public final String batchId;
        public final String batchNumber;
        public final String costLotId;
        public final int quantityBaseUnits;
        public int returnedQuantityBaseUnits;
        public final BigDecimal unitSellingPrice;
        public final BigDecimal unitCost;
        public final LocalDate batchExpiryDate;
        public final boolean isBatchQuarantined;

        public SaleAllocationLookupDto(
                String allocationId,
                String tenantId,
                String branchId,
                String invoiceId,
                String productId,
                String productName,
                String batchId,
                String batchNumber,
                String costLotId,
                int quantityBaseUnits,
                int returnedQuantityBaseUnits,
                BigDecimal unitSellingPrice,
                BigDecimal unitCost,
                LocalDate batchExpiryDate,
                boolean isBatchQuarantined) {
            this.allocationId = allocationId != null ? allocationId : "";
            this.tenantId = tenantId != null ? tenantId : "";
            this.branchId = branchId != null ? branchId : "";
            this.invoiceId = invoiceId != null ? invoiceId : "";
            this.productId = productId != null ? productId : "";
            this.productName = productName != null ? productName : "";
            this.batchId = batchId != null ? batchId : "";
            this.batchNumber = batchNumber != null ? batchNumber : "";
            this.costLotId = costLotId;
            this.quantityBaseUnits = quantityBaseUnits;
            this.returnedQuantityBaseUnits = returnedQuantityBaseUnits;
            this.unitSellingPrice = unitSellingPrice != null ? unitSellingPrice.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.unitCost = unitCost != null ? unitCost.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.batchExpiryDate = batchExpiryDate;
            this.isBatchQuarantined = isBatchQuarantined;
        }

        public int getMaxReturnableUnits() {
            return Math.max(0, quantityBaseUnits - returnedQuantityBaseUnits);
        }
    }

    public static final class ReturnSafetyInspectionDto {
        public final String inspectionId;
        public final String tenantId;
        public final String branchId;
        public final String allocationId;
        public final String invoiceId;
        public final String productId;
        public final String productName;
        public final String batchId;
        public final String batchNumber;
        public final String costLotId;
        public final int returnedQuantityBaseUnits;
        public final Disposition disposition;
        public final boolean isSealIntact;
        public final boolean isPackagingUndamaged;
        public final boolean isStorageConditionVerified;
        public final boolean isUnexpired;
        public final Double temperatureReadingCelsius;
        public final String inspectorId;
        public final String customerReason;
        public final String inspectionNotes;
        public final BigDecimal refundAmount;
        public final BigDecimal restockedCogs;
        public boolean isReversed;
        public String reversalReason;
        public Instant reversedAt;
        public final Instant createdAt;

        public ReturnSafetyInspectionDto(
                String inspectionId,
                String tenantId,
                String branchId,
                String allocationId,
                String invoiceId,
                String productId,
                String productName,
                String batchId,
                String batchNumber,
                String costLotId,
                int returnedQuantityBaseUnits,
                Disposition disposition,
                boolean isSealIntact,
                boolean isPackagingUndamaged,
                boolean isStorageConditionVerified,
                boolean isUnexpired,
                Double temperatureReadingCelsius,
                String inspectorId,
                String customerReason,
                String inspectionNotes,
                BigDecimal refundAmount,
                BigDecimal restockedCogs,
                boolean isReversed,
                String reversalReason,
                Instant reversedAt,
                Instant createdAt) {
            this.inspectionId = inspectionId != null ? inspectionId : "";
            this.tenantId = tenantId != null ? tenantId : "";
            this.branchId = branchId != null ? branchId : "";
            this.allocationId = allocationId != null ? allocationId : "";
            this.invoiceId = invoiceId != null ? invoiceId : "";
            this.productId = productId != null ? productId : "";
            this.productName = productName != null ? productName : "";
            this.batchId = batchId != null ? batchId : "";
            this.batchNumber = batchNumber != null ? batchNumber : "";
            this.costLotId = costLotId;
            this.returnedQuantityBaseUnits = returnedQuantityBaseUnits;
            this.disposition = disposition;
            this.isSealIntact = isSealIntact;
            this.isPackagingUndamaged = isPackagingUndamaged;
            this.isStorageConditionVerified = isStorageConditionVerified;
            this.isUnexpired = isUnexpired;
            this.temperatureReadingCelsius = temperatureReadingCelsius;
            this.inspectorId = inspectorId != null ? inspectorId : "";
            this.customerReason = customerReason != null ? customerReason : "";
            this.inspectionNotes = inspectionNotes != null ? inspectionNotes : "";
            this.refundAmount = refundAmount != null ? refundAmount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.restockedCogs = restockedCogs != null ? restockedCogs.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.isReversed = isReversed;
            this.reversalReason = reversalReason;
            this.reversedAt = reversedAt;
            this.createdAt = createdAt != null ? createdAt : Instant.now();
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
    private final Map<String, ReturnSafetyInspectionDto> inspectionStore;

    public PosReturnInspectionClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline) {
        this(tenantId, branchId, userRole, userId, isOffline, new HashMap<>());
    }

    public PosReturnInspectionClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, ReturnSafetyInspectionDto> sharedStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "";
        this.userId = userId != null ? userId : "";
        this.isOffline = isOffline;
        this.inspectionStore = sharedStore != null ? sharedStore : new HashMap<>();
    }

    private boolean isPharmacistOrManager() {
        return "STAFF_PHARMACIST".equalsIgnoreCase(userRole)
                || "BRANCH_MANAGER".equalsIgnoreCase(userRole)
                || "PHARMACY_OWNER".equalsIgnoreCase(userRole)
                || "SUPERADMIN".equalsIgnoreCase(userRole);
    }

    private boolean isManagerOrOwner() {
        return "BRANCH_MANAGER".equalsIgnoreCase(userRole)
                || "PHARMACY_OWNER".equalsIgnoreCase(userRole)
                || "SUPERADMIN".equalsIgnoreCase(userRole);
    }

    /**
     * Inspect and process sales return against an exact lot allocation.
     */
    public ClientResult<ReturnSafetyInspectionDto> inspectAndProcessReturn(
            SaleAllocationLookupDto alloc,
            int returnedQty,
            boolean isSealIntact,
            boolean isPackagingUndamaged,
            boolean isStorageConditionVerified,
            boolean isUnexpired,
            Double temperatureReadingCelsius,
            Disposition disposition,
            String customerReason,
            String inspectionNotes) {

        if (!isPharmacistOrManager()) {
            return ClientResult.error("ERR_PERMISSION_DENIED",
                    "Safety inspection and return disposition requires a registered pharmacist or manager.",
                    "ওষুধ ফেরত পরিদর্শন এবং পুনর্বহালের সিদ্ধান্ত কেবল রেজিস্টার্ড ফার্মাসিস্ট বা ম্যানেজারের এখতিয়ারাধীন।");
        }

        if (alloc == null) {
            return ClientResult.error("ERR_ALLOCATION_NOT_FOUND",
                    "Sale lot allocation record not found.",
                    "বিক্রিত লট বরাদ্দের রেকর্ড পাওয়া যায়নি।");
        }

        if (!alloc.tenantId.equals(tenantId)) {
            return ClientResult.error("ERR_CROSS_TENANT_ACCESS",
                    "Cross-tenant return operations are strictly barred.",
                    "ভিন্ন প্রতিষ্ঠানের ওষুধ ফেরত প্রক্রিয়া নিষিদ্ধ।");
        }

        if (!alloc.branchId.equals(branchId)) {
            return ClientResult.error("ERR_CROSS_BRANCH_RETURN",
                    "Return must be processed in the originating dispensing branch.",
                    "ওষুধ অবশ্যই যে শাখা থেকে বিক্রি হয়েছিল সে শাখাতেই ফেরত গ্রহণ করতে হবে।");
        }

        if (returnedQty <= 0) {
            return ClientResult.error("ERR_INVALID_QUANTITY",
                    "Returned quantity must be a positive integer.",
                    "ফেরত পরিমাণ অবশ্যই ধনাত্মক পূর্ণসংখ্যা হতে হবে।");
        }

        int maxReturnable = alloc.getMaxReturnableUnits();
        if (returnedQty > maxReturnable) {
            return ClientResult.error("ERR_RETURN_EXCEEDS_DISPENSED",
                    "Returned quantity (" + returnedQty + ") exceeds max returnable dispensed units (" + maxReturnable + ").",
                    "ফেরতের পরিমাণ বিক্রিত অবশিষ্ট পরিমাণের চেয়ে বেশি হতে পারে না।");
        }

        if (disposition == null) {
            return ClientResult.error("ERR_INVALID_DISPOSITION",
                    "A valid inspection disposition must be selected.",
                    "সঠিক পরিদর্শন সিদ্ধান্ত নির্বাচন আবশ্যক।");
        }

        // Safety verification rules for RESTOCK_APPROVED
        if (disposition == Disposition.RESTOCK_APPROVED) {
            if (!isSealIntact || !isPackagingUndamaged || !isStorageConditionVerified || !isUnexpired) {
                return ClientResult.error("ERR_FAILED_SAFETY_INSPECTION_CANNOT_RESTOCK",
                        "Medicine failing safety inspection cannot be restocked. Route to QUARANTINE_HOLD or DAMAGED_DESTROY.",
                        "নিরাপত্তা পরীক্ষায় অকৃতকার্য ওষুধ তাকে পুনর্বহাল নিষিদ্ধ। কোয়ারেন্টাইন বা নষ্ট হিসেবে চিহ্নিত করুন।");
            }

            if (alloc.batchExpiryDate != null && !alloc.batchExpiryDate.isAfter(LocalDate.now())) {
                return ClientResult.error("ERR_BATCH_EXPIRED_NO_RESTOCK",
                        "Batch is expired. Expired stock cannot be restocked for patient dispensing.",
                        "ব্যাচের মেয়াদ উত্তীর্ণ হয়েছে। মেয়াদোত্তীর্ণ ওষুধ বিক্রির জন্য পুনরায় তাকে ওঠানো নিষিদ্ধ।");
            }

            if (alloc.isBatchQuarantined) {
                return ClientResult.error("ERR_BATCH_QUARANTINED_NO_RESTOCK",
                        "Batch is currently quarantined. Cannot restock into active inventory.",
                        "উক্ত ব্যাচটি বর্তমানে কোয়ারেন্টাইনে রয়েছে। সক্রিয় স্টকে যোগ করা সম্ভব নয়।");
            }
        }

        // Calculate refund & restored COGS
        BigDecimal refundAmount = alloc.unitSellingPrice.multiply(BigDecimal.valueOf(returnedQty)).setScale(4, RoundingMode.HALF_UP);
        BigDecimal restockedCogs = disposition == Disposition.RESTOCK_APPROVED
                ? alloc.unitCost.multiply(BigDecimal.valueOf(returnedQty)).setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        // Update allocation in-memory
        alloc.returnedQuantityBaseUnits += returnedQty;

        String inspectionId = "retinsp-" + UUID.randomUUID().toString().substring(0, 12);
        ReturnSafetyInspectionDto dto = new ReturnSafetyInspectionDto(
                inspectionId,
                tenantId,
                branchId,
                alloc.allocationId,
                alloc.invoiceId,
                alloc.productId,
                alloc.productName,
                alloc.batchId,
                alloc.batchNumber,
                alloc.costLotId,
                returnedQty,
                disposition,
                isSealIntact,
                isPackagingUndamaged,
                isStorageConditionVerified,
                isUnexpired,
                temperatureReadingCelsius,
                userId,
                customerReason != null ? customerReason : "",
                inspectionNotes != null ? inspectionNotes : "",
                refundAmount,
                restockedCogs,
                false,
                null,
                null,
                Instant.now()
        );

        inspectionStore.put(inspectionId, dto);

        return ClientResult.ok(dto,
                "Return safety inspection processed successfully.",
                "ফেরত ওষুধের নিরাপত্তা পরিদর্শন ও স্টক সমন্বয় সফলভাবে সম্পন্ন হয়েছে।");
    }

    /**
     * Reverse an approved return inspection.
     */
    public ClientResult<ReturnSafetyInspectionDto> reverseInspection(
            String inspectionId,
            String reason) {

        if (!isManagerOrOwner()) {
            return ClientResult.error("ERR_PERMISSION_DENIED",
                    "Only branch managers or owners can reverse return safety inspections.",
                    "কেবল ব্রাঞ্চ ম্যানেজার বা মালিক ওষুধ ফেরত সিদ্ধান্ত বাতিল করতে পারেন।");
        }

        if (reason == null || reason.trim().length() < 10) {
            return ClientResult.error("ERR_AUDIT_REASON_REQUIRED",
                    "A valid reason of at least 10 characters is required for inspection reversal.",
                    "ফেরত সিদ্ধান্ত বাতিলের জন্য ন্যূনতম ১০ অক্ষরের সুস্পষ্ট কারণ আবশ্যক।");
        }

        ReturnSafetyInspectionDto existing = inspectionStore.get(inspectionId);
        if (existing == null) {
            return ClientResult.error("ERR_INSPECTION_NOT_FOUND",
                    "Return safety inspection record not found.",
                    "ফেরত পরিদর্শনের রেকর্ড পাওয়া যায়নি।");
        }

        if (!existing.tenantId.equals(tenantId) || !existing.branchId.equals(branchId)) {
            return ClientResult.error("ERR_CROSS_BRANCH_ACCESS",
                    "Cannot reverse inspection from another branch or tenant.",
                    "ভিন্ন শাখা বা প্রতিষ্ঠানের পরিদর্শন রেকর্ড বাতিল করা নিষিদ্ধ।");
        }

        if (existing.isReversed) {
            return ClientResult.error("ERR_ALREADY_REVERSED",
                    "This return inspection has already been reversed.",
                    "এই পরিদর্শন সিদ্ধান্তটি পূর্বেই বাতিল করা হয়েছে।");
        }

        existing.isReversed = true;
        existing.reversalReason = reason.trim();
        existing.reversedAt = Instant.now();

        return ClientResult.ok(existing,
                "Return safety inspection reversed successfully.",
                "ওষুধ ফেরত সিদ্ধান্ত সফলভাবে বাতিল ও সমন্বয় করা হয়েছে।");
    }

    public ClientResult<List<ReturnSafetyInspectionDto>> listInspections() {
        List<ReturnSafetyInspectionDto> list = new ArrayList<>();
        for (ReturnSafetyInspectionDto insp : inspectionStore.values()) {
            if (insp.tenantId.equals(tenantId) && insp.branchId.equals(branchId)) {
                list.add(insp);
            }
        }
        return ClientResult.ok(list, "Inspections retrieved.", "পরিদর্শন তালিকা প্রাপ্ত হয়েছে।");
    }

    public ClientResult<ReturnSafetyInspectionDto> getInspection(String inspectionId) {
        ReturnSafetyInspectionDto insp = inspectionStore.get(inspectionId);
        if (insp == null || !insp.tenantId.equals(tenantId) || !insp.branchId.equals(branchId)) {
            return ClientResult.error("ERR_INSPECTION_NOT_FOUND", "Inspection not found.", "পরিদর্শন রেকর্ড পাওয়া যায়নি।");
        }
        return ClientResult.ok(insp, "Inspection found.", "পরিদর্শন রেকর্ড প্রাপ্ত হয়েছে।");
    }
}

