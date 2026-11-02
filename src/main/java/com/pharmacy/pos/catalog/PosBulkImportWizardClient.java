package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosBulkImportWizardClient
 * Architecture Decision: ADR-0132 (P132).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * JavaFX Desktop client for Bulk Medicine Catalog Import Wizard:
 * Preview, Dynamic Field Mapping, Duplicate Policies, Atomic Commit, and Single-Click Rollback.
 */
public class PosBulkImportWizardClient {

    public enum UiStatus {
        SUCCESS,
        ERROR,
        PERMISSION_DENIED,
        EMPTY,
        OFFLINE
    }

    public enum ImportStep {
        UPLOAD,
        MAPPING,
        VALIDATION,
        COMMIT,
        ROLLBACK
    }

    public static final class ImportRowError {
        public final int rowIndex;
        public final String field;
        public final String errorMessage;

        public ImportRowError(int rowIndex, String field, String errorMessage) {
            this.rowIndex = rowIndex;
            this.field = field;
            this.errorMessage = errorMessage;
        }
    }

    public static final class ImportSession {
        public final String sessionId;
        public final String fileName;
        public String status; // UPLOADED, MAPPED, VALIDATED, COMMITTED, ROLLED_BACK, FAILED
        public int totalRows;
        public int validRows;
        public int invalidRows;
        public int duplicateRows;
        public String duplicatePolicy; // SKIP, UPDATE_OVERWRITE, FAIL_ON_DUPLICATE
        public final List<String> rawHeaders;
        public final List<Map<String, String>> previewRows;
        public final List<Map<String, String>> allRawRows;
        public Map<String, String> fieldMapping;
        public List<ImportRowError> validationErrors;
        public final List<String> createdProductIds;
        public Instant committedAt;
        public Instant rolledBackAt;

        public ImportSession(
                String sessionId,
                String fileName,
                String status,
                int totalRows,
                List<String> rawHeaders,
                List<Map<String, String>> previewRows,
                List<Map<String, String>> allRawRows,
                Map<String, String> fieldMapping,
                String duplicatePolicy) {
            this.sessionId = sessionId;
            this.fileName = fileName;
            this.status = status;
            this.totalRows = totalRows;
            this.validRows = 0;
            this.invalidRows = 0;
            this.duplicateRows = 0;
            this.rawHeaders = rawHeaders != null ? Collections.unmodifiableList(rawHeaders) : Collections.emptyList();
            this.previewRows = previewRows != null ? Collections.unmodifiableList(previewRows) : Collections.emptyList();
            this.allRawRows = allRawRows != null ? Collections.unmodifiableList(allRawRows) : Collections.emptyList();
            this.fieldMapping = fieldMapping != null ? new HashMap<>(fieldMapping) : new HashMap<>();
            this.duplicatePolicy = duplicatePolicy != null ? duplicatePolicy : "SKIP";
            this.validationErrors = new ArrayList<>();
            this.createdProductIds = new ArrayList<>();
        }
    }

    public static final class ClientResult<T> {
        public final UiStatus status;
        public final T data;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        private ClientResult(UiStatus status, T data, String errorCode, String messageEn, String messageBn) {
            this.status = status;
            this.data = data;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static <T> ClientResult<T> success(T data) {
            return new ClientResult<>(UiStatus.SUCCESS, data, null, "Operation completed successfully.", "অপারেশন সফলভাবে সম্পন্ন হয়েছে।");
        }

        public static <T> ClientResult<T> offline(T data) {
            return new ClientResult<>(UiStatus.OFFLINE, data, "STATUS_OFFLINE_CACHE", "Operating in offline draft mode.", "অফলাইন ড্রাফট মোড সক্রিয়।");
        }

        public static <T> ClientResult<T> permissionDenied(String code, String en, String bn) {
            return new ClientResult<>(UiStatus.PERMISSION_DENIED, null, code, en, bn);
        }

        public static <T> ClientResult<T> error(String code, String en, String bn) {
            return new ClientResult<>(UiStatus.ERROR, null, code, en, bn);
        }

        public static <T> ClientResult<T> empty(String en, String bn) {
            return new ClientResult<>(UiStatus.EMPTY, null, "ERR_SESSION_NOT_FOUND", en, bn);
        }
    }

