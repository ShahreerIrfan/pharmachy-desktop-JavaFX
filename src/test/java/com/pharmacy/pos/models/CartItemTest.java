package com.pharmacy.pos.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CartItem financial mathematics and precision.
 * Architecture Decision: ADR-0001, ADR-0021 & ADR-0023.
 */
class CartItemTest {

    @Test
    @DisplayName("Line total calculates precisely with BigDecimal without float drift")
    void testLineCalculationsBigDecimalPrecision() {
        CartItem item = new CartItem(
                "med-001",
                "Napa Extra 500mg/65mg",
                "Paracetamol + Caffeine",
                "STRIP",
                10,
                new BigDecimal("25.00"),
                "B-2026-09",
                LocalDate.of(2027, 12, 31),
                new BigDecimal("0.05") // 5% VAT
        );

        // Subtotal: 10 * 25.00 = 250.00
        assertEquals(new BigDecimal("250.00"), item.getLineSubtotal());

        // VAT: 250.00 * 0.05 = 12.50
        assertEquals(new BigDecimal("12.50"), item.getLineVat());

        // Line Total: 250.00 + 12.50 = 262.50
        assertEquals(new BigDecimal("262.50"), item.getLineTotal());
    }

    @Test
    @DisplayName("Quantity must be strictly positive (>0)")
    void testRejectsZeroOrNegativeQuantity() {
        assertThrows(IllegalArgumentException.class, () -> new CartItem(
                "med-001", "Napa Extra", "Paracetamol", "STRIP",
                0, new BigDecimal("25.00"), "B-001", LocalDate.now(), BigDecimal.ZERO
        ));

        assertThrows(IllegalArgumentException.class, () -> new CartItem(
                "med-001", "Napa Extra", "Paracetamol", "STRIP",
                -5, new BigDecimal("25.00"), "B-001", LocalDate.now(), BigDecimal.ZERO
        ));
    }

    @Test
    @DisplayName("Negative unit price is rejected")
    void testRejectsNegativeUnitPrice() {
        assertThrows(IllegalArgumentException.class, () -> new CartItem(
                "med-001", "Napa Extra", "Paracetamol", "STRIP",
                2, new BigDecimal("-10.00"), "B-001", LocalDate.now(), BigDecimal.ZERO
        ));
    }

    @Test
    @DisplayName("Empty or whitespace batch number is rejected")
    void testRejectsEmptyBatchNumber() {
        assertThrows(IllegalArgumentException.class, () -> new CartItem(
                "med-001", "Napa Extra", "Paracetamol", "STRIP",
                2, new BigDecimal("20.00"), "   ", LocalDate.now(), BigDecimal.ZERO
        ));
    }
}

