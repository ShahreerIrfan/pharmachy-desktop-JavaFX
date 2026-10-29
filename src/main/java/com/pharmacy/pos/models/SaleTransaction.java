package com.pharmacy.pos.models;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable finalized POS sale transaction.
 * Supports offline storage with explicit watermark audit requirement (ADR-0003 & ADR-0023).
 */
public class SaleTransaction {
    public static final String OFFLINE_WATERMARK_EN = "*** OFFLINE RECEIPT - PENDING CLOUD CONFIRMATION ***";
    public static final String OFFLINE_WATERMARK_BN = "*** অফলাইন রসিদ - ক্লাউড কনফার্মেশন অপেক্ষমান ***";

    private final String transactionId;
    private final String invoiceNumber;
    private final String tenantId;
    private final String branchId;
    private final String cashierId;
    private final List<CartItem> items;
    private final BigDecimal discountAmount;
    private final boolean isOfflineSyncPending;
    private final Instant createdAt;

    public SaleTransaction(String transactionId, String invoiceNumber, String tenantId, String branchId,
                           String cashierId, List<CartItem> items, BigDecimal discountAmount,
                           boolean isOfflineSyncPending, Instant createdAt) {
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId cannot be null");
        this.invoiceNumber = Objects.requireNonNull(invoiceNumber, "invoiceNumber cannot be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId cannot be null");
        this.branchId = Objects.requireNonNull(branchId, "branchId cannot be null");
        this.cashierId = Objects.requireNonNull(cashierId, "cashierId cannot be null");
        Objects.requireNonNull(items, "items list cannot be null");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("SaleTransaction must contain at least one CartItem.");
        }
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.discountAmount = discountAmount != null ? discountAmount : BigDecimal.ZERO;
        this.isOfflineSyncPending = isOfflineSyncPending;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public BigDecimal getSubtotal() {
        BigDecimal sum = BigDecimal.ZERO;
        for (CartItem item : items) {
            sum = sum.add(item.getLineSubtotal());
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getTotalVat() {
        BigDecimal sum = BigDecimal.ZERO;
        for (CartItem item : items) {
            sum = sum.add(item.getLineVat());
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getGrandTotal() {
        BigDecimal total = getSubtotal().add(getTotalVat()).subtract(discountAmount);
        return total.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : total.setScale(2, RoundingMode.HALF_UP);
    }

    public String generateReceiptText() {
        StringBuilder sb = new StringBuilder();

        if (isOfflineSyncPending) {
            sb.append("========================================\n");
            sb.append(OFFLINE_WATERMARK_EN).append("\n");
            sb.append(OFFLINE_WATERMARK_BN).append("\n");
            sb.append("========================================\n");
        }

        sb.append("INVOICE: ").append(invoiceNumber).append("\n");
        sb.append("BRANCH: ").append(branchId).append(" | CASHIER: ").append(cashierId).append("\n");
        sb.append("DATE: ").append(createdAt.toString()).append("\n");
        sb.append("----------------------------------------\n");

        for (CartItem item : items) {
            sb.append(String.format("%-18s %2d %-5s @%7s = %7s\n",
                    item.getBrandName(),
                    item.getQuantity(),
                    item.getPackagingUnit(),
                    item.getUnitPrice().toPlainString(),
                    item.getLineTotal().toPlainString()));
        }

        sb.append("----------------------------------------\n");
        sb.append(String.format("Subtotal:   BDT %10s\n", getSubtotal().toPlainString()));
        sb.append(String.format("VAT:        BDT %10s\n", getTotalVat().toPlainString()));
        if (discountAmount.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("Discount:  -BDT %10s\n", discountAmount.toPlainString()));
        }
        sb.append(String.format("TOTAL:      BDT %10s\n", getGrandTotal().toPlainString()));
        sb.append("========================================\n");

        if (isOfflineSyncPending) {
            sb.append(OFFLINE_WATERMARK_EN).append("\n");
            sb.append("========================================\n");
        }

        return sb.toString();
    }

    public String getTransactionId() { return transactionId; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public String getTenantId() { return tenantId; }
    public String getBranchId() { return branchId; }
    public String getCashierId() { return cashierId; }
    public List<CartItem> getItems() { return items; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public boolean isOfflineSyncPending() { return isOfflineSyncPending; }
    public Instant getCreatedAt() { return createdAt; }
}

