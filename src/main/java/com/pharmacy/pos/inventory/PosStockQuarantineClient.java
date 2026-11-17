package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosStockQuarantineClient
 * Architecture Decision: ADR-0206 (P206).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Client for Expired Stock Quarantine:
 * - Prohibits sale of expired or quarantined batches at checkout.
 * - Automated expired batch detection and quarantine holding.
 * - Disposal, vendor return, and supervised destruction lifecycles.
 */
public class PosStockQuarantineClient {

    public enum QuarantineReason {
        BATCH_EXPIRED("Batch Passed Expiry Date", "মেয়াদোত্তীর্ণ ব্যাচ"),
        NEAR_EXPIRY_THRESHOLD("Near-Expiry Sale Cutoff", "মেয়াদ শেষের কাছাকাছি বিক্রয় সীমাবদ্ধতা"),
        COLD_CHAIN_EXCURSION("Cold-Chain Temperature Breach", "কোল্ড-চেইন তাপমাত্রা বিচ্যুতি"),
        QUALITY_DEFECT("Quality / Blister Foil Defect", "গুণগত ত্রুটি / নষ্ট ফয়েল"),
        DGDA_MANDATE("DGDA Regulatory Mandate", "ডিজিডিএ নিয়ন্ত্রক নির্দেশনা"),
        RECALL("Manufacturer Batch Recall", "উত্পাদক কর্তৃক প্রত্যাহার");

        public final String labelEn;
        public final String labelBn;

