package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosSupplyChainAlertTest
 * Acceptance Unit Tests for P214: Supply chain stock alerts (Desktop POS).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 */
class PosSupplyChainAlertTest {

    private PosSupplyChainAlertClient client;

    @BeforeEach
    void setUp() {
        client = new PosSupplyChainAlertClient();
    }

    @Test
    @DisplayName("P214-FX-1: Stockout and Reorder alerts generate with correct severity")
    void testStockoutAndReorderAlerts() {
        PosSupplyChainAlertClient.StockAlert stockout = client.evaluateStockLevel(
                "ten-01", "br-01", "prod-01", "Napa Extra", 0, 20, 100
        );
        assertNotNull(stockout);
        assertEquals(PosSupplyChainAlertClient.AlertType.STOCKOUT, stockout.getAlertType());
        assertEquals(PosSupplyChainAlertClient.Severity.CRITICAL, stockout.getSeverity());
        assertEquals(PosSupplyChainAlertClient.AlertStatus.OPEN, stockout.getStatus());
        assertEquals(1, stockout.getOccurredCount());

        PosSupplyChainAlertClient.StockAlert reorder = client.evaluateStockLevel(
                "ten-01", "br-01", "prod-02", "Amoxicillin 500mg", 15, 30, 200
        );
        assertNotNull(reorder);
        assertEquals(PosSupplyChainAlertClient.AlertType.REORDER_REQUIRED, reorder.getAlertType());
        assertEquals(PosSupplyChainAlertClient.Severity.WARNING, reorder.getSeverity());
    }

    @Test
    @DisplayName("P214-FX-2: Deduplication increments occurrence count without creating duplicate alert")
    void testAlertDeduplication() {
        PosSupplyChainAlertClient.StockAlert a1 = client.evaluateStockLevel(
                "ten-01", "br-01", "prod-01", "Napa Extra", 0, 20, 100
        );
        assertEquals(1, a1.getOccurredCount());

        PosSupplyChainAlertClient.StockAlert a2 = client.evaluateStockLevel(
                "ten-01", "br-01", "prod-01", "Napa Extra", 0, 20, 100
        );
        assertEquals(a1.getAlertId(), a2.getAlertId());
        assertEquals(2, a2.getOccurredCount());

        List<PosSupplyChainAlertClient.StockAlert> active = client.listActiveAlerts("ten-01", "br-01");
        assertEquals(1, active.size());
    }

    @Test
    @DisplayName("P214-FX-3: Cold chain temperature risk alert generated upon threshold breach")
    void testTemperatureRiskAlert() {
        PosSupplyChainAlertClient.StockAlert tempAlert = client.evaluateTemperatureRisk(
                "ten-01", "br-01", "loc-fridge", "Vaccine Fridge 1",
                new BigDecimal("13.50"), new BigDecimal("2.00"), new BigDecimal("8.00")
        );
        assertNotNull(tempAlert);
        assertEquals(PosSupplyChainAlertClient.AlertType.TEMPERATURE_RISK, tempAlert.getAlertType());
        assertEquals(PosSupplyChainAlertClient.Severity.CRITICAL, tempAlert.getSeverity());
    }

    @Test
    @DisplayName("P214-FX-4: Alert lifecycle transitions (Open -> Acknowledged -> Resolved)")
    void testAlertLifecycle() {
        PosSupplyChainAlertClient.StockAlert alert = client.evaluateStockLevel(
                "ten-01", "br-01", "prod-01", "Napa Extra", 5, 20, 100
        );

        PosSupplyChainAlertClient.StockAlert acked = client.acknowledgeAlert(
                "STAFF_PHARMACIST", alert.getAlertId(), "pharmacist_ashraf", "PO dispatched"
        );
        assertEquals(PosSupplyChainAlertClient.AlertStatus.ACKNOWLEDGED, acked.getStatus());
        assertEquals("pharmacist_ashraf", acked.getAcknowledgedBy());

        PosSupplyChainAlertClient.StockAlert resolved = client.resolveAlert(
                "STAFF_PHARMACIST", alert.getAlertId(), "pharmacist_ashraf", "Stock received"
        );
        assertEquals(PosSupplyChainAlertClient.AlertStatus.RESOLVED, resolved.getStatus());
    }

    @Test
    @DisplayName("P214-FX-5: Cashier role is rejected from managing alerts")
    void testCashierDenied() {
        PosSupplyChainAlertClient.StockAlert alert = client.evaluateStockLevel(
                "ten-01", "br-01", "prod-01", "Napa Extra", 0, 20, 100
        );

        assertThrows(SecurityException.class, () ->
                client.acknowledgeAlert("CASHIER", alert.getAlertId(), "cashier_rahim", "")
        );
    }
}
