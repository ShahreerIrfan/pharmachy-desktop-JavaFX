package com.pharmacy.pos.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * PosExpenseBudgetClient
 * Architecture Decision: ADR-0296 (P296).
 * Phase 15: General Ledger, Operating Expenses & Profitability.
 *
 * JavaFX Desktop POS client integrating Operating Expense Budget Controls:
 * - Planned budgets scoped by branch, expense category, and period (MONTHLY/QUARTERLY/ANNUAL).
 * - Alert threshold visualization (HEALTHY / WARNING / EXCEEDED) using BigDecimal precision.
 * - Pre-flight budget check before submitting or approving operating expenses.
 * - RBAC gating (PHARMACY_OWNER / FINANCE_OFFICER manage; BRANCH_MANAGER branch-scoped read; staff denied).
 * - Loading, empty, error, offline, permission-denied, and translated EN/BN UI states.
 */
public class PosExpenseBudgetClient {

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "BRANCH_MANAGER", "AUDITOR", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> MANAGE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> ENFORCEMENT_MODES = Set.of(
            "ADVISORY_ALERT", "REQUIRE_APPROVAL", "HARD_STOP"
    );

    public enum UiState {
        IDLE, LOADING, LOADED, EMPTY, OFFLINE_CACHE, PERMISSION_DENIED, ERROR
    }

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private UiState uiState = UiState.IDLE;
    private final List<Map<String, Object>> cachedBudgets = new ArrayList<>();

    public PosExpenseBudgetClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
    }

    public UiState getUiState() {
        return uiState;
    }

    public boolean isViewAuthorized(String targetBranchId) {
        if (!VIEW_ROLES.contains(this.userRole)) {
            return false;
        }
        if ("BRANCH_MANAGER".equals(this.userRole)) {
            if (targetBranchId != null && !targetBranchId.isEmpty() && !targetBranchId.equals(this.branchId)) {
                return false;
            }
        }
        return true;
    }

    public boolean isManageAuthorized() {
        return MANAGE_ROLES.contains(this.userRole);
    }

    public String getListEndpoint(Integer fiscalYear, Integer fiscalMonth, String targetBranchId) {
        StringBuilder sb = new StringBuilder("/api/v2/finance/expense-budgets/?tenant_id=").append(this.tenantId);
        if (fiscalYear != null) {
            sb.append("&fiscal_year=").append(fiscalYear);
        }
        if (fiscalMonth != null) {
            sb.append("&fiscal_month=").append(fiscalMonth);
        }
        if (targetBranchId != null && !targetBranchId.isEmpty()) {
            sb.append("&branch_id=").append(targetBranchId);
        }
        return sb.toString();
    }

    public String getEvaluateEndpoint() {
        return "/api/v2/finance/expense-budgets/evaluate/";
    }

    public String getAlertsEndpoint() {
        return "/api/v2/finance/expense-budgets/alerts/?tenant_id=" + this.tenantId;
    }

    public String getExportCsvEndpoint(Integer fiscalYear, Integer fiscalMonth) {
        StringBuilder sb = new StringBuilder("/api/v2/finance/expense-budgets/export/?format=csv&tenant_id=")
                .append(this.tenantId);
        if (fiscalYear != null) {
            sb.append("&fiscal_year=").append(fiscalYear);
        }
        if (fiscalMonth != null) {
            sb.append("&fiscal_month=").append(fiscalMonth);
        }
        return sb.toString();
    }

    public Map<String, Object> buildCreateBudgetPayload(
            String targetBranchId,
            String categoryId,
            String periodType,
            int fiscalYear,
            Integer fiscalMonth,
            BigDecimal plannedAmountBdt,
            BigDecimal warningThresholdPct,
            BigDecimal criticalThresholdPct,
            String enforcementMode,
            String notes
    ) {
        if (!isManageAuthorized()) {
            this.uiState = UiState.PERMISSION_DENIED;
            throw new SecurityException("ERR_BUDGET_MANAGE_UNAUTHORIZED: Only Pharmacy Owner or Finance Officer can manage budgets.");
        }
        if (plannedAmountBdt == null || plannedAmountBdt.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("ERR_INVALID_PLANNED_AMOUNT: Planned amount must be positive.");
        }
        String mode = enforcementMode != null ? enforcementMode.toUpperCase() : "REQUIRE_APPROVAL";
        if (!ENFORCEMENT_MODES.contains(mode)) {
            throw new IllegalArgumentException("ERR_INVALID_ENFORCEMENT_MODE: Unsupported enforcement mode " + mode);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenant_id", this.tenantId);
        if (targetBranchId != null && !targetBranchId.isEmpty()) {
            payload.put("branch_id", targetBranchId);
        }
        if (categoryId != null && !categoryId.isEmpty()) {
            payload.put("category_id", categoryId);
        }
        payload.put("period_type", periodType != null ? periodType.toUpperCase() : "MONTHLY");
        payload.put("fiscal_year", fiscalYear);
        if (fiscalMonth != null) {
            payload.put("fiscal_month", fiscalMonth);
        }
        payload.put("planned_amount_bdt", plannedAmountBdt.setScale(2, RoundingMode.HALF_UP).toPlainString());
        payload.put("warning_threshold_pct", (warningThresholdPct != null ? warningThresholdPct : new BigDecimal("80.00"))
                .setScale(2, RoundingMode.HALF_UP).toPlainString());
        payload.put("critical_threshold_pct", (criticalThresholdPct != null ? criticalThresholdPct : new BigDecimal("100.00"))
                .setScale(2, RoundingMode.HALF_UP).toPlainString());
        payload.put("enforcement_mode", mode);
        payload.put("require_manager_approval_on_exceed", "REQUIRE_APPROVAL".equals(mode));
        payload.put("notes", notes != null ? notes.trim() : "");
        return payload;
    }

    public Map<String, String> localizedStatusBadge(String healthStatus) {
        String key = healthStatus != null ? healthStatus.toUpperCase() : "HEALTHY";
        Map<String, String> labels = new LinkedHashMap<>();
        switch (key) {
            case "EXCEEDED":
                labels.put("en", "Budget Exceeded");
                labels.put("bn", "বাজেট অতিক্রম করেছে");
                break;
            case "WARNING":
                labels.put("en", "Approaching Limit");
                labels.put("bn", "সীমার কাছাকাছি");
                break;
            default:
                labels.put("en", "Within Budget");
                labels.put("bn", "বাজেটের মধ্যে আছে");
                break;
        }
        return labels;
    }

    public void updateCacheFromServer(List<Map<String, Object>> budgets) {
        this.cachedBudgets.clear();
        if (budgets != null && !budgets.isEmpty()) {
            this.cachedBudgets.addAll(budgets);
            this.uiState = UiState.LOADED;
        } else {
            this.uiState = UiState.EMPTY;
        }
    }

    public List<Map<String, Object>> loadOfflineFallback() {
        this.uiState = this.cachedBudgets.isEmpty() ? UiState.EMPTY : UiState.OFFLINE_CACHE;
        return Collections.unmodifiableList(this.cachedBudgets);
    }

    public String getUserId() {
        return userId;
    }
}
