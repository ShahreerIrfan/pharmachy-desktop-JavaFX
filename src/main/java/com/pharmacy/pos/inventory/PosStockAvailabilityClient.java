package com.pharmacy.pos.inventory;

import java.util.*;

/**
 * PosStockAvailabilityClient
 * Architecture Decision: ADR-0171 (P171).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Client-side engine providing multi-tier branch stock availability metrics,
 * real-time POS cart pre-checkout checks, and packaging unit breakdowns.
 */
public class PosStockAvailabilityClient {

    public static final class AvailabilityMatrixRecord {
        public final String availabilityId;
        public final String tenantId;
        public final String branchId;
        public final String productId;
        public final String productName;
        public final String genericName;
        public final String baseUnit;
        public final int quantityOnHand;
        public final int quantityReserved;
        public final int quantityQuarantined;
        public final int quantityPendingInward;
        public final int quantityPhysicallyAvailable;
        public final int quantityServerConfirmedAvailable;
        public final int reorderLevelBaseUnits;
        public final boolean isOutOfStock;
        public final boolean isBelowReorderLevel;
        public final String packagingSummary;

        public AvailabilityMatrixRecord(
                String availabilityId,
                String tenantId,
                String branchId,
                String productId,
                String productName,
                String genericName,
                String baseUnit,
                int quantityOnHand,
                int quantityReserved,
                int quantityQuarantined,
                int quantityPendingInward,
                int reorderLevelBaseUnits,
                String packagingSummary) {
            this.availabilityId = availabilityId != null ? availabilityId : "";
            this.tenantId = tenantId != null ? tenantId : "";
            this.branchId = branchId != null ? branchId : "";
            this.productId = productId != null ? productId : "";
            this.productName = productName != null ? productName : "";
            this.genericName = genericName != null ? genericName : "";
            this.baseUnit = baseUnit != null ? baseUnit : "PIECE";
            this.quantityOnHand = Math.max(0, quantityOnHand);
            this.quantityReserved = Math.max(0, quantityReserved);
            this.quantityQuarantined = Math.max(0, quantityQuarantined);
            this.quantityPendingInward = Math.max(0, quantityPendingInward);
            this.reorderLevelBaseUnits = Math.max(0, reorderLevelBaseUnits);

            // Invariant rules
            this.quantityPhysicallyAvailable = Math.max(0, this.quantityOnHand - this.quantityQuarantined);
            this.quantityServerConfirmedAvailable = Math.max(0, this.quantityPhysicallyAvailable - this.quantityReserved);
            this.isOutOfStock = this.quantityServerConfirmedAvailable == 0;
            this.isBelowReorderLevel = this.reorderLevelBaseUnits > 0 && this.quantityServerConfirmedAvailable <= this.reorderLevelBaseUnits;
            this.packagingSummary = packagingSummary != null ? packagingSummary : "";
        }
    }

    public static final class PrecheckItemRequest {
        public final String productId;
        public final int requestedQuantityBaseUnits;

        public PrecheckItemRequest(String productId, int requestedQuantityBaseUnits) {
            this.productId = productId != null ? productId : "";
            this.requestedQuantityBaseUnits = requestedQuantityBaseUnits;
        }
    }

    public static final class PrecheckItemResult {
        public final String productId;
        public final int requestedQuantity;
        public final int serverConfirmedAvailable;
        public final int shortageUnits;
        public final boolean isAvailable;
        public final String packagingSummary;

        public PrecheckItemResult(
                String productId,
                int requestedQuantity,
                int serverConfirmedAvailable,
                int shortageUnits,
                boolean isAvailable,
                String packagingSummary) {
            this.productId = productId;
            this.requestedQuantity = requestedQuantity;
            this.serverConfirmedAvailable = serverConfirmedAvailable;
            this.shortageUnits = shortageUnits;
            this.isAvailable = isAvailable;
            this.packagingSummary = packagingSummary;
        }
    }

    public static final class PrecheckBatchResult {
        public final boolean allItemsAvailable;
        public final List<PrecheckItemResult> items;

        public PrecheckBatchResult(boolean allItemsAvailable, List<PrecheckItemResult> items) {
            this.allItemsAvailable = allItemsAvailable;
            this.items = items != null ? Collections.unmodifiableList(new ArrayList<>(items)) : Collections.emptyList();
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
    private final boolean isTenantSuspended;
    private final Map<String, AvailabilityMatrixRecord> localStore = new HashMap<>();

    public PosStockAvailabilityClient(String tenantId, String branchId, String userRole, boolean isTenantSuspended) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.isTenantSuspended = isTenantSuspended;
    }

    private boolean isRoleAuthorized() {
        return switch (this.userRole.toUpperCase()) {
            case "CASHIER", "STAFF_PHARMACIST", "BRANCH_MANAGER", "PHARMACY_OWNER", "INVENTORY_CLERK", "AUDITOR", "SUPERADMIN" -> true;
            default -> false;
        };
    }

