package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * PosPurchaseOrderClient
 * Architecture Decision: ADR-0185 (P185).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 *
 * Desktop POS client governing purchase orders, financial commitment calculation,
 * goods receipt progress tracking, and guarded state machine.
 */
public class PosPurchaseOrderClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, PurchaseOrderDto> poStore;

    private static final Set<String> ALLOWED_PROCUREMENT_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "PROCUREMENT_OFFICER", "AUDITOR"
    );

    private static final Set<String> APPROVAL_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "PROCUREMENT_OFFICER"
    );

    public PosPurchaseOrderClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, PurchaseOrderDto> poStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.poStore = poStore != null ? poStore : new HashMap<>();
    }

    public static final class PurchaseOrderItemDto {
        public final String itemId;
        public final String productId;
        public final String productName;
        public final String packageType;
        public final String packUnit;
        public final int packageSize;
        public final int orderedPackages;
        public final int orderedQuantity;
        public int receivedQuantity;
        public int cancelledQuantity;
        public String deliveryStatus;
        public final BigDecimal unitCostPrice;
        public final BigDecimal packageCostPrice;
        public final BigDecimal mrpUnit;
        public final BigDecimal discountPercentage;
        public final BigDecimal netUnitCost;
        public final BigDecimal taxPercentage;
        public final BigDecimal taxAmount;
        public final BigDecimal lineSubtotal;
        public final BigDecimal lineTotal;
        public final LocalDate expectedDeliveryDate;
        public final int freeGoodsBonusQuantity;
        public final String notes;

        public PurchaseOrderItemDto(
                String itemId,
                String productId,
                String productName,
                String packUnit,
                int packageSize,
                int orderedPackages,
                int orderedQuantity,
                int receivedQuantity,
                BigDecimal unitCostPrice,
                BigDecimal mrpUnit,
                BigDecimal discountPercentage,
                BigDecimal taxPercentage,
                LocalDate expectedDeliveryDate,
                int freeGoodsBonusQuantity,
                String notes) {
            this.itemId = itemId != null ? itemId : "poi-" + UUID.randomUUID().toString().substring(0, 8);
            this.productId = productId != null ? productId : "";
            this.productName = productName != null ? productName : "";
            this.packUnit = (packUnit != null && !packUnit.trim().isEmpty()) ? packUnit.trim() : "BOX";
            this.packageType = this.packUnit;
            this.packageSize = Math.max(1, packageSize);
            this.orderedPackages = Math.max(0, orderedPackages);

            int calcQty = orderedQuantity;
            if (calcQty <= 0 && this.orderedPackages > 0) {
                calcQty = this.orderedPackages * this.packageSize;
            }
            this.orderedQuantity = Math.max(1, calcQty);
            this.receivedQuantity = Math.max(0, receivedQuantity);
            this.unitCostPrice = unitCostPrice != null ? unitCostPrice.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.packageCostPrice = this.unitCostPrice.multiply(BigDecimal.valueOf(this.packageSize)).setScale(4, RoundingMode.HALF_UP);
            this.mrpUnit = mrpUnit != null ? mrpUnit.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.discountPercentage = discountPercentage != null ? discountPercentage.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.taxPercentage = taxPercentage != null ? taxPercentage.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            BigDecimal discountFactor = BigDecimal.ONE.subtract(this.discountPercentage.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            this.netUnitCost = this.unitCostPrice.multiply(discountFactor).setScale(4, RoundingMode.HALF_UP);
            this.lineSubtotal = this.netUnitCost.multiply(BigDecimal.valueOf(this.orderedQuantity)).setScale(4, RoundingMode.HALF_UP);
            this.taxAmount = this.lineSubtotal.multiply(this.taxPercentage.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)).setScale(4, RoundingMode.HALF_UP);
            this.lineTotal = this.lineSubtotal.add(this.taxAmount).setScale(4, RoundingMode.HALF_UP);
            this.expectedDeliveryDate = expectedDeliveryDate;
            this.freeGoodsBonusQuantity = Math.max(0, freeGoodsBonusQuantity);
            this.notes = notes != null ? notes : "";
            this.cancelledQuantity = 0;
            if (this.receivedQuantity >= this.orderedQuantity) {
                this.deliveryStatus = "FULLY_DELIVERED";
            } else if (this.receivedQuantity > 0) {
                this.deliveryStatus = "PARTIALLY_DELIVERED";
            } else {
                this.deliveryStatus = "PENDING";
            }
        }

        public int getBackorderedQuantity() {
            return Math.max(0, orderedQuantity - receivedQuantity - cancelledQuantity);
        }

        public PurchaseOrderItemDto(
                String itemId,
                String productId,
                String productName,
                String packageType,
                int packageSize,
                int orderedQuantity,
                int receivedQuantity,
                BigDecimal unitCostPrice,
                BigDecimal discountPercentage,
                String notes) {
            this(itemId, productId, productName, packageType, packageSize, 0, orderedQuantity, receivedQuantity, unitCostPrice, BigDecimal.ZERO, discountPercentage, BigDecimal.ZERO, null, 0, notes);
        }
    }

    public static final class PurchaseOrderDto {
        public final String poId;
        public final String poNumber;
        public final String tenantId;
        public final String branchId;
        public String branchName;
        public final String supplierId;
        public String supplierName;
        public String requisitionId;
        public String status;
        public int paymentTermsDays;
        public String currency;
        public BigDecimal subtotalAmount;
        public BigDecimal discountAmount;
        public BigDecimal taxAmount;
        public BigDecimal totalAmount;
        public LocalDate expectedDeliveryDate;
        public Instant orderedAt;
        public String approvedById;
        public Instant approvedAt;
        public String approvalNotes;
        public Instant closedAt;
        public String closingNotes;
        public String cancelledById;
        public Instant cancelledAt;
        public String cancellationReason;
        public String notes;
        public boolean isActive;
        public final List<PurchaseOrderItemDto> items;
        public final Instant createdAt;

        public PurchaseOrderDto(
                String poId,
                String poNumber,
                String tenantId,
                String branchId,
                String branchName,
                String supplierId,
                String supplierName,
                String requisitionId,
                String status,
                int paymentTermsDays,
                String currency,
                BigDecimal subtotalAmount,
                BigDecimal discountAmount,
                BigDecimal taxAmount,
                BigDecimal totalAmount,
                LocalDate expectedDeliveryDate,
                Instant orderedAt,
                String approvedById,
                Instant approvedAt,
                String approvalNotes,
                Instant closedAt,
                String closingNotes,
                String cancelledById,
                Instant cancelledAt,
                String cancellationReason,
                String notes,
                boolean isActive,
                List<PurchaseOrderItemDto> items,
                Instant createdAt) {
            this.poId = poId;
            this.poNumber = poNumber;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.branchName = branchName != null ? branchName : "";
            this.supplierId = supplierId;
            this.supplierName = supplierName != null ? supplierName : "";
            this.requisitionId = requisitionId;
            this.status = status != null ? status : "DRAFT";
            this.paymentTermsDays = paymentTermsDays;
            this.currency = currency != null ? currency : "BDT";
            this.subtotalAmount = subtotalAmount != null ? subtotalAmount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.discountAmount = discountAmount != null ? discountAmount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.taxAmount = taxAmount != null ? taxAmount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.totalAmount = totalAmount != null ? totalAmount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.expectedDeliveryDate = expectedDeliveryDate;
            this.orderedAt = orderedAt;
            this.approvedById = approvedById;
            this.approvedAt = approvedAt;
            this.approvalNotes = approvalNotes != null ? approvalNotes : "";
            this.closedAt = closedAt;
            this.closingNotes = closingNotes != null ? closingNotes : "";
            this.cancelledById = cancelledById;
            this.cancelledAt = cancelledAt;
            this.cancellationReason = cancellationReason != null ? cancellationReason : "";
            this.notes = notes != null ? notes : "";
            this.isActive = isActive;
            this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
            this.createdAt = createdAt != null ? createdAt : Instant.now();
        }
    }

    private void checkProcurementRole() {
        if (!ALLOWED_PROCUREMENT_ROLES.contains(userRole)) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers and unauthorized roles cannot manage purchase orders");
        }
    }

    private void checkApprovalRole() {
        if (!APPROVAL_ROLES.contains(userRole)) {
            throw new SecurityException("ERR_APPROVAL_ROLE_REQUIRED: Only Branch Managers and Owners can approve, dispatch, or cancel orders");
        }
    }

    public PurchaseOrderDto createPurchaseOrder(
            String supplierId,
            String supplierName,
            String requisitionId,
            int paymentTermsDays,
            BigDecimal orderDiscount,
            BigDecimal orderTax,
            LocalDate expectedDeliveryDate,
            String notes,
            List<PurchaseOrderItemDto> items) {
        checkProcurementRole();

        if (supplierId == null || supplierId.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_SUPPLIER_ID: Supplier ID cannot be empty");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("ERR_EMPTY_PO_ITEMS: Purchase order must contain at least one item");
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        for (PurchaseOrderItemDto item : items) {
            if (item.productId == null || item.productId.trim().isEmpty()) {
                throw new IllegalArgumentException("ERR_MISSING_PRODUCT_ID: Product ID cannot be empty");
            }
            if (item.orderedQuantity < 1) {
                throw new IllegalArgumentException("ERR_INVALID_QUANTITY: Ordered quantity must be at least 1");
            }
            if (item.unitCostPrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("ERR_INVALID_COST_PRICE: Unit cost price must be positive");
            }
            subtotal = subtotal.add(item.lineTotal);
        }

        BigDecimal discount = orderDiscount != null ? orderDiscount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        BigDecimal tax = orderTax != null ? orderTax.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.subtract(discount).add(tax).setScale(4, RoundingMode.HALF_UP);
        if (total.compareTo(BigDecimal.ZERO) < 0) {
            total = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        String poId = "po-" + UUID.randomUUID().toString().substring(0, 12);
        String poNumber = "PO-" + LocalDate.now().toString().replace("-", "").substring(0, 6) + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        PurchaseOrderDto po = new PurchaseOrderDto(
                poId,
                poNumber,
                tenantId,
                branchId,
                "Branch " + branchId,
                supplierId.trim(),
                supplierName != null ? supplierName.trim() : "",
                requisitionId,
                "DRAFT",
                Math.max(0, paymentTermsDays),
                "BDT",
                subtotal,
                discount,
                tax,
                total,
                expectedDeliveryDate,
                null, null, null, "", null, "", null, null, "",
                notes != null ? notes.trim() : "",
                true,
                items,
                Instant.now()
        );

        poStore.put(poId, po);
        return po;
    }

    public PurchaseOrderDto approvePurchaseOrder(String poId, String approvalNotes) {
        checkApprovalRole();

        PurchaseOrderDto existing = poStore.get(poId);
        if (existing == null) {
            throw new NoSuchElementException("ERR_PO_NOT_FOUND: Purchase order " + poId + " not found");
        }
        if (!existing.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant purchase order approval forbidden");
        }
        if (!"DRAFT".equals(existing.status)) {
            throw new IllegalStateException("ERR_INVALID_STATE_TRANSITION: Purchase order must be in DRAFT to approve");
        }

        PurchaseOrderDto approved = new PurchaseOrderDto(
                existing.poId,
                existing.poNumber,
                existing.tenantId,
                existing.branchId,
                existing.branchName,
                existing.supplierId,
                existing.supplierName,
                existing.requisitionId,
                "APPROVED",
                existing.paymentTermsDays,
                existing.currency,
                existing.subtotalAmount,
                existing.discountAmount,
                existing.taxAmount,
                existing.totalAmount,
                existing.expectedDeliveryDate,
                null,
                userId,
                Instant.now(),
                approvalNotes != null ? approvalNotes.trim() : "",
                null, "", null, null, "",
                existing.notes,
                existing.isActive,
                existing.items,
                existing.createdAt
        );

        poStore.put(poId, approved);
        return approved;
    }

    public PurchaseOrderDto orderPurchaseOrder(String poId) {
        checkApprovalRole();

        PurchaseOrderDto existing = poStore.get(poId);
        if (existing == null) {
            throw new NoSuchElementException("ERR_PO_NOT_FOUND: Purchase order " + poId + " not found");
        }
        if (!existing.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant purchase order dispatch forbidden");
        }
        if (!"APPROVED".equals(existing.status)) {
            throw new IllegalStateException("ERR_INVALID_STATE_TRANSITION: Purchase order must be APPROVED to dispatch");
        }

        PurchaseOrderDto ordered = new PurchaseOrderDto(
                existing.poId,
                existing.poNumber,
                existing.tenantId,
                existing.branchId,
                existing.branchName,
                existing.supplierId,
                existing.supplierName,
                existing.requisitionId,
                "ORDERED",
                existing.paymentTermsDays,
                existing.currency,
                existing.subtotalAmount,
                existing.discountAmount,
                existing.taxAmount,
                existing.totalAmount,
                existing.expectedDeliveryDate,
                Instant.now(),
                existing.approvedById,
                existing.approvedAt,
                existing.approvalNotes,
                null, "", null, null, "",
                existing.notes,
                existing.isActive,
                existing.items,
                existing.createdAt
        );

        poStore.put(poId, ordered);
        return ordered;
    }

    public PurchaseOrderDto receiveItems(String poId, Map<String, Integer> itemReceipts) {
        checkApprovalRole();

        PurchaseOrderDto existing = poStore.get(poId);
        if (existing == null) {
            throw new NoSuchElementException("ERR_PO_NOT_FOUND: Purchase order " + poId + " not found");
        }
        if (!existing.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant purchase order receipt forbidden");
        }
        if (!"ORDERED".equals(existing.status) && !"PARTIALLY_RECEIVED".equals(existing.status)) {
            throw new IllegalStateException("ERR_INVALID_STATE_TRANSITION: PO must be ORDERED or PARTIALLY_RECEIVED to receive goods");
        }

        if (itemReceipts != null) {
            for (PurchaseOrderItemDto item : existing.items) {
                if (itemReceipts.containsKey(item.itemId)) {
                    int addQty = itemReceipts.get(item.itemId);
                    if (addQty > 0) {
                        item.receivedQuantity += addQty;
                        if ((item.receivedQuantity + item.cancelledQuantity) >= item.orderedQuantity) {
                            item.deliveryStatus = "FULLY_DELIVERED";
                        } else if (item.receivedQuantity > 0) {
                            item.deliveryStatus = "PARTIALLY_DELIVERED";
                        }
                    }
                }
            }
        }

        boolean allFulfilled = true;
        boolean anyReceived = false;
        for (PurchaseOrderItemDto item : existing.items) {
            if (item.receivedQuantity < item.orderedQuantity) {
                allFulfilled = false;
            }
            if (item.receivedQuantity > 0) {
                anyReceived = true;
            }
        }

        String newStatus = existing.status;
        Instant closedAt = existing.closedAt;
        String closingNotes = existing.closingNotes;

        if (allFulfilled) {
            newStatus = "CLOSED";
            closedAt = Instant.now();
            closingNotes = "All line items fully received";
        } else if (anyReceived) {
            newStatus = "PARTIALLY_RECEIVED";
        }

        PurchaseOrderDto updated = new PurchaseOrderDto(
                existing.poId,
                existing.poNumber,
                existing.tenantId,
                existing.branchId,
                existing.branchName,
                existing.supplierId,
                existing.supplierName,
                existing.requisitionId,
                newStatus,
                existing.paymentTermsDays,
                existing.currency,
                existing.subtotalAmount,
                existing.discountAmount,
                existing.taxAmount,
                existing.totalAmount,
                existing.expectedDeliveryDate,
                existing.orderedAt,
                existing.approvedById,
                existing.approvedAt,
                existing.approvalNotes,
                closedAt,
                closingNotes,
                null, null, "",
                existing.notes,
                existing.isActive,
                existing.items,
                existing.createdAt
        );

        poStore.put(poId, updated);
        return updated;
    }

    public PurchaseOrderDto cancelBackorder(String poId, String itemId, int cancelQuantity, String reason) {
        checkApprovalRole();
        PurchaseOrderDto existing = poStore.get(poId);
        if (existing == null) {
            throw new NoSuchElementException("ERR_PO_NOT_FOUND: Purchase order " + poId + " not found");
        }
        if (!existing.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant PO backorder cancellation forbidden");
        }
        if (!"ORDERED".equals(existing.status) && !"PARTIALLY_RECEIVED".equals(existing.status)) {
            throw new IllegalStateException("ERR_PO_NOT_MODIFIABLE: Cannot cancel backorders on PO in status " + existing.status);
        }

        PurchaseOrderItemDto targetItem = null;
        for (PurchaseOrderItemDto item : existing.items) {
            if (item.itemId.equals(itemId)) {
                targetItem = item;
                break;
            }
        }
        if (targetItem == null) {
            throw new NoSuchElementException("ERR_PO_ITEM_NOT_FOUND: PO item " + itemId + " not found");
        }

        int currentBackorder = targetItem.getBackorderedQuantity();
        if (cancelQuantity <= 0 || cancelQuantity > currentBackorder) {
            throw new IllegalArgumentException("ERR_INVALID_CANCEL_QUANTITY: Cannot cancel " + cancelQuantity + "; backordered is " + currentBackorder);
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_CANCEL_REASON: Cancellation reason is required");
        }

        targetItem.cancelledQuantity += cancelQuantity;
        if ((targetItem.receivedQuantity + targetItem.cancelledQuantity) >= targetItem.orderedQuantity) {
            targetItem.deliveryStatus = targetItem.receivedQuantity > 0 ? "FULLY_DELIVERED" : "CANCELLED";
        } else {
            targetItem.deliveryStatus = "PARTIALLY_DELIVERED";
        }

        boolean allFulfilled = true;
        for (PurchaseOrderItemDto item : existing.items) {
            if ((item.receivedQuantity + item.cancelledQuantity) < item.orderedQuantity) {
                allFulfilled = false;
                break;
            }
        }

        String newStatus = existing.status;
        Instant closedAt = existing.closedAt;
        String closingNotes = existing.closingNotes;
        if (allFulfilled) {
            newStatus = "CLOSED";
            closedAt = Instant.now();
            closingNotes = "Closed after backorder cancellation: " + reason.trim();
        }

        PurchaseOrderDto updated = new PurchaseOrderDto(
                existing.poId,
                existing.poNumber,
                existing.tenantId,
                existing.branchId,
                existing.branchName,
                existing.supplierId,
                existing.supplierName,
                existing.requisitionId,
                newStatus,
                existing.paymentTermsDays,
                existing.currency,
                existing.subtotalAmount,
                existing.discountAmount,
                existing.taxAmount,
                existing.totalAmount,
                existing.expectedDeliveryDate,
                existing.orderedAt,
                existing.approvedById,
                existing.approvedAt,
                existing.approvalNotes,
                closedAt,
                closingNotes,
                existing.cancelledById,
                existing.cancelledAt,
                existing.cancellationReason,
                existing.notes,
                existing.isActive,
                existing.items,
                existing.createdAt
        );

        poStore.put(poId, updated);
        return updated;
    }

    public PurchaseOrderDto closePurchaseOrder(String poId, String closingNotes) {
        checkApprovalRole();

        PurchaseOrderDto existing = poStore.get(poId);
        if (existing == null) {
            throw new NoSuchElementException("ERR_PO_NOT_FOUND: Purchase order " + poId + " not found");
        }
        if (!existing.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant purchase order close forbidden");
        }
        if (!"ORDERED".equals(existing.status) && !"PARTIALLY_RECEIVED".equals(existing.status)) {
            throw new IllegalStateException("ERR_INVALID_STATE_TRANSITION: PO must be ORDERED or PARTIALLY_RECEIVED to close");
        }

        PurchaseOrderDto closed = new PurchaseOrderDto(
                existing.poId,
                existing.poNumber,
                existing.tenantId,
                existing.branchId,
                existing.branchName,
                existing.supplierId,
                existing.supplierName,
                existing.requisitionId,
                "CLOSED",
                existing.paymentTermsDays,
                existing.currency,
                existing.subtotalAmount,
                existing.discountAmount,
                existing.taxAmount,
                existing.totalAmount,
                existing.expectedDeliveryDate,
                existing.orderedAt,
                existing.approvedById,
                existing.approvedAt,
                existing.approvalNotes,
                Instant.now(),
                closingNotes != null ? closingNotes.trim() : "Manually closed",
                null, null, "",
                existing.notes,
                existing.isActive,
                existing.items,
                existing.createdAt
        );

        poStore.put(poId, closed);
        return closed;
    }

    public PurchaseOrderDto cancelPurchaseOrder(String poId, String cancellationReason) {
        checkApprovalRole();

        PurchaseOrderDto existing = poStore.get(poId);
        if (existing == null) {
            throw new NoSuchElementException("ERR_PO_NOT_FOUND: Purchase order " + poId + " not found");
        }
        if (!existing.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant purchase order cancellation forbidden");
        }
        if ("CLOSED".equals(existing.status) || "CANCELLED".equals(existing.status)) {
            throw new IllegalStateException("ERR_INVALID_STATE_TRANSITION: Cannot cancel order in " + existing.status + " status");
        }

        int totalReceived = 0;
        for (PurchaseOrderItemDto item : existing.items) {
            totalReceived += item.receivedQuantity;
        }
        if (totalReceived > 0) {
            throw new IllegalStateException("ERR_CANNOT_CANCEL_RECEIVED_PO: Cannot cancel purchase order with received goods (" + totalReceived + " units received)");
        }

        if (cancellationReason == null || cancellationReason.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_CANCELLATION_REASON: cancellation_reason is required");
        }

        PurchaseOrderDto cancelled = new PurchaseOrderDto(
                existing.poId,
                existing.poNumber,
                existing.tenantId,
                existing.branchId,
                existing.branchName,
                existing.supplierId,
                existing.supplierName,
                existing.requisitionId,
                "CANCELLED",
                existing.paymentTermsDays,
                existing.currency,
                existing.subtotalAmount,
                existing.discountAmount,
                existing.taxAmount,
                existing.totalAmount,
                existing.expectedDeliveryDate,
                existing.orderedAt,
                existing.approvedById,
                existing.approvedAt,
                existing.approvalNotes,
                existing.closedAt,
                existing.closingNotes,
                userId,
                Instant.now(),
                cancellationReason.trim(),
                existing.notes,
                existing.isActive,
                existing.items,
                existing.createdAt
        );

        poStore.put(poId, cancelled);
        return cancelled;
    }

    public PurchaseOrderDto getPurchaseOrder(String poId) {
        PurchaseOrderDto existing = poStore.get(poId);
        if (existing == null) {
            throw new NoSuchElementException("ERR_PO_NOT_FOUND: Purchase order " + poId + " not found");
        }
        if (!existing.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant purchase order access forbidden");
        }
        return existing;
    }

    public List<PurchaseOrderDto> listPurchaseOrders(String supplierId, String status) {
        List<PurchaseOrderDto> list = new ArrayList<>();
        for (PurchaseOrderDto po : poStore.values()) {
            if (!po.tenantId.equals(tenantId)) {
                continue;
            }
            if (supplierId != null && !po.supplierId.equals(supplierId)) {
                continue;
            }
            if (status != null && !po.status.equalsIgnoreCase(status)) {
                continue;
            }
            list.add(po);
        }
        list.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return list;
    }

    private PurchaseOrderDto recalculatePo(PurchaseOrderDto po, List<PurchaseOrderItemDto> items) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        for (PurchaseOrderItemDto item : items) {
            subtotal = subtotal.add(item.lineSubtotal);
            taxTotal = taxTotal.add(item.taxAmount);
        }
        BigDecimal discount = po.discountAmount != null ? po.discountAmount : BigDecimal.ZERO;
        BigDecimal total = subtotal.subtract(discount).add(taxTotal).setScale(4, RoundingMode.HALF_UP);
        if (total.compareTo(BigDecimal.ZERO) < 0) {
            total = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return new PurchaseOrderDto(
                po.poId, po.poNumber, po.tenantId, po.branchId, po.branchName,
                po.supplierId, po.supplierName, po.requisitionId, po.status,
                po.paymentTermsDays, po.currency, subtotal, discount, taxTotal, total,
                po.expectedDeliveryDate, po.orderedAt, po.approvedById, po.approvedAt,
                po.approvalNotes, po.closedAt, po.closingNotes, po.cancelledById,
                po.cancelledAt, po.cancellationReason, po.notes, po.isActive, items, po.createdAt
        );
    }

    private void validateLineItem(PurchaseOrderItemDto item) {
        if (item.productId == null || item.productId.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_PRODUCT_ID: Product ID cannot be empty");
        }
        if (item.orderedQuantity < 1) {
            throw new IllegalArgumentException("ERR_INVALID_QUANTITY: Ordered quantity must be at least 1");
        }
        if (item.unitCostPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("ERR_INVALID_COST_PRICE: Unit cost price must be positive");
        }
        if (item.discountPercentage.compareTo(BigDecimal.ZERO) < 0 || item.discountPercentage.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("ERR_INVALID_DISCOUNT: Discount percentage must be between 0 and 100");
        }
        if (item.taxPercentage.compareTo(BigDecimal.ZERO) < 0 || item.taxPercentage.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("ERR_INVALID_TAX: Tax percentage must be between 0 and 100");
        }
        if (item.mrpUnit.compareTo(BigDecimal.ZERO) > 0 && item.unitCostPrice.compareTo(item.mrpUnit) > 0) {
            throw new IllegalArgumentException("ERR_COST_EXCEEDS_MRP: Unit cost price (" + item.unitCostPrice + ") cannot exceed maximum retail price (" + item.mrpUnit + ")");
        }
    }

    public List<PurchaseOrderItemDto> listPoItems(String poId) {
        PurchaseOrderDto po = getPurchaseOrder(poId);
        return Collections.unmodifiableList(po.items);
    }

    public PurchaseOrderDto addPoItem(String poId, PurchaseOrderItemDto newItem) {
        checkProcurementRole();
        PurchaseOrderDto existing = getPurchaseOrder(poId);
        if (!"DRAFT".equals(existing.status)) {
            throw new IllegalStateException("ERR_PO_LOCKED: Line items can only be added to DRAFT purchase orders");
        }
        if (newItem == null) {
            throw new IllegalArgumentException("ERR_NULL_ITEM: Item cannot be null");
        }
        validateLineItem(newItem);

        List<PurchaseOrderItemDto> updatedItems = new ArrayList<>(existing.items);
        updatedItems.add(newItem);
        PurchaseOrderDto updatedPo = recalculatePo(existing, updatedItems);
        poStore.put(poId, updatedPo);
        return updatedPo;
    }

    public PurchaseOrderDto updatePoItem(String poId, String itemId, PurchaseOrderItemDto updatedItem) {
        checkProcurementRole();
        PurchaseOrderDto existing = getPurchaseOrder(poId);
        if (!"DRAFT".equals(existing.status)) {
            throw new IllegalStateException("ERR_PO_LOCKED: Line items can only be modified in DRAFT purchase orders");
        }
        if (itemId == null || itemId.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_ITEM_ID: Item ID cannot be empty");
        }
        if (updatedItem == null) {
            throw new IllegalArgumentException("ERR_NULL_ITEM: Item cannot be null");
        }
        validateLineItem(updatedItem);

        boolean found = false;
        List<PurchaseOrderItemDto> updatedItems = new ArrayList<>();
        for (PurchaseOrderItemDto item : existing.items) {
            if (item.itemId.equals(itemId)) {
                updatedItems.add(updatedItem);
                found = true;
            } else {
                updatedItems.add(item);
            }
        }
        if (!found) {
            throw new NoSuchElementException("ERR_ITEM_NOT_FOUND: Item " + itemId + " not found on purchase order");
        }

        PurchaseOrderDto updatedPo = recalculatePo(existing, updatedItems);
        poStore.put(poId, updatedPo);
        return updatedPo;
    }

    public PurchaseOrderDto deletePoItem(String poId, String itemId) {
        checkProcurementRole();
        PurchaseOrderDto existing = getPurchaseOrder(poId);
        if (!"DRAFT".equals(existing.status)) {
            throw new IllegalStateException("ERR_PO_LOCKED: Line items can only be deleted from DRAFT purchase orders");
        }
        if (itemId == null || itemId.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_ITEM_ID: Item ID cannot be empty");
        }

        boolean found = false;
        List<PurchaseOrderItemDto> updatedItems = new ArrayList<>();
        for (PurchaseOrderItemDto item : existing.items) {
            if (item.itemId.equals(itemId)) {
                found = true;
            } else {
                updatedItems.add(item);
            }
        }
        if (!found) {
            throw new NoSuchElementException("ERR_ITEM_NOT_FOUND: Item " + itemId + " not found on purchase order");
        }
        if (updatedItems.isEmpty()) {
            throw new IllegalStateException("ERR_CANNOT_DELETE_LAST_ITEM: Purchase order must retain at least one item");
        }

        PurchaseOrderDto updatedPo = recalculatePo(existing, updatedItems);
        poStore.put(poId, updatedPo);
        return updatedPo;
    }
}

