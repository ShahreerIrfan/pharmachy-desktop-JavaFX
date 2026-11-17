package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStockSegregationTest
 * Architecture Decision: ADR-0207 (P207).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 */
public class PosStockSegregationTest {

    private PosStockSegregationClient client;

    @BeforeEach
    void setUp() {
        client = new PosStockSegregationClient();
        client.registerBatch(new PosStockSegregationClient.BatchStockDto(
                "batch-zith", "BATCH-ZITH-01", 100, new BigDecimal("35.00"), "SHELF-A2", false
        ));
    }

    @Test
    @DisplayName("Segregating stock immediately deducts from available sellable inventory")
    void testSegregateStockDeduction() {
        assertEquals(100, client.getBatch("batch-zith").getAvailableQuantity());

        var rec = client.segregateStock(
                "ten-01", "br-01", "batch-zith", 30,
                PosStockSegregationClient.SegregationCategory.DAMAGED,
                "Outer carton crushed.", "DAMAGED-BAY",
                "usr-pharm", "STAFF_PHARMACIST"
        );

        assertEquals(PosStockSegregationClient.SegregationStatus.SEGREGATED, rec.status);
        assertEquals(30, client.getBatch("batch-zith").reservedQuantityBaseUnits);
        assertEquals(70, client.getBatch("batch-zith").getAvailableQuantity());
    }

    @Test
    @DisplayName("QA pharmacist clears return with documented justification (>= 15 chars)")
    void testReleaseToSellableDocumentedRule() {
        var rec = client.segregateStock(
                "ten-01", "br-01", "batch-zith", 15,
                PosStockSegregationClient.SegregationCategory.RETURNED_CUSTOMER,
                "Customer return within 15 mins.", "RETURNS-HOLD",
                "usr-pharm", "STAFF_PHARMACIST"
        );

        // Less than 15 chars fails
        var ex = assertThrows(IllegalArgumentException.class, () ->
                client.releaseToSellable(rec.segregationId, "Seal ok", "SHELF-A2", "usr-pharm", "STAFF_PHARMACIST")
        );
        assertTrue(ex.getMessage().contains("ERR_RELEASE_JUSTIFICATION_REQUIRED"));

        // Valid justification restores availability
        var released = client.releaseToSellable(
                rec.segregationId,
                "Factory packaging seal verified intact and authentic.",
                "SHELF-A2", "usr-pharm", "STAFF_PHARMACIST"
        );

        assertEquals(PosStockSegregationClient.SegregationStatus.RELEASED_TO_SELLABLE, released.status);
        assertEquals(100, client.getBatch("batch-zith").getAvailableQuantity());
    }

    @Test
    @DisplayName("Recalled or counterfeit stock is strictly prohibited from release")
    void testProhibitedReleaseRecalled() {
        var rec = client.segregateStock(
                "ten-01", "br-01", "batch-zith", 20,
                PosStockSegregationClient.SegregationCategory.RECALLED,
                "DGDA recall notice.", "RECALL-SAFE",
                "usr-pharm", "STAFF_PHARMACIST"
        );

        var ex = assertThrows(IllegalStateException.class, () ->
                client.releaseToSellable(rec.segregationId, "Attempting release of recalled stock.", "SHELF-A2", "usr-pharm", "STAFF_PHARMACIST")
        );
        assertTrue(ex.getMessage().contains("ERR_PROHIBITED_RELEASE"));
    }

    @Test
    @DisplayName("Disposal deduction reduces physical batch stock and releases reservation")
    void testDisposalDeduction() {
        var rec = client.segregateStock(
                "ten-01", "br-01", "batch-zith", 40,
                PosStockSegregationClient.SegregationCategory.DAMAGED,
                "Moisture damage.", "DAMAGED-BAY",
                "usr-pharm", "STAFF_PHARMACIST"
        );

        var disposed = client.disposeOrReturnStock(
                rec.segregationId,
                PosStockSegregationClient.SegregationStatus.DISPOSED_WRITTEN_OFF,
                "DISP-001", "Incinerated per policy.",
                "usr-mgr", "BRANCH_MANAGER"
        );

        assertEquals(PosStockSegregationClient.SegregationStatus.DISPOSED_WRITTEN_OFF, disposed.status);
        assertEquals(60, client.getBatch("batch-zith").quantityBaseUnits);
        assertEquals(0, client.getBatch("batch-zith").reservedQuantityBaseUnits);
    }

    @Test
    @DisplayName("Cashier role is strictly denied from segregating stock")
    void testCashierDenied() {
        var ex = assertThrows(SecurityException.class, () ->
                client.segregateStock(
                        "ten-01", "br-01", "batch-zith", 10,
                        PosStockSegregationClient.SegregationCategory.DAMAGED,
                        "Dropped box.", "BAY",
                        "usr-c", "CASHIER"
                )
        );
        assertTrue(ex.getMessage().contains("ERR_STAFF_ROLE_DENIED"));
    }
}
