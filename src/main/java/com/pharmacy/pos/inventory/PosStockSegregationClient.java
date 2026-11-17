package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosStockSegregationClient
 * Architecture Decision: ADR-0207 (P207).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Client for Damaged, Suspicious, Recalled, and Returned Stock Segregation:
 * - Immediate deduction from live sellable stock availability.
 * - Mandatory documented release rules (pharmacist QA justification >= 15 chars).
 * - Guardrail against releasing recalled or counterfeit pharmaceuticals.
 */
public class PosStockSegregationClient {

    public enum SegregationCategory {
        DAMAGED("Damaged / Broken Packaging", "ভাঙন বা ক্ষতিগ্রস্ত প্যাকেজিং"),
        SUSPICIOUS_COUNTERFEIT("Suspicious / Suspected Counterfeit", "সন্দেহভাজন নকল ওষুধ"),
        RECALLED("Manufacturer / DGDA Recalled Lot", "উত্পাদক বা ডিজিডিএ প্রত্যাহারকৃত লট"),
        RETURNED_CUSTOMER("Customer Returned", "গ্রাহক ফেরত"),
        COLD_CHAIN_BREACH("Suspected Cold Chain Breach", "কোল্ড চেইন বিঘ্নিত");

        public final String labelEn;
        public final String labelBn;

