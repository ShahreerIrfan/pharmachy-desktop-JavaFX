package com.pharmacy.pos.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * PosRecurringExpenseClient
 * Architecture Decision: ADR-0288 (P288).
 * Phase 15: General Ledger, Operating Expenses & Profitability.
 *
 * JavaFX Desktop POS client integrating Recurring Operating Expense Templates:
 * - Templates for recurring rent, electricity, broadband internet, security, and maintenance.
 * - Frequency rules (Monthly, Weekly, Daily, Quarterly, Yearly) with next run date calculation.
 * - On-demand generation and auto-approval workflows.
 * - Multilingual EN/BN feedback and role enforcement.
 */
public class PosRecurringExpenseClient {

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

    private final Map<String, Map<String, Object>> mockTemplates = new HashMap<>();

    public PosRecurringExpenseClient(String tenantId, String branchId, String userRole, String userId) {
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

    public Map<String, Object> createTemplate(
            String templateName,
            String categoryId,
            String categoryName,
            String payeeName,
            double estimatedAmountBdt,
            String frequency,
            int dayOfMonth,
            boolean autoApprove
    ) {
        if (!isManageAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_RECURRING_MANAGE_UNAUTHORIZED",
                    "message_en", "Only Pharmacy Owner or Finance Officer can create recurring expense templates.",
                    "message_bn", "শুধুমাত্র ফার্মেসি ওনার বা ফিন্যান্স অফিসার রিকারিং খরচ টেমপ্লেট তৈরি করতে পারেন।"
            );
        }

        if (templateName == null || templateName.trim().isEmpty()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_MISSING_TEMPLATE_NAME",
                    "message_en", "Template name is required.",
                    "message_bn", "টেমপ্লেটের নাম আবশ্যক।"
            );
        }

        if (estimatedAmountBdt <= 0) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_INVALID_EXPENSE_AMOUNT",
                    "message_en", "Estimated amount must be greater than zero.",
                    "message_bn", "আনুমানিক খরচের পরিমাণ অবশ্যই শূন্যের চেয়ে বেশি হতে হবে।"
            );
        }

        String tplId = "RECTPL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        LocalDate now = LocalDate.now();
        LocalDate nextRun = now.withDayOfMonth(Math.min(dayOfMonth, now.lengthOfMonth()));
        if (nextRun.isBefore(now)) {
            nextRun = nextRun.plusMonths(1);
        }

        Map<String, Object> tpl = new HashMap<>();
        tpl.put("template_id", tplId);
        tpl.put("template_name", templateName.trim());
        tpl.put("tenant_id", tenantId);
        tpl.put("branch_id", branchId);
        tpl.put("category_id", categoryId != null ? categoryId : "EXPCAT-DEFAULT");
        tpl.put("category_name", categoryName != null ? categoryName : "General Expense");
        tpl.put("payee_name", payeeName != null ? payeeName.trim() : "Default Payee");
        tpl.put("estimated_amount_bdt", String.format(Locale.US, "%.2f", estimatedAmountBdt));
        tpl.put("frequency", frequency != null ? frequency.toUpperCase() : "MONTHLY");
        tpl.put("day_of_month", dayOfMonth);
        tpl.put("start_date", now.toString());
        tpl.put("next_run_date", nextRun.toString());
        tpl.put("auto_approve", autoApprove);
        tpl.put("is_active", true);
        tpl.put("created_by", userId);
        tpl.put("created_at", Instant.now().toString());

        mockTemplates.put(tplId, tpl);

        return Map.of(
                "status", "SUCCESS",
                "template", tpl,
                "message_en", "Recurring expense template created successfully.",
                "message_bn", "রিকারিং খরচ টেমপ্লেট সফলভাবে তৈরি হয়েছে।"
        );
    }

    public Map<String, Object> triggerGeneration(String templateId) {
        if (!isManageAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_RECURRING_MANAGE_UNAUTHORIZED",
                    "message_en", "Only Pharmacy Owner or Finance Officer can trigger recurring expenses.",
                    "message_bn", "শুধুমাত্র ফার্মেসি ওনার বা ফিন্যান্স অফিসার রিকারিং খরচ ট্রিগার করতে পারেন।"
            );
        }

        Map<String, Object> tpl = mockTemplates.get(templateId);
        if (tpl == null) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_TEMPLATE_NOT_FOUND",
                    "message_en", "Recurring expense template not found.",
                    "message_bn", "রিকারিং খরচ টেমপ্লেট খুঁজে পাওয়া যায়নি।"
            );
        }

        String voucherNum = "EXP-REC-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        tpl.put("last_run_date", LocalDate.now().toString());

        return Map.of(
                "status", "SUCCESS",
                "generated_voucher", voucherNum,
                "template_id", templateId,
                "message_en", "Recurring expense voucher generated successfully.",
                "message_bn", "রিকারিং খরচ ভাউচার সফলভাবে তৈরি হয়েছে।"
        );
    }

    public Map<String, Object> archiveTemplate(String templateId) {
        if (!isManageAuthorized()) {
            return Map.of("status", "ERROR", "error_code", "ERR_RECURRING_MANAGE_UNAUTHORIZED");
        }

        Map<String, Object> tpl = mockTemplates.get(templateId);
        if (tpl == null) {
            return Map.of("status", "ERROR", "error_code", "ERR_TEMPLATE_NOT_FOUND");
        }

        tpl.put("is_active", false);

        return Map.of(
                "status", "SUCCESS",
                "template", tpl,
                "message_en", "Recurring expense template archived successfully.",
                "message_bn", "রিকারিং খরচ টেমপ্লেট সফলভাবে আর্কাইভ করা হয়েছে।"
        );
    }

    public List<Map<String, Object>> listTemplates(Boolean isActive) {
        if (!isViewAuthorized()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> tpl : mockTemplates.values()) {
            if (isActive == null || Objects.equals(tpl.get("is_active"), isActive)) {
                list.add(tpl);
            }
        }
        return list;
    }
}