    public static String formatPackaging(
            String baseUnit,
            String packLabel,
            String interLabel,
            int packToInter,
            int interToBase,
            int totalBaseUnits) {
        if (totalBaseUnits <= 0) {
            return "0 " + (baseUnit != null ? baseUnit : "PIECE");
        }
        int pToI = Math.max(1, packToInter);
        int iToB = Math.max(1, interToBase);
        int basePerPack = pToI * iToB;

        int packs = totalBaseUnits / basePerPack;
        int rem = totalBaseUnits % basePerPack;
        int inters = rem / iToB;
        int loose = rem % iToB;

        List<String> parts = new ArrayList<>();
        if (packs > 0) {
            parts.add(packs + " " + (packLabel != null ? packLabel : "BOX") + (packs > 1 ? "es" : ""));
        }
        if (inters > 0) {
            parts.add(inters + " " + (interLabel != null ? interLabel : "STRIP") + (inters > 1 ? "s" : ""));
        }
        if (loose > 0 || parts.isEmpty()) {
            parts.add(loose + " " + (baseUnit != null ? baseUnit : "PIECE") + (loose > 1 ? "s" : ""));
        }
        return String.join(", ", parts);
    }

    public void putRecord(AvailabilityMatrixRecord record) {
        if (record != null) {
            this.localStore.put(record.productId, record);
        }
    }

    public ClientResponse<AvailabilityMatrixRecord> getAvailability(String productId) {
        if (isTenantSuspended) {
            return ClientResponse.failure(
                    "ERR_TENANT_SUSPENDED",
                    "Tenant subscription is suspended.",
                    "গ্রাহক সাবস্ক্রিপশন স্থগিত রয়েছে।"
            );
        }
        if (!isRoleAuthorized()) {
            return ClientResponse.failure(
                    "ERR_ROLE_UNAUTHORIZED",
                    "Role is not authorized to access inventory availability.",
                    "এই রোল ইনভেন্টরি স্টক দেখতে অননুমোদিত।"
            );
        }
        if (productId == null || productId.trim().isEmpty()) {
            return ClientResponse.failure(
                    "ERR_MISSING_PRODUCT",
                    "Product ID is required.",
                    "প্রোডাক্ট আইডি প্রয়োজন।"
            );
        }

        AvailabilityMatrixRecord rec = this.localStore.get(productId);
        if (rec == null) {
            return ClientResponse.failure(
                    "ERR_PRODUCT_NOT_FOUND",
                    "Product availability record not found.",
                    "পণ্যটির কোনো স্টক তথ্য পাওয়া যায়নি।"
            );
        }

        return ClientResponse.ok(rec, "Stock availability retrieved successfully.", "স্টক তথ্য সফলভাবে লোড হয়েছে।");
    }

    public ClientResponse<PrecheckBatchResult> precheckCheckout(List<PrecheckItemRequest> items) {
        if (isTenantSuspended) {
            return ClientResponse.failure(
                    "ERR_TENANT_SUSPENDED",
                    "Tenant subscription is suspended.",
                    "গ্রাহক সাবস্ক্রিপশন স্থগিত রয়েছে।"
            );
        }
        if (!isRoleAuthorized()) {
            return ClientResponse.failure(
                    "ERR_ROLE_UNAUTHORIZED",
                    "Role is not authorized to execute checkout availability precheck.",
                    "চেকআউট স্টক যাচাই করার অনুমতি নেই।"
            );
        }
        if (items == null || items.isEmpty()) {
            return ClientResponse.failure(
                    "ERR_EMPTY_ITEMS",
                    "Items list cannot be empty.",
                    "চেকআউটের জন্য পণ্যের তালিকা ফাঁকা হতে পারে না।"
            );
        }

        boolean allAvailable = true;
        List<PrecheckItemResult> results = new ArrayList<>();

        for (PrecheckItemRequest req : items) {
            if (req.requestedQuantityBaseUnits <= 0) {
                return ClientResponse.failure(
                        "ERR_INVALID_QUANTITY",
                        "Requested quantity must be positive.",
                        "অনুরোধকৃত স্টক সংখ্যা ধনাত্মক হতে হবে।"
                );
            }
            AvailabilityMatrixRecord rec = this.localStore.get(req.productId);
            int confirmed = rec != null ? rec.quantityServerConfirmedAvailable : 0;
            boolean isAvail = confirmed >= req.requestedQuantityBaseUnits;
            int shortage = Math.max(0, req.requestedQuantityBaseUnits - confirmed);
            if (!isAvail) {
                allAvailable = false;
            }
            results.add(new PrecheckItemResult(
                    req.productId,
                    req.requestedQuantityBaseUnits,
                    confirmed,
                    shortage,
                    isAvail,
                    rec != null ? rec.packagingSummary : ""
            ));
        }

        return ClientResponse.ok(
                new PrecheckBatchResult(allAvailable, results),
                "Precheck verification complete.",
                "চেকআউট স্টক প্রি-চেক সম্পন্ন হয়েছে।"
        );
    }
}
