package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosInventorySnapshotClient
 * Architecture Decision: ADR-0215 (P215).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Client for Inventory Snapshots:
 * - Read models strictly derived from canonical stock ledger movements.
 * - Point-in-time snapshots with mathematical reconciliation (batch balance == ledger delta sum).
 * - Cryptographic SHA-256 checksum over line items for regulatory compliance.
 * - Enforces role-based access control (Cashier denied).
 * - Fast local offline caching for POS checkouts and inventory inquiry.
 */
public class PosInventorySnapshotClient {

    public enum CheckpointType {
        DAILY_EOD("Daily End of Day", "দৈনিক কার্যদিবস সমাপ্তি"),
        MONTHLY_CLOSE("Monthly Accounting Close", "মাসিক হিসাব ক্লোজিং"),
        CYCLE_COUNT("Cycle Count Checkpoint", "সাইকেল কাউন্ট স্ন্যাপশট"),
        ON_DEMAND("On-Demand Snapshot", "অন-ডিমান্ড স্ন্যাপশট");

        public final String labelEn;
        public final String labelBn;

        CheckpointType(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static class SnapshotLineItem {
        private final String productId;
        private final String productName;
        private final String batchNumber;
        private final String storageLocation;
        private final int quantityBaseUnits;
        private final BigDecimal unitCost;
        private final BigDecimal totalValuation;
        private final boolean isColdChain;
        private final int ledgerDeltaSum;
        private final int discrepancy;

        public SnapshotLineItem(
                String productId,
                String productName,
                String batchNumber,
                String storageLocation,
                int quantityBaseUnits,
                BigDecimal unitCost,
                boolean isColdChain,
                int ledgerDeltaSum
        ) {
            this.productId = productId;
            this.productName = productName;
            this.batchNumber = batchNumber;
            this.storageLocation = storageLocation != null ? storageLocation : "";
            this.quantityBaseUnits = quantityBaseUnits;
            this.unitCost = unitCost != null ? unitCost : BigDecimal.ZERO;
            this.totalValuation = this.unitCost.multiply(BigDecimal.valueOf(quantityBaseUnits));
            this.isColdChain = isColdChain;
            this.ledgerDeltaSum = ledgerDeltaSum;
            this.discrepancy = quantityBaseUnits - ledgerDeltaSum;
        }

        public String getProductId() { return productId; }
        public String getProductName() { return productName; }
        public String getBatchNumber() { return batchNumber; }
        public String getStorageLocation() { return storageLocation; }
        public int getQuantityBaseUnits() { return quantityBaseUnits; }
        public BigDecimal getUnitCost() { return unitCost; }
        public BigDecimal getTotalValuation() { return totalValuation; }
        public boolean isColdChain() { return isColdChain; }
        public int getLedgerDeltaSum() { return ledgerDeltaSum; }
        public int getDiscrepancy() { return discrepancy; }
    }

    public static class SnapshotCheckpoint {
        private final String snapshotId;
        private final String tenantId;
        private final String branchId;
        private final CheckpointType checkpointType;
        private final Instant asOfTimestamp;
        private final int totalProducts;
        private final int totalBatches;
        private final int totalQuantity;
        private final BigDecimal totalValuation;
        private final int ledgerEntriesCount;
        private final String sha256Checksum;
        private final boolean isReconciled;
        private final List<SnapshotLineItem> lineItems;

        public SnapshotCheckpoint(
                String snapshotId,
                String tenantId,
                String branchId,
                CheckpointType checkpointType,
                Instant asOfTimestamp,
                int totalProducts,
                int totalBatches,
                int totalQuantity,
                BigDecimal totalValuation,
                int ledgerEntriesCount,
                String sha256Checksum,
                boolean isReconciled,
                List<SnapshotLineItem> lineItems
        ) {
            this.snapshotId = snapshotId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.checkpointType = checkpointType;
            this.asOfTimestamp = asOfTimestamp;
            this.totalProducts = totalProducts;
            this.totalBatches = totalBatches;
            this.totalQuantity = totalQuantity;
            this.totalValuation = totalValuation;
            this.ledgerEntriesCount = ledgerEntriesCount;
            this.sha256Checksum = sha256Checksum;
            this.isReconciled = isReconciled;
            this.lineItems = Collections.unmodifiableList(lineItems != null ? lineItems : Collections.emptyList());
        }

        public String getSnapshotId() { return snapshotId; }
        public String getTenantId() { return tenantId; }
        public String getBranchId() { return branchId; }
        public CheckpointType getCheckpointType() { return checkpointType; }
        public Instant getAsOfTimestamp() { return asOfTimestamp; }
        public int getTotalProducts() { return totalProducts; }
        public int getTotalBatches() { return totalBatches; }
        public int getTotalQuantity() { return totalQuantity; }
        public BigDecimal getTotalValuation() { return totalValuation; }
        public int getLedgerEntriesCount() { return ledgerEntriesCount; }
        public String getSha256Checksum() { return sha256Checksum; }
        public boolean isReconciled() { return isReconciled; }
        public List<SnapshotLineItem> getLineItems() { return lineItems; }
    }

    private final Map<String, SnapshotCheckpoint> snapshotCache = new ConcurrentHashMap<>();

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "PHARMACY_OWNER",
            "BRANCH_MANAGER",
            "SUPERADMIN",
            "STAFF_PHARMACIST",
            "PROCUREMENT_OFFICER",
            "INVENTORY_CLERK"
    );

