package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosDeliveryLogisticsTest
 * Architecture Decision: ADR-0196 (P196).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 */
public class PosDeliveryLogisticsTest {

    private Map<String, PosDeliveryLogisticsClient.ProcurementShipmentDto> shipmentStore;
    private PosDeliveryLogisticsClient managerClient;
    private PosDeliveryLogisticsClient cashierClient;

    @BeforeEach
    void setUp() {
        shipmentStore = new HashMap<>();

        managerClient = new PosDeliveryLogisticsClient(
                "tenant-dhaka-001",
                "branch-dhanmondi-01",
                "BRANCH_MANAGER",
                "usr-manager-1",
                false,
                shipmentStore
        );

        cashierClient = new PosDeliveryLogisticsClient(
                "tenant-dhaka-001",
                "branch-dhanmondi-01",
                "CASHIER",
                "usr-cashier-1",
                false,
                shipmentStore
        );
    }

    @Test
    void test01_createShipmentWithCarrierAndEta() {
        Instant eta = Instant.now().plusSeconds(86400);
        var shp = managerClient.createShipment(
                "po-101", "sup-sq", "Square Pharma", "SUPPLIER_FLEET",
                "Square Reefer Fleet", "SQ-CONS-001", "Dhaka Metro-U-11-2233",
                "Kamal Hossain", "+8801711223344", eta, 30, true,
                new BigDecimal("4.20"), "Vaccines and biologics"
        );

        assertNotNull(shp);
        assertEquals("DISPATCHED", shp.stage);
        assertEquals(30, shp.totalPackagesDispatched);
        assertTrue(shp.requiresColdChain);
        assertFalse(shp.coldChainBreached);
        assertEquals(1, shp.events.size());
        assertEquals("DISPATCHED", shp.events.get(0).stage);
    }

    @Test
    void test02_updateShipmentStagesAndCheckpoints() {
        Instant eta = Instant.now().plusSeconds(86400);
        var shp = managerClient.createShipment(
                "po-101", "sup-sq", "Square Pharma", "COURIER",
                "SA Paribahan", "SA-9988", "", "Driver X", "", eta, 10, false, null, ""
        );

        managerClient.updateShipmentStage(shp.shipmentId, "IN_TRANSIT", "Central Hub", null, "En route");
        managerClient.updateShipmentStage(shp.shipmentId, "OUT_FOR_DELIVERY", "Dhanmondi Area", null, "Van dispatched");
        var delivered = managerClient.updateShipmentStage(shp.shipmentId, "DELIVERED", "Branch Gate", null, "Arrived at branch");

        assertEquals("DELIVERED", delivered.stage);
        assertNotNull(delivered.actualDeliveryAt);
        assertEquals(4, delivered.events.size());
    }

    @Test
    void test03_inTransitColdChainBreachDetected() {
        Instant eta = Instant.now().plusSeconds(86400);
        var shp = managerClient.createShipment(
                "po-101", "sup-sq", "Square Pharma", "SUPPLIER_FLEET",
                "Reefer Van", "SQ-COLD-01", "", "", "", eta, 15, true,
                new BigDecimal("5.00"), ""
        );

        // Spike to 14.50°C
        var updated = managerClient.updateShipmentStage(
                shp.shipmentId, "IN_TRANSIT", "Highway Checkpoint",
                new BigDecimal("14.50"), "Reefer battery drained"
        );

        assertTrue(updated.coldChainBreached);
        assertEquals(new BigDecimal("14.50"), updated.transitTempCelsius);
    }

    @Test
    void test04_confirmBranchReceivingNoDiscrepancy() {
        Instant eta = Instant.now().plusSeconds(86400);
        var shp = managerClient.createShipment(
                "po-101", "sup-sq", "Square Pharma", "SUPPLIER_FLEET",
                "Van", "C-1", "", "", "", eta, 20, false, null, ""
        );

        var confirmed = managerClient.confirmBranchReceiving(shp.shipmentId, 20, 0, 0, null, "Perfect seal");

        assertEquals("RECEIVING_CONFIRMED", confirmed.stage);
        assertEquals(20, confirmed.totalPackagesReceived);
        assertEquals(0, confirmed.damagedPackages);
        assertEquals(0, confirmed.missingPackages);
        assertEquals("NONE", confirmed.discrepancyType);
    }

    @Test
    void test05_confirmBranchReceivingWithDamageAndShortage() {
        Instant eta = Instant.now().plusSeconds(86400);
        var shp = managerClient.createShipment(
                "po-101", "sup-sq", "Square Pharma", "SUPPLIER_FLEET",
                "Van", "C-1", "", "", "", eta, 50, false, null, ""
        );

        var confirmed = managerClient.confirmBranchReceiving(
                shp.shipmentId, 45, 3, 5, null, "3 cartons crushed, 5 missing"
        );

        assertEquals("RECEIVING_CONFIRMED", confirmed.stage);
        assertEquals(45, confirmed.totalPackagesReceived);
        assertEquals(3, confirmed.damagedPackages);
        assertEquals(5, confirmed.missingPackages);
        assertTrue(List.of("DAMAGED", "SHORTAGE").contains(confirmed.discrepancyType));
    }

    @Test
    void test06_cannotUpdateStageOnConfirmedShipment() {
        Instant eta = Instant.now().plusSeconds(86400);
        var shp = managerClient.createShipment(
                "po-101", "sup-sq", "Square Pharma", "SUPPLIER_FLEET",
                "Van", "C-1", "", "", "", eta, 10, false, null, ""
        );
        managerClient.confirmBranchReceiving(shp.shipmentId, 10, 0, 0, null, "");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                managerClient.updateShipmentStage(shp.shipmentId, "IN_TRANSIT", "Hub", null, "")
        );
        assertTrue(ex.getMessage().contains("ERR_SHIPMENT_ALREADY_CONFIRMED"));
    }

    @Test
    void test07_cashierRoleDenied() {
        Instant eta = Instant.now().plusSeconds(86400);
        SecurityException ex = assertThrows(SecurityException.class, () ->
                cashierClient.createShipment("po-1", "sup-1", "Sup", "COURIER", "Carrier", "", "", "", "", eta, 1, false, null, "")
        );
        assertTrue(ex.getMessage().contains("ERR_STAFF_ROLE_DENIED"));
    }
}
