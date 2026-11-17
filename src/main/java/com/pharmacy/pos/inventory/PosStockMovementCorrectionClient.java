package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosStockMovementCorrectionClient
 * Architecture Decision: ADR-0217 (P217).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Client for Audited Stock Movement Corrections:
 * - Reverses incorrect posted inventory moves using compensating entries.
 * - Bars direct row overwrites and deletions on posted ledger entries.
 * - Enforces double-reversal prevention and negative stock guards.
 * - RBAC protection (Cashier strictly denied).
 */
public class PosStockMovementCorrectionClient {

    public enum CorrectionReason {
        MISTYPED_QUANTITY("Mistyped Quantity Count", "ভুল পরিমাণ টাইপ"),
        WRONG_BATCH_ALLOCATION("Wrong Batch Stock Selected", "ভুল ব্যাচ নির্বাচন"),
        DUPLICATE_POSTING("Duplicate Movement Posting", "ডুপ্লিকেট মুভমেন্ট পোস্টিং"),
        SYSTEM_GLITCH_RECOVERY("Sync Glitch Recovery", "সিংক ত্রুটি সংশোধন"),
        DAMAGED_IN_HANDLING("Damaged in Handling", "মুভমেন্টে ক্ষতিগ্রস্ত"),
        DGDA_AUDIT_ORDER("DGDA Audit Correction", "ডিজিডিএ অডিট নির্দেশ"),
        OTHER_OPERATIONAL_ERROR("Other Operational Correction", "অন্যান্য অপারেশনাল ত্রুটি");

        public final String labelEn;
        public final String labelBn;

        CorrectionReason(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum CorrectionStatus {
        COMPLETED("Compensating Movement Executed", "কমপেনসেটিং মুভমেন্ট সম্পন্ন"),
        REJECTED("Correction Rejected", "সংশোধন বাতিল");

        public final String labelEn;
        public final String labelBn;

        CorrectionStatus(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static class MovementCorrection {
        private final String correctionId;
        private final String tenantId;
        private final String branchId;
        private final String originalEntryId;
        private final String compensatingEntryId;
        private final String productId;
        private final String batchNumber;
        private final CorrectionReason reason;
        private final String explanation;
        private final int reversedQuantityDelta;
        private final BigDecimal unitCostBdt;
        private final BigDecimal totalCostImpactBdt;
        private final CorrectionStatus status;
        private final Instant createdAt;

        public MovementCorrection(
                String correctionId,
                String tenantId,
                String branchId,
                String originalEntryId,
                String compensatingEntryId,
                String productId,
                String batchNumber,
                CorrectionReason reason,
                String explanation,
                int reversedQuantityDelta,
                BigDecimal unitCostBdt,
                BigDecimal totalCostImpactBdt,
                CorrectionStatus status,
                Instant createdAt
        ) {
            this.correctionId = correctionId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.originalEntryId = originalEntryId;
            this.compensatingEntryId = compensatingEntryId;
            this.productId = productId;
            this.batchNumber = batchNumber;
            this.reason = reason;
            this.explanation = explanation;
            this.reversedQuantityDelta = reversedQuantityDelta;
            this.unitCostBdt = (unitCostBdt != null ? unitCostBdt : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            this.totalCostImpactBdt = (totalCostImpactBdt != null ? totalCostImpactBdt : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            this.status = status;
            this.createdAt = createdAt != null ? createdAt : Instant.now();
        }

        public String getCorrectionId() { return correctionId; }
        public String getTenantId() { return tenantId; }
        public String getBranchId() { return branchId; }
        public String getOriginalEntryId() { return originalEntryId; }
        public String getCompensatingEntryId() { return compensatingEntryId; }
        public String getProductId() { return productId; }
        public String getBatchNumber() { return batchNumber; }
        public CorrectionReason getReason() { return reason; }
        public String getExplanation() { return explanation; }
        public int getReversedQuantityDelta() { return reversedQuantityDelta; }
        public BigDecimal getUnitCostBdt() { return unitCostBdt; }
        public BigDecimal getTotalCostImpactBdt() { return totalCostImpactBdt; }
        public CorrectionStatus getStatus() { return status; }
        public Instant getCreatedAt() { return createdAt; }
    }

    private final Map<String, MovementCorrection> correctionCache = new ConcurrentHashMap<>();
    private final Set<String> reversedOriginalEntries = Collections.synchronizedSet(new HashSet<>());

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "PHARMACY_OWNER",
            "BRANCH_MANAGER",
            "SUPERADMIN",
            "STAFF_PHARMACIST",
            "PROCUREMENT_OFFICER",
            "CHIEF_PHARMACIST",
            "AUDITOR"
    );

    private void assertCorrectionRole(String userRole) {
        if (userRole == null || "CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("Cashiers cannot execute movement corrections. Restricted to Pharmacists and Managers.");
        }
    }

    public MovementCorrection executeCorrection(
            String userRole,
            String tenantId,
            String branchId,
            String originalEntryId,
            int originalDelta,
            int currentBatchStock,
            BigDecimal unitCostBdt,
            String productId,
            String batchNumber,
            CorrectionReason reason,
            String explanation
    ) {
        assertCorrectionRole(userRole);

        if (explanation == null || explanation.trim().length() < 5) {
            throw new IllegalArgumentException("A descriptive explanation of at least 5 characters is required.");
        }

        if (reversedOriginalEntries.contains(originalEntryId)) {
            throw new IllegalStateException("Original stock movement entry " + originalEntryId + " has already been reversed.");
        }

        int reversedDelta = -originalDelta;
        int newBalance = currentBatchStock + reversedDelta;

        if (newBalance < 0) {
            throw new IllegalStateException("Reversal of " + reversedDelta + " units would cause negative batch stock (current: " + currentBatchStock + ").");
        }

        BigDecimal unitCost = (unitCostBdt != null ? unitCostBdt : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalCost = unitCost.multiply(BigDecimal.valueOf(Math.abs(reversedDelta))).setScale(2, RoundingMode.HALF_UP);

        String correctionId = "correc-" + UUID.randomUUID().toString().substring(0, 12);
        String compensatingEntryId = "stkld-rev-" + UUID.randomUUID().toString().substring(0, 10);

        MovementCorrection correction = new MovementCorrection(
                correctionId,
                tenantId,
                branchId,
                originalEntryId,
                compensatingEntryId,
                productId,
                batchNumber,
                reason,
                explanation,
                reversedDelta,
                unitCost,
                totalCost,
                CorrectionStatus.COMPLETED,
                Instant.now()
        );

        correctionCache.put(correctionId, correction);
        reversedOriginalEntries.add(originalEntryId);

        return correction;
    }

    public Optional<MovementCorrection> getCorrection(String correctionId) {
        return Optional.ofNullable(correctionCache.get(correctionId));
    }

    public boolean isReversed(String originalEntryId) {
        return reversedOriginalEntries.contains(originalEntryId);
    }
}
