package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosStockAdjustmentClient
 * Architecture Decision: ADR-0205 (P205).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Client for Nontrivial Inventory Adjustments, Maker-Checker Workflows,
 * DGDA-compliant mandatory reason codes, and SHA-256 tamper-evident difference events.
 */
public class PosStockAdjustmentClient {

    public static final int NONTRIVIAL_QTY_THRESHOLD = 10;
    public static final BigDecimal NONTRIVIAL_COST_THRESHOLD = new BigDecimal("500.00");

    private static final Set<String> ALLOWED_PROPOSER_ROLES = Set.of(
            "PHARMACY_OWNER",
            "BRANCH_MANAGER",
            "PLATFORM_SUPER_ADMIN",
            "STAFF_PHARMACIST",
            "PHARMACIST",
            "INVENTORY_CLERK",
            "TENANT_OWNER"
    );

    private static final Set<String> ALLOWED_APPROVER_ROLES = Set.of(
            "PHARMACY_OWNER",
            "BRANCH_MANAGER",
            "PLATFORM_SUPER_ADMIN",
            "TENANT_OWNER"
    );

    public enum StockAdjustmentReasonCode {
        BREAKAGE_SPILLAGE("Breakage or Spillage", "ভাঙন বা তরল অপচয়"),
        EXPIRY_SPOILAGE("Expired / Deteriorated Medicine", "মেয়াদোত্তীর্ণ বা নষ্ট ওষুধ"),
        THEFT_PILFERAGE("Theft or Unaccounted Pilferage", "চুরি বা হিসাববিহীন অপচয়"),
        COLD_CHAIN_EXCURSION("Cold-Chain Temperature Excursion", "কোল্ড-চেইন তাপমাত্রা বিচ্যুতি"),
        DISPENSING_ERROR_REVERSAL("Dispensing / Billing Error Reversal", "বিতরণ বা বিলিং ত্রুটি সংশোধন"),
        SUPPLIER_SHORT_SHIPMENT("Supplier Short Shipment Variance", "সরবরাহকারীর ঘাটতি চালান"),
        REGULATORY_SEIZURE_DGDA("DGDA Regulatory Seizure / Quarantine", "ডিজিডিএ নিয়ন্ত্রক বাজেয়াপ্ত বা কোয়ারেন্টাইন"),
        AUDIT_COUNT_VARIANCE("Physical Audit Variance Correction", "শারীরিক অডিট গণনা পার্থক্য"),
        OTHER("Other Documented Adjustment", "অন্যান্য নথিভুক্ত সমন্বয়");

        public final String labelEn;
        public final String labelBn;

