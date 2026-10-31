package com.pharmacy.pos.security;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Set;

/**
 * ADR-0064: Platform Super Admin KPI Cards Client & Boundary Guard.
 * Guarantees platform-level KPI telemetry is strictly isolated from tenant POS interfaces
 * and validates monetary BDT formatting conforming to ADR-0055.
 */
public final class PosSuperAdminKpiCardsClient {

    private static final Set<String> AUTHORIZED_PLATFORM_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR"
    );

    private static final Set<String> ALLOWED_PERIODS = Set.of("current", "30d", "mtd");

    private PosSuperAdminKpiCardsClient() {
        // Utility / client boundary class
    }

    /**
     * Verifies if a user role is authorized to view platform KPI cards.
     */
    public static boolean canAccessPlatformKpis(String role) {
        if (role == null) {
            return false;
        }
        return AUTHORIZED_PLATFORM_ROLES.contains(role.toUpperCase().trim());
    }

    /**
     * Architectural invariant: Platform KPI cards are strictly omitted from tenant POS views.
     */
    public static boolean isKpiCardVisibleInTenantUI() {
        return false;
    }

    /**
     * Builds the Platform KPI endpoint URL with strict role and parameter validation.
     */
    public static String getKpiCardsUrl(String role, String currency, String period) {
        if (!canAccessPlatformKpis(role)) {
            throw new SecurityException("Access denied: Role '" + role + "' cannot access platform KPI cards.");
        }
        String cleanCurrency = Objects.requireNonNullElse(currency, "BDT").toUpperCase().trim();
        if (!"BDT".equals(cleanCurrency)) {
            throw new IllegalArgumentException("Unsupported currency '" + currency + "'. Platform KPIs require BDT.");
        }
        String cleanPeriod = Objects.requireNonNullElse(period, "current").toLowerCase().trim();
        if (!ALLOWED_PERIODS.contains(cleanPeriod)) {
            throw new IllegalArgumentException("Unsupported period '" + period + "'. Allowed: " + ALLOWED_PERIODS);
        }
        return "/api/v2/platform/console/kpis/?currency=" + cleanCurrency + "&period=" + cleanPeriod;
    }

    /**
     * Formats a Decimal BDT monetary value into localized Bangladesh Taka representation.
     */
    public static String formatBdtRevenue(String amountStr) {
        if (amountStr == null || amountStr.trim().isEmpty()) {
            return "৳ 0.00";
        }
        try {
            BigDecimal dec = new BigDecimal(amountStr.trim()).setScale(2, RoundingMode.HALF_UP);
            return "৳ " + dec.toPlainString();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid monetary amount string: '" + amountStr + "'", ex);
        }
    }
}
