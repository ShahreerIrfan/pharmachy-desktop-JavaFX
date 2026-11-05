package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosSupplierOrganizationClientTest
 * Architecture Decision: ADR-0181 (P181).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 */
class PosSupplierOrganizationClientTest {

    private Map<String, PosSupplierOrganizationClient.SupplierDto> supplierStore;
    private List<PosSupplierOrganizationClient.SupplierLedgerEntryDto> ledgerStore;
    private PosSupplierOrganizationClient managerClient;
    private PosSupplierOrganizationClient cashierClient;
    private PosSupplierOrganizationClient foreignClient;

    @BeforeEach
    void setUp() {
        supplierStore = new HashMap<>();
        ledgerStore = new ArrayList<>();

        managerClient = new PosSupplierOrganizationClient(
                "tenant-alpha", "branch-main", "BRANCH_MANAGER", "user-mgr-1", false, supplierStore, ledgerStore
        );
        cashierClient = new PosSupplierOrganizationClient(
                "tenant-alpha", "branch-main", "CASHIER", "user-csh-1", false, supplierStore, ledgerStore
        );
        foreignClient = new PosSupplierOrganizationClient(
                "tenant-beta", "branch-north", "BRANCH_MANAGER", "user-mgr-2", false, supplierStore, ledgerStore
        );
    }

    @Test
    @DisplayName("Test 1: Valid supplier creation with legal credentials, NBR tax, and credit limits")
    void testValidSupplierCreationAndRetrieval() {
        PosSupplierOrganizationClient.SupplierDto supplier = managerClient.createSupplier(
                "Square Pharmaceuticals Depot",
                "Square Pharmaceuticals PLC Distribution Division",
                "+8801711223344",
                "depot.dhaka@squarepharma.com.bd",
                "DHAKA",
                "DGDA-DL-DH-98765",
                "BIN-001122334-0101",
                "TIN-998877665544",
                "TRAD/DNCC/2026/0129",
                30,
                new BigDecimal("500000.0000")
        );

        assertNotNull(supplier);
        assertTrue(supplier.supplierId.startsWith("sup-"));
        assertEquals("Square Pharmaceuticals Depot", supplier.name);
        assertEquals("DHAKA", supplier.region);
        assertEquals("BIN-001122334-0101", supplier.binNumber);
        assertEquals(0, supplier.creditLimit.compareTo(new BigDecimal("500000.0000")));
        assertEquals(0, supplier.currentBalance.compareTo(BigDecimal.ZERO));
        assertTrue(supplier.isActive);

        // Verification of authentic origin
        PosSupplierOrganizationClient.SupplierDto verified = managerClient.verifyProofOfOrigin(
                supplier.supplierId, "DGDA wholesale distribution certificate verified"
        );
        assertTrue(verified.proofOfOriginVerified);
        assertEquals("DGDA wholesale distribution certificate verified", verified.proofOfOriginNotes);

        // List verification
        List<PosSupplierOrganizationClient.SupplierDto> list = managerClient.listSuppliers();
        assertEquals(1, list.size());
        assertEquals(supplier.supplierId, list.get(0).supplierId);
    }

    @Test
    @DisplayName("Test 2: Incomplete or invalid supplier inputs are cleanly rejected")
    void testInvalidSupplierInputs() {
        // Missing name
        assertThrows(IllegalArgumentException.class, () -> managerClient.createSupplier(
                "", "Legal", "017", "e@x.com", "DHAKA", "DL", "BIN", "TIN", "TRAD", 30, BigDecimal.ZERO
        ));

        // Negative credit limit
        assertThrows(IllegalArgumentException.class, () -> managerClient.createSupplier(
                "Incepta Depot", "Incepta Legal", "017", "e@x.com", "DHAKA", "DL", "BIN", "TIN", "TRAD", 30, new BigDecimal("-500.00")
        ));

        // Duplicate active supplier name
        managerClient.createSupplier(
                "Beximco Pharma Depot", "Beximco Legal", "017", "e@x.com", "DHAKA", "DL", "BIN", "TIN", "TRAD", 30, BigDecimal.ZERO
        );

        assertThrows(IllegalStateException.class, () -> managerClient.createSupplier(
                "Beximco Pharma Depot", "Beximco Duplicate", "017", "e@x.com", "DHAKA", "DL", "BIN", "TIN", "TRAD", 30, BigDecimal.ZERO
        ));
    }

