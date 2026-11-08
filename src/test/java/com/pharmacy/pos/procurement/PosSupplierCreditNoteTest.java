package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosSupplierCreditNoteTest
 * Architecture Decision: ADR-0192 (P192).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 */
public class PosSupplierCreditNoteTest {

    private PosSupplierCreditNoteClient managerClient;
    private PosSupplierCreditNoteClient cashierClient;

    private Map<String, PosSupplierCreditNoteClient.SupplierCreditNoteDto> noteStore;
    private Map<String, PosSupplierCreditNoteClient.SupplierLedgerEntryDto> ledgerStore;

    @BeforeEach
    void setUp() {
        noteStore = new HashMap<>();
        ledgerStore = new HashMap<>();

        // Seed initial AP balance of ৳30,000
        ledgerStore.put("sup-post-init", new PosSupplierCreditNoteClient.SupplierLedgerEntryDto(
                "sup-post-init",
                "sup-square",
                "GRN_INVOICE",
                "INV-001",
                BigDecimal.ZERO,
                new BigDecimal("30000.0000"),
                new BigDecimal("30000.0000"),
                Instant.now()
        ));

        managerClient = new PosSupplierCreditNoteClient(
                "tenant-sq",
                "branch-1",
                "BRANCH_MANAGER",
                "usr-mgr-1",
                false,
                noteStore,
                ledgerStore
        );

        cashierClient = new PosSupplierCreditNoteClient(
                "tenant-sq",
                "branch-1",
                "CASHIER",
                "usr-csh-1",
                false,
                noteStore,
                ledgerStore
        );
    }

    @Test
    @DisplayName("P192-01: Create and post credit note reduces AP balance")
    void testCreateAndPostCreditNoteDecrementsAPBalance() {
        List<PosSupplierCreditNoteClient.SupplierCreditNoteItemDto> items = List.of(
                new PosSupplierCreditNoteClient.SupplierCreditNoteItemDto(
                        null, "med-1", "Seclo 20mg", 100,
                        new BigDecimal("0.5000"), new BigDecimal("5.00"),
                        null, "Discount"
                )
        );

        PosSupplierCreditNoteClient.SupplierCreditNoteDto scn = managerClient.createCreditNote(
                "sup-square", "Square Pharma", "VCN-001", "PRICE_DIFFERENCE",
                "INVOICE", "INV-889", LocalDate.now(), null, items, false
        );

        assertNotNull(scn);
        assertEquals("DRAFT", scn.status);
        assertEquals(new BigDecimal("50.0000"), scn.subtotalAmount);
        assertEquals(new BigDecimal("2.5000"), scn.taxAmount);
        assertEquals(new BigDecimal("52.5000"), scn.totalCreditAmount);

        // Post Credit Note
        PosSupplierCreditNoteClient.SupplierCreditNoteDto posted = managerClient.postCreditNote(scn.noteId);
        assertEquals("POSTED", posted.status);
        assertNotNull(posted.postedAt);

        // Verify AP Ledger: 30,000 - 52.50 = 29,947.50
        PosSupplierCreditNoteClient.SupplierLedgerEntryDto entry = ledgerStore.get(posted.debitLedgerEntryId);
        assertNotNull(entry);
        assertEquals("SUPPLIER_CREDIT_NOTE", entry.postingType);
        assertEquals(new BigDecimal("52.5000"), entry.debitAmount);
        assertEquals(new BigDecimal("29947.5000"), entry.balanceAfter);
    }

    @Test
    @DisplayName("P192-02: Non-destructive reversal restores AP balance")
    void testNonDestructiveReversalRestoresAPBalance() {
        PosSupplierCreditNoteClient.SupplierCreditNoteDto scn = managerClient.createCreditNote(
                "sup-square", "Square Pharma", "VCN-REV-001", "VOLUME_REBATE",
                "GENERAL_COMMERCIAL", "AGR-2026", LocalDate.now(),
                new BigDecimal("5000.0000"), null, true // autoPost = true
        );

        assertEquals("POSTED", scn.status);
        // AP balance after posting: 30,000 - 5,000 = 25,000

        // Reverse credit note
        PosSupplierCreditNoteClient.SupplierCreditNoteDto reversed = managerClient.reverseCreditNote(
                scn.noteId, "Turnover rebate applied to wrong branch ledger"
        );

        assertEquals("REVERSED", reversed.status);
        assertEquals("Turnover rebate applied to wrong branch ledger", reversed.reversalReason);
        assertNotNull(reversed.reversalPostedAt);

        // Verify AP ledger reversal: 25,000 + 5,000 = 30,000
        PosSupplierCreditNoteClient.SupplierLedgerEntryDto revEntry = ledgerStore.get(reversed.reversalLedgerEntryId);
        assertNotNull(revEntry);
        assertEquals("CREDIT_NOTE_REVERSAL", revEntry.postingType);
        assertEquals(new BigDecimal("5000.0000"), revEntry.creditAmount);
        assertEquals(new BigDecimal("30000.0000"), revEntry.balanceAfter);
    }

