package com.pharmacy.pos.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P087: JavaFX Desktop POS Renewal Invoice Client Tests")
public class PosRenewalInvoiceClientTest {

    @Test
    @DisplayName("Monthly renewal calculates subtotal, 5% tax and total BDT precisely")
    void testMonthlyInvoiceCalculation() {
        BigDecimal monthlyRate = new BigDecimal("6500.00"); // Standard plan
        BigDecimal taxRate = new BigDecimal("5.00");

        PosRenewalInvoiceClient.InvoiceTotalsCalculation calc =
            PosRenewalInvoiceClient.calculateTotals(monthlyRate, "MONTHLY", taxRate);

        assertEquals(new BigDecimal("6500.00"), calc.subtotalBdt);
        assertEquals(new BigDecimal("0.00"), calc.discountBdt);
        assertEquals(new BigDecimal("5.00"), calc.taxRatePercent);
        assertEquals(new BigDecimal("325.00"), calc.taxAmountBdt); // 6500 * 0.05
        assertEquals(new BigDecimal("6825.00"), calc.totalBdt);   // 6500 + 325
    }

    @Test
    @DisplayName("Annual renewal applies 2 months free discount and calculates 5% tax")
    void testAnnualInvoiceCalculation() {
        BigDecimal monthlyRate = new BigDecimal("15000.00"); // Pro plan
        BigDecimal taxRate = new BigDecimal("5.00");

        PosRenewalInvoiceClient.InvoiceTotalsCalculation calc =
            PosRenewalInvoiceClient.calculateTotals(monthlyRate, "ANNUAL", taxRate);

        assertEquals(new BigDecimal("180000.00"), calc.subtotalBdt); // 15000 * 12
        assertEquals(new BigDecimal("30000.00"), calc.discountBdt);  // 15000 * 2
        // Taxable = 150000.00
        assertEquals(new BigDecimal("7500.00"), calc.taxAmountBdt);   // 150000 * 0.05
        assertEquals(new BigDecimal("157500.00"), calc.totalBdt);    // 150000 + 7500
    }

    @Test
    @DisplayName("Payable status logic permits ISSUED and PAST_DUE, rejects PAID and VOID")
    void testPayableStatusValidation() {
        assertTrue(PosRenewalInvoiceClient.isInvoicePayable("ISSUED"));
        assertTrue(PosRenewalInvoiceClient.isInvoicePayable("PAST_DUE"));
        assertFalse(PosRenewalInvoiceClient.isInvoicePayable("PAID"));
        assertFalse(PosRenewalInvoiceClient.isInvoicePayable("VOID"));
        assertFalse(PosRenewalInvoiceClient.isInvoicePayable("DRAFT"));
        assertFalse(PosRenewalInvoiceClient.isInvoicePayable(null));
    }

    @Test
    @DisplayName("Currency formatting renders BDT symbol and two decimal places")
    void testCurrencyFormatting() {
        assertEquals("৳ 6825.00", PosRenewalInvoiceClient.formatCurrency(new BigDecimal("6825.00")));
        assertEquals("৳ 0.00", PosRenewalInvoiceClient.formatCurrency(null));
    }
}
