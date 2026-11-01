package com.pharmacy.pos.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * PlatformBillingReportClient
 * Architecture Decision: ADR-0098
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P098)
 * Requirement Trace: FR-044, FR-064, FR-066, FR-067, FR-074
 *
 * Client-side MRR, Churn, Collections, Aging/Outstanding Invoices, and Tenant Billing
 * Summary evaluation and presentation logic for JavaFX Desktop POS.
 */
public class PlatformBillingReportClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        OFFLINE,
        ERROR,
        PERMISSION_DENIED
    }

    public static final class MrrSummary {
        public final BigDecimal totalMrrBdt;
        public final BigDecimal totalArrBdt;
        public final BigDecimal arpuBdt;
        public final int activeSubscriptionsCount;
        public final int payingTenantsCount;
        public final BigDecimal basicMrrBdt;
        public final BigDecimal standardMrrBdt;
        public final BigDecimal proMrrBdt;

        public MrrSummary(
                BigDecimal totalMrrBdt,
                BigDecimal totalArrBdt,
                BigDecimal arpuBdt,
                int activeSubscriptionsCount,
                int payingTenantsCount,
                BigDecimal basicMrrBdt,
                BigDecimal standardMrrBdt,
                BigDecimal proMrrBdt) {
            this.totalMrrBdt = totalMrrBdt != null ? totalMrrBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.totalArrBdt = totalArrBdt != null ? totalArrBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.arpuBdt = arpuBdt != null ? arpuBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.activeSubscriptionsCount = activeSubscriptionsCount;
            this.payingTenantsCount = payingTenantsCount;
            this.basicMrrBdt = basicMrrBdt != null ? basicMrrBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.standardMrrBdt = standardMrrBdt != null ? standardMrrBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.proMrrBdt = proMrrBdt != null ? proMrrBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
    }

    public static final class ChurnSummary {
        public final BigDecimal churnRatePct;
        public final int churnedSubscriptionsCount;
        public final BigDecimal lostMrrBdt;

        public ChurnSummary(BigDecimal churnRatePct, int churnedSubscriptionsCount, BigDecimal lostMrrBdt) {
            this.churnRatePct = churnRatePct != null ? churnRatePct : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.churnedSubscriptionsCount = churnedSubscriptionsCount;
            this.lostMrrBdt = lostMrrBdt != null ? lostMrrBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
    }

    public static final class CollectionsSummary {
        public final BigDecimal totalCollectedBdt;
        public final BigDecimal bkashBdt;
        public final BigDecimal nagadBdt;
        public final BigDecimal bankTransferBdt;
        public final BigDecimal gatewayOnlineBdt;
        public final BigDecimal collectionEfficiencyPct;

        public CollectionsSummary(
                BigDecimal totalCollectedBdt,
                BigDecimal bkashBdt,
                BigDecimal nagadBdt,
                BigDecimal bankTransferBdt,
                BigDecimal gatewayOnlineBdt,
                BigDecimal collectionEfficiencyPct) {
            this.totalCollectedBdt = totalCollectedBdt != null ? totalCollectedBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.bkashBdt = bkashBdt != null ? bkashBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.nagadBdt = nagadBdt != null ? nagadBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.bankTransferBdt = bankTransferBdt != null ? bankTransferBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.gatewayOnlineBdt = gatewayOnlineBdt != null ? gatewayOnlineBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.collectionEfficiencyPct = collectionEfficiencyPct != null ? collectionEfficiencyPct : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
    }

    public static final class OutstandingSummary {
        public final BigDecimal totalOutstandingBdt;
        public final int outstandingInvoicesCount;
        public final BigDecimal currentBdt;
        public final BigDecimal days1To30Bdt;
        public final BigDecimal days31To60Bdt;
        public final BigDecimal days61To90Bdt;
        public final BigDecimal daysOver90Bdt;
        public final int atRiskTenantsCount;

        public OutstandingSummary(
                BigDecimal totalOutstandingBdt,
                int outstandingInvoicesCount,
                BigDecimal currentBdt,
                BigDecimal days1To30Bdt,
                BigDecimal days31To60Bdt,
                BigDecimal days61To90Bdt,
                BigDecimal daysOver90Bdt,
                int atRiskTenantsCount) {
            this.totalOutstandingBdt = totalOutstandingBdt != null ? totalOutstandingBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.outstandingInvoicesCount = outstandingInvoicesCount;
            this.currentBdt = currentBdt != null ? currentBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.days1To30Bdt = days1To30Bdt != null ? days1To30Bdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.days31To60Bdt = days31To60Bdt != null ? days31To60Bdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.days61To90Bdt = days61To90Bdt != null ? days61To90Bdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.daysOver90Bdt = daysOver90Bdt != null ? daysOver90Bdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.atRiskTenantsCount = atRiskTenantsCount;
        }

        public boolean hasHighRiskAr() {
            BigDecimal severe = days31To60Bdt.add(days61To90Bdt).add(daysOver90Bdt);
            return severe.compareTo(BigDecimal.ZERO) > 0 || atRiskTenantsCount > 0;
        }
    }

    public static final class TenantBillingStatement {
        public final String tenantId;
        public final String planTier;
        public final BigDecimal lifetimeBilledBdt;
        public final BigDecimal lifetimePaidBdt;
        public final BigDecimal outstandingBalanceBdt;
        public final int invoicesCount;

        public TenantBillingStatement(
                String tenantId,
                String planTier,
                BigDecimal lifetimeBilledBdt,
                BigDecimal lifetimePaidBdt,
                BigDecimal outstandingBalanceBdt,
                int invoicesCount) {
            this.tenantId = tenantId;
            this.planTier = planTier;
            this.lifetimeBilledBdt = lifetimeBilledBdt != null ? lifetimeBilledBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.lifetimePaidBdt = lifetimePaidBdt != null ? lifetimePaidBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.outstandingBalanceBdt = outstandingBalanceBdt != null ? outstandingBalanceBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.invoicesCount = invoicesCount;
        }

        public boolean hasOutstandingBalance() {
            return outstandingBalanceBdt.compareTo(BigDecimal.ZERO) > 0;
        }
    }

    public static final class BillingReportUiState {
        public final UiStatus status;
        public final MrrSummary mrr;
        public final ChurnSummary churn;
        public final CollectionsSummary collections;
        public final OutstandingSummary outstanding;
        public final TenantBillingStatement tenantStatement;
        public final String errorMessageEn;
        public final String errorMessageBn;

        public BillingReportUiState(
                UiStatus status,
                MrrSummary mrr,
                ChurnSummary churn,
                CollectionsSummary collections,
                OutstandingSummary outstanding,
                TenantBillingStatement tenantStatement,
                String errorMessageEn,
                String errorMessageBn) {
            this.status = status;
            this.mrr = mrr;
            this.churn = churn;
            this.collections = collections;
            this.outstanding = outstanding;
            this.tenantStatement = tenantStatement;
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

    public static BillingReportUiState createPermissionDeniedState(String role) {
        return new BillingReportUiState(
                UiStatus.PERMISSION_DENIED,
                null,
                null,
                null,
                null,
                null,
                "Access denied for role '" + role + "'. Only pharmacy owners and platform administrators can view billing reports.",
                "আপনার রোলে ('" + role + "') বিলিং রিপোর্ট দেখার অনুমতি নেই। শুধুমাত্র মালিক ও অ্যাডমিনদের প্রবেশাধিকার আছে।"
        );
    }

    public static BillingReportUiState createOfflineState() {
        return new BillingReportUiState(
                UiStatus.OFFLINE,
                null,
                null,
                null,
                null,
                null,
                "Device is currently offline. Billing reports require live connection to cloud server.",
                "ডিভাইসটি অফলাইনে আছে। বিলিং রিপোর্ট দেখতে ক্লাউড সার্ভারের সাথে সক্রিয় ইন্টারনেট সংযোগ প্রয়োজন।"
        );
    }
}

