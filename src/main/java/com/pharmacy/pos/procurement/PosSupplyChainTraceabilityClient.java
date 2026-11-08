package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * PosSupplyChainTraceabilityClient
 * Architecture Decision: ADR-0197 (P197).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 *
 * Desktop JavaFX client governing Supply-Chain Traceability:
 * - Traces each medicine lot through supplier, PO, shipment, GRN, storage, and sales.
 * - Computes physical stock reconciliation and cold-chain integrity flags.
 * - Generates audited traceability records with cryptographic verification hashes.
 */
public class PosSupplyChainTraceabilityClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, TraceabilityRecordDto> recordStore;

    private static final Set<String> ALLOWED_TRACE_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "PROCUREMENT_OFFICER", "INVENTORY_CLERK", "AUDITOR"
    );

    public PosSupplyChainTraceabilityClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, TraceabilityRecordDto> recordStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.recordStore = recordStore != null ? recordStore : new HashMap<>();
    }

    public static final class ProductTraceDto {
        public final String productId;
        public final String brandName;
        public final String genericName;
        public final String strength;
        public final String dosageForm;
        public final String manufacturer;
        public final boolean requiresColdChain;

        public ProductTraceDto(String productId, String brandName, String genericName, String strength,
                               String dosageForm, String manufacturer, boolean requiresColdChain) {
            this.productId = productId;
            this.brandName = brandName != null ? brandName : "";
            this.genericName = genericName != null ? genericName : "";
            this.strength = strength != null ? strength : "";
            this.dosageForm = dosageForm != null ? dosageForm : "";
            this.manufacturer = manufacturer != null ? manufacturer : "";
            this.requiresColdChain = requiresColdChain;
        }
    }

    public static final class BatchTraceDto {
        public final String batchId;
        public final String batchNumber;
        public final String lotReference;
        public final String expiryDate;
        public final BigDecimal costPerUnit;
        public final String status;
        public final String storageCondition;
        public final boolean isQuarantined;
        public final String recallNotice;
        public final int availableQuantity;

        public BatchTraceDto(String batchId, String batchNumber, String lotReference, String expiryDate,
                             BigDecimal costPerUnit, String status, String storageCondition,
                             boolean isQuarantined, String recallNotice, int availableQuantity) {
            this.batchId = batchId;
            this.batchNumber = batchNumber;
            this.lotReference = lotReference != null ? lotReference : "";
            this.expiryDate = expiryDate;
            this.costPerUnit = costPerUnit != null ? costPerUnit : BigDecimal.ZERO;
            this.status = status != null ? status : "ACTIVE";
            this.storageCondition = storageCondition != null ? storageCondition : "";
            this.isQuarantined = isQuarantined;
            this.recallNotice = recallNotice;
            this.availableQuantity = availableQuantity;
        }
    }

    public static final class DownstreamSaleDto {
        public final String allocationId;
        public final String invoiceNumber;
        public final String dispensedAt;
        public final int quantityDispensed;
        public final int quantityReturned;
        public final BigDecimal unitSellingPrice;
        public final boolean isReturned;

        public DownstreamSaleDto(String allocationId, String invoiceNumber, String dispensedAt,
                                 int quantityDispensed, int quantityReturned,
                                 BigDecimal unitSellingPrice, boolean isReturned) {
            this.allocationId = allocationId;
            this.invoiceNumber = invoiceNumber != null ? invoiceNumber : "";
            this.dispensedAt = dispensedAt;
            this.quantityDispensed = quantityDispensed;
            this.quantityReturned = quantityReturned;
            this.unitSellingPrice = unitSellingPrice != null ? unitSellingPrice : BigDecimal.ZERO;
            this.isReturned = isReturned;
        }
    }

    public static final class ReconciliationDto {
        public final int totalReceivedQuantity;
        public final int totalDispensedQuantity;
        public final int totalReturnedQuantity;
        public final int currentStockOnHand;
        public final int discrepancyQuantity;
        public final boolean coldChainBreachedInTransit;

        public ReconciliationDto(int totalReceivedQuantity, int totalDispensedQuantity, int totalReturnedQuantity,
                                 int currentStockOnHand, int discrepancyQuantity, boolean coldChainBreachedInTransit) {
            this.totalReceivedQuantity = totalReceivedQuantity;
            this.totalDispensedQuantity = totalDispensedQuantity;
            this.totalReturnedQuantity = totalReturnedQuantity;
            this.currentStockOnHand = currentStockOnHand;
            this.discrepancyQuantity = discrepancyQuantity;
            this.coldChainBreachedInTransit = coldChainBreachedInTransit;
        }
    }

    public static final class BatchLineageDto {
        public final ProductTraceDto product;
        public final BatchTraceDto batch;
        public final List<String> poNumbers;
        public final List<String> grnNumbers;
        public final List<String> shipmentConsignments;
        public final List<DownstreamSaleDto> sales;
        public final ReconciliationDto reconciliation;

        public BatchLineageDto(ProductTraceDto product, BatchTraceDto batch, List<String> poNumbers,
                               List<String> grnNumbers, List<String> shipmentConsignments,
                               List<DownstreamSaleDto> sales, ReconciliationDto reconciliation) {
            this.product = product;
            this.batch = batch;
            this.poNumbers = poNumbers != null ? poNumbers : new ArrayList<>();
            this.grnNumbers = grnNumbers != null ? grnNumbers : new ArrayList<>();
            this.shipmentConsignments = shipmentConsignments != null ? shipmentConsignments : new ArrayList<>();
            this.sales = sales != null ? sales : new ArrayList<>();
            this.reconciliation = reconciliation;
        }
    }

    public static final class TraceabilityRecordDto {
        public final String traceId;
        public final String traceNumber;
        public final String tenantId;
        public final String branchId;
        public final String productId;
        public final String productName;
        public final String batchNumber;
        public final String lotReference;
        public final String auditPurpose;
        public final ReconciliationDto reconciliation;
        public final String immutableVerificationHash;
        public final String generatedBy;
        public final Instant generatedAt;
        public final String notes;
        public final BatchLineageDto lineage;

        public TraceabilityRecordDto(
                String traceId, String traceNumber, String tenantId, String branchId,
                String productId, String productName, String batchNumber, String lotReference,
                String auditPurpose, ReconciliationDto reconciliation, String immutableVerificationHash,
                String generatedBy, Instant generatedAt, String notes, BatchLineageDto lineage) {
            this.traceId = traceId;
            this.traceNumber = traceNumber;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.productId = productId;
            this.productName = productName != null ? productName : "";
            this.batchNumber = batchNumber;
            this.lotReference = lotReference != null ? lotReference : "";
            this.auditPurpose = auditPurpose != null ? auditPurpose : "INTERNAL_AUDIT";
            this.reconciliation = reconciliation;
            this.immutableVerificationHash = immutableVerificationHash;
            this.generatedBy = generatedBy;
            this.generatedAt = generatedAt != null ? generatedAt : Instant.now();
            this.notes = notes != null ? notes : "";
            this.lineage = lineage;
        }
    }

    private void assertTraceRole() {
        if ("CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_TRACE_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Staff role " + userRole + " cannot access supply chain traceability.");
        }
    }

    public static String computeVerificationHash(String traceId, String tenantId, String batchNumber,
                                                 int totalReceived, int totalDispensed, int currentStock,
                                                 boolean coldChainBreached, String auditPurpose, Instant generatedAt) {
        try {
            String raw = traceId + "|" + tenantId + "|" + batchNumber + "|" + totalReceived + "|" +
                    totalDispensed + "|" + currentStock + "|" + coldChainBreached + "|" + auditPurpose + "|" +
                    (generatedAt != null ? generatedAt.toString() : "");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public TraceabilityRecordDto generateTraceabilityRecord(
            BatchLineageDto lineage,
            String auditPurpose,
            String notes) {
        assertTraceRole();

        if (lineage == null || lineage.batch == null) {
            throw new IllegalArgumentException("ERR_BATCH_REQUIRED: Valid batch lineage required.");
        }

        Instant now = Instant.now();
        String trcId = "trc-" + UUID.randomUUID().toString().substring(0, 8);
        String trcNum = "TRC-" + System.currentTimeMillis();

        ReconciliationDto rec = lineage.reconciliation;
        String hash = computeVerificationHash(
                trcId, tenantId, lineage.batch.batchNumber,
                rec.totalReceivedQuantity, rec.totalDispensedQuantity, rec.currentStockOnHand,
                rec.coldChainBreachedInTransit, auditPurpose, now
        );

        TraceabilityRecordDto record = new TraceabilityRecordDto(
                trcId,
                trcNum,
                tenantId,
                branchId,
                lineage.product.productId,
                lineage.product.brandName,
                lineage.batch.batchNumber,
                lineage.batch.lotReference,
                auditPurpose != null ? auditPurpose : "INTERNAL_AUDIT",
                rec,
                hash,
                userId,
                now,
                notes,
                lineage
        );

        recordStore.put(trcId, record);
        return record;
    }

    public TraceabilityRecordDto getTraceabilityRecord(String traceId) {
        TraceabilityRecordDto record = recordStore.get(traceId);
        if (record == null) {
            throw new NoSuchElementException("ERR_TRACE_NOT_FOUND: Trace record " + traceId + " not found.");
        }
        return record;
    }

    public List<TraceabilityRecordDto> listTraceabilityRecords(String auditPurposeFilter) {
        List<TraceabilityRecordDto> results = new ArrayList<>();
        for (TraceabilityRecordDto r : recordStore.values()) {
            if (auditPurposeFilter != null && !auditPurposeFilter.isEmpty() && !auditPurposeFilter.equalsIgnoreCase(r.auditPurpose)) {
                continue;
            }
            results.add(r);
        }
        return results;
    }
}
