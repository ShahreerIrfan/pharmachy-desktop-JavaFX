package com.pharmacy.pos.permissions;

import java.util.*;

/**
 * PosGranularPermissionClient
 * Architecture Decision: ADR-0104
 * Phase 06: Milestone M03 - Granular Action Permissions (P104)
 *
 * Client-side evaluation, action verification, and wholesale cost price masking
 * for JavaFX Desktop POS across the 9 operational actions:
 * sale, refund, discount, cost_visibility, stock_change, purchasing,
 * expense_approval, export, rx_review.
 */
public final class PosGranularPermissionClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        OFFLINE,
        ERROR,
        PERMISSION_DENIED
    }

    public static final Set<String> ALL_ACTIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "sale",
            "refund",
            "discount",
            "cost_visibility",
            "stock_change",
            "purchasing",
            "expense_approval",
            "export",
            "rx_review"
    )));

    public static final class GranularPermissionState {
        public final String userId;
        public final String tenantId;
        public final String role;
        public final boolean costVisibilityEnabled;
        public final Map<String, Boolean> actions;

        public GranularPermissionState(
                String userId,
                String tenantId,
                String role,
                boolean costVisibilityEnabled,
                Map<String, Boolean> actions) {
            this.userId = userId != null ? userId : "";
            this.tenantId = tenantId != null ? tenantId : "";
            this.role = role != null ? role.toUpperCase() : "UNKNOWN";
            this.costVisibilityEnabled = costVisibilityEnabled;
            this.actions = actions != null
                    ? Collections.unmodifiableMap(new HashMap<>(actions))
                    : Collections.emptyMap();
        }

        public boolean isActionAllowed(String action) {
            if (action == null) return false;
            return Boolean.TRUE.equals(this.actions.get(action.trim().toLowerCase()));
        }

        public boolean allowsSale() { return isActionAllowed("sale"); }
        public boolean allowsRefund() { return isActionAllowed("refund"); }
        public boolean allowsDiscount() { return isActionAllowed("discount"); }
        public boolean allowsCostVisibility() { return isActionAllowed("cost_visibility"); }
        public boolean allowsStockChange() { return isActionAllowed("stock_change"); }
        public boolean allowsPurchasing() { return isActionAllowed("purchasing"); }
        public boolean allowsExpenseApproval() { return isActionAllowed("expense_approval"); }
        public boolean allowsExport() { return isActionAllowed("export"); }
        public boolean allowsRxReview() { return isActionAllowed("rx_review"); }
    }

    public static final class EvaluationResult {
        public final boolean isAllowed;
        public final String action;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public EvaluationResult(boolean isAllowed, String action, String errorCode, String messageEn, String messageBn) {
            this.isAllowed = isAllowed;
            this.action = action;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static EvaluationResult allow(String action) {
            return new EvaluationResult(true, action, null, "Action permitted.", "অ্যাকশন অনুমোদিত।");
        }

        public static EvaluationResult deny(String action, String role, String reason) {
            return new EvaluationResult(
                    false,
                    action,
                    "ERR_GRANULAR_PERMISSION_DENIED",
                    String.format("Access denied: role '%s' cannot perform '%s'. %s", role, action, reason),
                    String.format("অননুমোদিত অ্যাকশন: '%s' রোলে '%s' সম্পন্ন করার অনুমতি নেই।", role, action)
            );
        }
    }

    public static final class GranularViewState {
        public final UiStatus status;
        public final GranularPermissionState permissionState;
        public final String messageEn;
        public final String messageBn;

        public GranularViewState(UiStatus status, GranularPermissionState permissionState, String messageEn, String messageBn) {
            this.status = status;
            this.permissionState = permissionState;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static GranularViewState loading() {
            return new GranularViewState(UiStatus.LOADING, null, "Loading permissions...", "অনুমতি লোড হচ্ছে...");
        }

        public static GranularViewState success(GranularPermissionState state) {
            return new GranularViewState(UiStatus.SUCCESS, state, "Permissions synchronized.", "অনুমতি সিঙ্ক হয়েছে।");
        }

        public static GranularViewState offline(GranularPermissionState fallback) {
            return new GranularViewState(UiStatus.OFFLINE, fallback, "Operating offline with cached permissions.", "ক্যাশ করা অনুমতি নিয়ে অফলাইনে চলছে।");
        }

        public static GranularViewState denied(String messageEn, String messageBn) {
            return new GranularViewState(UiStatus.PERMISSION_DENIED, null, messageEn, messageBn);
        }

        public static GranularViewState error(String messageEn, String messageBn) {
            return new GranularViewState(UiStatus.ERROR, null, messageEn, messageBn);
        }
    }

    private PosGranularPermissionClient() {}

    /**
     * Resolves default action flags based on canonical role definitions.
     */
    public static Map<String, Boolean> resolveRoleDefaults(String role) {
        String r = role != null ? role.toUpperCase() : "UNKNOWN";
        Map<String, Boolean> map = new HashMap<>();

        if ("SUPERADMIN".equals(r) || "SUPER_ADMIN".equals(r) || "PLATFORM_OPERATOR".equals(r) || "PHARMACY_OWNER".equals(r)) {
            for (String act : ALL_ACTIONS) map.put(act, true);
            return map;
        }

        // Initialize all to false
        for (String act : ALL_ACTIONS) map.put(act, false);

        switch (r) {
            case "BRANCH_MANAGER":
                map.put("sale", true);
                map.put("refund", true);
                map.put("discount", true);
                map.put("cost_visibility", true);
                map.put("stock_change", true);
                map.put("purchasing", true);
                map.put("expense_approval", true);
                break;
            case "STAFF_PHARMACIST":
            case "PHARMACIST":
                map.put("sale", true);
                map.put("rx_review", true);
                break;
            case "CASHIER":
                map.put("sale", true);
                break;
            case "INVENTORY_CLERK":
                map.put("cost_visibility", true);
                map.put("stock_change", true);
                map.put("purchasing", true);
                break;
            case "FINANCE_OFFICER":
                map.put("cost_visibility", true);
                map.put("expense_approval", true);
                map.put("export", true);
                break;
            case "AUDITOR":
                map.put("export", true);
                break;
            default:
                break;
        }
        return map;
    }

    /**
     * Evaluates action authority locally including branch scope.
     */
    public static EvaluationResult evaluateLocalAction(String role, String action, String userBranchId, String targetBranchId) {
        if (action == null || !ALL_ACTIONS.contains(action.trim().toLowerCase())) {
            return new EvaluationResult(false, action, "UNKNOWN_ACTION", "Unknown action: " + action, "অজানা অ্যাকশন: " + action);
        }
        String cleanAction = action.trim().toLowerCase();
        Map<String, Boolean> defaults = resolveRoleDefaults(role);

        if (!Boolean.TRUE.equals(defaults.get(cleanAction))) {
            return EvaluationResult.deny(cleanAction, role, "Missing capability in role profile.");
        }

        // Branch Manager branch isolation check
        if ("BRANCH_MANAGER".equalsIgnoreCase(role) && userBranchId != null && targetBranchId != null) {
            if (!userBranchId.equals(targetBranchId)) {
                return EvaluationResult.deny(cleanAction, role, "Cross-branch action blocked for branch manager.");
            }
        }

        return EvaluationResult.allow(cleanAction);
    }

    /**
     * Masks wholesale cost fields in medicine records if user lacks cost_visibility.
     */
    public static Map<String, Object> maskMedicineCost(Map<String, Object> medicine, boolean costVisibility) {
        if (medicine == null) return null;
        Map<String, Object> copy = new HashMap<>(medicine);
        if (!costVisibility) {
            copy.put("cost_unit", null);
            copy.put("cost_per_unit", null);
            copy.put("unit_cost_bdt", null);
            copy.put("cogs_bdt", null);
        }
        return copy;
    }

    /**
     * Creates offline fallback permission state for a user.
     */
    public static GranularPermissionState createOfflineFallback(String userId, String tenantId, String role) {
        Map<String, Boolean> defaults = resolveRoleDefaults(role);
        boolean costVis = Boolean.TRUE.equals(defaults.get("cost_visibility"));
        return new GranularPermissionState(userId, tenantId, role, costVis, defaults);
    }
}

