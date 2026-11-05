package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * PosStockCorrectionClient
 * Architecture Decision: ADR-0174 (P174).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Desktop POS client handling reasoned stock corrections,
 * two-person approval workflows, and reversible ledger entries.
 */
public class PosStockCorrectionClient {

    public enum AdjustmentType {
        DAMAGE("Damaged in Storage / Handling", "স্টোরেজে বা হ্যান্ডলিংয়ে ক্ষতিগ্রস্ত"),
        EXPIRY_REMOVAL("Expired Stock Removal", "মেয়াদোত্তীর্ণ ওষুধ সরানো"),
        COUNT_CORRECTION("Physical Count Correction", "শারীরিক গণনা সংশোধন"),
        INTERNAL_USE("Internal Clinic / Store Use", "অভ্যন্তরীণ ক্লিনিক / ডিসপেনসারি ব্যবহার"),
        THEFT_LOSS("Theft or Unaccounted Loss", "চুরি বা অপ্রদর্শিত অপচয়"),
        FOUND_SURPLUS("Found Physical Surplus", "শারীরিক অতিরিক্ত মজুদ প্রাপ্তি");

        public final String labelEn;
        public final String labelBn;

        AdjustmentType(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum CorrectionStatus {
        PENDING("Pending Approval", "অনুমোদনের অপেক্ষায়"),
        APPROVED("Approved & Posted", "অনুমোদিত ও পোস্টকৃত"),
        REJECTED("Rejected", "প্রত্যাখ্যাত"),
        REVERSED("Reversed", "বাতিল ও সমন্বয়কৃত");

        public final String labelEn;
        public final String labelBn;

        CorrectionStatus(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static final class StockAdjustmentDto {
        public final String adjustmentId;
        public final String tenantId;
        public final String branchId;
        public final String productId;
        public final String batchId;
        public final String costLotId;
        public final AdjustmentType adjustmentType;
        public final int quantityDelta;
        public final BigDecimal unitCostBdt;
        public final BigDecimal totalCostImpact;
        public final String reason;
        public CorrectionStatus status;
        public final String proposedBy;
        public String approvedBy;
        public Instant approvedAt;
        public final String reversalOfId;
        public boolean isReversed;
        public String reversalReason;
        public final String supportingDocumentRef;

        public StockAdjustmentDto(
                String adjustmentId,
                String tenantId,
                String branchId,
                String productId,
                String batchId,
                String costLotId,
                AdjustmentType adjustmentType,
                int quantityDelta,
                BigDecimal unitCostBdt,
                BigDecimal totalCostImpact,
                String reason,
                CorrectionStatus status,
                String proposedBy,
                String approvedBy,
                Instant approvedAt,
                String reversalOfId,
                boolean isReversed,
                String reversalReason,
                String supportingDocumentRef) {
            this.adjustmentId = adjustmentId != null ? adjustmentId : "";
            this.tenantId = tenantId != null ? tenantId : "";
            this.branchId = branchId != null ? branchId : "";
            this.productId = productId != null ? productId : "";
            this.batchId = batchId != null ? batchId : "";
            this.costLotId = costLotId;
            this.adjustmentType = adjustmentType;
            this.quantityDelta = quantityDelta;
            this.unitCostBdt = unitCostBdt != null ? unitCostBdt : BigDecimal.ZERO;
            this.totalCostImpact = totalCostImpact != null ? totalCostImpact : BigDecimal.ZERO;
            this.reason = reason != null ? reason : "";
            this.status = status != null ? status : CorrectionStatus.PENDING;
            this.proposedBy = proposedBy;
            this.approvedBy = approvedBy;
            this.approvedAt = approvedAt;
            this.reversalOfId = reversalOfId;
            this.isReversed = isReversed;
            this.reversalReason = reversalReason;
            this.supportingDocumentRef = supportingDocumentRef != null ? supportingDocumentRef : "";
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
    private final String username;
    private final boolean isTenantSuspended;
    private final Map<String, StockAdjustmentDto> ledgerStore;

    public PosStockCorrectionClient(
            String tenantId,
            String branchId,
            String userRole,
            String username,
            boolean isTenantSuspended) {
        this(tenantId, branchId, userRole, username, isTenantSuspended, new HashMap<>());
    }

    public PosStockCorrectionClient(
            String tenantId,
            String branchId,
            String userRole,
            String username,
            boolean isTenantSuspended,
            Map<String, StockAdjustmentDto> sharedStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.username = username != null ? username : "cashier1";
        this.isTenantSuspended = isTenantSuspended;
        this.ledgerStore = sharedStore != null ? sharedStore : new HashMap<>();
    }

    public Map<String, StockAdjustmentDto> getLedgerStore() {
        return this.ledgerStore;
    }

    public boolean canApprove() {
        return switch (this.userRole.toUpperCase()) {
            case "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "ADMIN", "OWNER_ADMIN" -> true;
            default -> false;
        };
    }

    public ClientResponse<StockAdjustmentDto> proposeAdjustment(
            String productId,
            String batchId,
            String costLotId,
            AdjustmentType adjustmentType,
            int quantityDelta,
            BigDecimal unitCostBdt,
            String reason,
            String supportingDocumentRef) {
        if (isTenantSuspended) {
            return ClientResponse.failure(
                    "ERR_TENANT_SUSPENDED",
                    "Tenant subscription is suspended.",
                    "গ্রাহক সাবস্ক্রিপশন স্থগিত রয়েছে।"
            );
        }

        if (quantityDelta == 0) {
            return ClientResponse.failure(
                    "ERR_ZERO_QUANTITY_DELTA",
                    "Quantity delta cannot be zero.",
                    "স্টক পরিবর্তনের পরিমাণ শূন্য হতে পারে না।"
            );
        }

        String cleanedReason = reason != null ? reason.trim() : "";
        if (cleanedReason.length() < 10) {
            return ClientResponse.failure(
                    "ERR_AUDIT_REASON_REQUIRED",
                    "A reasoned justification (minimum 10 characters) is strictly mandatory.",
                    "যথাযথ নিরীক্ষা কারণ (কমপক্ষে ১০ অক্ষর) আবশ্যক।"
            );
        }

        if (adjustmentType == null) {
            return ClientResponse.failure(
                    "ERR_INVALID_ADJUSTMENT_TYPE",
                    "Adjustment type is required.",
                    "অ্যাডজাস্টমেন্ট টাইপ নির্দিষ্ট করতে হবে।"
            );
        }

        BigDecimal unitCost = unitCostBdt != null ? unitCostBdt : BigDecimal.ZERO;
        BigDecimal totalImpact = unitCost.multiply(BigDecimal.valueOf(Math.abs(quantityDelta)));
        String adjId = "stkadj-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        StockAdjustmentDto record = new StockAdjustmentDto(
                adjId,
                tenantId,
                branchId,
                productId,
                batchId,
                costLotId,
                adjustmentType,
                quantityDelta,
                unitCost,
                totalImpact,
                cleanedReason,
                CorrectionStatus.PENDING,
                username,
                null,
                null,
                null,
                false,
                null,
                supportingDocumentRef
        );

        ledgerStore.put(adjId, record);
        return ClientResponse.ok(record, "Stock adjustment proposed successfully.", "স্টক সমন্বয় প্রস্তাব সফলভাবে জমা হয়েছে।");
    }

    public ClientResponse<StockAdjustmentDto> approveAdjustment(
            String adjustmentId,
            int currentPhysicalQty,
            int currentReservedQty) {
        if (isTenantSuspended) {
            return ClientResponse.failure(
                    "ERR_TENANT_SUSPENDED",
                    "Tenant subscription is suspended.",
                    "গ্রাহক সাবস্ক্রিপশন স্থগিত রয়েছে।"
            );
        }

        if (!canApprove()) {
            return ClientResponse.failure(
                    "ERR_PERMISSION_DENIED",
                    "Only Branch Managers or Pharmacy Owners can approve adjustments.",
                    "শুধুমাত্র ব্রাঞ্চ ম্যানেজার বা ফার্মেসি মালিক স্টক সমন্বয় অনুমোদন করতে পারেন।"
            );
        }

        StockAdjustmentDto record = ledgerStore.get(adjustmentId);
        if (record == null) {
            return ClientResponse.failure(
                    "ERR_ADJUSTMENT_NOT_FOUND",
                    "Adjustment record not found.",
                    "স্টক সমন্বয় রেকর্ডটি পাওয়া যায়নি।"
            );
        }

        if (record.status != CorrectionStatus.PENDING) {
            return ClientResponse.failure(
                    "ERR_INVALID_ADJUSTMENT_STATUS",
                    "Only PENDING adjustments can be approved.",
                    "কেবলমাত্র অপেক্ষারত সমন্বয় অনুমোদন করা সম্ভব।"
            );
        }

        // Reservation and stock conservation check
        if (record.quantityDelta < 0) {
            int availableUnreserved = currentPhysicalQty - currentReservedQty;
            if (availableUnreserved < Math.abs(record.quantityDelta)) {
                return ClientResponse.failure(
                        "ERR_INSUFFICIENT_UNRESERVED_STOCK",
                        "Cannot reduce stock below active cart reservations.",
                        "সক্রিয় কার্ট রিজার্ভেশনের নিচে স্টক কমানো যাবে না।"
                );
            }
        }

        record.status = CorrectionStatus.APPROVED;
        record.approvedBy = username;
        record.approvedAt = Instant.now();

        return ClientResponse.ok(record, "Stock adjustment approved and posted.", "স্টক সমন্বয় অনুমোদিত ও পোস্ট করা হয়েছে।");
    }

    public ClientResponse<StockAdjustmentDto> rejectAdjustment(String adjustmentId, String reason) {
        if (isTenantSuspended) {
            return ClientResponse.failure(
                    "ERR_TENANT_SUSPENDED",
                    "Tenant subscription is suspended.",
                    "গ্রাহক সাবস্ক্রিপশন স্থগিত রয়েছে।"
            );
        }

        if (!canApprove()) {
            return ClientResponse.failure(
                    "ERR_PERMISSION_DENIED",
                    "Only Branch Managers or Pharmacy Owners can reject adjustments.",
                    "শুধুমাত্র ব্রাঞ্চ ম্যানেজার বা ফার্মেসি মালিক স্টক সমন্বয় প্রত্যাখ্যান করতে পারেন।"
            );
        }

        StockAdjustmentDto record = ledgerStore.get(adjustmentId);
        if (record == null) {
            return ClientResponse.failure(
                    "ERR_ADJUSTMENT_NOT_FOUND",
                    "Adjustment record not found.",
                    "স্টক সমন্বয় রেকর্ডটি পাওয়া যায়নি।"
            );
        }

        if (record.status != CorrectionStatus.PENDING) {
            return ClientResponse.failure(
                    "ERR_INVALID_ADJUSTMENT_STATUS",
                    "Only PENDING adjustments can be rejected.",
                    "কেবলমাত্র অপেক্ষারত সমন্বয় প্রত্যাখ্যান করা সম্ভব।"
            );
        }

        String cleanedReason = reason != null ? reason.trim() : "";
        if (cleanedReason.length() < 10) {
            return ClientResponse.failure(
                    "ERR_AUDIT_REASON_REQUIRED",
                    "A valid rejection explanation (minimum 10 characters) is required.",
                    "কমপক্ষে ১০ অক্ষরের প্রত্যাখ্যান কারণ আবশ্যক।"
            );
        }

        record.status = CorrectionStatus.REJECTED;
        record.reversalReason = cleanedReason;
        record.approvedBy = username;
        record.approvedAt = Instant.now();

        return ClientResponse.ok(record, "Stock adjustment rejected.", "স্টক সমন্বয় প্রত্যাখ্যাত হয়েছে।");
    }

    public ClientResponse<StockAdjustmentDto> reverseAdjustment(
            String adjustmentId,
            String reversalReason,
            int currentPhysicalQty,
            int currentReservedQty) {
        if (isTenantSuspended) {
            return ClientResponse.failure(
                    "ERR_TENANT_SUSPENDED",
                    "Tenant subscription is suspended.",
                    "গ্রাহক সাবস্ক্রিপশন স্থগিত রয়েছে।"
            );
        }

        if (!canApprove()) {
            return ClientResponse.failure(
                    "ERR_PERMISSION_DENIED",
                    "Only Branch Managers or Pharmacy Owners can reverse adjustments.",
                    "শুধুমাত্র ব্রাঞ্চ ম্যানেজার বা ফার্মেসি মালিক স্টক সমন্বয় বাতিল ও রিভার্স করতে পারেন।"
            );
        }

        StockAdjustmentDto original = ledgerStore.get(adjustmentId);
        if (original == null) {
            return ClientResponse.failure(
                    "ERR_ADJUSTMENT_NOT_FOUND",
                    "Adjustment record not found.",
                    "স্টক সমন্বয় রেকর্ডটি পাওয়া যায়নি।"
            );
        }

        if (original.isReversed || original.status == CorrectionStatus.REVERSED) {
            return ClientResponse.failure(
                    "ERR_ALREADY_REVERSED",
                    "Adjustment has already been reversed.",
                    "সমন্বয়টি ইতিমধ্যে রিভার্স করা হয়েছে।"
            );
        }

        if (original.status != CorrectionStatus.APPROVED) {
            return ClientResponse.failure(
                    "ERR_CANNOT_REVERSE_UNAPPROVED",
                    "Only APPROVED adjustments can be reversed.",
                    "শুধুমাত্র অনুমোদিত সমন্বয় রিভার্স করা সম্ভব।"
            );
        }

        String cleanedReason = reversalReason != null ? reversalReason.trim() : "";
        if (cleanedReason.length() < 10) {
            return ClientResponse.failure(
                    "ERR_AUDIT_REASON_REQUIRED",
                    "A valid reversal justification (minimum 10 characters) is mandatory.",
                    "কমপক্ষে ১০ অক্ষরের রিভার্সাল কারণ আবশ্যক।"
            );
        }

        int reversingDelta = -original.quantityDelta;
        if (reversingDelta < 0) {
            int availableUnreserved = currentPhysicalQty - currentReservedQty;
            if (availableUnreserved < Math.abs(reversingDelta)) {
                return ClientResponse.failure(
                        "ERR_INSUFFICIENT_UNRESERVED_STOCK",
                        "Cannot reverse adjustment due to active cart reservations.",
                        "সক্রিয় কার্ট রিজার্ভেশনের কারণে রিভার্স করা সম্ভব নয়।"
                );
            }
        }

        String revId = "stkadj-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        StockAdjustmentDto reversalRecord = new StockAdjustmentDto(
                revId,
                original.tenantId,
                original.branchId,
                original.productId,
                original.batchId,
                original.costLotId,
                original.adjustmentType,
                reversingDelta,
                original.unitCostBdt,
                original.totalCostImpact,
                "Reversal of " + original.adjustmentId + ": " + cleanedReason,
                CorrectionStatus.APPROVED,
                username,
                username,
                Instant.now(),
                original.adjustmentId,
                false,
                null,
                original.supportingDocumentRef
        );

        original.isReversed = true;
        original.status = CorrectionStatus.REVERSED;
        original.reversalReason = cleanedReason;

        ledgerStore.put(revId, reversalRecord);
        return ClientResponse.ok(reversalRecord, "Stock adjustment reversed successfully.", "স্টক সমন্বয় সফলভাবে রিভার্স করা হয়েছে।");
    }

    public Optional<StockAdjustmentDto> getAdjustment(String adjustmentId) {
        return Optional.ofNullable(ledgerStore.get(adjustmentId));
    }
}
