package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PosSupplierDashboardClient Tests (ADR-0199 / P199)")
public class PosSupplierDashboardTest {

    private Map<String, PosSupplierDashboardClient.PosSupplierDashboardSnapshotDto> snapshotStore;
    private PosSupplierDashboardClient managerClient;
    private PosSupplierDashboardClient cashierClient;

    @BeforeEach
    public void setUp() {
        snapshotStore = new HashMap<>();

        managerClient = new PosSupplierDashboardClient(
                "tenant-dhaka-001",
                "branch-dhanmondi-01",
                "BRANCH_MANAGER",
                "usr-mgr-1",
                false,
                snapshotStore
        );

        cashierClient = new PosSupplierDashboardClient(
                "tenant-dhaka-001",
                "branch-dhanmondi-01",
                "CASHIER",
                "usr-csh-1",
                false,
                snapshotStore
        );
    }

    private List<PosSupplierDashboardClient.PosOpenOrderItem> createSampleOrders() {
        return List.of(
                new PosSupplierDashboardClient.PosOpenOrderItem(
                        "po-101", "PO-202611-101", "Square Pharmaceuticals Ltd.", "ORDERED", new BigDecimal("15000.0000")
                ),
                new PosSupplierDashboardClient.PosOpenOrderItem(
                        "po-102", "PO-202611-102", "Beximco Pharmaceuticals Ltd.", "APPROVED", new BigDecimal("8500.0000")
                )
        );
    }

    private List<PosSupplierDashboardClient.PosOverdueDeliveryItem> createSampleOverdue() {
        return List.of(
                new PosSupplierDashboardClient.PosOverdueDeliveryItem(
                        "po-101", "PO-202611-101", "Square Pharmaceuticals Ltd.", "2026-11-05", 3, new BigDecimal("15000.0000")
                )
        );
    }

    private List<PosSupplierDashboardClient.PosSupplierDueItem> createSampleDues() {
        return List.of(
                new PosSupplierDashboardClient.PosSupplierDueItem(
                        "sup-sq", "Square Pharmaceuticals Ltd.", 30, new BigDecimal("45000.0000"), true
                ),
                new PosSupplierDashboardClient.PosSupplierDueItem(
                        "sup-bx", "Beximco Pharmaceuticals Ltd.", 45, new BigDecimal("22000.0000"), false
                )
        );
    }

    private List<PosSupplierDashboardClient.PosLowStockSourcingItem> createSampleLowStock() {
        return List.of(
                new PosSupplierDashboardClient.PosLowStockSourcingItem(
                        "prod-napa", "Napa Extra 500mg", 15, 100, "Square Pharmaceuticals Ltd.",
                        new BigDecimal("2.5000"), new BigDecimal("250.0000")
                ),
                new PosSupplierDashboardClient.PosLowStockSourcingItem(
                        "prod-seclo", "Seclo 20mg", 8, 80, "Square Pharmaceuticals Ltd.",
                        new BigDecimal("5.0000"), new BigDecimal("400.0000")
                )
        );
    }

    @Test
    @DisplayName("01. Compiles operations dashboard summary with open orders and overdue metrics")
    public void test01_compilesOperationsSummary() {
        var summary = managerClient.compileSummary(
                createSampleOrders(),
                createSampleOverdue(),
                createSampleDues(),
                createSampleLowStock(),
                5
        );

        assertNotNull(summary);
        assertEquals(2, summary.openOrdersCount);
        assertEquals(new BigDecimal("23500.0000"), summary.openOrdersValue);

        assertEquals(1, summary.overdueDeliveriesCount);
        assertEquals(new BigDecimal("15000.0000"), summary.overdueDeliveriesValue);

        assertEquals(new BigDecimal("67000.0000"), summary.totalDueBalance);
        assertEquals(2, summary.supplierDues.size());

        assertEquals(2, summary.lowStockOpportunitiesCount);
        assertEquals(new BigDecimal("650.0000"), summary.estimatedReorderCost);
        assertEquals(5, summary.activeSuppliersCount);
    }

    @Test
    @DisplayName("02. Generates auditable dashboard snapshot with valid SHA-256 hash")
    public void test02_generatesAuditableSnapshot() {
        var summary = managerClient.compileSummary(
                createSampleOrders(),
                createSampleOverdue(),
                createSampleDues(),
                createSampleLowStock(),
                5
        );

        var snap = managerClient.createSnapshot(summary, "Morning supplier operations audit");
        assertNotNull(snap);
        assertTrue(snap.snapshotId.startsWith("sdash-"));
        assertEquals(64, snap.immutableVerificationHash.length());
        assertEquals("Morning supplier operations audit", snap.notes);
        assertEquals("usr-mgr-1", snap.generatedBy);
    }

    @Test
    @DisplayName("03. Retrieves and lists historical snapshots")
    public void test03_retrievesAndListsSnapshots() {
        var summary = managerClient.compileSummary(
                createSampleOrders(),
                createSampleOverdue(),
                createSampleDues(),
                createSampleLowStock(),
                5
        );

        var snap1 = managerClient.createSnapshot(summary, "Audit 1");
        var snap2 = managerClient.createSnapshot(summary, "Audit 2");

        var retrieved = managerClient.getSnapshot(snap1.snapshotId);
        assertEquals(snap1.snapshotId, retrieved.snapshotId);
        assertEquals(snap1.immutableVerificationHash, retrieved.immutableVerificationHash);

        var list = managerClient.listSnapshots();
        assertEquals(2, list.size());
    }

    @Test
    @DisplayName("04. Cashier role access denied with ERR_STAFF_ROLE_DENIED")
    public void test04_cashierRoleDenied() {
        SecurityException ex = assertThrows(SecurityException.class, () -> {
            cashierClient.compileSummary(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    0
            );
        });

        assertTrue(ex.getMessage().contains("ERR_STAFF_ROLE_DENIED"));
    }

    @Test
    @DisplayName("05. Handles empty operations data gracefully")
    public void test05_handlesEmptyDataGracefully() {
        var summary = managerClient.compileSummary(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                0
        );

        assertEquals(0, summary.openOrdersCount);
        assertEquals(BigDecimal.ZERO, summary.openOrdersValue);
        assertEquals(0, summary.overdueDeliveriesCount);
        assertEquals(BigDecimal.ZERO, summary.totalDueBalance);
        assertEquals(0, summary.lowStockOpportunitiesCount);
        assertEquals(BigDecimal.ZERO, summary.estimatedReorderCost);
    }

    @Test
    @DisplayName("06. Verifies deterministic SHA-256 hash generation")
    public void test06_verifiesDeterministicHash() {
        String hash1 = PosSupplierDashboardClient.computeVerificationHash(
                "sdash-1", "tenant-1", 3, new BigDecimal("1200.0000"), 1, new BigDecimal("5000.0000"), 2
        );
        String hash2 = PosSupplierDashboardClient.computeVerificationHash(
                "sdash-1", "tenant-1", 3, new BigDecimal("1200.0000"), 1, new BigDecimal("5000.0000"), 2
        );

        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length());
    }
}