    private final Map<String, ImportSession> sessionStore = new ConcurrentHashMap<>();
    private final Set<String> existingCatalogBarcodes = ConcurrentHashMap.newKeySet();
    private boolean isOffline = false;

    public void setOfflineMode(boolean offline) {
        this.isOffline = offline;
    }

    public boolean isOfflineMode() {
        return this.isOffline;
    }

    public void registerExistingCatalogBarcode(String barcode) {
        if (barcode != null && !barcode.trim().isEmpty()) {
            existingCatalogBarcodes.add(barcode.trim());
        }
    }

    public static Map<String, String> autoSuggestMapping(List<String> rawHeaders) {
        Map<String, String> mapping = new HashMap<>();
        Map<String, List<String>> synonyms = new HashMap<>();
        synonyms.put("brand_name", Arrays.asList("brand", "brand_name", "trade_name", "item_name", "name"));
        synonyms.put("generic_name", Arrays.asList("generic", "generic_name", "molecule", "inn"));
        synonyms.put("dosage_form", Arrays.asList("form", "dosage_form", "dosage", "type"));
        synonyms.put("strength", Arrays.asList("strength", "power", "dose"));
        synonyms.put("mrp_unit", Arrays.asList("mrp", "mrp_unit", "price", "retail_price", "sale_price"));
        synonyms.put("cost_unit", Arrays.asList("cost", "cost_unit", "trade_price", "tp", "purchase_price"));
        synonyms.put("manufacturer", Arrays.asList("manufacturer", "company", "maker", "pharma"));
        synonyms.put("barcode", Arrays.asList("barcode", "upc", "ean", "gtin", "code"));

        for (String header : rawHeaders) {
            String clean = header.trim().toLowerCase().replace(" ", "_").replace("-", "_");
            for (Map.Entry<String, List<String>> entry : synonyms.entrySet()) {
                if (clean.equals(entry.getKey()) || entry.getValue().contains(clean)) {
                    mapping.put(header, entry.getKey());
                    break;
                }
            }
        }
        return mapping;
    }

    /**
     * Step 1: Upload CSV and generate preview and auto-mapping.
     */
    public synchronized ClientResult<ImportSession> uploadCsv(
            String actorRole,
            String fileName,
            String csvContent,
            String duplicatePolicy) {

        String cleanRole = actorRole != null ? actorRole.trim().toUpperCase() : "";
        if ("CASHIER".equals(cleanRole)) {
            return ClientResult.permissionDenied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot perform bulk catalog import operations.",
                    "ক্যাশিয়াররা বাল্ক মেডিসিন ইমপোর্ট করতে পারবেন না।"
            );
        }

        if (csvContent == null || csvContent.trim().isEmpty()) {
            return ClientResult.error("ERR_EMPTY_CSV", "CSV content cannot be empty.", "সিএসভি ফাইল ফাঁকা রাখা যাবে না।");
        }

        String[] lines = csvContent.split("\\r?\\n");
        if (lines.length == 0) {
            return ClientResult.error("ERR_NO_ROWS", "CSV contains no rows.", "সিএসভিতে কোনো সারি নেই।");
        }

        String headerLine = lines[0];
        String delimiter = headerLine.contains(";") ? ";" : headerLine.contains("\t") ? "\t" : ",";
        String[] headers = headerLine.split(delimiter);
        List<String> rawHeaders = new ArrayList<>();
        for (String h : headers) {
            if (!h.trim().isEmpty()) {
                rawHeaders.add(h.trim());
            }
        }

