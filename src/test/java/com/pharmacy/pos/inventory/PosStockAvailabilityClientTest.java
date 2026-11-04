package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosStockAvailabilityClientTest
 * Tests for P171 multi-tier stock availability matrix, conservation invariants,
 * precheck checkout logic, and packaging breakdowns.
 */
public class PosStockAvailabilityClientTest {

    @Test
    @DisplayName("Test 1: Stock conservation and availability invariant calculation")
    void testStockConservationInvariants() {
        // onHand=100, reserved=15, quarantined=20, pending=50, reorder=20
        PosStockAvailabilityClient.AvailabilityMatrixRecord rec =
                new PosStockAvailabilityClient.AvailabilityMatrixRecord(
                        "bav-001",
                        "tenant-dhanmondi",
                        "branch-main",
                        "prod-napa-500",
                        "Napa 500mg Tablet",
                        "Paracetamol",
                        "TABLET",
                        100,
                        15,
                        20,
                        50,
                        20,
                        "1 Box"
                );

        // physically_available = on_hand - quarantined = 100 - 20 = 80
        assertEquals(80, rec.quantityPhysicallyAvailable);

        // server_confirmed_available = physically_available - reserved = 80 - 15 = 65
        assertEquals(65, rec.quantityServerConfirmedAvailable);

        // Invariant: server_confirmed + reserved + quarantined == on_hand
        assertEquals(rec.quantityOnHand, rec.quantityServerConfirmedAvailable + rec.quantityReserved + rec.quantityQuarantined);

        assertFalse(rec.isOutOfStock);
        assertFalse(rec.isBelowReorderLevel); // 65 > 20
    }

    @Test
    @DisplayName("Test 2: Multi-level packaging unit format breakdown")
    void testPackagingFormatBreakdown() {
        // 1 Box = 10 Strips = 100 Tablets (packToInter=10, interToBase=10)
        String summary100 = PosStockAvailabilityClient.formatPackaging("TABLET", "BOX", "STRIP", 10, 10, 100);
        assertTrue(summary100.contains("1 BOX"));

        String summary25 = PosStockAvailabilityClient.formatPackaging("TABLET", "BOX", "STRIP", 10, 10, 25);
        assertTrue(summary25.contains("2 STRIPs"));
        assertTrue(summary25.contains("5 TABLETs"));

        String summaryZero = PosStockAvailabilityClient.formatPackaging("TABLET", "BOX", "STRIP", 10, 10, 0);
        assertEquals("0 TABLET", summaryZero);
    }

    @Test
    @DisplayName("Test 3: POS Cart precheck with available items and shortage detection")
    void testPrecheckCheckoutLogic() {
        PosStockAvailabilityClient client = new PosStockAvailabilityClient(
                "tenant-dhanmondi", "branch-main", "CASHIER", false
        );

        // Product A: 50 confirmed available
        client.putRecord(new PosStockAvailabilityClient.AvailabilityMatrixRecord(
                "bav-a", "tenant-dhanmondi", "branch-main", "prod-a", "Product A", "Generic A", "PIECE",
                50, 0, 0, 0, 10, "5 Strips"
        ));

        // Product B: 10 confirmed available
        client.putRecord(new PosStockAvailabilityClient.AvailabilityMatrixRecord(
                "bav-b", "tenant-dhanmondi", "branch-main", "prod-b", "Product B", "Generic B", "PIECE",
                10, 0, 0, 0, 5, "1 Strip"
        ));

        // Request: 20 of Prod A (Available), 25 of Prod B (Shortage of 15)
        List<PosStockAvailabilityClient.PrecheckItemRequest> items = List.of(
                new PosStockAvailabilityClient.PrecheckItemRequest("prod-a", 20),
                new PosStockAvailabilityClient.PrecheckItemRequest("prod-b", 25)
        );

        PosStockAvailabilityClient.ClientResponse<PosStockAvailabilityClient.PrecheckBatchResult> res =
                client.precheckCheckout(items);

        assertTrue(res.success);
        assertNotNull(res.data);
        assertFalse(res.data.allItemsAvailable);

        PosStockAvailabilityClient.PrecheckItemResult resA = res.data.items.get(0);
        assertTrue(resA.isAvailable);
        assertEquals(0, resA.shortageUnits);

        PosStockAvailabilityClient.PrecheckItemResult resB = res.data.items.get(1);
        assertFalse(resB.isAvailable);
        assertEquals(15, resB.shortageUnits);
    }

    @Test
    @DisplayName("Test 4: RBAC and suspended tenant enforcement")
    void testRbacAndSuspendedTenant() {
        // Unauthorized role
        PosStockAvailabilityClient unauthClient = new PosStockAvailabilityClient(
                "tenant-dhanmondi", "branch-main", "GUEST_USER", false
        );
        var unauthRes = unauthClient.getAvailability("prod-a");
        assertFalse(unauthRes.success);
        assertEquals("ERR_ROLE_UNAUTHORIZED", unauthRes.errorCode);

        // Suspended tenant
        PosStockAvailabilityClient suspendedClient = new PosStockAvailabilityClient(
                "tenant-dhanmondi", "branch-main", "CASHIER", true
        );
        var suspRes = suspendedClient.getAvailability("prod-a");
        assertFalse(suspRes.success);
        assertEquals("ERR_TENANT_SUSPENDED", suspRes.errorCode);
    }
}

