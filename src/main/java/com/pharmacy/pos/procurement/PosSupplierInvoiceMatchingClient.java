package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * PosSupplierInvoiceMatchingClient
 * Architecture Decision: ADR-0190 (P190).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 *
 * Desktop JavaFX client governing 3-Way Supplier Invoice Matching:
 * - Reconciles PO commitment <-> GRN physical custody <-> Supplier Invoice billing.
 * - Handles price variance tolerances (default 1.5% or ৳50 BDT) and quantity variance.
 * - Routes price spikes, physical over-billing, and unmatched items to the Discrepancy Queue.
 * - Enforces managerial exception overrides and immutable Accounts Payable ledger posting.
 */
public class PosSupplierInvoiceMatchingClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, SupplierInvoiceDto> invoiceStore;
    private final Map<String, SupplierLedgerEntryDto> ledgerStore;
    private final PosPurchaseOrderClient poClient;
    private final PosGoodsReceiptClient grnClient;

    private static final Set<String> ALLOWED_PROCUREMENT_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "PROCUREMENT_OFFICER", "INVENTORY_CLERK", "AUDITOR"
    );

    private static final Set<String> ALLOWED_APPROVER_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "AUDITOR"
    );

    public PosSupplierInvoiceMatchingClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, SupplierInvoiceDto> invoiceStore,
            Map<String, SupplierLedgerEntryDto> ledgerStore,
            PosPurchaseOrderClient poClient,
            PosGoodsReceiptClient grnClient) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.invoiceStore = invoiceStore != null ? invoiceStore : new HashMap<>();
        this.ledgerStore = ledgerStore != null ? ledgerStore : new HashMap<>();
        this.poClient = poClient;
        this.grnClient = grnClient;
    }

    public static final class SupplierInvoiceItemDto {
        public final String itemId;
        public final String productId;
        public final String productBrandName;
        public final String poItemId;
        public final String grnLineId;
        public final String batchNumber;
        public final int billedQuantity;
        public int receivedQuantity;
        public int orderedQuantity;
        public final BigDecimal billedUnitPrice;
        public BigDecimal poUnitPrice;
        public final BigDecimal lineSubtotal;
        public final BigDecimal lineTaxPercentage;
        public final BigDecimal lineDiscountPercentage;
        public final BigDecimal lineTotal;
        public String matchStatus;
        public BigDecimal priceVariance;
        public int quantityVariance;
        public final String notes;

        public SupplierInvoiceItemDto(
                String itemId,
                String productId,
                String productBrandName,
                String poItemId,
                String grnLineId,
                String batchNumber,
                int billedQuantity,
                int receivedQuantity,
                int orderedQuantity,
                BigDecimal billedUnitPrice,
                BigDecimal poUnitPrice,
                BigDecimal lineSubtotal,
                BigDecimal lineTaxPercentage,
                BigDecimal lineDiscountPercentage,
                BigDecimal lineTotal,
                String matchStatus,
                BigDecimal priceVariance,
                int quantityVariance,
                String notes) {
            this.itemId = itemId != null ? itemId : "sii-" + UUID.randomUUID().toString().substring(0, 8);
            this.productId = productId != null ? productId : "";
            this.productBrandName = productBrandName != null ? productBrandName : "";
            this.poItemId = poItemId;
            this.grnLineId = grnLineId;
            this.batchNumber = batchNumber != null ? batchNumber : "";
            this.billedQuantity = Math.max(1, billedQuantity);
            this.receivedQuantity = Math.max(0, receivedQuantity);
            this.orderedQuantity = Math.max(0, orderedQuantity);
            this.billedUnitPrice = billedUnitPrice != null ? billedUnitPrice.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.poUnitPrice = poUnitPrice != null ? poUnitPrice.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.lineTaxPercentage = lineTaxPercentage != null ? lineTaxPercentage.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.lineDiscountPercentage = lineDiscountPercentage != null ? lineDiscountPercentage.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            if (lineSubtotal != null && lineTotal != null) {
                this.lineSubtotal = lineSubtotal.setScale(4, RoundingMode.HALF_UP);
                this.lineTotal = lineTotal.setScale(4, RoundingMode.HALF_UP);
            } else {
                BigDecimal discFactor = BigDecimal.ONE.subtract(this.lineDiscountPercentage.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
                BigDecimal netPrice = this.billedUnitPrice.multiply(discFactor).setScale(4, RoundingMode.HALF_UP);
                BigDecimal sub = netPrice.multiply(BigDecimal.valueOf(this.billedQuantity)).setScale(4, RoundingMode.HALF_UP);
                BigDecimal tax = sub.multiply(this.lineTaxPercentage.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)).setScale(4, RoundingMode.HALF_UP);
                this.lineSubtotal = sub;
                this.lineTotal = sub.add(tax).setScale(4, RoundingMode.HALF_UP);
            }

            this.matchStatus = matchStatus != null ? matchStatus : "MATCHED";
            this.priceVariance = priceVariance != null ? priceVariance.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.quantityVariance = quantityVariance;
            this.notes = notes != null ? notes : "";
        }
    }

    public static final class SupplierInvoiceDto {
        public final String invoiceId;
        public final String invoiceNumber;
        public final String tenantId;
        public final String branchId;
        public final String supplierId;
        public final String supplierName;
        public final String purchaseOrderId;
        public final String purchaseOrderNumber;
        public final String goodsReceiptId;
        public final String goodsReceiptNumber;
        public final LocalDate invoiceDate;
        public final LocalDate dueDate;
        public final String currency;
        public final BigDecimal subtotalAmount;
        public final BigDecimal taxAmount;
        public final BigDecimal discountAmount;
        public final BigDecimal totalAmount;
        public String matchingStatus;
        public boolean hasDiscrepancy;
        public List<String> discrepancyReasons;
        public BigDecimal priceVarianceAmount;
        public int quantityVarianceUnits;
        public BigDecimal totalVarianceAmount;
        public final BigDecimal toleratedPriceVariancePercentage;
        public final BigDecimal toleratedAmountVarianceBdt;
        public String exceptionApprovedBy;
        public Instant exceptionApprovedAt;
        public String exceptionJustification;
        public String rejectionReason;
        public String rejectedBy;
        public Instant rejectedAt;
        public Instant postedToApAt;
        public String apLedgerEntryId;
        public final String proofAttachmentReference;
        public final String notes;
        public boolean isActive;
        public final List<SupplierInvoiceItemDto> items;
        public final Instant createdAt;

        public SupplierInvoiceDto(
                String invoiceId,
                String invoiceNumber,
                String tenantId,
                String branchId,
                String supplierId,
                String supplierName,
                String purchaseOrderId,
                String purchaseOrderNumber,
                String goodsReceiptId,
                String goodsReceiptNumber,
                LocalDate invoiceDate,
                LocalDate dueDate,
                String currency,
                BigDecimal subtotalAmount,
                BigDecimal taxAmount,
                BigDecimal discountAmount,
                BigDecimal totalAmount,
                String matchingStatus,
                boolean hasDiscrepancy,
                List<String> discrepancyReasons,
                BigDecimal priceVarianceAmount,
                int quantityVarianceUnits,
                BigDecimal totalVarianceAmount,
                BigDecimal toleratedPriceVariancePercentage,
                BigDecimal toleratedAmountVarianceBdt,
                String exceptionApprovedBy,
                Instant exceptionApprovedAt,
                String exceptionJustification,
                String rejectionReason,
                String rejectedBy,
                Instant rejectedAt,
                Instant postedToApAt,
                String apLedgerEntryId,
                String proofAttachmentReference,
                String notes,
                boolean isActive,
                List<SupplierInvoiceItemDto> items,
                Instant createdAt) {
            this.invoiceId = invoiceId;
            this.invoiceNumber = invoiceNumber;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.supplierId = supplierId;
            this.supplierName = supplierName;
            this.purchaseOrderId = purchaseOrderId;
            this.purchaseOrderNumber = purchaseOrderNumber;
            this.goodsReceiptId = goodsReceiptId;
            this.goodsReceiptNumber = goodsReceiptNumber;
            this.invoiceDate = invoiceDate;
            this.dueDate = dueDate;
            this.currency = currency != null ? currency : "BDT";
            this.subtotalAmount = subtotalAmount.setScale(4, RoundingMode.HALF_UP);
            this.taxAmount = taxAmount.setScale(4, RoundingMode.HALF_UP);
            this.discountAmount = discountAmount.setScale(4, RoundingMode.HALF_UP);
            this.totalAmount = totalAmount.setScale(4, RoundingMode.HALF_UP);
            this.matchingStatus = matchingStatus != null ? matchingStatus : "DRAFT";
            this.hasDiscrepancy = hasDiscrepancy;
            this.discrepancyReasons = discrepancyReasons != null ? new ArrayList<>(discrepancyReasons) : new ArrayList<>();
            this.priceVarianceAmount = priceVarianceAmount != null ? priceVarianceAmount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.quantityVarianceUnits = quantityVarianceUnits;
            this.totalVarianceAmount = totalVarianceAmount != null ? totalVarianceAmount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.toleratedPriceVariancePercentage = toleratedPriceVariancePercentage != null ? toleratedPriceVariancePercentage.setScale(2, RoundingMode.HALF_UP) : new BigDecimal("1.50");
            this.toleratedAmountVarianceBdt = toleratedAmountVarianceBdt != null ? toleratedAmountVarianceBdt.setScale(4, RoundingMode.HALF_UP) : new BigDecimal("50.0000");
            this.exceptionApprovedBy = exceptionApprovedBy;
            this.exceptionApprovedAt = exceptionApprovedAt;
            this.exceptionJustification = exceptionJustification != null ? exceptionJustification : "";
            this.rejectionReason = rejectionReason != null ? rejectionReason : "";
            this.rejectedBy = rejectedBy;
            this.rejectedAt = rejectedAt;
            this.postedToApAt = postedToApAt;
            this.apLedgerEntryId = apLedgerEntryId;
            this.proofAttachmentReference = proofAttachmentReference != null ? proofAttachmentReference : "";
            this.notes = notes != null ? notes : "";
            this.isActive = isActive;
            this.items = items != null ? items : new ArrayList<>();
            this.createdAt = createdAt != null ? createdAt : Instant.now();
        }
    }

    public static final class SupplierLedgerEntryDto {
        public final String postingId;
        public final String tenantId;
        public final String supplierId;
        public final String postingType;
        public final String referenceNumber;
        public final BigDecimal creditAmount;
        public final BigDecimal debitAmount;
        public final BigDecimal balanceAfter;
        public final String currency;
        public final String notes;
        public final Instant postedAt;

        public SupplierLedgerEntryDto(
                String postingId,
                String tenantId,
                String supplierId,
                String postingType,
                String referenceNumber,
                BigDecimal creditAmount,
                BigDecimal debitAmount,
                BigDecimal balanceAfter,
                String currency,
                String notes,
                Instant postedAt) {
            this.postingId = postingId;
            this.tenantId = tenantId;
            this.supplierId = supplierId;
            this.postingType = postingType;
            this.referenceNumber = referenceNumber;
            this.creditAmount = creditAmount.setScale(4, RoundingMode.HALF_UP);
            this.debitAmount = debitAmount.setScale(4, RoundingMode.HALF_UP);
            this.balanceAfter = balanceAfter.setScale(4, RoundingMode.HALF_UP);
            this.currency = currency != null ? currency : "BDT";
            this.notes = notes != null ? notes : "";
            this.postedAt = postedAt != null ? postedAt : Instant.now();
        }
    }

    private void checkProcurementRole() {
        if ("CASHIER".equals(userRole) || !ALLOWED_PROCUREMENT_ROLES.contains(userRole)) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Staff role " + userRole + " is denied access to supplier invoice matching.");
        }
    }

    private void checkApproverRole() {
        if (!ALLOWED_APPROVER_ROLES.contains(userRole)) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Only Managers and Owners can approve discrepancy exceptions.");
        }
    }

    public SupplierInvoiceDto createSupplierInvoice(
            String supplierId,
            String supplierName,
            String invoiceNumber,
            LocalDate invoiceDate,
            LocalDate dueDate,
            String purchaseOrderId,
            String goodsReceiptId,
            String currency,
            BigDecimal toleratedPriceVariancePercentage,
            BigDecimal toleratedAmountVarianceBdt,
            String proofAttachmentReference,
            String notes,
            List<SupplierInvoiceItemDto> items) {
        checkProcurementRole();

        if (supplierId == null || supplierId.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_SUPPLIER: Supplier ID is required");
        }
        if (invoiceNumber == null || invoiceNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_INVOICE_NUMBER: Supplier invoice number is required");
        }
        if (invoiceDate == null) {
            throw new IllegalArgumentException("ERR_MISSING_INVOICE_DATE: Invoice date is required");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("ERR_EMPTY_INVOICE: Invoice must contain at least one line item");
        }

        // Duplicate invoice detection
        for (SupplierInvoiceDto existing : invoiceStore.values()) {
            if (existing.tenantId.equals(tenantId) && existing.supplierId.equals(supplierId) && existing.invoiceNumber.equalsIgnoreCase(invoiceNumber.trim())) {
                throw new IllegalStateException("ERR_DUPLICATE_INVOICE: Invoice " + invoiceNumber + " already exists for supplier " + supplierName);
            }
        }

        // PO and GRN resolution
        PosPurchaseOrderClient.PurchaseOrderDto po = null;
        if (purchaseOrderId != null && !purchaseOrderId.trim().isEmpty() && poClient != null) {
            po = poClient.getPurchaseOrder(purchaseOrderId.trim());
            if (!po.tenantId.equals(tenantId)) {
                throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant PO access forbidden");
            }
        }

        PosGoodsReceiptClient.GoodsReceiptDto grn = null;
        if (goodsReceiptId != null && !goodsReceiptId.trim().isEmpty() && grnClient != null) {
            grn = grnClient.getGrn(goodsReceiptId.trim());
            if (!grn.tenantId.equals(tenantId)) {
                throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant GRN access forbidden");
            }
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;

        for (SupplierInvoiceItemDto item : items) {
            if (item.productId == null || item.productId.trim().isEmpty()) {
                throw new IllegalArgumentException("ERR_MISSING_PRODUCT: Product ID is required on each line");
            }
            if (item.billedQuantity <= 0) {
                throw new IllegalArgumentException("ERR_INVALID_QUANTITY: Billed quantity must be positive");
            }
            if (item.billedUnitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("ERR_INVALID_UNIT_PRICE: Billed unit price must be positive");
            }

            subtotal = subtotal.add(item.lineSubtotal);
            total = total.add(item.lineTotal);
        }

        String invoiceId = "si-" + UUID.randomUUID().toString().substring(0, 12);

        SupplierInvoiceDto invoice = new SupplierInvoiceDto(
                invoiceId,
                invoiceNumber.trim(),
                tenantId,
                branchId,
                supplierId,
                supplierName != null ? supplierName : "",
                purchaseOrderId,
                po != null ? po.poNumber : "",
                goodsReceiptId,
                grn != null ? grn.grnNumber : "",
                invoiceDate,
                dueDate,
                currency != null ? currency : "BDT",
                subtotal,
                tax,
                discount,
                total,
                "DRAFT",
                false,
                new ArrayList<>(),
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                toleratedPriceVariancePercentage != null ? toleratedPriceVariancePercentage : new BigDecimal("1.50"),
                toleratedAmountVarianceBdt != null ? toleratedAmountVarianceBdt : new BigDecimal("50.0000"),
                null, null, "", "", null, null, null, null,
                proofAttachmentReference,
                notes,
                true,
                items,
                Instant.now()
        );

        invoiceStore.put(invoiceId, invoice);

        // Execute automated 3-way match
        return executeThreeWayMatch(invoiceId);
    }

    public SupplierInvoiceDto executeThreeWayMatch(String invoiceId) {
        checkProcurementRole();

        SupplierInvoiceDto invoice = invoiceStore.get(invoiceId);
        if (invoice == null) {
            throw new NoSuchElementException("ERR_INVOICE_NOT_FOUND: Supplier invoice " + invoiceId + " not found");
        }
        if (!invoice.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant invoice matching forbidden");
        }
        if ("POSTED_TO_AP".equals(invoice.matchingStatus) || "REJECTED".equals(invoice.matchingStatus)) {
            throw new IllegalStateException("ERR_INVOICE_IMMUTABLE: Cannot match invoice in " + invoice.matchingStatus + " status");
        }

        PosPurchaseOrderClient.PurchaseOrderDto po = null;
        if (invoice.purchaseOrderId != null && !invoice.purchaseOrderId.trim().isEmpty() && poClient != null) {
            po = poClient.getPurchaseOrder(invoice.purchaseOrderId);
        }

        PosGoodsReceiptClient.GoodsReceiptDto grn = null;
        if (invoice.goodsReceiptId != null && !invoice.goodsReceiptId.trim().isEmpty() && grnClient != null) {
            grn = grnClient.getGrn(invoice.goodsReceiptId);
        }

        List<String> discrepancies = new ArrayList<>();
        BigDecimal totalPriceVariance = BigDecimal.ZERO;
        int totalQtyVariance = 0;
        boolean hasBlockingDiscrepancy = false;

        BigDecimal priceTolerancePct = invoice.toleratedPriceVariancePercentage;
        BigDecimal amountToleranceBdt = invoice.toleratedAmountVarianceBdt;

        for (SupplierInvoiceItemDto item : invoice.items) {
            boolean itemHasDiscrepancy = false;

            // 1. PO Price Verification
            if (po != null) {
                PosPurchaseOrderClient.PurchaseOrderItemDto poItem = null;
                for (PosPurchaseOrderClient.PurchaseOrderItemDto pi : po.items) {
                    if (item.poItemId != null && item.poItemId.equals(pi.itemId)) {
                        poItem = pi;
                        break;
                    } else if (item.productId.equals(pi.productId)) {
                        poItem = pi;
                        break;
                    }
                }

                if (poItem != null) {
                    item.poUnitPrice = poItem.unitCostPrice;
                    item.orderedQuantity = poItem.orderedQuantity;
                    BigDecimal priceDiff = item.billedUnitPrice.subtract(poItem.unitCostPrice);
                    item.priceVariance = priceDiff;
                    totalPriceVariance = totalPriceVariance.add(priceDiff.multiply(BigDecimal.valueOf(item.billedQuantity)));

                    if (poItem.unitCostPrice.compareTo(BigDecimal.ZERO) > 0 && priceDiff.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal pctDiff = priceDiff.divide(poItem.unitCostPrice, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
                        if (pctDiff.compareTo(priceTolerancePct) > 0 && priceDiff.compareTo(BigDecimal.ONE) > 0) {
                            item.matchStatus = "PRICE_VARIANCE";
                            itemHasDiscrepancy = true;
                            hasBlockingDiscrepancy = true;
                            String msg = "PRICE_VARIANCE: " + item.productBrandName + " billed ৳" + item.billedUnitPrice + " exceeds PO price ৳" + poItem.unitCostPrice + " by " + pctDiff + "%";
                            if (!discrepancies.contains(msg)) {
                                discrepancies.add(msg);
                            }
                        }
                    }
                }
            }

            // 2. GRN Physical Quantity Verification
            if (grn != null) {
                PosGoodsReceiptClient.GoodsReceiptLineDto grnLine = null;
                for (PosGoodsReceiptClient.GoodsReceiptLineDto gl : grn.lines) {
                    if (item.grnLineId != null && item.grnLineId.equals(gl.lineId)) {
                        grnLine = gl;
                        break;
                    } else if (item.productId.equals(gl.productId)) {
                        grnLine = gl;
                        break;
                    }
                }

                if (grnLine != null) {
                    item.receivedQuantity = grnLine.receivedQuantityBaseUnits;
                    int qtyDiff = item.billedQuantity - grnLine.receivedQuantityBaseUnits;
                    item.quantityVariance = qtyDiff;

                    if (qtyDiff > 0) {
                        // Physical Over-billing!
                        totalQtyVariance += qtyDiff;
                        item.matchStatus = "QUANTITY_OVER_BILLING";
                        itemHasDiscrepancy = true;
                        hasBlockingDiscrepancy = true;
                        String msg = "QUANTITY_OVER_BILLING: " + item.productBrandName + " billed " + item.billedQuantity + " units exceeds GRN received " + grnLine.receivedQuantityBaseUnits + " units";
                        if (!discrepancies.contains(msg)) {
                            discrepancies.add(msg);
                        }
                    } else if (qtyDiff < 0) {
                        item.matchStatus = "QUANTITY_UNDER_BILLING";
                    }
                } else {
                    item.matchStatus = "UNMATCHED_ITEM";
                    itemHasDiscrepancy = true;
                    hasBlockingDiscrepancy = true;
                    String msg = "UNMATCHED_ITEM: " + item.productBrandName + " billed but not found in GRN delivery";
                    if (!discrepancies.contains(msg)) {
                        discrepancies.add(msg);
                    }
                }
            }

            if (!itemHasDiscrepancy) {
                item.matchStatus = "MATCHED";
            }
        }

        if (totalPriceVariance.abs().compareTo(amountToleranceBdt) > 0 && totalPriceVariance.compareTo(BigDecimal.ZERO) > 0) {
            hasBlockingDiscrepancy = true;
            String msg = "AMOUNT_VARIANCE: Net invoice variance ৳" + totalPriceVariance + " exceeds tolerance ৳" + amountToleranceBdt;
            if (!discrepancies.contains(msg)) {
                discrepancies.add(msg);
            }
        }

        invoice.hasDiscrepancy = hasBlockingDiscrepancy;
        invoice.discrepancyReasons = discrepancies;
        invoice.priceVarianceAmount = totalPriceVariance;
        invoice.quantityVarianceUnits = totalQtyVariance;
        invoice.totalVarianceAmount = totalPriceVariance;

        if (hasBlockingDiscrepancy) {
            invoice.matchingStatus = "DISCREPANCY_QUEUE";
        } else {
            invoice.matchingStatus = "MATCHED";
        }

        return invoice;
    }

    public SupplierInvoiceDto approveException(String invoiceId, String justification, String notes) {
        checkApproverRole();

        SupplierInvoiceDto invoice = invoiceStore.get(invoiceId);
        if (invoice == null) {
            throw new NoSuchElementException("ERR_INVOICE_NOT_FOUND: Supplier invoice " + invoiceId + " not found");
        }
        if (!invoice.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant exception approval forbidden");
        }
        if (!"DISCREPANCY_QUEUE".equals(invoice.matchingStatus)) {
            throw new IllegalStateException("ERR_INVALID_INVOICE_STATUS: Invoice is not in discrepancy queue (" + invoice.matchingStatus + ")");
        }
        if (justification == null || justification.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_JUSTIFICATION: Documented managerial justification is required");
        }

        invoice.matchingStatus = "APPROVED_EXCEPTION";
        invoice.exceptionApprovedBy = userId;
        invoice.exceptionApprovedAt = Instant.now();
        invoice.exceptionJustification = justification.trim();
        return invoice;
    }

    public SupplierInvoiceDto rejectInvoice(String invoiceId, String rejectionReason, String notes) {
        checkApproverRole();

        SupplierInvoiceDto invoice = invoiceStore.get(invoiceId);
        if (invoice == null) {
            throw new NoSuchElementException("ERR_INVOICE_NOT_FOUND: Supplier invoice " + invoiceId + " not found");
        }
        if (!invoice.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant invoice rejection forbidden");
        }
        if ("POSTED_TO_AP".equals(invoice.matchingStatus)) {
            throw new IllegalStateException("ERR_INVOICE_ALREADY_POSTED: Cannot reject invoice already posted to AP");
        }
        if (rejectionReason == null || rejectionReason.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_REJECTION_REASON: Documented rejection reason is required");
        }

        invoice.matchingStatus = "REJECTED";
        invoice.rejectedBy = userId;
        invoice.rejectedAt = Instant.now();
        invoice.rejectionReason = rejectionReason.trim();
        return invoice;
    }

    public SupplierInvoiceDto postInvoiceToAp(String invoiceId) {
        checkProcurementRole();

        SupplierInvoiceDto invoice = invoiceStore.get(invoiceId);
        if (invoice == null) {
            throw new NoSuchElementException("ERR_INVOICE_NOT_FOUND: Supplier invoice " + invoiceId + " not found");
        }
        if (!invoice.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant AP posting forbidden");
        }
        if ("POSTED_TO_AP".equals(invoice.matchingStatus)) {
            throw new IllegalStateException("ERR_ALREADY_POSTED: Invoice is already posted to Accounts Payable");
        }
        if ("REJECTED".equals(invoice.matchingStatus)) {
            throw new IllegalStateException("ERR_INVOICE_REJECTED: Cannot post rejected invoice to Accounts Payable");
        }
        if (!"MATCHED".equals(invoice.matchingStatus) && !"APPROVED_EXCEPTION".equals(invoice.matchingStatus)) {
            throw new IllegalStateException("ERR_INVOICE_NOT_MATCHED: Cannot post invoice in " + invoice.matchingStatus + " status. Must be MATCHED or APPROVED_EXCEPTION.");
        }

        // Calculate running balance for supplier
        BigDecimal lastBalance = BigDecimal.ZERO;
        for (SupplierLedgerEntryDto entry : ledgerStore.values()) {
            if (entry.tenantId.equals(tenantId) && entry.supplierId.equals(invoice.supplierId)) {
                if (entry.balanceAfter.compareTo(lastBalance) > 0) {
                    lastBalance = entry.balanceAfter;
                }
            }
        }
        BigDecimal newBalance = lastBalance.add(invoice.totalAmount);

        String postingId = "sup-post-" + UUID.randomUUID().toString().substring(0, 12);
        SupplierLedgerEntryDto entry = new SupplierLedgerEntryDto(
                postingId,
                tenantId,
                invoice.supplierId,
                "GRN_INVOICE",
                invoice.invoiceNumber,
                invoice.totalAmount,
                BigDecimal.ZERO,
                newBalance,
                invoice.currency,
                "Commercial Invoice Matched: " + invoice.invoiceNumber,
                Instant.now()
        );
        ledgerStore.put(postingId, entry);

        invoice.matchingStatus = "POSTED_TO_AP";
        invoice.postedToApAt = Instant.now();
        invoice.apLedgerEntryId = postingId;

        // If GRN linked, update AP invoice amount
        if (grnClient != null && invoice.goodsReceiptId != null && !invoice.goodsReceiptId.trim().isEmpty()) {
            PosGoodsReceiptClient.GoodsReceiptDto grn = grnClient.getGrn(invoice.goodsReceiptId);
            grn.apPostingStatus = "POSTED";
            grn.apInvoiceAmount = invoice.totalAmount;
        }

        return invoice;
    }

    public List<SupplierInvoiceDto> getExceptionQueue(String branchId) {
        List<SupplierInvoiceDto> queue = new ArrayList<>();
        for (SupplierInvoiceDto inv : invoiceStore.values()) {
            if (!inv.tenantId.equals(tenantId)) {
                continue;
            }
            if (branchId != null && !branchId.equals(inv.branchId)) {
                continue;
            }
            if ("DISCREPANCY_QUEUE".equals(inv.matchingStatus)) {
                queue.add(inv);
            }
        }
        queue.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return queue;
    }

    public SupplierInvoiceDto getSupplierInvoice(String invoiceId) {
        SupplierInvoiceDto invoice = invoiceStore.get(invoiceId);
        if (invoice == null) {
            throw new NoSuchElementException("ERR_INVOICE_NOT_FOUND: Supplier invoice " + invoiceId + " not found");
        }
        if (!invoice.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant invoice access forbidden");
        }
        return invoice;
    }

    public List<SupplierInvoiceDto> listSupplierInvoices(String supplierId, String matchingStatus) {
        List<SupplierInvoiceDto> list = new ArrayList<>();
        for (SupplierInvoiceDto inv : invoiceStore.values()) {
            if (!inv.tenantId.equals(tenantId)) {
                continue;
            }
            if (supplierId != null && !supplierId.equals(inv.supplierId)) {
                continue;
            }
            if (matchingStatus != null && !matchingStatus.equalsIgnoreCase(inv.matchingStatus)) {
                continue;
            }
            list.add(inv);
        }
        list.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return list;
    }
}

