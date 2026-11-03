package com.pharmacy.pos.storage;

import java.math.BigDecimal;
import java.util.*;

/**
 * PosStorageConditionalClient
 * Architecture Decision: ADR-0155 (P155).
 * Phase 08: Milestone M04 / M05 - Specialized Item & Consumable Editors.
 *
 * Client-side validation and conditional cold-chain engine for JavaFX Desktop POS.
 * Governs conditional exposure of cold-chain requirements, safe temperature ranges,
 * refrigerator shelf placement, and excursion tolerance protocols.
 */
public class PosStorageConditionalClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public enum StoragePlacementZone {
        SHELF_MIDDLE("Middle Shelf (Uniform Temperature)", "মাঝের তাক (স্থির তাপমাত্রা)"),
        SHELF_TOP("Top Shelf", "উপরের তাক"),
        SHELF_BOTTOM("Bottom Shelf (Above Crisper)", "নিচের তাক (ক্রিসপারের উপরে)"),
        CHILLER_TRAY("Chiller Tray (2°C - 4°C)", "চিলার ট্রে (২°সে - ৪°সে)"),
        DOOR_NOT_PERMITTED("Door Placement Prohibited", "দরজায় রাখা কঠোরভাবে নিষিদ্ধ");

        private final String labelEn;
        private final String labelBn;

        StoragePlacementZone(String labelEn, String labelBn) {
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

    public enum ExcursionHandlingAction {
        QUARANTINE_IMMEDIATELY("Quarantine Immediately for Assessment", "তাত্ক্ষণিক কোয়ারেন্টাইন করুন"),
        RETURN_TO_SUPPLIER("Return to Cold-Chain Supplier", "কোল্ড-চেইন সরবরাহকারীকে ফেরত পাঠান"),
        DISPOSE_BIOHAZARD("Biohazard Safe Disposal", "নিরাপদ বায়োহ্যাজার্ড বিনষ্টকরণ"),
        NOTIFY_SUPERVISOR("Notify Chief Pharmacist", "প্রধান ফার্মাসিস্টকে অবহিত করুন");

        private final String labelEn;
        private final String labelBn;

        ExcursionHandlingAction(String labelEn, String labelBn) {
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

    public static final class StorageValidationResult {
        public final boolean isValid;
        public final String errorCode;
        public final String errorMessage;
        public final boolean isColdChainRequired;
        public final BigDecimal temperatureDeltaCelsius;

        private StorageValidationResult(
                boolean isValid,
                String errorCode,
                String errorMessage,
                boolean isColdChainRequired,
                BigDecimal temperatureDeltaCelsius) {
            this.isValid = isValid;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.isColdChainRequired = isColdChainRequired;
            this.temperatureDeltaCelsius = temperatureDeltaCelsius;
        }

        public static StorageValidationResult success(boolean isColdChain, BigDecimal delta) {
            return new StorageValidationResult(true, null, null, isColdChain, delta);
        }

        public static StorageValidationResult failure(String errorCode, String errorMessage) {
            return new StorageValidationResult(false, errorCode, errorMessage, false, BigDecimal.ZERO);
        }
    }

    /**
     * Validates conditional storage parameters.
     * When isColdChainRequired is false, cold chain requirements are cleanly inactive.
     */
    public StorageValidationResult validateStorageConditions(
            boolean isColdChainRequired,
            BigDecimal minTempCelsius,
            BigDecimal maxTempCelsius,
            String refrigeratorPlacement,
            int maxExcursionHours,
            String excursionHandlingAction) {

        if (!isColdChainRequired) {
            return StorageValidationResult.success(false, BigDecimal.ZERO);
        }

        if (minTempCelsius == null || maxTempCelsius == null) {
            return StorageValidationResult.failure(
                    "ERR_MISSING_TEMPERATURE_RANGE",
                    "Minimum and maximum temperatures are mandatory for cold-chain products."
            );
        }

        if (minTempCelsius.compareTo(maxTempCelsius) >= 0) {
            return StorageValidationResult.failure(
                    "ERR_INVALID_TEMPERATURE_RANGE",
                    "Minimum temperature (" + minTempCelsius + "°C) must be less than maximum temperature (" + maxTempCelsius + "°C)."
            );
        }

        if (refrigeratorPlacement == null || refrigeratorPlacement.trim().isEmpty()) {
            return StorageValidationResult.failure(
                    "ERR_MISSING_PLACEMENT_ZONE",
                    "Refrigerator placement zone is required for cold-chain products."
            );
        }

        String normalizedPlacement = refrigeratorPlacement.trim().toUpperCase();
        if ("DOOR_NOT_PERMITTED".equals(normalizedPlacement)) {
            return StorageValidationResult.failure(
                    "ERR_PROHIBITED_DOOR_PLACEMENT",
                    "Medicines and biologicals cannot be placed on refrigerator doors."
            );
        }

        if (maxExcursionHours < 0 || maxExcursionHours > 72) {
            return StorageValidationResult.failure(
                    "ERR_INVALID_EXCURSION_HOURS",
                    "Maximum excursion hours must be between 0 and 72."
            );
        }

        BigDecimal delta = maxTempCelsius.subtract(minTempCelsius);
        return StorageValidationResult.success(true, delta);
    }

    /**
     * Cashier role cannot mutate storage configurations.
     */
    public boolean canMutateStorage(String role) {
        if (role == null) return false;
        String normalized = role.toUpperCase().trim();
        return !"CASHIER".equals(normalized);
    }

    /**
     * Resolves localized label for refrigerator shelf placement.
     */
    public String getPlacementDisplay(String code, String locale) {
        if (code == null) return "";
        try {
            StoragePlacementZone zone = StoragePlacementZone.valueOf(code.toUpperCase().trim());
            return "bn".equalsIgnoreCase(locale) ? zone.getLabelBn() : zone.getLabelEn();
        } catch (IllegalArgumentException e) {
            return code;
        }
    }

    /**
     * Resolves localized label for excursion handling action.
     */
    public String getActionDisplay(String code, String locale) {
        if (code == null) return "";
        try {
            ExcursionHandlingAction action = ExcursionHandlingAction.valueOf(code.toUpperCase().trim());
            return "bn".equalsIgnoreCase(locale) ? action.getLabelBn() : action.getLabelEn();
        } catch (IllegalArgumentException e) {
            return code;
        }
    }
}

