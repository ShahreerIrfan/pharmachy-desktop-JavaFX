package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosLooseTabletSalesClientTest
 * Architecture Decision: ADR-0164 (P164).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Unit tests for loose tablet sales, partial strip dispensing,
 * base-piece stock conservation, FEFO allocation, and blister guardrails in JavaFX POS.
 */
public class PosLooseTabletSalesClientTest {

    private PosLooseTabletSalesClient client;
    private PosLooseTabletSalesClient.LooseSalesPolicy allowedLoosePolicy;
    private PosLooseTabletSalesClient.LooseSalesPolicy prohibitedLoosePolicy;
    private PosLooseTabletSalesClient.LooseSalesPolicy surchargePolicy;

    @BeforeEach
    public void setUp() {
        client = new PosLooseTabletSalesClient();

        // Paracetamol 500mg: Regular tablet, loose sales permitted
        allowedLoosePolicy = new PosLooseTabletSalesClient.LooseSalesPolicy(
                "ltsc-01",
                "ten-01",
                "br-01",
                "prod-para-500",
                "Napa 500mg Tablet",
                true, // allow loose sales
                1,
                100,
                10, // strip size = 10 tablets
                new BigDecimal("30.00"), // 30 BDT per strip
                new BigDecimal("3.00"),  // 3 BDT per loose tablet
                false,
                BigDecimal.ZERO,
                ""
        );

        // Effervescent / Hygroscopic Vitamin C: Loose tablets strictly prohibited
        prohibitedLoosePolicy = new PosLooseTabletSalesClient.LooseSalesPolicy(
                "ltsc-02",
                "ten-01",
                "br-01",
                "prod-vitc-eff",
                "Ceevit Effervescent 1000mg",
                false, // PROHIBITED
                1,
                50,
                10,
                new BigDecimal("150.00"),
                new BigDecimal("15.00"),
                false,
                BigDecimal.ZERO,
                "Hygroscopic formulation degrades upon breaking blister seal."
        );

        // Antibiotic with 5% loose unit surcharge
        surchargePolicy = new PosLooseTabletSalesClient.LooseSalesPolicy(
                "ltsc-03",
                "ten-01",
                "br-01",
                "prod-azithro-500",
                "Zimax 500mg",
                true,
                1,
                100,
                10,
                new BigDecimal("350.00"), // 35 BDT per tablet base
                new BigDecimal("35.00"),
                true, // surcharge active
                new BigDecimal("10.00"), // 10% surcharge on loose pieces -> 38.50 BDT
                ""
        );
    }

    @Test
    @DisplayName("1. Quote calculation: Exact strip and loose pieces price with zero Decimal drift")
    public void testValidLooseTabletSaleQuote() {
        // 1 strip (10 tablets) + 4 loose tablets = 14 tablets total
        PosLooseTabletSalesClient.QuoteCalculation quote = client.calculateQuote(
                allowedLoosePolicy,
                1, 4, null, 100
        );

        assertTrue(quote.isAllowed);
        assertEquals(1, quote.normalizedStrips);
        assertEquals(4, quote.normalizedLoosePieces);
        assertEquals(14, quote.totalBaseUnits);
        assertEquals(new BigDecimal("30.00"), quote.stripTotal);
        assertEquals(new BigDecimal("12.00"), quote.looseTotal); // 4 * 3.00
        assertEquals(new BigDecimal("42.00"), quote.finalQuoteAmount);
        assertTrue(quote.isStockSufficient);
    }

    @Test
    @DisplayName("2. Prohibition guardrail: Selling loose pieces of moisture-sensitive tablets is rejected")
    public void testProhibitedLooseTabletSale() {
        // Attempting to buy 3 loose tablets of effervescent medicine
        PosLooseTabletSalesClient.QuoteCalculation quote = client.calculateQuote(
                prohibitedLoosePolicy,
                0, 3, null, 100
        );

        assertFalse(quote.isAllowed);
        assertEquals("ERR_LOOSE_TABLET_SALES_PROHIBITED", quote.errorCode);
        assertTrue(quote.errorMessageEn.contains("Hygroscopic"));
        assertTrue(quote.errorMessageBn.contains("নিষিদ্ধ"));
    }

    @Test
    @DisplayName("3. Prohibition exception: Full strips of moisture-sensitive medicine are permitted")
    public void testProhibitedAllowsFullStripsOnly() {
        // 2 full strips (20 tablets, 0 loose) should be accepted even if loose sales are prohibited
        PosLooseTabletSalesClient.QuoteCalculation quote = client.calculateQuote(
                prohibitedLoosePolicy,
                2, 0, null, 50
        );

        assertTrue(quote.isAllowed);
        assertEquals(2, quote.normalizedStrips);
        assertEquals(0, quote.normalizedLoosePieces);
        assertEquals(20, quote.totalBaseUnits);
        assertEquals(new BigDecimal("300.00"), quote.finalQuoteAmount);
    }

