package com.pharmacy.pos.models;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Architecture Decision: ADR-0055
 * POS Client implementation of Time and Money Standards:
 * - BigDecimal scale 2, RoundingMode.HALF_UP
 * - Rejection of raw floats or negative values where disallowed
 * - Asia/Dhaka (UTC+6) display conversion
 * - South Asian number grouping (Lakhs and Crores)
 */
public final class PosMoneyTimeStandards {

    public static final String CURRENCY_CODE = "BDT";
    public static final String CURRENCY_SYMBOL = "৳";
    public static final ZoneId ZONE_UTC = ZoneId.of("UTC");
    public static final ZoneId ZONE_DHAKA = ZoneId.of("Asia/Dhaka");
    public static final int MONETARY_SCALE = 2;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    private static final DateTimeFormatter DHAKA_DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a");

    private static final String[] BN_DIGITS = {"০", "১", "২", "৩", "৪", "৫", "৬", "৭", "৮", "৯"};

    private PosMoneyTimeStandards() {
        // utility class
    }

    public static BigDecimal quantize(BigDecimal amount) {
        Objects.requireNonNull(amount, "Monetary amount cannot be null");
        return amount.setScale(MONETARY_SCALE, ROUNDING_MODE);
    }

    public static BigDecimal parseDecimal(String raw) {
        Objects.requireNonNull(raw, "Monetary string cannot be null");
        String cleaned = raw.replace("৳", "").replace("Tk", "").replace(",", "").trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Monetary string cannot be empty");
        }
        return quantize(new BigDecimal(cleaned));
    }

    public static String formatSouthAsian(BigDecimal amount) {
        BigDecimal q = quantize(amount);
        String sign = q.compareTo(BigDecimal.ZERO) < 0 ? "-" : "";
        BigDecimal absVal = q.abs();
        String strVal = String.format("%.2f", absVal);
        String[] parts = strVal.split("\\.");
        String integerPart = parts[0];
        String fractionPart = parts[1];

        String groupedInteger;
        if (integerPart.length() <= 3) {
            groupedInteger = integerPart;
        } else {
            String lastThree = integerPart.substring(integerPart.length() - 3);
            String remaining = integerPart.substring(0, integerPart.length() - 3);
            StringBuilder sb = new StringBuilder();
            while (remaining.length() > 2) {
                String pair = remaining.substring(remaining.length() - 2);
                sb.insert(0, "," + pair);
                remaining = remaining.substring(0, remaining.length() - 2);
            }
            if (!remaining.isEmpty()) {
                sb.insert(0, remaining);
            }
            sb.append(",").append(lastThree);
            groupedInteger = sb.toString();
        }

        return sign + groupedInteger + "." + fractionPart;
    }

    public static String toBengaliDigits(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c >= '0' && c <= '9') {
                sb.append(BN_DIGITS[c - '0']);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String formatBdt(BigDecimal amount, boolean inBengali) {
        String formatted = formatSouthAsian(amount);
        if (inBengali) {
            return CURRENCY_SYMBOL + toBengaliDigits(formatted);
        }
        return CURRENCY_SYMBOL + " " + formatted;
    }

    public static ZonedDateTime toDhaka(Instant instant) {
        Objects.requireNonNull(instant, "Instant cannot be null");
        return instant.atZone(ZONE_DHAKA);
    }

    public static String formatDhakaDisplay(Instant instant) {
        return toDhaka(instant).format(DHAKA_DATETIME_FORMATTER);
    }

    public static boolean isBatchExpired(LocalDate expiryDate, LocalDate referenceDhakaDate) {
        Objects.requireNonNull(expiryDate, "expiryDate cannot be null");
        LocalDate ref = referenceDhakaDate != null ? referenceDhakaDate : LocalDate.now(ZONE_DHAKA);
        return expiryDate.isBefore(ref);
    }

    public static PosInvoiceSummary calculateInvoice(
            List<CartItem> items,
            BigDecimal invoiceDiscount,
            BigDecimal paidAmount
    ) {
        Objects.requireNonNull(items, "Cart items cannot be null");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Cart cannot be empty");
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal totalVat = BigDecimal.ZERO;

        for (CartItem item : items) {
            subtotal = subtotal.add(item.getLineSubtotal());
            totalVat = totalVat.add(item.getLineVat());
        }

        subtotal = quantize(subtotal);
        totalVat = quantize(totalVat);

        BigDecimal discount = invoiceDiscount != null ? quantize(invoiceDiscount) : BigDecimal.ZERO.setScale(2);
        if (discount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Invoice discount cannot be negative");
        }

        BigDecimal netTaxable = subtotal.subtract(discount).max(BigDecimal.ZERO).setScale(2, ROUNDING_MODE);
        BigDecimal grandTotal = quantize(netTaxable.add(totalVat));

        BigDecimal paid = paidAmount != null ? quantize(paidAmount) : BigDecimal.ZERO.setScale(2);
        BigDecimal due = grandTotal.subtract(paid).max(BigDecimal.ZERO).setScale(2, ROUNDING_MODE);
        BigDecimal change = paid.subtract(grandTotal).max(BigDecimal.ZERO).setScale(2, ROUNDING_MODE);

        return new PosInvoiceSummary(
                items.size(),
                subtotal,
                discount,
                totalVat,
                grandTotal,
                paid,
                due,
                change,
                Instant.now()
        );
    }

    public static record PosInvoiceSummary(
            int itemCount,
            BigDecimal subtotal,
            BigDecimal discount,
            BigDecimal totalVat,
            BigDecimal grandTotal,
            BigDecimal paidAmount,
            BigDecimal dueAmount,
            BigDecimal changeAmount,
            Instant createdAtUtc
    ) {
        public String getDhakaDisplay() {
            return formatDhakaDisplay(createdAtUtc);
        }

        public String getGrandTotalFormattedBdt(boolean inBengali) {
            return formatBdt(grandTotal, inBengali);
        }
    }
}
