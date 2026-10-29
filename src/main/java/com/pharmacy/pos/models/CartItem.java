package com.pharmacy.pos.models;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Line item in the POS Cashier Cart.
 * Strict financial precision: uses BigDecimal exclusively (ADR-0001 & ADR-0023).
 */
public class CartItem {
    private final String medicineId;
    private final String brandName;
    private final String genericName;
    private final String packagingUnit; // BOX, STRIP, TABLET
    private final int quantity;
    private final BigDecimal unitPrice;
    private final String batchNumber;
    private final LocalDate expiryDate;
    private final BigDecimal vatRate; // e.g. 0.05 for 5% VAT

    public CartItem(String medicineId, String brandName, String genericName, String packagingUnit,
                    int quantity, BigDecimal unitPrice, String batchNumber, LocalDate expiryDate, BigDecimal vatRate) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("CartItem quantity must be strictly positive (>0). Got: " + quantity);
        }
        Objects.requireNonNull(unitPrice, "unitPrice cannot be null");
        if (unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("CartItem unitPrice cannot be negative. Got: " + unitPrice);
        }
        Objects.requireNonNull(batchNumber, "batchNumber cannot be null");
        if (batchNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("batchNumber cannot be empty");
        }
        Objects.requireNonNull(expiryDate, "expiryDate cannot be null");

        this.medicineId = Objects.requireNonNull(medicineId, "medicineId cannot be null");
        this.brandName = Objects.requireNonNull(brandName, "brandName cannot be null");
        this.genericName = Objects.requireNonNull(genericName, "genericName cannot be null");
        this.packagingUnit = Objects.requireNonNull(packagingUnit, "packagingUnit cannot be null");
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.batchNumber = batchNumber.trim();
        this.expiryDate = expiryDate;
        this.vatRate = vatRate != null ? vatRate : BigDecimal.ZERO;
    }

    public BigDecimal getLineSubtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getLineVat() {
        return getLineSubtotal().multiply(vatRate).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getLineTotal() {
        return getLineSubtotal().add(getLineVat());
    }

    public String getMedicineId() { return medicineId; }
    public String getBrandName() { return brandName; }
    public String getGenericName() { return genericName; }
    public String getPackagingUnit() { return packagingUnit; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public String getBatchNumber() { return batchNumber; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public BigDecimal getVatRate() { return vatRate; }
}

