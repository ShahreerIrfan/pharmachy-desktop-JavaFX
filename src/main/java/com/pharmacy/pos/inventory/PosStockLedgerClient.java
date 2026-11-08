package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * PosStockLedgerClient
 * Architecture Decision: ADR-0201 (P201).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Append-only stock movement ledger client for Desktop POS.
 * Enforces strict mathematical stock conservation:
 *   balance_after = balance_before + quantity_delta
 * Cryptographic SHA-256 tamper verification, role-based authorization (Cashier denied),
 * and batch reconciliation support.
 */
public class PosStockLedgerClient {

    public enum StockMovementType {
        PURCHASE_RECEIPT("Purchase Receipt (GRN Inward)", "ক্রয় রসিদ (ইনওয়ার্ড)"),
        SALE_DISPENSE("Sale Dispense (Invoice Outward)", "বিক্রয় বিতরণ (আউটওয়ার্ড)"),
        CUSTOMER_RETURN("Customer Sale Return", "গ্রাহক বিক্রয় ফেরত"),
        PURCHASE_RETURN("Supplier Purchase Return", "সরবরাহকারী ক্রয় ফেরত"),
        TRANSFER_OUT("Branch Transfer Outward", "শাখা স্থানান্তর বহির্গামী"),
        TRANSFER_IN("Branch Transfer Inward", "শাখা স্থানান্তর অন্তর্মুখী"),
        EXPIRY_DISPOSAL("Expired Stock Disposal", "মেয়াদোত্তীর্ণ ওষুধ অপসারণ"),
        STOCK_ADJUSTMENT_IN("Inventory Positive Adjustment", "শারীরিক গণনা বৃদ্ধি সমন্বয়"),
        STOCK_ADJUSTMENT_OUT("Inventory Negative Adjustment", "শারীরিক গণনা হ্রাস সমন্বয়"),
        QUARANTINE_HOLD("Quality / DGDA Quarantine Hold", "কোয়ারেন্টাইন আটক"),
        QUARANTINE_RELEASE("Quality Quarantine Release", "কোয়ারেন্টাইন মুক্তি");

        public final String labelEn;
        public final String labelBn;

        StockMovementType(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static final class StockLedgerEntryDto {
        public final String entryId;
        public final String tenantId;
        public final String branchId;
        public final String productId;
        public final String productName;
        public final String batchNumber;
        public final String movementType;
        public final int quantityDelta;
        public final int balanceBefore;
        public final int balanceAfter;
        public final BigDecimal unitCost;
        public final BigDecimal totalCost;
        public final BigDecimal unitPrice;
        public final String sourceEntityType;
        public final String sourceEntityId;
        public final String sourceReference;
        public final boolean isColdChain;
        public final String storageLocation;
        public final String notes;
        public final String verificationHash;
        public final String postedBy;
        public final Instant postedAt;

        public StockLedgerEntryDto(
                String entryId,
                String tenantId,
                String branchId,
                String productId,
                String productName,
                String batchNumber,
                String movementType,
                int quantityDelta,
                int balanceBefore,
                int balanceAfter,
                BigDecimal unitCost,
                BigDecimal totalCost,
                BigDecimal unitPrice,
                String sourceEntityType,
                String sourceEntityId,
                String sourceReference,
                boolean isColdChain,
                String storageLocation,
                String notes,
                String verificationHash,
                String postedBy,
                Instant postedAt) {
            this.entryId = entryId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.productId = productId;
            this.productName = productName;
            this.batchNumber = batchNumber;
            this.movementType = movementType;
            this.quantityDelta = quantityDelta;
            this.balanceBefore = balanceBefore;
            this.balanceAfter = balanceAfter;
            this.unitCost = unitCost != null ? unitCost : BigDecimal.ZERO;
            this.totalCost = totalCost != null ? totalCost : BigDecimal.ZERO;
            this.unitPrice = unitPrice != null ? unitPrice : BigDecimal.ZERO;
            this.sourceEntityType = sourceEntityType;
            this.sourceEntityId = sourceEntityId;
            this.sourceReference = sourceReference;
            this.isColdChain = isColdChain;
            this.storageLocation = storageLocation;
            this.notes = notes;
            this.verificationHash = verificationHash;
            this.postedBy = postedBy;
            this.postedAt = postedAt != null ? postedAt : Instant.now();
        }
    }

    public static final class StockReconciliationResultDto {
        public final String tenantId;
        public final String batchNumber;
        public final Integer recordedQuantity;
        public final int ledgerSum;
        public final int discrepancy;
        public final boolean isBalanced;
        public final boolean tamperDetected;
        public final int totalEntries;

        public StockReconciliationResultDto(
                String tenantId,
                String batchNumber,
                Integer recordedQuantity,
                int ledgerSum,
                int discrepancy,
                boolean isBalanced,
                boolean tamperDetected,
                int totalEntries) {
            this.tenantId = tenantId;
            this.batchNumber = batchNumber;
            this.recordedQuantity = recordedQuantity;
            this.ledgerSum = ledgerSum;
            this.discrepancy = discrepancy;
            this.isBalanced = isBalanced;
            this.tamperDetected = tamperDetected;
            this.totalEntries = totalEntries;
        }
    }

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, StockLedgerEntryDto> ledgerStore = new LinkedHashMap<>();
    private final Map<String, Integer> batchBalanceStore = new HashMap<>();

    public PosStockLedgerClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
    }

