package com.pharmacy.pos.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * PosOperatingExpenseClient
 * Architecture Decision: ADR-0287 (P287) & ADR-0289 (P289).
 * Phase 15: General Ledger, Operating Expenses & Profitability.
 *
 * JavaFX Desktop POS client integrating Operating Expense Entries:
 * - Draft expense voucher creation with payee, amount, date, category, payment method, and reimbursable claims.
 * - High-value and reimbursable approval policy requiring Owner or Finance Officer elevated sign-off.
 * - Self-approval prevention for reimbursement claimants.
 * - Reimbursement payout settlement workflow.
 * - Audited correction events (Full Reversal, Amount Adjustment, Category Reclassification) with General Ledger adjustments.
 * - Multilingual EN/BN feedback and role enforcement.
 */
public class PosOperatingExpenseClient {

    public static final double HIGH_VALUE_THRESHOLD = 5000.00;

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "AUDITOR", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> CREATE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> APPROVE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> SENIOR_APPROVE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final Map<String, Map<String, Object>> mockExpenses = new HashMap<>();
    private final List<Map<String, Object>> mockCorrections = new ArrayList<>();

    public PosOperatingExpenseClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
    }

    public boolean isViewAuthorized() {
        return VIEW_ROLES.contains(this.userRole);
    }

    public boolean isCreateAuthorized() {
        return CREATE_ROLES.contains(this.userRole);
    }

    public boolean isApproveAuthorized() {
        return APPROVE_ROLES.contains(this.userRole);
    }

    public boolean isSeniorApproveAuthorized() {
        return SENIOR_APPROVE_ROLES.contains(this.userRole);
    }

    public Map<String, Object> createDraft(
            String categoryId,
            String categoryName,
            String defaultGlCode,
            String payeeName,
            String payeePhone,
            double amountBdt,
            String paymentMethod,
            String paymentRef,
            String receiptUrl,
            String notes,
            boolean taxDeductible,
            boolean isReimbursable,
            String claimantName
    ) {
        if (!isCreateAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_EXPENSE_CREATE_UNAUTHORIZED",
                    "message_en", "Unauthorized to create expense entries.",
                    "message_bn", "খরচ এন্ট্রি তৈরি করার অনুমতি নেই।"
            );
        }

        if (payeeName == null || payeeName.trim().isEmpty()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_MISSING_PAYEE",
                    "message_en", "Payee name is required.",
                    "message_bn", "প্রাপকের নাম আবশ্যক।"
            );
        }

        if (amountBdt <= 0) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_INVALID_EXPENSE_AMOUNT",
                    "message_en", "Expense amount must be greater than zero.",
                    "message_bn", "খরচের পরিমাণ অবশ্যই শূন্যের চেয়ে বেশি হতে হবে।"
            );
        }

        String expId = "OPEXP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String voucherNum = "EXP-" + LocalDate.now().toString().replace("-", "").substring(0, 6) + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();

        boolean isHighVal = amountBdt >= HIGH_VALUE_THRESHOLD;
        String resolvedClaimant = (isReimbursable && (claimantName == null || claimantName.trim().isEmpty())) ? userId : claimantName;

        Map<String, Object> exp = new HashMap<>();
        exp.put("expense_id", expId);
        exp.put("voucher_number", voucherNum);
        exp.put("tenant_id", tenantId);
        exp.put("branch_id", branchId);
        exp.put("category_id", categoryId != null ? categoryId : "EXPCAT-DEFAULT");
        exp.put("category_name", categoryName != null ? categoryName : "General Expense");
        exp.put("default_gl_code", defaultGlCode != null ? defaultGlCode : "6060");
        exp.put("payee_name", payeeName.trim());
        exp.put("payee_phone", payeePhone != null ? payeePhone.trim() : "");
        exp.put("expense_date", LocalDate.now().toString());
        exp.put("amount_bdt", String.format(Locale.US, "%.2f", amountBdt));
        exp.put("payment_method", paymentMethod != null ? paymentMethod.toUpperCase() : "CASH");
        exp.put("payment_reference", paymentRef != null ? paymentRef.trim() : "");
        exp.put("receipt_file_url", receiptUrl != null ? receiptUrl.trim() : "");
        exp.put("notes", notes != null ? notes.trim() : "");
        exp.put("tax_deductible", taxDeductible);
        exp.put("is_reimbursable", isReimbursable);
        exp.put("claimant_name", resolvedClaimant != null ? resolvedClaimant : "");
        exp.put("claimant_user_id", isReimbursable ? userId : null);
        exp.put("reimbursement_status", isReimbursable ? "PENDING_REVIEW" : "NOT_APPLICABLE");
        exp.put("is_high_value", isHighVal);
        exp.put("status", "DRAFT");
        exp.put("created_by", userId);
        exp.put("created_at", Instant.now().toString());

        mockExpenses.put(expId, exp);

        return Map.of(
                "status", "SUCCESS",
                "expense", exp,
                "message_en", "Operating expense draft voucher created successfully.",
                "message_bn", "অপারেটিং খরচ ড্রাফট ভাউচার সফলভাবে তৈরি হয়েছে।"
        );
    }

    public Map<String, Object> approveExpense(String expenseId, String approvalNotes) {
        if (!isApproveAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_EXPENSE_APPROVE_UNAUTHORIZED",
                    "message_en", "Only Pharmacy Owner, Finance Officer or Branch Manager can approve expenses.",
                    "message_bn", "শুধুমাত্র ফার্মেসি ওনার, ফিন্যান্স অফিসার বা ব্রাঞ্চ ম্যানেজার খরচ অনুমোদন করতে পারেন।"
            );
        }

        Map<String, Object> exp = mockExpenses.get(expenseId);
        if (exp == null) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_EXPENSE_NOT_FOUND",
                    "message_en", "Expense voucher not found.",
                    "message_bn", "খরচ ভাউচার খুঁজে পাওয়া যায়নি।"
            );
        }

        if ("APPROVED".equals(exp.get("status")) || "REVERSED".equals(exp.get("status"))) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_EXPENSE_ALREADY_APPROVED",
                    "message_en", "Expense voucher has already been approved or reversed.",
                    "message_bn", "খরচ ভাউচারটি ইতোমধ্যে অনুমোদিত বা বাতিল করা হয়েছে।"
            );
        }

        double amt = Double.parseDouble(String.valueOf(exp.get("amount_bdt")));
        boolean isHighVal = Boolean.TRUE.equals(exp.get("is_high_value")) || amt >= HIGH_VALUE_THRESHOLD;
        boolean isReimbursable = Boolean.TRUE.equals(exp.get("is_reimbursable"));

        if ((isHighVal || isReimbursable) && !isSeniorApproveAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_HIGH_VALUE_APPROVAL_REQUIRED",
                    "message_en", "High-value or reimbursable expenses require Owner or Finance Officer approval.",
                    "message_bn", "উচ্চমূল্যের বা রিইম্বার্সমেন্ট খরচ অনুমোদনের জন্য ওনার বা ফিন্যান্স অফিসারের অনুমোদন আবশ্যক।"
            );
        }

        // Self approval prevention
        if (isReimbursable && userId.equals(exp.get("claimant_user_id"))) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_SELF_APPROVAL_FORBIDDEN",
                    "message_en", "Claimants cannot approve their own reimbursement claims.",
                    "message_bn", "দাবিকারক নিজে নিজের রিইম্বার্সমেন্ট ক্লেইম অনুমোদন করতে পারবেন না।"
            );
        }

        String journalNum = "JRN-OPEX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        exp.put("status", "APPROVED");
        exp.put("approved_by", userId);
        exp.put("approved_at", Instant.now().toString());
        exp.put("approval_notes", approvalNotes != null ? approvalNotes.trim() : "");
        exp.put("posted_journal_number", journalNum);

        if (isReimbursable) {
            exp.put("reimbursement_status", "APPROVED_PENDING_PAYOUT");
        }

        return Map.of(
                "status", "SUCCESS",
                "expense_id", expenseId,
                "voucher_number", exp.get("voucher_number"),
                "status", "APPROVED",
                "posted_journal_number", journalNum,
                "reimbursement_status", exp.get("reimbursement_status"),
                "expense", exp,
                "message_en", "Expense voucher approved and posted to General Ledger.",
                "message_bn", "খরচ ভাউচার অনুমোদিত এবং জেনারেল লেজারে পোস্ট করা হয়েছে।"
        );
    }

    public Map<String, Object> reimburseExpense(String expenseId, String payoutMethod, String reference) {
        if (!isApproveAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_UNAUTHORIZED",
                    "message_en", "Unauthorized to disburse reimbursement.",
                    "message_bn", "রিইম্বার্সমেন্ট পরিশোধের অনুমতি নেই।"
            );
        }

        Map<String, Object> exp = mockExpenses.get(expenseId);
        if (exp == null) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_EXPENSE_NOT_FOUND",
                    "message_en", "Expense voucher not found.",
                    "message_bn", "খরচ ভাউচার খুঁজে পাওয়া যায়নি।"
            );
        }

        if (!Boolean.TRUE.equals(exp.get("is_reimbursable"))) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_NOT_REIMBURSABLE",
                    "message_en", "Expense is not reimbursable.",
                    "message_bn", "খরচটি রিইম্বার্সযোগ্য নয়।"
            );
        }

        if (!"APPROVED".equals(exp.get("status"))) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_EXPENSE_NOT_APPROVED",
                    "message_en", "Only approved expenses can be reimbursed.",
                    "message_bn", "শুধুমাত্র অনুমোদিত খরচের রিইম্বার্সমেন্ট করা যায়।"
            );
        }

        if ("PAID_OUT".equals(exp.get("reimbursement_status"))) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_ALREADY_REIMBURSED",
                    "message_en", "Expense claim already reimbursed.",
                    "message_bn", "রিইম্বার্সমেন্ট দাবি ইতোমধ্যে পরিশোধিত হয়েছে।"
            );
        }

        exp.put("reimbursement_status", "PAID_OUT");
        exp.put("reimbursed_at", Instant.now().toString());
        exp.put("reimbursed_by", userId);
        exp.put("reimbursement_reference", reference != null ? reference.trim() : "");
        exp.put("reimbursement_payment_method", payoutMethod != null ? payoutMethod.toUpperCase() : "CASH");

        return Map.of(
                "status", "SUCCESS",
                "expense", exp,
                "message_en", "Expense reimbursement disbursed successfully.",
                "message_bn", "খরচের রিইম্বার্সমেন্ট সফলভাবে পরিশোধ করা হয়েছে।"
        );
    }

    public Map<String, Object> correctExpense(String expenseId, String correctionType, String reason, Double correctedAmount, String correctedCategoryName) {
        if (!isSeniorApproveAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_UNAUTHORIZED",
                    "message_en", "Only Pharmacy Owner or Finance Officer can execute expense corrections.",
                    "message_bn", "শুধুমাত্র ওনার বা ফিন্যান্স অফিসার খরচ সংশোধন করতে পারেন।"
            );
        }

        if (reason == null || reason.trim().length() < 5) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_MANDATORY_CORRECTION_REASON",
                    "message_en", "Meaningful correction reason of at least 5 characters is required.",
                    "message_bn", "সংশোধনের কারণ কমপক্ষে ৫ অক্ষরের হওয়া আবশ্যক।"
            );
        }

        Map<String, Object> exp = mockExpenses.get(expenseId);
        if (exp == null) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_EXPENSE_NOT_FOUND",
                    "message_en", "Expense voucher not found.",
                    "message_bn", "খরচ ভাউচার খুঁজে পাওয়া যায়নি।"
            );
        }

        if (!"APPROVED".equals(exp.get("status"))) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_EXPENSE_NOT_APPROVED",
                    "message_en", "Only approved expenses can be corrected.",
                    "message_bn", "শুধুমাত্র অনুমোদিত খরচ সংশোধন করা যায়।"
            );
        }

        String corrId = "EXPCORR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String type = correctionType != null ? correctionType.toUpperCase().trim() : "FULL_REVERSAL";

        Map<String, Object> corr = new HashMap<>();
        corr.put("correction_id", corrId);
        corr.put("expense_id", expenseId);
        corr.put("correction_type", type);
        corr.put("reason", reason.trim());
        corr.put("created_by", userId);
        corr.put("created_at", Instant.now().toString());

        if ("FULL_REVERSAL".equals(type)) {
            exp.put("status", "REVERSED");
            if (Boolean.TRUE.equals(exp.get("is_reimbursable")) && !"PAID_OUT".equals(exp.get("reimbursement_status"))) {
                exp.put("reimbursement_status", "REJECTED");
            }
            corr.put("reversal_journal", "REV-JRN-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        } else if ("AMOUNT_ADJUSTMENT".equals(type) && correctedAmount != null && correctedAmount > 0) {
            exp.put("amount_bdt", String.format(Locale.US, "%.2f", correctedAmount));
            corr.put("reversal_journal", "ADJ-JRN-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        }

        mockCorrections.add(corr);

        return Map.of(
                "status", "SUCCESS",
                "correction_id", corrId,
                "correction_type", type,
                "expense", exp,
                "message_en", "Expense correction executed with balanced General Ledger adjustment.",
                "message_bn", "জেনারেল লেজার সমন্বয় সহ খরচ ভাউচার সংশোধন সম্পন্ন হয়েছে।"
        );
    }
}
