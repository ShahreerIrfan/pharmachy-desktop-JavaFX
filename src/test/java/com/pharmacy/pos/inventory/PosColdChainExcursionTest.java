package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosColdChainExcursionTest
 * Acceptance Unit Tests for P212: Cold chain excursion workflow (Desktop POS).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 */
class PosColdChainExcursionTest {

    private PosColdChainExcursionClient client;

    @BeforeEach
    void setUp() {
        client = new PosColdChainExcursionClient();
    }

    @Test
    @DisplayName("P212-FX-1: Excursion triggers automated batch quarantine and zero sellable units")
    void testTriggerExcursionAndQuarantine() {
        List<PosColdChainExcursionClient.QuarantinedBatchItem> batches = List.of(
                new PosColdChainExcursionClient.QuarantinedBatchItem("LOT-INS-101", "Insulin Glargine", 40, new BigDecimal("450.00")),
                new PosColdChainExcursionClient.QuarantinedBatchItem("LOT-VAC-202", "Hepatitis B Vaccine", 25, new BigDecimal("800.00"))
        );

        PosColdChainExcursionClient.ExcursionIncident incident = client.triggerExcursion(
                "ten-evercare-01",
                "br-central-01",
                "FRIDGE-BIOL-01",
                "Main Vaccine Refrigerator",
                new BigDecimal("12.50"), // Above 8.00°C safe upper limit
                new BigDecimal("2.00"),
                new BigDecimal("8.00"),
                batches
        );

        assertNotNull(incident);
        assertEquals(PosColdChainExcursionClient.IncidentStatus.DETECTED, incident.getStatus());
        assertEquals(PosColdChainExcursionClient.Severity.WARNING, incident.getSeverity());
        assertEquals(2, incident.getQuarantinedBatchesCount());
        assertEquals(new BigDecimal("38000.00"), incident.getTotalQuarantinedValue()); // 40*450 + 25*800 = 18000 + 20000 = 38000
        assertEquals(PosColdChainExcursionClient.DispositionOutcome.PENDING, incident.getDispositionOutcome());
    }

    @Test
    @DisplayName("P212-FX-2: Pharmacist review with valid clinical justification releases stock to sellable")
    void testReviewAndReleaseStock() {
        List<PosColdChainExcursionClient.QuarantinedBatchItem> batches = List.of(
                new PosColdChainExcursionClient.QuarantinedBatchItem("LOT-INS-101", "Insulin Glargine", 40, new BigDecimal("450.00"))
        );

        PosColdChainExcursionClient.ExcursionIncident incident = client.triggerExcursion(
                "ten-evercare-01",
                "br-central-01",
                "FRIDGE-BIOL-01",
                "Main Vaccine Refrigerator",
                new BigDecimal("9.50"),
                new BigDecimal("2.00"),
                new BigDecimal("8.00"),
                batches
        );

        PosColdChainExcursionClient.ExcursionIncident resolved = client.reviewDisposition(
                incident.getIncidentId(),
                "STAFF_PHARMACIST",
                "pharmacist_ashraf",
                PosColdChainExcursionClient.DispositionOutcome.RELEASE_TO_SELLABLE,
                "Stability analysis confirmed within acceptable deviation (under 2 hours at 9.5C)."
        );

        assertEquals(PosColdChainExcursionClient.IncidentStatus.RESOLVED, resolved.getStatus());
        assertEquals(PosColdChainExcursionClient.DispositionOutcome.RELEASE_TO_SELLABLE, resolved.getDispositionOutcome());
        assertEquals("pharmacist_ashraf", resolved.getReviewedBy());
        assertNotNull(resolved.getReviewedAt());
        assertEquals(PosColdChainExcursionClient.DispositionOutcome.RELEASE_TO_SELLABLE, resolved.getAffectedBatches().get(0).getDisposition());
    }

    @Test
    @DisplayName("P212-FX-3: Pharmacist condemns and disposes stock on severe excursion")
    void testReviewCondemnAndDispose() {
        List<PosColdChainExcursionClient.QuarantinedBatchItem> batches = List.of(
                new PosColdChainExcursionClient.QuarantinedBatchItem("LOT-VAC-909", "Rabies Vaccine", 15, new BigDecimal("1200.00"))
        );

        PosColdChainExcursionClient.ExcursionIncident incident = client.triggerExcursion(
                "ten-evercare-01",
                "br-central-01",
                "FRIDGE-BIOL-01",
                "Main Vaccine Refrigerator",
                new BigDecimal("26.00"), // Severe excursion
                new BigDecimal("2.00"),
                new BigDecimal("8.00"),
                batches
        );

        assertEquals(PosColdChainExcursionClient.Severity.CRITICAL, incident.getSeverity());

        PosColdChainExcursionClient.ExcursionIncident resolved = client.reviewDisposition(
                incident.getIncidentId(),
                "STAFF_PHARMACIST",
                "pharmacist_ashraf",
                PosColdChainExcursionClient.DispositionOutcome.CONDEMN_DISPOSE,
                "Severe excursion above 25C for over 8 hours; cold-chain broken, batches condemned."
        );

        assertEquals(PosColdChainExcursionClient.DispositionOutcome.CONDEMN_DISPOSE, resolved.getDispositionOutcome());
        assertEquals(PosColdChainExcursionClient.DispositionOutcome.CONDEMN_DISPOSE, resolved.getAffectedBatches().get(0).getDisposition());
    }

    @Test
    @DisplayName("P212-FX-4: Short clinical justification (< 15 characters) throws IllegalArgumentException")
    void testShortClinicalJustificationRejected() {
        List<PosColdChainExcursionClient.QuarantinedBatchItem> batches = List.of(
                new PosColdChainExcursionClient.QuarantinedBatchItem("LOT-INS-101", "Insulin Glargine", 10, new BigDecimal("450.00"))
        );

        PosColdChainExcursionClient.ExcursionIncident incident = client.triggerExcursion(
                "ten-evercare-01",
                "br-central-01",
                "FRIDGE-BIOL-01",
                "Main Vaccine Refrigerator",
                new BigDecimal("10.00"),
                new BigDecimal("2.00"),
                new BigDecimal("8.00"),
                batches
        );

        assertThrows(IllegalArgumentException.class, () -> client.reviewDisposition(
                incident.getIncidentId(),
                "STAFF_PHARMACIST",
                "pharmacist_ashraf",
                PosColdChainExcursionClient.DispositionOutcome.RELEASE_TO_SELLABLE,
                "Looks fine" // Under 15 chars
        ));
    }

    @Test
    @DisplayName("P212-FX-5: Cashier role is forbidden from reviewing cold chain excursions")
    void testCashierReviewDenied() {
        List<PosColdChainExcursionClient.QuarantinedBatchItem> batches = List.of(
                new PosColdChainExcursionClient.QuarantinedBatchItem("LOT-INS-101", "Insulin Glargine", 10, new BigDecimal("450.00"))
        );

        PosColdChainExcursionClient.ExcursionIncident incident = client.triggerExcursion(
                "ten-evercare-01",
                "br-central-01",
                "FRIDGE-BIOL-01",
                "Main Vaccine Refrigerator",
                new BigDecimal("10.00"),
                new BigDecimal("2.00"),
                new BigDecimal("8.00"),
                batches
        );

        assertThrows(SecurityException.class, () -> client.reviewDisposition(
                incident.getIncidentId(),
                "CASHIER",
                "cashier_rahim",
                PosColdChainExcursionClient.DispositionOutcome.RELEASE_TO_SELLABLE,
                "Unauthorized cashier attempt to release stock."
        ));
    }
}

