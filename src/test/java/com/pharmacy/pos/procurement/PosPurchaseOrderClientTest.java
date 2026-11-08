package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosPurchaseOrderClientTest
 * Architecture Decision: ADR-0185 (P185).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 */
class PosPurchaseOrderClientTest {

    private Map<String, PosPurchaseOrderClient.PurchaseOrderDto> poStore;
    private PosPurchaseOrderClient managerClient;
    private PosPurchaseOrderClient pharmacistClient;
    private PosPurchaseOrderClient cashierClient;
    private PosPurchaseOrderClient foreignClient;

    @BeforeEach
    void setUp() {
        poStore = new HashMap<>();

        managerClient = new PosPurchaseOrderClient(
                "tenant-alpha", "branch-main", "BRANCH_MANAGER", "user-mgr-1", false, poStore
        );
        pharmacistClient = new PosPurchaseOrderClient(
                "tenant-alpha", "branch-main", "STAFF_PHARMACIST", "user-pharm-1", false, poStore
        );
        cashierClient = new PosPurchaseOrderClient(
                "tenant-alpha", "branch-main", "CASHIER", "user-csh-1", false, poStore
        );
        foreignClient = new PosPurchaseOrderClient(
                "tenant-beta", "branch-north", "BRANCH_MANAGER", "user-mgr-2", false, poStore
        );
    }

