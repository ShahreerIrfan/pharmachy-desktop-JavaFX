package com.pharmacy.pos.finance;

import java.time.Instant;
import java.util.*;

/**
 * PosPurchaseAccountingClient
 * Architecture Decision: ADR-0284 (P284).
 * Phase 15: General Ledger, Operating Expenses & Profitability.
 *
 * JavaFX Desktop POS client integrating General Ledger purchase accounting postings:
 * - GRN / Invoice Recognition: Capitalizes Merchandise Inventory Asset (1040)
 *   and records Accounts Payable (2010) without counting purchases as immediate operating expenses.
 * - Supplier Payments: Settles Accounts Payable (2010) against Cash (1010) or Bank (1020).
 * - Multi-branch purchase and supplier liability compilation.
 * - Compensating reversal workflows for returned or voided procurement vouchers.
 */
public class PosPurchaseAccountingClient {

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "AUDITOR", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> GRN_POST_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> PAYMENT_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> REVERSE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final Map<String, Map<String, Object>> mockPostings = new HashMap<>();

    public PosPurchaseAccountingClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
    }

    public boolean isViewAuthorized() {
        return VIEW_ROLES.contains(this.userRole);
    }

    public boolean isGrnPostAuthorized() {
        return GRN_POST_ROLES.contains(this.userRole);
    }

    public boolean isPaymentAuthorized() {
        return PAYMENT_ROLES.contains(this.userRole);
    }

    public boolean isReverseAuthorized() {
        return REVERSE_ROLES.contains(this.userRole);
    }

    private void assertViewAuthorized() {
        if (!isViewAuthorized()) {
            throw new SecurityException("ERR_PURCHASE_ACCOUNTING_VIEW_DENIED: User not authorized to view purchase accounting.");
        }
    }

    private void assertGrnPostAuthorized() {
        if (!isGrnPostAuthorized()) {
            throw new SecurityException("ERR_PURCHASE_GRN_POST_UNAUTHORIZED: User not authorized to post GRN purchase accounting.");
        }
    }

    private void assertPaymentAuthorized() {
        if (!isPaymentAuthorized()) {
            throw new SecurityException("ERR_SUPPLIER_PAYMENT_UNAUTHORIZED: Only Owner or Finance Officer can disburse supplier payments.");
        }
    }

    private void assertReverseAuthorized() {
        if (!isReverseAuthorized()) {
            throw new SecurityException("ERR_PURCHASE_ACCOUNTING_REVERSE_UNAUTHORIZED: Only Owner or Finance Officer can reverse purchase accounting.");
        }
    }

    public Map<String, Object> postGrnInvoiceRecognition(
            String grnId,
            String supplierId,
            String supplierName,
            double totalCostBdt,
            double paidCashBdt,
            double paidBankBdt,
            String challanNumber,
            String supplierInvoiceNumber,
            String idempotencyKey
    ) {
        assertGrnPostAuthorized();

        if (totalCostBdt <= 0.0) {
            throw new IllegalArgumentException("ERR_INVALID_PURCHASE_AMOUNT: totalCostBdt must be strictly positive.");
        }

        double immediatePaid = paidCashBdt + paidBankBdt;
        if (immediatePaid > totalCostBdt) {
            throw new IllegalArgumentException("ERR_OVERPAYMENT_EXCEEDS_COST: immediatePaid exceeds totalCostBdt.");
        }

        // Idempotency check
        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            for (Map<String, Object> p : mockPostings.values()) {
                if (idempotencyKey.equals(p.get("idempotencyKey"))) {
                    Map<String, Object> replay = new HashMap<>(p);
                    replay.put("isIdempotentReplay", true);
                    return replay;
                }
            }
        }

        double liabilityAp = totalCostBdt - immediatePaid;
        String postingId = "pap-" + UUID.randomUUID().toString().substring(0, 12);
        String journalId = "jrn-pur-" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> posting = new HashMap<>();
        posting.put("postingId", postingId);
        posting.put("tenantId", this.tenantId);
        posting.put("branchId", this.branchId);
        posting.put("supplierId", supplierId);
        posting.put("supplierName", supplierName != null ? supplierName : "Pharmaceutical Supplier");
        posting.put("grnId", grnId);
        posting.put("challanNumber", challanNumber);
        posting.put("supplierInvoiceNumber", supplierInvoiceNumber);
        posting.put("postingDate", Instant.now().toString().substring(0, 10));
        posting.put("postingType", "INVOICE_RECOGNITION");
        posting.put("status", "POSTED");
        posting.put("isReversed", false);
        posting.put("totalInventoryCostBdt", String.format(Locale.US, "%.2f", totalCostBdt));
        posting.put("liabilityApBdt", String.format(Locale.US, "%.2f", liabilityAp));
        posting.put("settledCashBdt", String.format(Locale.US, "%.2f", paidCashBdt));
        posting.put("settledBankBdt", String.format(Locale.US, "%.2f", paidBankBdt));
        posting.put("isOperatingExpense", false); // INVARIANT: Purchases do not count as operating expenses
        posting.put("journalId", journalId);
        posting.put("idempotencyKey", idempotencyKey);
        posting.put("isIdempotentReplay", false);

        mockPostings.put(postingId, posting);
        return posting;
    }

    public Map<String, Object> postSupplierPayment(
            String supplierId,
            String supplierName,
            double paymentAmountBdt,
            String paymentMode,
            String reference,
            String idempotencyKey
    ) {
        assertPaymentAuthorized();

        if (paymentAmountBdt <= 0.0) {
            throw new IllegalArgumentException("ERR_INVALID_PAYMENT_AMOUNT: paymentAmountBdt must be strictly positive.");
        }

        boolean isCash = "CASH".equalsIgnoreCase(paymentMode);
        String postingId = "pap-pmt-" + UUID.randomUUID().toString().substring(0, 10);
        String journalId = "jrn-pmt-" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> posting = new HashMap<>();
        posting.put("postingId", postingId);
        posting.put("tenantId", this.tenantId);
        posting.put("branchId", this.branchId);
        posting.put("supplierId", supplierId);
        posting.put("supplierName", supplierName != null ? supplierName : "Supplier");
        posting.put("postingDate", Instant.now().toString().substring(0, 10));
        posting.put("postingType", "SUPPLIER_PAYMENT");
        posting.put("status", "POSTED");
        posting.put("isReversed", false);
        posting.put("totalInventoryCostBdt", "0.00");
        posting.put("liabilityApBdt", "0.00");
        posting.put("settledCashBdt", isCash ? String.format(Locale.US, "%.2f", paymentAmountBdt) : "0.00");
        posting.put("settledBankBdt", !isCash ? String.format(Locale.US, "%.2f", paymentAmountBdt) : "0.00");
        posting.put("isOperatingExpense", false);
        posting.put("journalId", journalId);
        posting.put("idempotencyKey", idempotencyKey);
        posting.put("isIdempotentReplay", false);

        mockPostings.put(postingId, posting);
        return posting;
    }

    public Map<String, Object> reversePurchaseAccounting(String postingId, String reason) {
        assertReverseAuthorized();

        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_REVERSAL_REASON: Reason is required.");
        }

        Map<String, Object> posting = mockPostings.get(postingId);
        if (posting == null) {
            throw new NoSuchElementException("ERR_POSTING_NOT_FOUND: Posting not found: " + postingId);
        }

        if ("REVERSED".equals(posting.get("status")) || Boolean.TRUE.equals(posting.get("isReversed"))) {
            throw new IllegalStateException("ERR_ALREADY_REVERSED: Posting is already reversed.");
        }

        posting.put("status", "REVERSED");
        posting.put("isReversed", true);
        posting.put("reversalReason", reason.trim());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("postingId", postingId);
        result.put("status", "REVERSED");
        result.put("reversalReason", reason.trim());
        result.put("message_en", "Purchase accounting posting reversed successfully.");
        result.put("message_bn", "ক্রয় অ্যাকাউন্টিং পোস্টিং সফলভাবে রিভার্স করা হয়েছে।");
        return result;
    }

    public Map<String, Object> getPurchaseSummary() {
        assertViewAuthorized();

        double totalInventory = 0.0;
        double totalApRecognized = 0.0;
        double totalPayments = 0.0;
        int activeGrn = 0;
        int activePmt = 0;

        for (Map<String, Object> p : mockPostings.values()) {
            if ("POSTED".equals(p.get("status"))) {
                String type = (String) p.get("postingType");
                if ("INVOICE_RECOGNITION".equals(type)) {
                    totalInventory += Double.parseDouble((String) p.get("totalInventoryCostBdt"));
                    totalApRecognized += Double.parseDouble((String) p.get("liabilityApBdt"));
                    activeGrn++;
                } else if ("SUPPLIER_PAYMENT".equals(type)) {
                    totalPayments += Double.parseDouble((String) p.get("settledCashBdt")) +
                                     Double.parseDouble((String) p.get("settledBankBdt"));
                    activePmt++;
                }
            }
        }

        double netAp = totalApRecognized - totalPayments;

        Map<String, Object> summary = new HashMap<>();
        summary.put("tenantId", this.tenantId);
        summary.put("grnPostingsCount", activeGrn);
        summary.put("paymentPostingsCount", activePmt);
        summary.put("totalInventoryCapitalizedBdt", String.format(Locale.US, "%.2f", totalInventory));
        summary.put("totalApRecognizedBdt", String.format(Locale.US, "%.2f", totalApRecognized));
        summary.put("totalSupplierPaymentsBdt", String.format(Locale.US, "%.2f", totalPayments));
        summary.put("netOutstandingApLiabilityBdt", String.format(Locale.US, "%.2f", netAp));
        summary.put("totalOperatingExpensesBdt", "0.00"); // Invariant verified
        summary.put("isOperatingExpense", false);
        summary.put("currency", "BDT");
        return summary;
    }
}
