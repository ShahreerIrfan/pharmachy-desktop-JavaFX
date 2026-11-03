package com.pharmacy.pos.stock;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * PosExpiryManufacturingClient
 * Architecture Decision: ADR-0154 (P154).
 * Phase 08: Milestone M04 / M05 - Regulated Expiry and Manufacturing Date Controls.
 *
 * Client-side validation and stock intake engine for JavaFX Desktop POS.
 * Coordinates DGDA compliance, quarantine alerts, shelf-life calculations,
 * and bilingual storage directives.
 */
public class PosExpiryManufacturingClient {

    private static final Pattern LOT_PATTERN = Pattern.compile("^[A-Za-z0-9\\-_./]+$");

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public enum StorageCondition {
        ROOM_TEMPERATURE_15_25C("Room Temperature (15°C - 25°C)", "স্বাভাবিক তাপমাত্রা (১৫°সে - ২৫°সে)"),
        COOL_8_15C("Cool Storage (8°C - 15°C)", "শীতল তাপমাত্রা (৮°সে - ১৫°সে)"),
        COLD_CHAIN_2_8C("Cold Chain (2°C - 8°C)", "কোল্ড চেইন রেফ্রিজারেশন (২°সে - ৮°সে)"),
        FREEZER_MINUS_20C("Freezer (-20°C)", "ডিপ ফ্রিজার (-২০°সে)"),
        DO_NOT_FREEZE("Do Not Freeze", "ফ্রিজ করবেন না"),
        PROTECT_FROM_LIGHT("Protect From Direct Light", "সরাসরি আলো থেকে সুরক্ষিত রাখুন");

        private final String labelEn;
        private final String labelBn;

        StorageCondition(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }

        public String getLabelEn() {
            return labelEn;
        }

        public String getLabelBn() {
            return labelBn;
        }
    }

    public static final class ValidationResult {
        public final boolean isValid;
        public final String errorCode;
        public final String errorMessage;
        public final long shelfLifeDays;
        public final boolean isAutoQuarantined;

        private ValidationResult(
                boolean isValid,
                String errorCode,
                String errorMessage,
                long shelfLifeDays,
                boolean isAutoQuarantined) {
            this.isValid = isValid;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.shelfLifeDays = shelfLifeDays;
            this.isAutoQuarantined = isAutoQuarantined;
        }

        public static ValidationResult success(long shelfLifeDays, boolean isAutoQuarantined) {
            return new ValidationResult(true, null, null, shelfLifeDays, isAutoQuarantined);
        }

        public static ValidationResult failure(String errorCode, String errorMessage) {
            return new ValidationResult(false, errorCode, errorMessage, 0, false);
        }
    }

    /**
     * Validates regulated stock intake dates and lot reference per DGDA guidelines.
     */
    public ValidationResult validateStockIntake(
            String lotReference,
            LocalDate manufacturingDate,
            LocalDate expiryDate,
            LocalDate currentDate,
            int quarantineLeadDays,
            boolean isQuarantined) {

        if (currentDate == null) {
            currentDate = LocalDate.now();
        }

        if (lotReference == null || lotReference.trim().isEmpty()) {
            return ValidationResult.failure("ERR_MISSING_LOT_REFERENCE", "Lot reference is required.");
        }

        String trimmedLot = lotReference.trim();
        if (trimmedLot.length() < 2 || trimmedLot.length() > 64) {
            return ValidationResult.failure("ERR_INVALID_LOT_LENGTH", "Lot reference must be 2 to 64 characters.");
        }

        if (!LOT_PATTERN.matcher(trimmedLot).matches()) {
            return ValidationResult.failure("ERR_INVALID_LOT_FORMAT", "Lot reference contains invalid characters.");
        }

        if (manufacturingDate == null) {
            return ValidationResult.failure("ERR_MISSING_MANUFACTURING_DATE", "Manufacturing date is required.");
        }

        if (manufacturingDate.isAfter(currentDate)) {
            return ValidationResult.failure(
                    "ERR_FUTURE_MANUFACTURING_DATE",
                    "Manufacturing date cannot be in the future (" + manufacturingDate + ")."
            );
        }

        if (expiryDate == null) {
            return ValidationResult.failure("ERR_MISSING_EXPIRY_DATE", "Expiration date is required.");
        }

        if (!expiryDate.isAfter(manufacturingDate)) {
            return ValidationResult.failure(
                    "ERR_EXPIRY_BEFORE_MANUFACTURING",
                    "Expiration date must be strictly after manufacturing date."
            );
        }

        long daysUntilExpiry = ChronoUnit.DAYS.between(currentDate, expiryDate);
        if (daysUntilExpiry <= 0 && !isQuarantined) {
            return ValidationResult.failure(
                    "ERR_STOCK_ALREADY_EXPIRED",
                    "Expired stock cannot be received into active dispensing inventory."
            );
        }

        boolean autoQuarantine = isQuarantined;
        if (daysUntilExpiry <= quarantineLeadDays && !autoQuarantine) {
            autoQuarantine = true;
        }

        long totalShelfLife = ChronoUnit.DAYS.between(manufacturingDate, expiryDate);

        return ValidationResult.success(totalShelfLife, autoQuarantine);
    }

    /**
     * Checks if current user role can perform stock intake mutations.
     * Cashier is read-only.
     */
    public boolean canMutateStock(String role) {
        if (role == null) return false;
        String normalized = role.toUpperCase().trim();
        return !"CASHIER".equals(normalized);
    }

    /**
     * Resolves localized label for storage condition.
     */
    public String getStorageConditionDisplay(String code, String locale) {
        if (code == null) return "";
        try {
            StorageCondition cond = StorageCondition.valueOf(code.toUpperCase().trim());
            return "bn".equalsIgnoreCase(locale) ? cond.getLabelBn() : cond.getLabelEn();
        } catch (IllegalArgumentException e) {
            return code;
        }
    }
}