        QuarantineReason(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum QuarantineStatus {
        QUARANTINED("Held in Quarantine", "কোয়ারেন্টাইনে সংরক্ষিত"),
        RELEASED("Released to Active Stock", "সক্রিয় স্টকে ছাড়কৃত"),
        DISPOSED("Safely Disposed", "নিরাপদে অপসারিত"),
        RETURNED_TO_SUPPLIER("Returned to Supplier", "সরবরাহকারীকে ফেরত"),
        DESTROYED("Destroyed Supervised", "তত্ত্বাবধানে ধ্বংসকৃত");

        public final String labelEn;
        public final String labelBn;

        QuarantineStatus(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static final class BatchDto {
        public final String batchId;
        public final String batchNumber;
        public final LocalDate expiryDate;
        public int quantityBaseUnits;
        public BigDecimal unitCost;
        public String storageLocation;
        public boolean isQuarantined;
        public String status;

        public BatchDto(String batchId, String batchNumber, LocalDate expiryDate, int quantityBaseUnits, BigDecimal unitCost, String storageLocation) {
            this.batchId = batchId;
            this.batchNumber = batchNumber;
            this.expiryDate = expiryDate;
            this.quantityBaseUnits = quantityBaseUnits;
            this.unitCost = unitCost;
            this.storageLocation = storageLocation;
            this.isQuarantined = false;
            this.status = "ACTIVE";
        }
    }

    public static final class QuarantineRecordDto {
        public final String quarantineId;
        public final String tenantId;
        public final String branchId;
        public final String batchId;
        public final String batchNumber;
        public final int quarantinedQuantity;
        public final BigDecimal unitCostBdt;
        public final BigDecimal totalCostImpactBdt;
        public final QuarantineReason reason;
        public QuarantineStatus status;
        public final String quarantineNotes;
        public String quarantineLocation;
        public final String quarantinedByUserId;
        public final Instant quarantinedAt;
        public String actionedByUserId;
        public Instant actionedAt;
        public String actionNotes;
        public String disposalCertificateRef;
        public String ledgerEntryId;
        public final String immutableHash;

        public QuarantineRecordDto(
                String quarantineId,
                String tenantId,
                String branchId,
                String batchId,
                String batchNumber,
                int quarantinedQuantity,
                BigDecimal unitCostBdt,
                BigDecimal totalCostImpactBdt,
                QuarantineReason reason,
                QuarantineStatus status,
                String quarantineNotes,
                String quarantineLocation,
                String quarantinedByUserId,
                String immutableHash
        ) {
            this.quarantineId = quarantineId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.batchId = batchId;
            this.batchNumber = batchNumber;
            this.quarantinedQuantity = quarantinedQuantity;
            this.unitCostBdt = unitCostBdt;
            this.totalCostImpactBdt = totalCostImpactBdt;
            this.reason = reason;
            this.status = status;
            this.quarantineNotes = quarantineNotes;
            this.quarantineLocation = quarantineLocation;
            this.quarantinedByUserId = quarantinedByUserId;
            this.quarantinedAt = Instant.now();
            this.immutableHash = immutableHash;
        }
    }

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "PHARMACY_OWNER", "BRANCH_MANAGER", "PLATFORM_SUPER_ADMIN",
            "STAFF_PHARMACIST", "PHARMACIST", "INVENTORY_CLERK", "TENANT_OWNER"
    );

    private static final Set<String> ACTION_ROLES = Set.of(
            "PHARMACY_OWNER", "BRANCH_MANAGER", "PLATFORM_SUPER_ADMIN", "TENANT_OWNER"
    );

    private final Map<String, BatchDto> batches = new ConcurrentHashMap<>();
    private final Map<String, QuarantineRecordDto> records = new ConcurrentHashMap<>();
    private int counter = 1000;

    public void registerBatch(BatchDto batch) {
        batches.put(batch.batchId, batch);
    }

    public BatchDto getBatch(String batchId) {
        return batches.get(batchId);
    }

    public void assertSaleAllowed(String batchId) {
        BatchDto batch = batches.get(batchId);
        if (batch == null) {
            throw new NoSuchElementException("Batch not found: " + batchId);
        }
        if (batch.isQuarantined || "QUARANTINED".equalsIgnoreCase(batch.status)) {
            throw new IllegalStateException("ERR_EXPIRED_STOCK_SALE_PROHIBITED: Batch is held in quarantine.");
        }
        if (batch.expiryDate.isBefore(LocalDate.now()) || batch.expiryDate.isEqual(LocalDate.now())) {
            throw new IllegalStateException("ERR_EXPIRED_STOCK_SALE_PROHIBITED: Batch is expired.");
        }
    }

    public QuarantineRecordDto quarantineBatch(
            String tenantId,
            String branchId,
            String batchId,
            QuarantineReason reason,
            String notes,
            String quarantineLocation,
            String actorUserId,
            String actorRole
    ) {
        if (actorRole != null && actorRole.equalsIgnoreCase("CASHIER")) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers cannot quarantine inventory.");
        }
        if (actorRole != null && !ALLOWED_ROLES.contains(actorRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role not authorized.");
        }

        BatchDto batch = batches.get(batchId);
        if (batch == null) {
            throw new NoSuchElementException("Batch not found: " + batchId);
        }

        if (batch.isQuarantined) {
            throw new IllegalStateException("ERR_ALREADY_QUARANTINED: Batch is already quarantined.");
        }

        String loc = (quarantineLocation != null && !quarantineLocation.isBlank()) ? quarantineLocation.trim() : "QUARANTINE-ZONE";
        batch.isQuarantined = true;
        batch.status = "QUARANTINED";
        batch.storageLocation = loc;

        String qId = "quar-" + (counter++);
        BigDecimal totalCost = batch.unitCost.multiply(BigDecimal.valueOf(batch.quantityBaseUnits));
        String raw = qId + "|" + batch.batchNumber + "|" + batch.quantityBaseUnits + "|" + reason.name();
        String hash = computeSha256(raw);

        QuarantineRecordDto rec = new QuarantineRecordDto(
                qId, tenantId, branchId, batch.batchId, batch.batchNumber,
                batch.quantityBaseUnits, batch.unitCost, totalCost,
                reason, QuarantineStatus.QUARANTINED, notes, loc, actorUserId, hash
        );
        rec.ledgerEntryId = "stkled-" + (counter++);
        records.put(qId, rec);
        return rec;
    }

    public List<QuarantineRecordDto> sweepExpiredBatches(String tenantId, String branchId, String actorUserId, String actorRole) {
        List<QuarantineRecordDto> swept = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (BatchDto b : batches.values()) {
            if (!b.isQuarantined && (b.expiryDate.isBefore(today) || b.expiryDate.isEqual(today)) && b.quantityBaseUnits > 0) {
                swept.add(quarantineBatch(tenantId, branchId, b.batchId, QuarantineReason.BATCH_EXPIRED, "Automated sweep", "QUARANTINE-ZONE", actorUserId, actorRole));
            }
        }
        return swept;
    }

    public QuarantineRecordDto approveDisposal(
            String quarantineId,
            QuarantineStatus actionType,
            String certRef,
            String actionNotes,
            String actorUserId,
            String actorRole
    ) {
        if (actorRole == null || !ACTION_ROLES.contains(actorRole.toUpperCase())) {
            throw new SecurityException("ERR_ACTION_ROLE_DENIED: Manager privilege required.");
        }

        QuarantineRecordDto rec = records.get(quarantineId);
        if (rec == null) throw new NoSuchElementException("Quarantine record not found.");
        if (rec.status != QuarantineStatus.QUARANTINED) {
            throw new IllegalStateException("Cannot action non-quarantined record.");
        }

        BatchDto batch = batches.get(rec.batchId);
        if (batch != null) {
            batch.quantityBaseUnits = 0;
            batch.status = "DEPLETED";
        }

        rec.status = actionType;
        rec.actionedByUserId = actorUserId;
        rec.actionedAt = Instant.now();
        rec.disposalCertificateRef = certRef;
        rec.actionNotes = actionNotes;
        return rec;
    }

    public QuarantineRecordDto releaseQuarantine(
            String quarantineId,
            String releaseReason,
            String newStorageLocation,
            String actorUserId,
            String actorRole
    ) {
        if (actorRole == null || !ACTION_ROLES.contains(actorRole.toUpperCase())) {
            throw new SecurityException("ERR_ACTION_ROLE_DENIED: Manager privilege required.");
        }

        QuarantineRecordDto rec = records.get(quarantineId);
        if (rec == null) throw new NoSuchElementException("Quarantine record not found.");

        BatchDto batch = batches.get(rec.batchId);
        if (batch != null && (batch.expiryDate.isBefore(LocalDate.now()) || batch.expiryDate.isEqual(LocalDate.now()))) {
            throw new IllegalStateException("ERR_EXPIRED_BATCH_CANNOT_BE_RELEASED: Cannot release expired batch.");
        }

        if (releaseReason == null || releaseReason.trim().length() < 10) {
            throw new IllegalArgumentException("Release reason must be at least 10 characters.");
        }

        if (batch != null) {
            batch.isQuarantined = false;
            batch.status = "ACTIVE";
            batch.storageLocation = newStorageLocation;
        }

        rec.status = QuarantineStatus.RELEASED;
        rec.actionedByUserId = actorUserId;
        rec.actionedAt = Instant.now();
        rec.actionNotes = releaseReason.trim();
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

