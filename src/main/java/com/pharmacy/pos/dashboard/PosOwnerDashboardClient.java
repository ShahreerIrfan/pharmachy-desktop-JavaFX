package com.pharmacy.pos.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

/**
 * PosOwnerDashboardClient
 * Architecture Decision: ADR-0101
 * Phase 06: Milestone M03 - Offline-First Desktop POS & Owner Visibility (P101)
 * Requirement Trace: FR-031, FR-032, FR-033, FR-044, FR-066, FR-070
 *
 * Client-side integration for executive pharmacy dashboard, gross profit,
 * customer dues, inventory valuation, DGDA expiry alerts, and POS sync health in JavaFX.
 */
public class PosOwnerDashboardClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        OFFLINE,
        ERROR,
        PERMISSION_DENIED
    }

    public static final class SalesKpi {
        public final BigDecimal todaySalesBdt;
        public final BigDecimal periodSalesBdt;
        public final int totalOrdersCount;
        public final BigDecimal averageOrderValueBdt;

        public SalesKpi(
                BigDecimal todaySalesBdt,
                BigDecimal periodSalesBdt,
                int totalOrdersCount,
                BigDecimal averageOrderValueBdt) {
            this.todaySalesBdt = todaySalesBdt != null ? todaySalesBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.periodSalesBdt = periodSalesBdt != null ? periodSalesBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.totalOrdersCount = totalOrdersCount;
            this.averageOrderValueBdt = averageOrderValueBdt != null ? averageOrderValueBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
    }

    public static final class ProfitabilityKpi {
        public final BigDecimal grossSalesBdt;
        public final BigDecimal cogsBdt;
        public final BigDecimal grossProfitBdt;
        public final BigDecimal grossMarginPercent;
        public final BigDecimal discountsBdt;
        public final BigDecimal netProfitBdt;

        public ProfitabilityKpi(
                BigDecimal grossSalesBdt,
                BigDecimal cogsBdt,
                BigDecimal grossProfitBdt,
                BigDecimal grossMarginPercent,
                BigDecimal discountsBdt,
                BigDecimal netProfitBdt) {
            this.grossSalesBdt = grossSalesBdt != null ? grossSalesBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.cogsBdt = cogsBdt != null ? cogsBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.grossProfitBdt = grossProfitBdt != null ? grossProfitBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.grossMarginPercent = grossMarginPercent != null ? grossMarginPercent : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.discountsBdt = discountsBdt != null ? discountsBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.netProfitBdt = netProfitBdt != null ? netProfitBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
    }

    public static final class CustomerDuesKpi {
        public final BigDecimal totalDuesBdt;
        public final int outstandingCustomersCount;
        public final int overdueInvoicesCount;

        public CustomerDuesKpi(
                BigDecimal totalDuesBdt,
                int outstandingCustomersCount,
                int overdueInvoicesCount) {
            this.totalDuesBdt = totalDuesBdt != null ? totalDuesBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.outstandingCustomersCount = outstandingCustomersCount;
            this.overdueInvoicesCount = overdueInvoicesCount;
        }
    }

    public static final class InventoryKpi {
        public final BigDecimal totalStockValueBdt;
        public final int activeBatchesCount;
        public final int lowStockItemsCount;
        public final int outOfStockItemsCount;

        public InventoryKpi(
                BigDecimal totalStockValueBdt,
                int activeBatchesCount,
                int lowStockItemsCount,
                int outOfStockItemsCount) {
            this.totalStockValueBdt = totalStockValueBdt != null ? totalStockValueBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.activeBatchesCount = activeBatchesCount;
            this.lowStockItemsCount = lowStockItemsCount;
            this.outOfStockItemsCount = outOfStockItemsCount;
        }

        public boolean hasStockShortage() {
            return lowStockItemsCount > 0 || outOfStockItemsCount > 0;
        }
    }

    public static final class ExpiryKpi {
        public final int expiringIn30DaysCount;
        public final int expiringIn90DaysCount;
        public final int expiredBatchesCount;
        public final BigDecimal expiredStockValueBdt;

        public ExpiryKpi(
                int expiringIn30DaysCount,
                int expiringIn90DaysCount,
                int expiredBatchesCount,
                BigDecimal expiredStockValueBdt) {
            this.expiringIn30DaysCount = expiringIn30DaysCount;
            this.expiringIn90DaysCount = expiringIn90DaysCount;
            this.expiredBatchesCount = expiredBatchesCount;
            this.expiredStockValueBdt = expiredStockValueBdt != null ? expiredStockValueBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        public boolean hasCriticalExpiryRisk() {
            return expiringIn30DaysCount > 0 || expiredBatchesCount > 0;
        }
    }

    public static final class SyncHealthKpi {
        public final int enrolledDevicesCount;
        public final int activeLeasesCount;
        public final int unreconciledHeldCount;
        public final String syncStatus; // HEALTHY, NEEDS_SYNC, DEGRADED
        public final String lastSyncAt;

        public SyncHealthKpi(
                int enrolledDevicesCount,
                int activeLeasesCount,
                int unreconciledHeldCount,
                String syncStatus,
                String lastSyncAt) {
            this.enrolledDevicesCount = enrolledDevicesCount;
            this.activeLeasesCount = activeLeasesCount;
            this.unreconciledHeldCount = unreconciledHeldCount;
            this.syncStatus = syncStatus != null ? syncStatus : "HEALTHY";
            this.lastSyncAt = lastSyncAt;
        }

        public boolean isHealthy() {
            return "HEALTHY".equalsIgnoreCase(syncStatus) && unreconciledHeldCount == 0;
        }
    }

    public static final class OwnerDashboardData {
        public final String tenantId;
        public final String tenantName;
        public final String branchFilter;
        public final SalesKpi sales;
        public final ProfitabilityKpi profitability;
        public final CustomerDuesKpi dues;
        public final InventoryKpi inventory;
        public final ExpiryKpi expiry;
        public final SyncHealthKpi syncHealth;
        public final String asOfTimestamp;

        public OwnerDashboardData(
                String tenantId,
                String tenantName,
                String branchFilter,
                SalesKpi sales,
                ProfitabilityKpi profitability,
                CustomerDuesKpi dues,
                InventoryKpi inventory,
                ExpiryKpi expiry,
                SyncHealthKpi syncHealth,
                String asOfTimestamp) {
            this.tenantId = tenantId;
            this.tenantName = tenantName;
            this.branchFilter = branchFilter;
            this.sales = sales;
            this.profitability = profitability;
            this.dues = dues;
            this.inventory = inventory;
            this.expiry = expiry;
            this.syncHealth = syncHealth;
            this.asOfTimestamp = asOfTimestamp;
        }
    }

    public static final class OwnerDashboardUiState {
        public final UiStatus status;
        public final OwnerDashboardData data;
        public final String errorMessageEn;
        public final String errorMessageBn;

        public OwnerDashboardUiState(
                UiStatus status,
                OwnerDashboardData data,
                String errorMessageEn,
                String errorMessageBn) {
            this.status = status;
            this.data = data;
            this.errorMessageEn = errorMessageEn;
            this.errorMessageBn = errorMessageBn;
        }
    }

    public static String formatBdt(BigDecimal amount) {
        if (amount == null) {
            return "৳0.00";
        }
        return "৳" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static OwnerDashboardUiState createPermissionDeniedState(String role) {
        return new OwnerDashboardUiState(
                UiStatus.PERMISSION_DENIED,
                null,
                "Access denied for role '" + role + "'. Only pharmacy owners and branch managers can access executive dashboards.",
                "আপনার রোলে ('" + role + "') এক্সিকিউটিভ ড্যাশবোর্ড দেখার অনুমতি নেই।"
        );
    }

    public static OwnerDashboardUiState createOfflineState() {
        return new OwnerDashboardUiState(
                UiStatus.OFFLINE,
                null,
                "Device is currently offline. Executive metrics require cloud server synchronization.",
                "ডিভাইসটি অফলাইনে আছে। এক্সিকিউটিভ তথ্য দেখতে ক্লাউড সার্ভারের সাথে সক্রিয় ইন্টারনেট সংযোগ প্রয়োজন।"
        );
    }
}
