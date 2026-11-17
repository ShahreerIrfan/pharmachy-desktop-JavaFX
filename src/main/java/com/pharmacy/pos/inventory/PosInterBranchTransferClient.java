package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosInterBranchTransferClient
 * Architecture Decision: ADR-0208, ADR-0209, ADR-0210 (P210).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Client for Interbranch Transfer Requests, Reconciliation, and Lot Cost Continuity:
 * - Carries original batch identity, manufacturer expiry, and lot/cost trace across branches.
 * - Enforces transfer-cost policy (AT_COST, LANDED_MARKUP, FIXED_FEE).
 * - Strictly prohibits altering or extending pharmaceutical expiry date in transit.
 * - Computes accurate weighted-average unit cost when merged into existing branch inventory.
 */
public class PosInterBranchTransferClient {

    public enum TransferStatus {
        REQUESTED("Transfer Requested", "স্থানান্তর অনুরোধকৃত"),
        APPROVED("Transfer Approved", "স্থানান্তর অনুমোদিত"),
        DISPATCHED("In Transit / Dispatched", "চালান প্রেরণকৃত"),
        RECEIVED("Received & Fully Reconciled", "গৃহীত ও সম্পূর্ণরূপে সমন্বিত"),
        PARTIALLY_RECEIVED("Partially Received", "আংশিক প্রাপ্ত"),
        RECONCILED("Reconciled with Discrepancy", "অনৈক্যসহ সমন্বিত"),
        REJECTED("Transfer Rejected", "স্থানান্তর প্রত্যাখ্যাত"),
        CANCELLED("Transfer Cancelled", "স্থানান্তর বাতিলকৃত");

        public final String labelEn;
        public final String labelBn;

