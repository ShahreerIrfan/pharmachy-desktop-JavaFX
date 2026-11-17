package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for P246 AntibioticRegisterClient (JavaFX Desktop).
 */
public class AntibioticRegisterClientTest {

    @Test
    public void testValidAntibioticPreflight() {
        boolean valid = AntibioticRegisterClient.validateDispensePreflight(
                true,
                14,
                "PCB-A99214",
                "BMDC-A33120",
                "rx-test-001"
        );
        assertTrue(valid);
    }

    @Test
    public void testPreflightRejectsMissingPharmacistPcb() {
        boolean valid = AntibioticRegisterClient.validateDispensePreflight(
                true,
                14,
                "", // Missing PCB
                "BMDC-A33120",
                "rx-test-001"
        );
        assertFalse(valid);
    }

    @Test
    public void testNetUnitsCalculationAndStatusBilingual() {
        AntibioticRegisterClient.AntibioticRegisterEntryDto dto = new AntibioticRegisterClient.AntibioticRegisterEntryDto(
                "abx-001",
                "ORD-202611-001",
                "RX-202611-001",
                "Cef-3 200mg",
                "Cefixime",
                "ANTIMICROBIAL_ACCESS",
                14,
                4, // 4 units returned
                "BMDC-55112",
                "PCB-9921",
                AntibioticRegisterClient.RegisterStatus.PARTIALLY_RETURNED,
                "abcdef123456"
        );

        assertEquals(10, dto.getNetUnits());
        assertEquals(AntibioticRegisterClient.RegisterStatus.PARTIALLY_RETURNED, dto.getStatus());
        assertEquals("আংশিক ফেরত", dto.getStatus().getLocalized("bn"));
        assertEquals("Partially Returned", dto.getStatus().getLocalized("en"));
    }
}

