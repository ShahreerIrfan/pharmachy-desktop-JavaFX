package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * PosSupplyChainAlertClient
 * Architecture Decision: ADR-0214 (P214).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Client for Supply Chain Stock Alerts:
 * - Real-time alerts for reorder, stockout, overstock, negative availability, and temperature risk.
 * - Deduplication engine ensures repeated triggers increment counter instead of spamming.
 * - Lifecycle state machine (Open -> Acknowledged -> Resolved / Dismissed).
 * - Enforces role-based access control (Cashier denied).
 * - Local in-memory caching and offline fallback for POS cashiers and inventory clerks.
 */
public class PosSupplyChainAlertClient {

    public enum AlertType {
        REORDER_REQUIRED("Reorder Level Reached", "রিঅর্ডার সীমা অতিক্রম"),
        STOCKOUT("Complete Stockout", "স্টকআউট"),
        OVERSTOCK("Overstock Exceeded Maximum", "অতিরিক্ত স্টক"),
        NEGATIVE_AVAILABILITY("Negative Availability Anomaly", "নেতিবাচক স্টক অসঙ্গতি"),
        TEMPERATURE_RISK("Cold Chain Temperature Risk", "কোল্ড চেইন তাপমাত্রা ঝুঁকি");

        public final String labelEn;
        public final String labelBn;

