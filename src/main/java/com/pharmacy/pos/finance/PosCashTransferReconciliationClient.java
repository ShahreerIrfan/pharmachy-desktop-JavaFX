package com.pharmacy.pos.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * PosCashTransferReconciliationClient
 * Architecture Decision: ADR-0290 (P290).
 * Phase 15: General Ledger, Operating Expenses & Profitability.
 *
 * JavaFX Desktop POS client integrating Treasury Transfers and Bank Reconciliations:
 * - Internal fund movement between Cash registers/tills and Bank/MFS accounts.
 * - Invariant: Movements between Asset accounts (Dr Dest Asset, Cr Source Asset).
 *   ZERO impact on Revenue (4000s) and ZERO impact on Operating Expenses (6000s).
 * - Bank statement reconciliation comparing statement closing balances against GL book balance.
 * - Multilingual EN/BN feedback and role enforcement.
 */
public class PosCashTransferReconciliationClient {

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "AUDITOR", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> TRANSFER_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> RECONCILE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final Map<String, Double> mockAccountBalances = new HashMap<>();
    private final List<Map<String, Object>> mockTransfers = new ArrayList<>();
    private final List<Map<String, Object>> mockReconciliations = new ArrayList<>();

    public PosCashTransferReconciliationClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
    }

    public boolean isViewAuthorized() {
        return VIEW_ROLES.contains(this.userRole);
    }

    public boolean isTransferAuthorized() {
        return TRANSFER_ROLES.contains(this.userRole);
    }

    public boolean isReconcileAuthorized() {
        return RECONCILE_ROLES.contains(this.userRole);
    }

    public void setAccountBalance(String accountId, double balance) {
        mockAccountBalances.put(accountId, balance);
    }

    public double getAccountBalance(String accountId) {
        return mockAccountBalances.getOrDefault(accountId, 0.0);
    }

    public Map<String, Object> executeTransfer(
            String sourceAccountId,
            String sourceAccountName,
            String destAccountId,
            String destAccountName,
            double amountBdt,
            String referenceNumber,
            String notes
    ) {
        if (!isTransferAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_TREASURY_MANAGE_UNAUTHORIZED",
                    "message_en", "Unauthorized to execute treasury fund transfer.",
                    "message_bn", "ট্রেজারি ফান্ড ট্রান্সফার করার অনুমতি নেই।"
            );
        }

        if (sourceAccountId == null || destAccountId == null || sourceAccountId.trim().isEmpty() || destAccountId.trim().isEmpty()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_MISSING_ACCOUNTS",
                    "message_en", "Both source and destination accounts are required.",
                    "message_bn", "উৎস এবং গন্তব্য উভয় অ্যাকাউন্ট আবশ্যক।"
            );
        }

        if (sourceAccountId.trim().equalsIgnoreCase(destAccountId.trim())) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_SAME_ACCOUNT_TRANSFER",
                    "message_en", "Source and destination accounts must be different.",
                    "message_bn", "উৎস এবং গন্তব্য অ্যাকাউন্ট ভিন্ন হতে হবে।"
            );
        }

        if (amountBdt <= 0) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_INVALID_TRANSFER_AMOUNT",
                    "message_en", "Transfer amount must be greater than zero.",
                    "message_bn", "ট্রান্সফারের পরিমাণ অবশ্যই শূন্যের চেয়ে বেশি হতে হবে।"
            );
        }

        double srcBal = mockAccountBalances.getOrDefault(sourceAccountId, 0.0);
        double dstBal = mockAccountBalances.getOrDefault(destAccountId, 0.0);

        mockAccountBalances.put(sourceAccountId, srcBal - amountBdt);
        mockAccountBalances.put(destAccountId, dstBal + amountBdt);

        String transferId = "CBTRX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String transferNumber = "TRF-" + LocalDate.now().toString().replace("-", "").substring(0, 6) + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        String journalNum = "JRN-TRF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Map<String, Object> tx = new HashMap<>();
        tx.put("transfer_id", transferId);
        tx.put("transfer_number", transferNumber);
        tx.put("tenant_id", tenantId);
        tx.put("branch_id", branchId);
        tx.put("source_account_id", sourceAccountId);
        tx.put("source_account_name", sourceAccountName != null ? sourceAccountName : "Cash Till");
        tx.put("destination_account_id", destAccountId);
        tx.put("destination_account_name", destAccountName != null ? destAccountName : "Bank Account");
        tx.put("amount_bdt", String.format(Locale.US, "%.2f", amountBdt));
        tx.put("transfer_date", LocalDate.now().toString());
        tx.put("reference_number", referenceNumber != null ? referenceNumber.trim() : "");
        tx.put("status", "COMPLETED");
        tx.put("posted_journal_number", journalNum);
        tx.put("created_by", userId);
        tx.put("created_at", Instant.now().toString());

        mockTransfers.add(tx);

        return Map.of(
                "status", "SUCCESS",
                "transfer", tx,
                "message_en", "Treasury fund transfer executed and posted to General Ledger.",
                "message_bn", "ট্রেজারি ফান্ড ট্রান্সফার সম্পন্ন এবং জেনারেল লেজারে পোস্ট করা হয়েছে।"
        );
    }

    public Map<String, Object> createReconciliationBatch(
            String bankAccountId,
            String bankAccountName,
            double statementEndingBalance,
            String statementDate,
            String notes
    ) {
        if (!isReconcileAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_RECONCILIATION_UNAUTHORIZED",
                    "message_en", "Unauthorized to perform bank reconciliation.",
                    "message_bn", "ব্যাংক রিকনসিলিয়েশন করার অনুমতি নেই।"
            );
        }

        if (bankAccountId == null || bankAccountId.trim().isEmpty()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_MISSING_BANK_ACCOUNT",
                    "message_en", "Bank account ID is required.",
                    "message_bn", "ব্যাংক অ্যাকাউন্ট আইডি আবশ্যক।"
            );
        }

        double bookBalance = mockAccountBalances.getOrDefault(bankAccountId, 0.0);
        double diff = statementEndingBalance - bookBalance;
        String status = Math.abs(diff) < 0.001 ? "RECONCILED" : "DISCREPANCY";

        String recId = "BREC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String batchNum = "BREC-" + LocalDate.now().toString().replace("-", "").substring(0, 6) + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();

        Map<String, Object> rec = new HashMap<>();
        rec.put("reconciliation_id", recId);
        rec.put("batch_number", batchNum);
        rec.put("tenant_id", tenantId);
        rec.put("branch_id", branchId);
        rec.put("bank_account_id", bankAccountId);
        rec.put("bank_account_name", bankAccountName != null ? bankAccountName : "Commercial Bank A/C");
        rec.put("statement_date", statementDate != null ? statementDate : LocalDate.now().toString());
        rec.put("statement_ending_balance", String.format(Locale.US, "%.2f", statementEndingBalance));
        rec.put("book_balance", String.format(Locale.US, "%.2f", bookBalance));
        rec.put("difference_bdt", String.format(Locale.US, "%.2f", diff));
        rec.put("status", status);
        rec.put("reconciled_by", "RECONCILED".equals(status) ? userId : null);
        rec.put("reconciled_at", "RECONCILED".equals(status) ? Instant.now().toString() : null);
        rec.put("created_by", userId);
        rec.put("created_at", Instant.now().toString());

        mockReconciliations.add(rec);

        return Map.of(
                "status", "SUCCESS",
                "reconciliation", rec,
                "message_en", "Bank reconciliation batch processed.",
                "message_bn", "ব্যাংক রিকনসিলিয়েশন ব্যাচ সম্পন্ন হয়েছে।"
        );
    }
}
