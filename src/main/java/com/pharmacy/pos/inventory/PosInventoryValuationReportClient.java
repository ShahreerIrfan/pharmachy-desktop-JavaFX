package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosInventoryValuationReportClient
 * Architecture Decision: ADR-0216 (P216).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Client for Multi-Category Inventory Valuation Reports:
 * - On-hand, sellable, quarantined, in-transit, and expired stock valuation.
 * - Costing rules: FEFO lot cost, FIFO, Weighted Average, and MRP.
 * - Mathematical precision (BigDecimal 2-decimal places in BDT).
 * - Cryptographic SHA-256 checksum integrity verification.
 * - RBAC protection (Cashier strictly denied).
 * - Offline caching and rapid POS valuation inquiries.
 */
public class PosInventoryValuationReportClient {

    public enum ValuationCostingRule {
        FEFO_LOT_COST("FEFO Batch Lot Cost", "এফইএফও ব্যাচ লট কস্ট"),
        FIFO_LOT_COST("FIFO Inward Purchase Lot Cost", "ফিফো পারচেজ লট কস্ট"),
        WEIGHTED_AVERAGE("Weighted Average Cost", "ভারযুক্ত গড় মূল্য"),
        MRP_RETAIL_VALUE("MRP Retail Valuation", "খুচরা মূল্য ভ্যালুয়েশন");

        public final String labelEn;
        public final String labelBn;

