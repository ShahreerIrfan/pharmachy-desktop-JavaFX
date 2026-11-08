package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * PosGoodsReceiptClient
 * Architecture Decision: ADR-0187 (P187).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 *
 * Desktop POS client governing Goods Receipt Notes (GRN), PO line matching,
 * cold-chain temperature verification, idempotent stock posting, and reversal safety.
 */
public class PosGoodsReceiptClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, GoodsReceiptDto> grnStore;
    private final PosPurchaseOrderClient poClient;

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "PROCUREMENT_OFFICER", "INVENTORY_CLERK"
    );

    public PosGoodsReceiptClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, GoodsReceiptDto> grnStore,
            PosPurchaseOrderClient poClient) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.grnStore = grnStore != null ? grnStore : new HashMap<>();
        this.poClient = poClient;
    }

    public static final class GoodsReceiptLineDto {
        public final String lineId;
        public final String poItemId;
        public final String productId;
        public final String productBrandName;
        public final String batchNumber;
        public final LocalDate expiryDate;
        public final LocalDate mfgDate;
        public final String packUnit;
        public final int packageSize;
        public final int receivedPackages;
        public final int orderedQuantityBaseUnits;
        public final int receivedQuantityBaseUnits;
        public final BigDecimal unitCost;
        public final BigDecimal mrpUnit;
        public final BigDecimal discountPercentage;
        public final BigDecimal taxPercentage;
        public final BigDecimal lineTotal;
        public final String storageLocation;
        public final boolean isBonusGoods;
        public final int excessQuantity;
        public final String excessReason;
        public boolean isFullyAllocated;

        public GoodsReceiptLineDto(
                String lineId,
                String poItemId,
                String productId,
                String productBrandName,
                String batchNumber,
                LocalDate expiryDate,
                LocalDate mfgDate,
                String packUnit,
                int packageSize,
                int receivedPackages,
                int orderedQuantityBaseUnits,
                int receivedQuantityBaseUnits,
                BigDecimal unitCost,
                BigDecimal mrpUnit,
                BigDecimal discountPercentage,
                BigDecimal taxPercentage,
                String storageLocation,
                boolean isBonusGoods,
                int excessQuantity,
                String excessReason) {
            this.lineId = lineId != null ? lineId : "grnl-" + UUID.randomUUID().toString().substring(0, 8);
            this.poItemId = poItemId;
            this.productId = productId != null ? productId : "";
            this.productBrandName = productBrandName != null ? productBrandName : "";
            this.batchNumber = batchNumber != null ? batchNumber.trim() : "";
            this.expiryDate = expiryDate;
            this.mfgDate = mfgDate;
            this.packUnit = packUnit != null ? packUnit : "BOX";
            this.packageSize = Math.max(1, packageSize);
            this.receivedPackages = Math.max(0, receivedPackages);
            this.orderedQuantityBaseUnits = Math.max(0, orderedQuantityBaseUnits);

            int calcUnits = receivedQuantityBaseUnits;
            if (calcUnits <= 0 && this.receivedPackages > 0) {
                calcUnits = this.receivedPackages * this.packageSize;
            }
            this.receivedQuantityBaseUnits = Math.max(1, calcUnits);
            this.isBonusGoods = isBonusGoods;
            this.excessQuantity = Math.max(0, excessQuantity);
            this.excessReason = excessReason != null ? excessReason : "";

            if (isBonusGoods) {
                this.unitCost = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
                this.mrpUnit = mrpUnit != null ? mrpUnit.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
                this.discountPercentage = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                this.taxPercentage = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                this.lineTotal = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            } else {
                this.unitCost = unitCost != null ? unitCost.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
                this.mrpUnit = mrpUnit != null ? mrpUnit.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
                this.discountPercentage = discountPercentage != null ? discountPercentage.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                this.taxPercentage = taxPercentage != null ? taxPercentage.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

                BigDecimal discountFactor = BigDecimal.ONE.subtract(this.discountPercentage.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
                BigDecimal netCost = this.unitCost.multiply(discountFactor).setScale(4, RoundingMode.HALF_UP);
                BigDecimal subtotal = netCost.multiply(BigDecimal.valueOf(this.receivedQuantityBaseUnits)).setScale(4, RoundingMode.HALF_UP);
                BigDecimal taxAmount = subtotal.multiply(this.taxPercentage.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)).setScale(4, RoundingMode.HALF_UP);
                this.lineTotal = subtotal.add(taxAmount).setScale(4, RoundingMode.HALF_UP);
            }
            this.storageLocation = storageLocation != null ? storageLocation : "MAIN_RACK";
            this.isFullyAllocated = false;
        }

        public GoodsReceiptLineDto(
                String lineId,
                String poItemId,
                String productId,
                String productBrandName,
                String batchNumber,
                LocalDate expiryDate,
                LocalDate mfgDate,
                String packUnit,
                int packageSize,
                int receivedPackages,
                int orderedQuantityBaseUnits,
                int receivedQuantityBaseUnits,
                BigDecimal unitCost,
                BigDecimal mrpUnit,
                BigDecimal discountPercentage,
                BigDecimal taxPercentage,
                String storageLocation) {
            this(lineId, poItemId, productId, productBrandName, batchNumber, expiryDate, mfgDate, packUnit, packageSize, receivedPackages, orderedQuantityBaseUnits, receivedQuantityBaseUnits, unitCost, mrpUnit, discountPercentage, taxPercentage, storageLocation, false, 0, "");
        }
    }

    public static final class GoodsReceiptDto {
        public final String grnId;
        public final String grnNumber;
        public final String tenantId;
        public final String branchId;
        public final String purchaseOrderId;
        public final String purchaseOrderNumber;
        public final String supplierId;
        public final String supplierName;
        public final String challanNumber;
        public final LocalDate challanDate;
        public final LocalDate receiptDate;
        public final String storageCondition;
        public final BigDecimal storageTemperatureCelsius;
        public final boolean storageCheckPassed;
        public final String proofOfOrigin;
        public String status;
        public final int totalReceivedBaseUnits;
        public final BigDecimal totalInvoiceAmount;
        public String apPostingStatus;
        public BigDecimal apInvoiceAmount;
        public String notes;
        public boolean isActive;
        public final List<GoodsReceiptLineDto> lines;
        public final Instant createdAt;

        public GoodsReceiptDto(
                String grnId,
                String grnNumber,
                String tenantId,
                String branchId,
                String purchaseOrderId,
                String purchaseOrderNumber,
                String supplierId,
                String supplierName,
                String challanNumber,
                LocalDate challanDate,
                LocalDate receiptDate,
                String storageCondition,
                BigDecimal storageTemperatureCelsius,
                boolean storageCheckPassed,
                String proofOfOrigin,
                String status,
                int totalReceivedBaseUnits,
                BigDecimal totalInvoiceAmount,
                String apPostingStatus,
                BigDecimal apInvoiceAmount,
                String notes,
                boolean isActive,
                List<GoodsReceiptLineDto> lines,
                Instant createdAt) {
            this.grnId = grnId;
            this.grnNumber = grnNumber;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.purchaseOrderId = purchaseOrderId;
            this.purchaseOrderNumber = purchaseOrderNumber != null ? purchaseOrderNumber : "";
            this.supplierId = supplierId;
            this.supplierName = supplierName != null ? supplierName : "";
            this.challanNumber = challanNumber != null ? challanNumber : "";
            this.challanDate = challanDate;
            this.receiptDate = receiptDate != null ? receiptDate : LocalDate.now();
            this.storageCondition = storageCondition != null ? storageCondition : "ROOM_TEMPERATURE";
            this.storageTemperatureCelsius = storageTemperatureCelsius;
            this.storageCheckPassed = storageCheckPassed;
            this.proofOfOrigin = proofOfOrigin != null ? proofOfOrigin : "";
            this.status = status != null ? status : "RECEIVED";
            this.totalReceivedBaseUnits = totalReceivedBaseUnits;
            this.totalInvoiceAmount = totalInvoiceAmount != null ? totalInvoiceAmount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.apPostingStatus = apPostingStatus != null ? apPostingStatus : "PENDING";
            this.apInvoiceAmount = apInvoiceAmount != null ? apInvoiceAmount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.notes = notes != null ? notes : "";
            this.isActive = isActive;
            this.lines = lines != null ? new ArrayList<>(lines) : new ArrayList<>();
            this.createdAt = createdAt != null ? createdAt : Instant.now();
        }
    }

    private void checkProcurementRole() {
        if ("CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_ROLES.contains(userRole)) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers cannot create or post goods receipts");
        }
    }

    public GoodsReceiptDto createGrn(
            String purchaseOrderId,
            String supplierId,
            String supplierName,
            String challanNumber,
            LocalDate challanDate,
            LocalDate receiptDate,
            String storageCondition,
            BigDecimal storageTemperatureCelsius,
            boolean storageCheckPassed,
            String proofOfOrigin,
            String notes,
            List<GoodsReceiptLineDto> lines) {
        return createGrn(purchaseOrderId, supplierId, supplierName, challanNumber, challanDate, receiptDate, storageCondition, storageTemperatureCelsius, storageCheckPassed, proofOfOrigin, notes, false, "", lines);
    }

    public GoodsReceiptDto createGrn(
            String purchaseOrderId,
            String supplierId,
            String supplierName,
            String challanNumber,
            LocalDate challanDate,
            LocalDate receiptDate,
            String storageCondition,
            BigDecimal storageTemperatureCelsius,
            boolean storageCheckPassed,
            String proofOfOrigin,
            String notes,
            boolean allowExcess,
            String excessReason,
            List<GoodsReceiptLineDto> lines) {
        checkProcurementRole();

        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("ERR_EMPTY_GRN: Goods receipt must contain at least one line item");
        }

        // Purchase Order Validation
        PosPurchaseOrderClient.PurchaseOrderDto po = null;
        if (purchaseOrderId != null && !purchaseOrderId.trim().isEmpty()) {
            if (poClient != null) {
                po = poClient.getPurchaseOrder(purchaseOrderId.trim());
                if (!po.tenantId.equals(tenantId)) {
                    throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant purchase order access forbidden");
                }
                if (!"ORDERED".equals(po.status) && !"PARTIALLY_RECEIVED".equals(po.status)) {
                    throw new IllegalStateException("ERR_PO_NOT_RECEIVABLE: PO must be in ORDERED or PARTIALLY_RECEIVED status");
                }
            }
        }

        // Cold-Chain & Storage Inspection Checks
        String cond = storageCondition != null ? storageCondition.toUpperCase() : "ROOM_TEMPERATURE";
        if (cond.contains("COLD_CHAIN")) {
            if (!storageCheckPassed) {
                throw new IllegalStateException("ERR_STORAGE_CHECK_FAILED: Cold-chain physical inspection failed");
            }
            if (storageTemperatureCelsius != null) {
                if (storageTemperatureCelsius.compareTo(new BigDecimal("2.0")) < 0 ||
                    storageTemperatureCelsius.compareTo(new BigDecimal("8.0")) > 0) {
                    throw new IllegalStateException("ERR_STORAGE_CHECK_FAILED: Cold-chain temperature violation (" + storageTemperatureCelsius + " C). Accepted: 2-8 C");
                }
            }
        } else if (!storageCheckPassed) {
            throw new IllegalStateException("ERR_STORAGE_CHECK_FAILED: Storage inspection check failed");
        }

        LocalDate today = LocalDate.now();
        int totalUnits = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (GoodsReceiptLineDto line : lines) {
            if (line.productId == null || line.productId.trim().isEmpty()) {
                throw new IllegalArgumentException("ERR_MISSING_PRODUCT: Product ID cannot be empty");
            }
            if (line.batchNumber == null || line.batchNumber.trim().isEmpty()) {
                throw new IllegalArgumentException("ERR_MISSING_BATCH_NUMBER: Batch number cannot be empty");
            }
            if (line.expiryDate == null) {
                throw new IllegalArgumentException("ERR_MISSING_EXPIRY_DATE: Expiry date is required");
            }
            if (line.expiryDate.isBefore(today) || line.expiryDate.isEqual(today)) {
                throw new IllegalArgumentException("ERR_EXPIRED_BATCH_RECEIVED: Batch " + line.batchNumber + " is expired");
            }
            if (!line.isBonusGoods && line.unitCost.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("ERR_INVALID_COST: Unit cost must be positive");
            }
            if (!line.isBonusGoods && line.mrpUnit.compareTo(BigDecimal.ZERO) > 0 && line.unitCost.compareTo(line.mrpUnit) > 0) {
                throw new IllegalArgumentException("ERR_COST_EXCEEDS_MRP: Unit cost cannot exceed retail price");
            }

            // PO Over-receipt check
            if (po != null && line.poItemId != null) {
                PosPurchaseOrderClient.PurchaseOrderItemDto poItem = null;
                for (PosPurchaseOrderClient.PurchaseOrderItemDto itm : po.items) {
                    if (itm.itemId.equals(line.poItemId)) {
                        poItem = itm;
                        break;
                    }
                }
                if (poItem != null) {
                    int remaining = poItem.getBackorderedQuantity();
                    if (line.receivedQuantityBaseUnits > remaining) {
                        if (line.isBonusGoods) {
                            // Bonus promotional goods permitted
                        } else if (allowExcess) {
                            if (!"BRANCH_MANAGER".equals(userRole) && !"PHARMACY_OWNER".equals(userRole) && !"SUPERADMIN".equals(userRole)) {
                                throw new SecurityException("ERR_STAFF_ROLE_DENIED: Only Managers and Owners can authorize excess delivery");
                            }
                            if (excessReason == null || excessReason.trim().isEmpty()) {
                                throw new IllegalArgumentException("ERR_MISSING_EXCESS_REASON: Managerial excess reason is required");
                            }
                        } else {
                            throw new IllegalArgumentException("ERR_EXCESS_RECEIPT: Received quantity (" + line.receivedQuantityBaseUnits + ") exceeds remaining unreceived quantity (" + remaining + ") on PO item");
                        }
                    }
                }
            }

            totalUnits += line.receivedQuantityBaseUnits;
            totalAmount = totalAmount.add(line.lineTotal);
        }

        String grnId = "grn-" + UUID.randomUUID().toString().substring(0, 12);
        String grnNumber = "GRN-" + LocalDate.now().toString().replace("-", "").substring(0, 6) + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        GoodsReceiptDto grn = new GoodsReceiptDto(
                grnId,
                grnNumber,
                tenantId,
                branchId,
                purchaseOrderId,
                po != null ? po.poNumber : "",
                supplierId,
                supplierName != null ? supplierName : (po != null ? po.supplierName : ""),
                challanNumber,
                challanDate,
                receiptDate,
                cond,
                storageTemperatureCelsius,
                storageCheckPassed,
                proofOfOrigin,
                "RECEIVED",
                totalUnits,
                totalAmount,
                "PENDING",
                BigDecimal.ZERO,
                notes,
                true,
                lines,
                Instant.now()
        );

        grnStore.put(grnId, grn);
        return grn;
    }

    public GoodsReceiptDto postGrnToStock(String grnId) {
        checkProcurementRole();

        GoodsReceiptDto grn = grnStore.get(grnId);
        if (grn == null) {
            throw new NoSuchElementException("ERR_GRN_NOT_FOUND: Goods receipt " + grnId + " not found");
        }
        if (!grn.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant GRN post forbidden");
        }
        if ("POSTED".equals(grn.status)) {
            throw new IllegalStateException("ERR_ALREADY_POSTED: Goods receipt is already posted to inventory");
        }
        if ("CANCELLED".equals(grn.status) || "REVERSED".equals(grn.status)) {
            throw new IllegalStateException("ERR_GRN_INACTIVE: Cannot post inactive GRN");
        }

        // Post lines & update PO if linked
        if (poClient != null && grn.purchaseOrderId != null && !grn.purchaseOrderId.trim().isEmpty()) {
            Map<String, Integer> receipts = new HashMap<>();
            for (GoodsReceiptLineDto line : grn.lines) {
                if (line.poItemId != null) {
                    receipts.put(line.poItemId, receipts.getOrDefault(line.poItemId, 0) + line.receivedQuantityBaseUnits);
                }
            }
            if (!receipts.isEmpty()) {
                poClient.receiveItems(grn.purchaseOrderId, receipts);
            }
        }

        for (GoodsReceiptLineDto line : grn.lines) {
            line.isFullyAllocated = true;
        }

        grn.status = "POSTED";
        grn.apPostingStatus = "POSTED";
        grn.apInvoiceAmount = grn.totalInvoiceAmount;
        return grn;
    }

    public GoodsReceiptDto reverseGrn(String grnId, String reason, Map<String, Integer> consumedStockPerLine) {
        checkProcurementRole();

        GoodsReceiptDto grn = grnStore.get(grnId);
        if (grn == null) {
            throw new NoSuchElementException("ERR_GRN_NOT_FOUND: Goods receipt " + grnId + " not found");
        }
        if (!grn.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant GRN reversal forbidden");
        }

        if (!"POSTED".equals(grn.status)) {
            grn.status = "CANCELLED";
            return grn;
        }

        // Check if any portion was consumed
        if (consumedStockPerLine != null) {
            for (GoodsReceiptLineDto line : grn.lines) {
                int consumed = consumedStockPerLine.getOrDefault(line.lineId, 0);
                if (consumed > 0) {
                    throw new IllegalStateException("ERR_CANNOT_REVERSE_CONSUMED_STOCK: Cannot reverse GRN; " + consumed + " units of batch " + line.batchNumber + " already dispensed");
                }
            }
        }

        for (GoodsReceiptLineDto line : grn.lines) {
            line.isFullyAllocated = false;
        }

        grn.status = "REVERSED";
        grn.apPostingStatus = "REVERSED";
        grn.notes = grn.notes + " | Reversal Reason: " + (reason != null ? reason : "Reversed");
        return grn;
    }

    public GoodsReceiptDto getGrn(String grnId) {
        GoodsReceiptDto grn = grnStore.get(grnId);
        if (grn == null) {
            throw new NoSuchElementException("ERR_GRN_NOT_FOUND: Goods receipt " + grnId + " not found");
        }
        if (!grn.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant GRN access forbidden");
        }
        return grn;
    }

    public List<GoodsReceiptDto> listGrns(String purchaseOrderId, String status) {
        List<GoodsReceiptDto> list = new ArrayList<>();
        for (GoodsReceiptDto grn : grnStore.values()) {
            if (!grn.tenantId.equals(tenantId)) {
                continue;
            }
            if (purchaseOrderId != null && !purchaseOrderId.equals(grn.purchaseOrderId)) {
                continue;
            }
            if (status != null && !status.equalsIgnoreCase(grn.status)) {
                continue;
            }
            list.add(grn);
        }
        list.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return list;
    }

    public PosPurchaseOrderClient.PurchaseOrderDto cancelPoBackorder(String poId, String itemId, int cancelQuantity, String reason) {
        if (poClient == null) {
            throw new IllegalStateException("ERR_PO_CLIENT_NOT_CONFIGURED: Purchase order client is required");
        }
        return poClient.cancelBackorder(poId, itemId, cancelQuantity, reason);
    }
}

