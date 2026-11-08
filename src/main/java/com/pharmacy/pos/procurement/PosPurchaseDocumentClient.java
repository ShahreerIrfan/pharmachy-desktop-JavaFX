package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * PosPurchaseDocumentClient
 * Architecture Decision: ADR-0198 (P198).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 *
 * Desktop JavaFX client for Purchase Document Generation:
 * - Generates versioned PO, GRN, Return, and Statement documents.
 * - Computes immutable SHA-256 cryptographic hashes for tamper verification.
 * - Enforces role-based permissions and version history tracking.
 */
public class PosPurchaseDocumentClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, PurchaseDocumentDto> documentStore;

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "PROCUREMENT_OFFICER", "INVENTORY_CLERK", "AUDITOR"
    );

    public enum PosDocumentType {
        PURCHASE_ORDER,
        GOODS_RECEIPT_NOTE,
        PURCHASE_RETURN,
        SUPPLIER_STATEMENT
    }

    public enum PosDocumentFormat {
        A4_PDF,
        THERMAL_80MM,
        JSON_DATA
    }

    public enum PosDocumentStatus {
        DRAFT,
        FINAL,
        SUPERSEDED,
        VOID
    }

    public PosPurchaseDocumentClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, PurchaseDocumentDto> documentStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.documentStore = documentStore != null ? documentStore : new HashMap<>();
    }

    public static final class DocumentLineItemDto {
        public final String itemId;
        public final String productName;
        public final String batchOrReference;
        public final int quantity;
        public final BigDecimal unitCost;
        public final BigDecimal lineTotal;

        public DocumentLineItemDto(String itemId, String productName, String batchOrReference,
                                   int quantity, BigDecimal unitCost, BigDecimal lineTotal) {
            this.itemId = itemId != null ? itemId : "";
            this.productName = productName != null ? productName : "";
            this.batchOrReference = batchOrReference != null ? batchOrReference : "";
            this.quantity = quantity;
            this.unitCost = unitCost != null ? unitCost : BigDecimal.ZERO;
            this.lineTotal = lineTotal != null ? lineTotal : BigDecimal.ZERO;
        }
    }

    public static final class PurchaseDocumentDto {
        public final String documentId;
        public final String documentNumber;
        public final PosDocumentType documentType;
        public final int version;
        public boolean isLatest;
        public final String sourceId;
        public final String sourceReference;
        public final String supplierId;
        public final String supplierName;
        public final PosDocumentFormat format;
        public final BigDecimal totalAmount;
        public final String currency;
        public final List<DocumentLineItemDto> lineItems;
        public final String immutableVerificationHash;
        public PosDocumentStatus status;
        public final String notes;
        public final String generatedBy;
        public final Instant generatedAt;

        public PurchaseDocumentDto(
                String documentId,
                String documentNumber,
                PosDocumentType documentType,
                int version,
                boolean isLatest,
                String sourceId,
                String sourceReference,
                String supplierId,
                String supplierName,
                PosDocumentFormat format,
                BigDecimal totalAmount,
                String currency,
                List<DocumentLineItemDto> lineItems,
                String immutableVerificationHash,
                PosDocumentStatus status,
                String notes,
                String generatedBy,
                Instant generatedAt) {
            this.documentId = documentId;
            this.documentNumber = documentNumber;
            this.documentType = documentType;
            this.version = version;
            this.isLatest = isLatest;
            this.sourceId = sourceId;
            this.sourceReference = sourceReference;
            this.supplierId = supplierId;
            this.supplierName = supplierName;
            this.format = format;
            this.totalAmount = totalAmount;
            this.currency = currency != null ? currency : "BDT";
            this.lineItems = lineItems != null ? Collections.unmodifiableList(new ArrayList<>(lineItems)) : Collections.emptyList();
            this.immutableVerificationHash = immutableVerificationHash;
            this.status = status;
            this.notes = notes != null ? notes : "";
            this.generatedBy = generatedBy;
            this.generatedAt = generatedAt != null ? generatedAt : Instant.now();
        }
    }

    private void assertRolePermission() {
        if ("CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Staff role " + userRole + " is not authorized to generate purchase documents.");
        }
    }

    public static String computeVerificationHash(
            String documentId,
            String tenantId,
            PosDocumentType docType,
            String sourceId,
            int version,
            BigDecimal totalAmount,
            int lineItemCount) {
        String raw = documentId + "|" + tenantId + "|" + docType.name() + "|" + sourceId + "|" +
                version + "|" + totalAmount.toPlainString() + "|" + lineItemCount;
        try {
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
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public PurchaseDocumentDto generateDocument(
            PosDocumentType docType,
            String sourceId,
            String sourceReference,
            String supplierId,
            String supplierName,
            BigDecimal totalAmount,
            List<DocumentLineItemDto> lineItems,
            String notes,
            PosDocumentFormat format) {

        assertRolePermission();

        if (docType == null || sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("ERR_INVALID_INPUT: docType and sourceId are required.");
        }

        // 1. Check existing versions
        int latestVersion = 0;
        for (PurchaseDocumentDto existing : documentStore.values()) {
            if (existing.documentType == docType && sourceId.equalsIgnoreCase(existing.sourceId)) {
                if (existing.version > latestVersion) {
                    latestVersion = existing.version;
                }
                if (existing.isLatest) {
                    existing.isLatest = false;
                    existing.status = PosDocumentStatus.SUPERSEDED;
                }
            }
        }

        int nextVersion = latestVersion + 1;
        String docId = "pdoc-" + (documentStore.size() + 1) + "-" + System.currentTimeMillis();
        String prefix = switch (docType) {
            case PURCHASE_ORDER -> "DOC-PO";
            case GOODS_RECEIPT_NOTE -> "DOC-GRN";
            case PURCHASE_RETURN -> "DOC-RET";
            case SUPPLIER_STATEMENT -> "DOC-STMT";
        };
        String docNum = prefix + "-" + System.currentTimeMillis();

        BigDecimal safeTotal = totalAmount != null ? totalAmount : BigDecimal.ZERO;
        int lineCount = lineItems != null ? lineItems.size() : 0;

        String hash = computeVerificationHash(docId, tenantId, docType, sourceId, nextVersion, safeTotal, lineCount);

        PurchaseDocumentDto newDoc = new PurchaseDocumentDto(
                docId,
                docNum,
                docType,
                nextVersion,
                true,
                sourceId,
                sourceReference != null ? sourceReference : sourceId,
                supplierId,
                supplierName != null ? supplierName : "",
                format != null ? format : PosDocumentFormat.A4_PDF,
                safeTotal,
                "BDT",
                lineItems,
                hash,
                PosDocumentStatus.FINAL,
                notes,
                userId,
                Instant.now()
        );

        documentStore.put(docId, newDoc);
        return newDoc;
    }

    public PurchaseDocumentDto getDocument(String documentId) {
        PurchaseDocumentDto doc = documentStore.get(documentId);
        if (doc == null) {
            throw new NoSuchElementException("ERR_DOCUMENT_NOT_FOUND: Document " + documentId + " not found.");
        }
        return doc;
    }

    public List<PurchaseDocumentDto> listDocuments(PosDocumentType filterType, String filterSourceId, boolean onlyLatest) {
        List<PurchaseDocumentDto> result = new ArrayList<>();
        for (PurchaseDocumentDto doc : documentStore.values()) {
            if (filterType != null && doc.documentType != filterType) {
                continue;
            }
            if (filterSourceId != null && !filterSourceId.isBlank() && !filterSourceId.equalsIgnoreCase(doc.sourceId)) {
                continue;
            }
            if (onlyLatest && !doc.isLatest) {
                continue;
            }
            result.add(doc);
        }
        result.sort((a, b) -> Integer.compare(b.version, a.version));
        return result;
    }

    public boolean verifyDocumentIntegrity(String documentId) {
        PurchaseDocumentDto doc = getDocument(documentId);
        int lineCount = doc.lineItems != null ? doc.lineItems.size() : 0;
        String recomputed = computeVerificationHash(
                doc.documentId, tenantId, doc.documentType, doc.sourceId, doc.version, doc.totalAmount, lineCount
        );
        return recomputed.equalsIgnoreCase(doc.immutableVerificationHash);
    }
}