    @Test
    @DisplayName("Test 3: AP ledger postings maintain exact decimal precision and running balances")
    void testApLedgerPostings() {
        PosSupplierOrganizationClient.SupplierDto supplier = managerClient.createSupplier(
                "Renata Distribution Depot", "Renata Ltd", "017", "e@x.com", "DHAKA", "DL", "BIN", "TIN", "TRAD", 30, new BigDecimal("100000.0000")
        );

        // Step 1: GRN invoice received: Credit +45,250.7500 BDT
        PosSupplierOrganizationClient.SupplierLedgerEntryDto grnEntry = managerClient.postApLedgerEntry(
                supplier.supplierId,
                "GRN_INVOICE",
                "GRN-2026-0012",
                BigDecimal.ZERO,
                new BigDecimal("45250.7500"),
                "Received antibiotics and oral solids shipment"
        );
        assertEquals(0, grnEntry.balanceAfter.compareTo(new BigDecimal("45250.7500")));
        assertEquals(0, supplier.currentBalance.compareTo(new BigDecimal("45250.7500")));

        // Step 2: Payment made: Debit -25,000.0000 BDT
        PosSupplierOrganizationClient.SupplierLedgerEntryDto payEntry = managerClient.postApLedgerEntry(
                supplier.supplierId,
                "PAYMENT",
                "PAY-VOUCH-8841",
                new BigDecimal("25000.0000"),
                BigDecimal.ZERO,
                "Bank cheque payment"
        );
        assertEquals(0, payEntry.balanceAfter.compareTo(new BigDecimal("20250.7500")));
        assertEquals(0, supplier.currentBalance.compareTo(new BigDecimal("20250.7500")));

        // Step 3: Purchase Return Debit Note: Debit -250.7500 BDT
        PosSupplierOrganizationClient.SupplierLedgerEntryDto retEntry = managerClient.postApLedgerEntry(
                supplier.supplierId,
                "RETURN_DEBIT_NOTE",
                "PR-DEBIT-003",
                new BigDecimal("250.7500"),
                BigDecimal.ZERO,
                "Returned damaged strip packs"
        );
        assertEquals(0, retEntry.balanceAfter.compareTo(new BigDecimal("20000.0000")));
        assertEquals(0, supplier.currentBalance.compareTo(new BigDecimal("20000.0000")));
    }

    @Test
    @DisplayName("Test 4: Cashier RBAC gate and cross-tenant isolation enforcement")
    void testRbacAndCrossTenantEnforcement() {
        // Cashier cannot register supplier
        assertThrows(SecurityException.class, () -> cashierClient.createSupplier(
                "Opsonin Depot", "Opsonin Legal", "017", "e@x.com", "DHAKA", "DL", "BIN", "TIN", "TRAD", 30, BigDecimal.ZERO
        ));

        // Create under tenant-alpha
        PosSupplierOrganizationClient.SupplierDto supplier = managerClient.createSupplier(
                "ACI Healthcare Depot", "ACI Legal", "017", "e@x.com", "DHAKA", "DL", "BIN", "TIN", "TRAD", 30, BigDecimal.ZERO
        );

        // Cashier cannot post to AP ledger
        assertThrows(SecurityException.class, () -> cashierClient.postApLedgerEntry(
                supplier.supplierId, "PAYMENT", "REF-001", new BigDecimal("100.00"), BigDecimal.ZERO, "Pay"
        ));

        // Foreign tenant cannot access supplier
        assertThrows(SecurityException.class, () -> foreignClient.getSupplier(supplier.supplierId));
    }
}

