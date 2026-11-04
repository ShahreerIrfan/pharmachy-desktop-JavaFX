package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosBatchEntityClientTest
 * Architecture Decision: ADR-0166 (P166).
 * Unit tests verifying batch date invariants, FEFO ordering, and recall blocks.
 */
public class PosBatchEntityClientTest {

    private final LocalDate today = LocalDate.of(2026, 11, 4);

    @Test
    @DisplayName("Should successfully validate valid future expiry and older manufacturing date")
    void testValidateBatchDates_Success() {
        LocalDate mfg = LocalDate.of(2025, 1, 1);
        LocalDate exp = LocalDate.of(2027, 1, 1);

        PosBatchEntityClient.BatchValidationResult res = PosBatchEntityClient.validateBatchDates(mfg, exp, today);
        assertTrue(res.isValid);
        assertNull(res.errorCode);
    }

    @Test
    @DisplayName("Should reject batch when expiry date is prior to or same as manufacturing date")
    void testValidateBatchDates_ExpiryBeforeManufacturing_Fails() {
        LocalDate mfg = LocalDate.of(2026, 1, 1);
        LocalDate exp = LocalDate.of(2025, 12, 31);

        PosBatchEntityClient.BatchValidationResult res = PosBatchEntityClient.validateBatchDates(mfg, exp, today);
        assertFalse(res.isValid);
        assertEquals("ERR_EXPIRY_BEFORE_MANUFACTURING", res.errorCode);
    }

    @Test
    @DisplayName("Should reject batch creation when expiry date is in the past")
    void testValidateBatchDates_ExpiredOnCreation_Fails() {
        LocalDate mfg = LocalDate.of(2024, 1, 1);
        LocalDate exp = LocalDate.of(2026, 10, 1); // Expired prior to today (2026-11-04)

        PosBatchEntityClient.BatchValidationResult res = PosBatchEntityClient.validateBatchDates(mfg, exp, today);
        assertFalse(res.isValid);
        assertEquals("ERR_BATCH_EXPIRED_ON_CREATION", res.errorCode);
    }

    @Test
    @DisplayName("Should allow dispensing valid active non-expired batch with sufficient stock")
    void testEvaluateDispensability_Active_Success() {
        PosBatchEntityClient.BatchRecord batch = new PosBatchEntityClient.BatchRecord(
                "bat-001", "ten-1", "br-1", "prod-1", "Napa Extra 500mg", "B-101",
                "Beximco", 100, new BigDecimal("2.50"), LocalDate.of(2025, 1, 1),
                LocalDate.of(2027, 6, 1), "ACTIVE", "ROOM_TEMPERATURE_15_25C",
                "LOT-A", 30, false, null, null, true
        );

        PosBatchEntityClient.BatchDispenseResult res = PosBatchEntityClient.evaluateDispensability(batch, null, 10, today);
        assertTrue(res.isDispensable);
        assertNull(res.errorCode);
        assertEquals("bat-001", res.batchId);
    }

    @Test
    @DisplayName("Should hard block dispensing when statutory recall notice affects the batch")
    void testEvaluateDispensability_StatutoryRecall_BlocksDispense() {
        PosBatchEntityClient.BatchRecord batch = new PosBatchEntityClient.BatchRecord(
                "bat-002", "ten-1", "br-1", "prod-1", "Napa Extra 500mg", "B-DEFECTIVE",
                "Beximco", 50, new BigDecimal("2.50"), LocalDate.of(2025, 1, 1),
                LocalDate.of(2027, 6, 1), "ACTIVE", "ROOM_TEMPERATURE_15_25C",
                "LOT-B", 30, false, null, null, true
        );

        PosBatchEntityClient.RecallNotice recallNotice = new PosBatchEntityClient.RecallNotice(
                "rec-001", "NOT-2026-09", "prod-1", "BATCH_SPECIFIC",
                Collections.singletonList("B-DEFECTIVE"), "Sub-potent formulation detected", true
        );

        PosBatchEntityClient.BatchDispenseResult res = PosBatchEntityClient.evaluateDispensability(batch, recallNotice, 5, today);
        assertFalse(res.isDispensable);
        assertEquals("ERR_BATCH_RECALLED", res.errorCode);
        assertTrue(res.messageBn.contains("প্রত্যাহার"));
    }

    @Test
    @DisplayName("Should block dispensing expired batch")
    void testEvaluateDispensability_Expired_BlocksDispense() {
        PosBatchEntityClient.BatchRecord batch = new PosBatchEntityClient.BatchRecord(
                "bat-003", "ten-1", "br-1", "prod-1", "Napa Extra 500mg", "B-OLD",
                "Beximco", 50, new BigDecimal("2.50"), LocalDate.of(2024, 1, 1),
                LocalDate.of(2026, 5, 1), "ACTIVE", "ROOM_TEMPERATURE_15_25C",
                "LOT-C", 30, false, null, null, true
        );

        PosBatchEntityClient.BatchDispenseResult res = PosBatchEntityClient.evaluateDispensability(batch, null, 5, today);
        assertFalse(res.isDispensable);
        assertEquals("ERR_BATCH_EXPIRED", res.errorCode);
        assertTrue(res.messageBn.contains("মেয়াদ উত্তীর্ণ"));
    }

