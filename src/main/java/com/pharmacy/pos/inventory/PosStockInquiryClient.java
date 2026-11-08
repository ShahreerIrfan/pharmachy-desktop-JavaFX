package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * PosStockInquiryClient
 * Architecture Decision: ADR-0203 (P203).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Client for authorized tenant & branch stock inquiries.
 * Breaks down base units, available, reserved, quarantined, damaged, and expired stock.
 */
public class PosStockInquiryClient {

    public enum ExpiryCategory {
        ACTIVE("Active Commercial Stock", "সক্রিয় বাণিজ্যিক স্টক"),
        NEAR_EXPIRY("Near Expiry (<90 Days)", "আসন্ন মেয়াদোত্তীর্ণ (<৯০ দিন)"),
        CRITICAL_NEAR_EXPIRY("Critical (<30 Days)", "জরুরি মেয়াদোত্তীর্ণ (<৩০ দিন)"),
        EXPIRED("Expired Stock (Non-Sellable)", "মেয়াদোত্তীর্ণ (অ-বিক্রয়যোগ্য)"),
        QUARANTINED("Quarantined Stock", "কোয়ারেন্টাইন স্টক"),
        RECALLED("DGDA Recalled Stock", "ডিজিডিএ প্রত্যাহারকৃত স্টক");

        public final String labelEn;
        public final String labelBn;

