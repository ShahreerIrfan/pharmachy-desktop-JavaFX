package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosPackagingHierarchyClientTest
 * Architecture Decision: ADR-0162 (P162).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Unit test suite for packaging hierarchy tree resolution and conversions in JavaFX POS.
 */
public class PosPackagingHierarchyClientTest {

    private PosPackagingHierarchyClient client;
    private List<PosPackagingHierarchyClient.PackagingNode> standardNodes;

    @BeforeEach
    public void setUp() {
        client = new PosPackagingHierarchyClient();
        standardNodes = new ArrayList<>();

        // CARTON -> BOX (x10)
        standardNodes.add(new PosPackagingHierarchyClient.PackagingNode(
                "pkn-01", "prod-napa-500", "CARTON", "BOX", 10, 3, false,
                LocalDate.of(2026, 1, 1), null, true
        ));
        // BOX -> STRIP (x10)
        standardNodes.add(new PosPackagingHierarchyClient.PackagingNode(
                "pkn-02", "prod-napa-500", "BOX", "STRIP", 10, 2, false,
                LocalDate.of(2026, 1, 1), null, true
        ));
        // STRIP -> TABLET (x10)
        standardNodes.add(new PosPackagingHierarchyClient.PackagingNode(
                "pkn-03", "prod-napa-500", "STRIP", "TABLET", 10, 1, true,
                LocalDate.of(2026, 1, 1), null, true
        ));
    }

    @Test
    @DisplayName("1. Valid scenario: Resolves 3-tier packaging chain with cumulative multipliers")
    public void testResolvePackagingTree() {
        PosPackagingHierarchyClient.HierarchyTree tree =
                client.resolvePackagingTree("prod-napa-500", standardNodes, LocalDate.of(2026, 6, 1));

        assertEquals("TABLET", tree.baseUnit);
        assertEquals(3, tree.chain.size());
        assertEquals(1000, tree.cumulativeFactors.get("CARTON")); // 10 * 10 * 10
        assertEquals(100, tree.cumulativeFactors.get("BOX"));      // 10 * 10
        assertEquals(10, tree.cumulativeFactors.get("STRIP"));     // 10
        assertEquals(1, tree.cumulativeFactors.get("TABLET"));      // 1
    }

    @Test
    @DisplayName("2. Valid scenario: Converts 2 Cartons to Strips and 5 Boxes to Tablets")
    public void testConvertPackagingTiers() {
        // 2 Cartons -> Strips: (2 * 1000) / 10 = 200 Strips
        PosPackagingHierarchyClient.HierarchyConversionResult res1 =
                client.convertPackagingTier("prod-napa-500", "CARTON", "STRIP", new BigDecimal("2"), standardNodes, LocalDate.of(2026, 6, 1));
        assertTrue(res1.success);
        assertEquals(0, new BigDecimal("200.0000").compareTo(res1.convertedQuantity));

        // 5 Boxes -> Tablets: (5 * 100) / 1 = 500 Tablets
        PosPackagingHierarchyClient.HierarchyConversionResult res2 =
                client.convertPackagingTier("prod-napa-500", "BOX", "TABLET", new BigDecimal("5"), standardNodes, LocalDate.of(2026, 6, 1));
        assertTrue(res2.success);
        assertEquals(0, new BigDecimal("500.0000").compareTo(res2.convertedQuantity));
    }

    @Test
    @DisplayName("3. Effective date switching: Different historical vs new conversion factors")
    public void testEffectiveDateSwitching() {
        List<PosPackagingHierarchyClient.PackagingNode> datedNodes = new ArrayList<>();
        // Era 1 (before 2026-06-01): 1 BOX = 10 STRIPS
        datedNodes.add(new PosPackagingHierarchyClient.PackagingNode(
                "pkn-old", "prod-napa-500", "BOX", "STRIP", 10, 2, false,
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 5, 31), true
        ));
        // Era 2 (from 2026-06-01 onwards): 1 BOX = 20 STRIPS
        datedNodes.add(new PosPackagingHierarchyClient.PackagingNode(
                "pkn-new", "prod-napa-500", "BOX", "STRIP", 20, 2, false,
                LocalDate.of(2026, 6, 1), null, true
        ));
        // STRIP -> TABLET (constant x10)
        datedNodes.add(new PosPackagingHierarchyClient.PackagingNode(
                "pkn-strip", "prod-napa-500", "STRIP", "TABLET", 10, 1, true,
                LocalDate.of(2025, 1, 1), null, true
        ));

        // Date in Era 1: 2026-05-15 -> 1 Box should yield 10 strips and 100 tablets
        PosPackagingHierarchyClient.HierarchyConversionResult resOld =
                client.convertPackagingTier("prod-napa-500", "BOX", "TABLET", BigDecimal.ONE, datedNodes, LocalDate.of(2026, 5, 15));
        assertTrue(resOld.success);
        assertEquals(0, new BigDecimal("100.0000").compareTo(resOld.convertedQuantity));

        // Date in Era 2: 2026-07-01 -> 1 Box should yield 20 strips and 200 tablets
        PosPackagingHierarchyClient.HierarchyConversionResult resNew =
                client.convertPackagingTier("prod-napa-500", "BOX", "TABLET", BigDecimal.ONE, datedNodes, LocalDate.of(2026, 7, 1));
        assertTrue(resNew.success);
        assertEquals(0, new BigDecimal("200.0000").compareTo(resNew.convertedQuantity));
    }

    @Test
    @DisplayName("4. Cycle detection: Self cycle and circular loop detected")
    public void testCycleDetection() {
        // Self cycle: BOX -> BOX
        assertTrue(client.hasCycle("BOX", "BOX", standardNodes));

        // Circular loop: TABLET -> CARTON when CARTON -> BOX -> STRIP -> TABLET exists
        assertTrue(client.hasCycle("TABLET", "CARTON", standardNodes));

        // Valid new parent: PALLET -> CARTON (no cycle)
        assertFalse(client.hasCycle("PALLET", "CARTON", standardNodes));
    }

    @Test
    @DisplayName("5. Date overlap detection: Detects overlapping active intervals for same parent & child")
    public void testDateOverlapDetection() {
        // Existing node is 2026-01-01 to null (open-ended)
        boolean overlap = client.hasDateOverlap(
                "BOX", "STRIP", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 12, 31),
                standardNodes, null
        );
        assertTrue(overlap);
    }

    @Test
    @DisplayName("6. Unlinked tier rejection: Attempting to convert unlinked unit fails")
    public void testUnlinkedTierFails() {
        PosPackagingHierarchyClient.HierarchyConversionResult res =
                client.convertPackagingTier("prod-napa-500", "BOTTLE", "TABLET", BigDecimal.ONE, standardNodes, LocalDate.of(2026, 6, 1));
        assertFalse(res.success);
        assertEquals("ERR_UNLINKED_PACKAGING_TIER", res.errorCode);
    }

    @Test
    @DisplayName("7. Role authorization: Cashier blocked from configuring packaging hierarchy")
    public void testRoleAuthorization() {
        assertTrue(client.canConfigurePackaging("OWNER"));
        assertTrue(client.canConfigurePackaging("MANAGER"));
        assertTrue(client.canConfigurePackaging("PHARMACIST"));
        assertFalse(client.canConfigurePackaging("CASHIER"));
        assertFalse(client.canConfigurePackaging(null));
    }
}

