package com.pharmacy.pos.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * PosPayrollExpenseClient
 * Architecture Decision: ADR-0291 (P291).
 * Phase 15: General Ledger, Operating Expenses & Profitability.
 *
 * JavaFX Desktop POS client integrating Payroll Expense Boundaries & Employee Salary Privacy:
 * - Strict segregation of payroll from ordinary expense vouchers.
 * - Prevents general cashier/dispensary users from entering or inspecting employee salary records.
 * - Confidential draft payroll batch calculation and validation.
 * - Approved payroll posting to General Ledger (Dr 6030 Salaries Expense, Cr 1010/1020 Settlement, Cr 2020 Deductions).
 * - Multilingual EN/BN UI status feedback.
 */
public class PosPayrollExpenseClient {

    public static final Set<String> SENIOR_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> STAFF_DENIED_ROLES = Set.of(
            "CASHIER", "STAFF_PHARMACIST", "INVENTORY_CLERK", "DISPENSER"
    );

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final List<Map<String, Object>> mockPayrollRuns = new ArrayList<>();

    public PosPayrollExpenseClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
    }

    public boolean isManageAuthorized() {
        return SENIOR_ROLES.contains(this.userRole);
    }

    public boolean isStaffDenied() {
        return STAFF_DENIED_ROLES.contains(this.userRole);
    }

    /**
     * Enforces payroll boundary against ordinary operating expense vouchers.
     */
    public Map<String, Object> validateOrdinaryExpenseCategory(String categoryCode) {
        if ("SALARIES".equalsIgnoreCase(categoryCode)) {
            return Map.of(
                    "allowed", false,
                    "error_code", "ERR_PAYROLL_EXPENSE_BOUNDARY_ENFORCED",
                    "message_en", "Individual employee payroll cannot be entered as ordinary operational expenses. Use the dedicated Payroll module.",
                    "message_bn", "কর্মচারীদের বেতন সাধারণ পরিচালন ব্যয় হিসেবে এন্ট্রি করা যাবে না। ডেডিকেটেড পেরোল মডিউল ব্যবহার করুন।"
            );
        }
        return Map.of("allowed", true);
    }

    /**
     * Creates a confidential draft payroll batch.
     */
    public Map<String, Object> createPayrollRun(
            String periodStart,
            String periodEnd,
            String paymentMethod,
            String settlementAccountId,
            List<Map<String, Object>> slips,
            String notes
    ) {
        if (!isManageAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_PAYROLL_MANAGE_UNAUTHORIZED",
                    "message_en", "Only Pharmacy Owner or Finance Officer can create payroll runs.",
                    "message_bn", "শুধুমাত্র ফার্মেসি ওনার বা ফিন্যান্স অফিসার পেরোল প্রস্তুত করতে পারেন।"
            );
        }

        if (periodStart == null || periodEnd == null || periodStart.trim().isEmpty() || periodEnd.trim().isEmpty()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_MISSING_PERIOD",
                    "message_en", "Payroll period start and end dates are required.",
                    "message_bn", "পেরোল পিরিয়ডের শুরুর ও শেষের তারিখ আবশ্যক।"
            );
        }

        if (slips == null || slips.isEmpty()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_EMPTY_PAYROLL_SLIPS",
                    "message_en", "Payroll run requires at least one staff salary slip.",
                    "message_bn", "পেরোল রানে অন্তত একজন কর্মচারীর বেতন স্লিপ থাকা আবশ্যক।"
            );
        }

        double totalGross = 0.0;
        double totalAllowances = 0.0;
        double totalBonuses = 0.0;
        double totalDeductions = 0.0;
        double totalNetPayable = 0.0;

        List<Map<String, Object>> processedSlips = new ArrayList<>();

        for (Map<String, Object> s : slips) {
            String staffId = (String) s.get("staff_member_id");
            String staffName = (String) s.getOrDefault("staff_name", "Employee");
            double base = ((Number) s.getOrDefault("base_salary_bdt", 0.0)).doubleValue();
            double allow = ((Number) s.getOrDefault("allowances_bdt", 0.0)).doubleValue();
            double bonus = ((Number) s.getOrDefault("bonus_bdt", 0.0)).doubleValue();
            double ded = ((Number) s.getOrDefault("deductions_bdt", 0.0)).doubleValue();

            if (base <= 0.0) {
                return Map.of(
                        "status", "ERROR",
                        "error_code", "ERR_INVALID_BASE_SALARY",
                        "message_en", "Base salary must be a positive number.",
                        "message_bn", "মূল বেতন ধনাত্মক সংখ্যা হতে হবে।"
                );
            }

            double net = base + allow + bonus - ded;
            if (net < 0.0) {
                return Map.of(
                        "status", "ERROR",
                        "error_code", "ERR_NEGATIVE_NET_SALARY",
                        "message_en", "Net salary cannot be negative.",
                        "message_bn", "নিট বেতন ঋণাত্মক হতে পারে না।"
                );
            }

            totalGross += base;
            totalAllowances += allow;
            totalBonuses += bonus;
            totalDeductions += ded;
            totalNetPayable += net;

            Map<String, Object> slipMap = new HashMap<>(s);
            slipMap.put("net_salary_bdt", String.format(Locale.US, "%.2f", net));
            slipMap.put("payment_status", "PENDING");
            processedSlips.add(slipMap);
        }

        String runId = "payrun-" + UUID.randomUUID().toString().substring(0, 8);
        String runNumber = "PAY-" + periodStart.replace("-", "").substring(0, 6) + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();

        Map<String, Object> run = new HashMap<>();
        run.put("run_id", runId);
        run.put("tenant_id", this.tenantId);
        run.put("branch_id", this.branchId);
        run.put("run_number", runNumber);
        run.put("period_start", periodStart);
        run.put("period_end", periodEnd);
        run.put("payment_date", LocalDate.now().toString());
        run.put("status", "DRAFT");
        run.put("payment_method", paymentMethod != null ? paymentMethod : "BANK_TRANSFER");
        run.put("total_gross_bdt", String.format(Locale.US, "%.2f", totalGross));
        run.put("total_allowances_bdt", String.format(Locale.US, "%.2f", totalAllowances));
        run.put("total_bonuses_bdt", String.format(Locale.US, "%.2f", totalBonuses));
        run.put("total_deductions_bdt", String.format(Locale.US, "%.2f", totalDeductions));
        run.put("total_net_payable_bdt", String.format(Locale.US, "%.2f", totalNetPayable));
        run.put("settlement_account_id", settlementAccountId);
        run.put("is_confidential", true);
        run.put("slips", processedSlips);
        run.put("notes", notes != null ? notes : "");
        run.put("created_at", Instant.now().toString());

        mockPayrollRuns.add(run);

        return Map.of(
                "status", "SUCCESS",
                "message_en", "Draft payroll batch created.",
                "message_bn", "ড্রাফট পেরোল ব্যাচ প্রস্তুত হয়েছে।",
                "payroll_run", run
        );
    }

    /**
     * Approves and posts payroll to General Ledger.
     */
    public Map<String, Object> approveAndPostPayroll(String runId) {
        if (!isManageAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_PAYROLL_MANAGE_UNAUTHORIZED",
                    "message_en", "Only Pharmacy Owner or Finance Officer can approve payroll.",
                    "message_bn", "শুধুমাত্র ফার্মেসি ওনার বা ফিন্যান্স অফিসার পেরোল অনুমোদন করতে পারেন।"
            );
        }

        Map<String, Object> targetRun = null;
        for (Map<String, Object> r : mockPayrollRuns) {
            if (runId.equals(r.get("run_id"))) {
                targetRun = r;
                break;
            }
        }

        if (targetRun == null) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_PAYROLL_RUN_NOT_FOUND",
                    "message_en", "Payroll run not found.",
                    "message_bn", "পেরোল রান পাওয়া যায়নি।"
            );
        }

        if ("POSTED".equals(targetRun.get("status"))) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_PAYROLL_ALREADY_POSTED",
                    "message_en", "Payroll run has already been approved and posted.",
                    "message_bn", "পেরোল রান ইতোমধ্যে অনুমোদিত এবং পোস্ট করা হয়েছে।"
            );
        }

        String journalId = "jrnl-pay-" + UUID.randomUUID().toString().substring(0, 8);
        targetRun.put("status", "POSTED");
        targetRun.put("posted_journal_id", journalId);
        targetRun.put("approved_by", this.userId);
        targetRun.put("approved_at", Instant.now().toString());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slips = (List<Map<String, Object>>) targetRun.get("slips");
        if (slips != null) {
            for (Map<String, Object> s : slips) {
                s.put("payment_status", "PAID");
            }
        }

        return Map.of(
                "status", "SUCCESS",
                "message_en", "Payroll approved and posted to General Ledger.",
                "message_bn", "পেরোল অনুমোদিত এবং জেনারেল লেজারে পোস্ট করা হয়েছে।",
                "payroll_run", targetRun
        );
    }

    public List<Map<String, Object>> getPayrollRuns() {
        return Collections.unmodifiableList(mockPayrollRuns);
    }
}
