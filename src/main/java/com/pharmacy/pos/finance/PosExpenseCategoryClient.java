package com.pharmacy.pos.finance;

import java.time.Instant;
import java.util.*;

/**
 * PosExpenseCategoryClient
 * Architecture Decision: ADR-0286 (P286).
 * Phase 15: General Ledger, Operating Expenses & Profitability.
 *
 * JavaFX Desktop POS client integrating owner-managed operating expense categories:
 * - Rent, electricity, staff salaries, internet, transport, maintenance, and miscellaneous costs.
 * - Linked to General Ledger Chart of Accounts (6000-series).
 * - Multi-branch budgeting, tax deductibility, and soft-archival.
 * - Multilingual EN/BN feedback and role enforcement.
 */
public class PosExpenseCategoryClient {

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

    private final Map<String, Map<String, Object>> mockCategories = new HashMap<>();

    public PosExpenseCategoryClient(String tenantId, String branchId, String userRole, String userId) {
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

    public Map<String, Object> createCategory(
            String name,
            String nameBn,
            String code,
            String defaultGlCode,
            double monthlyBudgetBdt,
            boolean taxDeductible,
            String description
    ) {
        if (!isManageAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_EXPENSE_MANAGE_UNAUTHORIZED",
                    "message_en", "Only Pharmacy Owner or Finance Officer can create expense categories.",
                    "message_bn", "শুধুমাত্র ফার্মেসি ওনার বা ফিন্যান্স অফিসার খরচ ক্যাটাগরি তৈরি করতে পারেন।"
            );
        }

        if (name == null || name.trim().isEmpty()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_MISSING_CATEGORY_NAME",
                    "message_en", "Category name is required.",
                    "message_bn", "ক্যাটাগরির নাম আবশ্যক।"
            );
        }

        if (monthlyBudgetBdt < 0) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_INVALID_BUDGET",
                    "message_en", "Monthly budget cannot be negative.",
                    "message_bn", "মাসিক বাজেট ঋণাত্মক হতে পারে না।"
            );
        }

        String catId = "EXPCAT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String catCode = (code != null && !code.trim().isEmpty()) ? code.trim().toUpperCase() : name.replaceAll("[^A-Za-z0-9]", "_").toUpperCase();

        Map<String, Object> cat = new HashMap<>();
        cat.put("category_id", catId);
        cat.put("tenant_id", tenantId);
        cat.put("branch_id", branchId);
        cat.put("name", name.trim());
        cat.put("name_bn", nameBn != null ? nameBn.trim() : "");
        cat.put("code", catCode);
        cat.put("default_gl_code", defaultGlCode != null ? defaultGlCode : "6060");
        cat.put("monthly_budget_bdt", String.format(Locale.US, "%.2f", monthlyBudgetBdt));
        cat.put("tax_deductible", taxDeductible);
        cat.put("description", description != null ? description : "");
        cat.put("is_active", true);
        cat.put("created_at", Instant.now().toString());

        mockCategories.put(catId, cat);

        return Map.of(
                "status", "SUCCESS",
                "category", cat,
                "message_en", "Expense category created successfully.",
                "message_bn", "খরচ ক্যাটাগরি সফলভাবে তৈরি করা হয়েছে।"
        );
    }

    public Map<String, Object> updateCategory(String categoryId, Map<String, Object> updates) {
        if (!isManageAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_EXPENSE_MANAGE_UNAUTHORIZED",
                    "message_en", "Only Pharmacy Owner or Finance Officer can update expense categories.",
                    "message_bn", "শুধুমাত্র ফার্মেসি ওনার বা ফিন্যান্স অফিসার খরচ ক্যাটাগরি পরিবর্তন করতে পারেন।"
            );
        }

        Map<String, Object> cat = mockCategories.get(categoryId);
        if (cat == null) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_CATEGORY_NOT_FOUND",
                    "message_en", "Expense category not found.",
                    "message_bn", "খরচ ক্যাটাগরি খুঁজে পাওয়া যায়নি।"
            );
        }

        if (updates.containsKey("name")) {
            String newName = (String) updates.get("name");
            if (newName == null || newName.trim().isEmpty()) {
                return Map.of("status", "ERROR", "error_code", "ERR_MISSING_CATEGORY_NAME");
            }
            cat.put("name", newName.trim());
        }
        if (updates.containsKey("name_bn")) cat.put("name_bn", updates.get("name_bn"));
        if (updates.containsKey("monthly_budget_bdt")) cat.put("monthly_budget_bdt", updates.get("monthly_budget_bdt"));
        if (updates.containsKey("tax_deductible")) cat.put("tax_deductible", updates.get("tax_deductible"));
        if (updates.containsKey("is_active")) cat.put("is_active", updates.get("is_active"));
        if (updates.containsKey("description")) cat.put("description", updates.get("description"));

        return Map.of(
                "status", "SUCCESS",
                "category", cat,
                "message_en", "Expense category updated successfully.",
                "message_bn", "খরচ ক্যাটাগরি সফলভাবে আপডেট করা হয়েছে।"
        );
    }

    public Map<String, Object> archiveCategory(String categoryId) {
        if (!isManageAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_EXPENSE_MANAGE_UNAUTHORIZED",
                    "message_en", "Only Pharmacy Owner or Finance Officer can archive expense categories.",
                    "message_bn", "শুধুমাত্র ফার্মেসি ওনার বা ফিন্যান্স অফিসার খরচ ক্যাটাগরি আর্কাইভ করতে পারেন।"
            );
        }

        Map<String, Object> cat = mockCategories.get(categoryId);
        if (cat == null) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_CATEGORY_NOT_FOUND",
                    "message_en", "Expense category not found.",
                    "message_bn", "খরচ ক্যাটাগরি খুঁজে পাওয়া যায়নি।"
            );
        }

        cat.put("is_active", false);

        return Map.of(
                "status", "SUCCESS",
                "category", cat,
                "message_en", "Expense category archived successfully.",
                "message_bn", "খরচ ক্যাটাগরি সফলভাবে আর্কাইভ করা হয়েছে।"
        );
    }

    public List<Map<String, Object>> listCategories(Boolean isActive) {
        if (!isViewAuthorized()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> cat : mockCategories.values()) {
            if (isActive == null || Objects.equals(cat.get("is_active"), isActive)) {
                list.add(cat);
            }
        }
        return list;
    }
}
