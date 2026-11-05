package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * PosVendorPriceListClient
 * Architecture Decision: ADR-0183 (P183).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 *
 * Desktop POS client governing vendor product price lists, SKU quotations,
 * trade discounts, MOQ bounds, DGDA price-ceiling guard, and best-price PO allocation.
 */
public class PosVendorPriceListClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, VendorPriceEntryDto> priceEntryStore;

    private static final Set<String> ALLOWED_PROCUREMENT_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "PROCUREMENT_OFFICER", "AUDITOR"
    );

    public PosVendorPriceListClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, VendorPriceEntryDto> priceEntryStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.priceEntryStore = priceEntryStore != null ? priceEntryStore : new HashMap<>();
    }

    public static final class VendorPriceEntryDto {
        public final String priceEntryId;
        public final String tenantId;
        public final String supplierId;
        public String supplierName;
        public final String productId;
        public String productName;
        public String supplierSkuCode;
        public String packageType;
        public int packageSize;
        public BigDecimal unitCostPrice;
        public BigDecimal packageCostPrice;
        public BigDecimal mrpUnit;
        public BigDecimal discountPercentage;
        public BigDecimal netUnitCost;
        public LocalDate effectiveFrom;
        public LocalDate effectiveTo;
        public int minimumOrderQuantity;
        public int freeGoodsThreshold;
        public int freeGoodsBonusQuantity;
        public boolean isContracted;
        public boolean isActive;
        public String notes;

        public VendorPriceEntryDto(
                String priceEntryId,
                String tenantId,
                String supplierId,
                String supplierName,
                String productId,
                String productName,
                String supplierSkuCode,
                String packageType,
                int packageSize,
                BigDecimal unitCostPrice,
                BigDecimal mrpUnit,
                BigDecimal discountPercentage,
                LocalDate effectiveFrom,
                LocalDate effectiveTo,
                int minimumOrderQuantity,
                int freeGoodsThreshold,
                int freeGoodsBonusQuantity,
                boolean isContracted,
                boolean isActive,
                String notes) {
            this.priceEntryId = priceEntryId;
            this.tenantId = tenantId;
            this.supplierId = supplierId;
            this.supplierName = supplierName != null ? supplierName : "";
            this.productId = productId;
            this.productName = productName != null ? productName : "";
            this.supplierSkuCode = supplierSkuCode != null ? supplierSkuCode : "";
            this.packageType = packageType != null ? packageType : "BOX";
            this.packageSize = Math.max(1, packageSize);
            this.unitCostPrice = unitCostPrice != null ? unitCostPrice.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.packageCostPrice = this.unitCostPrice.multiply(BigDecimal.valueOf(this.packageSize)).setScale(4, RoundingMode.HALF_UP);
            this.mrpUnit = mrpUnit != null ? mrpUnit.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.discountPercentage = discountPercentage != null ? discountPercentage.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            BigDecimal discountFactor = BigDecimal.ONE.subtract(this.discountPercentage.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            this.netUnitCost = this.unitCostPrice.multiply(discountFactor).setScale(4, RoundingMode.HALF_UP);

            this.effectiveFrom = effectiveFrom != null ? effectiveFrom : LocalDate.now();
            this.effectiveTo = effectiveTo;
            this.minimumOrderQuantity = Math.max(1, minimumOrderQuantity);
            this.freeGoodsThreshold = Math.max(0, freeGoodsThreshold);
            this.freeGoodsBonusQuantity = Math.max(0, freeGoodsBonusQuantity);
            this.isContracted = isContracted;
            this.isActive = isActive;
            this.notes = notes != null ? notes : "";
        }
    }

    public static final class BestPriceOfferDto {
        public final String selectedPriceEntryId;
        public final String supplierId;
        public final String supplierName;
        public final String productId;
        public final String productName;
        public final int demandedQuantity;
        public final BigDecimal unitCostPrice;
        public final BigDecimal discountPercentage;
        public final BigDecimal netUnitCost;
        public final BigDecimal totalNetCost;
        public final int freeGoodsBonusUnits;
        public final boolean moqSatisfied;

        public BestPriceOfferDto(
                String selectedPriceEntryId,
                String supplierId,
                String supplierName,
                String productId,
                String productName,
                int demandedQuantity,
                BigDecimal unitCostPrice,
                BigDecimal discountPercentage,
                BigDecimal netUnitCost,
                BigDecimal totalNetCost,
                int freeGoodsBonusUnits,
                boolean moqSatisfied) {
            this.selectedPriceEntryId = selectedPriceEntryId;
            this.supplierId = supplierId;
            this.supplierName = supplierName;
            this.productId = productId;
            this.productName = productName;
            this.demandedQuantity = demandedQuantity;
            this.unitCostPrice = unitCostPrice;
            this.discountPercentage = discountPercentage;
            this.netUnitCost = netUnitCost;
            this.totalNetCost = totalNetCost;
            this.freeGoodsBonusUnits = freeGoodsBonusUnits;
            this.moqSatisfied = moqSatisfied;
        }
    }

    private void checkProcurementRole() {
        if (!ALLOWED_PROCUREMENT_ROLES.contains(userRole)) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers and unauthorized roles cannot manage price lists");
        }
    }

    public VendorPriceEntryDto createPriceEntry(
            String supplierId,
            String supplierName,
            String productId,
            String productName,
            String supplierSkuCode,
            String packageType,
            int packageSize,
            BigDecimal unitCostPrice,
            BigDecimal mrpUnit,
            BigDecimal discountPercentage,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            int minimumOrderQuantity,
            int freeGoodsThreshold,
            int freeGoodsBonusQuantity,
            boolean isContracted,
            String notes) {
        checkProcurementRole();

        if (supplierId == null || supplierId.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_SUPPLIER_ID: Supplier ID cannot be empty");
        }
        if (productId == null || productId.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_PRODUCT_ID: Product ID cannot be empty");
        }
        if (unitCostPrice == null || unitCostPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("ERR_INVALID_COST_PRICE: Unit cost price must be positive");
        }
        if (mrpUnit != null && mrpUnit.compareTo(BigDecimal.ZERO) > 0 && unitCostPrice.compareTo(mrpUnit) > 0) {
            throw new IllegalArgumentException("ERR_COST_EXCEEDS_MRP: Unit cost price cannot exceed product retail price (MRP)");
        }
        if (discountPercentage != null && (discountPercentage.compareTo(BigDecimal.ZERO) < 0 || discountPercentage.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new IllegalArgumentException("ERR_INVALID_DISCOUNT_PERCENTAGE: Discount must be between 0% and 100%");
        }
        if (minimumOrderQuantity < 1) {
            throw new IllegalArgumentException("ERR_INVALID_MOQ: Minimum order quantity must be at least 1");
        }
        if (effectiveTo != null && effectiveFrom != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("ERR_INVALID_DATE_RANGE: Effective to date cannot precede effective from date");
        }

        String priceEntryId = "vpl-" + UUID.randomUUID().toString().substring(0, 12);
        VendorPriceEntryDto entry = new VendorPriceEntryDto(
                priceEntryId,
                tenantId,
                supplierId.trim(),
                supplierName != null ? supplierName.trim() : "",
                productId.trim(),
                productName != null ? productName.trim() : "",
                supplierSkuCode != null ? supplierSkuCode.trim() : "",
                packageType != null ? packageType.trim() : "BOX",
                packageSize,
                unitCostPrice,
                mrpUnit,
                discountPercentage != null ? discountPercentage : BigDecimal.ZERO,
                effectiveFrom != null ? effectiveFrom : LocalDate.now(),
                effectiveTo,
                minimumOrderQuantity,
                freeGoodsThreshold,
                freeGoodsBonusQuantity,
                isContracted,
                true,
                notes != null ? notes.trim() : ""
        );

        priceEntryStore.put(priceEntryId, entry);
        return entry;
    }

    public VendorPriceEntryDto updatePriceEntry(
            String priceEntryId,
            BigDecimal newUnitCostPrice,
            BigDecimal newDiscountPercentage,
            Integer newMoq,
            LocalDate newEffectiveTo,
            Boolean newIsActive) {
        checkProcurementRole();

        VendorPriceEntryDto existing = priceEntryStore.get(priceEntryId);
        if (existing == null) {
            throw new NoSuchElementException("ERR_PRICE_ENTRY_NOT_FOUND: Price entry " + priceEntryId + " not found");
        }
        if (!existing.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant price entry update forbidden");
        }

        BigDecimal unitCost = existing.unitCostPrice;
        if (newUnitCostPrice != null) {
            if (newUnitCostPrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("ERR_INVALID_COST_PRICE: Unit cost price must be positive");
            }
            if (existing.mrpUnit.compareTo(BigDecimal.ZERO) > 0 && newUnitCostPrice.compareTo(existing.mrpUnit) > 0) {
                throw new IllegalArgumentException("ERR_COST_EXCEEDS_MRP: Unit cost price cannot exceed product retail price (MRP)");
            }
            unitCost = newUnitCostPrice;
        }

        BigDecimal discount = existing.discountPercentage;
        if (newDiscountPercentage != null) {
            if (newDiscountPercentage.compareTo(BigDecimal.ZERO) < 0 || newDiscountPercentage.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("ERR_INVALID_DISCOUNT_PERCENTAGE: Discount must be between 0% and 100%");
            }
            discount = newDiscountPercentage;
        }

        int moq = existing.minimumOrderQuantity;
        if (newMoq != null) {
            if (newMoq < 1) {
                throw new IllegalArgumentException("ERR_INVALID_MOQ: Minimum order quantity must be at least 1");
            }
            moq = newMoq;
        }

        LocalDate effectiveTo = existing.effectiveTo;
        if (newEffectiveTo != null) {
            if (newEffectiveTo.isBefore(existing.effectiveFrom)) {
                throw new IllegalArgumentException("ERR_INVALID_DATE_RANGE: Effective to date cannot precede effective from date");
            }
            effectiveTo = newEffectiveTo;
        }

        boolean active = newIsActive != null ? newIsActive : existing.isActive;

        VendorPriceEntryDto updated = new VendorPriceEntryDto(
                existing.priceEntryId,
                existing.tenantId,
                existing.supplierId,
                existing.supplierName,
                existing.productId,
                existing.productName,
                existing.supplierSkuCode,
                existing.packageType,
                existing.packageSize,
                unitCost,
                existing.mrpUnit,
                discount,
                existing.effectiveFrom,
                effectiveTo,
                moq,
                existing.freeGoodsThreshold,
                existing.freeGoodsBonusQuantity,
                existing.isContracted,
                active,
                existing.notes
        );

        priceEntryStore.put(priceEntryId, updated);
        return updated;
    }

    public VendorPriceEntryDto archivePriceEntry(String priceEntryId) {
        checkProcurementRole();
        VendorPriceEntryDto existing = priceEntryStore.get(priceEntryId);
        if (existing == null) {
            throw new NoSuchElementException("ERR_PRICE_ENTRY_NOT_FOUND: Price entry " + priceEntryId + " not found");
        }
        if (!existing.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant price entry archival forbidden");
        }

        return updatePriceEntry(priceEntryId, null, null, null, null, false);
    }

    public VendorPriceEntryDto getPriceEntry(String priceEntryId) {
        VendorPriceEntryDto existing = priceEntryStore.get(priceEntryId);
        if (existing == null) {
            throw new NoSuchElementException("ERR_PRICE_ENTRY_NOT_FOUND: Price entry " + priceEntryId + " not found");
        }
        if (!existing.tenantId.equals(tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant price entry access forbidden");
        }
        return existing;
    }

    public List<VendorPriceEntryDto> listPriceEntries(
            String supplierId,
            String productId,
            LocalDate activeOnDate,
            Boolean isActive) {
        List<VendorPriceEntryDto> result = new ArrayList<>();
        for (VendorPriceEntryDto e : priceEntryStore.values()) {
            if (!e.tenantId.equals(tenantId)) {
                continue;
            }
            if (supplierId != null && !e.supplierId.equals(supplierId)) {
                continue;
            }
            if (productId != null && !e.productId.equals(productId)) {
                continue;
            }
            if (isActive != null && e.isActive != isActive) {
                continue;
            }
            if (activeOnDate != null) {
                if (e.effectiveFrom.isAfter(activeOnDate)) {
                    continue;
                }
                if (e.effectiveTo != null && e.effectiveTo.isBefore(activeOnDate)) {
                    continue;
                }
            }
            result.add(e);
        }
        result.sort((a, b) -> b.effectiveFrom.compareTo(a.effectiveFrom));
        return result;
    }

    public BestPriceOfferDto resolveBestPrice(String productId, int quantity, LocalDate targetDate) {
        if (productId == null || productId.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_PRODUCT_ID: Product ID cannot be empty");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("ERR_INVALID_QUANTITY: Demanded quantity must be greater than zero");
        }
        LocalDate date = targetDate != null ? targetDate : LocalDate.now();

        List<VendorPriceEntryDto> candidates = new ArrayList<>();
        for (VendorPriceEntryDto e : priceEntryStore.values()) {
            if (!e.tenantId.equals(tenantId) || !e.productId.equals(productId) || !e.isActive) {
                continue;
            }
            if (e.effectiveFrom.isAfter(date)) {
                continue;
            }
            if (e.effectiveTo != null && e.effectiveTo.isBefore(date)) {
                continue;
            }
            if (quantity >= e.minimumOrderQuantity) {
                candidates.add(e);
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        // Sort by lowest net unit cost, then highest discount
        candidates.sort((a, b) -> {
            int cmp = a.netUnitCost.compareTo(b.netUnitCost);
            if (cmp != 0) {
                return cmp;
            }
            return b.discountPercentage.compareTo(a.discountPercentage);
        });

        VendorPriceEntryDto best = candidates.get(0);
        BigDecimal totalNetCost = best.netUnitCost.multiply(BigDecimal.valueOf(quantity)).setScale(4, RoundingMode.HALF_UP);

        int bonusUnits = 0;
        if (best.freeGoodsThreshold > 0 && best.freeGoodsBonusQuantity > 0) {
            int tiers = quantity / best.freeGoodsThreshold;
            bonusUnits = tiers * best.freeGoodsBonusQuantity;
        }

        return new BestPriceOfferDto(
                best.priceEntryId,
                best.supplierId,
                best.supplierName,
                best.productId,
                best.productName,
                quantity,
                best.unitCostPrice,
                best.discountPercentage,
                best.netUnitCost,
                totalNetCost,
                bonusUnits,
                true
        );
    }
}

