package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStoragePolicyTest
 * Acceptance Unit Tests for P213: Storage policy enforcement (Desktop POS).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 */
class PosStoragePolicyTest {

    private PosStoragePolicyClient client;
    private PosStoragePolicyClient.StorageLocationInfo fridge;
    private PosStoragePolicyClient.StorageLocationInfo shelf;

    @BeforeEach
    void setUp() {
        client = new PosStoragePolicyClient();
        fridge = new PosStoragePolicyClient.StorageLocationInfo(
                "loc-fridge-01",
                "br-central-01",
                "FRIDGE-01",
                "Vaccine Refrigerator",
                PosStoragePolicyClient.LocationType.FRIDGE,
                true,
                new BigDecimal("2.00"),
                new BigDecimal("8.00")
        );
        shelf = new PosStoragePolicyClient.StorageLocationInfo(
                "loc-shelf-01",
                "br-central-01",
                "SHELF-A",
                "General Shelf A",
                PosStoragePolicyClient.LocationType.SHELF,
                false,
                null,
                null
        );
    }

    @Test
    @DisplayName("P213-FX-1: Cold-chain medicine assigned to cold-capable fridge succeeds")
    void testValidColdChainAssignment() {
        PosStoragePolicyClient.BatchAssignmentItem batch = new PosStoragePolicyClient.BatchAssignmentItem(
                "bat-01", "BATCH-INS-01", "Insulin Glargine 100IU", true, "UNASSIGNED", 50
        );

        PosStoragePolicyClient.BatchAssignmentItem result = client.relocateBatch(
                "STAFF_PHARMACIST", batch, fridge, ""
        );

        assertEquals("FRIDGE-01", result.getStorageLocationCode());
    }

    @Test
    @DisplayName("P213-FX-2: Cold-chain medicine assigned to ambient shelf throws IllegalArgumentException")
    void testColdChainToAmbientShelfRejected() {
        PosStoragePolicyClient.BatchAssignmentItem batch = new PosStoragePolicyClient.BatchAssignmentItem(
                "bat-01", "BATCH-INS-01", "Insulin Glargine 100IU", true, "FRIDGE-01", 50
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                client.relocateBatch("STAFF_PHARMACIST", batch, shelf, "")
        );
        assertTrue(ex.getMessage().contains("Storage policy violation"));
        assertTrue(ex.getMessage().contains("requires cold-chain"));
    }

    @Test
    @DisplayName("P213-FX-3: Policy override allowed with documented clinical justification")
    void testPolicyOverrideWithJustification() {
        PosStoragePolicyClient.BatchAssignmentItem batch = new PosStoragePolicyClient.BatchAssignmentItem(
                "bat-01", "BATCH-INS-01", "Insulin Glargine 100IU", true, "FRIDGE-01", 10
        );

        PosStoragePolicyClient.BatchAssignmentItem result = client.relocateBatch(
                "STAFF_PHARMACIST", batch, shelf, "Room temperature transit approved for under 4 hours."
        );
        assertEquals("SHELF-A", result.getStorageLocationCode());
    }

    @Test
    @DisplayName("P213-FX-4: Cashier role is strictly forbidden from relocating stock")
    void testCashierRelocationDenied() {
        PosStoragePolicyClient.BatchAssignmentItem batch = new PosStoragePolicyClient.BatchAssignmentItem(
                "bat-01", "BATCH-INS-01", "Insulin Glargine 100IU", true, "FRIDGE-01", 50
        );

        assertThrows(SecurityException.class, () ->
                client.relocateBatch("CASHIER", batch, fridge, "")
        );
    }

    @Test
    @DisplayName("P213-FX-5: Branch storage compliance audit correctly flags violations")
    void testBranchAuditCompliance() {
        List<PosStoragePolicyClient.BatchAssignmentItem> batches = List.of(
                new PosStoragePolicyClient.BatchAssignmentItem("b1", "B-01", "Insulin", true, "FRIDGE-01", 30),
                new PosStoragePolicyClient.BatchAssignmentItem("b2", "B-02", "Paracetamol", false, "SHELF-A", 100),
                new PosStoragePolicyClient.BatchAssignmentItem("b3", "B-03", "Rabies Vaccine", true, "SHELF-A", 20) // Violation!
        );

        Map<String, PosStoragePolicyClient.StorageLocationInfo> locations = Map.of(
                "FRIDGE-01", fridge,
                "SHELF-A", shelf
        );

        Map<String, Object> audit = client.auditBranchCompliance(batches, locations);
        assertEquals(3, audit.get("totalBatches"));
        assertEquals(2, audit.get("compliantCount"));
        assertEquals(1, audit.get("violationCount"));
    }
}

