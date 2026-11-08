package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PosGoodsReceiptTest {

    private PosGoodsReceiptClient grnClient;
    private PosPurchaseOrderClient poClient;
    private Map<String, PosGoodsReceiptClient.GoodsReceiptDto> grnStore;
    private Map<String, PosPurchaseOrderClient.PurchaseOrderDto> poStore;

    @BeforeEach
    void setUp() {
        poStore = new HashMap<>();
        grnStore = new HashMap<>();

        poClient = new PosPurchaseOrderClient(
                "tenant-001",
                "branch-001",
                "BRANCH_MANAGER",
                "user-manager-001",
                false,
                poStore
        );

        grnClient = new PosGoodsReceiptClient(
                "tenant-001",
                "branch-001",
                "BRANCH_MANAGER",
                "user-manager-001",
                false,
                grnStore,
                poClient
        );
    }

    private PosPurchaseOrderClient.PurchaseOrderDto createOrderedPo(int orderedQty) {
        PosPurchaseOrderClient.PurchaseOrderItemDto item = new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "poi-001",
                "prod-napa-500",
                "Napa Extra 500mg",
                "BOX",
                10,
                10,
                orderedQty,
                0,
                new BigDecimal("2.0000"),
                new BigDecimal("3.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDate.now().plusDays(7),
                0,
                ""
        );
        PosPurchaseOrderClient.PurchaseOrderDto po = poClient.createPurchaseOrder(
                "supp-square",
                "Square Pharma",
                null,
                30,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDate.now().plusDays(7),
                "Order for receipt test",
                List.of(item)
        );
        poClient.approvePurchaseOrder(po.poId, "Approved by manager");
        return poClient.orderPurchaseOrder(po.poId);
    }

    @Test
    void testCreateGrnWithPoMatchingAndStorageChecks() {
        PosPurchaseOrderClient.PurchaseOrderDto po = createOrderedPo(100);
        assertEquals("ORDERED", po.status);

        PosGoodsReceiptClient.GoodsReceiptLineDto line = new PosGoodsReceiptClient.GoodsReceiptLineDto(
                "grnl-001",
                "poi-001",
                "prod-napa-500",
                "Napa Extra 500mg",
                "B-98712",
                LocalDate.now().plusYears(2),
                LocalDate.now().minusMonths(1),
                "BOX",
                10,
                10,
                100,
                100,
                new BigDecimal("2.0000"),
                new BigDecimal("3.0000"),
                new BigDecimal("5.00"), // 5% discount -> net = 1.90
                new BigDecimal("5.00"), // 5% tax -> line total = 199.50
                "RACK-A1"
        );

        PosGoodsReceiptClient.GoodsReceiptDto grn = grnClient.createGrn(
                po.poId,
                "supp-square",
                "Square Pharma",
                "CHALLAN-2026-001",
                LocalDate.now(),
                LocalDate.now(),
                "ROOM_TEMPERATURE",
                new BigDecimal("22.5"),
                true,
                "DGDA Manufacturer COA & Factory Invoice",
                "First shipment batch receipt",
                List.of(line)
        );

        assertNotNull(grn);
        assertEquals("RECEIVED", grn.status);
        assertEquals("PENDING", grn.apPostingStatus);
        assertEquals(100, grn.totalReceivedBaseUnits);
        assertEquals(new BigDecimal("199.5000"), grn.totalInvoiceAmount);
        assertEquals(1, grn.lines.size());
        assertEquals("B-98712", grn.lines.get(0).batchNumber);
    }

    @Test
    void testColdChainStorageViolationRejected() {
        PosPurchaseOrderClient.PurchaseOrderDto po = createOrderedPo(50);

        PosGoodsReceiptClient.GoodsReceiptLineDto line = new PosGoodsReceiptClient.GoodsReceiptLineDto(
                "grnl-001",
                "poi-001",
                "prod-napa-500",
                "Vaccine Cold Item",
                "B-COLD-01",
                LocalDate.now().plusYears(1),
                LocalDate.now().minusMonths(1),
                "VIAL",
                1,
                50,
                50,
                50,
                new BigDecimal("50.0000"),
                new BigDecimal("60.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "COLD_ROOM"
        );

        // Cold-chain temperature violation: 12.5 C (Allowed: 2 to 8 C)
        Exception tempExc = assertThrows(IllegalStateException.class, () -> grnClient.createGrn(
                po.poId,
                "supp-square",
                "Square Pharma",
                "CH-002",
                LocalDate.now(),
                LocalDate.now(),
                "COLD_CHAIN",
                new BigDecimal("12.5"),
                true,
                "COA",
                "Cold chain breach",
                List.of(line)
        ));
        assertTrue(tempExc.getMessage().contains("ERR_STORAGE_CHECK_FAILED"));

        // Storage check failed flag
        Exception checkExc = assertThrows(IllegalStateException.class, () -> grnClient.createGrn(
                po.poId,
                "supp-square",
                "Square Pharma",
                "CH-002",
                LocalDate.now(),
                LocalDate.now(),
                "COLD_CHAIN",
                new BigDecimal("4.5"),
                false, // failed inspection
                "COA",
                "Broken seal",
                List.of(line)
        ));
        assertTrue(checkExc.getMessage().contains("ERR_STORAGE_CHECK_FAILED"));
    }

    @Test
    void testExpiredBatchAndExcessReceiptRejected() {
        PosPurchaseOrderClient.PurchaseOrderDto po = createOrderedPo(100);

        // 1. Expired batch
        PosGoodsReceiptClient.GoodsReceiptLineDto expiredLine = new PosGoodsReceiptClient.GoodsReceiptLineDto(
                "grnl-001",
                "poi-001",
                "prod-napa-500",
                "Napa Extra 500mg",
                "B-EXPIRED",
                LocalDate.now().minusDays(5), // Expired!
                LocalDate.now().minusYears(2),
                "BOX",
                10,
                10,
                100,
                100,
                new BigDecimal("2.0000"),
                new BigDecimal("3.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "RACK"
        );

        Exception expExc = assertThrows(IllegalArgumentException.class, () -> grnClient.createGrn(
                po.poId,
                "supp-square",
                "Square Pharma",
                "CH-003",
                LocalDate.now(),
                LocalDate.now(),
                "ROOM_TEMPERATURE",
                new BigDecimal("20.0"),
                true,
                "COA",
                "Expired shipment",
                List.of(expiredLine)
        ));
        assertTrue(expExc.getMessage().contains("ERR_EXPIRED_BATCH_RECEIVED"));

        // 2. Excess receipt: Ordered 100, attempting to receive 150
        PosGoodsReceiptClient.GoodsReceiptLineDto excessLine = new PosGoodsReceiptClient.GoodsReceiptLineDto(
                "grnl-002",
                "poi-001",
                "prod-napa-500",
                "Napa Extra 500mg",
                "B-VALID",
                LocalDate.now().plusYears(1),
                LocalDate.now().minusMonths(1),
                "BOX",
                10,
                15,
                100,
                150, // 150 exceeds 100!
                new BigDecimal("2.0000"),
                new BigDecimal("3.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "RACK"
        );

        Exception excessExc = assertThrows(IllegalArgumentException.class, () -> grnClient.createGrn(
                po.poId,
                "supp-square",
                "Square Pharma",
                "CH-004",
                LocalDate.now(),
                LocalDate.now(),
                "ROOM_TEMPERATURE",
                new BigDecimal("20.0"),
                true,
                "COA",
                "Excess quantity",
                List.of(excessLine)
        ));
        assertTrue(excessExc.getMessage().contains("ERR_EXCESS_RECEIPT"));
    }

    @Test
    void testPostGrnIdempotencyAndPoClosedTransition() {
        PosPurchaseOrderClient.PurchaseOrderDto po = createOrderedPo(100);

        PosGoodsReceiptClient.GoodsReceiptLineDto line = new PosGoodsReceiptClient.GoodsReceiptLineDto(
                "grnl-001",
                "poi-001",
                "prod-napa-500",
                "Napa Extra 500mg",
                "B-POST-01",
                LocalDate.now().plusYears(2),
                LocalDate.now().minusMonths(1),
                "BOX",
                10,
                10,
                100,
                100,
                new BigDecimal("2.0000"),
                new BigDecimal("3.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "MAIN_RACK"
        );

        PosGoodsReceiptClient.GoodsReceiptDto grn = grnClient.createGrn(
                po.poId,
                "supp-square",
                "Square Pharma",
                "CH-005",
                LocalDate.now(),
                LocalDate.now(),
                "ROOM_TEMPERATURE",
                new BigDecimal("21.0"),
                true,
                "COA",
                "Full order delivery",
                List.of(line)
        );

        // Post GRN to stock
        PosGoodsReceiptClient.GoodsReceiptDto postedGrn = grnClient.postGrnToStock(grn.grnId);
        assertEquals("POSTED", postedGrn.status);
        assertEquals("POSTED", postedGrn.apPostingStatus);
        assertEquals(new BigDecimal("200.0000"), postedGrn.apInvoiceAmount);

        // Verify PO transitioned to CLOSED
        PosPurchaseOrderClient.PurchaseOrderDto updatedPo = poClient.getPurchaseOrder(po.poId);
        assertEquals("CLOSED", updatedPo.status);
        assertEquals(100, updatedPo.items.get(0).receivedQuantity);

        // Duplicate post attempt is strictly rejected
        Exception dupExc = assertThrows(IllegalStateException.class, () -> grnClient.postGrnToStock(grn.grnId));
        assertTrue(dupExc.getMessage().contains("ERR_ALREADY_POSTED"));
    }

    @Test
    void testReverseGrnWithConsumedStockGuard() {
        PosPurchaseOrderClient.PurchaseOrderDto po = createOrderedPo(50);

        PosGoodsReceiptClient.GoodsReceiptLineDto line = new PosGoodsReceiptClient.GoodsReceiptLineDto(
                "grnl-001",
                "poi-001",
                "prod-napa-500",
                "Napa Extra 500mg",
                "B-REV-01",
                LocalDate.now().plusYears(1),
                LocalDate.now().minusMonths(1),
                "BOX",
                10,
                5,
                50,
                50,
                new BigDecimal("2.0000"),
                new BigDecimal("3.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "RACK"
        );

        PosGoodsReceiptClient.GoodsReceiptDto grn = grnClient.createGrn(
                po.poId,
                "supp-square",
                "Square Pharma",
                "CH-006",
                LocalDate.now(),
                LocalDate.now(),
                "ROOM_TEMPERATURE",
                new BigDecimal("20.0"),
                true,
                "COA",
                "Reversible delivery",
                List.of(line)
        );

        grnClient.postGrnToStock(grn.grnId);

        // 1. Attempt reversal when stock has been consumed by patients
        Map<String, Integer> consumed = Map.of(line.lineId, 10);
        Exception consumedExc = assertThrows(IllegalStateException.class, () -> grnClient.reverseGrn(grn.grnId, "Defective packaging", consumed));
        assertTrue(consumedExc.getMessage().contains("ERR_CANNOT_REVERSE_CONSUMED_STOCK"));

        // 2. Successful reversal when 0 stock consumed
        PosGoodsReceiptClient.GoodsReceiptDto reversedGrn = grnClient.reverseGrn(grn.grnId, "Defective shipment", Map.of(line.lineId, 0));
        assertEquals("REVERSED", reversedGrn.status);
        assertEquals("REVERSED", reversedGrn.apPostingStatus);
    }
}