    @Test
    @DisplayName("Should block dispensing quarantined batch")
    void testEvaluateDispensability_Quarantined_BlocksDispense() {
        PosBatchEntityClient.BatchRecord batch = new PosBatchEntityClient.BatchRecord(
                "bat-004", "ten-1", "br-1", "prod-1", "Napa Extra 500mg", "B-QUAR",
                "Beximco", 50, new BigDecimal("2.50"), LocalDate.of(2025, 1, 1),
                LocalDate.of(2027, 5, 1), "QUARANTINED", "ROOM_TEMPERATURE_15_25C",
                "LOT-D", 30, true, null, null, true
        );

        PosBatchEntityClient.BatchDispenseResult res = PosBatchEntityClient.evaluateDispensability(batch, null, 5, today);
        assertFalse(res.isDispensable);
        assertEquals("ERR_BATCH_QUARANTINED", res.errorCode);
        assertTrue(res.messageBn.contains("কোয়ারেন্টাইনে"));
    }

    @Test
    @DisplayName("Should block dispensing when requested units exceed available batch stock")
    void testEvaluateDispensability_InsufficientStock_BlocksDispense() {
        PosBatchEntityClient.BatchRecord batch = new PosBatchEntityClient.BatchRecord(
                "bat-005", "ten-1", "br-1", "prod-1", "Napa Extra 500mg", "B-LOW",
                "Beximco", 8, new BigDecimal("2.50"), LocalDate.of(2025, 1, 1),
                LocalDate.of(2027, 5, 1), "ACTIVE", "ROOM_TEMPERATURE_15_25C",
                "LOT-E", 30, false, null, null, true
        );

        PosBatchEntityClient.BatchDispenseResult res = PosBatchEntityClient.evaluateDispensability(batch, null, 10, today);
        assertFalse(res.isDispensable);
        assertEquals("ERR_INSUFFICIENT_STOCK", res.errorCode);
        assertTrue(res.messageBn.contains("পর্যাপ্ত স্টক নেই"));
    }

    @Test
    @DisplayName("Should correctly select earliest expiring valid batch (FEFO order)")
    void testSelectFefoBatch_PrioritizesEarliestNonExpiredBatch() {
        PosBatchEntityClient.BatchRecord expiredBatch = new PosBatchEntityClient.BatchRecord(
                "bat-exp", "ten-1", "br-1", "prod-1", "Napa Extra", "B-EXP",
                "Beximco", 100, new BigDecimal("2.50"), LocalDate.of(2024, 1, 1),
                LocalDate.of(2026, 1, 1), "ACTIVE", "ROOM_TEMPERATURE_15_25C",
                "L1", 30, false, null, null, true
        );

        PosBatchEntityClient.BatchRecord farFutureBatch = new PosBatchEntityClient.BatchRecord(
                "bat-far", "ten-1", "br-1", "prod-1", "Napa Extra", "B-FAR",
                "Beximco", 100, new BigDecimal("2.50"), LocalDate.of(2025, 1, 1),
                LocalDate.of(2028, 1, 1), "ACTIVE", "ROOM_TEMPERATURE_15_25C",
                "L2", 30, false, null, null, true
        );

        PosBatchEntityClient.BatchRecord nearFutureBatch = new PosBatchEntityClient.BatchRecord(
                "bat-near", "ten-1", "br-1", "prod-1", "Napa Extra", "B-NEAR",
                "Beximco", 100, new BigDecimal("2.50"), LocalDate.of(2025, 1, 1),
                LocalDate.of(2027, 1, 1), "ACTIVE", "ROOM_TEMPERATURE_15_25C",
                "L3", 30, false, null, null, true
        );

        List<PosBatchEntityClient.BatchRecord> candidateList = Arrays.asList(farFutureBatch, expiredBatch, nearFutureBatch);

        PosBatchEntityClient.BatchRecord selected = PosBatchEntityClient.selectFefoBatch(candidateList, "prod-1", 10, today);
        assertNotNull(selected);
        assertEquals("bat-near", selected.batchId);
        assertEquals("B-NEAR", selected.batchNumber);
    }

    @Test
    @DisplayName("Should deduct stock base units and transition to DEPLETED on zero")
    void testDeductBatchStock_DeductsAndSetsDepletedWhenZero() {
        PosBatchEntityClient.BatchRecord batch = new PosBatchEntityClient.BatchRecord(
                "bat-006", "ten-1", "br-1", "prod-1", "Napa Extra", "B-DEP",
                "Beximco", 15, new BigDecimal("2.50"), LocalDate.of(2025, 1, 1),
                LocalDate.of(2027, 1, 1), "ACTIVE", "ROOM_TEMPERATURE_15_25C",
                "L4", 30, false, null, null, true
        );

        boolean d1 = PosBatchEntityClient.deductBatchStock(batch, 10);
        assertTrue(d1);
        assertEquals(5, batch.quantityBaseUnits);
        assertEquals("ACTIVE", batch.status);

        boolean d2 = PosBatchEntityClient.deductBatchStock(batch, 5);
        assertTrue(d2);
        assertEquals(0, batch.quantityBaseUnits);
        assertEquals("DEPLETED", batch.status);

        boolean d3 = PosBatchEntityClient.deductBatchStock(batch, 1);
        assertFalse(d3); // Insufficient stock
    }
}

