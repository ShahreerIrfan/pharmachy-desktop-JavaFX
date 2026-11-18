package com.pharmacy.pos.finance;

import java.time.Instant;
import java.util.*;

/**
 * PosControlAccountClient
 * Architecture Decision: ADR-0285 (P285).
 * Phase 15: General Ledger, Operating Expenses & Profitability.
 *
 * JavaFX Desktop POS client integrating General Ledger Control Accounts:
 * - Reconciles Accounts Receivable (1030) against detailed Customer Subledgers.
 * - Reconciles Accounts Payable (2010) against detailed Supplier Subledgers.
 * - Live multi-branch control accounts vs subledgers overview.
 * - Audited balancing adjustment journal workflows.
 * - Multilingual EN/BN user feedback and strict role checks.
 */
public class PosControlAccountClient {

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "AUDITOR", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> ADJUST_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> DENIED_STAFF_ROLES = Set.of(
            "CASHIER", "STAFF_PHARMACIST", "DISPENSER", "INVENTORY_CLERK"
    );

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final Map<String, Map<String, Object>> mockReconRuns = new HashMap<>();

    public PosControlAccountClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
    }

    public boolean isViewAuthorized() {
        return VIEW_ROLES.contains(this.userRole);
    }

    public boolean isAdjustAuthorized() {
        return ADJUST_ROLES.contains(this.userRole);
    }

    public Map<String, Object> reconcileControlAccount(
            String controlAccountType,
            double glBalance,
            double subledgerTotal,
            List<Map<String, Object>> partyDetails
    ) {
        if (!isViewAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_STAFF_ROLE_DENIED",
                    "message_en", "Unauthorized to reconcile control accounts.",
                    "message_bn", "কন্ট্রোল অ্যাকাউন্ট রিকনসিলিয়েশন দেখার অনুমতি নেই।"
            );
        }

        String typeClean = controlAccountType != null ? controlAccountType.toUpperCase().trim() : "";
        if (!typeClean.equals("CUSTOMER_AR") && !typeClean.equals("SUPPLIER_AP")) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_INVALID_CONTROL_ACCOUNT_TYPE",
                    "message_en", "Control account type must be CUSTOMER_AR or SUPPLIER_AP.",
                    "message_bn", "কন্ট্রোল অ্যাকাউন্ট টাইপ অবশ্যই CUSTOMER_AR অথবা SUPPLIER_AP হতে হবে।"
            );
        }

        String runId = "RUN-" + UUID.randomUUID().toString().substring(0, 8);
        double variance = Math.round((glBalance - subledgerTotal) * 100.0) / 100.0;
        boolean isBalanced = Math.abs(variance) < 0.005;
        String status = isBalanced ? "MATCHED" : "DISCREPANCY_FLAGGED";

        Map<String, Object> run = new HashMap<>();
        run.put("run_id", runId);
        run.put("tenant_id", tenantId);
        run.put("branch_id", branchId);
        run.put("control_account_type", typeClean);
        run.put("gl_account_code", typeClean.equals("CUSTOMER_AR") ? "1030" : "2010");
        run.put("gl_control_balance_bdt", String.format(Locale.US, "%.2f", glBalance));
        run.put("subledger_total_balance_bdt", String.format(Locale.US, "%.2f", subledgerTotal));
        run.put("variance_bdt", String.format(Locale.US, "%.2f", variance));
        run.put("is_balanced", isBalanced);
        run.put("party_count", partyDetails != null ? partyDetails.size() : 0);
        run.put("status", status);
        run.put("reconciled_by", userId);
        run.put("created_at", Instant.now().toString());

        mockReconRuns.put(runId, run);

        return Map.of(
                "status", "SUCCESS",
                "run", run,
                "message_en", isBalanced ? "Control account reconciled and perfectly balanced." : "Discrepancy detected and flagged for review.",
                "message_bn", isBalanced ? "কন্ট্রোল অ্যাকাউন্ট সফলভাবে রিকনসাইল এবং ব্যালেন্সড হয়েছে।" : "পার্থক্য শনাক্ত করা হয়েছে এবং পর্যালোচনার জন্য চিহ্নিত করা হয়েছে।"
        );
    }

    public Map<String, Object> applyBalancingAdjustment(
            String runId,
            String reason,
            String contraAccountCode
    ) {
        if (!isAdjustAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_CONTROL_ADJUST_UNAUTHORIZED",
                    "message_en", "Only Pharmacy Owner or Finance Officer can apply balancing adjustments.",
                    "message_bn", "শুধুমাত্র ফার্মেসি ওনার বা ফিন্যান্স অফিসার অ্যাডজাস্টমেন্ট প্রয়োগ করতে পারেন।"
            );
        }

        if (reason == null || reason.trim().isEmpty()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_MISSING_ADJUSTMENT_REASON",
                    "message_en", "A mandatory explanatory reason is required.",
                    "message_bn", "অ্যাডজাস্টমেন্টের জন্য সুনির্দিষ্ট কারণ প্রদান আবশ্যক।"
            );
        }

        Map<String, Object> run = mockReconRuns.get(runId);
        if (run == null) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_RUN_NOT_FOUND",
                    "message_en", "Reconciliation run not found.",
                    "message_bn", "রিকনসিলিয়েশন রান খুঁজে পাওয়া যায়নি।"
            );
        }

        boolean isBalanced = Boolean.TRUE.equals(run.get("is_balanced"));
        if (isBalanced || "RECONCILED".equals(run.get("status"))) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_NO_ADJUSTMENT_NEEDED",
                    "message_en", "Run is already balanced or reconciled.",
                    "message_bn", "এই রিকনসিলিয়েশনটি ইতিমধ্যেই ব্যালেন্সড রয়েছে।"
            );
        }

        String journalNum = "JRN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        run.put("adjustment_journal_number", journalNum);
        run.put("status", "RECONCILED");
        run.put("is_balanced", true);
        run.put("variance_bdt", "0.00");
        run.put("gl_control_balance_bdt", run.get("subledger_total_balance_bdt"));
        run.put("reconciliation_notes", reason.trim());

        return Map.of(
                "status", "SUCCESS",
                "run", run,
                "adjustment_journal_number", journalNum,
                "message_en", "Balancing adjustment journal posted successfully.",
                "message_bn", "ব্যালেন্সিং অ্যাডজাস্টমেন্ট জার্নাল সফলভাবে পোস্ট করা হয়েছে।"
        );
    }

    public Map<String, Object> getControlAccountsOverview(
            double arGl,
            double arSub,
            double apGl,
            double apSub
    ) {
        if (!isViewAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_STAFF_ROLE_DENIED",
                    "message_en", "Unauthorized to view control accounts overview.",
                    "message_bn", "কন্ট্রোল অ্যাকাউন্ট ওভারভিউ দেখার অনুমতি নেই।"
            );
        }

        double arVar = Math.round((arGl - arSub) * 100.0) / 100.0;
        double apVar = Math.round((apGl - apSub) * 100.0) / 100.0;
        boolean arBalanced = Math.abs(arVar) < 0.005;
        boolean apBalanced = Math.abs(apVar) < 0.005;
        boolean allBalanced = arBalanced && apBalanced;

        Map<String, Object> arMap = Map.of(
                "account_code", "1030",
                "account_name", "Accounts Receivable (Customer Dues)",
                "gl_control_balance_bdt", String.format(Locale.US, "%.2f", arGl),
                "subledger_total_bdt", String.format(Locale.US, "%.2f", arSub),
                "variance_bdt", String.format(Locale.US, "%.2f", arVar),
                "is_balanced", arBalanced
        );

        Map<String, Object> apMap = Map.of(
                "account_code", "2010",
                "account_name", "Accounts Payable (Supplier Dues)",
                "gl_control_balance_bdt", String.format(Locale.US, "%.2f", apGl),
                "subledger_total_bdt", String.format(Locale.US, "%.2f", apSub),
                "variance_bdt", String.format(Locale.US, "%.2f", apVar),
                "is_balanced", apBalanced
        );

        return Map.of(
                "status", "SUCCESS",
                "overall_status", allBalanced ? "BALANCED" : "DISCREPANCIES_DETECTED",
                "is_fully_reconciled", allBalanced,
                "accounts_receivable", arMap,
                "accounts_payable", apMap
        );
    }
}
