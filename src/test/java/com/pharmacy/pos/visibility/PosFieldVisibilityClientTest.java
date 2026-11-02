package com.pharmacy.pos.visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P110: PosFieldVisibilityClient JUnit 5 Tests.
 * Verifies fine-grained field masking for costs, profits, salaries, prescriptions,
 * patient PII, and secure client-side CSV exports.
 */
public class PosFieldVisibilityClientTest {

    @Test
    @DisplayName("P110: Pharmacy Owner has unrestricted access to all 5 sensitive domains")
    void testOwnerUnrestrictedVisibility() {
        PosFieldVisibilityClient.FieldVisibilityProfile profile = PosFieldVisibilityClient.resolveProfile(
                "usr-owner-01", "tn-popular", "PHARMACY_OWNER", Collections.emptyList()
        );

        assertTrue(profile.costVisibility);
        assertTrue(profile.profitVisibility);
        assertTrue(profile.salaryVisibility);
        assertTrue(profile.prescriptionVisibility);
        assertTrue(profile.patientPiiVisibility);

        PosFieldVisibilityClient.MaskedMedicine med = PosFieldVisibilityClient.maskMedicine(
                "med-1", "Napa 500mg", new BigDecimal("1.20"), new BigDecimal("0.85"), profile
        );
        assertFalse(med.isCostMasked);
        assertEquals(new BigDecimal("0.85"), med.costUnit);
        assertEquals("৳0.85", med.getDisplayCost("en"));
    }

    @Test
    @DisplayName("P110: Cashier has purchase costs, profits, salaries, prescriptions and PII masked")
    void testCashierAllSensitiveFieldsMasked() {
        PosFieldVisibilityClient.FieldVisibilityProfile profile = PosFieldVisibilityClient.resolveProfile(
                "usr-cashier-01", "tn-popular", "CASHIER", Collections.emptyList()
        );

        assertFalse(profile.costVisibility);
        assertFalse(profile.profitVisibility);
        assertFalse(profile.salaryVisibility);
        assertFalse(profile.prescriptionVisibility);
        assertFalse(profile.patientPiiVisibility);

        // 1. Medicine cost masked
        PosFieldVisibilityClient.MaskedMedicine med = PosFieldVisibilityClient.maskMedicine(
                "med-2", "Ace Plus", new BigDecimal("2.50"), new BigDecimal("1.70"), profile
        );
        assertTrue(med.isCostMasked);
        assertNull(med.costUnit);
        assertEquals("***", med.getDisplayCost("bn"));

        // 2. Invoice profit & customer phone masked
        PosFieldVisibilityClient.MaskedInvoice inv = PosFieldVisibilityClient.maskInvoice(
                "inv-101", "Shahidul Islam", "01712345678",
                new BigDecimal("500.00"), new BigDecimal("350.00"), new BigDecimal("150.00"), profile
        );
        assertTrue(inv.isCostMasked);
        assertTrue(inv.isProfitMasked);
        assertTrue(inv.isPiiMasked);
        assertNull(inv.grossProfit);
        assertEquals("017****5678", inv.customerPhone);
        assertEquals("S**** I****", inv.customerName);
        assertEquals("***", inv.getDisplayProfit("en"));

        // 3. Salary masked
        PosFieldVisibilityClient.MaskedSalary sal = PosFieldVisibilityClient.maskSalary(
                "sal-01", "usr-cashier-01", "cashier1",
                new BigDecimal("22000.00"), "1234567890", profile
        );
        assertTrue(sal.isSalaryMasked);
        assertNull(sal.baseSalaryBdt);
        assertEquals("***REDACTED***", sal.bankAccountNo);
        assertEquals("***REDACTED***", sal.getDisplaySalary("en"));

        // 4. Prescription clinical notes masked
        PosFieldVisibilityClient.MaskedPrescription rx = PosFieldVisibilityClient.maskPrescription(
                "rx-01", "Dr. Rahman", "Bacterial Pharyngitis", "Take Amoxicillin 500mg TDS",
                "https://cdn.example.com/rx/1.jpg", profile
        );
        assertTrue(rx.isPrescriptionMasked);
        assertEquals("[CLINICAL_DIAGNOSIS_REDACTED]", rx.diagnosis);
        assertEquals("[CONFIDENTIAL_CLINICAL_NOTES]", rx.notes);
        assertNull(rx.prescriptionImageUrl);
    }

    @Test
    @DisplayName("P110: Branch Manager sees costs and clinical data but has profits and salaries masked")
    void testBranchManagerVisibilityBoundaries() {
        PosFieldVisibilityClient.FieldVisibilityProfile profile = PosFieldVisibilityClient.resolveProfile(
                "usr-mgr-01", "tn-popular", "BRANCH_MANAGER", Collections.emptyList()
        );

        assertTrue(profile.costVisibility);
        assertFalse(profile.profitVisibility);
        assertFalse(profile.salaryVisibility);
        assertTrue(profile.prescriptionVisibility);
        assertTrue(profile.patientPiiVisibility);

        PosFieldVisibilityClient.MaskedInvoice inv = PosFieldVisibilityClient.maskInvoice(
                "inv-202", "Rahim Uddin", "01799887766",
                new BigDecimal("1000.00"), new BigDecimal("700.00"), new BigDecimal("300.00"), profile
        );
        assertFalse(inv.isCostMasked);
        assertEquals(new BigDecimal("700.00"), inv.cogsAmount);
        assertTrue(inv.isProfitMasked);
        assertNull(inv.grossProfit);
        assertFalse(inv.isPiiMasked);
        assertEquals("01799887766", inv.customerPhone);
        assertEquals("Rahim Uddin", inv.customerName);
    }

