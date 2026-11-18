package com.pharmacy.pos.finance;

import java.util.*;

/**
 * PosAccountingExportClient
 * Architecture Decision: ADR-0298 (P298).
 * Phase 15: General Ledger, Operating Expenses & Profitability.
 *
 * JavaFX Desktop POS client for Permission-Scoped Historical Accounting Snapshots & Exports.
 */
public class PosAccountingExportClient {

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "BRANCH_MANAGER", "AUDITOR", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> REPORT_TYPES = Set.of(
            "TRIAL_BALANCE", "JOURNAL_LEDGER", "PROFIT_AND_LOSS", "OPERATING_EXPENSES", "RECONCILIATION_PACK"
    );

    public enum UiState { IDLE, LOADING, LOADED, EMPTY, OFFLINE_CACHE, PERMISSION_DENIED, ERROR }

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private UiState uiState = UiState.IDLE;
    private final List<Map<String, Object>> cachedSnapshots = new ArrayList<>();

    public PosAccountingExportClient(String tenantId, String branchId, String userRole) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
    }

    public UiState getUiState() {
        return uiState;
    }

    public boolean canExportScope(String targetBranchId) {
        if (!VIEW_ROLES.contains(this.userRole)) {
            return false;
        }
        if ("BRANCH_MANAGER".equals(this.userRole)) {
            return targetBranchId != null && targetBranchId.equals(this.branchId);
        }
        return true;
    }

    public String getSnapshotDownloadCsvUrl(String snapshotId) {
        return "/api/v2/finance/accounting-exports/" + snapshotId + "/download/?format=csv&tenant_id=" + this.tenantId;
    }

    public String getLiveExportCsvUrl(String reportType, String targetBranchId) {
        String rt = reportType != null ? reportType.toUpperCase() : "TRIAL_BALANCE";
        if (!REPORT_TYPES.contains(rt)) {
            throw new IllegalArgumentException("ERR_INVALID_REPORT_TYPE: " + rt);
        }
        if (!canExportScope(targetBranchId)) {
            this.uiState = UiState.PERMISSION_DENIED;
            throw new SecurityException("ERR_BRANCH_SCOPE_DENIED: Not authorized to export this scope.");
        }
        StringBuilder sb = new StringBuilder("/api/v2/finance/accounting-exports/live/?format=csv&tenant_id=")
                .append(this.tenantId).append("&report_type=").append(rt);
        if (targetBranchId != null && !targetBranchId.isEmpty()) {
            sb.append("&branch_id=").append(targetBranchId);
        }
        return sb.toString();
    }

    public void cacheSnapshots(List<Map<String, Object>> snaps) {
        this.cachedSnapshots.clear();
        if (snaps != null && !snaps.isEmpty()) {
            this.cachedSnapshots.addAll(snaps);
            this.uiState = UiState.LOADED;
        } else {
            this.uiState = UiState.EMPTY;
        }
    }

    public List<Map<String, Object>> loadOfflineCache() {
        this.uiState = this.cachedSnapshots.isEmpty() ? UiState.EMPTY : UiState.OFFLINE_CACHE;
        return Collections.unmodifiableList(this.cachedSnapshots);
    }
}