    @Test
    @DisplayName("Test 1: Valid purchase order creation, line discounts, and manager approval")
    void testCreateAndApproveValidPurchaseOrder() {
        List<PosPurchaseOrderClient.PurchaseOrderItemDto> items = new ArrayList<>();
        // Item 1: 100 units @ 2.50 BDT, 10% discount -> net 2.2500 -> total 225.0000
        items.add(new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "poi-1", "prod-napa-500", "Napa Extra", "BOX", 100, 100, 0,
                new BigDecimal("2.5000"), new BigDecimal("10.00"), "Batch promo"
        ));
        // Item 2: 50 units @ 5.00 BDT, 0% discount -> net 5.0000 -> total 250.0000
        items.add(new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "poi-2", "prod-seclo-20", "Seclo 20mg", "STRIP", 10, 50, 0,
                new BigDecimal("5.0000"), BigDecimal.ZERO, ""
        ));

        // Subtotal = 225.0000 + 250.0000 = 475.0000
        // Order discount: 25.0000, Tax: 10.0000 -> Total = 460.0000
        PosPurchaseOrderClient.PurchaseOrderDto po = managerClient.createPurchaseOrder(
                "sup-square-001",
                "Square Pharmaceuticals Depot",
                "req-12345",
                30,
                new BigDecimal("25.0000"),
                new BigDecimal("10.0000"),
                LocalDate.of(2026, 11, 25),
                "Monthly replenishment PO",
                items
        );

        assertNotNull(po);
        assertTrue(po.poId.startsWith("po-"));
        assertTrue(po.poNumber.startsWith("PO-"));
        assertEquals("tenant-alpha", po.tenantId);
        assertEquals("DRAFT", po.status);
        assertEquals(0, po.subtotalAmount.compareTo(new BigDecimal("475.0000")));
        assertEquals(0, po.discountAmount.compareTo(new BigDecimal("25.0000")));
        assertEquals(0, po.taxAmount.compareTo(new BigDecimal("10.0000")));
        assertEquals(0, po.totalAmount.compareTo(new BigDecimal("460.0000")));
        assertEquals(2, po.items.size());

        // Approve
        PosPurchaseOrderClient.PurchaseOrderDto approved = managerClient.approvePurchaseOrder(
                po.poId, "Commercial liability approved"
        );
        assertEquals("APPROVED", approved.status);
        assertEquals("user-mgr-1", approved.approvedById);
        assertNotNull(approved.approvedAt);
    }

    @Test
    @DisplayName("Test 2: Dispatch order and progressive goods receipt transitions to CLOSED")
    void testOrderAndReceiveItemsProgressively() {
        List<PosPurchaseOrderClient.PurchaseOrderItemDto> items = new ArrayList<>();
        items.add(new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "poi-1", "prod-napa-500", "Napa", "BOX", 100, 100, 0,
                new BigDecimal("2.0000"), BigDecimal.ZERO, ""
        ));
        items.add(new PosPurchaseOrderClient.PurchaseOrderItemDto(
                "poi-2", "prod-ace-500", "Ace", "BOX", 100, 50, 0,
                new BigDecimal("3.0000"), BigDecimal.ZERO, ""
        ));

        PosPurchaseOrderClient.PurchaseOrderDto po = managerClient.createPurchaseOrder(
                "sup-sq", "Square", null, 30, BigDecimal.ZERO, BigDecimal.ZERO, null, "", items
        );
        managerClient.approvePurchaseOrder(po.poId, "OK");

        // Dispatch to supplier
        PosPurchaseOrderClient.PurchaseOrderDto ordered = managerClient.orderPurchaseOrder(po.poId);
        assertEquals("ORDERED", ordered.status);
        assertNotNull(ordered.orderedAt);

        // 1. Partial receipt: Receive 50 out of 100 for Napa, 0 for Ace
        Map<String, Integer> partialMap = Map.of("poi-1", 50);
        PosPurchaseOrderClient.PurchaseOrderDto partial = managerClient.receiveItems(po.poId, partialMap);
        assertEquals("PARTIALLY_RECEIVED", partial.status);
        assertEquals(50, partial.items.get(0).receivedQuantity);

        // 2. Final receipt: Receive remaining 50 for Napa, and all 50 for Ace
        Map<String, Integer> finalMap = Map.of("poi-1", 50, "poi-2", 50);
        PosPurchaseOrderClient.PurchaseOrderDto closed = managerClient.receiveItems(po.poId, finalMap);
        assertEquals("CLOSED", closed.status);
        assertEquals(100, closed.items.get(0).receivedQuantity);
        assertEquals(50, closed.items.get(1).receivedQuantity);
        assertNotNull(closed.closedAt);
    }

    @Test
    @DisplayName("Test 3: Guarded cancellation prevents cancellation if goods were already received")
    void testGuardedCancellationRule() {
        List<PosPurchaseOrderClient.PurchaseOrderItemDto> items = List.of(
                new PosPurchaseOrderClient.PurchaseOrderItemDto(
                        "poi-1", "prod-1", "Prod 1", "BOX", 100, 50, 0,
                        new BigDecimal("2.0000"), BigDecimal.ZERO, ""
                )
        );

        // PO 1: Clean cancellation before receipt
        PosPurchaseOrderClient.PurchaseOrderDto po1 = managerClient.createPurchaseOrder(
                "sup-sq", "Square", null, 30, BigDecimal.ZERO, BigDecimal.ZERO, null, "", items
        );
        managerClient.approvePurchaseOrder(po1.poId, "OK");
        managerClient.orderPurchaseOrder(po1.poId);

        // Missing cancellation reason throws error
        assertThrows(IllegalArgumentException.class, () ->
                managerClient.cancelPurchaseOrder(po1.poId, "")
        );

        PosPurchaseOrderClient.PurchaseOrderDto cancelled = managerClient.cancelPurchaseOrder(
                po1.poId, "Supplier out of stock"
        );
        assertEquals("CANCELLED", cancelled.status);
        assertEquals("Supplier out of stock", cancelled.cancellationReason);

        // PO 2: Received items cannot be cancelled
        PosPurchaseOrderClient.PurchaseOrderDto po2 = managerClient.createPurchaseOrder(
                "sup-sq", "Square", null, 30, BigDecimal.ZERO, BigDecimal.ZERO, null, "", items
        );
        managerClient.approvePurchaseOrder(po2.poId, "OK");
        managerClient.orderPurchaseOrder(po2.poId);
        managerClient.receiveItems(po2.poId, Map.of("poi-1", 10)); // 10 units received

        IllegalStateException exc = assertThrows(IllegalStateException.class, () ->
                managerClient.cancelPurchaseOrder(po2.poId, "Try cancel received")
        );
        assertTrue(exc.getMessage().contains("ERR_CANNOT_CANCEL_RECEIVED_PO"));
    }

    @Test
    @DisplayName("Test 4: RBAC permissions enforcement and cross-tenant boundary isolation")
    void testRoleAuthorizationAndCrossTenantIsolation() {
        List<PosPurchaseOrderClient.PurchaseOrderItemDto> items = List.of(
                new PosPurchaseOrderClient.PurchaseOrderItemDto(
                        "poi-1", "prod-1", "Prod 1", "BOX", 100, 10, 0,
                        new BigDecimal("2.0000"), BigDecimal.ZERO, ""
                )
        );

        // Cashier cannot create PO
        assertThrows(SecurityException.class, () ->
                cashierClient.createPurchaseOrder("sup-sq", "Square", null, 30, BigDecimal.ZERO, BigDecimal.ZERO, null, "", items)
        );

        // Pharmacist can create DRAFT PO
        PosPurchaseOrderClient.PurchaseOrderDto po = pharmacistClient.createPurchaseOrder(
                "sup-sq", "Square", null, 30, BigDecimal.ZERO, BigDecimal.ZERO, null, "", items
        );
        assertEquals("DRAFT", po.status);

        // Pharmacist cannot approve or dispatch
        assertThrows(SecurityException.class, () -> pharmacistClient.approvePurchaseOrder(po.poId, ""));
        assertThrows(SecurityException.class, () -> pharmacistClient.orderPurchaseOrder(po.poId));

        // Foreign tenant cannot access or approve
        assertThrows(SecurityException.class, () -> foreignClient.getPurchaseOrder(po.poId));
        assertThrows(SecurityException.class, () -> foreignClient.approvePurchaseOrder(po.poId, ""));
    }
}

