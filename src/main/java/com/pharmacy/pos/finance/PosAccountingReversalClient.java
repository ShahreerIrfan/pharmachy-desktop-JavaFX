package com.pharmacy.pos.finance;

import java.util.*;

/**
 * PosAccountingReversalClient
 * Architecture Decision: ADR-0299 (P299).
 * JavaFX Desktop POS client for Maker-Checker Accounting Error Reversals.
 */
public class PosAccountingReversalClient {

    public static final Set<String> REQUEST_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "BRANCH_MANAGER", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> APPROVE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public enum UiState { IDLE, LOADING, LOADED, EMPTY, OFFLINE_CACHE, PERMISSION_DENIED, ERROR }

    private final String tenantId;
    private final String userRole;
    private UiState uiState = UiState.IDLE;

    public PosAccountingReversalClient(String tenantId, String userRole) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
    }

    public UiState getUiState() {
        return uiState;
    }

    public boolean canRequestReversal() {
        return REQUEST_ROLES.contains(this.userRole);
    }

    public boolean canApproveReversal() {
        return APPROVE_ROLES.contains(this.userRole);
    }

    public Map<String, Object> buildReversalRequestPayload(
            String originalJournalId,
            String errorCategory,
            String reason,
            List<Map<String, Object>> replacementLines
    ) {
        if (!canRequestReversal()) {
            this.uiState = UiState.PERMISSION_DENIED;
            throw new SecurityException("ERR_REVERSAL_REQUEST_DENIED: Staff role cannot request journal reversals.");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_REVERSAL_REASON: Reason is mandatory.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenant_id", this.tenantId);
        payload.put("original_journal_id", originalJournalId);
        payload.put("error_category", errorCategory != null ? errorCategory.toUpperCase() : "WRONG_AMOUNT");
        payload.put("reason", reason.trim());
        if (replacementLines != null && !replacementLines.isEmpty()) {
            payload.put("replacement_lines", replacementLines);
        }
        return payload;
    }
}
