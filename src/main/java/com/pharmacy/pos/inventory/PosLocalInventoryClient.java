package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * PosLocalInventoryClient
 * Architecture Decision: ADR-0156 (P156).
 * Phase 08: Milestone M04 / M05 - Specialized Item & Consumable Editors.
 *
 * Client-side validation and branch inventory engine for JavaFX Desktop POS.
 * Coordinates branch-specific SKUs, custom store selling prices,
 * reorder alert thresholds, physical shelf coordinates, and store visibility.
 */
public class PosLocalInventoryClient {

    private static final Pattern SKU_PATTERN = Pattern.compile("^[A-Za-z0-9\\-_./]*$");

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class InventoryValidationResult {
        public final boolean isValid;
        public final String errorCode;
        public final String errorMessage;
        public final BigDecimal effectiveSellingPrice;
        public final boolean isPriceOverridden;

        private InventoryValidationResult(
                boolean isValid,
                String errorCode,
                String errorMessage,
                BigDecimal effectiveSellingPrice,
                boolean isPriceOverridden) {
            this.isValid = isValid;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.effectiveSellingPrice = effectiveSellingPrice;
            this.isPriceOverridden = isPriceOverridden;
        }

        public static InventoryValidationResult success(BigDecimal effectivePrice, boolean isPriceOverridden) {
            return new InventoryValidationResult(true, null, null, effectivePrice, isPriceOverridden);
        }

        public static InventoryValidationResult failure(String errorCode, String errorMessage) {
            return new InventoryValidationResult(false, errorCode, errorMessage, BigDecimal.ZERO, false);
        }
    }

    /**
     * Validates branch-specific inventory parameters and resolves effective selling price.
     */
    public InventoryValidationResult validateLocalInventory(
            String localSku,
            BigDecimal localSellingPrice,
            BigDecimal masterMrp,
            int reorderLevel,
            int minOrderQty,
            Integer maxCapacity,
            String shelfLocation) {

        if (localSku != null && !localSku.trim().isEmpty()) {
            String trimmed = localSku.trim();
            if (trimmed.length() > 64) {
                return InventoryValidationResult.failure(
                        "ERR_INVALID_SKU_LENGTH",
                        "Local SKU cannot exceed 64 characters."
                );
            }
            if (!SKU_PATTERN.matcher(trimmed).matches()) {
                return InventoryValidationResult.failure(
                        "ERR_INVALID_SKU_FORMAT",
                        "Local SKU contains invalid characters."
                );
            }
        }

        if (localSellingPrice != null && localSellingPrice.compareTo(new BigDecimal("0.01")) < 0) {
            return InventoryValidationResult.failure(
                    "ERR_INVALID_SELLING_PRICE",
                    "Local selling price must be at least 0.01 BDT."
            );
        }

        if (reorderLevel < 0 || reorderLevel > 100000) {
            return InventoryValidationResult.failure(
                    "ERR_INVALID_REORDER_LEVEL",
                    "Reorder level must be between 0 and 100,000."
            );
        }

        if (minOrderQty < 1 || minOrderQty > 50000) {
            return InventoryValidationResult.failure(
                    "ERR_INVALID_MIN_ORDER_QTY",
                    "Minimum order quantity must be between 1 and 50,000."
            );
        }

        if (maxCapacity != null && maxCapacity < minOrderQty) {
            return InventoryValidationResult.failure(
                    "ERR_CAPACITY_BELOW_MIN_ORDER",
                    "Maximum capacity (" + maxCapacity + ") cannot be less than minimum order quantity (" + minOrderQty + ")."
            );
        }

        if (shelfLocation != null && shelfLocation.trim().length() > 128) {
            return InventoryValidationResult.failure(
                    "ERR_LOCATION_TOO_LONG",
                    "Shelf location coordinates cannot exceed 128 characters."
            );
        }

        boolean overridden = localSellingPrice != null;
        BigDecimal effectivePrice = overridden ? localSellingPrice : (masterMrp != null ? masterMrp : BigDecimal.ZERO);

        return InventoryValidationResult.success(effectivePrice, overridden);
    }

    /**
     * Checks if stock level triggers a low-stock reorder warning.
     */
    public boolean isLowStock(int currentStock, int reorderLevel) {
        return currentStock <= reorderLevel;
    }

    /**
     * Cashier role is restricted from editing local inventory configurations.
     */
    public boolean canMutateInventory(String role) {
        if (role == null) return false;
        String normalized = role.toUpperCase().trim();
        return !"CASHIER".equals(normalized);
    }
}