        SegregationCategory(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum SegregationStatus {
        SEGREGATED("Held in Segregation", "পৃথকীকরণে সংরক্ষিত"),
        RELEASED_TO_SELLABLE("Released to Sellable", "বিক্রয়যোগ্য স্টকে অবমুক্ত"),
        MOVED_TO_QUARANTINE("Escalated to Quarantine", "কোয়ারেন্টাইনে স্থানান্তরিত"),
        RETURNED_TO_SUPPLIER("Returned to Supplier", "সরবরাহকারীকে ফেরত"),
        DISPOSED_WRITTEN_OFF("Disposed & Written Off", "অপসারিত ও অবলোপনকৃত");

        public final String labelEn;
        public final String labelBn;

        SegregationStatus(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static final class BatchStockDto {
        public final String batchId;
        public final String batchNumber;
        public int quantityBaseUnits;
        public int reservedQuantityBaseUnits;
        public BigDecimal unitCost;
        public String storageLocation;
        public boolean isExpired;

        public BatchStockDto(String batchId, String batchNumber, int quantityBaseUnits, BigDecimal unitCost, String storageLocation, boolean isExpired) {
            this.batchId = batchId;
            this.batchNumber = batchNumber;
            this.quantityBaseUnits = quantityBaseUnits;
            this.reservedQuantityBaseUnits = 0;
            this.unitCost = unitCost;
            this.storageLocation = storageLocation;
            this.isExpired = isExpired;
        }

        public int getAvailableQuantity() {
            return Math.max(0, quantityBaseUnits - reservedQuantityBaseUnits);
        }
    }

    public static final class SegregationRecordDto {
        public final String segregationId;
        public final String tenantId;
        public final String branchId;
        public final String batchId;
        public final String batchNumber;
        public final int segregatedQuantity;
        public final BigDecimal unitCostBdt;
        public final BigDecimal totalCostImpactBdt;
        public final SegregationCategory category;
        public SegregationStatus status;
        public String segregationLocation;
        public final String reasonNotes;
        public final String segregatedByUserId;
        public final Instant segregatedAt;
        public String inspectedByUserId;
        public Instant inspectedAt;
        public String releaseJustification;
        public String actionedByUserId;
        public Instant actionedAt;
        public String dispositionReference;
        public String ledgerEntryId;
        public final String immutableHash;

        public SegregationRecordDto(
                String segregationId,
                String tenantId,
                String branchId,
                String batchId,
                String batchNumber,
                int segregatedQuantity,
                BigDecimal unitCostBdt,
                BigDecimal totalCostImpactBdt,
                SegregationCategory category,
                SegregationStatus status,
                String segregationLocation,
                String reasonNotes,
                String segregatedByUserId,
                String immutableHash
        ) {
            this.segregationId = segregationId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.batchId = batchId;
            this.batchNumber = batchNumber;
            this.segregatedQuantity = segregatedQuantity;
            this.unitCostBdt = unitCostBdt;
            this.totalCostImpactBdt = totalCostImpactBdt;
            this.category = category;
            this.status = status;
            this.segregationLocation = segregationLocation;
            this.reasonNotes = reasonNotes;
            this.segregatedByUserId = segregatedByUserId;
            this.segregatedAt = Instant.now();
            this.immutableHash = immutableHash;
        }
    }

    private static final Set<String> ALLOWED_STAFF_ROLES = Set.of(
            "PHARMACY_OWNER", "BRANCH_MANAGER", "PLATFORM_SUPER_ADMIN",
            "STAFF_PHARMACIST", "PHARMACIST", "INVENTORY_CLERK", "TENANT_OWNER"
    );

    private static final Set<String> ALLOWED_QA_ROLES = Set.of(
            "PHARMACY_OWNER", "BRANCH_MANAGER", "PLATFORM_SUPER_ADMIN",
            "STAFF_PHARMACIST", "PHARMACIST", "TENANT_OWNER"
    );

    private final Map<String, BatchStockDto> batches = new ConcurrentHashMap<>();
    private final Map<String, SegregationRecordDto> records = new ConcurrentHashMap<>();
    private int counter = 1000;

    public void registerBatch(BatchStockDto batch) {
        batches.put(batch.batchId, batch);
    }

    public BatchStockDto getBatch(String batchId) {
        return batches.get(batchId);
    }

    public SegregationRecordDto segregateStock(
            String tenantId,
            String branchId,
            String batchId,
            int quantity,
            SegregationCategory category,
            String notes,
            String location,
            String actorUserId,
            String actorRole
    ) {
        if (actorRole != null && actorRole.equalsIgnoreCase("CASHIER")) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers cannot segregate inventory.");
        }
        if (actorRole != null && !ALLOWED_STAFF_ROLES.contains(actorRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role not authorized.");
        }

        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero.");
        }

        BatchStockDto batch = batches.get(batchId);
        if (batch == null) {
            throw new NoSuchElementException("Batch not found: " + batchId);
        }

        if (quantity > batch.getAvailableQuantity()) {
            throw new IllegalArgumentException("Cannot segregate more than available units.");
        }

        // Deduct sellable availability by holding in reserved units
        batch.reservedQuantityBaseUnits += quantity;

        String segId = "seg-" + (counter++);
        BigDecimal totalCost = batch.unitCost.multiply(BigDecimal.valueOf(quantity));
        String loc = (location != null && !location.isBlank()) ? location.trim() : "SEGREGATION-HOLD";
        String raw = segId + "|" + batch.batchNumber + "|" + quantity + "|" + category.name();
        String hash = computeSha256(raw);

        SegregationRecordDto rec = new SegregationRecordDto(
                segId, tenantId, branchId, batch.batchId, batch.batchNumber,
                quantity, batch.unitCost, totalCost, category,
                SegregationStatus.SEGREGATED, loc, notes, actorUserId, hash
        );
        records.put(segId, rec);
        return rec;
    }

    public SegregationRecordDto releaseToSellable(
            String segregationId,
            String justification,
            String newStorageLocation,
            String inspectorUserId,
            String inspectorRole
    ) {
        if (inspectorRole == null || !ALLOWED_QA_ROLES.contains(inspectorRole.toUpperCase())) {
            throw new SecurityException("ERR_QA_ROLE_REQUIRED: QA authority required to release stock.");
        }

        SegregationRecordDto rec = records.get(segregationId);
        if (rec == null) throw new NoSuchElementException("Record not found.");

        if (rec.status != SegregationStatus.SEGREGATED) {
            throw new IllegalStateException("Cannot release record from status: " + rec.status);
        }

        if (rec.category == SegregationCategory.RECALLED || rec.category == SegregationCategory.SUSPICIOUS_COUNTERFEIT) {
            throw new IllegalStateException("ERR_PROHIBITED_RELEASE: Recalled or counterfeit items cannot be released.");
        }

        BatchStockDto batch = batches.get(rec.batchId);
        if (batch != null && batch.isExpired) {
            throw new IllegalStateException("ERR_EXPIRED_BATCH_CANNOT_BE_RELEASED: Cannot release expired batch.");
        }

        if (justification == null || justification.trim().length() < 15) {
            throw new IllegalArgumentException("ERR_RELEASE_JUSTIFICATION_REQUIRED: Detailed justification (min 15 chars) required.");
        }

        // Restore sellable availability
        if (batch != null) {
            batch.reservedQuantityBaseUnits = Math.max(0, batch.reservedQuantityBaseUnits - rec.segregatedQuantity);
            if (newStorageLocation != null && !newStorageLocation.isBlank()) {
                batch.storageLocation = newStorageLocation.trim();
            }
        }

        rec.status = SegregationStatus.RELEASED_TO_SELLABLE;
        rec.inspectedByUserId = inspectorUserId;
        rec.inspectedAt = Instant.now();
        rec.releaseJustification = justification.trim();
        rec.actionedByUserId = inspectorUserId;
        rec.actionedAt = Instant.now();
        return rec;
    }

    public SegregationRecordDto disposeOrReturnStock(
            String segregationId,
            SegregationStatus actionType,
            String dispRef,
            String notes,
            String actorUserId,
            String actorRole
    ) {
        if (actorRole == null || !ALLOWED_QA_ROLES.contains(actorRole.toUpperCase())) {
            throw new SecurityException("ERR_QA_ROLE_REQUIRED: QA authority required.");
        }

        SegregationRecordDto rec = records.get(segregationId);
        if (rec == null) throw new NoSuchElementException("Record not found.");

        BatchStockDto batch = batches.get(rec.batchId);
        if (batch != null) {
            batch.quantityBaseUnits = Math.max(0, batch.quantityBaseUnits - rec.segregatedQuantity);
            batch.reservedQuantityBaseUnits = Math.max(0, batch.reservedQuantityBaseUnits - rec.segregatedQuantity);
        }

        rec.status = actionType;
        rec.actionedByUserId = actorUserId;
        rec.actionedAt = Instant.now();
        rec.dispositionReference = dispRef;
        rec.ledgerEntryId = "stkled-" + (counter++);
        return rec;
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
