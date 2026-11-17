package com.pharmacy.pos.offline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P238 - POS Offline Transaction Atomicity & SQLite Outbox Tests")
class PosOfflineTransactionRepositoryTest {

    private PosOfflineTransactionRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        repository = PosOfflineTransactionRepository.inMemory();
        // Seed 100 tablets of Cef-3 200mg (BX-001)
        repository.seedBatchStock(
                "batch-cef-001",
                "prod-cef-200",
                "BX-202611-01",
                "2027-11-16",
                100,
                new BigDecimal("30.00"),
                new BigDecimal("40.00")
        );
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    @DisplayName("commitOfflineSaleAtomic commits sale, lines, stock deduction, and outbox in single transaction")
    void testSuccessfulAtomicOfflineSale() throws SQLException {
        PosOfflineTransactionRepository.OfflineSaleLineCmd line = new PosOfflineTransactionRepository.OfflineSaleLineCmd(
                "line-001",
                "prod-cef-200",
                "batch-cef-001",
                "BX-202611-01",
                15, // 15 tablets
                new BigDecimal("40.00"),
                new BigDecimal("600.00")
        );

        PosOfflineTransactionRepository.OfflineSaleCommand cmd = new PosOfflineTransactionRepository.OfflineSaleCommand(
                "sale-off-001",
                "OFF-DH01-20261116-0001",
                "ten-001",
                "br-001",
                "DEV-POS-1",
                "cashier-001",
                new BigDecimal("600.00"),
                new BigDecimal("600.00"),
                BigDecimal.ZERO,
                "CASH",
                List.of(line)
        );

        String outboxId = repository.commitOfflineSaleAtomic(cmd);
        assertNotNull(outboxId);

        // 1. Verify stock deducted: 100 - 15 = 85
        assertEquals(85, repository.getAvailableStock("batch-cef-001"));

        // 2. Verify sale order committed
        assertTrue(repository.hasSaleOrder("sale-off-001"));

        // 3. Verify durable outbox event pending
        List<PosOfflineTransactionRepository.OutboxEventDto> pending = repository.getPendingOutboxEvents();
        assertEquals(1, pending.size());
        assertEquals("POS_SALE_COMMITTED", pending.get(0).eventType);
        assertEquals("sale-off-001", pending.get(0).aggregateId);
        assertTrue(pending.get(0).payloadJson.contains("OFF-DH01-20261116-0001"));

        // 4. Mark outbox synced
        repository.markOutboxSynced(outboxId);
        assertTrue(repository.getPendingOutboxEvents().isEmpty());
    }

    @Test
    @DisplayName("commitOfflineSaleAtomic rolls back entire transaction when stock is insufficient")
    void testRollbackOnInsufficientStock() throws SQLException {
        // Attempt to sell 150 tablets when only 100 are available
        PosOfflineTransactionRepository.OfflineSaleLineCmd line = new PosOfflineTransactionRepository.OfflineSaleLineCmd(
                "line-over-001",
                "prod-cef-200",
                "batch-cef-001",
                "BX-202611-01",
                150,
                new BigDecimal("40.00"),
                new BigDecimal("6000.00")
        );

        PosOfflineTransactionRepository.OfflineSaleCommand cmd = new PosOfflineTransactionRepository.OfflineSaleCommand(
                "sale-off-fail-001",
                "OFF-DH01-20261116-0002",
                "ten-001",
                "br-001",
                "DEV-POS-1",
                "cashier-001",
                new BigDecimal("6000.00"),
                new BigDecimal("6000.00"),
                BigDecimal.ZERO,
                "CASH",
                List.of(line)
        );

        assertThrows(PosOfflineTransactionRepository.InsufficientLocalStockException.class, () -> {
            repository.commitOfflineSaleAtomic(cmd);
        });

        // CRITICAL ATOMICITY INVARIANT:
        // 1. Stock must remain exactly 100 (unmodified)
        assertEquals(100, repository.getAvailableStock("batch-cef-001"));

        // 2. Sale order must NOT exist in SQLite database
        assertFalse(repository.hasSaleOrder("sale-off-fail-001"));

        // 3. Outbox queue must remain completely empty (no orphaned outbox events)
        assertTrue(repository.getPendingOutboxEvents().isEmpty());
    }

    @Test
    @DisplayName("commitOfflineSaleAtomic rolls back on duplicate order_number collision without stock drift")
    void testRollbackOnDuplicateOrderNumber() throws SQLException {
        PosOfflineTransactionRepository.OfflineSaleLineCmd line1 = new PosOfflineTransactionRepository.OfflineSaleLineCmd(
                "line-dup-001",
                "prod-cef-200",
                "batch-cef-001",
                "BX-202611-01",
                10,
                new BigDecimal("40.00"),
                new BigDecimal("400.00")
        );

        PosOfflineTransactionRepository.OfflineSaleCommand cmd1 = new PosOfflineTransactionRepository.OfflineSaleCommand(
                "sale-dup-001",
                "OFF-COLLISION-001",
                "ten-001",
                "br-001",
                "DEV-POS-1",
                "cashier-001",
                new BigDecimal("400.00"),
                new BigDecimal("400.00"),
                BigDecimal.ZERO,
                "CASH",
                List.of(line1)
        );

        repository.commitOfflineSaleAtomic(cmd1);
        assertEquals(90, repository.getAvailableStock("batch-cef-001"));

        // Second sale with same order_number must fail due to UNIQUE constraint
        PosOfflineTransactionRepository.OfflineSaleLineCmd line2 = new PosOfflineTransactionRepository.OfflineSaleLineCmd(
                "line-dup-002",
                "prod-cef-200",
                "batch-cef-001",
                "BX-202611-01",
                20,
                new BigDecimal("40.00"),
                new BigDecimal("800.00")
        );

        PosOfflineTransactionRepository.OfflineSaleCommand cmd2 = new PosOfflineTransactionRepository.OfflineSaleCommand(
                "sale-dup-002",
                "OFF-COLLISION-001", // Duplicate order number!
                "ten-001",
                "br-001",
                "DEV-POS-1",
                "cashier-001",
                new BigDecimal("800.00"),
                new BigDecimal("800.00"),
                BigDecimal.ZERO,
                "CASH",
                List.of(line2)
        );

        assertThrows(SQLException.class, () -> {
            repository.commitOfflineSaleAtomic(cmd2);
        });

        // Invariant: Stock must remain at 90 (the 20 units from cmd2 were rolled back)
        assertEquals(90, repository.getAvailableStock("batch-cef-001"));

        // Sale 2 must not exist
        assertFalse(repository.hasSaleOrder("sale-dup-002"));

        // Outbox contains only the 1 event from sale 1
        assertEquals(1, repository.getPendingOutboxEvents().size());
    }
}
