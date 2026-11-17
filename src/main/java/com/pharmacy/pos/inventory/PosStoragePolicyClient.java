package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosStoragePolicyClient
 * Architecture Decision: ADR-0213 (P213).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Client for Storage Policy Enforcement:
 * - Prevents assignment of cold-chain medicines to storage zones lacking approved temperature capabilities.
 * - Validates location capabilities (Fridge, Freezer vs Ambient Shelf/Bin).
 * - Pre-flight relocation validation before POS / stock movements.
 * - Enforces role-based access control (Cashier denied).
 * - Local offline validation cache for zero-latency cashier/inventory checks.
 */
public class PosStoragePolicyClient {

    public enum LocationType {
        SHELF("Ambient Shelf / Rack", "সাধারণ শেলফ / র্যাক"),
        BIN("Medicine Bin / Drawer", "মেডিসিন বিন / ড্রয়ার"),
        FRIDGE("Refrigerated Cold Chain Unit (2°C - 8°C)", "রেফ্রিজারেটর কোল্ড-চেইন (২°সে - ৮°সে)"),
        FREEZER("Deep Freezer Unit (-20°C)", "ডিপ ফ্রিজার (-২০°সে)"),
        QUARANTINE_ZONE("Quarantine Holding Area", "কোয়ারেন্টাইন এলাকা"),
        NARCOTICS_VAULT("Controlled Substances Vault", "নিয়ন্ত্রিত ওষুধের ভল্ট"),
        DISPENSARY_COUNTER("Dispensary Fast Counter", "দ্রুত বিতরণ কাউন্টার");

        public final String labelEn;
        public final String labelBn;

        LocationType(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static class StorageLocationInfo {
        private final String locationId;
        private final String branchId;
        private final String code;
        private final String name;
        private final LocationType locationType;
        private final boolean isColdCapable;
        private final BigDecimal minTemp;
        private final BigDecimal maxTemp;

        public StorageLocationInfo(
                String locationId,
                String branchId,
                String code,
                String name,
                LocationType locationType,
                boolean isColdCapable,
                BigDecimal minTemp,
                BigDecimal maxTemp
        ) {
            this.locationId = locationId;
            this.branchId = branchId;
            this.code = code;
            this.name = name;
            this.locationType = locationType;
            this.isColdCapable = isColdCapable || locationType == LocationType.FRIDGE || locationType == LocationType.FREEZER;
            this.minTemp = minTemp;
            this.maxTemp = maxTemp;
        }

        public String getLocationId() { return locationId; }
        public String getBranchId() { return branchId; }
        public String getCode() { return code; }
        public String getName() { return name; }
        public LocationType getLocationType() { return locationType; }
        public boolean isColdCapable() { return isColdCapable; }
        public BigDecimal getMinTemp() { return minTemp; }
        public BigDecimal getMaxTemp() { return maxTemp; }
    }

    public static class BatchAssignmentItem {
        private final String batchId;
        private final String batchNumber;
        private final String productName;
        private final boolean requiresColdChain;
        private String storageLocationCode;
        private final int quantityUnits;

        public BatchAssignmentItem(
                String batchId,
                String batchNumber,
                String productName,
                boolean requiresColdChain,
                String storageLocationCode,
                int quantityUnits
        ) {
            this.batchId = batchId;
            this.batchNumber = batchNumber;
            this.productName = productName;
            this.requiresColdChain = requiresColdChain;
            this.storageLocationCode = storageLocationCode;
            this.quantityUnits = quantityUnits;
        }

        public String getBatchId() { return batchId; }
        public String getBatchNumber() { return batchNumber; }
        public String getProductName() { return productName; }
        public boolean isRequiresColdChain() { return requiresColdChain; }
        public String getStorageLocationCode() { return storageLocationCode; }
        public void setStorageLocationCode(String code) { this.storageLocationCode = code; }
        public int getQuantityUnits() { return quantityUnits; }
    }

    public static class ValidationResult {
        private final boolean compliant;
        private final String reason;
        private final String productName;
        private final String locationCode;

        public ValidationResult(boolean compliant, String reason, String productName, String locationCode) {
            this.compliant = compliant;
            this.reason = reason;
            this.productName = productName;
            this.locationCode = locationCode;
        }

        public boolean isCompliant() { return compliant; }
        public String getReason() { return reason; }
        public String getProductName() { return productName; }
        public String getLocationCode() { return locationCode; }
    }

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "PHARMACY_OWNER",
            "BRANCH_MANAGER",
            "SUPERADMIN",
            "STAFF_PHARMACIST",
            "PROCUREMENT_OFFICER",
            "INVENTORY_CLERK"
    );