    private void checkInventoryRole() {
        String role = userRole.toUpperCase();
        if ("CASHIER".equals(role)) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers are denied access to the audit stock ledger.");
        }
    }

    public static String computeVerificationHash(StockLedgerEntryDto entry) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = String.format("%s|%s|%s|%s|%s|%s|%d|%d|%d|%s|%s|%s",
                    entry.entryId, entry.tenantId, entry.branchId, entry.productId,
                    entry.batchNumber, entry.movementType, entry.quantityDelta,
                    entry.balanceBefore, entry.balanceAfter, entry.totalCost.toPlainString(),
                    entry.sourceEntityId, entry.postedAt.toString());
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 digest computation failed", e);
        }
    }

    public synchronized StockLedgerEntryDto recordMovement(
            String productId,
            String productName,
            String batchNumber,
            StockMovementType movementType,
            int quantityDelta,
            String sourceEntityType,
            String sourceEntityId,
            String sourceReference,
            BigDecimal unitCost,
            BigDecimal unitPrice,
            boolean isColdChain,
            String storageLocation,
            String notes) {
        checkInventoryRole();

        if (quantityDelta == 0) {
            throw new IllegalArgumentException("ERR_ZERO_DELTA: Stock movement quantityDelta cannot be zero.");
        }

        String batchKey = (tenantId + ":" + branchId + ":" + batchNumber).toLowerCase();
        int currentBalance = batchBalanceStore.getOrDefault(batchKey, 0);
        int newBalance = currentBalance + quantityDelta;

        if (newBalance < 0) {
            throw new IllegalStateException("ERR_INSUFFICIENT_STOCK: Batch " + batchNumber + " current balance is " + currentBalance + ", cannot deduct " + Math.abs(quantityDelta));
        }

        BigDecimal cost = unitCost != null ? unitCost : BigDecimal.ZERO;
        BigDecimal totalCost = cost.multiply(BigDecimal.valueOf(Math.abs(quantityDelta)));
        String entryId = "stkld-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant now = Instant.now();

        // Stock conservation invariant check
        if (newBalance != currentBalance + quantityDelta) {
            throw new IllegalStateException("ERR_CONSERVATION_VIOLATION: balanceAfter must equal balanceBefore + quantityDelta");
        }

        StockLedgerEntryDto preEntry = new StockLedgerEntryDto(
                entryId, tenantId, branchId, productId, productName, batchNumber,
                movementType.name(), quantityDelta, currentBalance, newBalance,
                cost, totalCost, unitPrice, sourceEntityType, sourceEntityId,
                sourceReference, isColdChain, storageLocation, notes,
                "", userId, now
        );
        String hash = computeVerificationHash(preEntry);

        StockLedgerEntryDto entry = new StockLedgerEntryDto(
                entryId, tenantId, branchId, productId, productName, batchNumber,
                movementType.name(), quantityDelta, currentBalance, newBalance,
                cost, totalCost, unitPrice, sourceEntityType, sourceEntityId,
                sourceReference, isColdChain, storageLocation, notes,
                hash, userId, now
        );

        ledgerStore.put(entryId, entry);
        batchBalanceStore.put(batchKey, newBalance);
        return entry;
    }

    public synchronized void setInitialBatchBalance(String batchNumber, int initialBalance) {
        String batchKey = (tenantId + ":" + branchId + ":" + batchNumber).toLowerCase();
        batchBalanceStore.put(batchKey, initialBalance);
    }

    public synchronized List<StockLedgerEntryDto> getBatchTrail(String batchNumber) {
        checkInventoryRole();
        List<StockLedgerEntryDto> trail = new ArrayList<>();
        for (StockLedgerEntryDto entry : ledgerStore.values()) {
            if (entry.tenantId.equals(tenantId) && entry.batchNumber.equalsIgnoreCase(batchNumber)) {
                trail.add(entry);
            }
        }
        return Collections.unmodifiableList(trail);
    }

    public synchronized StockReconciliationResultDto reconcileBatch(String batchNumber) {
        checkInventoryRole();
        List<StockLedgerEntryDto> trail = getBatchTrail(batchNumber);
        int sumDelta = 0;
        boolean tamperDetected = false;

        for (StockLedgerEntryDto entry : trail) {
            sumDelta += entry.quantityDelta;
            String expectedHash = computeVerificationHash(entry);
            if (!expectedHash.equals(entry.verificationHash)) {
                tamperDetected = true;
            }
        }

        String batchKey = (tenantId + ":" + branchId + ":" + batchNumber).toLowerCase();
        Integer recordedQty = batchBalanceStore.get(batchKey);
        int discrepancy = recordedQty != null ? (recordedQty - sumDelta) : 0;
        boolean balanced = (discrepancy == 0) && !tamperDetected;

        return new StockReconciliationResultDto(
                tenantId, batchNumber, recordedQty, sumDelta, discrepancy,
                balanced, tamperDetected, trail.size()
        );
    }
}