    @Test
    @DisplayName("P110: Finance Officer sees costs, profits, and salaries but clinical Rx and patient PII masked")
    void testFinanceOfficerVisibilityBoundaries() {
        PosFieldVisibilityClient.FieldVisibilityProfile profile = PosFieldVisibilityClient.resolveProfile(
                "usr-fin-01", "tn-popular", "FINANCE_OFFICER", Collections.emptyList()
        );

        assertTrue(profile.costVisibility);
        assertTrue(profile.profitVisibility);
        assertTrue(profile.salaryVisibility);
        assertFalse(profile.prescriptionVisibility);
        assertFalse(profile.patientPiiVisibility);

        PosFieldVisibilityClient.MaskedSalary sal = PosFieldVisibilityClient.maskSalary(
                "sal-02", "usr-stf-02", "pharmacist1",
                new BigDecimal("45000.00"), "9876543210", profile
        );
        assertFalse(sal.isSalaryMasked);
        assertEquals(new BigDecimal("45000.00"), sal.baseSalaryBdt);
        assertEquals("9876543210", sal.bankAccountNo);

        PosFieldVisibilityClient.MaskedPrescription rx = PosFieldVisibilityClient.maskPrescription(
                "rx-02", "Dr. Ahmed", "Hypertension", "Amlodipine 5mg", null, profile
        );
        assertTrue(rx.isPrescriptionMasked);
        assertEquals("[CLINICAL_DIAGNOSIS_REDACTED]", rx.diagnosis);
    }

    @Test
    @DisplayName("P110: Staff Pharmacist sees clinical prescriptions and patient details but costs/profits masked")
    void testStaffPharmacistVisibilityBoundaries() {
        PosFieldVisibilityClient.FieldVisibilityProfile profile = PosFieldVisibilityClient.resolveProfile(
                "usr-rx-01", "tn-popular", "STAFF_PHARMACIST", Collections.emptyList()
        );

        assertFalse(profile.costVisibility);
        assertFalse(profile.profitVisibility);
        assertFalse(profile.salaryVisibility);
        assertTrue(profile.prescriptionVisibility);
        assertTrue(profile.patientPiiVisibility);

        PosFieldVisibilityClient.MaskedPrescription rx = PosFieldVisibilityClient.maskPrescription(
                "rx-03", "Dr. Khan", "Type 2 Diabetes", "Metformin 500mg BID",
                "https://cdn.example.com/rx/3.jpg", profile
        );
        assertFalse(rx.isPrescriptionMasked);
        assertEquals("Type 2 Diabetes", rx.diagnosis);
        assertEquals("Metformin 500mg BID", rx.notes);
        assertEquals("https://cdn.example.com/rx/3.jpg", rx.prescriptionImageUrl);
    }

    @Test
    @DisplayName("P110: Custom Role granting profit_visibility unmasks margin calculations")
    void testCustomRoleProfitVisibilityOverride() {
        List<String> customKeys = List.of("sales:checkout", "finance:profit_visibility", "inventory:view_cost");
        PosFieldVisibilityClient.FieldVisibilityProfile profile = PosFieldVisibilityClient.resolveProfile(
                "usr-custom-01", "tn-popular", "SENIOR_LEAD_CASHIER", customKeys
        );

        assertTrue(profile.costVisibility);
        assertTrue(profile.profitVisibility);
        assertFalse(profile.salaryVisibility);

        PosFieldVisibilityClient.MaskedInvoice inv = PosFieldVisibilityClient.maskInvoice(
                "inv-303", "Anisur Rahman", "01811223344",
                new BigDecimal("800.00"), new BigDecimal("600.00"), new BigDecimal("200.00"), profile
        );
        assertFalse(inv.isCostMasked);
        assertFalse(inv.isProfitMasked);
        assertEquals(new BigDecimal("200.00"), inv.grossProfit);
    }

    @Test
    @DisplayName("P110: CSV export generator applies field masking so cashiers cannot leak profit/costs")
    void testExportInvoicesCsvStrictMasking() {
        PosFieldVisibilityClient.FieldVisibilityProfile profile = PosFieldVisibilityClient.resolveProfile(
                "usr-cashier-02", "tn-popular", "CASHIER", Collections.emptyList()
        );

        PosFieldVisibilityClient.MaskedInvoice inv = PosFieldVisibilityClient.maskInvoice(
                "INV-999", "Tanvir Hasan", "01700112233",
                new BigDecimal("450.00"), new BigDecimal("300.00"), new BigDecimal("150.00"), profile
        );

        String csv = PosFieldVisibilityClient.exportInvoicesCsv(List.of(inv));
        assertFalse(csv.contains("150.00"), "Masked CSV must not contain profit 150.00");
        assertFalse(csv.contains("300.00"), "Masked CSV must not contain cost 300.00");
        assertFalse(csv.contains("01700112233"), "Masked CSV must not contain full phone");
        assertTrue(csv.contains("017****2233"), "Masked CSV should contain masked phone");
        assertTrue(csv.contains("T**** H****"), "Masked CSV should contain masked customer name");
    }
}

