package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * PosTemperatureLogClient
 * Architecture Decision: ADR-0211 (P211).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Client for Cold-Chain Storage Temperature & Humidity Monitoring:
 * - Device registry (IoT wireless, USB data loggers, calibrated digital thermometers).
 * - Manual daily temperature logging sheets.
 * - USB logger file dump bulk batch import.
 * - Real-time excursion classification (Normal, Warning, Critical Excursion).
 * - Cryptographic SHA-256 audit hash for regulatory inspection integrity.
 */
public class PosTemperatureLogClient {

    public enum SensorDeviceType {
        IOT_WIRELESS("IoT Wireless / Continuous Sensor", "আইওটি ওয়্যারলেস সেন্সর"),
        USB_DATA_LOGGER("USB Temperature Data Logger", "ইউএসবি টেম্পারেচার ডেটা লগার"),
        BLUETOOTH_BEACON("Bluetooth BLE Cold-Chain Logger", "ব্লুটুথ কোল্ড-চেইন লগার"),
        MANUAL_DIGITAL("Manual Calibrated Digital Thermometer", "ম্যানুয়াল ডিজিটাল থার্মোমিটার"),
        ANALOG_HYGROMETER("Manual Dial Hygrometer", "ডায়াল হাইগ্রোমিটার / থার্মোমিটার");

        public final String labelEn;
        public final String labelBn;

