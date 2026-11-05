package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * PosBranchBatchOwnershipClient
 * Architecture Decision: ADR-0175 (P175).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Desktop POS client ensuring batch inventory and checkouts remain
 * strictly branch-scoped, preventing cross-branch stock mixing.
 */
public class PosBranchBatchOwnershipClient {

    public static final class BranchBatchDto {
        public final String batchId;
        public final String tenantId;
        public final String branchId;
        public final String branchName;
        public final String productId;
        public final String productName;
        public final String batchNumber;
        public int quantityBaseUnits;
        public int reservedQuantityBaseUnits;
        public final LocalDate expiryDate;
        public final BigDecimal costPerUnit;
        public String status;
        public boolean isDispensable;

        public BranchBatchDto(
                String batchId,
                String tenantId,
                String branchId,
                String branchName,
                String productId,
                String productName,
                String batchNumber,
                int quantityBaseUnits,
                int reservedQuantityBaseUnits,
                LocalDate expiryDate,
                BigDecimal costPerUnit,
                String status,
                boolean isDispensable) {
            this.batchId = batchId != null ? batchId : "";
            this.tenantId = tenantId != null ? tenantId : "";
            this.branchId = branchId != null ? branchId : "";
            this.branchName = branchName != null ? branchName : "";
            this.productId = productId != null ? productId : "";
            this.productName = productName != null ? productName : "";
            this.batchNumber = batchNumber != null ? batchNumber : "";
            this.quantityBaseUnits = Math.max(0, quantityBaseUnits);
            this.reservedQuantityBaseUnits = Math.max(0, reservedQuantityBaseUnits);
            this.expiryDate = expiryDate;
            this.costPerUnit = costPerUnit != null ? costPerUnit : BigDecimal.ZERO;
            this.status = status != null ? status : "ACTIVE";
            this.isDispensable = isDispensable;
        }

        public int getAvailableQuantityBaseUnits() {
            return Math.max(0, quantityBaseUnits - reservedQuantityBaseUnits);
        }
    }

    public static final class DispenseItem {
        public final String batchId;
        public final int quantity;

        public DispenseItem(String batchId, int quantity) {
            this.batchId = batchId;
            this.quantity = quantity;
        }
    }

    public static final class BranchStockSummaryDto {
        public final String branchId;
        public final String branchName;
        public final int quantityOnHand;
        public final int quantityReserved;
        public final int quantityAvailable;
        public final List<BranchBatchDto> batches;

