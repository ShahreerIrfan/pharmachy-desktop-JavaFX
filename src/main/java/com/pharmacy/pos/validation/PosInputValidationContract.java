package com.pharmacy.pos.validation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * JavaFX Desktop POS Input Validation Contract & Client-Side Guardrails.
 * Architecture Decision: ADR-0014 & ADR-0046.
 */
public class PosInputValidationContract {

    private static final Pattern BD_PHONE_PATTERN = Pattern.compile("^\\+8801[3-9]\\d{8}$");

    public record ValidationErrorDetail(
            String errorCode,
            String messageEn,
            String messageBn,
            Map<String, List<String>> fieldErrors,
            boolean isRetryable
    ) {
        public ValidationErrorDetail {
            Objects.requireNonNull(errorCode, "errorCode cannot be null");
            Objects.requireNonNull(messageEn, "messageEn cannot be null");
            Objects.requireNonNull(messageBn, "messageBn cannot be null");
            fieldErrors = fieldErrors != null ? Collections.unmodifiableMap(new HashMap<>(fieldErrors)) : Collections.emptyMap();
        }
    }

    public static BigDecimal validateMoney(BigDecimal amount, String fieldName) {
        Objects.requireNonNull(amount, fieldName + " cannot be null");
        if (amount.scale() > 2) {
            throw new IllegalArgumentException(fieldName + " cannot have more than 2 decimal places: " + amount);
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(fieldName + " cannot be negative: " + amount);
        }
        return amount;
    }

    public static int validateQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be strictly positive (>0). Received: " + quantity);
        }
        if (quantity > 1_000_000) {
            throw new IllegalArgumentException("Quantity exceeds maximum allowable limit of 1,000,000 units.");
        }
        return quantity;
    }

    public static LocalDate validateExpiryDate(LocalDate expiryDate, LocalDate referenceDate) {
        Objects.requireNonNull(expiryDate, "expiryDate cannot be null");
        LocalDate today = referenceDate != null ? referenceDate : LocalDate.now();
        if (expiryDate.isBefore(today)) {
            throw new IllegalArgumentException(
                    "Expiry date " + expiryDate + " is in the past. Selling expired medicine is prohibited under DGDA regulations."
            );
        }
        return expiryDate;
    }

    public static String validatePhone(String rawPhone) {
        if (rawPhone == null || rawPhone.trim().isEmpty()) {
            return "";
        }
        String cleaned = rawPhone.replaceAll("[\\s\\-\\(\\)]", "").trim();
        if (cleaned.startsWith("01")) {
            cleaned = "+88" + cleaned;
        } else if (cleaned.startsWith("880")) {
            cleaned = "+" + cleaned;
        } else if (cleaned.startsWith("1")) {
            cleaned = "+880" + cleaned;
        }

        if (!BD_PHONE_PATTERN.matcher(cleaned).matches()) {
            throw new IllegalArgumentException(
                    "Invalid Bangladesh phone number format: '" + rawPhone + "'. Must match +8801[3-9]XXXXXXXX."
            );
        }
        return cleaned;
    }

    public static void validateDiscount(BigDecimal total, BigDecimal discount) {
        validateMoney(total, "total");
        validateMoney(discount, "discount");
        if (discount.compareTo(total) > 0) {
            throw new IllegalArgumentException(
                    "Discount amount (" + discount + ") cannot exceed total bill amount (" + total + ")."
            );
        }
    }

    @SuppressWarnings("unchecked")
    public static ValidationErrorDetail parseValidationError(Map<String, Object> errorMap) {
        Objects.requireNonNull(errorMap, "Error response payload cannot be null");

        String code = (String) errorMap.getOrDefault("error_code", errorMap.getOrDefault("code", "ERR_VALIDATION_FAILED"));
        String msgEn = (String) errorMap.getOrDefault("message_en", errorMap.getOrDefault("message", "Validation failed."));
        String msgBn = (String) errorMap.getOrDefault("message_bn", "তথ্য যাচাইকরণ ব্যর্থ হয়েছে।");
        boolean retryable = Boolean.TRUE.equals(errorMap.get("is_retryable")) || Boolean.TRUE.equals(errorMap.get("retryable"));

        Map<String, List<String>> fields = new HashMap<>();
        Object detailsObj = errorMap.get("field_errors");
        if (detailsObj == null) {
            detailsObj = errorMap.get("details");
        }

        if (detailsObj instanceof Map<?, ?> detailsMap) {
            for (Map.Entry<?, ?> entry : detailsMap.entrySet()) {
                if (entry.getValue() instanceof List<?> list) {
                    fields.put(String.valueOf(entry.getKey()), list.stream().map(String::valueOf).toList());
                } else if (entry.getValue() != null) {
                    fields.put(String.valueOf(entry.getKey()), List.of(String.valueOf(entry.getValue())));
                }
            }
        }

        return new ValidationErrorDetail(code, msgEn, msgBn, fields, retryable);
    }
}