    private void assertInventoryRole(String userRole) {
        if (userRole == null || "CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("Cashiers cannot generate or audit inventory snapshots. Access is restricted to Managers, Owners, and Pharmacists.");
        }
    }

    public static String computeChecksum(List<SnapshotLineItem> lines) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            List<SnapshotLineItem> sorted = new ArrayList<>(lines);
            sorted.sort(Comparator.comparing(SnapshotLineItem::getProductId).thenComparing(SnapshotLineItem::getBatchNumber));

            for (SnapshotLineItem l : sorted) {
                String token = l.getProductId() + "|" + l.getBatchNumber() + "|" + l.getQuantityBaseUnits() + "|" + l.getUnitCost() + "|" + l.getTotalValuation() + "\n";
                md.update(token.getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = md.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    public SnapshotCheckpoint generateSnapshot(
            String userRole,
            String tenantId,
            String branchId,
            CheckpointType checkpointType,
            List<SnapshotLineItem> lines,
            int ledgerEntriesCount
    ) {
        assertInventoryRole(userRole);

        Set<String> products = new HashSet<>();
        Set<String> batches = new HashSet<>();
        int totalQty = 0;
        BigDecimal totalVal = BigDecimal.ZERO;
        boolean allReconciled = true;

        for (SnapshotLineItem item : lines) {
            products.add(item.getProductId());
            batches.add(item.getBatchNumber());
            totalQty += item.getQuantityBaseUnits();
            totalVal = totalVal.add(item.getTotalValuation());
            if (item.getDiscrepancy() != 0) {
                allReconciled = false;
            }
        }

        String checksum = computeChecksum(lines);
        String id = "snap-" + UUID.randomUUID().toString().substring(0, 12);

        SnapshotCheckpoint checkpoint = new SnapshotCheckpoint(
                id,
                tenantId,
                branchId,
                checkpointType,
                Instant.now(),
                products.size(),
                batches.size(),
                totalQty,
                totalVal,
                ledgerEntriesCount,
                checksum,
                allReconciled,
                lines
        );

        snapshotCache.put(id, checkpoint);
        return checkpoint;
    }

    public boolean verifyIntegrity(SnapshotCheckpoint checkpoint) {
        String recomputed = computeChecksum(checkpoint.getLineItems());
        return recomputed.equals(checkpoint.getSha256Checksum());
    }

    public Optional<SnapshotCheckpoint> getSnapshot(String snapshotId) {
        return Optional.ofNullable(snapshotCache.get(snapshotId));
    }
}
