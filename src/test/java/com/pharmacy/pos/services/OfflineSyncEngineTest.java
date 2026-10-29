package com.pharmacy.pos.services;

import com.pharmacy.pos.models.CartItem;
import com.pharmacy.pos.models.SaleTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OfflineSyncEngine and offline receipt watermarks.
 * Architecture Decision: ADR-0003 & ADR-0023.
 */
class OfflineSyncEngineTest {

    private OfflineSyncEngine syncEngine;
    private CartItem sampleItem;

    @BeforeEach
    void setUp() {
        syncEngine = new OfflineSyncEngine();
        sampleItem = new CartItem(
                "med-insulin-01",
                "Mixtard 30 FlexPen 100IU/ml",
                "Human Insulin 30/70",
                "BOX",
                1,
                new BigDecimal("650.00"),
                "LOT-INS-2026-X",
                LocalDate.of(2027, 6, 30),
                BigDecimal.ZERO
        );
    }

    @Test
    @DisplayName("Enqueue offline transaction increases pending count")
    void testEnqueueOfflineTransaction() {
        SaleTransaction tx = new SaleTransaction(
                UUID.randomUUID().toString(),
                "INV-OFFLINE-001",
                "tenant-dhaka-01",
                "branch-dhanmondi",
                "cashier-01",
                Collections.singletonList(sampleItem),
                BigDecimal.ZERO,
                true,
                Instant.now()
        );

        syncEngine.enqueueTransaction(tx);
        assertEquals(1, syncEngine.getPendingCount());

        SaleTransaction drained = syncEngine.drainNext();
        assertNotNull(drained);
        assertEquals("INV-OFFLINE-001", drained.getInvoiceNumber());
        assertTrue(drained.isOfflineSyncPending());
        assertEquals(0, syncEngine.getPendingCount());
    }

    @Test
    @DisplayName("Offline transaction receipt contains mandatory bilingual watermark")
    void testOfflineReceiptWatermark() {
        SaleTransaction offlineTx = new SaleTransaction(
                UUID.randomUUID().toString(),
                "INV-OFFLINE-002",
                "tenant-dhaka-01",
                "branch-dhanmondi",
                "cashier-01",
                Collections.singletonList(sampleItem),
                BigDecimal.ZERO,
                true,
                Instant.now()
        );

        String receipt = offlineTx.generateReceiptText();
        assertTrue(receipt.contains(SaleTransaction.OFFLINE_WATERMARK_EN));
        assertTrue(receipt.contains(SaleTransaction.OFFLINE_WATERMARK_BN));
        assertTrue(receipt.contains("Mixtard 30 FlexPen"));
        assertTrue(receipt.contains("BDT     650.00"));
    }

    @Test
    @DisplayName("Online confirmed transaction does not print offline watermark")
    void testOnlineReceiptNoOfflineWatermark() {
        SaleTransaction onlineTx = new SaleTransaction(
                UUID.randomUUID().toString(),
                "INV-ONLINE-001",
                "tenant-dhaka-01",
                "branch-dhanmondi",
                "cashier-01",
                Collections.singletonList(sampleItem),
                BigDecimal.ZERO,
                false,
                Instant.now()
        );

        String receipt = onlineTx.generateReceiptText();
        assertFalse(receipt.contains(SaleTransaction.OFFLINE_WATERMARK_EN));
        assertFalse(receipt.contains(SaleTransaction.OFFLINE_WATERMARK_BN));
        assertTrue(receipt.contains("Mixtard 30 FlexPen"));
    }
}

