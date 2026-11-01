package com.pharmacy.pos.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

/**
 * PosDunningNotificationClient
 * Architecture Decision: ADR-0099
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P099)
 * Requirement Trace: FR-044, FR-066, FR-068, FR-070
 *
 * Client-side dunning alert evaluation, urgency detection, bilingual banner
 * rendering, and acknowledgment logic for JavaFX Desktop POS.
 */
public class PosDunningNotificationClient {

    public enum DunningStage {
        UPCOMING_RENEWAL,
        PAYMENT_FAILED,
        GRACE_PERIOD_ACTIVE,
        SUBSCRIPTION_EXPIRED,
        ACCOUNT_SUSPENDED,
        PAYMENT_RECOVERED
    }

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        OFFLINE,
        ERROR,
        PERMISSION_DENIED
    }

    public static final class DunningAlert {
        public final String notificationId;
        public final String tenantId;
        public final String invoiceId;
        public final DunningStage stage;
        public final String deliveryStatus; // PENDING, DELIVERED, FAILED, SUPPRESSED
        public final String titleEn;
        public final String titleBn;
        public final String bodyEn;
        public final String bodyBn;
        public final BigDecimal amountDueBdt;
        public final boolean isAcknowledged;
        public final int retryCount;
        public final String createdAt;

        public DunningAlert(
                String notificationId,
                String tenantId,
                String invoiceId,
                DunningStage stage,
                String deliveryStatus,
                String titleEn,
                String titleBn,
                String bodyEn,
                String bodyBn,
                BigDecimal amountDueBdt,
                boolean isAcknowledged,
                int retryCount,
                String createdAt) {
            this.notificationId = notificationId;
            this.tenantId = tenantId;
            this.invoiceId = invoiceId;
            this.stage = stage;
            this.deliveryStatus = deliveryStatus != null ? deliveryStatus : "DELIVERED";
            this.titleEn = titleEn;
            this.titleBn = titleBn;
            this.bodyEn = bodyEn;
            this.bodyBn = bodyBn;
            this.amountDueBdt = amountDueBdt != null ? amountDueBdt : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.isAcknowledged = isAcknowledged;
            this.retryCount = retryCount;
            this.createdAt = createdAt;
        }

        public boolean isUrgent() {
            return stage == DunningStage.PAYMENT_FAILED
                    || stage == DunningStage.GRACE_PERIOD_ACTIVE
                    || stage == DunningStage.SUBSCRIPTION_EXPIRED
                    || stage == DunningStage.ACCOUNT_SUSPENDED;
        }

        public boolean isSuppressed() {
            return "SUPPRESSED".equalsIgnoreCase(deliveryStatus);
        }

        public boolean isRecovered() {
            return stage == DunningStage.PAYMENT_RECOVERED;
        }
    }

    public static final class DunningUiState {
        public final UiStatus status;
        public final List<DunningAlert> alerts;
        public final int unacknowledgedCount;
        public final String errorMessageEn;
        public final String errorMessageBn;

        public DunningUiState(
                UiStatus status,
                List<DunningAlert> alerts,
                int unacknowledgedCount,
                String errorMessageEn,
                String errorMessageBn) {
            this.status = status;
            this.alerts = alerts != null ? alerts : Collections.emptyList();
            this.unacknowledgedCount = unacknowledgedCount;
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

    public static DunningUiState createPermissionDeniedState(String role) {
        return new DunningUiState(
                UiStatus.PERMISSION_DENIED,
                Collections.emptyList(),
                0,
                "Access denied for role '" + role + "'. Only pharmacy owners and administrators can view dunning notifications.",
                "আপনার রোলে ('" + role + "') ডানিং নোটিফিকেশন দেখার অনুমতি নেই। শুধুমাত্র মালিক ও অ্যাডমিনদের প্রবেশাধিকার আছে।"
        );
    }

    public static DunningUiState createOfflineState() {
        return new DunningUiState(
                UiStatus.OFFLINE,
                Collections.emptyList(),
                0,
                "Device is currently offline. Dunning notifications require cloud server connection.",
                "ডিভাইসটি অফলাইনে আছে। ডানিং নোটিফিকেশন দেখতে ক্লাউড সার্ভারের সাথে সক্রিয় ইন্টারনেট সংযোগ প্রয়োজন।"
        );
    }
}
