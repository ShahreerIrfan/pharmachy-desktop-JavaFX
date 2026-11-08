package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosWarehouseLocationTest
 * Architecture Decision: ADR-0202 (P202).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Tests for Warehouse, Bins, Cold Chain & Access Rules:
 * 1. Create warehouse and shelf/bin hierarchy with EN/BN translations.
 * 2. Invalid inputs: negative capacity, inverted temperature.
 * 3. Cold chain temperature mismatch rejection.
 * 4. Quarantine segregation enforcement.
 * 5. Narcotics vault dual authorization and Cashier RBAC denial.
 * 6. Capacity limit enforcement and stock transfer conservation.
 */
class PosWarehouseLocationTest {

    private PosWarehouseLocationClient client;
    private final String tenantId = "ten-lake-01";
    private final String branchId = "br-banani-01";

    @BeforeEach
    void setUp() {
        client = new PosWarehouseLocationClient();
    }

    @Test
    @DisplayName("Test 1: Create warehouse and storage locations with EN/BN labels")
    void testCreateWarehouseAndLocations() {
        PosWarehouseLocationClient.WarehouseDto wh = client.createWarehouse(
                tenantId, branchId, "WH-MAIN", "Main Backroom", "মূল গুদাম",
                PosWarehouseLocationClient.WarehouseType.MAIN_DISPENSARY, true, "BRANCH_MANAGER"
        );
        assertEquals("WH-MAIN", wh.code);
        assertEquals("মূল গুদাম", wh.nameBn);

        PosWarehouseLocationClient.StorageLocationDto shelf = client.createStorageLocation(
                tenantId, branchId, wh.warehouseId, "SH-A1", "Shelf A1", "তাক এ১",
                PosWarehouseLocationClient.StorageLocationType.SHELF, 100, false, null, null, "BRANCH_MANAGER"
        );
        assertEquals("SH-A1", shelf.code);
        assertEquals("তাক এ১", shelf.nameBn);
        assertEquals(100, shelf.capacityPackLimit);

        List<PosWarehouseLocationClient.StorageLocationDto> list = client.listLocationsByWarehouse(wh.warehouseId);
        assertEquals(1, list.size());
    }

    @Test
    @DisplayName("Test 2: Reject negative capacity and inverted temperatures")
    void testInvalidInputs() {
        PosWarehouseLocationClient.WarehouseDto wh = client.createWarehouse(
                tenantId, branchId, "WH-02", "Secondary", "দ্বিতীয় গুদাম",
                PosWarehouseLocationClient.WarehouseType.CENTRAL_WAREHOUSE, false, "PHARMACY_OWNER"
        );

        assertThrows(IllegalArgumentException.class, () ->
                client.createStorageLocation(
                        tenantId, branchId, wh.warehouseId, "BIN-BAD", "Bad Capacity", "ভুল ক্যাপাসিটি",
                        PosWarehouseLocationClient.StorageLocationType.BIN, -5, false, null, null, "BRANCH_MANAGER"
                )
        );

        assertThrows(IllegalArgumentException.class, () ->
                client.createStorageLocation(
                        tenantId, branchId, wh.warehouseId, "FRIDGE-BAD", "Bad Temp", "ভুল তাপমাত্রা",
                        PosWarehouseLocationClient.StorageLocationType.FRIDGE, 50, false,
                        new BigDecimal("10.00"), new BigDecimal("2.00"), "BRANCH_MANAGER"
                )
        );
    }

    @Test
    @DisplayName("Test 3: Reject cold-chain placement into ambient shelf")
    void testColdChainTemperatureSafety() {
        PosWarehouseLocationClient.WarehouseDto wh = client.createWarehouse(
                tenantId, branchId, "WH-COLD", "Cold Store", "কোল্ড স্টোর",
                PosWarehouseLocationClient.WarehouseType.COLD_ROOM, true, "BRANCH_MANAGER"
        );

        PosWarehouseLocationClient.StorageLocationDto shelf = client.createStorageLocation(
                tenantId, branchId, wh.warehouseId, "SH-AMB", "Ambient Rack", "সাধারণ তাক",
                PosWarehouseLocationClient.StorageLocationType.SHELF, 100, false, null, null, "BRANCH_MANAGER"
        );

        PosWarehouseLocationClient.StorageLocationDto fridge = client.createStorageLocation(
                tenantId, branchId, wh.warehouseId, "FRIDGE-01", "Chiller Refrigerator", "রেফ্রিজারেটর ১",
                PosWarehouseLocationClient.StorageLocationType.FRIDGE, 50, false,
                new BigDecimal("2.00"), new BigDecimal("8.00"), "BRANCH_MANAGER"
        );

        // Cold-chain item into ambient shelf must fail
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                client.assignBatchToLocation(
                        tenantId, branchId, "bat-ins-01", "BAT-INS-01", true, false, 50,
                        shelf.locationId, 10, "BRANCH_MANAGER", null
                )
        );
        assertTrue(ex.getMessage().contains("ERR_COLD_CHAIN_TEMPERATURE_MISMATCH"));

