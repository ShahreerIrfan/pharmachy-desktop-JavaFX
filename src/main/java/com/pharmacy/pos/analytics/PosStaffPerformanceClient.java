package com.pharmacy.pos.analytics;

import java.util.*;

/**
 * PosStaffPerformanceClient
 * Architecture Decision: ADR-0115 (P115).
 * Phase 06: Milestone M03 - Offline-First Desktop POS & Owner Visibility.
 *
 * Client-side integration for JavaFX Desktop POS presenting authorized staff performance
 * metrics, sales volumes, refund metrics, cash reconciliation variances, and freshness.
 */
public class PosStaffPerformanceClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class StaffPerformanceRecord {
        public final String snapshotId;
        public final String tenantId;
        public final String branchId;
        public final String staffId;
        public final String staffName;
        public final String staffRole;
        public final String metricDate;
        public final int totalSalesCount;
        public final double totalSalesAmount;
        public final int totalRefundsCount;
        public final double totalRefundsAmount;
        public final double netRevenue;
        public final double reconciliationVarianceAmount;
        public final String reconciliationStatus;
        public final int reportingFreshnessSeconds;

        public StaffPerformanceRecord(
                String snapshotId,
                String tenantId,
                String branchId,
                String staffId,
                String staffName,
                String staffRole,
                String metricDate,
                int totalSalesCount,
                double totalSalesAmount,
                int totalRefundsCount,
                double totalRefundsAmount,
                double netRevenue,
                double reconciliationVarianceAmount,
                String reconciliationStatus,
                int reportingFreshnessSeconds) {
            this.snapshotId = snapshotId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.staffId = staffId;
            this.staffName = staffName;
            this.staffRole = staffRole;
            this.metricDate = metricDate;
            this.totalSalesCount = totalSalesCount;
            this.totalSalesAmount = totalSalesAmount;
            this.totalRefundsCount = totalRefundsCount;
            this.totalRefundsAmount = totalRefundsAmount;
            this.netRevenue = netRevenue;
            this.reconciliationVarianceAmount = reconciliationVarianceAmount;
            this.reconciliationStatus = reconciliationStatus;
            this.reportingFreshnessSeconds = reportingFreshnessSeconds;
        }
    }

    public static final class ScopedMetricsResult {
        public final UiStatus uiStatus;
        public final List<StaffPerformanceRecord> records;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public ScopedMetricsResult(
                UiStatus uiStatus,
                List<StaffPerformanceRecord> records,
                String errorCode,
                String messageEn,
                String messageBn) {
            this.uiStatus = uiStatus;
            this.records = records != null ? List.copyOf(records) : List.of();
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static ScopedMetricsResult success(List<StaffPerformanceRecord> records) {
            return new ScopedMetricsResult(
                    records.isEmpty() ? UiStatus.EMPTY : UiStatus.SUCCESS,
                    records,
                    null,
                    "Staff performance metrics retrieved successfully.",
                    "স্টাফ পারফরম্যান্স মেট্রিক সফলভাবে লোড হয়েছে।"
            );
        }

        public static ScopedMetricsResult error(UiStatus status, String code, String msgEn, String msgBn) {
            return new ScopedMetricsResult(status, List.of(), code, msgEn, msgBn);
        }
    }

    public ScopedMetricsResult filterMetrics(
            List<StaffPerformanceRecord> allRecords,
            String callerRole,
            String callerTenantId,
            String callerBranchId,
            String callerStaffId,
            String targetTenantId,
            String targetBranchId,
            String targetStaffId) {

        // 1. Cross-tenant check
        if (targetTenantId != null && !Objects.equals(callerTenantId, targetTenantId) && !"SUPERADMIN".equalsIgnoreCase(callerRole)) {
            return ScopedMetricsResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_CROSS_TENANT_VIOLATION",
                    "Cross-tenant metrics access forbidden.",
                    "অন্য টেন্যান্টের পারফরম্যান্স মেট্রিক দেখার অনুমতি নেই।"
            );
        }

        List<StaffPerformanceRecord> filtered = new ArrayList<>();
        boolean isOwner = "PHARMACY_OWNER".equalsIgnoreCase(callerRole) || "SUPERADMIN".equalsIgnoreCase(callerRole);
        boolean isManager = "BRANCH_MANAGER".equalsIgnoreCase(callerRole);

        if (isOwner) {
            for (StaffPerformanceRecord r : allRecords) {
                if (targetBranchId != null && !targetBranchId.equals(r.branchId)) continue;
                if (targetStaffId != null && !targetStaffId.equals(r.staffId)) continue;
                filtered.add(r);
            }
        } else if (isManager) {
            if (targetBranchId != null && !targetBranchId.equals(callerBranchId)) {
                return ScopedMetricsResult.error(
                        UiStatus.PERMISSION_DENIED,
                        "ERR_CROSS_BRANCH_VIOLATION",
                        "Branch managers cannot inspect performance of other branches.",
                        "শাখা ব্যবস্থাপক অন্য শাখার মেট্রিক দেখতে পারেন না।"
                );
            }
            for (StaffPerformanceRecord r : allRecords) {
                if (!Objects.equals(callerBranchId, r.branchId)) continue;
                if (targetStaffId != null && !targetStaffId.equals(r.staffId)) continue;
                filtered.add(r);
            }
        } else {
            // Cashier / Staff: Self-only
            if (targetStaffId != null && !targetStaffId.equals(callerStaffId)) {
                return ScopedMetricsResult.error(
                        UiStatus.PERMISSION_DENIED,
                        "ERR_PERMISSION_DENIED",
                        "Staff are strictly restricted to their own performance metrics.",
                        "স্টাফ শুধুমাত্র নিজের পারফরম্যান্স মেট্রিক দেখতে পারেন।"
                );
            }
            for (StaffPerformanceRecord r : allRecords) {
                if (Objects.equals(callerStaffId, r.staffId)) {
                    filtered.add(r);
                }
            }
        }

        return ScopedMetricsResult.success(filtered);
    }
}