    @Test
    @DisplayName("P192-03: Duplicate vendor reference throws ERR_DUPLICATE_CREDIT_NOTE_REF")
    void testDuplicateVendorRefThrowsException() {
        managerClient.createCreditNote(
                "sup-square", "Square Pharma", "VCN-DUP", "PRICE_DIFFERENCE",
                "INVOICE", "INV-1", LocalDate.now(), new BigDecimal("100.0000"), null, false
        );

        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                managerClient.createCreditNote(
                        "sup-square", "Square Pharma", "VCN-DUP", "PRICE_DIFFERENCE",
                        "INVOICE", "INV-2", LocalDate.now(), new BigDecimal("200.0000"), null, false
                )
        );
        assertTrue(ex.getMessage().contains("ERR_DUPLICATE_CREDIT_NOTE_REF"));
    }

    @Test
    @DisplayName("P192-04: Reversal requires mandatory reason")
    void testReversalWithoutReasonThrowsException() {
        PosSupplierCreditNoteClient.SupplierCreditNoteDto scn = managerClient.createCreditNote(
                "sup-square", "Square Pharma", "VCN-NOREAS", "PRICE_DIFFERENCE",
                "INVOICE", "INV-1", LocalDate.now(), new BigDecimal("100.0000"), null, true
        );

        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                managerClient.reverseCreditNote(scn.noteId, "   ")
        );
        assertTrue(ex.getMessage().contains("ERR_REVERSAL_REASON_REQUIRED"));
    }

    @Test
    @DisplayName("P192-05: Cannot reverse unposted draft credit note")
    void testCannotReverseUnpostedDraft() {
        PosSupplierCreditNoteClient.SupplierCreditNoteDto scn = managerClient.createCreditNote(
                "sup-square", "Square Pharma", "VCN-DRAFT", "PRICE_DIFFERENCE",
                "INVOICE", "INV-1", LocalDate.now(), new BigDecimal("100.0000"), null, false
        );

        Exception ex = assertThrows(IllegalStateException.class, () ->
                managerClient.reverseCreditNote(scn.noteId, "Should fail on draft")
        );
        assertTrue(ex.getMessage().contains("ERR_CANNOT_REVERSE_UNPOSTED"));
    }

    @Test
    @DisplayName("P192-06: Cannot cancel posted credit note directly")
    void testCannotCancelPostedCreditNote() {
        PosSupplierCreditNoteClient.SupplierCreditNoteDto scn = managerClient.createCreditNote(
                "sup-square", "Square Pharma", "VCN-NOCAN", "PRICE_DIFFERENCE",
                "INVOICE", "INV-1", LocalDate.now(), new BigDecimal("100.0000"), null, true
        );

        Exception ex = assertThrows(IllegalStateException.class, () ->
                managerClient.cancelCreditNote(scn.noteId, "Should fail on posted")
        );
        assertTrue(ex.getMessage().contains("ERR_CANNOT_CANCEL_POSTED_CREDIT_NOTE"));
    }

    @Test
    @DisplayName("P192-07: Cashier role is barred with ERR_STAFF_ROLE_DENIED")
    void testCashierRoleForbidden() {
        Exception ex = assertThrows(SecurityException.class, () ->
                cashierClient.createCreditNote(
                        "sup-square", "Square Pharma", "VCN-CSH", "PRICE_DIFFERENCE",
                        "INVOICE", "INV-1", LocalDate.now(), new BigDecimal("100.0000"), null, false
                )
        );
        assertTrue(ex.getMessage().contains("ERR_STAFF_ROLE_DENIED"));
    }
}

