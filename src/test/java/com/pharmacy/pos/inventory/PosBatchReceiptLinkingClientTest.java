package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosBatchReceiptLinkingClientTest
 * Architecture Decision: ADR-0168 (P168).
 * Unit tests verifying GRN multi-batch linking, idempotent posting, and duplicate-increment guards.
 */
public class PosBatchReceiptLinkingClientTest {

    @Test
    @DisplayName("Should successfully bind multiple distinct batches to a single GRN line item")
    void testAddBatchAllocationToLine_SplitAllocation_Success() {
        PosBatchReceiptLinkingClient.GrnLine line = new PosBatchReceiptLinkingClient.GrnLine(
                "grnl-1", "prod-1", "Napa Extra", 500, 500, new BigDecimal("2.1000"), false, new ArrayList<>()
        );

        // Allocate 300 units to Batch B-101
        PosBatchReceiptLinkingClient.BatchAllocation alloc1 = new PosBatchReceiptLinkingClient.BatchAllocation(
                "grnbl-1", "bat-101", "B-101", 300, new BigDecimal("2.1000"), false
        );
        boolean ok1 = PosBatchReceiptLinkingClient.addBatchAllocationToLine(line, alloc1);
        assertTrue(ok1);
        assertEquals(300, line.getAllocatedQuantity());
        assertFalse(line.isFullyAllocated);

        // Allocate 200 units to Batch B-102
        PosBatchReceiptLinkingClient.BatchAllocation alloc2 = new PosBatchReceiptLinkingClient.BatchAllocation(
                "grnbl-2", "bat-102", "B-102", 200, new BigDecimal("2.1000"), false
        );
        boolean ok2 = PosBatchReceiptLinkingClient.addBatchAllocationToLine(line, alloc2);
        assertTrue(ok2);
        assertEquals(500, line.getAllocatedQuantity());
        assertTrue(line.isFullyAllocated);
    }

    @Test
    @DisplayName("Should reject allocation when total allocated units exceed line received quantity")
    void testAddBatchAllocationToLine_ExceedsReceivedQuantity_Fails() {
        PosBatchReceiptLinkingClient.GrnLine line = new PosBatchReceiptLinkingClient.GrnLine(
                "grnl-2", "prod-1", "Napa Extra", 100, 100, new BigDecimal("2.1000"), false, new ArrayList<>()
        );

        PosBatchReceiptLinkingClient.BatchAllocation allocExcess = new PosBatchReceiptLinkingClient.BatchAllocation(
                "grnbl-ex", "bat-101", "B-101", 120, new BigDecimal("2.1000"), false
        );
        boolean ok = PosBatchReceiptLinkingClient.addBatchAllocationToLine(line, allocExcess);
        assertFalse(ok);
        assertEquals(0, line.getAllocatedQuantity());
    }

    @Test
    @DisplayName("Should post GRN and increment batch stock exactly once across all allocations")
    void testPostGrn_IncrementsBatchStockExactlyOnce_Success() {
        PosBatchReceiptLinkingClient.GrnLine line = new PosBatchReceiptLinkingClient.GrnLine(
                "grnl-3", "prod-1", "Napa Extra", 150, 150, new BigDecimal("2.1000"), true, new ArrayList<>()
        );
        line.allocations.add(new PosBatchReceiptLinkingClient.BatchAllocation(
                "link-1", "bat-1", "B-1", 100, new BigDecimal("2.1000"), false
        ));
        line.allocations.add(new PosBatchReceiptLinkingClient.BatchAllocation(
                "link-2", "bat-2", "B-2", 50, new BigDecimal("2.1000"), false
        ));

        PosBatchReceiptLinkingClient.GrnNote grn = new PosBatchReceiptLinkingClient.GrnNote(
                "grn-1", "GRN-2026-001", "ten-1", "br-1", "sup-1", "Beximco",
                "PO-1", "INV-1", LocalDate.now(), "RECEIVED", Collections.singletonList(line)
        );

        Map<String, Integer> stockMap = new HashMap<>();
        stockMap.put("bat-1", 0);
        stockMap.put("bat-2", 10);

        PosBatchReceiptLinkingClient.PostingResult res = PosBatchReceiptLinkingClient.postGrn(grn, stockMap);
        assertTrue(res.isSuccessful);
        assertEquals(2, res.postedAllocationsCount);
        assertEquals(150, res.totalQuantityIncremented);

        assertEquals(100, stockMap.get("bat-1"));
        assertEquals(60, stockMap.get("bat-2")); // 10 + 50 = 60
        assertEquals("POSTED", grn.status);
    }

