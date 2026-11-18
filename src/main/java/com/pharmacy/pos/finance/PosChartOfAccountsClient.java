package com.pharmacy.pos.finance;

import java.util.*;

/**
 * PosChartOfAccountsClient
 * Architecture Decision: ADR-0281 (P281).
 * Phase 15: General Ledger, Operating Expenses & Profitability.
 *
 * JavaFX Desktop POS client integrating tenant-specific General Ledger (GL) Chart of Accounts (COA):
 * - Cash, bank, AR receivables, merchandise inventory asset, AP payables, and operating expenses.
 * - Idempotent standard accounts seeding, code uniqueness, trial balance generation, and RBAC guards.
 */
public class PosChartOfAccountsClient {

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "AUDITOR", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> MANAGE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final Map<String, Map<String, Object>> accountDatabase = new HashMap<>();

    public PosChartOfAccountsClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
    }

    public boolean isViewAuthorized() {
        return VIEW_ROLES.contains(this.userRole);
    }

    public boolean isManageAuthorized() {
        return MANAGE_ROLES.contains(this.userRole);
    }

    private void assertViewAuthorized() {
        if (!isViewAuthorized()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashier and staff pharmacist are not authorized to access Chart of Accounts.");
        }
    }

    private void assertManageAuthorized() {
        if (!isManageAuthorized()) {
            throw new SecurityException("ERR_COA_MANAGE_UNAUTHORIZED: Only Pharmacy Owner or Finance Officer can create or modify Chart of Accounts.");
        }
    }

    public int initializeDefaultAccounts() {
        assertViewAuthorized();
        String[][] standardAccounts = {
                {"1010", "Cash on Hand / Till Cash", "হাতে নগদ ক্যাশ", "ASSET", "DEBIT", "true"},
                {"1020", "Bank & MFS Clearing Accounts", "ব্যাংক ও এমএফএস ক্লিয়ারিং হিসাব", "ASSET", "DEBIT", "true"},
                {"1030", "Accounts Receivable (Customer Dues)", "গ্রাহক দেনা / প্রাপ্য হিসাব (AR)", "ASSET", "DEBIT", "true"},
                {"1040", "Merchandise Inventory Asset", "ফার্মেসি মজুদ ওষুধ সম্পদ", "ASSET", "DEBIT", "true"},
                {"2010", "Accounts Payable (Supplier Dues)", "সরবরাহকারী পাওনা হিসাব (AP)", "LIABILITY", "CREDIT", "true"},
                {"2030", "NBR VAT & Tax Payable", "এনবিআর মূসক / ভ্যাট প্রদেয়", "LIABILITY", "CREDIT", "true"},
                {"3010", "Owner Equity & Paid-in Capital", "মালিকের মূলধন ও ইকুইটি", "EQUITY", "CREDIT", "true"},
                {"4010", "Medicine Sales Revenue", "ওষুধ বিক্রয় আয়", "REVENUE", "CREDIT", "true"},
                {"5010", "Cost of Goods Sold (COGS - Medicine)", "বিক্রীত ওষুধের ক্রয় ব্যয় (COGS)", "COGS", "DEBIT", "true"},
                {"6010", "Branch Rent Expense", "দোকান ভাড়া খরচ", "EXPENSE", "DEBIT", "false"},
                {"6020", "Electricity & Cold Chain Utilities", "বিদ্যুৎ ও কোল্ড-চেইন খরচ", "EXPENSE", "DEBIT", "false"},
                {"6030", "Staff Salaries & Pharmacist Compensation", "স্টাফ বেতন খরচ", "EXPENSE", "DEBIT", "false"},
        };

        int created = 0;
        for (String[] def : standardAccounts) {
            String code = def[0];
            if (!accountDatabase.containsKey(code)) {
                Map<String, Object> acc = new HashMap<>();
                acc.put("account_id", "coa-" + code);
                acc.put("account_code", code);
                acc.put("account_name", def[1]);
                acc.put("account_name_bn", def[2]);
                acc.put("category", def[3]);
                acc.put("normal_balance", def[4]);
                acc.put("is_system_account", Boolean.parseBoolean(def[5]));
                acc.put("current_balance_bdt", 0.0);
                acc.put("status", "ACTIVE");
                accountDatabase.put(code, acc);
                created++;
            }
        }
        return created;
    }

    public Map<String, Object> createAccount(String code, String name, String category, String normalBalance, String description) {
        assertManageAuthorized();
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_ACCOUNT_CODE: Account code is required.");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_ACCOUNT_NAME: Account name is required.");
        }
        String cleanCode = code.trim();
        if (accountDatabase.containsKey(cleanCode)) {
            throw new IllegalStateException("ERR_DUPLICATE_ACCOUNT_CODE: Account code '" + cleanCode + "' already exists.");
        }

        String cat = category != null ? category.toUpperCase() : "EXPENSE";
        String norm = normalBalance != null ? normalBalance.toUpperCase() : ("ASSET".equals(cat) || "COGS".equals(cat) || "EXPENSE".equals(cat) ? "DEBIT" : "CREDIT");

        Map<String, Object> acc = new HashMap<>();
        String accId = "coa-" + cleanCode;
        acc.put("account_id", accId);
        acc.put("account_code", cleanCode);
        acc.put("account_name", name.trim());
        acc.put("category", cat);
        acc.put("normal_balance", norm);
        acc.put("is_system_account", false);
        acc.put("current_balance_bdt", 0.0);
        acc.put("status", "ACTIVE");
        acc.put("description", description != null ? description : "");
        accountDatabase.put(cleanCode, acc);

        Map<String, Object> res = new HashMap<>(acc);
        res.put("success", true);
        return res;
    }

    public Map<String, Object> archiveAccount(String code) {
        assertManageAuthorized();
        Map<String, Object> acc = accountDatabase.get(code);
        if (acc == null) throw new NoSuchElementException("ERR_ACCOUNT_NOT_FOUND: Account not found.");

        boolean isSystem = (boolean) acc.getOrDefault("is_system_account", false);
        if (isSystem) {
            throw new IllegalStateException("ERR_SYSTEM_ACCOUNT_IMMUTABLE: Standard system accounts cannot be archived.");
        }

        double bal = (double) acc.getOrDefault("current_balance_bdt", 0.0);
        if (bal != 0.0) {
            throw new IllegalStateException("ERR_NONZERO_BALANCE_ACCOUNT: Cannot archive account with non-zero balance.");
        }

        acc.put("status", "ARCHIVED");
        Map<String, Object> res = new HashMap<>(acc);
        res.put("success", true);
        return res;
    }

    public List<Map<String, Object>> listAccounts(String category, String status) {
        assertViewAuthorized();
        if (accountDatabase.isEmpty()) {
            initializeDefaultAccounts();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> acc : accountDatabase.values()) {
            if (category != null && !category.equalsIgnoreCase((String) acc.get("category"))) continue;
            if (status != null && !status.equalsIgnoreCase((String) acc.get("status"))) continue;
            result.add(new HashMap<>(acc));
        }
        result.sort(Comparator.comparing(a -> (String) a.get("account_code")));
        return result;
    }

    public Map<String, Object> getTrialBalance() {
        assertViewAuthorized();
        if (accountDatabase.isEmpty()) {
            initializeDefaultAccounts();
        }
        double totalDebits = 0.0;
        double totalCredits = 0.0;

        for (Map<String, Object> acc : accountDatabase.values()) {
            if (!"ACTIVE".equalsIgnoreCase((String) acc.get("status"))) continue;
            double bal = (double) acc.getOrDefault("current_balance_bdt", 0.0);
            String norm = (String) acc.get("normal_balance");
            if ("DEBIT".equalsIgnoreCase(norm)) totalDebits += bal;
            else totalCredits += bal;
        }

        double variance = Math.abs(totalDebits - totalCredits);
        Map<String, Object> tb = new HashMap<>();
        tb.put("total_debits_bdt", String.format(Locale.US, "%.2f", totalDebits));
        tb.put("total_credits_bdt", String.format(Locale.US, "%.2f", totalCredits));
        tb.put("variance_bdt", String.format(Locale.US, "%.2f", variance));
        tb.put("is_balanced", variance == 0.0);
        return tb;
    }
}
