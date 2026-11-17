package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStockQuarantineTest
 * Architecture Decision: ADR-0206 (P206).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 */
public class PosStockQuarantineTest {

    private PosStockQuarantineClient client;

    @BeforeEach
    void setUp() {
        client = new PosStockQuarantineClient();
        // Active non-expired batch
        client.registerBatch(new PosStockQuarantineClient.BatchDto(
                "batch-act", "BATCH-ACT-01", LocalDate.now().plusDays(100), 50, new BigDecimal("100.00"), "SHELF-A1"
        ));
        // Expired batch
        client.registerBatch(new PosStockQuarantineClient.BatchDto(
                "batch-exp", "BATCH-EXP-01", LocalDate.now().minusDays(5), 30, new BigDecimal("80.00"), "SHELF-B2"
        ));
    }

    @Test
    @DisplayName("Manual quarantine moves batch and prohibits checkout sales")
    void testQuarantineBatchAndProhibitSale() {
        assertDoesNotThrow(() -> client.assertSaleAllowed("batch-act"));

        var record = client.quarantineBatch(
                "ten-01", "br-01", "batch-act",
                PosStockQuarantineClient.QuarantineReason.COLD_CHAIN_EXCURSION,
                "Temperature spike detected.", "QUARANTINE-COLD-BIN",
                "usr-pharm", "STAFF_PHARMACIST"
        );

        assertEquals(PosStockQuarantineClient.QuarantineStatus.QUARANTINED, record.status);
        assertEquals("QUARANTINE-COLD-BIN", client.getBatch("batch-act").storageLocation);
        assertTrue(client.getBatch("batch-act").isQuarantined);

        // Sale is now prohibited
        var ex = assertThrows(IllegalStateException.class, () -> client.assertSaleAllowed("batch-act"));
        assertTrue(ex.getMessage().contains("ERR_EXPIRED_STOCK_SALE_PROHIBITED"));
    }

    @Test
    @DisplayName("Automated sweep detects expired batches and quarantines them")
    void testSweepExpiredBatches() {
        List<PosStockQuarantineClient.QuarantineRecordDto> swept = client.sweepExpiredBatches("ten-01", "br-01", "usr-pharm", "STAFF_PHARMACIST");
        assertEquals(1, swept.size());
        assertEquals("BATCH-EXP-01", swept.get(0).batchNumber);
        assertTrue(client.getBatch("batch-exp").isQuarantined);
    }

    @Test
    @DisplayName("Manager approves disposal, sets batch quantity to zero")
    void testDisposalApproval() {
        var rec = client.quarantineBatch("ten-01", "br-01", "batch-exp", PosStockQuarantineClient.QuarantineReason.BATCH_EXPIRED, "Expired", "QUAR-ZONE", "usr-p", "STAFF_PHARMACIST");
        var actioned = client.approveDisposal(rec.quarantineId, PosStockQuarantineClient.QuarantineStatus.DISPOSED, "CERT-001", "Incinerated", "usr-mgr", "BRANCH_MANAGER");
        assertEquals(PosStockQuarantineClient.QuarantineStatus.DISPOSED, actioned.status);
        assertEquals(0, client.getBatch("batch-exp").quantityBaseUnits);
        assertEquals("DEPLETED", client.getBatch("batch-exp").status);
    }

    @Test
    @DisplayName("Release guard: expired batch cannot be released back to active stock")
    void testReleaseGuardExpiredBatch() {
        var rec = client.quarantineBatch("ten-01", "br-01", "batch-exp", PosStockQuarantineClient.QuarantineReason.BATCH_EXPIRED, "Expired", "QUAR-ZONE", "usr-p", "STAFF_PHARMACIST");
        var ex = assertThrows(IllegalStateException.class, () -> client.releaseQuarantine(rec.quarantineId, "Attempt release", "SHELF-A1", "usr-mgr", "BRANCH_MANAGER"));
        assertTrue(ex.getMessage().contains("ERR_EXPIRED_BATCH_CANNOT_BE_RELEASED"));
    }

    @Test
    @DisplayName("Cashier role is strictly denied from quarantine operations")
    void testCashierDenied() {
        var ex = assertThrows(SecurityException.class, () -> client.quarantineBatch("ten-01", "br-01", "batch-act", PosStockQuarantineClient.QuarantineReason.QUALITY_DEFECT, "Notes", "Q-ZONE", "usr-c", "CASHIER"));
        assertTrue(ex.getMessage().contains("ERR_STAFF_ROLE_DENIED"));
    }
}