    @Test
    @DisplayName("4. Surcharge calculation: Loose unit surcharge applies only to loose pieces")
    public void testSurchargeCalculation() {
        // 1 strip + 2 loose pieces with 10% surcharge on loose
        // Strip = 350.00, Loose unit = 35.00 * 1.10 = 38.50 -> 2 * 38.50 = 77.00 -> Total = 427.00
        PosLooseTabletSalesClient.QuoteCalculation quote = client.calculateQuote(
                surchargePolicy,
                1, 2, null, 100
        );

        assertTrue(quote.isAllowed);
        assertEquals(1, quote.normalizedStrips);
        assertEquals(2, quote.normalizedLoosePieces);
        assertEquals(12, quote.totalBaseUnits);
        assertEquals(new BigDecimal("350.00"), quote.stripTotal);
        assertEquals(new BigDecimal("77.00"), quote.looseTotal);
        assertEquals(new BigDecimal("427.00"), quote.finalQuoteAmount);
    }

    @Test
    @DisplayName("5. FEFO Allocation: Checkouts deduct from the earliest unexpired batch first")
    public void testFefoBatchAllocation() {
        LocalDate today = LocalDate.of(2026, 11, 4);

        PosLooseTabletSalesClient.BatchStockItem batchEarly = new PosLooseTabletSalesClient.BatchStockItem(
                "batch-01", "B-EARLY", LocalDate.of(2026, 12, 1), 10, false
        );
        PosLooseTabletSalesClient.BatchStockItem batchLate = new PosLooseTabletSalesClient.BatchStockItem(
                "batch-02", "B-LATE", LocalDate.of(2027, 6, 1), 50, false
        );

        List<PosLooseTabletSalesClient.BatchStockItem> batches = Arrays.asList(batchLate, batchEarly);

        // Buy 14 tablets: should take 10 from batchEarly and 4 from batchLate
        PosLooseTabletSalesClient.CheckoutExecution checkout = client.executeCheckout(
                allowedLoosePolicy,
                batches,
                1, 4, null,
                "Rahim Ahmed", "01711000000", "CASH",
                today
        );

        assertTrue(checkout.success);
        assertNotNull(checkout.invoiceId);
        assertEquals(2, checkout.allocations.size());

        // First allocation is batchEarly
        assertEquals("batch-01", checkout.allocations.get(0).batchId);
        assertEquals(10, checkout.allocations.get(0).deductedBaseUnits);
        assertEquals(0, batchEarly.quantityBaseUnits);

        // Second allocation is batchLate
        assertEquals("batch-02", checkout.allocations.get(1).batchId);
        assertEquals(4, checkout.allocations.get(1).deductedBaseUnits);
        assertEquals(46, batchLate.quantityBaseUnits);
    }

    @Test
    @DisplayName("6. Compliance: Expired batches are excluded from dispensing")
    public void testBlockExpiredBatch() {
        LocalDate today = LocalDate.of(2026, 11, 4);

        // Expired batch yesterday
        PosLooseTabletSalesClient.BatchStockItem expiredBatch = new PosLooseTabletSalesClient.BatchStockItem(
                "batch-exp", "B-EXPIRED", LocalDate.of(2026, 11, 3), 100, false
        );
        List<PosLooseTabletSalesClient.BatchStockItem> batches = Collections.singletonList(expiredBatch);

        PosLooseTabletSalesClient.CheckoutExecution checkout = client.executeCheckout(
                allowedLoosePolicy,
                batches,
                0, 5, null,
                "Customer", "01811000000", "CASH",
                today
        );

        assertFalse(checkout.success);
        assertEquals("ERR_INSUFFICIENT_STOCK", checkout.errorCode);
        assertEquals(100, expiredBatch.quantityBaseUnits); // Stock untouched
    }

    @Test
    @DisplayName("7. Stock conservation: Reversal re-credits exact deducted integer base units")
    public void testSaleReversalRestoresStock() {
        LocalDate today = LocalDate.of(2026, 11, 4);

        PosLooseTabletSalesClient.BatchStockItem batch = new PosLooseTabletSalesClient.BatchStockItem(
                "batch-01", "B-01", LocalDate.of(2027, 1, 1), 30, false
        );
        Map<String, PosLooseTabletSalesClient.BatchStockItem> lookup = new HashMap<>();
        lookup.put(batch.batchId, batch);

        PosLooseTabletSalesClient.CheckoutExecution checkout = client.executeCheckout(
                allowedLoosePolicy,
                Collections.singletonList(batch),
                0, 7, null,
                "Customer", "01911000000", "CASH",
                today
        );

        assertTrue(checkout.success);
        assertEquals(23, batch.quantityBaseUnits); // 30 - 7

        // Reversal
        boolean reversed = client.reverseSale(checkout, lookup);
        assertTrue(reversed);
        assertEquals(30, batch.quantityBaseUnits); // Restored back to 30
    }
}
