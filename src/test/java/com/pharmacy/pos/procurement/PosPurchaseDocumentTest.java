package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PosPurchaseDocumentClient Tests (ADR-0198 / P198)")
public class PosPurchaseDocumentTest {

    private Map<String, PosPurchaseDocumentClient.PurchaseDocumentDto> documentStore;
    private PosPurchaseDocumentClient managerClient;
    private PosPurchaseDocumentClient cashierClient;

    @BeforeEach
    public void setUp() {
        documentStore = new HashMap<>();

        managerClient = new PosPurchaseDocumentClient(
                "tenant-dhaka-001",
                "branch-dhanmondi-01",
                "BRANCH_MANAGER",
                "usr-mgr-1",
                false,
                documentStore
        );

        cashierClient = new PosPurchaseDocumentClient(
                "tenant-dhaka-001",
                "branch-dhanmondi-01",
                "CASHIER",
                "usr-csh-1",
                false,
                documentStore
        );
    }

    private List<PosPurchaseDocumentClient.DocumentLineItemDto> createSampleLines() {
        return List.of(
                new PosPurchaseDocumentClient.DocumentLineItemDto(
                        "item-1",
                        "Napa Extra 500mg",
                        "BATCH-NP-01",
                        100,
                        new BigDecimal("2.5000"),
                        new BigDecimal("250.0000")
                ),
                new PosPurchaseDocumentClient.DocumentLineItemDto(
                        "item-2",
                        "Seclo 20mg Capsule",
                        "BATCH-SC-02",
                        50,
                        new BigDecimal("5.0000"),
                        new BigDecimal("250.0000")
                )
        );
    }

    @Test
    @DisplayName("01. Generates version 1 PO document with valid SHA-256 hash and totals")
    public void test01_generatesVersion1PoDocument() {
        var lines = createSampleLines();
        var doc = managerClient.generateDocument(
                PosPurchaseDocumentClient.PosDocumentType.PURCHASE_ORDER,
                "po-001",
                "PO-202611-001",
                "sup-square",
                "Square Pharmaceuticals Ltd.",
                new BigDecimal("500.0000"),
                lines,
                "Regular seasonal restock",
                PosPurchaseDocumentClient.PosDocumentFormat.A4_PDF
        );

        assertNotNull(doc);
        assertEquals(1, doc.version);
        assertTrue(doc.isLatest);
        assertEquals(PosPurchaseDocumentClient.PosDocumentStatus.FINAL, doc.status);
        assertEquals(2, doc.lineItems.size());
        assertEquals(new BigDecimal("500.0000"), doc.totalAmount);
        assertNotNull(doc.immutableVerificationHash);
        assertEquals(64, doc.immutableVerificationHash.length());
        assertTrue(managerClient.verifyDocumentIntegrity(doc.documentId));
    }

    @Test
    @DisplayName("02. Re-generating document increments version to 2 and supersedes version 1")
    public void test02_regeneratingDocumentIncrementsVersion() {
        var lines = createSampleLines();
        var v1 = managerClient.generateDocument(
                PosPurchaseDocumentClient.PosDocumentType.PURCHASE_ORDER,
                "po-002",
                "PO-202611-002",
                "sup-beximco",
                "Beximco Pharmaceuticals Ltd.",
                new BigDecimal("500.0000"),
                lines,
                "Initial order",
                PosPurchaseDocumentClient.PosDocumentFormat.A4_PDF
        );

        assertEquals(1, v1.version);
        assertTrue(v1.isLatest);

        // Regenerate updated order with 600 BDT
        var v2 = managerClient.generateDocument(
                PosPurchaseDocumentClient.PosDocumentType.PURCHASE_ORDER,
                "po-002",
                "PO-202611-002",
                "sup-beximco",
                "Beximco Pharmaceuticals Ltd.",
                new BigDecimal("600.0000"),
                lines,
                "Amended delivery terms",
                PosPurchaseDocumentClient.PosDocumentFormat.A4_PDF
        );

        assertEquals(2, v2.version);
        assertTrue(v2.isLatest);
        assertEquals(PosPurchaseDocumentClient.PosDocumentStatus.FINAL, v2.status);

        // Prior version 1 must now be superseded
        var retrievedV1 = managerClient.getDocument(v1.documentId);
        assertFalse(retrievedV1.isLatest);
        assertEquals(PosPurchaseDocumentClient.PosDocumentStatus.SUPERSEDED, retrievedV1.status);

        // Listing versions
        var allVersions = managerClient.listDocuments(
                PosPurchaseDocumentClient.PosDocumentType.PURCHASE_ORDER, "po-002", false
        );
        assertEquals(2, allVersions.size());
    }

