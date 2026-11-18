package com.pharmacy.pos.finance;

import java.time.Instant;
import java.util.*;

/**
 * PosDoubleEntryJournalClient
 * Architecture Decision: ADR-0282 (P282).
 * Phase 15: General Ledger, Operating Expenses & Profitability.
 *
 * JavaFX Desktop POS client integrating the General Ledger double-entry posting engine:
 * - Balanced journal entry validation (total debits == total credits).
 * - Multi-leg debit/credit line processing.
 * - Compensating reversal workflows with audit trails.
 */
public class PosDoubleEntryJournalClient {

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "AUDITOR", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> POST_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final Map<String, Map<String, Object>> mockJournals = new HashMap<>();
    private final Map<String, Double> mockAccountBalances = new HashMap<>();

    public PosDoubleEntryJournalClient(String tenantId, String branchId, String userRole, String userId) {
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

    private void assertViewAuthorized() {
        if (!isViewAuthorized()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashier and staff pharmacist cannot view General Ledger journals.");
        }
    }

    private void assertPostAuthorized() {
        if (!isPostAuthorized()) {
            throw new SecurityException("ERR_JOURNAL_POST_UNAUTHORIZED: Only Pharmacy Owner or Finance Officer can post or reverse General Ledger journals.");
        }
    }

    public void setAccountBalance(String accountCode, double balance) {
        mockAccountBalances.put(accountCode, balance);
    }

    public double getAccountBalance(String accountCode) {
        return mockAccountBalances.getOrDefault(accountCode, 0.0);
    }

    public Map<String, Object> postJournalEntry(
            List<Map<String, Object>> lines,
            String narration,
            String sourceModule,
            String sourceReference,
            String idempotencyKey
    ) {
        assertPostAuthorized();

        if (lines == null || lines.size() < 2) {
            throw new IllegalArgumentException("ERR_MINIMUM_LINES_REQUIRED: A double-entry journal requires at least two lines.");
        }

        double totalDebits = 0.0;
        double totalCredits = 0.0;

        for (Map<String, Object> l : lines) {
            double dr = (double) l.getOrDefault("debit_amount_bdt", 0.0);
            double cr = (double) l.getOrDefault("credit_amount_bdt", 0.0);
            totalDebits += dr;
            totalCredits += cr;
        }

        totalDebits = Math.round(totalDebits * 100.0) / 100.0;
        totalCredits = Math.round(totalCredits * 100.0) / 100.0;

        if (totalDebits != totalCredits || totalDebits <= 0.0) {
            throw new IllegalStateException("ERR_UNBALANCED_JOURNAL_ENTRY: Total Debits (" + totalDebits + ") does not equal Total Credits (" + totalCredits + ").");
        }

        String journalId = "jrn-" + UUID.randomUUID().toString().substring(0, 8);
        String journalNumber = "JRN-" + System.currentTimeMillis();

        Map<String, Object> entry = new HashMap<>();
        entry.put("entry_id", journalId);
        entry.put("journal_number", journalNumber);
        entry.put("source_module", sourceModule != null ? sourceModule : "MANUAL");
        entry.put("source_reference", sourceReference != null ? sourceReference : "");
        entry.put("narration", narration != null ? narration : "");
        entry.put("status", "POSTED");
        entry.put("total_debit_bdt", String.format(Locale.US, "%.2f", totalDebits));
        entry.put("total_credit_bdt", String.format(Locale.US, "%.2f", totalCredits));
        entry.put("is_balanced", true);
        entry.put("lines", lines);
        entry.put("posted_at", Instant.now().toString());

        // Update balances
        for (Map<String, Object> l : lines) {
            String code = (String) l.get("account_code");
            double dr = (double) l.getOrDefault("debit_amount_bdt", 0.0);
            double cr = (double) l.getOrDefault("credit_amount_bdt", 0.0);
            double prev = mockAccountBalances.getOrDefault(code, 0.0);
            mockAccountBalances.put(code, Math.round((prev + (dr - cr)) * 100.0) / 100.0);
        }

        mockJournals.put(journalId, entry);

        Map<String, Object> res = new HashMap<>(entry);
        res.put("success", true);
        return res;
    }

    public Map<String, Object> reverseJournalEntry(String journalId, String reason) {
        assertPostAuthorized();
        Map<String, Object> orig = mockJournals.get(journalId);
        if (orig == null) throw new NoSuchElementException("ERR_JOURNAL_NOT_FOUND: Journal not found.");

        if ("REVERSED".equalsIgnoreCase((String) orig.get("status"))) {
            throw new IllegalStateException("ERR_ALREADY_REVERSED: Journal entry already reversed.");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> origLines = (List<Map<String, Object>>) orig.get("lines");
        List<Map<String, Object>> revLines = new ArrayList<>();

        for (Map<String, Object> l : origLines) {
            Map<String, Object> rl = new HashMap<>();
            String code = (String) l.get("account_code");
            double dr = (double) l.getOrDefault("debit_amount_bdt", 0.0);
            double cr = (double) l.getOrDefault("credit_amount_bdt", 0.0);
            rl.put("account_code", code);
            rl.put("debit_amount_bdt", cr);
            rl.put("credit_amount_bdt", dr);
            revLines.add(rl);

            // Re-adjust
            double prev = mockAccountBalances.getOrDefault(code, 0.0);
            mockAccountBalances.put(code, Math.round((prev + (cr - dr)) * 100.0) / 100.0);
        }

        orig.put("status", "REVERSED");
        orig.put("reversal_reason", reason);

        String revId = "rev-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("original_journal_id", journalId);
        res.put("reversal_journal_id", revId);
        res.put("status", "POSTED");
        res.put("lines", revLines);
        res.put("message_en", "Journal successfully reversed.");
        return res;
    }

    public List<Map<String, Object>> listJournals() {
        assertViewAuthorized();
        return new ArrayList<>(mockJournals.values());
    }
}