        AlertType(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum Severity {
        INFO("Informational", "তথ্যমূলক"),
        WARNING("Warning", "সতর্কতা"),
        CRITICAL("Critical", "গুরুতর"),
        EMERGENCY("Emergency", "জরুরি");

        public final String labelEn;
        public final String labelBn;

        Severity(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum AlertStatus {
        OPEN("Open / Active", "সক্রিয়"),
        ACKNOWLEDGED("Acknowledged", "স্বীকৃত"),
        RESOLVED("Resolved", "নিষ্পন্ন"),
        DISMISSED("Dismissed", "বাতিলকৃত");

        public final String labelEn;
        public final String labelBn;

        AlertStatus(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static class StockAlert {
        private final String alertId;
        private final String tenantId;
        private final String branchId;
        private final AlertType alertType;
        private final Severity severity;
        private AlertStatus status;
        private final String title;
        private final String message;
        private final String messageBn;
        private int currentQuantity;
        private Integer thresholdQuantity;
        private BigDecimal currentTemperature;
        private final String dedupFingerprint;
        private int occurredCount;
        private final Instant firstTriggeredAt;
        private Instant lastTriggeredAt;
        private String acknowledgedBy;
        private Instant acknowledgedAt;
        private String resolvedBy;
        private Instant resolvedAt;
        private String resolutionNotes;

        public StockAlert(
                String alertId,
                String tenantId,
                String branchId,
                AlertType alertType,
                Severity severity,
                String title,
                String message,
                String messageBn,
                int currentQuantity,
                Integer thresholdQuantity,
                BigDecimal currentTemperature,
                String dedupFingerprint
        ) {
            this.alertId = alertId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.alertType = alertType;
            this.severity = severity;
            this.status = AlertStatus.OPEN;
            this.title = title;
            this.message = message;
            this.messageBn = messageBn;
            this.currentQuantity = currentQuantity;
            this.thresholdQuantity = thresholdQuantity;
            this.currentTemperature = currentTemperature;
            this.dedupFingerprint = dedupFingerprint;
            this.occurredCount = 1;
            this.firstTriggeredAt = Instant.now();
            this.lastTriggeredAt = this.firstTriggeredAt;
            this.resolutionNotes = "";
        }

        public String getAlertId() { return alertId; }
        public String getTenantId() { return tenantId; }
        public String getBranchId() { return branchId; }
        public AlertType getAlertType() { return alertType; }
        public Severity getSeverity() { return severity; }
        public AlertStatus getStatus() { return status; }
        public String getTitle() { return title; }
        public String getMessage() { return message; }
        public String getMessageBn() { return messageBn; }
        public int getCurrentQuantity() { return currentQuantity; }
        public Integer getThresholdQuantity() { return thresholdQuantity; }
        public BigDecimal getCurrentTemperature() { return currentTemperature; }
        public String getDedupFingerprint() { return dedupFingerprint; }
        public int getOccurredCount() { return occurredCount; }
        public Instant getFirstTriggeredAt() { return firstTriggeredAt; }
        public Instant getLastTriggeredAt() { return lastTriggeredAt; }
        public String getAcknowledgedBy() { return acknowledgedBy; }
        public Instant getAcknowledgedAt() { return acknowledgedAt; }
        public String getResolvedBy() { return resolvedBy; }
        public Instant getResolvedAt() { return resolvedAt; }
        public String getResolutionNotes() { return resolutionNotes; }
    }

    private final Map<String, StockAlert> alertStore = new ConcurrentHashMap<>();

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "PHARMACY_OWNER",
            "BRANCH_MANAGER",
            "SUPERADMIN",
            "STAFF_PHARMACIST",
            "PROCUREMENT_OFFICER",
            "INVENTORY_CLERK"
    );

    private void assertInventoryRole(String userRole) {
        if (userRole == null || "CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("Cashiers cannot manage supply chain alerts. Access is restricted to Managers, Owners, and Pharmacists.");
        }
    }

    private String computeFingerprint(String tenantId, String branchId, String alertType, String entityId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String raw = tenantId + ":" + branchId + ":" + alertType + ":" + entityId;
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return tenantId + "-" + branchId + "-" + alertType + "-" + entityId;
        }
    }

    public StockAlert evaluateStockLevel(
            String tenantId,
            String branchId,
            String productId,
            String productName,
            int currentQty,
            int reorderLevel,
            Integer maxLevel
    ) {
        AlertType type;
        Severity sev;
        String title;
        String msg;
        String msgBn;

        if (currentQty < 0) {
            type = AlertType.NEGATIVE_AVAILABILITY;
            sev = Severity.CRITICAL;
            title = "Negative Stock: " + productName;
            msg = "Negative stock anomaly detected (" + currentQty + " units).";
            msgBn = "নেতিবাচক স্টক অসঙ্গতি শনাক্ত হয়েছে (" + currentQty + " ইউনিট)।";
        } else if (currentQty == 0) {
            type = AlertType.STOCKOUT;
            sev = Severity.CRITICAL;
            title = "Stockout: " + productName;
            msg = "Product " + productName + " is completely out of stock.";
            msgBn = productName + " সম্পূর্ণ স্টকআউট।";
        } else if (reorderLevel > 0 && currentQty <= reorderLevel) {
            type = AlertType.REORDER_REQUIRED;
            sev = Severity.WARNING;
            title = "Reorder Level Reached: " + productName;
            msg = "Current stock (" + currentQty + ") is at or below reorder threshold (" + reorderLevel + ").";
            msgBn = "স্টক রিঅর্ডার সীমার নিচে নেমেছে।";
        } else if (maxLevel != null && currentQty > maxLevel) {
            type = AlertType.OVERSTOCK;
            sev = Severity.INFO;
            title = "Overstock: " + productName;
            msg = "Current stock (" + currentQty + ") exceeds max target (" + maxLevel + ").";
            msgBn = "স্টক সর্বোচ্চ সীমার চেয়ে বেশি।";
        } else {
            // Auto-resolve any open stock alert for this product
            String fpStockout = computeFingerprint(tenantId, branchId, AlertType.STOCKOUT.name(), productId);
            String fpReorder = computeFingerprint(tenantId, branchId, AlertType.REORDER_REQUIRED.name(), productId);
            alertStore.values().stream()
                    .filter(a -> (a.getDedupFingerprint().equals(fpStockout) || a.getDedupFingerprint().equals(fpReorder))
                            && (a.getStatus() == AlertStatus.OPEN || a.getStatus() == AlertStatus.ACKNOWLEDGED))
                    .forEach(a -> {
                        a.status = AlertStatus.RESOLVED;
                        a.resolutionNotes = "Auto-resolved: Stock replenished into safe operating balance.";
                        a.resolvedAt = Instant.now();
                    });
            return null;
        }

        String fp = computeFingerprint(tenantId, branchId, type.name(), productId);

        // Deduplication
        for (StockAlert existing : alertStore.values()) {
            if (existing.getDedupFingerprint().equals(fp)
                    && (existing.getStatus() == AlertStatus.OPEN || existing.getStatus() == AlertStatus.ACKNOWLEDGED)) {
                existing.occurredCount++;
                existing.currentQuantity = currentQty;
                existing.thresholdQuantity = reorderLevel;
                existing.lastTriggeredAt = Instant.now();
                return existing;
            }
        }

        String id = UUID.randomUUID().toString();
        StockAlert newAlert = new StockAlert(
                id, tenantId, branchId, type, sev, title, msg, msgBn, currentQty, reorderLevel, null, fp
        );
        alertStore.put(id, newAlert);
        return newAlert;
    }

    public StockAlert evaluateTemperatureRisk(
            String tenantId,
            String branchId,
            String locationId,
            String locationName,
            BigDecimal recordedTemp,
            BigDecimal minTemp,
            BigDecimal maxTemp
    ) {
        if (recordedTemp.compareTo(minTemp) < 0 || recordedTemp.compareTo(maxTemp) > 0) {
            String fp = computeFingerprint(tenantId, branchId, AlertType.TEMPERATURE_RISK.name(), locationId);

            for (StockAlert existing : alertStore.values()) {
                if (existing.getDedupFingerprint().equals(fp)
                        && (existing.getStatus() == AlertStatus.OPEN || existing.getStatus() == AlertStatus.ACKNOWLEDGED)) {
                    existing.occurredCount++;
                    existing.currentTemperature = recordedTemp;
                    existing.lastTriggeredAt = Instant.now();
                    return existing;
                }
            }

            String id = UUID.randomUUID().toString();
            StockAlert newAlert = new StockAlert(
                    id, tenantId, branchId, AlertType.TEMPERATURE_RISK, Severity.CRITICAL,
                    "Temperature Excursion: " + locationName,
                    "Temperature breached safe limits: recorded " + recordedTemp + "°C",
                    "তাপমাত্রা সীমা লঙ্ঘিত হয়েছে: " + recordedTemp + "°সে",
                    0, null, recordedTemp, fp
            );
            alertStore.put(id, newAlert);
            return newAlert;
        }

        return null;
    }

    public StockAlert acknowledgeAlert(String userRole, String alertId, String username, String notes) {
        assertInventoryRole(userRole);
        StockAlert alert = alertStore.get(alertId);
        if (alert == null) throw new NoSuchElementException("Alert not found: " + alertId);

        alert.status = AlertStatus.ACKNOWLEDGED;
        alert.acknowledgedBy = username;
        alert.acknowledgedAt = Instant.now();
        if (notes != null && !notes.isBlank()) alert.resolutionNotes = notes;
        return alert;
    }

    public StockAlert resolveAlert(String userRole, String alertId, String username, String resolutionNotes) {
        assertInventoryRole(userRole);
        StockAlert alert = alertStore.get(alertId);
        if (alert == null) throw new NoSuchElementException("Alert not found: " + alertId);

        alert.status = AlertStatus.RESOLVED;
        alert.resolvedBy = username;
        alert.resolvedAt = Instant.now();
        alert.resolutionNotes = resolutionNotes != null ? resolutionNotes : "Resolved by staff";
        return alert;
    }

    public List<StockAlert> listActiveAlerts(String tenantId, String branchId) {
        return alertStore.values().stream()
                .filter(a -> a.getTenantId().equals(tenantId) && (branchId == null || a.getBranchId().equals(branchId)))
                .filter(a -> a.getStatus() == AlertStatus.OPEN || a.getStatus() == AlertStatus.ACKNOWLEDGED)
                .sorted(Comparator.comparing(StockAlert::getLastTriggeredAt).reversed())
                .collect(Collectors.toList());
    }
}
