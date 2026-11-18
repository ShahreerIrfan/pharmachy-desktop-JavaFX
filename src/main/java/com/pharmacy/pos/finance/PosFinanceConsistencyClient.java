package com.pharmacy.pos.finance;

import java.util.*;

/**
 * PosFinanceConsistencyClient
 * Architecture Decision: ADR-0300 (P300).
 * JavaFX Desktop POS client for E2E Financial Consistency & Balanced-Book Audit.
 */
public class PosFinanceConsistencyClient {

    public static final Set<String> AUDIT_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "BRANCH_MANAGER", "AUDITOR", "OWNER_ADMIN", "ADMIN"
    );

    public enum UiState { IDLE, LOADING, CONSISTENT, DISCREPANCY_DETECTED, OFFLINE_CACHE, PERMISSION_DENIED, ERROR }

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private UiState uiState = UiState.IDLE;

    public PosFinanceConsistencyClient(String tenantId, String branchId, String userRole) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
    }

    public UiState getUiState() {
        return uiState;
    }

    public boolean canRunAudit(String targetBranchId) {
        if (!AUDIT_ROLES.contains(this.userRole)) return false;
        if ("BRANCH_MANAGER".equals(this.userRole)) {
            return targetBranchId != null && targetBranchId.equals(this.branchId);
        }
        return true;
    }

    public String getAuditEndpoint(String targetBranchId) {
        if (!canRunAudit(targetBranchId)) {
            this.uiState = UiState.PERMISSION_DENIED;
            throw new SecurityException("ERR_AUDIT_SCOPE_DENIED: Not authorized for this branch scope.");
        }
        StringBuilder sb = new StringBuilder("/api/v2/finance/consistency-audit/?tenant_id=").append(this.tenantId);
        if (targetBranchId != null && !targetBranchId.isEmpty()) {
            sb.append("&branch_id=").append(targetBranchId);
        }
        return sb.toString();
    }
}
