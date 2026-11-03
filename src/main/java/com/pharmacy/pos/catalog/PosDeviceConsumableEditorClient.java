package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.util.*;

/**
 * PosDeviceConsumableEditorClient
 * Architecture Decision: ADR-0151 (P151).
 * Phase 08: Milestone M04 / M05 - Specialized Item & Consumable Editors.
 *
 * Client-side integration for JavaFX Desktop POS managing Medical Devices,
 * needles, diagnostic test strips, and surgical consumables without medicine-only restrictions.
 */
public class PosDeviceConsumableEditorClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class DeviceConsumableRecord {
        public final String id;
        public final String productId;
        public final String tenantId;
        public final String deviceCategory;
        public final String brandName;
        public final String modelNumber;
        public final String needleGauge;
        public final BigDecimal needleLengthMm;
        public final String meterCompatibility;
        public final int stripCountPerPack;
        public final BigDecimal sampleVolumeMicroliter;
        public final int testTimeSeconds;
        public final boolean isSterile;
        public final boolean isSingleUse;
        public final String regulatoryDeviceClass;
        public final BigDecimal storageTemperatureCelsiusMin;
        public final BigDecimal storageTemperatureCelsiusMax;
        public final boolean isActive;

        public DeviceConsumableRecord(
                String id,
                String productId,
                String tenantId,
                String deviceCategory,
                String brandName,
                String modelNumber,
                String needleGauge,
                BigDecimal needleLengthMm,
                String meterCompatibility,
                int stripCountPerPack,
                BigDecimal sampleVolumeMicroliter,
                int testTimeSeconds,
                boolean isSterile,
                boolean isSingleUse,
                String regulatoryDeviceClass,
                BigDecimal storageTemperatureCelsiusMin,
                BigDecimal storageTemperatureCelsiusMax,
                boolean isActive) {
            this.id = id;
            this.productId = productId;
            this.tenantId = tenantId;
            this.deviceCategory = deviceCategory;
            this.brandName = brandName;
            this.modelNumber = modelNumber != null ? modelNumber : "";
            this.needleGauge = needleGauge != null ? needleGauge : "NA";
            this.needleLengthMm = needleLengthMm != null ? needleLengthMm : BigDecimal.ZERO;
            this.meterCompatibility = meterCompatibility != null ? meterCompatibility : "";
            this.stripCountPerPack = stripCountPerPack;
            this.sampleVolumeMicroliter = sampleVolumeMicroliter != null ? sampleVolumeMicroliter : BigDecimal.ZERO;
            this.testTimeSeconds = testTimeSeconds;
            this.isSterile = isSterile;
            this.isSingleUse = isSingleUse;
            this.regulatoryDeviceClass = regulatoryDeviceClass != null ? regulatoryDeviceClass : "CLASS_A_LOW_RISK";
            this.storageTemperatureCelsiusMin = storageTemperatureCelsiusMin != null ? storageTemperatureCelsiusMin : new BigDecimal("2.00");
            this.storageTemperatureCelsiusMax = storageTemperatureCelsiusMax != null ? storageTemperatureCelsiusMax : new BigDecimal("30.00");
            this.isActive = isActive;
        }
    }

    public static final class ValidationResult {
        public final boolean isValid;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public ValidationResult(boolean isValid, String errorCode, String messageEn, String messageBn) {
            this.isValid = isValid;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null, "Validation passed", "যাচাই সফল হয়েছে");
        }

        public static ValidationResult error(String code, String msgEn, String msgBn) {
            return new ValidationResult(false, code, msgEn, msgBn);
        }
    }

    public static final List<String> FORBIDDEN_MEDICINE_FIELDS = List.of(
            "dosage_form",
            "is_antibiotic",
            "pom_required",
            "prescription_required",
            "narcotic_schedule",
            "active_ingredient",
            "active_ingredient_concentration"
    );

    public static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "NEEDLE_OR_SYRINGE",
            "DIAGNOSTIC_TEST_STRIP",
            "MONITORING_DEVICE",
            "SURGICAL_CONSUMABLE",
            "RESPIRATORY_DEVICE",
            "GENERAL_CONSUMABLE"
    );

    public static final Set<String> ALLOWED_GAUGES = Set.of(
            "18G", "20G", "21G", "22G", "23G", "25G", "26G", "27G", "29G", "30G", "31G", "32G", "NA"
    );

    public ValidationResult validateDraftPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return ValidationResult.error("ERR_EMPTY_PAYLOAD", "Payload cannot be empty", "পে-লোড খালি হতে পারে না");
        }

        // 1. Medicine-only forbidden fields check
        for (String field : FORBIDDEN_MEDICINE_FIELDS) {
            if (payload.containsKey(field) && payload.get(field) != null) {
                return ValidationResult.error(
                        "ERR_MEDICINE_FIELD_REJECTED",
                        "Medicine-only field '" + field + "' is illegal for device & consumable catalog.",
                        "মেডিকেল ডিভাইস বা কনজিউমেবলের জন্য ওষুধ-নির্দিষ্ট ফিল্ড '" + field + "' নিষিদ্ধ।"
                );
            }
        }

        // 2. Category check
        String category = (String) payload.get("device_category");
        if (category == null || !ALLOWED_CATEGORIES.contains(category)) {
            return ValidationResult.error(
                    "ERR_INVALID_DEVICE_CATEGORY",
                    "Invalid device category: " + category,
                    "অবৈধ ডিভাইস ক্যাটাগরি।"
            );
        }

        // 3. Brand name check
        String brandName = (String) payload.get("brand_name");
        if (brandName == null || brandName.trim().length() < 2) {
            return ValidationResult.error(
                    "ERR_INVALID_BRAND_NAME",
                    "brand_name is mandatory and must be at least 2 characters.",
                    "ব্র্যান্ড নাম আবশ্যক এবং অন্তত ২ অক্ষরের হতে হবে।"
            );
        }

        // 4. Needles validation
        if ("NEEDLE_OR_SYRINGE".equals(category)) {
            String gauge = (String) payload.get("needle_gauge");
            if (gauge == null || "NA".equals(gauge) || !ALLOWED_GAUGES.contains(gauge)) {
                return ValidationResult.error(
                        "ERR_INVALID_NEEDLE_GAUGE",
                        "Needles and syringes must specify a valid gauge (e.g., 21G, 25G, 31G).",
                        "সূঁচ বা সিরিঞ্জের জন্য সঠিক গজ উল্লেখ করা আবশ্যক।"
                );
            }

            Object lenObj = payload.get("needle_length_mm");
            if (lenObj == null) {
                return ValidationResult.error(
                        "ERR_INVALID_NEEDLE_LENGTH",
                        "needle_length_mm is required for needles.",
                        "সূঁচের দৈর্ঘ্য আবশ্যক।"
                );
            }
            try {
                BigDecimal len = new BigDecimal(lenObj.toString());
                if (len.compareTo(BigDecimal.ZERO) <= 0) {
                    return ValidationResult.error(
                            "ERR_INVALID_NEEDLE_LENGTH",
                            "needle_length_mm must be greater than 0.",
                            "সূঁচের দৈর্ঘ্য শূন্যের চেয়ে বড় হতে হবে।"
                    );
                }
            } catch (Exception e) {
                return ValidationResult.error(
                        "ERR_INVALID_NEEDLE_LENGTH",
                        "needle_length_mm must be numeric.",
                        "সূঁচের দৈর্ঘ্য সংখ্যা হতে হবে।"
                );
            }
        }

        // 5. Diagnostic Test Strip validation
        if ("DIAGNOSTIC_TEST_STRIP".equals(category)) {
            String meter = (String) payload.get("meter_compatibility");
            if (meter == null || meter.trim().isEmpty()) {
                return ValidationResult.error(
                        "ERR_MISSING_METER_COMPATIBILITY",
                        "Diagnostic test strips must specify meter compatibility.",
                        "টেস্ট স্ট্রিপের জন্য প্রযোজ্য মিটারের নাম উল্লেখ আবশ্যক।"
                );
            }

            Object stripObj = payload.get("strip_count_per_pack");
            if (stripObj == null) {
                return ValidationResult.error(
                        "ERR_INVALID_STRIP_COUNT",
                        "strip_count_per_pack is required.",
                        "প্যাক প্রতি স্ট্রিপ সংখ্যা আবশ্যক।"
                );
            }
            try {
                int count = Integer.parseInt(stripObj.toString());
                if (count < 1) {
                    return ValidationResult.error(
                            "ERR_INVALID_STRIP_COUNT",
                            "strip_count_per_pack must be at least 1.",
                            "স্ট্রিপ সংখ্যা অন্তত ১ হতে হবে।"
                    );
                }
            } catch (Exception e) {
                return ValidationResult.error(
                        "ERR_INVALID_STRIP_COUNT",
                        "strip_count_per_pack must be integer.",
                        "স্ট্রিপ সংখ্যা পূর্ণসংখ্যা হতে হবে।"
                );
            }
        }

        // 6. Storage temperature validation
        Object minObj = payload.get("storage_temperature_celsius_min");
        Object maxObj = payload.get("storage_temperature_celsius_max");
        if (minObj != null && maxObj != null) {
            try {
                BigDecimal min = new BigDecimal(minObj.toString());
                BigDecimal max = new BigDecimal(maxObj.toString());
                if (min.compareTo(max) > 0) {
                    return ValidationResult.error(
                            "ERR_INVALID_TEMPERATURE_RANGE",
                            "storage_temperature_celsius_min cannot exceed max.",
                            "সর্বনিম্ন তাপমাত্রা সর্বোচ্চ তাপমাত্রার চেয়ে বেশি হতে পারে না।"
                    );
                }
            } catch (Exception e) {
                return ValidationResult.error(
                        "ERR_INVALID_TEMPERATURE",
                        "Temperatures must be numeric.",
                        "তাপমাত্রা সংখ্যা হতে হবে।"
                );
            }
        }

        return ValidationResult.valid();
    }
}
