package com.pharmacy.pos.parties;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PosPartyImportExportClient
 * Architecture Decision: ADR-0278 (P278).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * JavaFX Desktop POS client integrating Customer and Supplier import/export
 * with preview, phone normalization, duplicate detection, and privacy masking.
 */
public class PosPartyImportExportClient {

    public static final Set<String> AUTHORIZED_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> UNMASKED_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "OWNER_ADMIN", "ADMIN"
    );

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final Map<String, Map<String, Object>> mockCustomerDb;
    private final Map<String, Map<String, Object>> mockSupplierDb;

    public PosPartyImportExportClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.mockCustomerDb = new HashMap<>();
        this.mockSupplierDb = new HashMap<>();
    }

    public boolean isAuthorized() {
        return AUTHORIZED_ROLES.contains(this.userRole);
    }

    public boolean canViewUnmasked() {
        return UNMASKED_ROLES.contains(this.userRole);
    }

    private void assertAuthorization() {
        if (!isAuthorized()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers and staff pharmacists cannot perform party import/export.");
        }
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.trim().isEmpty()) return "***";
        String p = phone.trim();
        if (p.length() >= 11) {
            return p.substring(0, 3) + "****" + p.substring(p.length() - 4);
        }
        return "***";
    }

    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        String[] parts = email.split("@", 2);
        String name = parts[0];
        String masked = name.length() > 1 ? name.charAt(0) + "***" : "***";
        return masked + "@" + parts[1];
    }

    public static String maskAccount(String acc) {
        if (acc == null || acc.trim().isEmpty()) return "****";
        String a = acc.trim();
        if (a.length() > 4) {
            return "****" + a.substring(a.length() - 4);
        }
        return "****";
    }

    public void seedCustomer(String id, String name, String phone, String email, String address) {
        Map<String, Object> data = new HashMap<>();
        data.put("customer_id", id);
        data.put("full_name", name);
        data.put("phone", phone);
        data.put("email", email);
        data.put("address", address);
        mockCustomerDb.put(id, data);
    }

    public void seedSupplier(String id, String name, String code, String phone, String bankAcc) {
        Map<String, Object> data = new HashMap<>();
        data.put("supplier_id", id);
        data.put("name", name);
        data.put("code", code);
        data.put("phone", phone);
        data.put("bank_account_no", bankAcc);
        mockSupplierDb.put(id, data);
    }

    public List<Map<String, Object>> exportParties(String partyType, boolean includeSensitive) {
        assertAuthorization();

        boolean allowUnmasked = canViewUnmasked() && includeSensitive;
        String type = partyType != null ? partyType.trim().toUpperCase() : "CUSTOMER";

        if ("CUSTOMER".equals(type)) {
            return mockCustomerDb.values().stream().map(c -> {
                Map<String, Object> res = new HashMap<>(c);
                if (!allowUnmasked) {
                    res.put("phone", maskPhone((String) c.get("phone")));
                    res.put("email", maskEmail((String) c.get("email")));
                    res.put("address", "[REDACTED]");
                    res.put("credit_limit_bdt", "[RESTRICTED]");
                }
                return res;
            }).collect(Collectors.toList());
        } else {
            return mockSupplierDb.values().stream().map(s -> {
                Map<String, Object> res = new HashMap<>(s);
                if (!allowUnmasked) {
                    res.put("phone", maskPhone((String) s.get("phone")));
                    res.put("bank_account_no", maskAccount((String) s.get("bank_account_no")));
                }
                return res;
            }).collect(Collectors.toList());
        }
    }

    public ImportPreviewResultDTO previewImport(String partyType, List<Map<String, Object>> records) {
        assertAuthorization();

        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("ERR_EMPTY_IMPORT_PAYLOAD: Records list is required.");
        }

        String type = partyType != null ? partyType.trim().toUpperCase() : "CUSTOMER";
        int valid = 0, dups = 0, invalid = 0;
        List<ImportPreviewItemDTO> previewItems = new ArrayList<>();

        if ("CUSTOMER".equals(type)) {
            Set<String> existingPhones = mockCustomerDb.values().stream()
                    .map(m -> String.valueOf(m.get("phone")).replaceAll("\\D", ""))
                    .collect(Collectors.toSet());

            int row = 1;
            for (Map<String, Object> r : records) {
                String name = (String) r.get("full_name");
                String phone = (String) r.get("phone");
                List<String> errors = new ArrayList<>();

                if (name == null || name.trim().isEmpty()) errors.add("Name is required");
                if (phone == null || phone.trim().isEmpty()) errors.add("Phone is required");

                boolean isDup = false;
                String dupId = null;
                if (errors.isEmpty()) {
                    String digits = phone.replaceAll("\\D", "");
                    if (existingPhones.contains(digits)) {
                        isDup = true;
                        dupId = "MATCH-EXISTING";
                    }
                }

                if (!errors.isEmpty()) invalid++;
                else if (isDup) dups++;
                else valid++;

                previewItems.add(new ImportPreviewItemDTO(row++, errors.isEmpty(), isDup, dupId, errors));
            }
        } else {
            Set<String> existingCodes = mockSupplierDb.values().stream()
                    .map(m -> String.valueOf(m.get("code")).toLowerCase())
                    .collect(Collectors.toSet());

            int row = 1;
            for (Map<String, Object> r : records) {
                String name = (String) r.get("name");
                String code = (String) r.get("code");
                List<String> errors = new ArrayList<>();

                if (name == null || name.trim().isEmpty()) errors.add("Name is required");

                boolean isDup = false;
                String dupId = null;
                if (errors.isEmpty() && code != null && existingCodes.contains(code.toLowerCase())) {
                    isDup = true;
                    dupId = "MATCH-CODE";
                }

                if (!errors.isEmpty()) invalid++;
                else if (isDup) dups++;
                else valid++;

                previewItems.add(new ImportPreviewItemDTO(row++, errors.isEmpty(), isDup, dupId, errors));
            }
        }

        return new ImportPreviewResultDTO(type, records.size(), valid, dups, invalid, previewItems);
    }

    public static class ImportPreviewItemDTO {
        public final int rowNumber;
        public final boolean isValid;
        public final boolean isDuplicate;
        public final String duplicateMatchId;
        public final List<String> errors;

        public ImportPreviewItemDTO(int rowNumber, boolean isValid, boolean isDuplicate, String duplicateMatchId, List<String> errors) {
            this.rowNumber = rowNumber;
            this.isValid = isValid;
            this.isDuplicate = isDuplicate;
            this.duplicateMatchId = duplicateMatchId;
            this.errors = errors;
        }
    }

    public static class ImportPreviewResultDTO {
        public final String partyType;
        public final int totalRecords;
        public final int validCount;
        public final int duplicateCount;
        public final int invalidCount;
        public final List<ImportPreviewItemDTO> previewItems;

        public ImportPreviewResultDTO(String partyType, int totalRecords, int validCount, int duplicateCount, int invalidCount, List<ImportPreviewItemDTO> previewItems) {
            this.partyType = partyType;
            this.totalRecords = totalRecords;
            this.validCount = validCount;
            this.duplicateCount = duplicateCount;
            this.invalidCount = invalidCount;
            this.previewItems = previewItems;
        }
    }
}
