package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosVendorPriceListClientTest
 * Architecture Decision: ADR-0183 (P183).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 */
class PosVendorPriceListClientTest {

    private Map<String, PosVendorPriceListClient.VendorPriceEntryDto> priceEntryStore;
    private PosVendorPriceListClient managerClient;
    private PosVendorPriceListClient cashierClient;
    private PosVendorPriceListClient foreignClient;

    @BeforeEach
    void setUp() {
        priceEntryStore = new HashMap<>();

        managerClient = new PosVendorPriceListClient(
                "tenant-alpha", "branch-main", "BRANCH_MANAGER", "user-mgr-1", false, priceEntryStore
        );
        cashierClient = new PosVendorPriceListClient(
                "tenant-alpha", "branch-main", "CASHIER", "user-csh-1", false, priceEntryStore
        );
        foreignClient = new PosVendorPriceListClient(
                "tenant-beta", "branch-north", "BRANCH_MANAGER", "user-mgr-2", false, priceEntryStore
        );
    }

    @Test
    @DisplayName("Test 1: Valid price entry creation with trade discount and net unit cost calculation")
    void testCreateAndRetrievePriceEntryWithDiscountAndNetCostCalculation() {
        PosVendorPriceListClient.VendorPriceEntryDto entry = managerClient.createPriceEntry(
                "sup-square-001",
                "Square Pharmaceuticals Depot",
                "prod-napa-500",
                "Napa 500mg Tablet",
                "SQ-NAP-500",
                "BOX",
                100,
                new BigDecimal("8.5000"),
                new BigDecimal("10.0000"),
                new BigDecimal("10.00"), // 10% discount -> net 7.6500
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                50,
                100,
                5,
                true,
                "Annual framework contract"
        );

        assertNotNull(entry);
        assertTrue(entry.priceEntryId.startsWith("vpl-"));
        assertEquals("tenant-alpha", entry.tenantId);
        assertEquals(0, entry.unitCostPrice.compareTo(new BigDecimal("8.5000")));
        assertEquals(0, entry.packageCostPrice.compareTo(new BigDecimal("850.0000")));
        assertEquals(0, entry.mrpUnit.compareTo(new BigDecimal("10.0000")));
        assertEquals(0, entry.discountPercentage.compareTo(new BigDecimal("10.00")));
        assertEquals(0, entry.netUnitCost.compareTo(new BigDecimal("7.6500")));
        assertTrue(entry.isActive);

        // Retrieve and list
        PosVendorPriceListClient.VendorPriceEntryDto fetched = managerClient.getPriceEntry(entry.priceEntryId);
        assertEquals(entry.priceEntryId, fetched.priceEntryId);

        List<PosVendorPriceListClient.VendorPriceEntryDto> list = managerClient.listPriceEntries(
                "sup-square-001", "prod-napa-500", LocalDate.of(2026, 6, 1), true
        );
        assertEquals(1, list.size());
        assertEquals(entry.priceEntryId, list.get(0).priceEntryId);
    }

