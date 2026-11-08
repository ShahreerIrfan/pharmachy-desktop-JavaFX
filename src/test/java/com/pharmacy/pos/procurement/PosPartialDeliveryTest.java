package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class PosPartialDeliveryTest {

    private PosPurchaseOrderClient poClient;
    private PosGoodsReceiptClient grnClient;
    private PosGoodsReceiptClient cashierGrnClient;
    private PosGoodsReceiptClient foreignGrnClient;

    private PosPurchaseOrderClient.PurchaseOrderDto testPo;

    @BeforeEach
    void setUp() {
        Map<String, PosPurchaseOrderClient.PurchaseOrderDto> poStore = new HashMap<>();
        Map<String, PosGoodsReceiptClient.GoodsReceiptDto> grnStore = new HashMap<>();

        poClient = new PosPurchaseOrderClient(
                "tenant-square",
                "branch-dhk-1",
                "BRANCH_MANAGER",
                "user-mgr-1",
                false,
                poStore
        );

        grnClient = new PosGoodsReceiptClient(
                "tenant-square",
                "branch-dhk-1",
                "BRANCH_MANAGER",
                "user-mgr-1",
                false,
                grnStore,
                poClient
        );

        cashierGrnClient = new PosGoodsReceiptClient(
                "tenant-square",
                "branch-dhk-1",
                "CASHIER",
                "user-cashier-1",
                false,
                grnStore,
                poClient
        );

        foreignGrnClient = new PosGoodsReceiptClient(
                "tenant-other",
                "branch-other-1",
                "BRANCH_MANAGER",
                "user-mgr-2",
                false,
                grnStore,
                poClient
        );

        List<PosPurchaseOrderClient.PurchaseOrderItemDto> items = new ArrayList<>();
        items.add(new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "poi-napa-1",
                "prod-napa-500",
                "Napa Extra 500mg",
                "BOX",
                10,
                10,
                100, // 100 ordered
                0,
                new BigDecimal("2.5000"),
                new BigDecimal("3.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDate.now().plusDays(10),
                0,
                "First line"
        ));
        items.add(new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "poi-seclo-2",
                "prod-seclo-20",
                "Seclo 20mg Capsule",
                "BOX",
                10,
                5,
                50, // 50 ordered
                0,
                new BigDecimal("7.0000"),
                new BigDecimal("8.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDate.now().plusDays(10),
                0,
                "Second line"
        ));

        testPo = poClient.createPurchaseOrder(
                "sup-bex-1",
                "Beximco Pharma Ltd",
                null,
                30,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDate.now().plusDays(10),
                "PO with Napa and Seclo",
                items
        );

        poClient.approvePurchaseOrder(testPo.poId, "Manager approval");
        poClient.orderPurchaseOrder(testPo.poId);
    }

    @Test
    void testPartialDeliveryFirstShipment() {
        // Deliver 60 of 100 Napa units; 0 Seclo units
        List<PosGoodsReceiptClient.GoodsReceiptLineDto> lines = List.of(
                new PosGoodsReceiptClient.GoodsReceiptLineDto(
                        "line-1",
                        "poi-napa-1",
                        "prod-napa-500",
                        "Napa Extra 500mg",
                        "BX-NAPA-P1",
                        LocalDate.now().plusDays(365),
                        LocalDate.now().minusDays(20),
                        "BOX",
                        10,
                        6,
                        100,
                        60,
                        new BigDecimal("2.5000"),
                        new BigDecimal("3.0000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "RACK-A"
                )
        );

        PosGoodsReceiptClient.GoodsReceiptDto grn = grnClient.createGrn(
                testPo.poId,
                "sup-bex-1",
                "Beximco Pharma Ltd",
                "CHAL-PARTIAL-1",
                LocalDate.now(),
                LocalDate.now(),
                "ROOM_TEMPERATURE",
                null,
                true,
                "Challan copy",
                "First partial delivery",
                lines
        );

        PosGoodsReceiptClient.GoodsReceiptDto postedGrn = grnClient.postGrnToStock(grn.grnId);
        assertEquals("POSTED", postedGrn.status);
        assertEquals(new BigDecimal("150.0000"), postedGrn.totalInvoiceAmount);

        // Verify PO transitioned to PARTIALLY_RECEIVED
        PosPurchaseOrderClient.PurchaseOrderDto po = poClient.getPurchaseOrder(testPo.poId);
        assertEquals("PARTIALLY_RECEIVED", po.status);

        PosPurchaseOrderClient.PurchaseOrderItemDto napaItem = po.items.stream()
                .filter(i -> i.itemId.equals("poi-napa-1")).findFirst().orElseThrow();
        assertEquals(60, napaItem.receivedQuantity);
        assertEquals(40, napaItem.getBackorderedQuantity());
        assertEquals("PARTIALLY_DELIVERED", napaItem.deliveryStatus);

        PosPurchaseOrderClient.PurchaseOrderItemDto secloItem = po.items.stream()
                .filter(i -> i.itemId.equals("poi-seclo-2")).findFirst().orElseThrow();
        assertEquals(0, secloItem.receivedQuantity);
        assertEquals(50, secloItem.getBackorderedQuantity());
        assertEquals("PENDING", secloItem.deliveryStatus);
    }

    @Test
    void testSubsequentDeliveryClosesPO() {
        // Step 1: Deliver 60 Napa units
        testPartialDeliveryFirstShipment();

        // Step 2: Second shipment delivers remaining 40 Napa and 50 Seclo units
        List<PosGoodsReceiptClient.GoodsReceiptLineDto> lines2 = List.of(
                new PosGoodsReceiptClient.GoodsReceiptLineDto(
                        "line-2",
                        "poi-napa-1",
                        "prod-napa-500",
                        "Napa Extra 500mg",
                        "BX-NAPA-P2",
                        LocalDate.now().plusDays(365),
                        LocalDate.now().minusDays(10),
                        "BOX",
                        10,
                        4,
                        100,
                        40,
                        new BigDecimal("2.5000"),
                        new BigDecimal("3.0000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "RACK-A"
                ),
                new PosGoodsReceiptClient.GoodsReceiptLineDto(
                        "line-3",
                        "poi-seclo-2",
                        "prod-seclo-20",
                        "Seclo 20mg Capsule",
                        "BX-SECLO-01",
                        LocalDate.now().plusDays(400),
                        LocalDate.now().minusDays(10),
                        "BOX",
                        10,
                        5,
                        50,
                        50,
                        new BigDecimal("7.0000"),
                        new BigDecimal("8.0000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "RACK-B"
                )
        );

        PosGoodsReceiptClient.GoodsReceiptDto grn2 = grnClient.createGrn(
                testPo.poId,
                "sup-bex-1",
                "Beximco Pharma Ltd",
                "CHAL-PARTIAL-2",
                LocalDate.now(),
                LocalDate.now(),
                "ROOM_TEMPERATURE",
                null,
                true,
                "Challan copy 2",
                "Final delivery",
                lines2
        );

        PosGoodsReceiptClient.GoodsReceiptDto postedGrn2 = grnClient.postGrnToStock(grn2.grnId);
        assertEquals("POSTED", postedGrn2.status);

        // Verify PO is now fully CLOSED
        PosPurchaseOrderClient.PurchaseOrderDto po = poClient.getPurchaseOrder(testPo.poId);
        assertEquals("CLOSED", po.status);
        assertNotNull(po.closedAt);

        PosPurchaseOrderClient.PurchaseOrderItemDto napaItem = po.items.stream()
                .filter(i -> i.itemId.equals("poi-napa-1")).findFirst().orElseThrow();
        assertEquals(100, napaItem.receivedQuantity);
        assertEquals(0, napaItem.getBackorderedQuantity());
        assertEquals("FULLY_DELIVERED", napaItem.deliveryStatus);
    }

    @Test
    void testPromotionalBonusGoodsHandling() {
        // Line with 100 ordered units + 10 free bonus units @ 0 cost
        List<PosGoodsReceiptClient.GoodsReceiptLineDto> lines = List.of(
                new PosGoodsReceiptClient.GoodsReceiptLineDto(
                        "line-bonus-1",
                        "poi-napa-1",
                        "prod-napa-500",
                        "Napa Extra 500mg",
                        "BX-BONUS-LOT",
                        LocalDate.now().plusDays(365),
                        LocalDate.now().minusDays(10),
                        "BOX",
                        10,
                        1,
                        100,
                        10,
                        BigDecimal.ZERO,
                        new BigDecimal("3.0000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "RACK-BONUS",
                        true,
                        10,
                        "Trade promotional bonus"
                )
        );

        PosGoodsReceiptClient.GoodsReceiptDto grn = grnClient.createGrn(
                testPo.poId,
                "sup-bex-1",
                "Beximco Pharma Ltd",
                "CHAL-BONUS",
                LocalDate.now(),
                LocalDate.now(),
                "ROOM_TEMPERATURE",
                null,
                true,
                "Challan with trade scheme",
                "Promotional bonus shipment",
                lines
        );

        assertEquals(BigDecimal.ZERO.setScale(4), grn.totalInvoiceAmount);
        assertEquals(10, grn.totalReceivedBaseUnits);
        assertTrue(grn.lines.get(0).isBonusGoods);
    }

    @Test
    void testExcessDeliveryAuthorizationAndRejection() {
        // Attempting to deliver 120 units when 100 were ordered
        List<PosGoodsReceiptClient.GoodsReceiptLineDto> excessLines = List.of(
                new PosGoodsReceiptClient.GoodsReceiptLineDto(
                        "line-excess-1",
                        "poi-napa-1",
                        "prod-napa-500",
                        "Napa Extra 500mg",
                        "BX-EXCESS-LOT",
                        LocalDate.now().plusDays(365),
                        LocalDate.now().minusDays(10),
                        "BOX",
                        10,
                        12,
                        100,
                        120, // 20 in excess
                        new BigDecimal("2.5000"),
                        new BigDecimal("3.0000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "RACK-A"
                )
        );

        // Case A: Unauthorized excess is strictly blocked
        IllegalArgumentException exc = assertThrows(IllegalArgumentException.class, () -> {
            grnClient.createGrn(
                    testPo.poId,
                    "sup-bex-1",
                    "Beximco Pharma Ltd",
                    "CHAL-UNAUTH",
                    LocalDate.now(),
                    LocalDate.now(),
                    "ROOM_TEMPERATURE",
                    null,
                    true,
                    "Challan",
                    "Unauthorized excess",
                    false,
                    "",
                    excessLines
            );
        });
        assertTrue(exc.getMessage().contains("ERR_EXCESS_RECEIPT"));

        // Case B: Cashier cannot authorize excess
        SecurityException secExc = assertThrows(SecurityException.class, () -> {
            cashierGrnClient.createGrn(
                    testPo.poId,
                    "sup-bex-1",
                    "Beximco Pharma Ltd",
                    "CHAL-CSH",
                    LocalDate.now(),
                    LocalDate.now(),
                    "ROOM_TEMPERATURE",
                    null,
                    true,
                    "Challan",
                    "Cashier excess attempt",
                    true,
                    "Cashier override",
                    excessLines
            );
        });
        assertTrue(secExc.getMessage().contains("ERR_STAFF_ROLE_DENIED"));

        // Case C: Manager authorizes excess with valid reason
        PosGoodsReceiptClient.GoodsReceiptDto authGrn = grnClient.createGrn(
                testPo.poId,
                "sup-bex-1",
                "Beximco Pharma Ltd",
                "CHAL-AUTH-EXCESS",
                LocalDate.now(),
                LocalDate.now(),
                "ROOM_TEMPERATURE",
                null,
                true,
                "Approved PO revision",
                "Authorized bulk packing variance",
                true,
                "Manufacturer standard 120-pack batch delivery",
                excessLines
        );

        assertNotNull(authGrn);
        assertEquals(120, authGrn.totalReceivedBaseUnits);
    }

    @Test
    void testBackorderCancellationAndClosure() {
        // Step 1: Deliver 60 of 100 Napa units
        testPartialDeliveryFirstShipment();

        // Step 2: Supplier cancels remaining 40 Napa units and 50 Seclo units due to manufacturer halt
        grnClient.cancelPoBackorder(testPo.poId, "poi-napa-1", 40, "Supplier out of stock for Napa");

        PosPurchaseOrderClient.PurchaseOrderDto midPo = poClient.getPurchaseOrder(testPo.poId);
        assertEquals("PARTIALLY_RECEIVED", midPo.status); // Seclo still backordered

        // Over-cancellation guard
        IllegalArgumentException overExc = assertThrows(IllegalArgumentException.class, () -> {
            grnClient.cancelPoBackorder(testPo.poId, "poi-seclo-2", 60, "Excess cancel attempt");
        });
        assertTrue(overExc.getMessage().contains("ERR_INVALID_CANCEL_QUANTITY"));

        // Cancel exact 50 units for Seclo -> closes PO
        PosPurchaseOrderClient.PurchaseOrderDto closedPo = grnClient.cancelPoBackorder(testPo.poId, "poi-seclo-2", 50, "Discontinued formulation");
        assertEquals("CLOSED", closedPo.status);
        assertTrue(closedPo.closingNotes.contains("Closed after backorder cancellation"));
    }
}
