package com.pharmacy.pos.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P046: Desktop POS Input Validation Contract & Guardrails Tests")
class PosInputValidationContractTest {

    @Test
    @DisplayName("Validates exact monetary Decimal scale and non-negativity")
    void testValidateMoneySuccess() {
        BigDecimal amount = new BigDecimal("150.50");
        BigDecimal result = PosInputValidationContract.validateMoney(amount, "unitPrice");
        assertEquals(new BigDecimal("150.50"), result);
    }

    @Test
    @DisplayName("Rejects money amounts with more than 2 decimal places")
    void testValidateMoneyTooManyDecimals() {
        BigDecimal invalidAmount = new BigDecimal("150.555");
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> PosInputValidationContract.validateMoney(invalidAmount, "unitPrice")
        );
        assertTrue(ex.getMessage().contains("cannot have more than 2 decimal places"));
    }

    @Test
    @DisplayName("Rejects negative money amounts")
    void testValidateMoneyNegative() {
        BigDecimal negative = new BigDecimal("-10.00");
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> PosInputValidationContract.validateMoney(negative, "subtotal")
        );
        assertTrue(ex.getMessage().contains("cannot be negative"));
    }

    @Test
    @DisplayName("Validates positive quantities and rejects zero or negative")
    void testValidateQuantity() {
        assertEquals(5, PosInputValidationContract.validateQuantity(5));

        assertThrows(IllegalArgumentException.class, () -> PosInputValidationContract.validateQuantity(0));
        assertThrows(IllegalArgumentException.class, () -> PosInputValidationContract.validateQuantity(-3));
    }

    @Test
    @DisplayName("Enforces DGDA batch expiry date rule: past dates are rejected")
    void testValidateExpiryDate() {
        LocalDate today = LocalDate.of(2026, 10, 31);
        LocalDate futureExp = LocalDate.of(2027, 5, 1);
        LocalDate pastExp = LocalDate.of(2025, 1, 1);

        assertEquals(futureExp, PosInputValidationContract.validateExpiryDate(futureExp, today));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> PosInputValidationContract.validateExpiryDate(pastExp, today)
        );
        assertTrue(ex.getMessage().contains("is in the past"));
    }

    @Test
    @DisplayName("Normalizes valid Bangladesh phone numbers and rejects malformed formats")
    void testValidatePhone() {
        assertEquals("+8801712345678", PosInputValidationContract.validatePhone("01712-345678"));
        assertEquals("+8801812345678", PosInputValidationContract.validatePhone("+8801812345678"));

        assertThrows(IllegalArgumentException.class, () -> PosInputValidationContract.validatePhone("0123456789"));
        assertThrows(IllegalArgumentException.class, () -> PosInputValidationContract.validatePhone("invalid-phone"));
    }

    @Test
    @DisplayName("Validates discount bounds against total bill amount")
    void testValidateDiscountBounds() {
        BigDecimal total = new BigDecimal("500.00");
        BigDecimal validDiscount = new BigDecimal("50.00");
        BigDecimal excessiveDiscount = new BigDecimal("600.00");

        assertDoesNotThrow(() -> PosInputValidationContract.validateDiscount(total, validDiscount));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> PosInputValidationContract.validateDiscount(total, excessiveDiscount)
        );
        assertTrue(ex.getMessage().contains("cannot exceed total bill amount"));
    }

    @Test
    @DisplayName("Parses server 422 error payload conforming to ADR-0014 and ADR-0046")
    void testParseValidationErrorPayload() {
        Map<String, Object> serverError = Map.of(
                "error_code", "ERR_VALIDATION_FAILED",
                "message_en", "One or more input fields failed validation.",
                "message_bn", "এক বা একাধিক তথ্যের মান সঠিক নয়।",
                "is_retryable", false,
                "field_errors", Map.of(
                        "quantity", List.of("Quantity must be positive."),
                        "mrp_unit", List.of("MRP cannot be less than cost price.")
                )
        );

        PosInputValidationContract.ValidationErrorDetail detail =
                PosInputValidationContract.parseValidationError(serverError);

        assertEquals("ERR_VALIDATION_FAILED", detail.errorCode());
        assertEquals("One or more input fields failed validation.", detail.messageEn());
        assertEquals("এক বা একাধিক তথ্যের মান সঠিক নয়।", detail.messageBn());
        assertFalse(detail.isRetryable());
        assertTrue(detail.fieldErrors().containsKey("quantity"));
        assertEquals(List.of("Quantity must be positive."), detail.fieldErrors().get("quantity"));
    }
}

