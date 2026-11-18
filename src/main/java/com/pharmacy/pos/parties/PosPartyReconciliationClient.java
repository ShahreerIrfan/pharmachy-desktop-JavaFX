package com.pharmacy.pos.parties;

import java.time.Instant;
import java.util.*;

/**
 * PosPartyReconciliationClient
 * Architecture Decision: ADR-0280 (P280).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * JavaFX Desktop POS client integrating End-to-End Party Reconciliation:
 * - Customer Accounts Receivable (AR) reconciliation against running ledgers.
 * - Supplier Accounts Payable (AP) reconciliation against liability ledgers.
 * - Auto-adjustment authority checks, discrepancy tracking, and audit trails.
 */
public class PosPartyReconciliationClient {

    public static final Set<String> RECON_VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> RECON_ADJUST_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final Map<String, Map<String, Object>> mockCustomerLedgers = new HashMap<>();
    private final Map<String, Map<String, Object>> mockSupplierLedgers = new HashMap<>();

    public PosPartyReconciliationClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
    }

    public boolean isAuthorized() {
        return RECON_VIEW_ROLES.contains(this.userRole);
    }

    public boolean canAutoAdjust() {
        return RECON_ADJUST_ROLES.contains(this.userRole);
    }

    private void assertAuthorized() {
        if (!isAuthorized()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashier and staff pharmacist are not authorized to perform party reconciliation.");
        }
    }

    private void assertAdjustAuthorized() {
        if (!canAutoAdjust()) {
            throw new SecurityException("ERR_AUTO_ADJUST_UNAUTHORIZED: Only Pharmacy Owner or Finance Officer can authorize compensating reconciliation adjustments.");
        }
    }

    public void seedCustomerLedger(String customerId, String name, double sourceInvoices, double collections, double refunds, double recordedDue) {
        Map<String, Object> data = new HashMap<>();
        data.put("customer_id", customerId);
        data.put("name", name);
        data.put("source_invoices", sourceInvoices);
        data.put("collections", collections);
        data.put("refunds", refunds);
        data.put("recorded_due", recordedDue);
        mockCustomerLedgers.put(customerId, data);
    }

    public void seedSupplierLedger(String supplierId, String name, double bills, double payments, double creditNotes, double recordedBalance) {
        Map<String, Object> data = new HashMap<>();
        data.put("supplier_id", supplierId);
        data.put("name", name);
        data.put("bills", bills);
        data.put("payments", payments);
        data.put("credit_notes", creditNotes);
        data.put("recorded_balance", recordedBalance);
        mockSupplierLedgers.put(supplierId, data);
    }

    public Map<String, Object> reconcileCustomerAr(String customerId, boolean autoAdjust, String adjustmentReason) {
        assertAuthorized();
        Map<String, Object> c = mockCustomerLedgers.get(customerId);
        if (c == null) throw new NoSuchElementException("ERR_CUSTOMER_NOT_FOUND: Customer not found.");

        double inv = (double) c.get("source_invoices");
        double col = (double) c.get("collections");
        double ref = (double) c.get("refunds");
        double recorded = (double) c.get("recorded_due");

        double calculated = Math.round((inv - col - ref) * 100.0) / 100.0;
        double variance = Math.round((calculated - recorded) * 100.0) / 100.0;

        boolean isBalanced = (variance == 0.0);
        String status = isBalanced ? "BALANCED" : "DISCREPANCY_DETECTED";
        List<String> discrepancies = new ArrayList<>();

        if (!isBalanced) {
            discrepancies.add("Variance of " + variance + " BDT detected between AR ledger and recorded due.");
        }

        if (autoAdjust && !isBalanced) {
            assertAdjustAuthorized();
            status = "RECONCILED_WITH_ADJUSTMENT";
            discrepancies.add("Auto-adjustment applied: " + adjustmentReason);
            variance = 0.0;
            isBalanced = true;
            c.put("recorded_due", calculated);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("party_type", "CUSTOMER");
        result.put("party_id", customerId);
        result.put("party_name", c.get("name"));
        result.put("status", status);
        result.put("is_balanced", isBalanced);
        result.put("total_source_invoices_bdt", String.format(Locale.US, "%.2f", inv));
        result.put("total_settlements_collections_bdt", String.format(Locale.US, "%.2f", col));
        result.put("total_refunds_credits_bdt", String.format(Locale.US, "%.2f", ref));
        result.put("calculated_balance_bdt", String.format(Locale.US, "%.2f", calculated));
        result.put("recorded_balance_bdt", String.format(Locale.US, "%.2f", recorded));
        result.put("variance_bdt", String.format(Locale.US, "%.2f", variance));
        result.put("discrepancies", discrepancies);
        result.put("reconciliation_id", "prec-" + UUID.randomUUID().toString().substring(0, 8));
        result.put("reconciled_at", Instant.now().toString());
        return result;
    }

    public Map<String, Object> reconcileSupplierAp(String supplierId, boolean autoAdjust, String adjustmentReason) {
        assertAuthorized();
        Map<String, Object> s = mockSupplierLedgers.get(supplierId);
        if (s == null) throw new NoSuchElementException("ERR_SUPPLIER_NOT_FOUND: Supplier not found.");

        double bills = (double) s.get("bills");
        double payments = (double) s.get("payments");
        double credits = (double) s.get("credit_notes");
        double recorded = (double) s.get("recorded_balance");

        double calculated = Math.round((bills - payments - credits) * 100.0) / 100.0;
        double variance = Math.round((calculated - recorded) * 100.0) / 100.0;

        boolean isBalanced = (variance == 0.0);
        String status = isBalanced ? "BALANCED" : "DISCREPANCY_DETECTED";
        List<String> discrepancies = new ArrayList<>();

        if (!isBalanced) {
            discrepancies.add("Variance of " + variance + " BDT detected between AP ledger and recorded balance.");
        }

        if (autoAdjust && !isBalanced) {
            assertAdjustAuthorized();
            status = "RECONCILED_WITH_ADJUSTMENT";
            discrepancies.add("Auto-adjustment applied: " + adjustmentReason);
            variance = 0.0;
            isBalanced = true;
            s.put("recorded_balance", calculated);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("party_type", "SUPPLIER");
        result.put("party_id", supplierId);
        result.put("party_name", s.get("name"));
        result.put("status", status);
        result.put("is_balanced", isBalanced);
        result.put("total_source_invoices_bdt", String.format(Locale.US, "%.2f", bills));
        result.put("total_settlements_collections_bdt", String.format(Locale.US, "%.2f", payments));
        result.put("total_refunds_credits_bdt", String.format(Locale.US, "%.2f", credits));
        result.put("calculated_balance_bdt", String.format(Locale.US, "%.2f", calculated));
        result.put("recorded_balance_bdt", String.format(Locale.US, "%.2f", recorded));
        result.put("variance_bdt", String.format(Locale.US, "%.2f", variance));
        result.put("discrepancies", discrepancies);
        result.put("reconciliation_id", "prec-" + UUID.randomUUID().toString().substring(0, 8));
        result.put("reconciled_at", Instant.now().toString());
        return result;
    }
}
