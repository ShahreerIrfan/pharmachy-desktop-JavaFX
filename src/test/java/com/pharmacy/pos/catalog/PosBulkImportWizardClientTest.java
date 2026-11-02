package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PosBulkImportWizardClientTest {

    private PosBulkImportWizardClient client;

    @BeforeEach
    void setUp() {
        client = new PosBulkImportWizardClient();
    }

    @Test
    @DisplayName("P132-D1: Upload valid CSV; auto-mapping maps headers and previews first rows")
    void testUploadCsvAutoMappingAndPreview() {
        String csv = "Trade Name,Molecule,Form,Strength,MRP,Manufacturer,Barcode\n" +
                "Napa 500,Paracetamol,Tablet,500mg,1.20,Beximco,890123456\n" +
                "Seclo 20,Omeprazole,Capsule,20mg,6.00,Square,890654321\n";

        var result = client.uploadCsv("PHARMACY_OWNER", "catalog.csv", csv, "SKIP");

        assertEquals(PosBulkImportWizardClient.UiStatus.SUCCESS, result.status);
        assertNotNull(result.data);
        assertEquals("catalog.csv", result.data.fileName);
        assertEquals(2, result.data.totalRows);
        assertEquals(2, result.data.previewRows.size());

        // Check auto-mapping
        Map<String, String> mapping = result.data.fieldMapping;
        assertEquals("brand_name", mapping.get("Trade Name"));
        assertEquals("generic_name", mapping.get("Molecule"));
        assertEquals("mrp_unit", mapping.get("MRP"));
    }

    @Test
    @DisplayName("P132-D2: Custom field mapping and pre-flight validation detects row errors on negative prices")
    void testCustomMappingAndValidationErrors() {
        String csv = "CUSTOM_BRAND,CUSTOM_PRICE\n" +
                "Valid Drug,10.00\n" +
                "Negative Drug,-5.00\n" +
                ",15.00\n";

        var uploadRes = client.uploadCsv("BRANCH_MANAGER", "custom.csv", csv, "SKIP");
        assertEquals(PosBulkImportWizardClient.UiStatus.SUCCESS, uploadRes.status);
        String sessionId = uploadRes.data.sessionId;

        Map<String, String> mapping = new HashMap<>();
        mapping.put("CUSTOM_BRAND", "brand_name");
        mapping.put("CUSTOM_PRICE", "mrp_unit");

        var valRes = client.applyMappingAndValidate(sessionId, mapping, "SKIP");
        assertEquals(PosBulkImportWizardClient.UiStatus.SUCCESS, valRes.status);
        assertEquals("VALIDATED", valRes.data.status);
        assertEquals(1, valRes.data.validRows);
        assertEquals(2, valRes.data.invalidRows);
        assertEquals(2, valRes.data.validationErrors.size());
    }

    @Test
    @DisplayName("P132-D3: Duplicate detection with SKIP policy increments duplicate counter without failing batch")
    void testDuplicateDetectionWithSkipPolicy() {
        // Pre-register existing barcode
        client.registerExistingCatalogBarcode("890111222");

        String csv = "Brand,MRP,Barcode\n" +
                "Existing Product,20.00,890111222\n" +
                "New Product,30.00,890999888\n";

        var uploadRes = client.uploadCsv("PHARMACY_OWNER", "dup_test.csv", csv, "SKIP");
        assertEquals(PosBulkImportWizardClient.UiStatus.SUCCESS, uploadRes.status);
        assertEquals(1, uploadRes.data.duplicateRows);
        assertEquals(1, uploadRes.data.validRows);
    }

    @Test
    @DisplayName("P132-D4: Atomic commit creates products and transitions session status to COMMITTED")
    void testCommitImportLifecycle() {
        String csv = "Brand,MRP\n" +
                "Drug A,10.00\n" +
                "Drug B,20.00\n";

        var uploadRes = client.uploadCsv("PHARMACY_OWNER", "commit_test.csv", csv, "SKIP");
        String sessionId = uploadRes.data.sessionId;

        var commitRes = client.commitImport(sessionId);
        assertEquals(PosBulkImportWizardClient.UiStatus.SUCCESS, commitRes.status);
        assertEquals("COMMITTED", commitRes.data.status);
        assertNotNull(commitRes.data.committedAt);
        assertEquals(2, commitRes.data.createdProductIds.size());

        // Re-committing already committed session fails
        var repeatRes = client.commitImport(sessionId);
        assertEquals(PosBulkImportWizardClient.UiStatus.ERROR, repeatRes.status);
        assertEquals("ERR_IMPORT_ALREADY_COMMITTED", repeatRes.errorCode);
    }

    @Test
    @DisplayName("P132-D5: Single-click rollback reverses created products and marks session ROLLED_BACK")
    void testRollbackReversesCreatedProducts() {
        String csv = "Brand,MRP\nDefective Drug,100.00\n";

        var uploadRes = client.uploadCsv("PHARMACY_OWNER", "rollback_test.csv", csv, "SKIP");
        String sessionId = uploadRes.data.sessionId;

        client.commitImport(sessionId);
        assertEquals("COMMITTED", client.getSession(sessionId).status);

        // Execute rollback
        var rollbackRes = client.rollbackImport(sessionId);
        assertEquals(PosBulkImportWizardClient.UiStatus.SUCCESS, rollbackRes.status);
        assertEquals("ROLLED_BACK", rollbackRes.data.status);
        assertNotNull(rollbackRes.data.rolledBackAt);
        assertTrue(rollbackRes.data.createdProductIds.isEmpty());

        // Re-attempting rollback fails
        var repeatRollback = client.rollbackImport(sessionId);
        assertEquals(PosBulkImportWizardClient.UiStatus.ERROR, repeatRollback.status);
        assertEquals("ERR_IMPORT_ALREADY_ROLLED_BACK", repeatRollback.errorCode);
    }

    @Test
    @DisplayName("P132-D6: Cashier role blocked from uploading CSV")
    void testCashierDeniedUpload() {
        var result = client.uploadCsv("CASHIER", "cashier.csv", "Col1,Col2\nVal1,Val2\n", "SKIP");

        assertEquals(PosBulkImportWizardClient.UiStatus.PERMISSION_DENIED, result.status);
        assertEquals("ERR_STAFF_ROLE_DENIED", result.errorCode);
        assertNotNull(result.messageBn);
    }
}
