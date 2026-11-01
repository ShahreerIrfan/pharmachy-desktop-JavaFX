package com.pharmacy.pos.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * PosRenewalInvoiceClient
 * Architecture Decision: ADR-0007, ADR-0023 & ADR-0087
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P087)
 *
 * Client-side renewal invoice calculations, tax formatting, and settlement handler
 * for JavaFX Desktop POS.
 */
public class PosRenewalInvoiceClient {

    public static final class InvoiceTotalsCalculation {
        public final BigDecimal subtotalBdt;
        public final BigDecimal discountBdt;
        public final BigDecimal taxRatePercent;
        public final BigDecimal taxAmountBdt;
        public final BigDecimal totalBdt;

        public InvoiceTotalsCalculation(
            BigDecimal subtotalBdt,
            BigDecimal discountBdt,
            BigDecimal taxRatePercent,
            BigDecimal taxAmountBdt,
            BigDecimal totalBdt
        ) {
            this.subtotalBdt = Objects.requireNonNull(subtotalBdt, "subtotalBdt required");
            this.discountBdt = Objects.requireNonNull(discountBdt, "discountBdt required");
            this.taxRatePercent = Objects.requireNonNull(taxRatePercent, "taxRatePercent required");
            this.taxAmountBdt = Objects.requireNonNull(taxAmountBdt, "taxAmountBdt required");
            this.totalBdt = Objects.requireNonNull(totalBdt, "totalBdt required");
        }
    }

    /**
     * Calculates precise BDT financial breakdown for monthly or annual billing.
     */
    public static InvoiceTotalsCalculation calculateTotals(
        BigDecimal monthlyRate,
        String cycle,
        BigDecimal taxRatePercent
    ) {
        BigDecimal baseMonthly = monthlyRate != null ? monthlyRate : BigDecimal.ZERO;
        String cleanCycle = cycle != null ? cycle.toUpperCase().trim() : "MONTHLY";
        BigDecimal taxRate = taxRatePercent != null ? taxRatePercent : new BigDecimal("5.00");

        BigDecimal subtotal;
        BigDecimal discount;

        if ("ANNUAL".equals(cleanCycle)) {
            subtotal = baseMonthly.multiply(new BigDecimal("12.00")).setScale(2, RoundingMode.HALF_UP);
            // 2 months free annual prepay discount (16.67%)
            discount = baseMonthly.multiply(new BigDecimal("2.00")).setScale(2, RoundingMode.HALF_UP);
        } else {
            subtotal = baseMonthly.setScale(2, RoundingMode.HALF_UP);
            discount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal taxableAmount = subtotal.subtract(discount).max(BigDecimal.ZERO);
        BigDecimal taxAmount = taxableAmount
            .multiply(taxRate)
            .divide(new BigDecimal("100.00"), 2, RoundingMode.HALF_UP);

        BigDecimal total = taxableAmount.add(taxAmount).setScale(2, RoundingMode.HALF_UP);

        return new InvoiceTotalsCalculation(subtotal, discount, taxRate, taxAmount, total);
    }

    /**
     * Verifies whether an invoice can accept payment settlement.
     */
    public static boolean isInvoicePayable(String status) {
        if (status == null) {
            return false;
        }
        String clean = status.toUpperCase().trim();
        return "ISSUED".equals(clean) || "PAST_DUE".equals(clean);
    }

    /**
     * Formats currency with BDT symbol.
     */
    public static String formatCurrency(BigDecimal amount) {
        BigDecimal val = amount != null ? amount : BigDecimal.ZERO;
        return "৳ " + val.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
