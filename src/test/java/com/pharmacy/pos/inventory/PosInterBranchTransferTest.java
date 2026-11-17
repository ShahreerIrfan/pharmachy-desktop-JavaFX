package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosInterBranchTransferTest
 * Architecture Decision: ADR-0208, ADR-0209, ADR-0210 (P210).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 */
public class PosInterBranchTransferTest {

    private PosInterBranchTransferClient client;

    @BeforeEach
    void setUp() {
        client = new PosInterBranchTransferClient();
        client.registerBatchStock(new PosInterBranchTransferClient.BatchStockDto(
                "batch-source-01", "branch-main", "med-cefixime", "BATCH-CEF-001",
                "LOT-CEF-2026-X1", "2027-12-31", 100, new BigDecimal("20.0000")
        ));
    }

    @Test
    @DisplayName("Requesting transfer reserves stock on sender batch and computes markup cost")
    void testRequestTransferReservesStockAndAppliesMarkup() {
        var item = new PosInterBranchTransferClient.TransferItemDto(
                null, "batch-source-01", "med-cefixime", "Cefixime 200mg", "BATCH-CEF-001",
                "LOT-CEF-2026-X1", "2027-12-31", 40, new BigDecimal("20.0000"), null
        );

        var transfer = client.requestTransfer(
                "tenant-001", "branch-main", "branch-dhanmondi", "usr-pharm", "PHARMACIST",
                PosInterBranchTransferClient.TransferPriority.URGENT,
                PosInterBranchTransferClient.TransferCostPolicy.LANDED_MARKUP,
                new BigDecimal("10.00"), BigDecimal.ZERO, // 10% markup -> 22.0000
                Collections.singletonList(item), "Urgent stock required"
        );

        assertNotNull(transfer.transferId);
        assertEquals(PosInterBranchTransferClient.TransferStatus.REQUESTED, transfer.status);
        var batch = client.getBatchStock("batch-source-01");
        assertEquals(40, batch.reservedQuantityBaseUnits);
        assertEquals(60, batch.getAvailableQuantity());

        // Transferred unit cost has 10% markup applied
        assertEquals(new BigDecimal("22.0000"), item.transferredCostPerUnit);
        assertEquals(new BigDecimal("20.0000"), item.originalCostPerUnit);
    }

    @Test
    @DisplayName("Lot cost continuity & weighted average cost calculation")
    void testLotCostContinuityAndWeightedAverageCost() {
        var item = new PosInterBranchTransferClient.TransferItemDto(
                "item-001", "batch-source-01", "med-cefixime", "Cefixime 200mg", "BATCH-CEF-001",
                "LOT-CEF-2026-X1", "2027-12-31", 50, new BigDecimal("20.0000"), null
        );
        var transfer = client.requestTransfer(
                "tenant-001", "branch-main", "branch-gulshan", "usr-mgr", "BRANCH_MANAGER",
                PosInterBranchTransferClient.TransferPriority.STANDARD,
                PosInterBranchTransferClient.TransferCostPolicy.LANDED_MARKUP,
                new BigDecimal("10.00"), BigDecimal.ZERO, // 20.00 + 10% = 22.00
                Collections.singletonList(item), "Stock replenishment"
        );
        client.approveTransfer(transfer.transferId, "usr-approver", "OWNER");
        client.dispatchTransfer(transfer.transferId, "usr-dispatcher", "PHARMACIST");

        // Destination batch already has 50 units @ 20.00
        Map<String, PosInterBranchTransferClient.BatchStockDto> goodBatches = new HashMap<>();
        var destGoodBatch = new PosInterBranchTransferClient.BatchStockDto(
                "batch-dest-good", "branch-gulshan", "med-cefixime", "BATCH-CEF-001",
                "LOT-CEF-2026-X1", "2027-12-31", 50, new BigDecimal("20.0000")
        );
        goodBatches.put("med-cefixime", destGoodBatch);

        var reconLine = new PosInterBranchTransferClient.ReconciliationLineDto(
                "item-001", 50, 0, 0, "", "QUARANTINE-DAMAGED"
        );

        var reconciled = client.reconcileTransfer(
                transfer.transferId, "usr-receiver", "PHARMACIST",
                Collections.singletonList(reconLine), "Pharmacist inspection completed",
                goodBatches, null
        );

        assertEquals(PosInterBranchTransferClient.TransferStatus.RECEIVED, reconciled.status);
        // Total units = 50 + 50 = 100
        assertEquals(100, destGoodBatch.quantityBaseUnits);
        // Weighted average: (50*20 + 50*22) / 100 = 21.0000
        assertEquals(new BigDecimal("21.0000"), destGoodBatch.costPerUnit);
        assertEquals("batch-source-01", destGoodBatch.originBatchId);
        assertEquals("branch-main", destGoodBatch.originBranchId);
    }

