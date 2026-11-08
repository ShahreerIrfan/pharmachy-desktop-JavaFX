package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosSupplyChainTraceabilityTest
 * Architecture Decision: ADR-0197 (P197).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 */
public class PosSupplyChainTraceabilityTest {

    private Map<String, PosSupplyChainTraceabilityClient.TraceabilityRecordDto> recordStore;
    private PosSupplyChainTraceabilityClient managerClient;
    private PosSupplyChainTraceabilityClient cashierClient;

    @BeforeEach
    void setUp() {
        recordStore = new HashMap<>();

        managerClient = new PosSupplyChainTraceabilityClient(
                "tenant-dhaka-001",
                "branch-dhanmondi-01",
                "BRANCH_MANAGER",
                "usr-manager-1",
                false,
                recordStore
        );

        cashierClient = new PosSupplyChainTraceabilityClient(
                "tenant-dhaka-001",
                "branch-dhanmondi-01",
                "CASHIER",
                "usr-cashier-1",
                false,
                recordStore
        );
    }

    private PosSupplyChainTraceabilityClient.BatchLineageDto createSampleLineage() {
        var prod = new PosSupplyChainTraceabilityClient.ProductTraceDto(
                "prod-humalog", "Humalog 100IU/ml", "Insulin Lispro", "100IU/ml", "INJECTION", "Square / Lilly", true
        );
        var batch = new PosSupplyChainTraceabilityClient.BatchTraceDto(
                "bat-001", "BATCH-INS-01", "LOT-SQ-01", "2027-10-31", new BigDecimal("450.00"), "ACTIVE", "Cold Storage 2-8°C", false, null, 85
        );
        var sales = List.of(
                new PosSupplyChainTraceabilityClient.DownstreamSaleDto("sla-1", "INV-001", "2026-11-06", 18, 3, new BigDecimal("520.00"), true)
        );
        var rec = new PosSupplyChainTraceabilityClient.ReconciliationDto(100, 18, 3, 85, 0, false);

        return new PosSupplyChainTraceabilityClient.BatchLineageDto(
                prod, batch, List.of("PO-2026-01"), List.of("GRN-2026-01"), List.of("SQ-REEFER-01"), sales, rec
        );
    }

    @Test
    void test01_generateTraceabilityRecordWithVerificationHash() {
        var lineage = createSampleLineage();
        var record = managerClient.generateTraceabilityRecord(lineage, "REGULATORY_INSPECTION", "DGDA annual audit");

        assertNotNull(record);
        assertTrue(record.traceNumber.startsWith("TRC-"));
        assertEquals("REGULATORY_INSPECTION", record.auditPurpose);
        assertEquals(100, record.reconciliation.totalReceivedQuantity);
        assertEquals(85, record.reconciliation.currentStockOnHand);
        assertEquals(0, record.reconciliation.discrepancyQuantity);
        assertNotNull(record.immutableVerificationHash);
        assertEquals(64, record.immutableVerificationHash.length()); // SHA-256 hash length
    }

    @Test
    void test02_hashDeterministicForSameParameters() {
        var hash1 = PosSupplyChainTraceabilityClient.computeVerificationHash(
                "trc-1", "tenant-1", "BATCH-01", 100, 20, 80, false, "INTERNAL_AUDIT", null
        );
        var hash2 = PosSupplyChainTraceabilityClient.computeVerificationHash(
                "trc-1", "tenant-1", "BATCH-01", 100, 20, 80, false, "INTERNAL_AUDIT", null
        );
        assertEquals(hash1, hash2);
    }

    @Test
    void test03_getTraceabilityRecord() {
        var lineage = createSampleLineage();
        var record = managerClient.generateTraceabilityRecord(lineage, "INTERNAL_AUDIT", "Routine check");

        var retrieved = managerClient.getTraceabilityRecord(record.traceId);
        assertEquals(record.traceId, retrieved.traceId);
        assertEquals(record.immutableVerificationHash, retrieved.immutableVerificationHash);
    }

    @Test
    void test04_listTraceabilityRecordsWithFilter() {
        var lineage = createSampleLineage();
        managerClient.generateTraceabilityRecord(lineage, "REGULATORY_INSPECTION", "R1");
        managerClient.generateTraceabilityRecord(lineage, "INTERNAL_AUDIT", "R2");

        var regList = managerClient.listTraceabilityRecords("REGULATORY_INSPECTION");
        assertEquals(1, regList.size());
        assertEquals("REGULATORY_INSPECTION", regList.get(0).auditPurpose);

        var allList = managerClient.listTraceabilityRecords(null);
        assertEquals(2, allList.size());
    }

    @Test
    void test05_nullLineageThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                managerClient.generateTraceabilityRecord(null, "INTERNAL_AUDIT", "")
        );
        assertTrue(ex.getMessage().contains("ERR_BATCH_REQUIRED"));
    }

    @Test
    void test06_cashierRoleDenied() {
        var lineage = createSampleLineage();
        SecurityException ex = assertThrows(SecurityException.class, () ->
                cashierClient.generateTraceabilityRecord(lineage, "INTERNAL_AUDIT", "")
        );
        assertTrue(ex.getMessage().contains("ERR_STAFF_ROLE_DENIED"));
    }
}
