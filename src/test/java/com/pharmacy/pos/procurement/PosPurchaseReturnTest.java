package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosPurchaseReturnTest
 * Architecture Decision: ADR-0191 (P191).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 */
public class PosPurchaseReturnTest {

    private PosPurchaseReturnClient managerClient;
    private PosPurchaseReturnClient cashierClient;

    private Map<String, PosPurchaseReturnClient.PurchaseReturnDto> returnStore;
    private Map<String, PosPurchaseReturnClient.BatchStockDto> batchStore;
    private Map<String, PosPurchaseReturnClient.SupplierLedgerEntryDto> ledgerStore;

    @BeforeEach
    void setUp() {
        returnStore = new HashMap<>();
        batchStore = new HashMap<>();
        ledgerStore = new HashMap<>();

        // Pre-populate batch stock: 100 units @ ৳450.00
        batchStore.put("bat-ins-1", new PosPurchaseReturnClient.BatchStockDto(
                "bat-ins-1",
                "BATCH-INS-2026",
                "med-ins-1",
                100,
                new BigDecimal("450.0000"),
                "ACTIVE"
        ));

        // Pre-populate supplier ledger with existing AP balance of ৳50,000
        ledgerStore.put("sup-post-init", new PosPurchaseReturnClient.SupplierLedgerEntryDto(
                "sup-post-init",
                "sup-square",
                "INVOICE_BILL",
                "INV-001",
                BigDecimal.ZERO,
                new BigDecimal("50000.0000"),
                new BigDecimal("50000.0000"),
                Instant.now()
        ));

        managerClient = new PosPurchaseReturnClient(
                "tenant-sq",
                "branch-1",
                "BRANCH_MANAGER",
                "usr-mgr-1",
                false,
                returnStore,
                batchStore,
                ledgerStore
        );

        cashierClient = new PosPurchaseReturnClient(
                "tenant-sq",
                "branch-1",
                "CASHIER",
                "usr-csh-1",
                false,
                returnStore,
                batchStore,
                ledgerStore
        );
    }

    @Test
    @DisplayName("P191-01: Create draft purchase return and post debit note to AP ledger")
    void testCreateAndPostPurchaseReturnSuccess() {
        List<PosPurchaseReturnClient.PurchaseReturnItemDto> items = List.of(
                new PosPurchaseReturnClient.PurchaseReturnItemDto(
                        null, "med-ins-1", "Insulin Regular", "bat-ins-1", "lot-1",
                        "BATCH-INS-2026", 20, new BigDecimal("450.0000"),
                        null, "DAMAGED_DELIVERY", "Damaged in transit"
                )
        );

        PosPurchaseReturnClient.PurchaseReturnDto pret = managerClient.createPurchaseReturn(
                "sup-square", "Square Pharma", "grn-1", "po-1",
                "DAMAGED_DELIVERY", "RETURNED_TO_VENDOR", "20 broken vials",
                "DN-2026-001", items, false
        );

        assertNotNull(pret);
        assertEquals("DRAFT", pret.status);
        assertEquals(20, pret.totalReturnQuantity);
        assertEquals(new BigDecimal("9000.0000"), pret.totalDebitAmount);

        // Post Return
        PosPurchaseReturnClient.PurchaseReturnDto posted = managerClient.postPurchaseReturn(pret.returnId);
        assertEquals("POSTED_DEBIT_NOTE", posted.status);
        assertNotNull(posted.postedAt);

        // Verify batch inventory decremented: 100 - 20 = 80
        PosPurchaseReturnClient.BatchStockDto batch = batchStore.get("bat-ins-1");
        assertEquals(80, batch.availableQuantity);
        assertEquals("ACTIVE", batch.status);

        // Verify AP debit note: 50,000 - 9,000 = 41,000
        PosPurchaseReturnClient.SupplierLedgerEntryDto ledgerEntry = ledgerStore.get(posted.debitLedgerEntryId);
        assertNotNull(ledgerEntry);
        assertEquals("RETURN_DEBIT_NOTE", ledgerEntry.postingType);
        assertEquals(new BigDecimal("9000.0000"), ledgerEntry.debitAmount);
        assertEquals(new BigDecimal("41000.0000"), ledgerEntry.balanceAfter);
    }

