package com.pharmacy.pos.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * PosTaxConfigurationClient
 * Architecture Decision: ADR-0297 (P297).
 * Phase 15: General Ledger, Operating Expenses & Profitability.
 *
 * JavaFX Desktop POS client for Tenant-Reviewed Tax Configuration & Invoice Presentation:
 * - Zero hardcoded statutory tax rates; applies only server-reviewed effective-dated rules.
 * - Supports EXCLUSIVE and INCLUSIVE BigDecimal tax computation for offline POS preview.
 * - Renders localized EN/BN invoice headers, BIN/VAT registration label, and non-certification disclaimers.
 * - Enforces RBAC (Owner/Finance manage rules; Cashier/Pharmacist read-only calculation).
 */
public class PosTaxConfigurationClient {

    public static final Set<String> MANAGE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public enum UiState {
        IDLE, LOADING, LOADED, EMPTY, OFFLINE_CACHE, PERMISSION_DENIED, ERROR
    }

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private UiState uiState = UiState.IDLE;
    private final List<Map<String, Object>> cachedReviewedRules = new ArrayList<>();

    public PosTaxConfigurationClient(String tenantId, String branchId, String userRole) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
    }

    public UiState getUiState() {
        return uiState;
    }

    public boolean canManageTaxRules() {
        return MANAGE_ROLES.contains(this.userRole);
    }

    public String getRulesEndpoint() {
        return "/api/v2/finance/tax-config/?tenant_id=" + this.tenantId + "&branch_id=" + this.branchId;
    }

    public String getPresentationEndpoint() {
        return "/api/v2/finance/tax-config/presentation/?tenant_id=" + this.tenantId + "&branch_id=" + this.branchId;
    }

    public String getCalculateInvoiceEndpoint() {
        return "/api/v2/finance/tax-config/calculate-invoice/";
    }

    public Map<String, Object> buildDraftRulePayload(
            String ruleCode,
            String name,
            String nameBn,
            String appliesToScope,
            String calculationMode,
            BigDecimal taxRatePct,
            String effectiveFromIso
    ) {
        if (!canManageTaxRules()) {
            this.uiState = UiState.PERMISSION_DENIED;
            throw new SecurityException("ERR_TAX_MANAGE_UNAUTHORIZED: Only Owner or Finance Officer can configure tax rules.");
        }
        if (taxRatePct == null || taxRatePct.compareTo(BigDecimal.ZERO) < 0 || taxRatePct.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("ERR_INVALID_TAX_RATE: Rate must be between 0.000 and 100.000.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenant_id", this.tenantId);
        payload.put("branch_id", this.branchId);
        payload.put("rule_code", ruleCode.trim().toUpperCase());
        payload.put("name", name.trim());
        payload.put("name_bn", nameBn != null ? nameBn.trim() : "");
        payload.put("applies_to_scope", appliesToScope.trim().toUpperCase());
        payload.put("calculation_mode", calculationMode.trim().toUpperCase());
        payload.put("tax_rate_pct", taxRatePct.setScale(3, RoundingMode.HALF_UP).toPlainString());
        payload.put("effective_from", effectiveFromIso);
        return payload;
    }

    public BigDecimal computeLineTax(BigDecimal lineGross, BigDecimal ratePct, String calculationMode) {
        if (lineGross == null || ratePct == null || ratePct.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal hundred = new BigDecimal("100");
        if ("INCLUSIVE".equalsIgnoreCase(calculationMode)) {
            BigDecimal divisor = BigDecimal.ONE.add(ratePct.divide(hundred, 6, RoundingMode.HALF_UP));
            BigDecimal taxable = lineGross.divide(divisor, 2, RoundingMode.HALF_UP);
            return lineGross.subtract(taxable).setScale(2, RoundingMode.HALF_UP);
        }
        return lineGross.multiply(ratePct).divide(hundred, 2, RoundingMode.HALF_UP);
    }

    public void cacheReviewedRules(List<Map<String, Object>> rules) {
        this.cachedReviewedRules.clear();
        if (rules != null && !rules.isEmpty()) {
            this.cachedReviewedRules.addAll(rules);
            this.uiState = UiState.LOADED;
        } else {
            this.uiState = UiState.EMPTY;
        }
    }

    public List<Map<String, Object>> getOfflineCachedRules() {
        this.uiState = this.cachedReviewedRules.isEmpty() ? UiState.EMPTY : UiState.OFFLINE_CACHE;
        return Collections.unmodifiableList(this.cachedReviewedRules);
    }
}
