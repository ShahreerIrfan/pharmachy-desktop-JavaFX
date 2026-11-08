package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosSupplierInvoiceMatchingTest
 * Architecture Decision: ADR-0190 (P190).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 */
public class PosSupplierInvoiceMatchingTest {

    private PosPurchaseOrderClient poClient;
    private PosGoodsReceiptClient grnClient;
    private PosSupplierInvoiceMatchingClient invoiceClient;
    private PosSupplierInvoiceMatchingClient cashierInvoiceClient;

    private PosPurchaseOrderClient.PurchaseOrderDto testPo;
    private PosGoodsReceiptClient.GoodsReceiptDto testGrn;

    @BeforeEach
    void setUp() {
        Map<String, PosPurchaseOrderClient.PurchaseOrderDto> poStore = new HashMap<>();
        Map<String, PosGoodsReceiptClient.GoodsReceiptDto> grnStore = new HashMap<>();
        Map<String, PosSupplierInvoiceMatchingClient.SupplierInvoiceDto> invoiceStore = new HashMap<>();
        Map<String, PosSupplierInvoiceMatchingClient.SupplierLedgerEntryDto> ledgerStore = new HashMap<>();

        poClient = new PosPurchaseOrderClient(
                "tenant-evercare",
                "branch-1",
                "BRANCH_MANAGER",
                "usr-mgr-1",
                false,
                poStore
        );

        grnClient = new PosGoodsReceiptClient(
                "tenant-evercare",
                "branch-1",
                "BRANCH_MANAGER",
                "usr-mgr-1",
                false,
                grnStore,
                poClient
        );

        invoiceClient = new PosSupplierInvoiceMatchingClient(
                "tenant-evercare",
                "branch-1",
                "BRANCH_MANAGER",
                "usr-mgr-1",
                false,
                invoiceStore,
                ledgerStore,
                poClient,
                grnClient
        );

        cashierInvoiceClient = new PosSupplierInvoiceMatchingClient(
                "tenant-evercare",
                "branch-1",
                "CASHIER",
                "usr-csh-1",
                false,
                invoiceStore,
                ledgerStore,
                poClient,
                grnClient
        );

        // 1. Create and Order PO for 50 vials of Insulin @ ৳350.00
        PosPurchaseOrderClient.PurchaseOrderItemDto insulinItem = new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "poi-ins-50",
                "prod-insulin",
                "Glarin Insulin 100IU",
                "VIAL",
                1,
                50,
                50,
                0,
                new BigDecimal("350.0000"),
                new BigDecimal("420.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDate.now().plusDays(30),
                0,
                "PO for 50 vials"
        );

        testPo = poClient.createPurchaseOrder(
                "supp-beximco",
                "Beximco Pharmaceuticals Ltd.",
                "",
                30,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDate.now().plusDays(10),
                "Insulin PO",
                List.of(insulinItem)
        );
        poClient.approvePurchaseOrder(testPo.poId, "Approved by Manager");
        poClient.orderPurchaseOrder(testPo.poId);

        // 2. Create GRN for 50 vials received
        PosGoodsReceiptClient.GoodsReceiptLineDto grnLine = new PosGoodsReceiptClient.GoodsReceiptLineDto(
                "grnl-ins-50",
                "poi-ins-50",
                "prod-insulin",
                "Glarin Insulin 100IU",
                "BEX-INS-2026",
                LocalDate.now().plusDays(365),
                LocalDate.now().minusDays(10),
                "VIAL",
                1,
                50,
                50,
                50,
                new BigDecimal("350.0000"),
                new BigDecimal("420.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "COLD_ROOM_A"
        );

        testGrn = grnClient.createGrn(
                testPo.poId,
                "supp-beximco",
                "Beximco Pharmaceuticals Ltd.",
                "CH-BEX-9901",
                LocalDate.now(),
                LocalDate.now(),
                "COLD_CHAIN_2_8C",
                new BigDecimal("4.50"),
                true,
                "Cold Truck Log",
                "Received in good condition",
                false,
                "",
                false,
                null,
                List.of(grnLine)
        );
        grnClient.postGrnToStock(testGrn.grnId);
    }

    @Test
    @DisplayName("1. Valid 3-Way Match within tolerance sets MATCHED status and posts to AP SupplierLedger")
    void testValidThreeWayMatchAndApPosting() {
        PosSupplierInvoiceMatchingClient.SupplierInvoiceItemDto item = new PosSupplierInvoiceMatchingClient.SupplierInvoiceItemDto(
                "sii-1",
                "prod-insulin",
                "Glarin Insulin 100IU",
                "poi-ins-50",
                "grnl-ins-50",
                "BEX-INS-2026",
                50,
                50,
                50,
                new BigDecimal("350.0000"),
                new BigDecimal("350.0000"),
                new BigDecimal("17500.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("17500.0000"),
                "MATCHED",
                BigDecimal.ZERO,
                0,
                ""
        );

        PosSupplierInvoiceMatchingClient.SupplierInvoiceDto invoice = invoiceClient.createSupplierInvoice(
                "supp-beximco",
                "Beximco Pharmaceuticals Ltd.",
                "INV-BEX-2026-001",
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                testPo.poId,
                testGrn.grnId,
                "BDT",
                new BigDecimal("1.50"),
                new BigDecimal("50.0000"),
                "INV_SCAN_001.PDF",
                "Regular vendor bill",
                List.of(item)
        );

        assertEquals("MATCHED", invoice.matchingStatus);
        assertFalse(invoice.hasDiscrepancy);
        assertTrue(invoice.discrepancyReasons.isEmpty());
        assertEquals(0, new BigDecimal("17500.0000").compareTo(invoice.totalAmount));

        // Post to AP
        PosSupplierInvoiceMatchingClient.SupplierInvoiceDto posted = invoiceClient.postInvoiceToAp(invoice.invoiceId);
        assertEquals("POSTED_TO_AP", posted.matchingStatus);
        assertNotNull(posted.postedToApAt);
        assertNotNull(posted.apLedgerEntryId);
    }

    @Test
    @DisplayName("2. Price variance exceeding 1.5% tolerance routes invoice to DISCREPANCY_QUEUE")
    void testPriceVarianceDiscrepancyQueueRouting() {
        // Vendor bills ৳370 instead of agreed ৳350 (+5.7% price spike)
        PosSupplierInvoiceMatchingClient.SupplierInvoiceItemDto item = new PosSupplierInvoiceMatchingClient.SupplierInvoiceItemDto(
                "sii-price-err",
                "prod-insulin",
                "Glarin Insulin 100IU",
                "poi-ins-50",
                "grnl-ins-50",
                "BEX-INS-2026",
                50,
                50,
                50,
                new BigDecimal("370.0000"), // Price violation
                new BigDecimal("350.0000"),
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "MATCHED",
                BigDecimal.ZERO,
                0,
                ""
        );

        PosSupplierInvoiceMatchingClient.SupplierInvoiceDto invoice = invoiceClient.createSupplierInvoice(
                "supp-beximco",
                "Beximco Pharmaceuticals Ltd.",
                "INV-BEX-2026-PRICE-VAR",
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                testPo.poId,
                testGrn.grnId,
                "BDT",
                new BigDecimal("1.50"),
                new BigDecimal("50.0000"),
                "INV_SCAN_PRICE.PDF",
                "Price discrepancy invoice",
                List.of(item)
        );

        assertEquals("DISCREPANCY_QUEUE", invoice.matchingStatus);
        assertTrue(invoice.hasDiscrepancy);
        assertFalse(invoice.discrepancyReasons.isEmpty());
        assertTrue(invoice.discrepancyReasons.get(0).contains("PRICE_VARIANCE"));

        // Unapproved invoice cannot be posted to AP
        assertThrows(IllegalStateException.class, () -> invoiceClient.postInvoiceToAp(invoice.invoiceId));

        // Appears in Exception Queue
        List<PosSupplierInvoiceMatchingClient.SupplierInvoiceDto> queue = invoiceClient.getExceptionQueue("branch-1");
        assertTrue(queue.stream().anyMatch(inv -> inv.invoiceId.equals(invoice.invoiceId)));
    }

    @Test
    @DisplayName("3. Physical quantity overbilling (billed 60 > received 50) routes to DISCREPANCY_QUEUE")
    void testQuantityOverbillingDiscrepancy() {
        // Vendor bills for 60 units, but warehouse only physically received 50
        PosSupplierInvoiceMatchingClient.SupplierInvoiceItemDto item = new PosSupplierInvoiceMatchingClient.SupplierInvoiceItemDto(
                "sii-qty-err",
                "prod-insulin",
                "Glarin Insulin 100IU",
                "poi-ins-50",
                "grnl-ins-50",
                "BEX-INS-2026",
                60, // Billed 60 > GRN 50
                50,
                50,
                new BigDecimal("350.0000"),
                new BigDecimal("350.0000"),
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "MATCHED",
                BigDecimal.ZERO,
                0,
                ""
        );

        PosSupplierInvoiceMatchingClient.SupplierInvoiceDto invoice = invoiceClient.createSupplierInvoice(
                "supp-beximco",
                "Beximco Pharmaceuticals Ltd.",
                "INV-BEX-2026-QTY-ERR",
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                testPo.poId,
                testGrn.grnId,
                "BDT",
                new BigDecimal("1.50"),
                new BigDecimal("50.0000"),
                "INV_SCAN_QTY.PDF",
                "Quantity overbilling",
                List.of(item)
        );

        assertEquals("DISCREPANCY_QUEUE", invoice.matchingStatus);
        assertTrue(invoice.hasDiscrepancy);
        assertTrue(invoice.discrepancyReasons.stream().anyMatch(r -> r.contains("QUANTITY_OVER_BILLING")));
    }

    @Test
    @DisplayName("4. Managerial exception approval unlocks AP posting with documented justification")
    void testManagerialExceptionApproval() {
        // Discrepant invoice
        PosSupplierInvoiceMatchingClient.SupplierInvoiceItemDto item = new PosSupplierInvoiceMatchingClient.SupplierInvoiceItemDto(
                "sii-override",
                "prod-insulin",
                "Glarin Insulin 100IU",
                "poi-ins-50",
                "grnl-ins-50",
                "BEX-INS-2026",
                50,
                50,
                50,
                new BigDecimal("360.0000"), // ৳10 higher
                new BigDecimal("350.0000"),
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "MATCHED",
                BigDecimal.ZERO,
                0,
                ""
        );

        PosSupplierInvoiceMatchingClient.SupplierInvoiceDto invoice = invoiceClient.createSupplierInvoice(
                "supp-beximco",
                "Beximco Pharmaceuticals Ltd.",
                "INV-BEX-2026-OVERRIDE",
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                testPo.poId,
                testGrn.grnId,
                "BDT",
                new BigDecimal("1.50"),
                new BigDecimal("50.0000"),
                "INV_SCAN_ADJ.PDF",
                "Emergency price increase",
                List.of(item)
        );

        assertEquals("DISCREPANCY_QUEUE", invoice.matchingStatus);

        // Missing justification throws exception
        assertThrows(IllegalArgumentException.class, () ->
                invoiceClient.approveException(invoice.invoiceId, "", ""));

        // Manager approves with documented business justification
        PosSupplierInvoiceMatchingClient.SupplierInvoiceDto approved = invoiceClient.approveException(
                invoice.invoiceId,
                "Supplier increased price due to fuel surcharge approved in revised circular DGDA-2026/04",
                "Logged by Manager"
        );

        assertEquals("APPROVED_EXCEPTION", approved.matchingStatus);
        assertEquals("usr-mgr-1", approved.exceptionApprovedBy);
        assertNotNull(approved.exceptionApprovedAt);

        // Can now be posted to AP
        PosSupplierInvoiceMatchingClient.SupplierInvoiceDto posted = invoiceClient.postInvoiceToAp(invoice.invoiceId);
        assertEquals("POSTED_TO_AP", posted.matchingStatus);
    }

    @Test
    @DisplayName("5. Invoice Rejection sets REJECTED; Cashier role is barred with ERR_STAFF_ROLE_DENIED")
    void testInvoiceRejectionAndCashierGuard() {
        PosSupplierInvoiceMatchingClient.SupplierInvoiceItemDto item = new PosSupplierInvoiceMatchingClient.SupplierInvoiceItemDto(
                "sii-reject",
                "prod-insulin",
                "Glarin Insulin 100IU",
                "poi-ins-50",
                "grnl-ins-50",
                "BEX-INS-2026",
                50,
                50,
                50,
                new BigDecimal("400.0000"), // Wild price spike
                new BigDecimal("350.0000"),
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "MATCHED",
                BigDecimal.ZERO,
                0,
                ""
        );

        PosSupplierInvoiceMatchingClient.SupplierInvoiceDto invoice = invoiceClient.createSupplierInvoice(
                "supp-beximco",
                "Beximco Pharmaceuticals Ltd.",
                "INV-BEX-2026-REJECT",
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                testPo.poId,
                testGrn.grnId,
                "BDT",
                new BigDecimal("1.50"),
                new BigDecimal("50.0000"),
                "INV_SCAN_REJ.PDF",
                "Disputed bill",
                List.of(item)
        );

        // Cashier barred from invoice operations
        assertThrows(SecurityException.class, () ->
                cashierInvoiceClient.executeThreeWayMatch(invoice.invoiceId));

        assertThrows(SecurityException.class, () ->
                cashierInvoiceClient.approveException(invoice.invoiceId, "Cashier attempt", ""));

        assertThrows(SecurityException.class, () ->
                cashierInvoiceClient.rejectInvoice(invoice.invoiceId, "Cashier attempt", ""));

        // Manager formally rejects invoice
        PosSupplierInvoiceMatchingClient.SupplierInvoiceDto rejected = invoiceClient.rejectInvoice(
                invoice.invoiceId,
                "Unjustified price spike from ৳350 to ৳400. Bill returned to Beximco for reissuance.",
                "Returned to sales representative"
        );

        assertEquals("REJECTED", rejected.matchingStatus);
        assertEquals("usr-mgr-1", rejected.rejectedBy);
        assertNotNull(rejected.rejectedAt);

        // Cannot post rejected invoice to AP
        assertThrows(IllegalStateException.class, () -> invoiceClient.postInvoiceToAp(invoice.invoiceId));
    }
}