    @Test
    @DisplayName("Expiry alteration prohibited: Conflicting expiry date throws IllegalStateException")
    void testExpiryDateAlterationProhibited() {
        var item = new PosInterBranchTransferClient.TransferItemDto(
                "item-002", "batch-source-01", "med-cefixime", "Cefixime 200mg", "BATCH-CEF-001",
                "LOT-CEF-2026-X1", "2027-12-31", 50, new BigDecimal("20.0000"), null
        );
        var transfer = client.requestTransfer(
                "tenant-001", "branch-main", "branch-uttara", "usr-mgr", "PHARMACIST",
                PosInterBranchTransferClient.TransferPriority.STANDARD,
                PosInterBranchTransferClient.TransferCostPolicy.AT_COST,
                BigDecimal.ZERO, BigDecimal.ZERO,
                Collections.singletonList(item), "Test"
        );
        client.approveTransfer(transfer.transferId, "usr-approver", "OWNER");
        client.dispatchTransfer(transfer.transferId, "usr-dispatcher", "PHARMACIST");

        // Destination batch has conflicting expiry date!
        Map<String, PosInterBranchTransferClient.BatchStockDto> goodBatches = new HashMap<>();
        var conflictBatch = new PosInterBranchTransferClient.BatchStockDto(
                "batch-conflict", "branch-uttara", "med-cefixime", "BATCH-CEF-001",
                "LOT-CEF-2026-X1", "2028-06-30", 10, new BigDecimal("20.0000") // Different expiry!
        );
        goodBatches.put("med-cefixime", conflictBatch);

        var reconLine = new PosInterBranchTransferClient.ReconciliationLineDto(
                "item-002", 50, 0, 0, "", "QUARANTINE-DAMAGED"
        );

        assertThrows(IllegalStateException.class, () -> client.reconcileTransfer(
                transfer.transferId, "usr-receiver", "PHARMACIST",
                Collections.singletonList(reconLine), "Expiry test",
                goodBatches, null
        ));
    }

    @Test
    @DisplayName("Duplicate reconciliation is prevented")
    void testDuplicateReconciliationPrevented() {
        var item = new PosInterBranchTransferClient.TransferItemDto(
                "item-003", "batch-source-01", "med-cefixime", "Cefixime 200mg", "BATCH-CEF-001",
                "LOT-CEF-2026-X1", "2027-12-31", 20, new BigDecimal("20.0000"), null
        );
        var transfer = client.requestTransfer(
                "tenant-001", "branch-main", "branch-uttara", "usr-mgr", "PHARMACIST",
                PosInterBranchTransferClient.TransferPriority.STANDARD,
                PosInterBranchTransferClient.TransferCostPolicy.AT_COST,
                BigDecimal.ZERO, BigDecimal.ZERO,
                Collections.singletonList(item), "Test"
        );
        client.approveTransfer(transfer.transferId, "usr-approver", "OWNER");
        client.dispatchTransfer(transfer.transferId, "usr-dispatcher", "PHARMACIST");

        var reconLine = new PosInterBranchTransferClient.ReconciliationLineDto(
                "item-003", 20, 0, 0, "", "QUARANTINE-DAMAGED"
        );

        client.reconcileTransfer(
                transfer.transferId, "usr-receiver", "PHARMACIST",
                Collections.singletonList(reconLine), "First receipt",
                null, null
        );

        // Re-receiving must fail
        assertThrows(IllegalStateException.class, () -> client.reconcileTransfer(
                transfer.transferId, "usr-receiver", "PHARMACIST",
                Collections.singletonList(reconLine), "Duplicate receipt attempt",
                null, null
        ));
    }

    @Test
    @DisplayName("Cashier role cannot create transfers")
    void testCashierRoleRejected() {
        var item = new PosInterBranchTransferClient.TransferItemDto(
                "item-004", "batch-source-01", "med-cefixime", "Cefixime 200mg", "BATCH-CEF-001",
                "LOT-CEF-2026-X1", "2027-12-31", 10, new BigDecimal("20.0000"), null
        );

        assertThrows(SecurityException.class, () -> client.requestTransfer(
                "tenant-001", "branch-main", "branch-uttara", "usr-cashier", "CASHIER",
                PosInterBranchTransferClient.TransferPriority.STANDARD,
                PosInterBranchTransferClient.TransferCostPolicy.AT_COST,
                BigDecimal.ZERO, BigDecimal.ZERO,
                Collections.singletonList(item), "Cashier attempt"
        ));
    }
}
