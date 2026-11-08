package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosColdChainEvidenceTest
 * Architecture Decision: ADR-0189 (P189).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 */
public class PosColdChainEvidenceTest {

    private PosPurchaseOrderClient poClient;
    private PosGoodsReceiptClient grnClient;
    private PosGoodsReceiptClient cashierGrnClient;
    private PosPurchaseOrderClient.PurchaseOrderDto testPo;

    @BeforeEach
    void setUp() {
        Map<String, PosPurchaseOrderClient.PurchaseOrderDto> poStore = new HashMap<>();
        Map<String, PosGoodsReceiptClient.GoodsReceiptDto> grnStore = new HashMap<>();

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

        cashierGrnClient = new PosGoodsReceiptClient(
                "tenant-evercare",
                "branch-1",
                "CASHIER",
                "usr-csh-1",
                false,
                grnStore,
                poClient
        );

        // Create PO with 50 vials of Insulin
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
                "Cold chain required"
        );

        testPo = poClient.createPurchaseOrder(
                "supp-beximco",
                "Beximco Pharmaceuticals Ltd.",
                "",
                30,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDate.now().plusDays(10),
                "Insulin purchase",
                List.of(insulinItem)
        );

        poClient.approvePurchaseOrder(testPo.poId, "Approved by Manager");
        poClient.orderPurchaseOrder(testPo.poId);
    }

    @Test
    @DisplayName("1. Valid cold chain receipt (4.5C) creates NOT_QUARANTINED evidence and posts to active stock")
    void testValidColdChainReceiptEvidence() {
        PosGoodsReceiptClient.GoodsReceiptLineDto line = new PosGoodsReceiptClient.GoodsReceiptLineDto(
                "grnl-ins-01",
                "poi-ins-50",
                "prod-insulin",
                "Glarin Insulin 100IU",
                "BX-INS-2026-A",
                LocalDate.now().plusDays(365),
                LocalDate.now().minusDays(30),
                "VIAL",
                1,
                50,
                50,
                50,
                new BigDecimal("350.0000"),
                new BigDecimal("420.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "COLD_ROOM_SHELF_1"
        );

        PosGoodsReceiptClient.GoodsReceiptDto grn = grnClient.createGrn(
                testPo.poId,
                "supp-beximco",
                "Beximco Pharmaceuticals Ltd.",
                "CHAL-COLD-01",
                LocalDate.now(),
                LocalDate.now(),
                "COLD_CHAIN",
                new BigDecimal("4.50"),
                true,
                "Beximco Cold-Van Slip",
                "Intact gel packs",
                List.of(line)
        );

        assertEquals(1, grn.coldChainEvidences.size());
        PosGoodsReceiptClient.ColdChainEvidenceDto evidence = grn.coldChainEvidences.get(0);
        assertEquals("NOT_QUARANTINED", evidence.quarantineStatus);
        assertFalse(evidence.excursionDetected);
        assertFalse(line.isQuarantined);

        grnClient.postGrnToStock(grn.grnId);
        assertEquals("POSTED", grn.status);
    }

    @Test
    @DisplayName("2. Suspicious excursion (9.5C) with quarantineSuspicious=true captures PENDING_REVIEW evidence without rejecting")
    void testSuspiciousExcursionQuarantined() {
        PosGoodsReceiptClient.GoodsReceiptLineDto line = new PosGoodsReceiptClient.GoodsReceiptLineDto(
                "grnl-ins-02",
                "poi-ins-50",
                "prod-insulin",
                "Glarin Insulin 100IU",
                "BX-INS-HOT-01",
                LocalDate.now().plusDays(365),
                LocalDate.now().minusDays(30),
                "VIAL",
                1,
                50,
                50,
                50,
                new BigDecimal("350.0000"),
                new BigDecimal("420.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "QUARANTINE_SHELF_1"
        );

        PosGoodsReceiptClient.ColdChainEvidenceDto customEvidence = new PosGoodsReceiptClient.ColdChainEvidenceDto(
                "cce-hot-01",
                "",
                line.lineId,
                line.productId,
                line.batchNumber,
                "TT-LOGGER-9912",
                new BigDecimal("9.50"),
                new BigDecimal("3.20"),
                new BigDecimal("10.10"),
                true,
                60,
                "ICE_PACKS_MELTED",
                "Biologistics BD",
                "INSULATED_BOX",
                "logger_download_9912.pdf",
                "Logger indicated 10.1C peak during highway transport",
                "PENDING_REVIEW",
                "Temperature excursion exceeded 8.0C limit"
        );

        PosGoodsReceiptClient.GoodsReceiptDto grn = grnClient.createGrn(
                testPo.poId,
                "supp-beximco",
                "Beximco Pharmaceuticals Ltd.",
                "CHAL-COLD-HOT",
                LocalDate.now(),
                LocalDate.now(),
                "COLD_CHAIN",
                new BigDecimal("9.50"),
                false, // check failed
                "Biologistics Slip",
                "Held for inspection",
                false,
                "",
                true, // quarantineSuspicious = true
                customEvidence,
                List.of(line)
        );

        assertTrue(line.isQuarantined);
        assertEquals(1, grn.coldChainEvidences.size());
        PosGoodsReceiptClient.ColdChainEvidenceDto ev = grn.coldChainEvidences.get(0);
        assertEquals("PENDING_REVIEW", ev.quarantineStatus);
        assertTrue(ev.excursionDetected);
        assertEquals("ICE_PACKS_MELTED", ev.packagingCondition);

        // Posting succeeds into quarantined stock
        grnClient.postGrnToStock(grn.grnId);
        assertEquals("POSTED", grn.status);
    }

    @Test
    @DisplayName("3. Strict inspection failure without quarantineSuspicious throws ERR_STORAGE_CHECK_FAILED")
    void testStrictInspectionFailureThrows() {
        PosGoodsReceiptClient.GoodsReceiptLineDto line = new PosGoodsReceiptClient.GoodsReceiptLineDto(
                "grnl-ins-03",
                "poi-ins-50",
                "prod-insulin",
                "Glarin Insulin 100IU",
                "BX-INS-FAIL-01",
                LocalDate.now().plusDays(365),
                LocalDate.now().minusDays(30),
                "VIAL",
                1,
                50,
                50,
                50,
                new BigDecimal("350.0000"),
                new BigDecimal("420.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "MAIN_RACK"
        );

        // Temperature too high (11.0C) with quarantineSuspicious = false
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                grnClient.createGrn(
                        testPo.poId,
                        "supp-beximco",
                        "Beximco Pharmaceuticals Ltd.",
                        "CHAL-HOT-FAIL",
                        LocalDate.now(),
                        LocalDate.now(),
                        "COLD_CHAIN",
                        new BigDecimal("11.00"),
                        true,
                        "Slip",
                        "",
                        List.of(line)
                )
        );
        assertTrue(ex.getMessage().contains("ERR_STORAGE_CHECK_FAILED"));
    }

    @Test
    @DisplayName("4. Authorized quarantine resolution (APPROVE_RELEASE) unquarantines batch with justification")
    void testResolveQuarantineApproveRelease() {
        PosGoodsReceiptClient.GoodsReceiptLineDto line = new PosGoodsReceiptClient.GoodsReceiptLineDto(
                "grnl-ins-04",
                "poi-ins-50",
                "prod-insulin",
                "Glarin Insulin 100IU",
                "BX-INS-RESOLVE-1",
                LocalDate.now().plusDays(365),
                LocalDate.now().minusDays(30),
                "VIAL",
                1,
                50,
                50,
                50,
                new BigDecimal("350.0000"),
                new BigDecimal("420.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "QUARANTINE_SHELF_1"
        );

        PosGoodsReceiptClient.GoodsReceiptDto grn = grnClient.createGrn(
                testPo.poId,
                "supp-beximco",
                "Beximco Pharmaceuticals Ltd.",
                "CHAL-COLD-RES-1",
                LocalDate.now(),
                LocalDate.now(),
                "COLD_CHAIN",
                new BigDecimal("8.80"),
                true,
                "Slip",
                "Held for stability check",
                false,
                "",
                true, // quarantine
                null,
                List.of(line)
        );

        grnClient.postGrnToStock(grn.grnId);
        assertTrue(line.isQuarantined);

        String evidenceId = grn.coldChainEvidences.get(0).evidenceId;

        // Resolve quarantine as Manager
        PosGoodsReceiptClient.ColdChainEvidenceDto resolved = grnClient.resolveQuarantine(
                grn.grnId,
                evidenceId,
                "APPROVE_RELEASE",
                "Manufacturer accelerated stability certificate confirms 24h at 10C maintains 100% potency",
                "Approved by Chief Pharmacist"
        );

        assertEquals("RELEASED", resolved.quarantineStatus);
        assertEquals("APPROVE_RELEASE", resolved.resolutionAction);
        assertFalse(line.isQuarantined);
    }

    @Test
    @DisplayName("5. Authorized REJECT_RETURN reduces AP liability, and Cashier is barred with ERR_STAFF_ROLE_DENIED")
    void testResolveQuarantineRejectReturnAndRbac() {
        PosGoodsReceiptClient.GoodsReceiptLineDto line = new PosGoodsReceiptClient.GoodsReceiptLineDto(
                "grnl-ins-05",
                "poi-ins-50",
                "prod-insulin",
                "Glarin Insulin 100IU",
                "BX-INS-RETURN-1",
                LocalDate.now().plusDays(365),
                LocalDate.now().minusDays(30),
                "VIAL",
                1,
                50,
                50,
                50,
                new BigDecimal("350.0000"),
                new BigDecimal("420.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "QUARANTINE_SHELF_1"
        );

        PosGoodsReceiptClient.GoodsReceiptDto grn = grnClient.createGrn(
                testPo.poId,
                "supp-beximco",
                "Beximco Pharmaceuticals Ltd.",
                "CHAL-COLD-REJ-1",
                LocalDate.now(),
                LocalDate.now(),
                "COLD_CHAIN",
                new BigDecimal("15.20"),
                false,
                "Slip",
                "Gross temperature excursion",
                false,
                "",
                true,
                null,
                List.of(line)
        );

        grnClient.postGrnToStock(grn.grnId);
        BigDecimal originalAp = grn.apInvoiceAmount;
        assertEquals(new BigDecimal("17500.0000"), originalAp);

        String evidenceId = grn.coldChainEvidences.get(0).evidenceId;

        // Cashier role blocked
        assertThrows(SecurityException.class, () ->
                cashierGrnClient.resolveQuarantine(
                        grn.grnId,
                        evidenceId,
                        "REJECT_RETURN",
                        "Cashier attempting return",
                        ""
                )
        );

        // Manager resolves as REJECT_RETURN
        PosGoodsReceiptClient.ColdChainEvidenceDto rejected = grnClient.resolveQuarantine(
                grn.grnId,
                evidenceId,
                "REJECT_RETURN",
                "Severe temperature violation during transit; return to Beximco for credit note",
                ""
        );

        assertEquals("RETURNED_TO_SUPPLIER", rejected.quarantineStatus);
        assertEquals("REJECT_RETURN", rejected.resolutionAction);
        assertFalse(line.isQuarantined);

        // AP invoice amount credited to 0
        assertEquals(BigDecimal.ZERO.setScale(4), grn.apInvoiceAmount);
    }
}
