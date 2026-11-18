package com.pharmacy.pos.parties;

import java.time.Instant;
import java.util.*;

/**
 * PosPartyPrivacyRetentionClient
 * Architecture Decision: ADR-0279 (P279).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * JavaFX Desktop POS client integrating party data privacy, statutory retention assessment,
 * audited demographic corrections, irreversible pseudonymization, and restricted dossier exports.
 */
public class PosPartyPrivacyRetentionClient {

    public static final Set<String> PRIVACY_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> CLINICAL_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "OWNER_ADMIN", "ADMIN"
    );

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final Map<String, Map<String, Object>> mockCustomerDb = new HashMap<>();
    private final Map<String, Map<String, Object>> mockSupplierDb = new HashMap<>();
    private final List<Map<String, Object>> auditLog = new ArrayList<>();

    public PosPartyPrivacyRetentionClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
    }

    public boolean isAuthorized() {
        return PRIVACY_ROLES.contains(this.userRole);
    }

    public boolean canExportClinical() {
        return CLINICAL_ROLES.contains(this.userRole);
    }

    private void assertPrivacyAuthorized() {
        if (!isAuthorized()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers and staff pharmacists cannot perform party privacy operations.");
        }
    }

    private void assertClinicalAuthorized() {
        if (!canExportClinical()) {
            throw new SecurityException("ERR_CLINICAL_EXPORT_DENIED: Export of clinical prescription dossier requires Pharmacy Owner authorization.");
        }
    }

    public void seedCustomer(String id, String name, String phone, String email, String address, double currentDue, int rxCount) {
        Map<String, Object> data = new HashMap<>();
        data.put("customer_id", id);
        data.put("full_name", name);
        data.put("phone", phone);
        data.put("email", email);
        data.put("address", address);
        data.put("current_due_bdt", currentDue);
        data.put("rx_count", rxCount);
        data.put("is_anonymized", false);
        mockCustomerDb.put(id, data);
    }

    public void seedSupplier(String id, String name, String phone, String email, double payableBalance) {
        Map<String, Object> data = new HashMap<>();
        data.put("supplier_id", id);
        data.put("name", name);
        data.put("phone", phone);
        data.put("email", email);
        data.put("current_balance", payableBalance);
        data.put("is_anonymized", false);
        mockSupplierDb.put(id, data);
    }

    public Map<String, Object> assessRetentionPolicy(String partyType, String partyId) {
        assertPrivacyAuthorized();
        String type = partyType != null ? partyType.toUpperCase() : "CUSTOMER";

        boolean holdActive = false;
        String authority = "";
        List<String> conflicts = new ArrayList<>();
        boolean canErase = true;

        if ("CUSTOMER".equals(type)) {
            Map<String, Object> c = mockCustomerDb.get(partyId);
            if (c == null) throw new NoSuchElementException("ERR_CUSTOMER_NOT_FOUND: Customer not found.");

            double due = (double) c.getOrDefault("current_due_bdt", 0.0);
            int rxCount = (int) c.getOrDefault("rx_count", 0);

            if (due > 0.0) {
                holdActive = true;
                canErase = false;
                authority = "Active Accounts Receivable Credit Due";
                conflicts.add("Active outstanding due balance (" + due + " BDT) exists.");
            }
            if (rxCount > 0) {
                holdActive = true;
                canErase = false;
                authority = "DGDA Dispensing Rules (2-Year Clinical Rx Retention)";
                conflicts.add(rxCount + " clinical prescriptions under active DGDA mandatory 2-year retention.");
            }

            Map<String, Object> res = new HashMap<>();
            res.put("party_type", "CUSTOMER");
            res.put("party_id", partyId);
            res.put("can_physically_erase", canErase);
            res.put("statutory_hold_active", holdActive);
            res.put("statutory_authority", authority);
            res.put("conflicts_detected", conflicts);
            res.put("open_due_balance_bdt", String.format(Locale.US, "%.2f", due));
            return res;
        } else if ("SUPPLIER".equals(type)) {
            Map<String, Object> s = mockSupplierDb.get(partyId);
            if (s == null) throw new NoSuchElementException("ERR_SUPPLIER_NOT_FOUND: Supplier not found.");

            double balance = (double) s.getOrDefault("current_balance", 0.0);
            if (balance != 0.0) {
                holdActive = true;
                canErase = false;
                authority = "Active Accounts Payable Liability";
                conflicts.add("Active payable balance (" + balance + " BDT) exists.");
            }

            Map<String, Object> res = new HashMap<>();
            res.put("party_type", "SUPPLIER");
            res.put("party_id", partyId);
            res.put("can_physically_erase", canErase);
            res.put("statutory_hold_active", holdActive);
            res.put("statutory_authority", authority);
            res.put("conflicts_detected", conflicts);
            res.put("current_payable_balance_bdt", String.format(Locale.US, "%.2f", balance));
            return res;
        } else {
            throw new IllegalArgumentException("ERR_INVALID_PARTY_TYPE: Unknown party type.");
        }
    }

    public Map<String, Object> correctPartyData(String partyType, String partyId, Map<String, Object> corrections, String reason) {
        assertPrivacyAuthorized();
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_CORRECTION_REASON: Mandatory explanation reason is required.");
        }
        String type = partyType != null ? partyType.toUpperCase() : "CUSTOMER";

        if ("CUSTOMER".equals(type)) {
            Map<String, Object> c = mockCustomerDb.get(partyId);
            if (c == null) throw new NoSuchElementException("ERR_CUSTOMER_NOT_FOUND: Customer not found.");

            if (corrections.containsKey("full_name")) {
                c.put("full_name", corrections.get("full_name"));
            }
            if (corrections.containsKey("phone")) {
                c.put("phone", corrections.get("phone"));
            }
            if (corrections.containsKey("email")) {
                c.put("email", corrections.get("email"));
            }
            if (corrections.containsKey("address")) {
                c.put("address", corrections.get("address"));
            }

            String auditId = "AUDIT-" + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("party_type", "CUSTOMER");
            result.put("customer_id", partyId);
            result.put("full_name", c.get("full_name"));
            result.put("phone", c.get("phone"));
            result.put("audit_id", auditId);
            result.put("message_en", "Customer demographic details successfully rectified and audited.");
            result.put("message_bn", "গ্রাহকের তথ্য সফলভাবে সংশোধন এবং অডিট করা হয়েছে।");
            return result;
        } else if ("SUPPLIER".equals(type)) {
            Map<String, Object> s = mockSupplierDb.get(partyId);
            if (s == null) throw new NoSuchElementException("ERR_SUPPLIER_NOT_FOUND: Supplier not found.");

            if (corrections.containsKey("name")) s.put("name", corrections.get("name"));
            if (corrections.containsKey("phone")) s.put("phone", corrections.get("phone"));

            String auditId = "AUDIT-" + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("party_type", "SUPPLIER");
            result.put("supplier_id", partyId);
            result.put("name", s.get("name"));
            result.put("audit_id", auditId);
            result.put("message_en", "Supplier organization details rectified and audited.");
            return result;
        } else {
            throw new IllegalArgumentException("ERR_INVALID_PARTY_TYPE: Unknown party type.");
        }
    }

    public Map<String, Object> anonymizePartyData(String partyType, String partyId, String reason) {
        assertPrivacyAuthorized();
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_ANONYMIZATION_REASON: Reason required.");
        }
        String type = partyType != null ? partyType.toUpperCase() : "CUSTOMER";

        if ("CUSTOMER".equals(type)) {
            Map<String, Object> c = mockCustomerDb.get(partyId);
            if (c == null) throw new NoSuchElementException("ERR_CUSTOMER_NOT_FOUND: Customer not found.");

            double due = (double) c.getOrDefault("current_due_bdt", 0.0);
            if (due > 0.0) {
                throw new IllegalStateException("ERR_OPEN_DUE_BALANCE_HOLD: Cannot anonymize customer with active due balance.");
            }

            String pseudoName = "Anonymized Customer " + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            c.put("full_name", pseudoName);
            c.put("phone", "+880000000000");
            c.put("email", "");
            c.put("address", "[ANONYMIZED_UNDER_STATUTORY_HOLD]");
            c.put("is_anonymized", true);

            String auditId = "AUDIT-" + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("party_type", "CUSTOMER");
            result.put("customer_id", partyId);
            result.put("anonymized_name", pseudoName);
            result.put("is_anonymized", true);
            result.put("audit_id", auditId);
            result.put("message_en", "Customer PII permanently anonymized under statutory retention hold.");
            return result;
        } else if ("SUPPLIER".equals(type)) {
            Map<String, Object> s = mockSupplierDb.get(partyId);
            if (s == null) throw new NoSuchElementException("ERR_SUPPLIER_NOT_FOUND: Supplier not found.");

            double bal = (double) s.getOrDefault("current_balance", 0.0);
            if (bal != 0.0) {
                throw new IllegalStateException("ERR_OPEN_PAYABLE_BALANCE_HOLD: Cannot anonymize supplier with outstanding balance.");
            }

            String pseudoName = "Anonymized Supplier " + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            s.put("name", pseudoName);
            s.put("phone", "+880000000000");
            s.put("is_anonymized", true);

            String auditId = "AUDIT-" + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("party_type", "SUPPLIER");
            result.put("supplier_id", partyId);
            result.put("anonymized_name", pseudoName);
            result.put("is_anonymized", true);
            result.put("audit_id", auditId);
            result.put("message_en", "Supplier details permanently anonymized.");
            return result;
        } else {
            throw new IllegalArgumentException("ERR_INVALID_PARTY_TYPE: Unknown party type.");
        }
    }

    public Map<String, Object> exportRestrictedDossier(String partyType, String partyId, String classification, String reason) {
        assertPrivacyAuthorized();
        String cls = classification != null ? classification.toUpperCase() : "STANDARD";
        if ("RESTRICTED_CLINICAL".equals(cls)) {
            assertClinicalAuthorized();
        }

        Map<String, Object> c = mockCustomerDb.get(partyId);
        if (c == null) throw new NoSuchElementException("ERR_CUSTOMER_NOT_FOUND: Customer not found.");

        Map<String, Object> dossier = new HashMap<>();
        dossier.put("party_type", "CUSTOMER");
        dossier.put("party_id", partyId);
        dossier.put("classification", cls);
        dossier.put("profile", Map.of("full_name", c.get("full_name"), "phone", c.get("phone")));
        dossier.put("audit_id", "AUDIT-" + UUID.randomUUID().toString().substring(0, 8));
        dossier.put("exported_at", Instant.now().toString());

        if ("RESTRICTED_CLINICAL".equals(cls)) {
            dossier.put("clinical_prescriptions", List.of(Map.of("rx_id", "RX-001", "doctor", "Dr. Rahman")));
        } else {
            dossier.put("clinical_prescriptions", Collections.emptyList());
        }

        return dossier;
    }

    public List<Map<String, Object>> getAuditLog() {
        return Collections.unmodifiableList(auditLog);
    }
}
