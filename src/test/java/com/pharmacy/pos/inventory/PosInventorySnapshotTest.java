package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosInventorySnapshotTest
 * Acceptance Unit Tests for P215: Inventory snapshots (Desktop POS).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 */
class PosInventorySnapshotTest {

    private PosInventorySnapshotClient client;

    @BeforeEach
    void setUp() {
        client = new PosInventorySnapshotClient();
    }

    @Test
    @DisplayName("P215-FX-1: Snapshot aggregates correctly and verifies mathematical reconciliation")
    void testValidSnapshotGeneration() {
        List<PosInventorySnapshotClient.SnapshotLineItem> lines = List.of(
                new PosInventorySnapshotClient.SnapshotLineItem(
                        "prod-ins", "Mixtard 30", "BATCH-01", "FRIDGE-01", 40, new BigDecimal("420.00"), true, 40
                ),
                new PosInventorySnapshotClient.SnapshotLineItem(
                        "prod-napa", "Napa Extra", "BATCH-02", "SHELF-A", 100, new BigDecimal("2.00"), false, 100
                )
        );

        PosInventorySnapshotClient.SnapshotCheckpoint snapshot = client.generateSnapshot(
                "STAFF_PHARMACIST", "ten-01", "br-01",
                PosInventorySnapshotClient.CheckpointType.DAILY_EOD, lines, 5
        );

        assertNotNull(snapshot);
        assertEquals(2, snapshot.getTotalProducts());
        assertEquals(2, snapshot.getTotalBatches());
        assertEquals(140, snapshot.getTotalQuantity());
        assertEquals(new BigDecimal("17000.00"), snapshot.getTotalValuation()); // 40*420 + 100*2 = 16800 + 200 = 17000
        assertTrue(snapshot.isReconciled());
        assertNotNull(snapshot.getSha256Checksum());
        assertEquals(64, snapshot.getSha256Checksum().length());
    }

    @Test
    @DisplayName("P215-FX-2: SHA-256 cryptographic integrity verification matches")
    void testCryptographicIntegrity() {
        List<PosInventorySnapshotClient.SnapshotLineItem> lines = List.of(
                new PosInventorySnapshotClient.SnapshotLineItem(
                        "prod-ins", "Mixtard 30", "BATCH-01", "FRIDGE-01", 40, new BigDecimal("420.00"), true, 40
                )
        );

        PosInventorySnapshotClient.SnapshotCheckpoint snapshot = client.generateSnapshot(
                "STAFF_PHARMACIST", "ten-01", "br-01",
                PosInventorySnapshotClient.CheckpointType.ON_DEMAND, lines, 2
        );

        assertTrue(client.verifyIntegrity(snapshot));
    }

    @Test
    @DisplayName("P215-FX-3: Discrepancy between batch balance and ledger delta is flagged as unreconciled")
    void testDiscrepancyDetection() {
        List<PosInventorySnapshotClient.SnapshotLineItem> lines = List.of(
                new PosInventorySnapshotClient.SnapshotLineItem(
                        "prod-ins", "Mixtard 30", "BATCH-01", "FRIDGE-01", 50, new BigDecimal("420.00"), true, 40 // Discrepancy = 10!
                )
        );

        PosInventorySnapshotClient.SnapshotCheckpoint snapshot = client.generateSnapshot(
                "STAFF_PHARMACIST", "ten-01", "br-01",
                PosInventorySnapshotClient.CheckpointType.CYCLE_COUNT, lines, 3
        );

        assertFalse(snapshot.isReconciled());
        assertEquals(10, snapshot.getLineItems().get(0).getDiscrepancy());
    }

    @Test
    @DisplayName("P215-FX-4: Cashier role is strictly rejected from generating snapshots")
    void testCashierDenied() {
        List<PosInventorySnapshotClient.SnapshotLineItem> lines = List.of();

        assertThrows(SecurityException.class, () ->
                client.generateSnapshot(
                        "CASHIER", "ten-01", "br-01",
                        PosInventorySnapshotClient.CheckpointType.DAILY_EOD, lines, 0
                )
        );
    }
}