    @Test
    @DisplayName("Test 2: DGDA MRP ceiling guard and parameter bounds enforcement")
    void testMrpCeilingGuardAndValidationRejections() {
        // Unit cost exceeds MRP
        assertThrows(IllegalArgumentException.class, () -> managerClient.createPriceEntry(
                "sup-square-001", "Square", "prod-napa-500", "Napa", "SQ-01", "BOX", 100,
                new BigDecimal("12.5000"), // Cost exceeds MRP
                new BigDecimal("10.0000"), // MRP
                BigDecimal.ZERO, LocalDate.now(), null, 1, 0, 0, false, ""
        ));

        // Invalid negative cost
        assertThrows(IllegalArgumentException.class, () -> managerClient.createPriceEntry(
                "sup-square-001", "Square", "prod-napa-500", "Napa", "SQ-01", "BOX", 100,
                new BigDecimal("-5.0000"),
                new BigDecimal("10.0000"),
                BigDecimal.ZERO, LocalDate.now(), null, 1, 0, 0, false, ""
        ));

        // Invalid discount > 100%
        assertThrows(IllegalArgumentException.class, () -> managerClient.createPriceEntry(
                "sup-square-001", "Square", "prod-napa-500", "Napa", "SQ-01", "BOX", 100,
                new BigDecimal("8.0000"),
                new BigDecimal("10.0000"),
                new BigDecimal("105.00"), // > 100%
                LocalDate.now(), null, 1, 0, 0, false, ""
        ));

        // Invalid MOQ < 1
        assertThrows(IllegalArgumentException.class, () -> managerClient.createPriceEntry(
                "sup-square-001", "Square", "prod-napa-500", "Napa", "SQ-01", "BOX", 100,
                new BigDecimal("8.0000"),
                new BigDecimal("10.0000"),
                BigDecimal.ZERO, LocalDate.now(), null, 0, 0, 0, false, ""
        ));

        // Effective to before effective from
        assertThrows(IllegalArgumentException.class, () -> managerClient.createPriceEntry(
                "sup-square-001", "Square", "prod-napa-500", "Napa", "SQ-01", "BOX", 100,
                new BigDecimal("8.0000"),
                new BigDecimal("10.0000"),
                BigDecimal.ZERO, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 5, 1), 1, 0, 0, false, ""
        ));
    }

    @Test
    @DisplayName("Test 3: Best-price PO resolution with MOQ thresholding and bonus free goods calculation")
    void testBestPriceResolutionWithMoqAndFreeGoodsBonus() {
        // Supplier A: net cost = 8.50 * 0.95 = 8.0750, MOQ = 50, bonus: 100 -> 5
        managerClient.createPriceEntry(
                "sup-A", "Supplier A", "prod-ace-500", "Ace 500", "A-ACE", "BOX", 100,
                new BigDecimal("8.5000"), new BigDecimal("10.0000"), new BigDecimal("5.00"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 50, 100, 5, false, ""
        );

        // Supplier B: net cost = 8.20 * 0.90 = 7.3800, MOQ = 200, no bonus
        managerClient.createPriceEntry(
                "sup-B", "Supplier B", "prod-ace-500", "Ace 500", "B-ACE", "BOX", 100,
                new BigDecimal("8.2000"), new BigDecimal("10.0000"), new BigDecimal("10.00"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 200, 0, 0, false, ""
        );

        // When order quantity is 100: Supplier B is disqualified because 100 < MOQ (200). Supplier A wins.
        PosVendorPriceListClient.BestPriceOfferDto offer100 = managerClient.resolveBestPrice(
                "prod-ace-500", 100, LocalDate.of(2026, 6, 1)
        );
        assertNotNull(offer100);
        assertEquals("sup-A", offer100.supplierId);
        assertEquals(0, offer100.netUnitCost.compareTo(new BigDecimal("8.0750")));
        assertEquals(0, offer100.totalNetCost.compareTo(new BigDecimal("807.5000")));
        assertEquals(5, offer100.freeGoodsBonusUnits); // (100 / 100) * 5 = 5

        // When order quantity is 250: Supplier B satisfies MOQ (200) and has lower net cost (7.3800 < 8.0750). Supplier B wins.
        PosVendorPriceListClient.BestPriceOfferDto offer250 = managerClient.resolveBestPrice(
                "prod-ace-500", 250, LocalDate.of(2026, 6, 1)
        );
        assertNotNull(offer250);
        assertEquals("sup-B", offer250.supplierId);
        assertEquals(0, offer250.netUnitCost.compareTo(new BigDecimal("7.3800")));
        assertEquals(0, offer250.totalNetCost.compareTo(new BigDecimal("1845.0000")));
        assertEquals(0, offer250.freeGoodsBonusUnits);
    }

    @Test
    @DisplayName("Test 4: RBAC enforcement and cross-tenant isolation guarantees")
    void testTenantIsolationAndCashierRoleRestriction() {
        // Cashier cannot create price entry
        assertThrows(SecurityException.class, () -> cashierClient.createPriceEntry(
                "sup-square-001", "Square", "prod-napa-500", "Napa", "SQ-01", "BOX", 100,
                new BigDecimal("8.0000"), new BigDecimal("10.0000"), BigDecimal.ZERO,
                LocalDate.now(), null, 1, 0, 0, false, ""
        ));

        // Manager creates price entry
        PosVendorPriceListClient.VendorPriceEntryDto entry = managerClient.createPriceEntry(
                "sup-square-001", "Square", "prod-napa-500", "Napa", "SQ-01", "BOX", 100,
                new BigDecimal("8.0000"), new BigDecimal("10.0000"), BigDecimal.ZERO,
                LocalDate.now(), null, 1, 0, 0, false, ""
        );

        // Cashier cannot update or archive
        assertThrows(SecurityException.class, () -> cashierClient.updatePriceEntry(
                entry.priceEntryId, new BigDecimal("7.5000"), null, null, null, null
        ));
        assertThrows(SecurityException.class, () -> cashierClient.archivePriceEntry(entry.priceEntryId));

        // Foreign tenant cannot access or update
        assertThrows(SecurityException.class, () -> foreignClient.getPriceEntry(entry.priceEntryId));
        assertThrows(SecurityException.class, () -> foreignClient.updatePriceEntry(
                entry.priceEntryId, new BigDecimal("7.5000"), null, null, null, null
        ));

        // Soft archive by manager
        PosVendorPriceListClient.VendorPriceEntryDto archived = managerClient.archivePriceEntry(entry.priceEntryId);
        assertFalse(archived.isActive);
    }
}