        TransferStatus(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum TransferPriority {
        STANDARD("Standard", "সাধারণ"),
        URGENT("Urgent", "জরুরী"),
        EMERGENCY("Emergency / Life-Saving", "জরুরী জীবনরক্ষাকারী");

        public final String labelEn;
        public final String labelBn;

        TransferPriority(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum TransferCostPolicy {
        AT_COST("At Original Purchase Lot Cost (Zero Markup)", "মূল ক্রয়মূল্যে (কোনো অতিরিক্ত চার্জ ছাড়া)"),
        LANDED_MARKUP("Percentage Logistics Markup", "শতাংশ লজিস্টিকস মার্কআপ"),
        FIXED_FEE("Flat Allocated Transfer Fee", "নির্দিষ্ট হ্যান্ডলিং ফি");

        public final String labelEn;
        public final String labelBn;

        TransferCostPolicy(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static final class TransferItemDto {
        public final String itemId;
        public final String batchId;
        public final String medicineId;
        public final String medicineName;
        public final String batchNumber;
        public final String lotReference;
        public final String expiryDate;
        public final int quantityRequested;
        public int quantityDispatched;
        public int quantityReceived;
        public int quantityReceivedGood;
        public int quantityReceivedDamaged;
        public int quantityLost;
        public BigDecimal originalCostPerUnit;
        public BigDecimal transferredCostPerUnit;
        public String discrepancyReason;
        public String damageStorageLocation;
        public boolean isReconciled;

        public TransferItemDto(
                String itemId,
                String batchId,
                String medicineId,
                String medicineName,
                String batchNumber,
                String lotReference,
                String expiryDate,
                int quantityRequested,
                BigDecimal originalCostPerUnit,
                BigDecimal transferredCostPerUnit
        ) {
            this.itemId = itemId != null ? itemId : UUID.randomUUID().toString();
            this.batchId = batchId;
            this.medicineId = medicineId;
            this.medicineName = medicineName;
            this.batchNumber = batchNumber;
            this.lotReference = lotReference != null ? lotReference : batchNumber;
            this.expiryDate = expiryDate != null ? expiryDate : "2027-12-31";
            this.quantityRequested = quantityRequested;
            this.originalCostPerUnit = originalCostPerUnit != null ? originalCostPerUnit : BigDecimal.ZERO;
            this.transferredCostPerUnit = transferredCostPerUnit != null ? transferredCostPerUnit : this.originalCostPerUnit;
            this.quantityDispatched = 0;
            this.quantityReceived = 0;
            this.quantityReceivedGood = 0;
            this.quantityReceivedDamaged = 0;
            this.quantityLost = 0;
            this.discrepancyReason = "";
            this.damageStorageLocation = "";
            this.isReconciled = false;
        }
    }

    public static final class ReconciliationLineDto {
        public final String itemId;
        public final int quantityGood;
        public final int quantityDamaged;
        public final int quantityLost;
        public final String discrepancyReason;
        public final String damageStorageLocation;

        public ReconciliationLineDto(
                String itemId,
                int quantityGood,
                int quantityDamaged,
                int quantityLost,
                String discrepancyReason,
                String damageStorageLocation
        ) {
            this.itemId = itemId;
            this.quantityGood = quantityGood;
            this.quantityDamaged = quantityDamaged;
            this.quantityLost = quantityLost;
            this.discrepancyReason = discrepancyReason != null ? discrepancyReason : "";
            this.damageStorageLocation = damageStorageLocation != null ? damageStorageLocation : "QUARANTINE-DAMAGED";
        }
    }

    public static final class TransferRequestDto {
        public final String transferId;
        public final String transferNumber;
        public final String tenantId;
        public final String sourceBranchId;
        public final String destinationBranchId;
        public TransferStatus status;
        public final TransferPriority priority;
        public final TransferCostPolicy costPolicy;
        public final BigDecimal markupPercentage;
        public final BigDecimal logisticsFeeBdt;
        public final String notes;
        public final String requestedBy;
        public String approvedBy;
        public String dispatchedBy;
        public String receivedBy;
        public String reconciledBy;
        public Instant reconciledAt;
        public String reconciliationNotes;
        public String rejectionReason;
        public int totalUnitsRequested;
        public int totalUnitsDispatched;
        public int totalUnitsReceived;
        public int totalUnitsReceivedGood;
        public int totalUnitsReceivedDamaged;
        public int totalUnitsLost;
        public final List<TransferItemDto> items;
        public final Instant createdAt;
        public Instant updatedAt;

        public TransferRequestDto(
                String transferId,
                String transferNumber,
                String tenantId,
                String sourceBranchId,
                String destinationBranchId,
                TransferPriority priority,
                TransferCostPolicy costPolicy,
                BigDecimal markupPercentage,
                BigDecimal logisticsFeeBdt,
                String notes,
                String requestedBy,
                List<TransferItemDto> items
        ) {
            this.transferId = transferId != null ? transferId : UUID.randomUUID().toString();
            this.transferNumber = transferNumber != null ? transferNumber : "TRF-" + System.currentTimeMillis();
            this.tenantId = tenantId;
            this.sourceBranchId = sourceBranchId;
            this.destinationBranchId = destinationBranchId;
            this.status = TransferStatus.REQUESTED;
            this.priority = priority != null ? priority : TransferPriority.STANDARD;
            this.costPolicy = costPolicy != null ? costPolicy : TransferCostPolicy.AT_COST;
            this.markupPercentage = markupPercentage != null ? markupPercentage : BigDecimal.ZERO;
            this.logisticsFeeBdt = logisticsFeeBdt != null ? logisticsFeeBdt : BigDecimal.ZERO;
            this.notes = notes;
            this.requestedBy = requestedBy;
            this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
            this.createdAt = Instant.now();
            this.updatedAt = Instant.now();
            this.totalUnitsRequested = 0;
            this.totalUnitsDispatched = 0;
            this.totalUnitsReceived = 0;
            this.totalUnitsReceivedGood = 0;
            this.totalUnitsReceivedDamaged = 0;
            this.totalUnitsLost = 0;
        }
    }

    public static final class BatchStockDto {
        public final String batchId;
        public final String branchId;
        public final String medicineId;
        public final String batchNumber;
        public final String lotReference;
        public final String expiryDate;
        public int quantityBaseUnits;
        public int reservedQuantityBaseUnits;
        public BigDecimal costPerUnit;
        public BigDecimal originalCostPerUnit;
        public BigDecimal transferMarkupBdt;
        public String originBatchId;
        public String originBranchId;
        public boolean isQuarantined;

        public BatchStockDto(
                String batchId,
                String branchId,
                String medicineId,
                String batchNumber,
                String lotReference,
                String expiryDate,
                int quantityBaseUnits,
                BigDecimal costPerUnit
        ) {
            this.batchId = batchId;
            this.branchId = branchId;
            this.medicineId = medicineId;
            this.batchNumber = batchNumber;
            this.lotReference = lotReference != null ? lotReference : batchNumber;
            this.expiryDate = expiryDate != null ? expiryDate : "2027-12-31";
            this.quantityBaseUnits = quantityBaseUnits;
            this.reservedQuantityBaseUnits = 0;
            this.costPerUnit = costPerUnit != null ? costPerUnit : BigDecimal.ZERO;
            this.originalCostPerUnit = this.costPerUnit;
            this.transferMarkupBdt = BigDecimal.ZERO;
            this.originBatchId = null;
            this.originBranchId = null;
            this.isQuarantined = false;
        }

        public int getAvailableQuantity() {
            if (isQuarantined) return 0;
            return Math.max(0, quantityBaseUnits - reservedQuantityBaseUnits);
        }
    }

    private final Map<String, TransferRequestDto> transfers = new ConcurrentHashMap<>();
    private final Map<String, BatchStockDto> batchStocks = new ConcurrentHashMap<>();

    public void registerBatchStock(BatchStockDto batch) {
        batchStocks.put(batch.batchId, batch);
    }

    public BatchStockDto getBatchStock(String batchId) {
        return batchStocks.get(batchId);
    }

    public TransferRequestDto getTransfer(String transferId) {
        return transfers.get(transferId);
    }

    private void checkAuthorizedRole(String role) {
        if (role == null || (!role.equalsIgnoreCase("PHARMACIST") &&
                !role.equalsIgnoreCase("BRANCH_MANAGER") &&
                !role.equalsIgnoreCase("OWNER") &&
                !role.equalsIgnoreCase("ADMIN"))) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Only authorized Pharmacists, Managers or Owners can perform transfer operations.");
        }
    }

    public synchronized TransferRequestDto requestTransfer(
            String tenantId,
            String sourceBranchId,
            String destinationBranchId,
            String requestedBy,
            String staffRole,
            TransferPriority priority,
            TransferCostPolicy costPolicy,
            BigDecimal markupPercentage,
            BigDecimal logisticsFeeBdt,
            List<TransferItemDto> items,
            String notes
    ) {
        checkAuthorizedRole(staffRole);

        if (sourceBranchId == null || destinationBranchId == null || sourceBranchId.equals(destinationBranchId)) {
            throw new IllegalArgumentException("ERR_SAME_BRANCH_TRANSFER: Source and destination branches must be distinct.");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("ERR_EMPTY_TRANSFER: At least one transfer item is required.");
        }

        int totalReq = 0;
        for (TransferItemDto item : items) {
            if (item.quantityRequested <= 0) {
                throw new IllegalArgumentException("ERR_INVALID_QUANTITY: Requested quantity must be positive.");
            }
            BatchStockDto batch = batchStocks.get(item.batchId);
            if (batch == null) {
                throw new NoSuchElementException("ERR_BATCH_NOT_FOUND: Batch " + item.batchId + " does not exist.");
            }
            if (!batch.branchId.equals(sourceBranchId)) {
                throw new IllegalStateException("ERR_BRANCH_MISMATCH: Batch does not belong to source branch.");
            }
            if (batch.getAvailableQuantity() < item.quantityRequested) {
                throw new IllegalStateException("ERR_INSUFFICIENT_AVAILABLE_STOCK: Available stock is "
                        + batch.getAvailableQuantity() + " but requested " + item.quantityRequested);
            }
            totalReq += item.quantityRequested;

            // Apply cost policy calculations
            item.originalCostPerUnit = batch.costPerUnit;
            if (costPolicy == TransferCostPolicy.LANDED_MARKUP && markupPercentage != null && markupPercentage.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal markup = batch.costPerUnit.multiply(markupPercentage).divide(new BigDecimal("100.00"), 4, RoundingMode.HALF_UP);
                item.transferredCostPerUnit = batch.costPerUnit.add(markup);
            } else if (costPolicy == TransferCostPolicy.FIXED_FEE && logisticsFeeBdt != null && logisticsFeeBdt.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal feePerUnit = logisticsFeeBdt.divide(BigDecimal.valueOf(item.quantityRequested), 4, RoundingMode.HALF_UP);
                item.transferredCostPerUnit = batch.costPerUnit.add(feePerUnit);
            } else {
                item.transferredCostPerUnit = batch.costPerUnit;
            }
        }

        // Apply reservations
        for (TransferItemDto item : items) {
            BatchStockDto batch = batchStocks.get(item.batchId);
            batch.reservedQuantityBaseUnits += item.quantityRequested;
        }

        TransferRequestDto transfer = new TransferRequestDto(
                UUID.randomUUID().toString(),
                "TRF-" + (transfers.size() + 1),
                tenantId,
                sourceBranchId,
                destinationBranchId,
                priority,
                costPolicy,
                markupPercentage,
                logisticsFeeBdt,
                notes,
                requestedBy,
                items
        );
        transfer.totalUnitsRequested = totalReq;
        transfers.put(transfer.transferId, transfer);
        return transfer;
    }

    public synchronized TransferRequestDto approveTransfer(String transferId, String approvedBy, String staffRole) {
        checkAuthorizedRole(staffRole);
        TransferRequestDto transfer = transfers.get(transferId);
        if (transfer == null) {
            throw new NoSuchElementException("ERR_TRANSFER_NOT_FOUND");
        }
        if (transfer.status != TransferStatus.REQUESTED) {
            throw new IllegalStateException("ERR_INVALID_TRANSITION: Only REQUESTED transfers can be approved.");
        }
        transfer.status = TransferStatus.APPROVED;
        transfer.approvedBy = approvedBy;
        transfer.updatedAt = Instant.now();
        return transfer;
    }

    public synchronized TransferRequestDto dispatchTransfer(String transferId, String dispatchedBy, String staffRole) {
        checkAuthorizedRole(staffRole);
        TransferRequestDto transfer = transfers.get(transferId);
        if (transfer == null) {
            throw new NoSuchElementException("ERR_TRANSFER_NOT_FOUND");
        }
        if (transfer.status != TransferStatus.APPROVED) {
            throw new IllegalStateException("ERR_INVALID_TRANSITION: Only APPROVED transfers can be dispatched.");
        }

        int totalDisp = 0;
        for (TransferItemDto item : transfer.items) {
            BatchStockDto batch = batchStocks.get(item.batchId);
            if (batch != null) {
                batch.reservedQuantityBaseUnits -= item.quantityRequested;
                batch.quantityBaseUnits -= item.quantityRequested;
            }
            item.quantityDispatched = item.quantityRequested;
            totalDisp += item.quantityDispatched;
        }

        transfer.status = TransferStatus.DISPATCHED;
        transfer.dispatchedBy = dispatchedBy;
        transfer.totalUnitsDispatched = totalDisp;
        transfer.updatedAt = Instant.now();
        return transfer;
    }

    public synchronized TransferRequestDto reconcileTransfer(
            String transferId,
            String actorUser,
            String staffRole,
            List<ReconciliationLineDto> lines,
            String notes,
            Map<String, BatchStockDto> destinationGoodBatches,
            Map<String, BatchStockDto> destinationDamageBatches
    ) {
        checkAuthorizedRole(staffRole);
        TransferRequestDto transfer = transfers.get(transferId);
        if (transfer == null) {
            throw new NoSuchElementException("ERR_TRANSFER_NOT_FOUND");
        }
        if (transfer.status == TransferStatus.RECEIVED || transfer.status == TransferStatus.RECONCILED) {
            throw new IllegalStateException("ERR_TRANSFER_ALREADY_RECEIVED: Transfer is already received/reconciled.");
        }
        if (transfer.status != TransferStatus.DISPATCHED) {
            throw new IllegalStateException("ERR_INVALID_TRANSITION: Transfer must be DISPATCHED before receiving.");
        }

        Map<String, ReconciliationLineDto> lineMap = new HashMap<>();
        if (lines != null) {
            for (ReconciliationLineDto l : lines) {
                lineMap.put(l.itemId, l);
            }
        }

        boolean hasDiscrepancy = false;
        int totalGood = 0;
        int totalDamaged = 0;
        int totalLost = 0;

        for (TransferItemDto item : transfer.items) {
            ReconciliationLineDto line = lineMap.get(item.itemId);
            int good = line != null ? line.quantityGood : item.quantityDispatched;
            int damaged = line != null ? line.quantityDamaged : 0;
            int lost = line != null ? line.quantityLost : 0;

            if (good < 0 || damaged < 0 || lost < 0) {
                throw new IllegalArgumentException("ERR_INVALID_QUANTITY: Quantities must be non-negative.");
            }

            int sum = good + damaged + lost;
            if (sum != item.quantityDispatched) {
                throw new IllegalStateException("ERR_DISCREPANCY_CONSERVATION: Good (" + good + ") + Damaged (" + damaged + ") + Lost (" + lost + ") != Dispatched (" + item.quantityDispatched + ")");
            }

            if (damaged > 0 || lost > 0) {
                hasDiscrepancy = true;
            }

            // 1. Credit Good Stock with Lot Continuity
            if (good > 0 && destinationGoodBatches != null && destinationGoodBatches.containsKey(item.medicineId)) {
                BatchStockDto destBatch = destinationGoodBatches.get(item.medicineId);

                // Strictly prohibit modifying expiry date!
                if (!destBatch.expiryDate.equals(item.expiryDate)) {
                    throw new IllegalStateException("ERR_EXPIRY_DATE_ALTERATION_PROHIBITED: Transferred batch expiry ("
                            + item.expiryDate + ") conflicts with existing batch expiry (" + destBatch.expiryDate + ").");
                }

                // Weighted Average Cost calculation
                if (destBatch.quantityBaseUnits > 0) {
                    BigDecimal existTotal = destBatch.costPerUnit.multiply(BigDecimal.valueOf(destBatch.quantityBaseUnits));
                    BigDecimal inTotal = item.transferredCostPerUnit.multiply(BigDecimal.valueOf(good));
                    BigDecimal totalUnits = BigDecimal.valueOf(destBatch.quantityBaseUnits + good);
                    destBatch.costPerUnit = existTotal.add(inTotal).divide(totalUnits, 4, RoundingMode.HALF_UP);
                } else {
                    destBatch.costPerUnit = item.transferredCostPerUnit;
                }

                destBatch.quantityBaseUnits += good;
                destBatch.originBatchId = item.batchId;
                destBatch.originBranchId = transfer.sourceBranchId;
                destBatch.originalCostPerUnit = item.originalCostPerUnit;
                destBatch.transferMarkupBdt = item.transferredCostPerUnit.subtract(item.originalCostPerUnit);
            }

            // 2. Credit Damaged Stock to Quarantined Batch (Zero sellable availability)
            if (damaged > 0 && destinationDamageBatches != null && destinationDamageBatches.containsKey(item.medicineId)) {
                BatchStockDto dmgBatch = destinationDamageBatches.get(item.medicineId);
                dmgBatch.isQuarantined = true;
                dmgBatch.quantityBaseUnits += damaged;
                dmgBatch.originBatchId = item.batchId;
                dmgBatch.originBranchId = transfer.sourceBranchId;
                dmgBatch.costPerUnit = item.transferredCostPerUnit;
            }

            item.quantityReceivedGood = good;
            item.quantityReceivedDamaged = damaged;
            item.quantityLost = lost;
            item.quantityReceived = good + damaged;
            item.discrepancyReason = line != null ? line.discrepancyReason : "";
            item.damageStorageLocation = line != null ? line.damageStorageLocation : "";
            item.isReconciled = true;

            totalGood += good;
            totalDamaged += damaged;
            totalLost += lost;
        }

        transfer.status = hasDiscrepancy ? TransferStatus.RECONCILED : TransferStatus.RECEIVED;
        transfer.receivedBy = actorUser;
        transfer.reconciledBy = actorUser;
        transfer.reconciledAt = Instant.now();
        transfer.reconciliationNotes = notes;
        transfer.totalUnitsReceived = totalGood + totalDamaged;
        transfer.totalUnitsReceivedGood = totalGood;
        transfer.totalUnitsReceivedDamaged = totalDamaged;
        transfer.totalUnitsLost = totalLost;
        transfer.updatedAt = Instant.now();

        return transfer;
    }
}
