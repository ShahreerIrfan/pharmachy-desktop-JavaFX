package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PosPurchaseOrderLineTest {

    private PosPurchaseOrderClient client;
    private Map<String, PosPurchaseOrderClient.PurchaseOrderDto> store;

    @BeforeEach
    void setUp() {
        store = new HashMap<>();
        client = new PosPurchaseOrderClient(
                "tenant-001",
                "branch-001",
                "BRANCH_MANAGER",
                "user-procurement-mgr",
                false,
                store
        );
    }

    @Test
    void testCreateAndListPurchaseOrderItemsWithFullLineModeling() {
        LocalDate deliveryDate = LocalDate.now().plusDays(7);
        PosPurchaseOrderClient.PurchaseOrderItemDto item = new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "item-001",
                "prod-napa-500",
                "Napa 500mg Tablet",
                "BOX",
                100, // 100 tablets per box
                5,   // 5 boxes
                500, // 500 tablets
                0,
                new BigDecimal("1.2000"), // unit cost
                new BigDecimal("1.5000"), // MRP unit
                new BigDecimal("5.00"),   // 5% discount
                new BigDecimal("7.50"),   // 7.5% tax
                deliveryDate,
                10, // 10 bonus tablets
                "Urgent stock replenishment"
        );

        PosPurchaseOrderClient.PurchaseOrderDto po = client.createPurchaseOrder(
                "supp-square",
                "Square Pharmaceuticals Ltd.",
                "req-001",
                30,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                deliveryDate,
                "First seasonal procurement",
                List.of(item)
        );

        assertNotNull(po);
        assertEquals("DRAFT", po.status);
        assertEquals(1, po.items.size());

        PosPurchaseOrderClient.PurchaseOrderItemDto savedItem = po.items.get(0);
        assertEquals("BOX", savedItem.packUnit);
        assertEquals(100, savedItem.packageSize);
        assertEquals(5, savedItem.orderedPackages);
        assertEquals(500, savedItem.orderedQuantity);
        assertEquals(10, savedItem.freeGoodsBonusQuantity);
        assertEquals(deliveryDate, savedItem.expectedDeliveryDate);

        // netUnitCost = 1.20 * (1 - 0.05) = 1.1400
        assertEquals(new BigDecimal("1.1400"), savedItem.netUnitCost);
        // lineSubtotal = 1.14 * 500 = 570.0000
        assertEquals(new BigDecimal("570.0000"), savedItem.lineSubtotal);
        // taxAmount = 570.00 * 0.075 = 42.7500
        assertEquals(new BigDecimal("42.7500"), savedItem.taxAmount);
        // lineTotal = 570.00 + 42.75 = 612.7500
        assertEquals(new BigDecimal("612.7500"), savedItem.lineTotal);

        List<PosPurchaseOrderClient.PurchaseOrderItemDto> itemsList = client.listPoItems(po.poId);
        assertEquals(1, itemsList.size());
        assertEquals("item-001", itemsList.get(0).itemId);
    }

    @Test
    void testAddLineItemAndRecalculateTotals() {
        PosPurchaseOrderClient.PurchaseOrderItemDto item1 = new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "item-001",
                "prod-1",
                "Product 1",
                "STRIP",
                10,
                2,
                20,
                0,
                new BigDecimal("10.0000"),
                new BigDecimal("12.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                0,
                ""
        );

        PosPurchaseOrderClient.PurchaseOrderDto po = client.createPurchaseOrder(
                "supp-beximco",
                "Beximco Pharmaceuticals",
                null,
                15,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "Initial order",
                List.of(item1)
        );

        // item1: lineSubtotal = 20 * 10 = 200.0000, tax = 0
        assertEquals(new BigDecimal("200.0000"), po.subtotalAmount);

        // Add second item with discount and tax
        PosPurchaseOrderClient.PurchaseOrderItemDto item2 = new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "item-002",
                "prod-2",
                "Product 2",
                "BOX",
                50,
                1,
                50,
                0,
                new BigDecimal("2.0000"),
                new BigDecimal("2.5000"),
                new BigDecimal("10.00"), // 10% disc => net = 1.8000, subtotal = 90.0000
                new BigDecimal("5.00"),  // 5% tax => 4.5000
                null,
                0,
                ""
        );

        PosPurchaseOrderClient.PurchaseOrderDto updatedPo = client.addPoItem(po.poId, item2);
        assertEquals(2, updatedPo.items.size());
        // Subtotal = 200.0000 + 90.0000 = 290.0000
        assertEquals(new BigDecimal("290.0000"), updatedPo.subtotalAmount);
        // Tax = 0 + 4.5000 = 4.5000
        assertEquals(new BigDecimal("4.5000"), updatedPo.taxAmount);
        // Total = 290.0000 + 4.5000 = 294.5000
        assertEquals(new BigDecimal("294.5000"), updatedPo.totalAmount);
    }

    @Test
    void testUpdateAndDeleteLineItem() {
        PosPurchaseOrderClient.PurchaseOrderItemDto item1 = new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "item-001",
                "prod-1",
                "Product 1",
                "BOX",
                10,
                1,
                10,
                0,
                new BigDecimal("5.0000"),
                new BigDecimal("6.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                0,
                ""
        );
        PosPurchaseOrderClient.PurchaseOrderItemDto item2 = new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "item-002",
                "prod-2",
                "Product 2",
                "BOX",
                10,
                1,
                10,
                0,
                new BigDecimal("8.0000"),
                new BigDecimal("10.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                0,
                ""
        );

        PosPurchaseOrderClient.PurchaseOrderDto po = client.createPurchaseOrder(
                "supp-incepta",
                "Incepta Pharma",
                null,
                30,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "Multi-item PO",
                List.of(item1, item2)
        );

        // Update item 1: increase quantity to 20
        PosPurchaseOrderClient.PurchaseOrderItemDto updatedItem1 = new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "item-001",
                "prod-1",
                "Product 1 (Updated)",
                "BOX",
                10,
                2,
                20,
                0,
                new BigDecimal("5.0000"),
                new BigDecimal("6.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                0,
                "Updated quantity"
        );

        PosPurchaseOrderClient.PurchaseOrderDto poAfterUpdate = client.updatePoItem(po.poId, "item-001", updatedItem1);
        // Subtotal = (20 * 5) + (10 * 8) = 100 + 80 = 180.0000
        assertEquals(new BigDecimal("180.0000"), poAfterUpdate.subtotalAmount);

        // Delete item 2
        PosPurchaseOrderClient.PurchaseOrderDto poAfterDelete = client.deletePoItem(po.poId, "item-002");
        assertEquals(1, poAfterDelete.items.size());
        assertEquals(new BigDecimal("100.0000"), poAfterDelete.subtotalAmount);

        // Deleting the remaining last item is prevented
        Exception exc = assertThrows(IllegalStateException.class, () -> client.deletePoItem(po.poId, "item-001"));
        assertTrue(exc.getMessage().contains("ERR_CANNOT_DELETE_LAST_ITEM"));
    }

    @Test
    void testLockedPoAndMrpCeilingGuards() {
        PosPurchaseOrderClient.PurchaseOrderItemDto item1 = new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "item-001",
                "prod-1",
                "Product 1",
                "BOX",
                10,
                1,
                10,
                0,
                new BigDecimal("10.0000"),
                new BigDecimal("15.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                0,
                ""
        );

        PosPurchaseOrderClient.PurchaseOrderDto po = client.createPurchaseOrder(
                "supp-square",
                "Square",
                null,
                30,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "Guarded PO",
                List.of(item1)
        );

        // 1. DGDA MRP price ceiling guard
        PosPurchaseOrderClient.PurchaseOrderItemDto invalidMrpItem = new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "item-002",
                "prod-2",
                "Product 2",
                "BOX",
                10,
                1,
                10,
                0,
                new BigDecimal("20.0000"), // Cost exceeds MRP
                new BigDecimal("18.0000"), // MRP
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                0,
                ""
        );

        Exception mrpExc = assertThrows(IllegalArgumentException.class, () -> client.addPoItem(po.poId, invalidMrpItem));
        assertTrue(mrpExc.getMessage().contains("ERR_COST_EXCEEDS_MRP"));

        // 2. Locked PO Guard: Approve the PO
        client.approvePurchaseOrder(po.poId, "Approved by manager");

        // Attempt to add, edit or delete item on APPROVED order
        PosPurchaseOrderClient.PurchaseOrderItemDto item3 = new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "item-003",
                "prod-3",
                "Product 3",
                "BOX",
                10,
                1,
                10,
                0,
                new BigDecimal("5.0000"),
                new BigDecimal("7.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                0,
                ""
        );

        Exception lockAddExc = assertThrows(IllegalStateException.class, () -> client.addPoItem(po.poId, item3));
        assertTrue(lockAddExc.getMessage().contains("ERR_PO_LOCKED"));

        Exception lockUpdateExc = assertThrows(IllegalStateException.class, () -> client.updatePoItem(po.poId, "item-001", item3));
        assertTrue(lockUpdateExc.getMessage().contains("ERR_PO_LOCKED"));

        Exception lockDelExc = assertThrows(IllegalStateException.class, () -> client.deletePoItem(po.poId, "item-001"));
        assertTrue(lockDelExc.getMessage().contains("ERR_PO_LOCKED"));
    }
}