        ValuationCostingRule(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum InventoryCategory {
        SELLABLE("Sellable Stock", "বিক্রয়যোগ্য স্টক"),
        QUARANTINED("Quarantined Hold", "কোয়ারেন্টাইনড স্টক"),
        IN_TRANSIT("In-Transit Transfer", "ইন-ট্রানজিট স্থানান্তর"),
        EXPIRED("Expired Pending Disposal", "মেয়াদোত্তীর্ণ স্টক");

        public final String labelEn;
        public final String labelBn;

        InventoryCategory(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static class ValuationLineItem {
        private final String productId;
        private final String productName;
        private final String batchNumber;
        private final InventoryCategory category;
        private final int quantityBaseUnits;
        private final BigDecimal unitCost;
        private final BigDecimal totalValueBdt;
        private final boolean isColdChain;
        private final String statusReason;

        public ValuationLineItem(
                String productId,
                String productName,
                String batchNumber,
                InventoryCategory category,
                int quantityBaseUnits,
                BigDecimal unitCost,
                boolean isColdChain,
                String statusReason
        ) {
            this.productId = productId;
            this.productName = productName != null ? productName : "";
            this.batchNumber = batchNumber != null ? batchNumber : "";
            this.category = category;
            this.quantityBaseUnits = quantityBaseUnits;
            this.unitCost = (unitCost != null ? unitCost : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            this.totalValueBdt = this.unitCost.multiply(BigDecimal.valueOf(quantityBaseUnits)).setScale(2, RoundingMode.HALF_UP);
            this.isColdChain = isColdChain;
            this.statusReason = statusReason != null ? statusReason : "";
        }

        public String getProductId() { return productId; }
        public String getProductName() { return productName; }
        public String getBatchNumber() { return batchNumber; }
        public InventoryCategory getCategory() { return category; }
        public int getQuantityBaseUnits() { return quantityBaseUnits; }
        public BigDecimal getUnitCost() { return unitCost; }
        public BigDecimal getTotalValueBdt() { return totalValueBdt; }
        public boolean isColdChain() { return isColdChain; }
        public String getStatusReason() { return statusReason; }
    }

    public static class ValuationReport {
        private final String reportId;
        private final String tenantId;
        private final String branchId;
        private final Instant asOfTimestamp;
        private final ValuationCostingRule costingRule;
        private final int totalOnHandUnits;
        private final BigDecimal totalOnHandValueBdt;
        private final int sellableUnits;
        private final BigDecimal sellableValueBdt;
        private final int quarantinedUnits;
        private final BigDecimal quarantinedValueBdt;
        private final int inTransitUnits;
        private final BigDecimal inTransitValueBdt;
        private final int expiredUnits;
        private final BigDecimal expiredValueBdt;
        private final int coldChainUnits;
        private final BigDecimal coldChainValueBdt;
        private final boolean isMathematicallyBalanced;
        private final int discrepancyUnits;
        private final BigDecimal discrepancyValueBdt;
        private final String sha256Checksum;
        private final List<ValuationLineItem> lineItems;

        public ValuationReport(
                String reportId,
                String tenantId,
                String branchId,
                Instant asOfTimestamp,
                ValuationCostingRule costingRule,
                int totalOnHandUnits,
                BigDecimal totalOnHandValueBdt,
                int sellableUnits,
                BigDecimal sellableValueBdt,
                int quarantinedUnits,
                BigDecimal quarantinedValueBdt,
                int inTransitUnits,
                BigDecimal inTransitValueBdt,
                int expiredUnits,
                BigDecimal expiredValueBdt,
                int coldChainUnits,
                BigDecimal coldChainValueBdt,
                boolean isMathematicallyBalanced,
                int discrepancyUnits,
                BigDecimal discrepancyValueBdt,
                String sha256Checksum,
                List<ValuationLineItem> lineItems
        ) {
            this.reportId = reportId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.asOfTimestamp = asOfTimestamp;
            this.costingRule = costingRule;
            this.totalOnHandUnits = totalOnHandUnits;
            this.totalOnHandValueBdt = totalOnHandValueBdt.setScale(2, RoundingMode.HALF_UP);
            this.sellableUnits = sellableUnits;
            this.sellableValueBdt = sellableValueBdt.setScale(2, RoundingMode.HALF_UP);
            this.quarantinedUnits = quarantinedUnits;
            this.quarantinedValueBdt = quarantinedValueBdt.setScale(2, RoundingMode.HALF_UP);
            this.inTransitUnits = inTransitUnits;
            this.inTransitValueBdt = inTransitValueBdt.setScale(2, RoundingMode.HALF_UP);
            this.expiredUnits = expiredUnits;
            this.expiredValueBdt = expiredValueBdt.setScale(2, RoundingMode.HALF_UP);
            this.coldChainUnits = coldChainUnits;
            this.coldChainValueBdt = coldChainValueBdt.setScale(2, RoundingMode.HALF_UP);
            this.isMathematicallyBalanced = isMathematicallyBalanced;
            this.discrepancyUnits = discrepancyUnits;
            this.discrepancyValueBdt = discrepancyValueBdt.setScale(2, RoundingMode.HALF_UP);
            this.sha256Checksum = sha256Checksum;
            this.lineItems = Collections.unmodifiableList(lineItems != null ? lineItems : Collections.emptyList());
        }

        public String getReportId() { return reportId; }
        public String getTenantId() { return tenantId; }
        public String getBranchId() { return branchId; }
        public Instant getAsOfTimestamp() { return asOfTimestamp; }
        public ValuationCostingRule getCostingRule() { return costingRule; }
        public int getTotalOnHandUnits() { return totalOnHandUnits; }
        public BigDecimal getTotalOnHandValueBdt() { return totalOnHandValueBdt; }
        public int getSellableUnits() { return sellableUnits; }
        public BigDecimal getSellableValueBdt() { return sellableValueBdt; }
        public int getQuarantinedUnits() { return quarantinedUnits; }
        public BigDecimal getQuarantinedValueBdt() { return quarantinedValueBdt; }
        public int getInTransitUnits() { return inTransitUnits; }
        public BigDecimal getInTransitValueBdt() { return inTransitValueBdt; }
        public int getExpiredUnits() { return expiredUnits; }
        public BigDecimal getExpiredValueBdt() { return expiredValueBdt; }
        public int getColdChainUnits() { return coldChainUnits; }
        public BigDecimal getColdChainValueBdt() { return coldChainValueBdt; }
        public boolean isMathematicallyBalanced() { return isMathematicallyBalanced; }
        public int getDiscrepancyUnits() { return discrepancyUnits; }
        public BigDecimal getDiscrepancyValueBdt() { return discrepancyValueBdt; }
        public String getSha256Checksum() { return sha256Checksum; }
        public List<ValuationLineItem> getLineItems() { return lineItems; }
    }

    private final Map<String, ValuationReport> reportCache = new ConcurrentHashMap<>();

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "PHARMACY_OWNER",
            "BRANCH_MANAGER",
            "SUPERADMIN",
            "STAFF_PHARMACIST",
            "PROCUREMENT_OFFICER",
            "INVENTORY_CLERK",
            "AUDITOR"
    );

    private void assertValuationRole(String userRole) {
        if (userRole == null || "CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("Cashiers cannot view or generate inventory valuation reports. Restricted to Managers, Owners, and Pharmacists.");
        }
    }

    public static String computeChecksum(List<ValuationLineItem> lines) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            List<ValuationLineItem> sorted = new ArrayList<>(lines);
            sorted.sort(Comparator.comparing(ValuationLineItem::getProductId)
                    .thenComparing(ValuationLineItem::getBatchNumber)
                    .thenComparing(item -> item.getCategory().name()));

            for (ValuationLineItem l : sorted) {
                String token = l.getProductId() + "|" + l.getBatchNumber() + "|" + l.getCategory().name() + "|" +
                        l.getQuantityBaseUnits() + "|" + l.getUnitCost().toPlainString() + "|" + l.getTotalValueBdt().toPlainString() + "\n";
                md.update(token.getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = md.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    public ValuationReport generateReport(
            String userRole,
            String tenantId,
            String branchId,
            ValuationCostingRule costingRule,
            List<ValuationLineItem> lines
    ) {
        assertValuationRole(userRole);

        int onHandQty = 0;
        BigDecimal onHandVal = BigDecimal.ZERO;
        int sellableQty = 0;
        BigDecimal sellableVal = BigDecimal.ZERO;
        int quarantinedQty = 0;
        BigDecimal quarantinedVal = BigDecimal.ZERO;
        int inTransitQty = 0;
        BigDecimal inTransitVal = BigDecimal.ZERO;
        int expiredQty = 0;
        BigDecimal expiredVal = BigDecimal.ZERO;
        int coldChainQty = 0;
        BigDecimal coldChainVal = BigDecimal.ZERO;

        for (ValuationLineItem item : lines) {
            BigDecimal lineVal = item.getTotalValueBdt();
            switch (item.getCategory()) {
                case SELLABLE -> {
                    sellableQty += item.getQuantityBaseUnits();
                    sellableVal = sellableVal.add(lineVal);
                    onHandQty += item.getQuantityBaseUnits();
                    onHandVal = onHandVal.add(lineVal);
                }
                case QUARANTINED -> {
                    quarantinedQty += item.getQuantityBaseUnits();
                    quarantinedVal = quarantinedVal.add(lineVal);
                    onHandQty += item.getQuantityBaseUnits();
                    onHandVal = onHandVal.add(lineVal);
                }
                case EXPIRED -> {
                    expiredQty += item.getQuantityBaseUnits();
                    expiredVal = expiredVal.add(lineVal);
                    onHandQty += item.getQuantityBaseUnits();
                    onHandVal = onHandVal.add(lineVal);
                }
                case IN_TRANSIT -> {
                    inTransitQty += item.getQuantityBaseUnits();
                    inTransitVal = inTransitVal.add(lineVal);
                }
            }

            if (item.isColdChain() && item.getCategory() != InventoryCategory.IN_TRANSIT) {
                coldChainQty += item.getQuantityBaseUnits();
                coldChainVal = coldChainVal.add(lineVal);
            }
        }

        int discrepancyUnits = onHandQty - (sellableQty + quarantinedQty + expiredQty);
        BigDecimal discrepancyVal = onHandVal.subtract(sellableVal.add(quarantinedVal).add(expiredVal));
        boolean isBalanced = (discrepancyUnits == 0 && discrepancyVal.compareTo(BigDecimal.ZERO) == 0);

        String checksum = computeChecksum(lines);
        String reportId = "valrep-" + UUID.randomUUID().toString().substring(0, 12);

        ValuationReport report = new ValuationReport(
                reportId,
                tenantId,
                branchId,
                Instant.now(),
                costingRule,
                onHandQty,
                onHandVal,
                sellableQty,
                sellableVal,
                quarantinedQty,
                quarantinedVal,
                inTransitQty,
                inTransitVal,
                expiredQty,
                expiredVal,
                coldChainQty,
                coldChainVal,
                isBalanced,
                discrepancyUnits,
                discrepancyVal,
                checksum,
                lines
        );

        reportCache.put(reportId, report);
        return report;
    }

    public boolean verifyIntegrity(ValuationReport report) {
        String recomputed = computeChecksum(report.getLineItems());
        return recomputed.equals(report.getSha256Checksum());
    }

    public Optional<ValuationReport> getReport(String reportId) {
        return Optional.ofNullable(reportCache.get(reportId));
    }
}
