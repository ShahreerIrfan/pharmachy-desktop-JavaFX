package com.pharmacy.pos.finance;

import java.time.Instant;
import java.util.*;

/**
 * PosSalesAccountingClient
 * Architecture Decision: ADR-0283 (P283).
 * Phase 15: General Ledger, Operating Expenses & Profitability.
 *
 * JavaFX Desktop POS client integrating General Ledger sales accounting postings:
 * - Posts atomic revenue settlements, AR, VAT, discount, and lot COGS.
 * - Computes real-time gross profit and gross margin percentage.
 * - Compensating reversal workflows for refunded / cancelled orders.
 * - Multi-language (EN/BN) and offline/permission states.
 */
public class PosSalesAccountingClient {

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "AUDITOR", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> POST_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "CASHIER", "STAFF_PHARMACIST", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> REVERSE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final Map<String, Map<String, Object>> mockPostings = new HashMap<>();

    public PosSalesAccountingClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
    }

    public boolean isViewAuthorized() {
        return VIEW_ROLES.contains(this.userRole);
    }

    public boolean isPostAuthorized() {
        return POST_ROLES.contains(this.userRole);
    }

    public boolean isReverseAuthorized() {
        return REVERSE_ROLES.contains(this.userRole);
    }

    private void assertViewAuthorized() {
        if (!isViewAuthorized()) {
            throw new SecurityException("ERR_SALES_ACCOUNTING_VIEW_DENIED: User not authorized to view sales accounting postings.");
        }
    }

    private void assertPostAuthorized() {
        if (!isPostAuthorized()) {
            throw new SecurityException("ERR_SALES_ACCOUNTING_POST_UNAUTHORIZED: User not authorized to post sales accounting entries.");
        }
    }

    private void assertReverseAuthorized() {
        if (!isReverseAuthorized()) {
            throw new SecurityException("ERR_SALES_ACCOUNTING_REVERSE_UNAUTHORIZED: Only Owner or Finance Officer can reverse sales accounting postings.");
        }
    }

    public Map<String, Object> postSaleInvoiceAccounting(
            String invoiceId,
            String invoiceNumber,
            double grossSalesBdt,
            double discountBdt,
            double taxVatBdt,
            double netSettledBdt,
            double receivableDueBdt,
            double totalCogsBdt,
            String idempotencyKey
    ) {
        assertPostAuthorized();

        if (invoiceNumber == null || invoiceNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_INVOICE_NUMBER: invoiceNumber is required.");
        }
        if (grossSalesBdt <= 0.0) {
            throw new IllegalArgumentException("ERR_INVALID_SALE_AMOUNT: grossSalesBdt must be strictly positive.");
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

        double netSales = grossSalesBdt - discountBdt;
        double grossProfit = netSales - totalCogsBdt;
        double marginPct = netSales > 0 ? (grossProfit / netSales) * 100.0 : 0.0;

        String postingId = "sap-" + UUID.randomUUID().toString().substring(0, 12);
        String revJournalId = "jrn-rev-" + UUID.randomUUID().toString().substring(0, 8);
        String cogsJournalId = totalCogsBdt > 0 ? "jrn-cog-" + UUID.randomUUID().toString().substring(0, 8) : null;

        Map<String, Object> posting = new HashMap<>();
        posting.put("postingId", postingId);
        posting.put("tenantId", this.tenantId);
        posting.put("branchId", this.branchId);
        posting.put("invoiceId", invoiceId);
        posting.put("invoiceNumber", invoiceNumber);
        posting.put("postingDate", Instant.now().toString().substring(0, 10));
        posting.put("status", "POSTED");
        posting.put("isReversed", false);
        posting.put("grossSalesBdt", String.format(Locale.US, "%.2f", grossSalesBdt));
        posting.put("discountBdt", String.format(Locale.US, "%.2f", discountBdt));
        posting.put("taxVatBdt", String.format(Locale.US, "%.2f", taxVatBdt));
        posting.put("netSettledBdt", String.format(Locale.US, "%.2f", netSettledBdt));
        posting.put("receivableDueBdt", String.format(Locale.US, "%.2f", receivableDueBdt));
        posting.put("totalCogsBdt", String.format(Locale.US, "%.2f", totalCogsBdt));
        posting.put("grossProfitBdt", String.format(Locale.US, "%.2f", grossProfit));
        posting.put("grossMarginPct", String.format(Locale.US, "%.2f", marginPct));
        posting.put("revenueJournalId", revJournalId);
        posting.put("cogsJournalId", cogsJournalId);
        posting.put("idempotencyKey", idempotencyKey);
        posting.put("isIdempotentReplay", false);

        mockPostings.put(postingId, posting);
        return posting;
    }

    public Map<String, Object> reverseSaleAccounting(String postingId, String reason) {
        assertReverseAuthorized();

        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_REVERSAL_REASON: Reversal explanation reason is mandatory.");
        }

        Map<String, Object> posting = mockPostings.get(postingId);
        if (posting == null) {
            throw new NoSuchElementException("ERR_POSTING_NOT_FOUND: Sales accounting posting not found: " + postingId);
        }

        if ("REVERSED".equals(posting.get("status")) || Boolean.TRUE.equals(posting.get("isReversed"))) {
            throw new IllegalStateException("ERR_ALREADY_REVERSED: Posting is already reversed: " + postingId);
        }

        posting.put("status", "REVERSED");
        posting.put("isReversed", true);
        posting.put("reversalReason", reason.trim());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("postingId", postingId);
        result.put("status", "REVERSED");
        result.put("reversalReason", reason.trim());
        result.put("message_en", "Sales accounting posting reversed successfully.");
        result.put("message_bn", "সেলস অ্যাকাউন্টিং পোস্টিং সফলভাবে রিভার্স করা হয়েছে।");
        return result;
    }

    public Map<String, Object> getProfitabilitySummary() {
        assertViewAuthorized();

        double totalGross = 0.0;
        double totalDiscount = 0.0;
        double totalCogs = 0.0;
        double totalProfit = 0.0;
        int activeCount = 0;

        for (Map<String, Object> p : mockPostings.values()) {
            if ("POSTED".equals(p.get("status"))) {
                totalGross += Double.parseDouble((String) p.get("grossSalesBdt"));
                totalDiscount += Double.parseDouble((String) p.get("discountBdt"));
                totalCogs += Double.parseDouble((String) p.get("totalCogsBdt"));
                totalProfit += Double.parseDouble((String) p.get("grossProfitBdt"));
                activeCount++;
            }
        }

        double netSales = totalGross - totalDiscount;
        double marginPct = netSales > 0 ? (totalProfit / netSales) * 100.0 : 0.0;

        Map<String, Object> summary = new HashMap<>();
        summary.put("tenantId", this.tenantId);
        summary.put("totalSalesCount", activeCount);
        summary.put("grossSalesBdt", String.format(Locale.US, "%.2f", totalGross));
        summary.put("totalDiscountBdt", String.format(Locale.US, "%.2f", totalDiscount));
        summary.put("netSalesBdt", String.format(Locale.US, "%.2f", netSales));
        summary.put("totalCogsBdt", String.format(Locale.US, "%.2f", totalCogs));
        summary.put("grossProfitBdt", String.format(Locale.US, "%.2f", totalProfit));
        summary.put("grossMarginPct", String.format(Locale.US, "%.2f", marginPct));
        summary.put("currency", "BDT");
        return summary;
    }
}