    @Test
    @DisplayName("Should block duplicate posting and prevent duplicate stock increments")
    void testPostGrn_AntiDuplicateGuard_BlocksSecondPost() {
        PosBatchReceiptLinkingClient.GrnLine line = new PosBatchReceiptLinkingClient.GrnLine(
                "grnl-4", "prod-1", "Napa Extra", 100, 100, new BigDecimal("2.1000"), true, new ArrayList<>()
        );
        line.allocations.add(new PosBatchReceiptLinkingClient.BatchAllocation(
                "link-3", "bat-1", "B-1", 100, new BigDecimal("2.1000"), false
        ));

        PosBatchReceiptLinkingClient.GrnNote grn = new PosBatchReceiptLinkingClient.GrnNote(
                "grn-2", "GRN-2026-002", "ten-1", "br-1", "sup-1", "Beximco",
                "PO-2", "INV-2", LocalDate.now(), "RECEIVED", Collections.singletonList(line)
        );

        Map<String, Integer> stockMap = new HashMap<>();
        stockMap.put("bat-1", 50);

        // 1st Post - succeeds
        PosBatchReceiptLinkingClient.PostingResult res1 = PosBatchReceiptLinkingClient.postGrn(grn, stockMap);
        assertTrue(res1.isSuccessful);
        assertEquals(150, stockMap.get("bat-1"));

        // 2nd Post (Duplicate retry) - blocked with ERR_ALREADY_POSTED!
        PosBatchReceiptLinkingClient.PostingResult res2 = PosBatchReceiptLinkingClient.postGrn(grn, stockMap);
        assertFalse(res2.isSuccessful);
        assertEquals("ERR_ALREADY_POSTED", res2.errorCode);
        assertEquals(150, stockMap.get("bat-1")); // Stock NOT incremented a second time!
        assertTrue(res2.messageBn.contains("ডুপ্লিকেট স্টক বৃদ্ধি সম্পূর্ণ নিষিদ্ধ"));
    }

    @Test
    @DisplayName("Should block posting when one or more lines are not fully allocated")
    void testPostGrn_IncompleteAllocation_BlocksPost() {
        PosBatchReceiptLinkingClient.GrnLine line = new PosBatchReceiptLinkingClient.GrnLine(
                "grnl-5", "prod-1", "Napa Extra", 100, 100, new BigDecimal("2.1000"), false, new ArrayList<>()
        );
        // Only 40 out of 100 allocated
        line.allocations.add(new PosBatchReceiptLinkingClient.BatchAllocation(
                "link-4", "bat-1", "B-1", 40, new BigDecimal("2.1000"), false
        ));

        PosBatchReceiptLinkingClient.GrnNote grn = new PosBatchReceiptLinkingClient.GrnNote(
                "grn-3", "GRN-2026-003", "ten-1", "br-1", "sup-1", "Beximco",
                "PO-3", "INV-3", LocalDate.now(), "RECEIVED", Collections.singletonList(line)
        );

        Map<String, Integer> stockMap = new HashMap<>();
        PosBatchReceiptLinkingClient.PostingResult res = PosBatchReceiptLinkingClient.postGrn(grn, stockMap);
        assertFalse(res.isSuccessful);
        assertEquals("ERR_ALLOCATION_MISMATCH", res.errorCode);
        assertEquals("RECEIVED", grn.status);
    }

    @Test
    @DisplayName("Should successfully reverse posted GRN and deduct inventory")
    void testReverseGrn_DeductsStock_Success() {
        PosBatchReceiptLinkingClient.GrnLine line = new PosBatchReceiptLinkingClient.GrnLine(
                "grnl-6", "prod-1", "Napa Extra", 50, 50, new BigDecimal("2.1000"), true, new ArrayList<>()
        );
        line.allocations.add(new PosBatchReceiptLinkingClient.BatchAllocation(
                "link-5", "bat-1", "B-1", 50, new BigDecimal("2.1000"), true
        ));

        PosBatchReceiptLinkingClient.GrnNote grn = new PosBatchReceiptLinkingClient.GrnNote(
                "grn-4", "GRN-2026-004", "ten-1", "br-1", "sup-1", "Beximco",
                "PO-4", "INV-4", LocalDate.now(), "POSTED", Collections.singletonList(line)
        );

        Map<String, Integer> stockMap = new HashMap<>();
        stockMap.put("bat-1", 50);

        PosBatchReceiptLinkingClient.ReversalResult res = PosBatchReceiptLinkingClient.reverseGrn(grn, stockMap, Collections.emptyMap());
        assertTrue(res.isSuccessful);
        assertEquals(50, res.totalQuantityDecremented);
        assertEquals(0, stockMap.get("bat-1"));
        assertEquals("REVERSED", grn.status);
    }

    @Test
    @DisplayName("Should block reversal if stock from this GRN has already been consumed / dispensed")
    void testReverseGrn_StockAlreadyConsumed_BlocksReversal() {
        PosBatchReceiptLinkingClient.GrnLine line = new PosBatchReceiptLinkingClient.GrnLine(
                "grnl-7", "prod-1", "Napa Extra", 100, 100, new BigDecimal("2.1000"), true, new ArrayList<>()
        );
        line.allocations.add(new PosBatchReceiptLinkingClient.BatchAllocation(
                "link-6", "bat-1", "B-1", 100, new BigDecimal("2.1000"), true
        ));

        PosBatchReceiptLinkingClient.GrnNote grn = new PosBatchReceiptLinkingClient.GrnNote(
                "grn-5", "GRN-2026-005", "ten-1", "br-1", "sup-1", "Beximco",
                "PO-5", "INV-5", LocalDate.now(), "POSTED", Collections.singletonList(line)
        );

        Map<String, Integer> stockMap = new HashMap<>();
        stockMap.put("bat-1", 30); // 70 units already dispensed!

        PosBatchReceiptLinkingClient.ReversalResult res = PosBatchReceiptLinkingClient.reverseGrn(grn, stockMap, Collections.emptyMap());
        assertFalse(res.isSuccessful);
        assertEquals("ERR_CANNOT_REVERSE_CONSUMED_STOCK", res.errorCode);
        assertEquals(30, stockMap.get("bat-1")); // Unchanged
        assertEquals("POSTED", grn.status);
    }
}

