package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosManufacturerSupplierClientTest
 * Architecture Decision: ADR-0127 (P127).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 */
class PosManufacturerSupplierClientTest {

    private PosManufacturerSupplierClient client;

    @BeforeEach
    void setUp() {
        client = new PosManufacturerSupplierClient();

        // Manufacturers
        client.registerManufacturer(new PosManufacturerSupplierClient.ManufacturerRecord(
                "mfr-square", "Square Pharmaceuticals Ltd.", "স্কয়ার", "SQUARE", "DGDA-001", true, null
        ));
        client.registerManufacturer(new PosManufacturerSupplierClient.ManufacturerRecord(
                "mfr-sq-pharma", "Square Pharma (Duplicate)", "স্কয়ার", "SQ_DUP", "DGDA-001", false, "mfr-square"
        ));

        // Suppliers
        client.registerSupplier(new PosManufacturerSupplierClient.SupplierRecord(
                "sup-t1-main", "tn-1", "Square Dhanmondi Depot", "Mr. Kamal", "01711111111", "dhanmondi@square.com", "DL-101", true, null
        ));
        client.registerSupplier(new PosManufacturerSupplierClient.SupplierRecord(
                "sup-t1-dup", "tn-1", "Square Depot Dhanmondi (Duplicate)", "Mr. Kamal", "01711111111", "dhanmondi@square.com", "DL-101", true, null
        ));
        client.registerSupplier(new PosManufacturerSupplierClient.SupplierRecord(
                "sup-t2-main", "tn-2", "Beximco Tejgaon Depot", "Mr. Rahim", "01822222222", "tejgaon@beximco.com", "DL-202", true, null
        ));
    }

    @Test
    @DisplayName("P127-D1: Active manufacturers list excludes merged duplicate entities")
    void testListActiveManufacturers() {
        List<PosManufacturerSupplierClient.ManufacturerRecord> list = client.listActiveManufacturers();
        assertEquals(1, list.size());
        assertEquals("Square Pharmaceuticals Ltd.", list.get(0).name);
    }

    @Test
    @DisplayName("P127-D2: Successfully merges duplicate supplier into canonical target")
    void testMergeSuppliersWithinTenant() {
        PosManufacturerSupplierClient.MergeResult res = client.mergeSuppliers(
                "tn-1", "PHARMACY_OWNER", "sup-t1-dup", "sup-t1-main", "Deduplicating local depot entry"
        );
        assertTrue(res.isMerged);
        assertEquals(PosManufacturerSupplierClient.UiStatus.SUCCESS, res.uiStatus);
        assertEquals("Square Dhanmondi Depot", res.targetName);

        // Verify active list now has only 1 supplier for tn-1
        List<PosManufacturerSupplierClient.SupplierRecord> active = client.listActiveSuppliers("tn-1");
        assertEquals(1, active.size());
        assertEquals("sup-t1-main", active.get(0).supplierId);
    }

    @Test
    @DisplayName("P127-D3: Self-merge is strictly prevented with ERR_CANNOT_MERGE_SELF")
    void testCannotMergeSelf() {
        PosManufacturerSupplierClient.MergeResult res = client.mergeSuppliers(
                "tn-1", "PHARMACY_OWNER", "sup-t1-main", "sup-t1-main", "Self merge test"
        );
        assertFalse(res.isMerged);
        assertEquals("ERR_CANNOT_MERGE_SELF", res.errorCode);
    }

    @Test
    @DisplayName("P127-D4: Cross-tenant supplier merge is strictly blocked")
    void testCrossTenantSupplierMergeBlocked() {
        PosManufacturerSupplierClient.MergeResult res = client.mergeSuppliers(
                "tn-1", "PHARMACY_OWNER", "sup-t1-main", "sup-t2-main", "Cross tenant merge"
        );
        assertFalse(res.isMerged);
        assertEquals(PosManufacturerSupplierClient.UiStatus.PERMISSION_DENIED, res.uiStatus);
        assertEquals("ERR_CROSS_TENANT_ACCESS", res.errorCode);
    }

    @Test
    @DisplayName("P127-D5: Cashier role is denied supplier merge with ERR_STAFF_ROLE_DENIED")
    void testCashierRoleMergeDenied() {
        PosManufacturerSupplierClient.MergeResult res = client.mergeSuppliers(
                "tn-1", "CASHIER", "sup-t1-dup", "sup-t1-main", "Cashier merge test"
        );
        assertFalse(res.isMerged);
        assertEquals(PosManufacturerSupplierClient.UiStatus.PERMISSION_DENIED, res.uiStatus);
        assertEquals("ERR_STAFF_ROLE_DENIED", res.errorCode);
    }

    @Test
    @DisplayName("P127-D6: Pharmacy owner blocked from manufacturer mutation with PLATFORM_CATALOG_OWNERSHIP_PROTECTED")
    void testTenantCreateManufacturerBlocked() {
        PosManufacturerSupplierClient.MergeResult res = client.attemptCreateManufacturer(
                "PHARMACY_OWNER",
                new PosManufacturerSupplierClient.ManufacturerRecord("mfr-test", "Test Pharma", "টেস্ট", "TEST", "", true, null)
        );
        assertFalse(res.isMerged);
        assertEquals(PosManufacturerSupplierClient.UiStatus.PERMISSION_DENIED, res.uiStatus);
        assertEquals("PLATFORM_CATALOG_OWNERSHIP_PROTECTED", res.errorCode);
    }
}