        ExpiryCategory(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static final class BatchInquiryDto {
        public final String batchId;
        public final String batchNumber;
        public final LocalDate expiryDate;
        public final long daysUntilExpiry;
        public final ExpiryCategory expiryStatus;
        public final int quantityBaseUnits;
        public final int reservedQuantityBaseUnits;
        public final int availableQuantityBaseUnits;
        public final String storageLocation;
        public final BigDecimal costPerUnit;
        public final BigDecimal mrpUnit;

        public BatchInquiryDto(
                String batchId,
                String batchNumber,
                LocalDate expiryDate,
                long daysUntilExpiry,
                ExpiryCategory expiryStatus,
                int quantityBaseUnits,
                int reservedQuantityBaseUnits,
                int availableQuantityBaseUnits,
                String storageLocation,
                BigDecimal costPerUnit,
                BigDecimal mrpUnit
        ) {
            this.batchId = batchId;
            this.batchNumber = batchNumber;
            this.expiryDate = expiryDate;
            this.daysUntilExpiry = daysUntilExpiry;
            this.expiryStatus = expiryStatus;
            this.quantityBaseUnits = quantityBaseUnits;
            this.reservedQuantityBaseUnits = reservedQuantityBaseUnits;
            this.availableQuantityBaseUnits = availableQuantityBaseUnits;
            this.storageLocation = storageLocation;
            this.costPerUnit = costPerUnit;
            this.mrpUnit = mrpUnit;
        }
    }

    public static final class PackagingSummaryDto {
        public final int boxes;
        public final int strips;
        public final int pieces;
        public final int totalBaseUnits;
        public final String formattedText;

        public PackagingSummaryDto(int boxes, int strips, int pieces, int totalBaseUnits, String formattedText) {
            this.boxes = boxes;
            this.strips = strips;
            this.pieces = pieces;
            this.totalBaseUnits = totalBaseUnits;
            this.formattedText = formattedText;
        }
    }

    public static final class ProductStockInquiryDto {
        public final String productId;
        public final String brandName;
        public final String brandNameBn;
        public final String genericName;
        public final String dosageForm;
        public int totalQuantityBaseUnits;
        public int availableQuantityBaseUnits;
        public int reservedQuantityBaseUnits;
        public int expiredQuantityBaseUnits;
        public int quarantinedQuantityBaseUnits;
        public int nearExpiryQuantityBaseUnits;
        public BigDecimal totalCostValuationBdt;
        public BigDecimal totalMrpValuationBdt;
        public PackagingSummaryDto packagingSummary;
        public final List<BatchInquiryDto> batches = new ArrayList<>();

        public ProductStockInquiryDto(
                String productId,
                String brandName,
                String brandNameBn,
                String genericName,
                String dosageForm
        ) {
            this.productId = productId;
            this.brandName = brandName;
            this.brandNameBn = brandNameBn;
            this.genericName = genericName;
            this.dosageForm = dosageForm;
            this.totalQuantityBaseUnits = 0;
            this.availableQuantityBaseUnits = 0;
            this.reservedQuantityBaseUnits = 0;
            this.expiredQuantityBaseUnits = 0;
            this.quarantinedQuantityBaseUnits = 0;
            this.nearExpiryQuantityBaseUnits = 0;
            this.totalCostValuationBdt = BigDecimal.ZERO;
            this.totalMrpValuationBdt = BigDecimal.ZERO;
        }
    }

    public static final class StockInquiryResponseDto {
        public final String tenantId;
        public final String branchId;
        public final int productsCount;
        public final int totalAvailableUnits;
        public final List<ProductStockInquiryDto> results;

        public StockInquiryResponseDto(
                String tenantId,
                String branchId,
                int productsCount,
                int totalAvailableUnits,
                List<ProductStockInquiryDto> results
        ) {
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.productsCount = productsCount;
            this.totalAvailableUnits = totalAvailableUnits;
            this.results = results;
        }
    }

    public static final class MockBatchRecord {
        public final String batchId;
        public final String tenantId;
        public final String branchId;
        public final String productId;
        public final String brandName;
        public final String brandNameBn;
        public final String genericName;
        public final String dosageForm;
        public final String batchNumber;
        public final int quantity;
        public final int reserved;
        public final LocalDate expiryDate;
        public final boolean isQuarantined;
        public final boolean isRecalled;
        public final BigDecimal costPerUnit;
        public final BigDecimal mrpUnit;
        public final String storageLocation;

        public MockBatchRecord(
                String batchId,
                String tenantId,
                String branchId,
                String productId,
                String brandName,
                String brandNameBn,
                String genericName,
                String dosageForm,
                String batchNumber,
                int quantity,
                int reserved,
                LocalDate expiryDate,
                boolean isQuarantined,
                boolean isRecalled,
                BigDecimal costPerUnit,
                BigDecimal mrpUnit,
                String storageLocation
        ) {
            this.batchId = batchId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.productId = productId;
            this.brandName = brandName;
            this.brandNameBn = brandNameBn;
            this.genericName = genericName;
            this.dosageForm = dosageForm;
            this.batchNumber = batchNumber;
            this.quantity = quantity;
            this.reserved = reserved;
            this.expiryDate = expiryDate;
            this.isQuarantined = isQuarantined;
            this.isRecalled = isRecalled;
            this.costPerUnit = costPerUnit;
            this.mrpUnit = mrpUnit;
            this.storageLocation = storageLocation;
        }
    }

    private final List<MockBatchRecord> database = new ArrayList<>();

    public void addBatch(MockBatchRecord batch) {
        database.add(batch);
    }

    public StockInquiryResponseDto queryStock(
            String tenantId,
            String branchId,
            String search,
            String productIdFilter,
            String expiryStatusFilter,
            String userRole,
            String userBranchId
    ) {
        if ("CASHIER".equalsIgnoreCase(userRole)) {
            if (userBranchId != null && !userBranchId.equals(branchId)) {
                throw new SecurityException("ERR_BRANCH_ACCESS_DENIED: Cashiers cannot query stock outside assigned branch.");
            }
        }

        LocalDate today = LocalDate.now();
        Map<String, ProductStockInquiryDto> productMap = new LinkedHashMap<>();
        int runAvailableTotal = 0;

        for (MockBatchRecord b : database) {
            if (!b.tenantId.equals(tenantId) || !b.branchId.equals(branchId)) {
                continue;
            }
            if (productIdFilter != null && !b.productId.equals(productIdFilter)) {
                continue;
            }
            if (search != null && !search.trim().isEmpty()) {
                String s = search.trim().toLowerCase();
                boolean matches = b.brandName.toLowerCase().contains(s)
                        || b.brandNameBn.toLowerCase().contains(s)
                        || b.genericName.toLowerCase().contains(s)
                        || b.batchNumber.toLowerCase().contains(s);
                if (!matches) {
                    continue;
                }
            }

            long daysLeft = ChronoUnit.DAYS.between(today, b.expiryDate);
            ExpiryCategory cat;
            if (b.isRecalled) {
                cat = ExpiryCategory.RECALLED;
            } else if (b.isQuarantined) {
                cat = ExpiryCategory.QUARANTINED;
            } else if (daysLeft <= 0) {
                cat = ExpiryCategory.EXPIRED;
            } else if (daysLeft <= 30) {
                cat = ExpiryCategory.CRITICAL_NEAR_EXPIRY;
            } else if (daysLeft <= 90) {
                cat = ExpiryCategory.NEAR_EXPIRY;
            } else {
                cat = ExpiryCategory.ACTIVE;
            }

            if (expiryStatusFilter != null && !"ALL".equalsIgnoreCase(expiryStatusFilter)) {
                if ("EXPIRED".equalsIgnoreCase(expiryStatusFilter) && cat != ExpiryCategory.EXPIRED) {
                    continue;
                }
                if ("NEAR_EXPIRY".equalsIgnoreCase(expiryStatusFilter)
                        && cat != ExpiryCategory.NEAR_EXPIRY
                        && cat != ExpiryCategory.CRITICAL_NEAR_EXPIRY) {
                    continue;
                }
            }

            int available = (cat == ExpiryCategory.EXPIRED || cat == ExpiryCategory.QUARANTINED || cat == ExpiryCategory.RECALLED)
                    ? 0
                    : Math.max(0, b.quantity - b.reserved);

            runAvailableTotal += available;

            ProductStockInquiryDto prod = productMap.computeIfAbsent(b.productId, k ->
                    new ProductStockInquiryDto(b.productId, b.brandName, b.brandNameBn, b.genericName, b.dosageForm)
            );

            prod.totalQuantityBaseUnits += b.quantity;
            prod.availableQuantityBaseUnits += available;
            prod.reservedQuantityBaseUnits += b.reserved;

            if (cat == ExpiryCategory.EXPIRED) {
                prod.expiredQuantityBaseUnits += b.quantity;
            } else if (cat == ExpiryCategory.QUARANTINED || cat == ExpiryCategory.RECALLED) {
                prod.quarantinedQuantityBaseUnits += b.quantity;
            } else if (cat == ExpiryCategory.NEAR_EXPIRY || cat == ExpiryCategory.CRITICAL_NEAR_EXPIRY) {
                prod.nearExpiryQuantityBaseUnits += b.quantity;
            }

            BigDecimal costVal = b.costPerUnit.multiply(BigDecimal.valueOf(b.quantity));
            BigDecimal mrpVal = b.mrpUnit.multiply(BigDecimal.valueOf(b.quantity));
            prod.totalCostValuationBdt = prod.totalCostValuationBdt.add(costVal);
            prod.totalMrpValuationBdt = prod.totalMrpValuationBdt.add(mrpVal);

            prod.batches.add(new BatchInquiryDto(
                    b.batchId, b.batchNumber, b.expiryDate, daysLeft, cat,
                    b.quantity, b.reserved, available, b.storageLocation, b.costPerUnit, b.mrpUnit
            ));
        }

        // FEFO sort and packaging calculation
        for (ProductStockInquiryDto prod : productMap.values()) {
            prod.batches.sort(Comparator.comparing(a -> a.expiryDate));
            int units = prod.availableQuantityBaseUnits;
            int boxes = units / 100;
            int rem = units % 100;
            int strips = rem / 10;
            int pieces = rem % 10;
            String text = String.format("%d Boxes, %d Strips, %d Pieces (Total: %d)", boxes, strips, pieces, units);
            prod.packagingSummary = new PackagingSummaryDto(boxes, strips, pieces, units, text);
        }

        return new StockInquiryResponseDto(
                tenantId, branchId, productMap.size(), runAvailableTotal, new ArrayList<>(productMap.values())
        );
    }
}