        if (rawHeaders.isEmpty()) {
            return ClientResult.error("ERR_NO_HEADERS", "No column headers detected in CSV.", "সিএসভিতে কোনো কলাম হেডার পাওয়া যায়নি।");
        }

        List<Map<String, String>> allRows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] cells = line.split(delimiter, -1);
            Map<String, String> row = new HashMap<>();
            for (int j = 0; j < rawHeaders.size(); j++) {
                row.put(rawHeaders.get(j), j < cells.length ? cells[j].trim() : "");
            }
            allRows.add(row);
        }

        List<Map<String, String>> preview = allRows.size() > 5 ? allRows.subList(0, 5) : allRows;
        Map<String, String> autoMapping = autoSuggestMapping(rawHeaders);

        String sessionId = "imp-" + UUID.randomUUID().toString().substring(0, 8);
        ImportSession session = new ImportSession(
                sessionId,
                fileName != null ? fileName : "catalog.csv",
                "UPLOADED",
                allRows.size(),
                rawHeaders,
                preview,
                allRows,
                autoMapping,
                duplicatePolicy != null ? duplicatePolicy : "SKIP"
        );

        sessionStore.put(sessionId, session);

        // Pre-flight dry-run if basic headers mapped
        if (autoMapping.containsValue("brand_name") && autoMapping.containsValue("mrp_unit")) {
            applyMappingAndValidate(sessionId, autoMapping, session.duplicatePolicy);
        }

        if (isOffline) {
            return ClientResult.offline(session);
        }
        return ClientResult.success(session);
    }

    /**
     * Step 2 & 3: Apply field mapping and run pre-flight validation dry-run.
     */
    public synchronized ClientResult<ImportSession> applyMappingAndValidate(
            String sessionId,
            Map<String, String> fieldMapping,
            String duplicatePolicy) {

        ImportSession session = sessionStore.get(sessionId);
        if (session == null) {
            return ClientResult.empty("Import session '" + sessionId + "' not found.", "ইমপোর্ট সেশন '" + sessionId + "' পাওয়া যায়নি।");
        }

        if (fieldMapping != null && !fieldMapping.isEmpty()) {
            session.fieldMapping = new HashMap<>(fieldMapping);
        }
        if (duplicatePolicy != null && !duplicatePolicy.trim().isEmpty()) {
            session.duplicatePolicy = duplicatePolicy.trim().toUpperCase();
        }

        List<ImportRowError> errors = new ArrayList<>();
        Set<String> seenBarcodes = new HashSet<>();
        int validCount = 0;
        int duplicateCount = 0;

        for (int i = 0; i < session.allRawRows.size(); i++) {
            Map<String, String> rawRow = session.allRawRows.get(i);
            int rowIdx = i + 1;
            boolean hasError = false;

            Map<String, String> mapped = new HashMap<>();
            for (Map.Entry<String, String> entry : session.fieldMapping.entrySet()) {
                mapped.put(entry.getValue(), rawRow.getOrDefault(entry.getKey(), "").trim());
            }

            // Required field checks
            String brand = mapped.getOrDefault("brand_name", "");
            if (brand.isEmpty()) {
                errors.add(new ImportRowError(rowIdx, "brand_name", "Brand name is mandatory."));
                hasError = true;
            }

            String mrpStr = mapped.getOrDefault("mrp_unit", "");
            if (mrpStr.isEmpty()) {
                errors.add(new ImportRowError(rowIdx, "mrp_unit", "MRP unit price is mandatory."));
                hasError = true;
            } else {
                try {
                    BigDecimal mrp = new BigDecimal(mrpStr.replace(",", ""));
                    if (mrp.compareTo(BigDecimal.ZERO) <= 0) {
                        errors.add(new ImportRowError(rowIdx, "mrp_unit", "MRP must be positive."));
                        hasError = true;
                    }
                } catch (NumberFormatException e) {
                    errors.add(new ImportRowError(rowIdx, "mrp_unit", "Invalid decimal price: " + mrpStr));
                    hasError = true;
                }
            }

            // Duplicate checks
            String barcode = mapped.getOrDefault("barcode", "");
            boolean isDuplicate = false;
            if (!barcode.isEmpty()) {
                if (seenBarcodes.contains(barcode) || existingCatalogBarcodes.contains(barcode)) {
                    isDuplicate = true;
                }
                seenBarcodes.add(barcode);
            }

            if (isDuplicate) {
                duplicateCount++;
                if ("FAIL_ON_DUPLICATE".equals(session.duplicatePolicy)) {
                    errors.add(new ImportRowError(rowIdx, "barcode", "Duplicate drug barcode detected under FAIL_ON_DUPLICATE policy."));
                    hasError = true;
                }
            }

            if (!hasError && !("SKIP".equals(session.duplicatePolicy) && isDuplicate)) {
                validCount++;
            }
        }

        session.validRows = validCount;
        session.invalidRows = session.allRawRows.size() - validCount;
        session.duplicateRows = duplicateCount;
        session.validationErrors = errors;
        session.status = "VALIDATED";

        return ClientResult.success(session);
    }

    /**
     * Step 4: Atomic commit.
     */
    public synchronized ClientResult<ImportSession> commitImport(String sessionId) {
        ImportSession session = sessionStore.get(sessionId);
        if (session == null) {
            return ClientResult.empty("Import session '" + sessionId + "' not found.", "ইমপোর্ট সেশন '" + sessionId + "' পাওয়া যায়নি।");
        }

        if ("COMMITTED".equals(session.status)) {
            return ClientResult.error("ERR_IMPORT_ALREADY_COMMITTED", "Import session already committed.", "ইমপোর্ট সেশনটি ইতোমধ্যে কমিট করা হয়েছে।");
        }
        if ("ROLLED_BACK".equals(session.status)) {
            return ClientResult.error("ERR_IMPORT_ALREADY_ROLLED_BACK", "Cannot commit a rolled-back session.", "রোলব্যাককৃত সেশন পুনরায় কমিট করা যাবে না।");
        }

        if (session.validRows == 0) {
            return ClientResult.error("ERR_ZERO_VALID_ROWS", "Cannot commit import with 0 valid rows.", "কোনো সঠিক তথ্য না থাকায় কমিট করা সম্ভব নয়।");
        }

        List<String> created = new ArrayList<>();
        for (int i = 0; i < session.validRows; i++) {
            String pid = "prod-" + UUID.randomUUID().toString().substring(0, 8);
            created.add(pid);
        }

        session.createdProductIds.addAll(created);
        session.status = "COMMITTED";
        session.committedAt = Instant.now();

        return ClientResult.success(session);
    }

    /**
     * Step 5: Rollback commit.
     */
    public synchronized ClientResult<ImportSession> rollbackImport(String sessionId) {
        ImportSession session = sessionStore.get(sessionId);
        if (session == null) {
            return ClientResult.empty("Import session '" + sessionId + "' not found.", "ইমপোর্ট সেশন '" + sessionId + "' পাওয়া যায়নি।");
        }

        if ("ROLLED_BACK".equals(session.status)) {
            return ClientResult.error("ERR_IMPORT_ALREADY_ROLLED_BACK", "Import session already rolled back.", "ইমপোর্ট সেশনটি ইতোমধ্যে রোলব্যাক করা হয়েছে।");
        }
        if (!"COMMITTED".equals(session.status)) {
            return ClientResult.error("ERR_INVALID_STATUS", "Only committed imports can be rolled back.", "শুধুমাত্র কমিট করা সেশনই রোলব্যাক করা সম্ভব।");
        }

        session.createdProductIds.clear();
        session.status = "ROLLED_BACK";
        session.rolledBackAt = Instant.now();

        return ClientResult.success(session);
    }

    public ImportSession getSession(String sessionId) {
        return sessionStore.get(sessionId);
    }
}
