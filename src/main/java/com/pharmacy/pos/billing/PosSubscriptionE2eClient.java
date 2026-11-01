package com.pharmacy.pos.billing;

import java.util.Collections;
import java.util.List;

/**
 * PosSubscriptionE2eClient
 * Architecture Decision: ADR-0100
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P100)
 * Requirement Trace: FR-044, FR-066, FR-070
 *
 * Client-side integration for Subscription End-to-End lifecycle orchestration,
 * support override exceptions, and emergency relief rendering in JavaFX Desktop POS.
 */
public class PosSubscriptionE2eClient {

    public enum OverrideType {
        GRACE_EXTENSION,
        EMERGENCY_UNBLOCK,
        QUOTA_OVERRIDE,
        REACTIVATION
    }

    public enum OverrideStatus {
        ACTIVE,
        EXPIRED,
        REVOKED
    }

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        OFFLINE,
        ERROR,
        PERMISSION_DENIED
    }

    public static final class SupportOverrideRecord {
        public final String overrideId;
        public final String tenantId;
        public final String subscriptionId;
        public final OverrideType overrideType;
        public final OverrideStatus status;
        public final Integer extensionDays;
        public final Integer quotaBonus;
        public final String justification;
        public final String ticketReference;
        public final String grantedByUserId;
        public final String grantedAt;
        public final String expiresAt;
        public final String revokedAt;
        public final String revokedByUserId;
        public final String revocationReason;

        public SupportOverrideRecord(
                String overrideId,
                String tenantId,
                String subscriptionId,
                OverrideType overrideType,
                OverrideStatus status,
                Integer extensionDays,
                Integer quotaBonus,
                String justification,
                String ticketReference,
                String grantedByUserId,
                String grantedAt,
                String expiresAt,
                String revokedAt,
                String revokedByUserId,
                String revocationReason) {
            this.overrideId = overrideId;
            this.tenantId = tenantId;
            this.subscriptionId = subscriptionId;
            this.overrideType = overrideType;
            this.status = status;
            this.extensionDays = extensionDays != null ? extensionDays : 0;
            this.quotaBonus = quotaBonus != null ? quotaBonus : 0;
            this.justification = justification;
            this.ticketReference = ticketReference;
            this.grantedByUserId = grantedByUserId;
            this.grantedAt = grantedAt;
            this.expiresAt = expiresAt;
            this.revokedAt = revokedAt;
            this.revokedByUserId = revokedByUserId;
            this.revocationReason = revocationReason;
        }

        public boolean isActive() {
            return status == OverrideStatus.ACTIVE;
        }

        public boolean isEmergencyUnblock() {
            return isActive() && overrideType == OverrideType.EMERGENCY_UNBLOCK;
        }

        public boolean isGraceExtended() {
            return isActive() && overrideType == OverrideType.GRACE_EXTENSION;
        }
    }

    public static final class LifecycleE2eUiState {
        public final UiStatus status;
        public final List<SupportOverrideRecord> overrides;
        public final boolean hasActiveOverride;
        public final String activeOverrideBadgeEn;
        public final String activeOverrideBadgeBn;
        public final String errorMessageEn;
        public final String errorMessageBn;

        public LifecycleE2eUiState(
                UiStatus status,
                List<SupportOverrideRecord> overrides,
                boolean hasActiveOverride,
                String activeOverrideBadgeEn,
                String activeOverrideBadgeBn,
                String errorMessageEn,
                String errorMessageBn) {
            this.status = status;
            this.overrides = overrides != null ? overrides : Collections.emptyList();
            this.hasActiveOverride = hasActiveOverride;
            this.activeOverrideBadgeEn = activeOverrideBadgeEn;
            this.activeOverrideBadgeBn = activeOverrideBadgeBn;
            this.errorMessageEn = errorMessageEn;
            this.errorMessageBn = errorMessageBn;
        }
    }

    public static LifecycleE2eUiState buildSuccessState(List<SupportOverrideRecord> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return new LifecycleE2eUiState(
                    UiStatus.EMPTY,
                    Collections.emptyList(),
                    false,
                    null,
                    null,
                    null,
                    null
            );
        }

        SupportOverrideRecord active = null;
        for (SupportOverrideRecord ovr : overrides) {
            if (ovr.isActive()) {
                active = ovr;
                break;
            }
        }

        boolean hasActive = active != null;
        String badgeEn = null;
        String badgeBn = null;

        if (hasActive) {
            switch (active.overrideType) {
                case EMERGENCY_UNBLOCK:
                    badgeEn = "Emergency Operational Unblock Active (Ticket: " + active.ticketReference + ")";
                    badgeBn = "জরুরি অপারেশনাল আনব্লক সক্রিয় (টিকেট: " + active.ticketReference + ")";
                    break;
                case GRACE_EXTENSION:
                    badgeEn = "Grace Period Extended by " + active.extensionDays + " Days (Ticket: " + active.ticketReference + ")";
                    badgeBn = "গ্রেস পিরিয়ড " + active.extensionDays + " দিন বাড়ানো হয়েছে (টিকেট: " + active.ticketReference + ")";
                    break;
                case QUOTA_OVERRIDE:
                    badgeEn = "Capacity Quota Override (+" + active.quotaBonus + " seats)";
                    badgeBn = "সামর্থ্য কোটা বৃদ্ধি (+" + active.quotaBonus + " আসন)";
                    break;
                case REACTIVATION:
                    badgeEn = "Emergency Reactivation Active";
                    badgeBn = "জরুরি রি-অ্যাক্টিভেশন সক্রিয়";
                    break;
            }
        }

        return new LifecycleE2eUiState(
                UiStatus.SUCCESS,
                overrides,
                hasActive,
                badgeEn,
                badgeBn,
                null,
                null
        );
    }

    public static LifecycleE2eUiState createPermissionDeniedState(String role) {
        return new LifecycleE2eUiState(
                UiStatus.PERMISSION_DENIED,
                Collections.emptyList(),
                false,
                null,
                null,
                "Access denied for role '" + role + "'. Only authorized administrators can view support overrides.",
                "আপনার রোলে ('" + role + "') সাপোর্ট ওভাররাইড দেখার অনুমতি নেই।"
        );
    }

    public static LifecycleE2eUiState createOfflineState() {
        return new LifecycleE2eUiState(
                UiStatus.OFFLINE,
                Collections.emptyList(),
                false,
                null,
                null,
                "Device is currently offline. Verification requires cloud server connection.",
                "ডিভাইসটি অফলাইনে আছে। যাচাইয়ের জন্য ক্লাউড সার্ভারের সাথে সক্রিয় ইন্টারনেট সংযোগ প্রয়োজন।"
        );
    }
}