        SensorDeviceType(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum SourceType {
        MANUAL_ENTRY("Manual Staff Entry", "ম্যানুয়াল এন্ট্রি"),
        SENSOR_IMPORT("Automated / Sensor Data Import", "সেন্সর ডেটা ইম্পোর্ট"),
        IOT_TELEMETRY("Live IoT Stream Telemetry", "লাইভ আইওটি স্ট্রীম"),
        USB_LOGGER_BATCH("USB Logger File Batch Import", "ইউএসবি লগার ফাইল ব্যাচ ইম্পোর্ট");

        public final String labelEn;
        public final String labelBn;

        SourceType(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum ExcursionSeverity {
        NONE("Normal / Safe Range", "স্বাভাবিক ও নিরাপদ"),
        WARNING("Borderline Warning", "সতর্কতামূলক বিচ্যুতি"),
        CRITICAL("Critical Excursion Out of Safe Range", "মারাত্মক তাপমাত্রা বিচ্যুতি");

        public final String labelEn;
        public final String labelBn;

        ExcursionSeverity(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static final class DeviceDto {
        public final String deviceId;
        public final String branchId;
        public final String storageLocationId;
        public final String deviceName;
        public final SensorDeviceType deviceType;
        public final String serialNumber;
        public boolean isActive;

        public DeviceDto(
                String deviceId,
                String branchId,
                String storageLocationId,
                String deviceName,
                SensorDeviceType deviceType,
                String serialNumber
        ) {
            this.deviceId = deviceId != null ? deviceId : UUID.randomUUID().toString();
            this.branchId = branchId;
            this.storageLocationId = storageLocationId;
            this.deviceName = deviceName;
            this.deviceType = deviceType != null ? deviceType : SensorDeviceType.MANUAL_DIGITAL;
            this.serialNumber = serialNumber != null ? serialNumber : "";
            this.isActive = true;
        }
    }

    public static final class TemperatureReadingDto {
        public final String logId;
        public final String branchId;
        public final String storageLocationId;
        public final String deviceId;
        public final SourceType sourceType;
        public final Instant recordedAt;
        public final BigDecimal temperatureCelsius;
        public final BigDecimal humidityPercentage;
        public final BigDecimal minThresholdCelsius;
        public final BigDecimal maxThresholdCelsius;
        public final boolean isExcursion;
        public final ExcursionSeverity excursionSeverity;
        public final String notes;
        public final String recordedBy;
        public final String auditHash;

        public TemperatureReadingDto(
                String logId,
                String branchId,
                String storageLocationId,
                String deviceId,
                SourceType sourceType,
                Instant recordedAt,
                BigDecimal temperatureCelsius,
                BigDecimal humidityPercentage,
                BigDecimal minThresholdCelsius,
                BigDecimal maxThresholdCelsius,
                String notes,
                String recordedBy
        ) {
            this.logId = logId != null ? logId : UUID.randomUUID().toString();
            this.branchId = branchId;
            this.storageLocationId = storageLocationId;
            this.deviceId = deviceId;
            this.sourceType = sourceType != null ? sourceType : SourceType.MANUAL_ENTRY;
            this.recordedAt = recordedAt != null ? recordedAt : Instant.now();
            this.temperatureCelsius = temperatureCelsius;
            this.humidityPercentage = humidityPercentage;
            this.minThresholdCelsius = minThresholdCelsius != null ? minThresholdCelsius : new BigDecimal("2.00");
            this.maxThresholdCelsius = maxThresholdCelsius != null ? maxThresholdCelsius : new BigDecimal("8.00");
            this.notes = notes != null ? notes : "";
            this.recordedBy = recordedBy;

            // Excursion classification
            if (temperatureCelsius.compareTo(this.minThresholdCelsius) < 0 || temperatureCelsius.compareTo(this.maxThresholdCelsius) > 0) {
                this.isExcursion = true;
                BigDecimal diff = temperatureCelsius.compareTo(this.maxThresholdCelsius) > 0
                        ? temperatureCelsius.subtract(this.maxThresholdCelsius)
                        : this.minThresholdCelsius.subtract(temperatureCelsius);

                if (diff.compareTo(new BigDecimal("5.00")) >= 0 || temperatureCelsius.compareTo(new BigDecimal("15.00")) >= 0) {
                    this.excursionSeverity = ExcursionSeverity.CRITICAL;
                } else {
                    this.excursionSeverity = ExcursionSeverity.WARNING;
                }
            } else {
                this.isExcursion = false;
                this.excursionSeverity = ExcursionSeverity.NONE;
            }

            this.auditHash = computeAuditHash();
        }

        private String computeAuditHash() {
            try {
                String raw = logId + "|" + branchId + "|" + storageLocationId + "|" + recordedAt.toString() + "|"
                        + temperatureCelsius.toPlainString() + "|" + minThresholdCelsius.toPlainString() + "|"
                        + maxThresholdCelsius.toPlainString() + "|" + isExcursion;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : hash) {
                    hex.append(String.format("%02x", b));
                }
                return hex.toString();
            } catch (Exception e) {
                return "HASH_FALLBACK_" + logId;
            }
        }
    }

    private final Map<String, DeviceDto> devices = new ConcurrentHashMap<>();
    private final List<TemperatureReadingDto> readings = Collections.synchronizedList(new ArrayList<>());

    private void checkAuthorizedRole(String role) {
        if (role == null || (!role.equalsIgnoreCase("PHARMACIST") &&
                !role.equalsIgnoreCase("BRANCH_MANAGER") &&
                !role.equalsIgnoreCase("PHARMACY_OWNER") &&
                !role.equalsIgnoreCase("OWNER") &&
                !role.equalsIgnoreCase("ADMIN"))) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Only Pharmacists or Managers can log or import temperature telemetry.");
        }
    }

    public DeviceDto registerDevice(
            String branchId,
            String storageLocationId,
            String deviceName,
            SensorDeviceType deviceType,
            String serialNumber,
            String staffRole
    ) {
        checkAuthorizedRole(staffRole);
        DeviceDto device = new DeviceDto(
                UUID.randomUUID().toString(),
                branchId,
                storageLocationId,
                deviceName,
                deviceType,
                serialNumber
        );
        devices.put(device.deviceId, device);
        return device;
    }

    public TemperatureReadingDto recordReading(
            String branchId,
            String storageLocationId,
            BigDecimal temperatureCelsius,
            BigDecimal humidityPercentage,
            BigDecimal minThreshold,
            BigDecimal maxThreshold,
            String deviceId,
            SourceType sourceType,
            String notes,
            String recordedBy,
            String staffRole
    ) {
        checkAuthorizedRole(staffRole);

        if (minThreshold != null && maxThreshold != null && minThreshold.compareTo(maxThreshold) >= 0) {
            throw new IllegalArgumentException("ERR_INVALID_THRESHOLDS: Min threshold must be strictly less than max threshold.");
        }

        TemperatureReadingDto reading = new TemperatureReadingDto(
                UUID.randomUUID().toString(),
                branchId,
                storageLocationId,
                deviceId,
                sourceType,
                Instant.now(),
                temperatureCelsius,
                humidityPercentage,
                minThreshold,
                maxThreshold,
                notes,
                recordedBy
        );
        readings.add(reading);
        return reading;
    }

    public List<TemperatureReadingDto> importBatch(
            String branchId,
            String storageLocationId,
            String deviceId,
            List<BigDecimal> temperatures,
            BigDecimal minThreshold,
            BigDecimal maxThreshold,
            String batchNotes,
            String recordedBy,
            String staffRole
    ) {
        checkAuthorizedRole(staffRole);
        if (temperatures == null || temperatures.isEmpty()) {
            throw new IllegalArgumentException("ERR_EMPTY_BATCH: At least one temperature reading is required.");
        }

        List<TemperatureReadingDto> result = new ArrayList<>();
        for (BigDecimal temp : temperatures) {
            TemperatureReadingDto reading = new TemperatureReadingDto(
                    UUID.randomUUID().toString(),
                    branchId,
                    storageLocationId,
                    deviceId,
                    SourceType.USB_LOGGER_BATCH,
                    Instant.now(),
                    temp,
                    null,
                    minThreshold,
                    maxThreshold,
                    batchNotes,
                    recordedBy
            );
            readings.add(reading);
            result.add(reading);
        }
        return result;
    }

    public List<TemperatureReadingDto> getReadings(String storageLocationId) {
        return readings.stream()
                .filter(r -> storageLocationId == null || r.storageLocationId.equals(storageLocationId))
                .collect(Collectors.toList());
    }

    public List<TemperatureReadingDto> getExcursions(String storageLocationId) {
        return readings.stream()
                .filter(r -> r.isExcursion && (storageLocationId == null || r.storageLocationId.equals(storageLocationId)))
                .collect(Collectors.toList());
    }
}