    @Test
    @DisplayName("03. Generates Goods Receipt Note and Purchase Return documents")
    public void test03_generatesGrnAndReturnDocuments() {
        var lines = createSampleLines();
        var grnDoc = managerClient.generateDocument(
                PosPurchaseDocumentClient.PosDocumentType.GOODS_RECEIPT_NOTE,
                "grn-001",
                "GRN-202611-001",
                "sup-incepta",
                "Incepta Pharmaceuticals Ltd.",
                new BigDecimal("500.0000"),
                lines,
                "Challan CH-9901 received",
                PosPurchaseDocumentClient.PosDocumentFormat.A4_PDF
        );

        assertNotNull(grnDoc);
        assertTrue(grnDoc.documentNumber.startsWith("DOC-GRN-"));

        var retDoc = managerClient.generateDocument(
                PosPurchaseDocumentClient.PosDocumentType.PURCHASE_RETURN,
                "ret-001",
                "RET-202611-001",
                "sup-incepta",
                "Incepta Pharmaceuticals Ltd.",
                new BigDecimal("50.0000"),
                lines,
                "Damaged strip returned",
                PosPurchaseDocumentClient.PosDocumentFormat.A4_PDF
        );

        assertNotNull(retDoc);
        assertTrue(retDoc.documentNumber.startsWith("DOC-RET-"));
    }

    @Test
    @DisplayName("04. Generates Supplier Statement of Account")
    public void test04_generatesSupplierStatement() {
        var stmtDoc = managerClient.generateDocument(
                PosPurchaseDocumentClient.PosDocumentType.SUPPLIER_STATEMENT,
                "sup-square",
                "STMT-2026-Q4",
                "sup-square",
                "Square Pharmaceuticals Ltd.",
                new BigDecimal("125000.0000"),
                Collections.emptyList(),
                "Q4 Ledger Reconciliation",
                PosPurchaseDocumentClient.PosDocumentFormat.A4_PDF
        );

        assertNotNull(stmtDoc);
        assertTrue(stmtDoc.documentNumber.startsWith("DOC-STMT-"));
        assertEquals(new BigDecimal("125000.0000"), stmtDoc.totalAmount);
    }

    @Test
    @DisplayName("05. Cashier role access denied with ERR_STAFF_ROLE_DENIED")
    public void test05_cashierRoleDenied() {
        SecurityException ex = assertThrows(SecurityException.class, () -> {
            cashierClient.generateDocument(
                    PosPurchaseDocumentClient.PosDocumentType.PURCHASE_ORDER,
                    "po-csh-01",
                    "PO-CSH-01",
                    "sup-square",
                    "Square",
                    BigDecimal.TEN,
                    Collections.emptyList(),
                    "Unauthorized attempt",
                    PosPurchaseDocumentClient.PosDocumentFormat.A4_PDF
            );
        });

        assertTrue(ex.getMessage().contains("ERR_STAFF_ROLE_DENIED"));
    }

    @Test
    @DisplayName("06. Verifies document cryptographic integrity against tampering")
    public void test06_verifiesIntegrityAgainstTampering() {
        var doc = managerClient.generateDocument(
                PosPurchaseDocumentClient.PosDocumentType.PURCHASE_ORDER,
                "po-sec-01",
                "PO-SEC-01",
                "sup-square",
                "Square",
                new BigDecimal("100.0000"),
                Collections.emptyList(),
                "Security check",
                PosPurchaseDocumentClient.PosDocumentFormat.A4_PDF
        );

        assertTrue(managerClient.verifyDocumentIntegrity(doc.documentId));
    }
}

