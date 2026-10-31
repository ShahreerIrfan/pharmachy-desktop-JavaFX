package com.pharmacy.pos.security;

import com.pharmacy.pos.models.CartItem;
import com.pharmacy.pos.models.PosMoneyTimeStandards;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0055: POS Money and Time Standards Tests")
class PosMoneyTimeStandardsTest {

    @Test
    @DisplayName("Should quantize monetary amount with scale 2 and ROUND_HALF_UP")
    void testMonetaryQuantization() {
        BigDecimal val1 = new BigDecimal("10.004");
        BigDecimal val2 = new BigDecimal("10.005");

        assertEquals(new BigDecimal("10.00"), PosMoneyTimeStandards.quantize(val1));
        assertEquals(new BigDecimal("10.01"), PosMoneyTimeStandards.quantize(val2));
    }

    @Test
    @DisplayName("Should format South Asian Lakh/Crore grouping accurately")
    void testSouthAsianNumberFormatting() {
        BigDecimal amount = new BigDecimal("152345.50");
        String formatted = PosMoneyTimeStandards.formatSouthAsian(amount);
        assertEquals("1,52,345.50", formatted);

        String bdtEn = PosMoneyTimeStandards.formatBdt(amount, false);
        assertEquals("৳ 1,52,345.50", bdtEn);

        String bdtBn = PosMoneyTimeStandards.formatBdt(amount, true);
        assertEquals("৳১,৫২,৩৪৫.৫০", bdtBn);
    }

    @Test
    @DisplayName("Should convert UTC Instant to Asia/Dhaka (+06:00)")
    void testDhakaTimeConversion() {
        // 2026-10-31T06:00:00Z is 12:00 PM in Asia/Dhaka
        Instant utcInstant = Instant.parse("2026-10-31T06:00:00Z");
        ZonedDateTime dhakaTime = PosMoneyTimeStandards.toDhaka(utcInstant);

        assertEquals(12, dhakaTime.getHour());
        assertEquals(0, dhakaTime.getMinute());
        assertEquals("Asia/Dhaka", dhakaTime.getZone().getId());

        String display = PosMoneyTimeStandards.formatDhakaDisplay(utcInstant);
        assertTrue(display.contains("31/10/2026") || display.contains("12:00"));
    }

    @Test
    @DisplayName("Should verify medicine batch expiry against Dhaka business date")
    void testBatchExpiry() {
        LocalDate refDate = LocalDate.of(2026, 10, 31);
        LocalDate expiredBatch = LocalDate.of(2026, 10, 30);
        LocalDate validBatch = LocalDate.of(2026, 11, 1);

        assertTrue(PosMoneyTimeStandards.isBatchExpired(expiredBatch, refDate));
        assertFalse(PosMoneyTimeStandards.isBatchExpired(validBatch, refDate));
    }

    @Test
    @DisplayName("Should calculate invoice totals deterministically with exact Decimal balance")
    void testInvoiceCalculation() {
        CartItem item1 = new CartItem(
                "MED-001", "Napa Extra", "Paracetamol", "STRIP",
                10, new BigDecimal("2.50"), "B-101", LocalDate.of(2027, 1, 1), new BigDecimal("0.05")
        );
        CartItem item2 = new CartItem(
                "MED-002", "Seclo 20", "Omeprazole", "BOX",
                2, new BigDecimal("80.00"), "B-102", LocalDate.of(2027, 6, 1), new BigDecimal("0.05")
        );

        // Subtotals: item1 = 25.00, VAT = 1.25. item2 = 160.00, VAT = 8.00.
        // Total Subtotal = 185.00, Total VAT = 9.25.
        // With invoice discount = 10.00 -> Net taxable = 175.00 -> Grand Total = 184.25
        PosMoneyTimeStandards.PosInvoiceSummary summary = PosMoneyTimeStandards.calculateInvoice(
                List.of(item1, item2),
                new BigDecimal("10.00"),
                new BigDecimal("200.00")
        );

        assertEquals(new BigDecimal("185.00"), summary.subtotal());
        assertEquals(new BigDecimal("10.00"), summary.discount());
        assertEquals(new BigDecimal("9.25"), summary.totalVat());
        assertEquals(new BigDecimal("184.25"), summary.grandTotal());
        assertEquals(new BigDecimal("200.00"), summary.paidAmount());
        assertEquals(new BigDecimal("0.00"), summary.dueAmount());
        assertEquals(new BigDecimal("15.75"), summary.changeAmount());
    }
}