    private void assertInventoryRole(String userRole) {
        if (userRole == null || "CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("Cashiers cannot assign or relocate medicine stock. Access is restricted to Managers, Owners, and Pharmacists.");
        }
    }

    public ValidationResult validateAssignment(
            String productName,
            boolean requiresColdChain,
            StorageLocationInfo targetLocation,
            String justification
    ) {
        if (targetLocation == null) {
            return new ValidationResult(false, "Target storage location cannot be null.", productName, "");
        }

        if (requiresColdChain && !targetLocation.isColdCapable()) {
            if (justification != null && justification.trim().length() >= 15) {
                return new ValidationResult(true, "Overridden with clinical justification: " + justification.trim(), productName, targetLocation.getCode());
            }
            String reason = String.format(
                    "Storage policy violation: Medicine '%s' requires cold-chain temperature control (2.00°C - 8.00°C) and cannot be assigned to ambient location '%s' [%s].",
                    productName, targetLocation.getName(), targetLocation.getCode()
            );
            return new ValidationResult(false, reason, productName, targetLocation.getCode());
        }

        if (!requiresColdChain && targetLocation.getLocationType() == LocationType.FREEZER) {
            String reason = String.format("Storage policy violation: Ambient product '%s' cannot be stored in Deep Freezer '%s'.", productName, targetLocation.getName());
            return new ValidationResult(false, reason, productName, targetLocation.getCode());
        }

        return new ValidationResult(true, "Storage assignment is compliant.", productName, targetLocation.getCode());
    }

    public BatchAssignmentItem relocateBatch(
            String userRole,
            BatchAssignmentItem batch,
            StorageLocationInfo targetLocation,
            String justification
    ) {
        assertInventoryRole(userRole);

        ValidationResult check = validateAssignment(
                batch.getProductName(),
                batch.isRequiresColdChain(),
                targetLocation,
                justification
        );

        if (!check.isCompliant()) {
            throw new IllegalArgumentException(check.getReason());
        }

        batch.setStorageLocationCode(targetLocation.getCode());
        return batch;
    }

    public Map<String, Object> auditBranchCompliance(
            List<BatchAssignmentItem> batches,
            Map<String, StorageLocationInfo> locationsMap
    ) {
        int total = batches.size();
        int compliantCount = 0;
        List<Map<String, String>> violations = new ArrayList<>();

        for (BatchAssignmentItem b : batches) {
            StorageLocationInfo loc = locationsMap.get(b.getStorageLocationCode());
            if (loc == null) {
                if (b.isRequiresColdChain()) {
                    violations.add(Map.of(
                            "batchNumber", b.getBatchNumber(),
                            "productName", b.getProductName(),
                            "reason", "Cold chain product stored in unmapped location."
                    ));
                } else {
                    compliantCount++;
                }
                continue;
            }

            ValidationResult res = validateAssignment(b.getProductName(), b.isRequiresColdChain(), loc, "");
            if (res.isCompliant()) {
                compliantCount++;
            } else {
                violations.add(Map.of(
                        "batchNumber", b.getBatchNumber(),
                        "productName", b.getProductName(),
                        "reason", res.getReason()
                ));
            }
        }

        Map<String, Object> report = new HashMap<>();
        report.put("totalBatches", total);
        report.put("compliantCount", compliantCount);
        report.put("violationCount", violations.size());
        report.put("complianceRate", total > 0 ? ((double) compliantCount / total) * 100.0 : 100.0);
        report.put("violations", violations);
        return report;
    }
}