        StockAdjustmentReasonCode(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum StockAdjustmentStatus {
        PENDING("Pending Review", "অনুমোদনের অপেক্ষায়"),
        APPROVED("Approved & Posted", "অনুমোদিত ও পোস্টকৃত"),
        REJECTED("Rejected", "প্রত্যাখ্যাত"),
        REVERSED("Reversed / Offset", "বাতিল / বিপরীত সমন্বয়");

        public final String labelEn;
        public final String labelBn;

        StockAdjustmentStatus(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static final class StockAdjustmentRecordDto {
        public final String adjustmentId;
        public final String tenantId;
        public final String branchId;
        public final String productId;
        public final String productName;
        public final String batchNumber;
        public final int quantityDelta;
        public final BigDecimal unitCostBdt;
        public final BigDecimal totalCostImpactBdt;
        public final StockAdjustmentReasonCode reasonCode;
        public final String reasonNotes;
        public final boolean isNontrivial;
        public StockAdjustmentStatus status;
        public final String proposedByUserId;
        public String approvedByUserId;
        public Instant approvedAt;
        public String ledgerEntryId;
        public boolean isReversed;
        public String reversalReason;
        public String reversalOfId;
        public final String supportingDocumentRef;
        public final Instant createdAt;

        public StockAdjustmentRecordDto(
                String adjustmentId,
                String tenantId,
                String branchId,
                String productId,
                String productName,
                String batchNumber,
                int quantityDelta,
                BigDecimal unitCostBdt,
                BigDecimal totalCostImpactBdt,
                StockAdjustmentReasonCode reasonCode,
                String reasonNotes,
                boolean isNontrivial,
                StockAdjustmentStatus status,
                String proposedByUserId,
                String supportingDocumentRef,
                Instant createdAt
        ) {
            this.adjustmentId = adjustmentId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.productId = productId;
            this.productName = productName;
            this.batchNumber = batchNumber;
            this.quantityDelta = quantityDelta;
            this.unitCostBdt = unitCostBdt;
            this.totalCostImpactBdt = totalCostImpactBdt;
            this.reasonCode = reasonCode;
            this.reasonNotes = reasonNotes;
            this.isNontrivial = isNontrivial;
            this.status = status;
            this.proposedByUserId = proposedByUserId;
            this.supportingDocumentRef = supportingDocumentRef;
            this.createdAt = createdAt;
            this.isReversed = false;
        }
    }

    public static final class StockAdjustmentDifferenceEventDto {
        public final String eventId;
        public final String adjustmentId;
        public final String productName;
        public final String batchNumber;
        public final int preAdjustmentQuantity;
        public final int postAdjustmentQuantity;
        public final int quantityDelta;
        public final BigDecimal unitCostBdt;
        public final BigDecimal totalCostImpactBdt;
        public final boolean isNontrivial;
        public final StockAdjustmentReasonCode reasonCode;
        public final String ledgerEntryId;
        public final String immutableEventHash;
        public final Instant createdAt;

        public StockAdjustmentDifferenceEventDto(
                String eventId,
                String adjustmentId,
                String productName,
                String batchNumber,
                int preAdjustmentQuantity,
                int postAdjustmentQuantity,
                int quantityDelta,
                BigDecimal unitCostBdt,
                BigDecimal totalCostImpactBdt,
                boolean isNontrivial,
                StockAdjustmentReasonCode reasonCode,
                String ledgerEntryId,
                String immutableEventHash,
                Instant createdAt
        ) {
            this.eventId = eventId;
            this.adjustmentId = adjustmentId;
            this.productName = productName;
            this.batchNumber = batchNumber;
            this.preAdjustmentQuantity = preAdjustmentQuantity;
            this.postAdjustmentQuantity = postAdjustmentQuantity;
            this.quantityDelta = quantityDelta;
            this.unitCostBdt = unitCostBdt;
            this.totalCostImpactBdt = totalCostImpactBdt;
            this.isNontrivial = isNontrivial;
            this.reasonCode = reasonCode;
            this.ledgerEntryId = ledgerEntryId;
            this.immutableEventHash = immutableEventHash;
            this.createdAt = createdAt;
        }
    }

    private final Map<String, StockAdjustmentRecordDto> adjustments = new ConcurrentHashMap<>();
    private final Map<String, List<StockAdjustmentDifferenceEventDto>> differenceEvents = new ConcurrentHashMap<>();
    private final Map<String, Integer> batchStockQuantities = new ConcurrentHashMap<>();

    private int idCounter = 1000;

    public void setBatchStock(String batchNumber, int quantity) {
        batchStockQuantities.put(batchNumber, quantity);
    }

    public int getBatchStock(String batchNumber) {
        return batchStockQuantities.getOrDefault(batchNumber, 0);
    }

    public static boolean isNontrivialAdjustment(
            int quantityDelta,
            BigDecimal totalCost,
            boolean isNarcotic,
            boolean requiresColdChain,
            boolean isQuarantined
    ) {
        if (Math.abs(quantityDelta) >= NONTRIVIAL_QTY_THRESHOLD) {
            return true;
        }
        if (totalCost.abs().compareTo(NONTRIVIAL_COST_THRESHOLD) >= 0) {
            return true;
        }
        if (isNarcotic || requiresColdChain || isQuarantined) {
            return true;
        }
        return false;
    }

    public StockAdjustmentRecordDto proposeAdjustment(
            String tenantId,
            String branchId,
            String productId,
            String productName,
            String batchNumber,
            int quantityDelta,
            BigDecimal unitCost,
            StockAdjustmentReasonCode reasonCode,
            String reasonNotes,
            String supportingDocumentRef,
            String actorUserId,
            String actorRole,
            boolean isNarcotic,
            boolean requiresColdChain,
            boolean isQuarantined
    ) {
        if (actorRole != null && actorRole.equalsIgnoreCase("CASHIER")) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers cannot propose stock adjustments.");
        }
        if (actorRole != null && !ALLOWED_PROPOSER_ROLES.contains(actorRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role not authorized to propose stock adjustments.");
        }

        if (quantityDelta == 0) {
            throw new IllegalArgumentException("ERR_ZERO_DELTA: Stock adjustment quantity delta cannot be zero.");
        }

        if (reasonNotes == null || reasonNotes.trim().length() < 10) {
            throw new IllegalArgumentException("ERR_MANDATORY_REASON_REQUIRED: Reason must be at least 10 characters.");
        }

        if (reasonCode == null) {
            throw new IllegalArgumentException("ERR_REASON_CODE_REQUIRED: Reason code is required.");
        }

        int currentStock = getBatchStock(batchNumber);
        if (quantityDelta < 0 && (currentStock + quantityDelta) < 0) {
            throw new IllegalArgumentException("ERR_INSUFFICIENT_STOCK: Cannot reduce more than available batch stock.");
        }

        BigDecimal cost = unitCost != null ? unitCost : BigDecimal.ZERO;
        BigDecimal totalCost = cost.multiply(BigDecimal.valueOf(Math.abs(quantityDelta)));

        boolean nontrivial = isNontrivialAdjustment(quantityDelta, totalCost, isNarcotic, requiresColdChain, isQuarantined);

        String adjId = "adj-" + (idCounter++);
        StockAdjustmentRecordDto record = new StockAdjustmentRecordDto(
                adjId,
                tenantId,
                branchId,
                productId,
                productName,
                batchNumber,
                quantityDelta,
                cost,
                totalCost,
                reasonCode,
                reasonNotes.trim(),
                nontrivial,
                StockAdjustmentStatus.PENDING,
                actorUserId,
                supportingDocumentRef != null ? supportingDocumentRef.trim() : "",
                Instant.now()
        );

        adjustments.put(adjId, record);
        return record;
    }

    public StockAdjustmentRecordDto approveAdjustment(
            String adjustmentId,
            String checkerUserId,
            String checkerRole,
            String checkerNotes
    ) {
        StockAdjustmentRecordDto adj = adjustments.get(adjustmentId);
        if (adj == null) {
            throw new NoSuchElementException("Adjustment not found: " + adjustmentId);
        }

        if (checkerRole == null || !ALLOWED_APPROVER_ROLES.contains(checkerRole.toUpperCase())) {
            throw new SecurityException("ERR_APPROVER_ROLE_DENIED: Role not authorized to approve adjustments.");
        }

        if (adj.status == StockAdjustmentStatus.APPROVED) {
            throw new IllegalStateException("ERR_ALREADY_APPROVED: Adjustment has already been approved.");
        }

        if (adj.status != StockAdjustmentStatus.PENDING) {
            throw new IllegalStateException("Adjustment cannot be approved from status: " + adj.status);
        }

        // Maker-Checker separation of duties for nontrivial adjustments
        if (adj.isNontrivial && checkerUserId != null && checkerUserId.equals(adj.proposedByUserId)) {
            throw new IllegalStateException("ERR_MAKER_CHECKER_SELF_APPROVAL: Proposer cannot approve their own nontrivial adjustment.");
        }

        int preQty = getBatchStock(adj.batchNumber);
        int postQty = preQty + adj.quantityDelta;
        batchStockQuantities.put(adj.batchNumber, postQty);

        String ledgerEntryId = "stkled-" + (idCounter++);
        adj.status = StockAdjustmentStatus.APPROVED;
        adj.approvedByUserId = checkerUserId;
        adj.approvedAt = Instant.now();
        adj.ledgerEntryId = ledgerEntryId;

        // Generate SHA-256 hash for difference event
        String eventId = "adjevt-" + (idCounter++);
        String payloadToHash = eventId + "|" + adj.adjustmentId + "|" + adj.batchNumber + "|" + preQty + "|" + postQty + "|" + adj.quantityDelta + "|" + ledgerEntryId;
        String hash = computeSha256(payloadToHash);

        StockAdjustmentDifferenceEventDto diffEvent = new StockAdjustmentDifferenceEventDto(
                eventId,
                adj.adjustmentId,
                adj.productName,
                adj.batchNumber,
                preQty,
                postQty,
                adj.quantityDelta,
                adj.unitCostBdt,
                adj.totalCostImpactBdt,
                adj.isNontrivial,
                adj.reasonCode,
                ledgerEntryId,
                hash,
                Instant.now()
        );

        differenceEvents.computeIfAbsent(adj.adjustmentId, k -> new ArrayList<>()).add(diffEvent);
        return adj;
    }

    public StockAdjustmentRecordDto rejectAdjustment(
            String adjustmentId,
            String checkerUserId,
            String checkerRole,
            String rejectionReason
    ) {
        StockAdjustmentRecordDto adj = adjustments.get(adjustmentId);
        if (adj == null) {
            throw new NoSuchElementException("Adjustment not found: " + adjustmentId);
        }

        if (checkerRole == null || !ALLOWED_APPROVER_ROLES.contains(checkerRole.toUpperCase())) {
            throw new SecurityException("ERR_APPROVER_ROLE_DENIED: Role not authorized to reject adjustments.");
        }

        if (adj.status != StockAdjustmentStatus.PENDING) {
            throw new IllegalStateException("Cannot reject adjustment from status: " + adj.status);
        }

        if (rejectionReason == null || rejectionReason.trim().length() < 5) {
            throw new IllegalArgumentException("ERR_REJECTION_REASON_REQUIRED: Specific rejection explanation required.");
        }

        adj.status = StockAdjustmentStatus.REJECTED;
        adj.approvedByUserId = checkerUserId;
        adj.approvedAt = Instant.now();
        adj.reversalReason = rejectionReason.trim();
        return adj;
    }

    public StockAdjustmentRecordDto reverseAdjustment(
            String adjustmentId,
            String actorUserId,
            String actorRole,
            String reversalReason
    ) {
        StockAdjustmentRecordDto orig = adjustments.get(adjustmentId);
        if (orig == null) {
            throw new NoSuchElementException("Adjustment not found: " + adjustmentId);
        }

        if (actorRole == null || !ALLOWED_APPROVER_ROLES.contains(actorRole.toUpperCase())) {
            throw new SecurityException("ERR_APPROVER_ROLE_DENIED: Role not authorized to reverse adjustments.");
        }

        if (orig.status != StockAdjustmentStatus.APPROVED) {
            throw new IllegalStateException("Only APPROVED adjustments can be reversed.");
        }

        if (orig.isReversed) {
            throw new IllegalStateException("ERR_ALREADY_REVERSED: Adjustment has already been reversed.");
        }

        if (reversalReason == null || reversalReason.trim().length() < 10) {
            throw new IllegalArgumentException("ERR_REVERSAL_REASON_REQUIRED: Reversal reason must be at least 10 characters.");
        }

        int preQty = getBatchStock(orig.batchNumber);
        int revDelta = -orig.quantityDelta;
        int postQty = preQty + revDelta;
        batchStockQuantities.put(orig.batchNumber, postQty);

        orig.isReversed = true;
        orig.reversalReason = reversalReason.trim();

        String revId = "adj-rev-" + (idCounter++);
        String revLedgerEntryId = "stkled-" + (idCounter++);

        StockAdjustmentRecordDto revRecord = new StockAdjustmentRecordDto(
                revId,
                orig.tenantId,
                orig.branchId,
                orig.productId,
                orig.productName,
                orig.batchNumber,
                revDelta,
                orig.unitCostBdt,
                orig.totalCostImpactBdt,
                orig.reasonCode,
                "Offset reversal: " + reversalReason.trim(),
                orig.isNontrivial,
                StockAdjustmentStatus.APPROVED,
                actorUserId,
                "REV-" + orig.adjustmentId,
                Instant.now()
        );
        revRecord.reversalOfId = orig.adjustmentId;
        revRecord.approvedByUserId = actorUserId;
        revRecord.approvedAt = Instant.now();
        revRecord.ledgerEntryId = revLedgerEntryId;

        adjustments.put(revId, revRecord);

        // Generate reversal difference event
        String revEventId = "adjevt-" + (idCounter++);
        String payloadToHash = revEventId + "|" + revId + "|" + orig.batchNumber + "|" + preQty + "|" + postQty + "|" + revDelta + "|" + revLedgerEntryId;
        String hash = computeSha256(payloadToHash);

        StockAdjustmentDifferenceEventDto diffEvent = new StockAdjustmentDifferenceEventDto(
                revEventId,
                revId,
                orig.productName,
                orig.batchNumber,
                preQty,
                postQty,
                revDelta,
                orig.unitCostBdt,
                orig.totalCostImpactBdt,
                orig.isNontrivial,
                orig.reasonCode,
                revLedgerEntryId,
                hash,
                Instant.now()
        );
        differenceEvents.computeIfAbsent(revId, k -> new ArrayList<>()).add(diffEvent);

        return revRecord;
    }

    public List<StockAdjustmentDifferenceEventDto> getDifferenceEvents(String adjustmentId) {
        return differenceEvents.getOrDefault(adjustmentId, Collections.emptyList());
    }

    public StockAdjustmentRecordDto getAdjustment(String adjustmentId) {
        return adjustments.get(adjustmentId);
    }

    private static String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }
}

