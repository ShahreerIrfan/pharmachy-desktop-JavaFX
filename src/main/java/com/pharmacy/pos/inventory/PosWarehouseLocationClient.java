package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * PosWarehouseLocationClient
 * Architecture Decision: ADR-0202 (P202).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Models branch warehouses, shelves, bins, cold-chain refrigerators,
 * quarantine segregation bays, and controlled-location access rules.
 */
public class PosWarehouseLocationClient {

    public enum WarehouseType {
        MAIN_DISPENSARY("Main Pharmacy Dispensary", "মূল ডিসপেনসারি"),
        CENTRAL_WAREHOUSE("Central Storage Warehouse", "কেন্দ্রীয় গুদাম"),
        COLD_ROOM("Cold Chain Storage Room", "কোল্ড চেইন রুম"),
        QUARANTINE_AREA("Dedicated Quarantine Facility", "কোয়ারেন্টাইন এলাকা"),
        HOSPITAL_STORE("Hospital Inpatient Store", "হাসপাতাল স্টোর");

        public final String labelEn;
        public final String labelBn;

        WarehouseType(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum StorageLocationType {
        SHELF("Ambient Shelf / Rack", "সাধারণ তাক / রেক"),
        BIN("Medicine Bin / Compartment", "মেডিসিন বিন / খুব"),
        FRIDGE("Refrigerated Cold Chain (2°C - 8°C)", "রেফ্রিজারেটর (২°C - ৮°C)"),
        FREEZER("Deep Freezer Unit (-20°C)", "ডিপ ফ্রিজার (-২০°C)"),
        QUARANTINE_ZONE("Quarantine Holding Zone", "কোয়ারেন্টাইন হোল্ডিং জোন"),
        NARCOTICS_VAULT("Controlled Substance Locker", "নিয়ন্ত্রিত নারকোটিক্স ভল্ট"),
        DISPENSARY_COUNTER("Front Fast Counter", "ফ্রন্ট ডিসপেনসারি কাউন্টার");

        public final String labelEn;
        public final String labelBn;

        StorageLocationType(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static final class WarehouseDto {
        public final String warehouseId;
        public final String tenantId;
        public final String branchId;
        public final String code;
        public final String name;
        public final String nameBn;
        public final WarehouseType warehouseType;
        public final boolean isColdStorage;
        public final boolean isActive;

        public WarehouseDto(
                String warehouseId,
                String tenantId,
                String branchId,
                String code,
                String name,
                String nameBn,
                WarehouseType warehouseType,
                boolean isColdStorage,
                boolean isActive
        ) {
            this.warehouseId = warehouseId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.code = code;
            this.name = name;
            this.nameBn = nameBn;
            this.warehouseType = warehouseType;
            this.isColdStorage = isColdStorage;
            this.isActive = isActive;
        }
    }

    public static final class StorageLocationDto {
        public final String locationId;
        public final String tenantId;
        public final String branchId;
        public final String warehouseId;
        public final String code;
        public final String name;
        public final String nameBn;
        public final StorageLocationType locationType;
        public final int capacityPackLimit;
        public int currentPackCount;
        public final boolean isControlledAccess;
        public final BigDecimal minTempCelsius;
        public final BigDecimal maxTempCelsius;
        public final boolean isActive;

        public StorageLocationDto(
                String locationId,
                String tenantId,
                String branchId,
                String warehouseId,
                String code,
                String name,
                String nameBn,
                StorageLocationType locationType,
                int capacityPackLimit,
                int currentPackCount,
                boolean isControlledAccess,
                BigDecimal minTempCelsius,
                BigDecimal maxTempCelsius,
                boolean isActive
        ) {
            this.locationId = locationId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.warehouseId = warehouseId;
            this.code = code;
            this.name = name;
            this.nameBn = nameBn;
            this.locationType = locationType;
            this.capacityPackLimit = capacityPackLimit;
            this.currentPackCount = currentPackCount;
            this.isControlledAccess = isControlledAccess;
            this.minTempCelsius = minTempCelsius;
            this.maxTempCelsius = maxTempCelsius;
            this.isActive = isActive;
        }
    }

    public static final class BatchLocationAssignmentDto {
        public final String assignmentId;
        public final String tenantId;
        public final String branchId;
        public final String batchId;
        public final String batchNumber;
        public final String locationId;
        public final String locationCode;
        public int quantity;
        public final String assignedBy;
        public final Instant assignedAt;
        public String notes;

        public BatchLocationAssignmentDto(
                String assignmentId,
                String tenantId,
                String branchId,
                String batchId,
                String batchNumber,
                String locationId,
                String locationCode,
                int quantity,
                String assignedBy,
                Instant assignedAt,
                String notes
        ) {
            this.assignmentId = assignmentId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.batchId = batchId;
            this.batchNumber = batchNumber;
            this.locationId = locationId;
            this.locationCode = locationCode;
            this.quantity = quantity;
            this.assignedBy = assignedBy;
            this.assignedAt = assignedAt;
            this.notes = notes;
        }
    }

    private final Map<String, WarehouseDto> warehouses = new HashMap<>();
    private final Map<String, StorageLocationDto> locations = new HashMap<>();
    private final Map<String, BatchLocationAssignmentDto> assignments = new HashMap<>();

    public WarehouseDto createWarehouse(
            String tenantId,
            String branchId,
            String code,
            String name,
            String nameBn,
            WarehouseType type,
            boolean isColdStorage,
            String actorRole
    ) {
        if ("CASHIER".equalsIgnoreCase(actorRole)) {
            throw new SecurityException("ERR_WAREHOUSE_CONFIG_DENIED: Cashiers cannot configure warehouses.");
        }
        String id = "wh-" + UUID.randomUUID().toString().substring(0, 8);
        WarehouseDto wh = new WarehouseDto(id, tenantId, branchId, code.toUpperCase(), name, nameBn, type, isColdStorage, true);
        warehouses.put(wh.warehouseId, wh);
        return wh;
    }

    public StorageLocationDto createStorageLocation(
            String tenantId,
            String branchId,
            String warehouseId,
            String code,
            String name,
            String nameBn,
            StorageLocationType type,
            int capacityPackLimit,
            boolean isControlledAccess,
            BigDecimal minTemp,
            BigDecimal maxTemp,
            String actorRole
    ) {
        if ("CASHIER".equalsIgnoreCase(actorRole)) {
            throw new SecurityException("ERR_WAREHOUSE_CONFIG_DENIED: Cashiers cannot configure storage locations.");
        }
        if (capacityPackLimit < 0) {
            throw new IllegalArgumentException("ERR_INVALID_CAPACITY: Capacity limit cannot be negative.");
        }
        if (minTemp != null && maxTemp != null && minTemp.compareTo(maxTemp) > 0) {
            throw new IllegalArgumentException("ERR_INVALID_TEMPERATURE: Min temp cannot exceed max temp.");
        }

        String id = "loc-" + UUID.randomUUID().toString().substring(0, 8);
        StorageLocationDto loc = new StorageLocationDto(
                id, tenantId, branchId, warehouseId, code.toUpperCase(), name, nameBn,
                type, capacityPackLimit, 0, isControlledAccess, minTemp, maxTemp, true
        );
        locations.put(loc.locationId, loc);
        return loc;
    }

    public BatchLocationAssignmentDto assignBatchToLocation(
            String tenantId,
            String branchId,
            String batchId,
            String batchNumber,
            boolean isColdChain,
            boolean isQuarantined,
            int totalBatchQty,
            String locationId,
            int quantity,
            String actorRole,
            String supervisorRole
    ) {
        if ("CASHIER".equalsIgnoreCase(actorRole)) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers cannot assign stock to bins.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("ERR_INVALID_QUANTITY: Quantity must be positive.");
        }
        if (quantity > totalBatchQty) {
            throw new IllegalArgumentException("ERR_INSUFFICIENT_BATCH_STOCK: Quantity exceeds batch total.");
        }

        StorageLocationDto loc = locations.get(locationId);
        if (loc == null || !loc.tenantId.equals(tenantId) || !loc.branchId.equals(branchId)) {
            throw new NoSuchElementException("ERR_LOCATION_NOT_FOUND: Location not found for branch.");
        }

        // Controlled location check
        if (loc.isControlledAccess) {
            if ("NARCOTICS_VAULT".equalsIgnoreCase(loc.locationType.name())) {
                if (supervisorRole == null || !supervisorRole.matches("(?i)BRANCH_MANAGER|PHARMACY_OWNER")) {
                    throw new IllegalStateException("ERR_DUAL_AUTH_REQUIRED: Dual manager sign-off required for narcotics vault.");
                }
            }
        }

        // Cold-chain check
        if (isColdChain) {
            if (loc.locationType != StorageLocationType.FRIDGE && loc.locationType != StorageLocationType.FREEZER) {
                throw new IllegalStateException("ERR_COLD_CHAIN_TEMPERATURE_MISMATCH: Cold chain items must be in FRIDGE or FREEZER.");
            }
        }

        // Quarantine check
        if (isQuarantined) {
            if (loc.locationType != StorageLocationType.QUARANTINE_ZONE) {
                throw new IllegalStateException("ERR_QUARANTINE_RESTRICTION: Quarantined stock must be stored in QUARANTINE_ZONE.");
            }
        } else {
            if (loc.locationType == StorageLocationType.QUARANTINE_ZONE) {
                throw new IllegalStateException("ERR_ACTIVE_STOCK_IN_QUARANTINE: Active stock cannot be placed in quarantine zone.");
            }
        }

        // Capacity check
        if (loc.capacityPackLimit > 0 && (loc.currentPackCount + quantity > loc.capacityPackLimit)) {
            throw new IllegalStateException("ERR_CAPACITY_EXCEEDED: Location capacity limit exceeded.");
        }

        String key = batchId + "@" + locationId;
        BatchLocationAssignmentDto assign = assignments.get(key);
        if (assign == null) {
            assign = new BatchLocationAssignmentDto(
                    "bla-" + UUID.randomUUID().toString().substring(0, 8),
                    tenantId, branchId, batchId, batchNumber, locationId, loc.code, quantity,
                    actorRole, Instant.now(), "Assigned via Desktop POS"
            );
            assignments.put(key, assign);
        } else {
            assign.quantity += quantity;
        }

        loc.currentPackCount += quantity;
        return assign;
    }

    public void transferBatchStock(
            String tenantId,
            String branchId,
            String batchId,
            String sourceLocationId,
            String targetLocationId,
            int quantity,
            String actorRole
    ) {
        if ("CASHIER".equalsIgnoreCase(actorRole)) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers cannot transfer stock.");
        }
        StorageLocationDto srcLoc = locations.get(sourceLocationId);
        StorageLocationDto dstLoc = locations.get(targetLocationId);
        if (srcLoc == null || dstLoc == null) {
            throw new NoSuchElementException("ERR_LOCATION_NOT_FOUND");
        }

        String srcKey = batchId + "@" + sourceLocationId;
        BatchLocationAssignmentDto srcAssign = assignments.get(srcKey);
        if (srcAssign == null || srcAssign.quantity < quantity) {
            throw new IllegalArgumentException("ERR_INSUFFICIENT_STOCK_AT_SOURCE");
        }

        if (dstLoc.capacityPackLimit > 0 && (dstLoc.currentPackCount + quantity > dstLoc.capacityPackLimit)) {
            throw new IllegalStateException("ERR_CAPACITY_EXCEEDED: Target location capacity exceeded.");
        }

        srcAssign.quantity -= quantity;
        srcLoc.currentPackCount -= quantity;

        String dstKey = batchId + "@" + targetLocationId;
        BatchLocationAssignmentDto dstAssign = assignments.get(dstKey);
        if (dstAssign == null) {
            dstAssign = new BatchLocationAssignmentDto(
                    "bla-" + UUID.randomUUID().toString().substring(0, 8),
                    tenantId, branchId, batchId, srcAssign.batchNumber, targetLocationId, dstLoc.code,
                    quantity, actorRole, Instant.now(), "Transferred stock"
            );
            assignments.put(dstKey, dstAssign);
        } else {
            dstAssign.quantity += quantity;
        }
        dstLoc.currentPackCount += quantity;
    }

    public List<StorageLocationDto> listLocationsByWarehouse(String warehouseId) {
        List<StorageLocationDto> list = new ArrayList<>();
        for (StorageLocationDto loc : locations.values()) {
            if (loc.warehouseId.equals(warehouseId)) {
                list.add(loc);
            }
        }
        return list;
    }
}
