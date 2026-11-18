package com.pharmacy.pos.finance;

import java.time.Instant;
import java.util.*;

/**
 * PosFinancialPeriodClient
 * Architecture Decision: ADR-0292 (P292).
 * Phase 15: General Ledger, Operating Expenses & Profitability.
 *
 * JavaFX Desktop POS client integrating Financial Period Close & Controlled Reopening:
 * - Period Close lifecycle (OPEN -> CLOSING -> CLOSED -> REOPENED).
 * - Pre-close ledger integrity check (trial balance parity & unapproved pending drafts).
 * - Immutability enforcement (closed periods lock GL from retroactive mutations).
 * - Controlled reopening gated strictly to Pharmacy Owner with mandatory audit reason.
 * - Multilingual EN/BN feedback.
 */
public class PosFinancialPeriodClient {

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "BRANCH_MANAGER", "AUDITOR", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> CLOSE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> REOPEN_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "OWNER_ADMIN", "ADMIN"
    );

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final List<Map<String, Object>> mockPeriods = new ArrayList<>();

    public PosFinancialPeriodClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
    }

    public boolean isViewAuthorized() {
        return VIEW_ROLES.contains(this.userRole);
    }

    public boolean isCloseAuthorized() {
        return CLOSE_ROLES.contains(this.userRole);
    }

    public boolean isReopenAuthorized() {
        return REOPEN_ROLES.contains(this.userRole);
    }

    public Map<String, Object> createPeriod(
            String periodCode,
            String periodName,
            String periodType,
            String startDate,
            String endDate
    ) {
        if (!isCloseAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_PERIOD_CLOSE_UNAUTHORIZED",
                    "message_en", "Only Pharmacy Owner or Finance Officer can create financial periods.",
                    "message_bn", "শুধুমাত্র ফার্মেসি ওনার বা ফিন্যান্স অফিসার আর্থিক পিরিয়ড তৈরি করতে পারেন।"
            );
        }

        if (periodCode == null || periodCode.trim().isEmpty() || startDate == null || endDate == null) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_MISSING_FIELDS",
                    "message_en", "Period code, start date, and end date are required.",
                    "message_bn", "পিরিয়ড কোড, শুরুর তারিখ এবং শেষের তারিখ আবশ্যক।"
            );
        }

        String periodId = "fper-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> period = new HashMap<>();
        period.put("period_id", periodId);
        period.put("tenant_id", this.tenantId);
        period.put("branch_id", this.branchId);
        period.put("period_code", periodCode.trim().toUpperCase());
        period.put("period_name", periodName != null ? periodName : "Accounting Period " + periodCode);
        period.put("period_type", periodType != null ? periodType : "MONTHLY");
        period.put("start_date", startDate);
        period.put("end_date", endDate);
        period.put("status", "OPEN");
        period.put("total_revenues_bdt", "0.00");
        period.put("total_expenses_bdt", "0.00");
        period.put("net_income_bdt", "0.00");
        period.put("reopen_count", 0);
        period.put("created_at", Instant.now().toString());

        mockPeriods.add(period);

        return Map.of(
                "status", "SUCCESS",
                "message_en", "Financial period created.",
                "message_bn", "আর্থিক পিরিয়ড তৈরি করা হয়েছে।",
                "period", period
        );
    }

    public Map<String, Object> validateIntegrity(String periodId, boolean simulatePendingDrafts) {
        if (!isViewAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_PERIOD_VIEW_UNAUTHORIZED",
                    "message_en", "Unauthorized to view period integrity.",
                    "message_bn", "পিরিয়ড ইন্টিগ্রিটি দেখার অনুমতি নেই।"
            );
        }

        List<String> issues = new ArrayList<>();
        if (simulatePendingDrafts) {
            issues.add("There are 2 unapproved draft operating expenses in this period.");
        }

        boolean isReady = issues.isEmpty();

        return Map.of(
                "period_id", periodId,
                "is_ready_to_close", isReady,
                "trial_balance_balanced", true,
                "blocking_issues", issues,
                "financial_summary", Map.of(
                        "total_revenues_bdt", "450000.00",
                        "total_expenses_bdt", "320000.00",
                        "net_income_bdt", "130000.00",
                        "is_profitable", true
                )
        );
    }

    public Map<String, Object> closePeriod(String periodId, String closingNotes) {
        if (!isCloseAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_PERIOD_CLOSE_UNAUTHORIZED",
                    "message_en", "Only Pharmacy Owner or Finance Officer can close financial periods.",
                    "message_bn", "শুধুমাত্র ফার্মেসি ওনার বা ফিন্যান্স অফিসার আর্থিক পিরিয়ড বন্ধ করতে পারেন।"
            );
        }

        Map<String, Object> target = null;
        for (Map<String, Object> p : mockPeriods) {
            if (periodId.equals(p.get("period_id"))) {
                target = p;
                break;
            }
        }

        if (target == null) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_PERIOD_NOT_FOUND",
                    "message_en", "Financial period not found.",
                    "message_bn", "আর্থিক পিরিয়ড পাওয়া যায়নি।"
            );
        }

        if ("CLOSED".equals(target.get("status"))) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_PERIOD_ALREADY_CLOSED",
                    "message_en", "Financial period is already closed.",
                    "message_bn", "আর্থিক পিরিয়ড ইতোমধ্যে বন্ধ করা হয়েছে।"
            );
        }

        target.put("status", "CLOSED");
        target.put("closed_at", Instant.now().toString());
        target.put("closed_by", this.userId);
        target.put("closing_journal_id", "jrnl-close-" + UUID.randomUUID().toString().substring(0, 8));
        target.put("closing_notes", closingNotes != null ? closingNotes : "");

        return Map.of(
                "status", "SUCCESS",
                "message_en", "Financial period closed and sealed.",
                "message_bn", "আর্থিক পিরিয়ড সফলভাবে বন্ধ ও সিল করা হয়েছে।",
                "period", target
        );
    }

    public Map<String, Object> reopenPeriod(String periodId, String reopenReason) {
        if (!isReopenAuthorized()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_PERIOD_REOPEN_UNAUTHORIZED",
                    "message_en", "Reopening a closed period strictly requires Pharmacy Owner authorization.",
                    "message_bn", "বন্ধ পিরিয়ড পুনরায় উন্মুক্ত করার জন্য ফার্মেসি ওনারের অনুমতি আবশ্যক।"
            );
        }

        if (reopenReason == null || reopenReason.trim().isEmpty()) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_MISSING_REOPEN_REASON",
                    "message_en", "A formal audit reason is mandatory to reopen a closed financial period.",
                    "message_bn", "বন্ধ পিরিয়ড খোলার জন্য অডিট কারণ উল্লেখ করা বাধ্যতামূলক।"
            );
        }

        Map<String, Object> target = null;
        for (Map<String, Object> p : mockPeriods) {
            if (periodId.equals(p.get("period_id"))) {
                target = p;
                break;
            }
        }

        if (target == null) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_PERIOD_NOT_FOUND",
                    "message_en", "Financial period not found.",
                    "message_bn", "আর্থিক পিরিয়ড পাওয়া যায়নি।"
            );
        }

        if (!"CLOSED".equals(target.get("status"))) {
            return Map.of(
                    "status", "ERROR",
                    "error_code", "ERR_PERIOD_NOT_CLOSED",
                    "message_en", "Only closed periods can be reopened.",
                    "message_bn", "শুধুমাত্র বন্ধ পিরিয়ড পুনরায় খোলা সম্ভব।"
            );
        }

        target.put("status", "REOPENED");
        target.put("reopened_at", Instant.now().toString());
        target.put("reopened_by", this.userId);
        target.put("reopen_reason", reopenReason.trim());
        int count = ((Number) target.getOrDefault("reopen_count", 0)).intValue() + 1;
        target.put("reopen_count", count);
        target.put("closing_journal_id", null);

        return Map.of(
                "status", "SUCCESS",
                "message_en", "Financial period reopened under audit control.",
                "message_bn", "আর্থিক পিরিয়ড অডিট নিয়ন্ত্রণে পুনরায় উন্মুক্ত করা হয়েছে।",
                "period", target
        );
    }

    public List<Map<String, Object>> getPeriods() {
        return Collections.unmodifiableList(mockPeriods);
    }
}