    @Test
    @DisplayName("P191-02: Insufficient stock guard throws ERR_INSUFFICIENT_STOCK_FOR_RETURN")
    void testInsufficientStockThrowsException() {
        List<PosPurchaseReturnClient.PurchaseReturnItemDto> items = List.of(
                new PosPurchaseReturnClient.PurchaseReturnItemDto(
                        null, "med-ins-1", "Insulin Regular", "bat-ins-1", "lot-1",
                        "BATCH-INS-2026", 150, new BigDecimal("450.0000"),
                        null, "EXPIRED_STOCK", "Over-quantity return"
                )
        );

        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                managerClient.createPurchaseReturn(
                        "sup-square", "Square Pharma", "grn-1", "po-1",
                        "EXPIRED_STOCK", "RETURNED_TO_VENDOR", "Exceeds available",
                        "DN-2026-002", items, false
                )
        );
        assertTrue(ex.getMessage().contains("ERR_INSUFFICIENT_STOCK_FOR_RETURN"));
    }

    @Test
    @DisplayName("P191-03: Complete batch return transitions batch status to DEPLETED")
    void testCompleteLotReturnMarksDepleted() {
        List<PosPurchaseReturnClient.PurchaseReturnItemDto> items = List.of(
                new PosPurchaseReturnClient.PurchaseReturnItemDto(
                        null, "med-ins-1", "Insulin Regular", "bat-ins-1", "lot-1",
                        "BATCH-INS-2026", 100, new BigDecimal("450.0000"),
                        null, "RECALLED_DRUG", "DGDA recall"
                )
        );

        PosPurchaseReturnClient.PurchaseReturnDto pret = managerClient.createPurchaseReturn(
                "sup-square", "Square Pharma", "grn-1", "po-1",
                "RECALLED_DRUG", "RETURNED_TO_VENDOR", "100% batch return",
                "DN-2026-003", items, true // autoPost = true
        );

        assertEquals("POSTED_DEBIT_NOTE", pret.status);

        PosPurchaseReturnClient.BatchStockDto batch = batchStore.get("bat-ins-1");
        assertEquals(0, batch.availableQuantity);
        assertEquals("DEPLETED", batch.status);
    }

    @Test
    @DisplayName("P191-04: Cancel draft return transitions status to CANCELLED")
    void testCancelDraftReturn() {
        List<PosPurchaseReturnClient.PurchaseReturnItemDto> items = List.of(
                new PosPurchaseReturnClient.PurchaseReturnItemDto(
                        null, "med-ins-1", "Insulin Regular", "bat-ins-1", "lot-1",
                        "BATCH-INS-2026", 10, new BigDecimal("450.0000"),
                        null, "DAMAGED_DELIVERY", "Minor box damage"
                )
        );

        PosPurchaseReturnClient.PurchaseReturnDto pret = managerClient.createPurchaseReturn(
                "sup-square", "Square Pharma", "grn-1", "po-1",
                "DAMAGED_DELIVERY", "RETURNED_TO_VENDOR", "Draft return",
                "DN-2026-004", items, false
        );

        PosPurchaseReturnClient.PurchaseReturnDto cancelled = managerClient.cancelPurchaseReturn(
                pret.returnId, "Resolved with replacement"
        );
        assertEquals("CANCELLED", cancelled.status);
        assertTrue(cancelled.reasonNotes.contains("Resolved with replacement"));

        // Verify batch stock is untouched
        assertEquals(100, batchStore.get("bat-ins-1").availableQuantity);
    }

    @Test
    @DisplayName("P191-05: Cannot cancel an already posted return")
    void testCannotCancelPostedReturn() {
        List<PosPurchaseReturnClient.PurchaseReturnItemDto> items = List.of(
                new PosPurchaseReturnClient.PurchaseReturnItemDto(
                        null, "med-ins-1", "Insulin Regular", "bat-ins-1", "lot-1",
                        "BATCH-INS-2026", 10, new BigDecimal("450.0000"),
                        null, "DAMAGED_DELIVERY", "Damage"
                )
        );

        PosPurchaseReturnClient.PurchaseReturnDto pret = managerClient.createPurchaseReturn(
                "sup-square", "Square Pharma", "grn-1", "po-1",
                "DAMAGED_DELIVERY", "RETURNED_TO_VENDOR", "Posted return",
                "DN-2026-005", items, true
        );

        Exception ex = assertThrows(IllegalStateException.class, () ->
                managerClient.cancelPurchaseReturn(pret.returnId, "Should fail")
        );
        assertTrue(ex.getMessage().contains("ERR_CANNOT_CANCEL_POSTED_RETURN"));
    }

    @Test
    @DisplayName("P191-06: Cashier role is barred with ERR_STAFF_ROLE_DENIED")
    void testCashierRoleForbidden() {
        List<PosPurchaseReturnClient.PurchaseReturnItemDto> items = List.of(
                new PosPurchaseReturnClient.PurchaseReturnItemDto(
                        null, "med-ins-1", "Insulin Regular", "bat-ins-1", "lot-1",
                        "BATCH-INS-2026", 5, new BigDecimal("450.0000"),
                        null, "DAMAGED_DELIVERY", "Damage"
                )
        );

        Exception ex = assertThrows(SecurityException.class, () ->
                cashierClient.createPurchaseReturn(
                        "sup-square", "Square Pharma", "grn-1", "po-1",
                        "DAMAGED_DELIVERY", "RETURNED_TO_VENDOR", "Cashier attempt",
                        "DN-2026-006", items, false
                )
        );
        assertTrue(ex.getMessage().contains("ERR_STAFF_ROLE_DENIED"));
    }
}

