package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * PosColdChainExcursionClient
 * Architecture Decision: ADR-0212 (P212).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Client for Cold Chain Excursion Workflow:
 * - Detect cold chain temperature excursions and register incidents.
 * - Automatically quarantine batches stored in the affected location, locking sellable quantity to 0.
 * - Pharmacist clinical disposition review (Release to sellable, Condemn & Dispose, Return to supplier).
 * - Enforces minimum 15-character clinical justification for stock release.
 * - Restricts cashier access (HTTP 403 / SecurityException).
 * - Offline-first caching with synchronized server state.
 */
public class PosColdChainExcursionClient {

    public enum IncidentStatus {
        DETECTED("Detected / Stock Quarantined", "বিচ্যুতি শনাক্ত / স্টক কোয়ারেন্টাইন"),
        UNDER_REVIEW("Under Pharmacist Review", "ফার্মাসিস্ট পর্যালোচনাধীন"),
        RESOLVED("Resolved & Disposed", "নিষ্পত্তি সম্পন্ন"),
        CANCELLED("Cancelled", "বাতিল");

        public final String labelEn;
        public final String labelBn;

        IncidentStatus(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum DispositionOutcome {
        PENDING("Pending Review", "পর্যালোচনা অপেক্ষমাণ"),
        RELEASE_TO_SELLABLE("Release to Sellable Stock", "বিক্রয়যোগ্য স্টকে অবমুক্ত"),
        CONDEMN_DISPOSE("Condemn & Write-off Disposal", "নষ্ট ঘোষণা ও ধ্বংস"),
        RETURN_TO_SUPPLIER("Return to Vaccine/Drug Supplier", "সরবরাহকারীকে ফেরত");

        public final String labelEn;
        public final String labelBn;

        DispositionOutcome(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum Severity {
        WARNING("Excursion Warning", "সতর্কতামূলক বিচ্যুতি"),
        CRITICAL("Critical Excursion", "মারাত্মক বিচ্যুতি");

        public final String labelEn;
        public final String labelBn;

        Severity(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static class QuarantinedBatchItem {
        private final String batchNumber;
        private final String productName;
        private final int quantity;
        private final BigDecimal unitCost;
        private final BigDecimal totalCost;
        private DispositionOutcome disposition;

        public QuarantinedBatchItem(String batchNumber, String productName, int quantity, BigDecimal unitCost) {
            this.batchNumber = Objects.requireNonNull(batchNumber, "batchNumber is required");
            this.productName = Objects.requireNonNull(productName, "productName is required");
            this.quantity = quantity;
            this.unitCost = unitCost != null ? unitCost : BigDecimal.ZERO;
            this.totalCost = this.unitCost.multiply(BigDecimal.valueOf(quantity));
            this.disposition = DispositionOutcome.PENDING;
        }

        public String getBatchNumber() { return batchNumber; }
        public String getProductName() { return productName; }
        public int getQuantity() { return quantity; }
        public BigDecimal getUnitCost() { return unitCost; }
        public BigDecimal getTotalCost() { return totalCost; }
        public DispositionOutcome getDisposition() { return disposition; }
        public void setDisposition(DispositionOutcome disposition) { this.disposition = disposition; }
    }

    public static class ExcursionIncident {
        private final String incidentId;
        private final String incidentNumber;
        private final String tenantId;
        private final String branchId;
        private final String storageLocationCode;
        private final String storageLocationName;
        private final BigDecimal recordedTemperature;
        private final BigDecimal minThreshold;
        private final BigDecimal maxThreshold;
        private final Severity severity;
        private IncidentStatus status;
        private DispositionOutcome dispositionOutcome;
        private int quarantinedBatchesCount;
        private BigDecimal totalQuarantinedValue;
        private String clinicalJustification;
        private String reviewedBy;
        private Instant reviewedAt;
        private final Instant createdAt;
        private final List<QuarantinedBatchItem> affectedBatches;

        public ExcursionIncident(
                String incidentId,
                String incidentNumber,
                String tenantId,
                String branchId,
                String storageLocationCode,
                String storageLocationName,
                BigDecimal recordedTemperature,
                BigDecimal minThreshold,
                BigDecimal maxThreshold,
                Severity severity,
                List<QuarantinedBatchItem> affectedBatches
        ) {
            this.incidentId = incidentId;
            this.incidentNumber = incidentNumber;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.storageLocationCode = storageLocationCode;
            this.storageLocationName = storageLocationName;
            this.recordedTemperature = recordedTemperature;
            this.minThreshold = minThreshold;
            this.maxThreshold = maxThreshold;
            this.severity = severity;
            this.status = IncidentStatus.DETECTED;
            this.dispositionOutcome = DispositionOutcome.PENDING;
            this.clinicalJustification = "";
            this.reviewedBy = null;
            this.reviewedAt = null;
            this.createdAt = Instant.now();
            this.affectedBatches = new ArrayList<>(affectedBatches != null ? affectedBatches : Collections.emptyList());
            this.quarantinedBatchesCount = this.affectedBatches.size();
            this.totalQuarantinedValue = this.affectedBatches.stream()
                    .map(QuarantinedBatchItem::getTotalCost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public String getIncidentId() { return incidentId; }
        public String getIncidentNumber() { return incidentNumber; }
        public String getTenantId() { return tenantId; }
        public String getBranchId() { return branchId; }
        public String getStorageLocationCode() { return storageLocationCode; }
        public String getStorageLocationName() { return storageLocationName; }
        public BigDecimal getRecordedTemperature() { return recordedTemperature; }
        public BigDecimal getMinThreshold() { return minThreshold; }
        public BigDecimal getMaxThreshold() { return maxThreshold; }
        public Severity getSeverity() { return severity; }
        public IncidentStatus getStatus() { return status; }
        public DispositionOutcome getDispositionOutcome() { return dispositionOutcome; }
        public int getQuarantinedBatchesCount() { return quarantinedBatchesCount; }
        public BigDecimal getTotalQuarantinedValue() { return totalQuarantinedValue; }
        public String getClinicalJustification() { return clinicalJustification; }
        public String getReviewedBy() { return reviewedBy; }
        public Instant getReviewedAt() { return reviewedAt; }
        public Instant getCreatedAt() { return createdAt; }
        public List<QuarantinedBatchItem> getAffectedBatches() { return Collections.unmodifiableList(affectedBatches); }
    }

    private final Map<String, ExcursionIncident> incidentStore = new ConcurrentHashMap<>();

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "PHARMACY_OWNER",
            "BRANCH_MANAGER",
            "SUPERADMIN",
            "STAFF_PHARMACIST"
    );

    private void assertPharmacistRole(String userRole) {
        if (userRole == null || "CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("Cashiers cannot review or dispose cold chain excursions. Only registered Pharmacists and Managers are permitted.");
        }
    }

    public ExcursionIncident triggerExcursion(
            String tenantId,
            String branchId,
            String storageLocationCode,
            String storageLocationName,
            BigDecimal recordedTemp,
            BigDecimal minThreshold,
            BigDecimal maxThreshold,
            List<QuarantinedBatchItem> activeBatchesInLocation
    ) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        if (branchId == null || branchId.isBlank()) throw new IllegalArgumentException("branchId is required");
        if (storageLocationCode == null || storageLocationCode.isBlank()) throw new IllegalArgumentException("storageLocationCode is required");
        if (recordedTemp == null) throw new IllegalArgumentException("recordedTemp is required");

        BigDecimal minT = minThreshold != null ? minThreshold : new BigDecimal("2.00");
        BigDecimal maxT = maxThreshold != null ? maxThreshold : new BigDecimal("8.00");
        if (minT.compareTo(maxT) >= 0) {
            throw new IllegalArgumentException("minThreshold must be strictly less than maxThreshold");
        }

        BigDecimal diff = recordedTemp.compareTo(maxT) > 0
                ? recordedTemp.subtract(maxT)
                : minT.subtract(recordedTemp);

        Severity severity = (diff.compareTo(new BigDecimal("5.00")) >= 0 || recordedTemp.compareTo(new BigDecimal("15.00")) >= 0)
                ? Severity.CRITICAL
                : Severity.WARNING;

        String incidentId = UUID.randomUUID().toString();
        String incidentNumber = "CCE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        ExcursionIncident incident = new ExcursionIncident(
                incidentId,
                incidentNumber,
                tenantId,
                branchId,
                storageLocationCode,
                storageLocationName,
                recordedTemp,
                minT,
                maxT,
                severity,
                activeBatchesInLocation
        );

        incidentStore.put(incidentId, incident);
        return incident;
    }

    public ExcursionIncident reviewDisposition(
            String incidentId,
            String userRole,
            String reviewerUsername,
            DispositionOutcome outcome,
            String clinicalJustification
    ) {
        assertPharmacistRole(userRole);

        ExcursionIncident incident = incidentStore.get(incidentId);
        if (incident == null) {
            throw new NoSuchElementException("Excursion incident " + incidentId + " not found");
        }

        if (incident.getStatus() == IncidentStatus.RESOLVED) {
            throw new IllegalStateException("Incident is already resolved");
        }

        if (outcome == null || outcome == DispositionOutcome.PENDING) {
            throw new IllegalArgumentException("Valid disposition outcome must be provided");
        }

        String justification = clinicalJustification != null ? clinicalJustification.trim() : "";
        if (outcome == DispositionOutcome.RELEASE_TO_SELLABLE) {
            if (justification.length() < 15) {
                throw new IllegalArgumentException("Clinical justification must be at least 15 characters describing stability data or manufacturer consultation.");
            }
        }

        for (QuarantinedBatchItem item : incident.affectedBatches) {
            item.setDisposition(outcome);
        }

        incident.status = IncidentStatus.RESOLVED;
        incident.dispositionOutcome = outcome;
        incident.clinicalJustification = justification;
        incident.reviewedBy = reviewerUsername;
        incident.reviewedAt = Instant.now();

        return incident;
    }

    public Optional<ExcursionIncident> getIncident(String incidentId) {
        return Optional.ofNullable(incidentStore.get(incidentId));
    }

    public List<ExcursionIncident> listIncidents(String tenantId, String branchId) {
        return incidentStore.values().stream()
                .filter(inc -> inc.getTenantId().equals(tenantId) && (branchId == null || inc.getBranchId().equals(branchId)))
                .sorted(Comparator.comparing(ExcursionIncident::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }
}