        // Cold-chain item into fridge succeeds
        PosWarehouseLocationClient.BatchLocationAssignmentDto assign = client.assignBatchToLocation(
                tenantId, branchId, "bat-ins-01", "BAT-INS-01", true, false, 50,
                fridge.locationId, 15, "BRANCH_MANAGER", null
        );
        assertEquals(15, assign.quantity);
        assertEquals(15, fridge.currentPackCount);
    }

    @Test
    @DisplayName("Test 4: Quarantine segregation enforcement")
    void testQuarantineSegregation() {
        PosWarehouseLocationClient.WarehouseDto wh = client.createWarehouse(
                tenantId, branchId, "WH-QUAR", "Main Store", "প্রধান গুদাম",
                PosWarehouseLocationClient.WarehouseType.MAIN_DISPENSARY, false, "BRANCH_MANAGER"
        );

        PosWarehouseLocationClient.StorageLocationDto shelf = client.createStorageLocation(
                tenantId, branchId, wh.warehouseId, "SH-NORM", "Normal Shelf", "সাধারণ তাক",
                PosWarehouseLocationClient.StorageLocationType.SHELF, 80, false, null, null, "BRANCH_MANAGER"
        );

        PosWarehouseLocationClient.StorageLocationDto quarBay = client.createStorageLocation(
                tenantId, branchId, wh.warehouseId, "QUAR-BAY", "Quarantine Bay", "কোয়ারেন্টাইন এলাকা",
                PosWarehouseLocationClient.StorageLocationType.QUARANTINE_ZONE, 40, true, null, null, "BRANCH_MANAGER"
        );

        // Quarantined batch into active shelf fails
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                client.assignBatchToLocation(
                        tenantId, branchId, "bat-rec-01", "BAT-RECALL-01", false, true, 20,
                        shelf.locationId, 10, "BRANCH_MANAGER", null
                )
        );
        assertTrue(ex.getMessage().contains("ERR_QUARANTINE_RESTRICTION"));

        // Quarantined batch into quarantine bay succeeds
        PosWarehouseLocationClient.BatchLocationAssignmentDto assign = client.assignBatchToLocation(
                tenantId, branchId, "bat-rec-01", "BAT-RECALL-01", false, true, 20,
                quarBay.locationId, 20, "BRANCH_MANAGER", null
        );
        assertEquals(20, assign.quantity);
    }

    @Test
    @DisplayName("Test 5: Controlled location rejects Cashiers and enforces dual authorization")
    void testControlledLocationAndRbac() {
        PosWarehouseLocationClient.WarehouseDto wh = client.createWarehouse(
                tenantId, branchId, "WH-SAFE", "Safe Facility", "সুরক্ষিত গুদাম",
                PosWarehouseLocationClient.WarehouseType.MAIN_DISPENSARY, false, "BRANCH_MANAGER"
        );

        PosWarehouseLocationClient.StorageLocationDto vault = client.createStorageLocation(
                tenantId, branchId, wh.warehouseId, "VAULT-01", "Narcotics Vault", "নারকোটিক্স ভল্ট",
                PosWarehouseLocationClient.StorageLocationType.NARCOTICS_VAULT, 30, true, null, null, "BRANCH_MANAGER"
        );

        // Cashier role rejected
        assertThrows(SecurityException.class, () ->
                client.assignBatchToLocation(
                        tenantId, branchId, "bat-mor-01", "BAT-MOR-01", false, false, 25,
                        vault.locationId, 10, "CASHIER", null
                )
        );

        // Without manager sign-off rejected
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                client.assignBatchToLocation(
                        tenantId, branchId, "bat-mor-01", "BAT-MOR-01", false, false, 25,
                        vault.locationId, 10, "STAFF_PHARMACIST", null
                )
        );
        assertTrue(ex.getMessage().contains("ERR_DUAL_AUTH_REQUIRED"));

        // Pharmacist + Branch Manager dual authorization succeeds
        PosWarehouseLocationClient.BatchLocationAssignmentDto assign = client.assignBatchToLocation(
                tenantId, branchId, "bat-mor-01", "BAT-MOR-01", false, false, 25,
                vault.locationId, 10, "STAFF_PHARMACIST", "BRANCH_MANAGER"
        );
        assertEquals(10, assign.quantity);
    }

    @Test
    @DisplayName("Test 6: Capacity overflow prevention and location-to-location stock transfer")
    void testCapacityAndStockTransfer() {
        PosWarehouseLocationClient.WarehouseDto wh = client.createWarehouse(
                tenantId, branchId, "WH-TRF", "Transfer Facility", "স্থানান্তর সুবিধা",
                PosWarehouseLocationClient.WarehouseType.MAIN_DISPENSARY, false, "BRANCH_MANAGER"
        );

        PosWarehouseLocationClient.StorageLocationDto binA = client.createStorageLocation(
                tenantId, branchId, wh.warehouseId, "BIN-A", "Bin A", "বিন এ",
                PosWarehouseLocationClient.StorageLocationType.BIN, 20, false, null, null, "BRANCH_MANAGER"
        );

        PosWarehouseLocationClient.StorageLocationDto binB = client.createStorageLocation(
                tenantId, branchId, wh.warehouseId, "BIN-B", "Bin B", "বিন বি",
                PosWarehouseLocationClient.StorageLocationType.BIN, 20, false, null, null, "BRANCH_MANAGER"
        );

        // Fill Bin A with 15 units
        client.assignBatchToLocation(
                tenantId, branchId, "bat-para-01", "BAT-PARA-01", false, false, 50,
                binA.locationId, 15, "BRANCH_MANAGER", null
        );
        assertEquals(15, binA.currentPackCount);

        // Exceeding capacity (15 + 10 = 25 > 20) fails
        assertThrows(IllegalStateException.class, () ->
                client.assignBatchToLocation(
                        tenantId, branchId, "bat-para-01", "BAT-PARA-01", false, false, 50,
                        binA.locationId, 10, "BRANCH_MANAGER", null
                )
        );

        // Transfer 5 units from Bin A to Bin B
        client.transferBatchStock(
                tenantId, branchId, "bat-para-01", binA.locationId, binB.locationId, 5, "BRANCH_MANAGER"
        );
        assertEquals(10, binA.currentPackCount);
        assertEquals(5, binB.currentPackCount);
    }
}
