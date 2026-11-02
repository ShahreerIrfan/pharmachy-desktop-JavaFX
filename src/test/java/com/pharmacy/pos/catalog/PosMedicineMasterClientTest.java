package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosMedicineMasterClientTest
 * Tests P121 Medicine Global Master desktop client logic.
 */
class PosMedicineMasterClientTest {

    private PosMedicineMasterClient client;
    private List<PosMedicineMasterClient.MedicineRecord> sampleMedicines;

    @BeforeEach
    void setUp() {
        client = new PosMedicineMasterClient();
        sampleMedicines = new ArrayList<>();

        sampleMedicines.add(new PosMedicineMasterClient.MedicineRecord(
                "prod-1", "Napa Extra", "Paracetamol + Caffeine", "Tablet",
                "500mg+65mg", "Beximco Pharmaceuticals Ltd", "Oral", "8901234567890",
                3.50, "DAR-101", "APPROVED", 1, "hash-001"
        ));

        sampleMedicines.add(new PosMedicineMasterClient.MedicineRecord(
                "prod-2", "Seclo", "Omeprazole", "Capsule",
                "20mg", "Square Pharmaceuticals PLC", "Oral", "8901234567891",
                7.00, "DAR-102", "APPROVED", 1, "hash-002"
        ));

        sampleMedicines.add(new PosMedicineMasterClient.MedicineRecord(
                "prod-3", "Flamyd", "Metronidazole", "Suspension",
                "200mg/5ml", "Sanofi Bangladesh Ltd", "Oral", "8901234567892",
                45.00, "DAR-103", "UNDER_REVIEW", 1, "hash-003"
        ));
    }

    @Test
    @DisplayName("Search by exact barcode match succeeds for barcode scanner")
    void testBarcodeMatch() {
        var res = client.search(sampleMedicines, "8901234567890", null, null);
        assertEquals(PosMedicineMasterClient.UiStatus.SUCCESS, res.uiStatus);
        assertEquals(1, res.medicines.size());
        assertEquals("Napa Extra", res.medicines.get(0).brandName);
    }

    @Test
    @DisplayName("Search by generic name finds matching approved medicines")
    void testSearchByGeneric() {
        var res = client.search(sampleMedicines, "Omeprazole", null, null);
        assertEquals(PosMedicineMasterClient.UiStatus.SUCCESS, res.uiStatus);
        assertEquals(1, res.medicines.size());
        assertEquals("Seclo", res.medicines.get(0).brandName);
    }

    @Test
    @DisplayName("Only approved medicines appear in tenant search results")
    void testOnlyApprovedMedicinesReturned() {
        var res = client.search(sampleMedicines, "", null, null);
        assertEquals(PosMedicineMasterClient.UiStatus.SUCCESS, res.uiStatus);
        assertEquals(2, res.medicines.size());
        assertTrue(res.medicines.stream().allMatch(m -> "APPROVED".equals(m.curationStatus)));
    }

    @Test
    @DisplayName("Filter by dosage form Capsule")
    void testFilterByDosageForm() {
        var res = client.search(sampleMedicines, "", null, "Capsule");
        assertEquals(PosMedicineMasterClient.UiStatus.SUCCESS, res.uiStatus);
        assertEquals(1, res.medicines.size());
        assertEquals("Capsule", res.medicines.get(0).dosageForm);
    }

    @Test
    @DisplayName("Tenant cashier cannot mutate global master catalog")
    void testCashierCannotMutateGlobalCatalog() {
        var res = client.attemptMutateGlobalMaster("CASHIER", sampleMedicines.get(0));
        assertEquals(PosMedicineMasterClient.UiStatus.PERMISSION_DENIED, res.uiStatus);
        assertEquals("PLATFORM_CATALOG_OWNERSHIP_PROTECTED", res.errorCode);
    }

    @Test
    @DisplayName("Superadmin operator can mutate global master catalog")
    void testSuperadminCanMutateGlobalCatalog() {
        var res = client.attemptMutateGlobalMaster("SUPERADMIN", sampleMedicines.get(0));
        assertEquals(PosMedicineMasterClient.UiStatus.SUCCESS, res.uiStatus);
    }
}
