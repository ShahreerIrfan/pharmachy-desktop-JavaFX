package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P241 Prescription Record Model Desktop Tests.
 */
public class PrescriptionRecordClientTest {

    @Test
    @DisplayName("P241-Desktop-1: Prescription status bilingual localization and state representation")
    void testPrescriptionStatusLocalization() {
        assertEquals("Pending Verification", PrescriptionRecordClient.PrescriptionStatus.PENDING.getLocalized("en"));
        assertEquals("অনুমোদনের অপেক্ষায়", PrescriptionRecordClient.PrescriptionStatus.PENDING.getLocalized("bn"));

        assertEquals("Verified by Pharmacist", PrescriptionRecordClient.PrescriptionStatus.VERIFIED.getLocalized("en"));
        assertEquals("ফার্মাসিস্ট দ্বারা যাচাইকৃত", PrescriptionRecordClient.PrescriptionStatus.VERIFIED.getLocalized("bn"));

        assertEquals("Partially Dispensed", PrescriptionRecordClient.PrescriptionStatus.PARTIALLY_DISPENSED.getLocalized("en"));
        assertEquals("আংশিক প্রদানকৃত", PrescriptionRecordClient.PrescriptionStatus.PARTIALLY_DISPENSED.getLocalized("bn"));

        assertEquals("Expired", PrescriptionRecordClient.PrescriptionStatus.EXPIRED.getLocalized("en"));
        assertEquals("মেয়াদোত্তীর্ণ", PrescriptionRecordClient.PrescriptionStatus.EXPIRED.getLocalized("bn"));
    }

    @Test
    @DisplayName("P241-Desktop-2: Validation of expiry checking and allowable dispensing logic")
    void testPrescriptionDispenseEligibility() {
        PrescriptionRecordClient.PrescriptionItemDto item = new PrescriptionRecordClient.PrescriptionItemDto(
                1, "prod-1", "Napa Extra", "1+0+1", 7, 14, 4, false
        );
        assertEquals(10, item.getRemainingQuantity());
        assertFalse(item.isDispensed());

        // 1. Verified and not expired -> eligible
        PrescriptionRecordClient.PrescriptionDto validRx = new PrescriptionRecordClient.PrescriptionDto(
                "rx-001", "RX-2026-001", "Dr. Rahman", "BMDC-12345", "Dhaka Medical",
                "PT-XYZ999", 35, "MALE", "Fever",
                PrescriptionRecordClient.PrescriptionStatus.VERIFIED, "PCB-9876",
                LocalDate.now(), LocalDate.now().plusDays(10),
                List.of(item)
        );
        assertFalse(validRx.isExpired());
        assertTrue(validRx.canDispense());
        assertEquals(14, validRx.getTotalPrescribedUnits());
        assertEquals(4, validRx.getTotalDispensedUnits());

        // 2. Expired Rx -> ineligible to dispense
        PrescriptionRecordClient.PrescriptionDto expiredRx = new PrescriptionRecordClient.PrescriptionDto(
                "rx-002", "RX-2026-002", "Dr. Rahman", "BMDC-12345", "Dhaka Medical",
                "PT-XYZ999", 35, "MALE", "Fever",
                PrescriptionRecordClient.PrescriptionStatus.VERIFIED, "PCB-9876",
                LocalDate.now().minusDays(15), LocalDate.now().minusDays(1),
                List.of(item)
        );
        assertTrue(expiredRx.isExpired());
        assertFalse(expiredRx.canDispense());

        // 3. UI Model state management
        PrescriptionRecordClient.PrescriptionUiModel uiModel = new PrescriptionRecordClient.PrescriptionUiModel();
        uiModel.setPrescription(validRx);
        assertEquals(PrescriptionRecordClient.UiState.READY, uiModel.getUiState());

        uiModel.setErrorMessage("Network connection timed out");
        assertEquals(PrescriptionRecordClient.UiState.ERROR, uiModel.getUiState());
        assertEquals("Network connection timed out", uiModel.getErrorMessage());
    }
}
