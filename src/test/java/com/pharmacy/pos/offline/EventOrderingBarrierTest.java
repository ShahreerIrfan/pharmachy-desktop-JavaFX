package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EventOrderingBarrierTest (ADR-0316 / P316)
 * Verifies client-side topological event sorting, causal dependency barriers (Customer -> Sale, GRN -> Sale),
 * and circular dependency rejection.
 */
public class EventOrderingBarrierTest {

    @Test
    void testTopologicalSortEnforcesCustomerAndGrnBeforeSale() {
        // Unordered batch: Sale declared first, but depends on Customer and GRN
        var saleNode = new EventOrderingBarrierManager.OfflineEventNode(
                "cmd-sale-1", "OFFLINE_POS_SALE", 3,
                List.of("cmd-cust-1", "cmd-grn-1")
        );
        var custNode = new EventOrderingBarrierManager.OfflineEventNode(
                "cmd-cust-1", "CUSTOMER_CREATE", 1,
                List.of()
        );
        var grnNode = new EventOrderingBarrierManager.OfflineEventNode(
                "cmd-grn-1", "GOODS_RECEIPT_POST", 2,
                List.of()
        );
        var returnNode = new EventOrderingBarrierManager.OfflineEventNode(
                "cmd-ret-1", "POS_RETURN", 4,
                List.of("cmd-sale-1")
        );

        List<EventOrderingBarrierManager.OfflineEventNode> unordered = List.of(
                saleNode, returnNode, custNode, grnNode
        );

        var sorted = EventOrderingBarrierManager.topologicalSort(unordered);
        assertEquals(4, sorted.size());

        // Sale must come AFTER Customer and GRN
        int custIdx = -1, grnIdx = -1, saleIdx = -1, retIdx = -1;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).commandId.equals("cmd-cust-1")) custIdx = i;
            if (sorted.get(i).commandId.equals("cmd-grn-1")) grnIdx = i;
            if (sorted.get(i).commandId.equals("cmd-sale-1")) saleIdx = i;
            if (sorted.get(i).commandId.equals("cmd-ret-1")) retIdx = i;
        }

        assertTrue(custIdx < saleIdx, "Customer must precede Sale");
        assertTrue(grnIdx < saleIdx, "GRN must precede Sale");
        assertTrue(saleIdx < retIdx, "Sale must precede Return");

        assertTrue(EventOrderingBarrierManager.verifyCausalOrdering(sorted));
    }

    @Test
    void testCircularDependencyThrowsException() {
        var nodeA = new EventOrderingBarrierManager.OfflineEventNode(
                "cmd-A", "TYPE_A", 1, List.of("cmd-B")
        );
        var nodeB = new EventOrderingBarrierManager.OfflineEventNode(
                "cmd-B", "TYPE_B", 2, List.of("cmd-A")
        );

        assertThrows(IllegalArgumentException.class, () -> {
            EventOrderingBarrierManager.topologicalSort(List.of(nodeA, nodeB));
        });
    }
}