        public BranchStockSummaryDto(
                String branchId,
                String branchName,
                int quantityOnHand,
                int quantityReserved,
                int quantityAvailable,
                List<BranchBatchDto> batches) {
            this.branchId = branchId;
            this.branchName = branchName;
            this.quantityOnHand = quantityOnHand;
            this.quantityReserved = quantityReserved;
            this.quantityAvailable = quantityAvailable;
            this.batches = batches != null ? batches : Collections.emptyList();
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
    private final String activeBranchId;
    private final String activeBranchName;
    private final String userRole;
    private final String username;
    private final boolean isTenantSuspended;
    private final Map<String, BranchBatchDto> batchStore;

    public PosBranchBatchOwnershipClient(
            String tenantId,
            String activeBranchId,
            String activeBranchName,
            String userRole,
            String username,
            boolean isTenantSuspended) {
        this(tenantId, activeBranchId, activeBranchName, userRole, username, isTenantSuspended, new HashMap<>());
    }

    public PosBranchBatchOwnershipClient(
            String tenantId,
            String activeBranchId,
            String activeBranchName,
            String userRole,
            String username,
            boolean isTenantSuspended,
            Map<String, BranchBatchDto> sharedStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.activeBranchId = activeBranchId != null ? activeBranchId : "";
        this.activeBranchName = activeBranchName != null ? activeBranchName : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.username = username != null ? username : "cashier1";
        this.isTenantSuspended = isTenantSuspended;
        this.batchStore = sharedStore != null ? sharedStore : new HashMap<>();
    }

    public boolean isMultiBranchUser() {
        return switch (this.userRole.toUpperCase()) {
            case "PHARMACY_OWNER", "SUPERADMIN", "AUDITOR", "OWNER_ADMIN", "ADMIN" -> true;
            default -> false;
        };
    }

    public void registerBatch(BranchBatchDto batch) {
        if (batch != null && !batch.batchId.isEmpty()) {
            batchStore.put(batch.batchId, batch);
        }
    }

    public Map<String, BranchBatchDto> getBatchStore() {
        return this.batchStore;
    }

    public ClientResponse<List<BranchBatchDto>> listBatchesForActiveTerminal(String requestedBranchId) {
        if (isTenantSuspended) {
            return ClientResponse.failure(
                    "ERR_TENANT_SUSPENDED",
                    "Tenant subscription is suspended.",
                    "গ্রাহক সাবস্ক্রিপশন স্থগিত রয়েছে।"
            );
        }

        String targetBranchId = activeBranchId;
        if (requestedBranchId != null && !requestedBranchId.isEmpty()) {
            if (!isMultiBranchUser() && !requestedBranchId.equals(activeBranchId)) {
                return ClientResponse.failure(
                        "ERR_CROSS_BRANCH_ACCESS",
                        "Cross-branch access forbidden. Cashier is restricted to active terminal branch.",
                        "অন্য ব্রাঞ্চের স্টক দেখার অনুমতি নেই। ক্যাশিয়ার কেবল নির্ধারিত ব্রাঞ্চের স্টক দেখতে পারবেন।"
                );
            }
            targetBranchId = requestedBranchId;
        }

        List<BranchBatchDto> results = new ArrayList<>();
        for (BranchBatchDto b : batchStore.values()) {
            if (b.tenantId.equals(tenantId) && b.branchId.equals(targetBranchId)) {
                results.add(b);
            }
        }
        results.sort(Comparator.comparing((BranchBatchDto b) -> b.expiryDate).thenComparing(b -> b.batchNumber));

        return ClientResponse.ok(
                results,
                "Batches retrieved for branch: " + targetBranchId,
                "ব্রাঞ্চের ব্যাচ তালিকা সফলভাবে লোড হয়েছে।"
        );
    }

    public ClientResponse<List<BranchBatchDto>> validateDispenseCart(List<DispenseItem> items) {
        if (isTenantSuspended) {
            return ClientResponse.failure(
                    "ERR_TENANT_SUSPENDED",
                    "Tenant subscription is suspended.",
                    "গ্রাহক সাবস্ক্রিপশন স্থগিত রয়েছে।"
            );
        }

        if (items == null || items.isEmpty()) {
            return ClientResponse.failure(
                    "ERR_EMPTY_DISPENSE_ITEMS",
                    "Cart items list cannot be empty.",
                    "কার্টের আইটেম তালিকা খালি হতে পারে না।"
            );
        }

        List<BranchBatchDto> validatedBatches = new ArrayList<>();
        for (DispenseItem item : items) {
            BranchBatchDto batch = batchStore.get(item.batchId);
            if (batch == null || !batch.tenantId.equals(tenantId)) {
                return ClientResponse.failure(
                        "ERR_BATCH_NOT_FOUND",
                        "Batch '" + item.batchId + "' not found.",
                        "ব্যাচটি পাওয়া যায়নি।"
                );
            }

            // Cross-branch dispense block
            if (!batch.branchId.equals(activeBranchId)) {
                return ClientResponse.failure(
                        "ERR_CROSS_BRANCH_DISPENSE_BLOCKED",
                        "Batch '" + batch.batchId + "' belongs to branch '" + batch.branchName +
                                "' (" + batch.branchId + "), not active checkout branch '" + activeBranchName +
                                "'. Cross-branch stock mixing is strictly barred.",
                        "এই ব্যাচটি অন্য ব্রাঞ্চের অন্তর্ভুক্ত। বিক্রির সময় অন্য ব্রাঞ্চের স্টক মেশানো সম্পূর্ণ নিষিদ্ধ।"
                );
            }

            if (!batch.isDispensable) {
                return ClientResponse.failure(
                        "ERR_BATCH_NOT_DISPENSABLE",
                        "Batch '" + batch.batchId + "' is not dispensable (expired, quarantined, or recalled).",
                        "ব্যাচটি বিক্রির অনুপযুক্ত (মেয়াদোত্তীর্ণ, কোয়ারেন্টাইন বা প্রত্যাহৃত)।"
                );
            }

            if (batch.getAvailableQuantityBaseUnits() < item.quantity) {
                return ClientResponse.failure(
                        "ERR_INSUFFICIENT_UNRESERVED_STOCK",
                        "Insufficient unreserved stock for batch '" + batch.batchId +
                                "'. Available: " + batch.getAvailableQuantityBaseUnits() + ", requested: " + item.quantity,
                        "পর্যাপ্ত অনির্ধারিত স্টক মজুদ নেই।"
                );
            }

            validatedBatches.add(batch);
        }

        return ClientResponse.ok(
                validatedBatches,
                "All items verified for branch checkout: " + activeBranchName,
                "ব্রাঞ্চের সমস্ত আইটেম বিক্রয়ের জন্য অনুমোদিত ও যাচাইকৃত।"
        );
    }

    public ClientResponse<List<BranchStockSummaryDto>> getMedicineMultiBranchSummary(
            String productId,
            List<String> authorizedBranches) {
        if (isTenantSuspended) {
            return ClientResponse.failure(
                    "ERR_TENANT_SUSPENDED",
                    "Tenant subscription is suspended.",
                    "গ্রাহক সাবস্ক্রিপশন স্থগিত রয়েছে।"
            );
        }

        if (!isMultiBranchUser()) {
            return ClientResponse.failure(
                    "ERR_PERMISSION_DENIED",
                    "Only Pharmacy Owners or Administrators can view multi-branch inventory summaries.",
                    "শুধুমাত্র ফার্মেসি মালিক বা অ্যাডমিন একাধিক ব্রাঞ্চের স্টক সারসংক্ষেপ দেখতে পারেন।"
            );
        }

        List<String> branchFilter = (authorizedBranches != null && !authorizedBranches.isEmpty())
                ? authorizedBranches
                : List.of(activeBranchId);

        List<BranchStockSummaryDto> summaries = new ArrayList<>();
        for (String brId : branchFilter) {
            List<BranchBatchDto> brBatches = new ArrayList<>();
            int onHand = 0;
            int reserved = 0;
            String brName = "";

            for (BranchBatchDto b : batchStore.values()) {
                if (b.tenantId.equals(tenantId) && b.branchId.equals(brId) && b.productId.equals(productId)) {
                    brBatches.add(b);
                    onHand += b.quantityBaseUnits;
                    reserved += b.reservedQuantityBaseUnits;
                    brName = b.branchName;
                }
            }

            summaries.add(new BranchStockSummaryDto(
                    brId,
                    brName.isEmpty() ? brId : brName,
                    onHand,
                    reserved,
                    Math.max(0, onHand - reserved),
                    brBatches
            ));
        }

        return ClientResponse.ok(
                summaries,
                "Multi-branch segregated summary generated.",
                "প্রতিটি ব্রাঞ্চের স্টক সারসংক্ষেপ সফলভাবে তৈরি হয়েছে।"
        );
    }
}

