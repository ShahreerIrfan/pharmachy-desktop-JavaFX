package com.pharmacy.pos.settings;

import java.util.*;

/**
 * PosBranchOperatingSettingsClient
 * Architecture Decision: ADR-0116 (P116).
 * Phase 06: Milestone M03 - Offline-First Desktop POS & Owner Visibility.
 *
 * Client-side integration for JavaFX Desktop POS managing branch receipt formats,
 * operating hours, storage zones, low-stock thresholds, tax defaults, and device policies.
 */
public class PosBranchOperatingSettingsClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class ReceiptFormat {
        public final String paperWidth;
        public final String headerText;
        public final String footerText;
        public final boolean showTaxBreakdown;
        public final String language;

        public ReceiptFormat(String paperWidth, String headerText, String footerText, boolean showTaxBreakdown, String language) {
            this.paperWidth = paperWidth != null ? paperWidth : "80mm";
            this.headerText = headerText != null ? headerText : "";
            this.footerText = footerText != null ? footerText : "";
            this.showTaxBreakdown = showTaxBreakdown;
            this.language = language != null ? language : "BILINGUAL";
        }
    }

    public static final class DevicePolicies {
        public final int maxActivePosTerminals;
        public final boolean allowOfflinePosSales;
        public final int maxOfflineHours;
        public final boolean requireCashDrawerKick;

        public DevicePolicies(int maxActivePosTerminals, boolean allowOfflinePosSales, int maxOfflineHours, boolean requireCashDrawerKick) {
            this.maxActivePosTerminals = maxActivePosTerminals;
            this.allowOfflinePosSales = allowOfflinePosSales;
            this.maxOfflineHours = maxOfflineHours;
            this.requireCashDrawerKick = requireCashDrawerKick;
        }
    }

    public static final class BranchSettingsRecord {
        public final String settingsId;
        public final String tenantId;
        public final String branchId;
        public final String branchName;
        public final ReceiptFormat receiptFormat;
        public final DevicePolicies devicePolicies;
        public final double defaultVatRate;
        public final int minLowStockStrips;

        public BranchSettingsRecord(
                String settingsId,
                String tenantId,
                String branchId,
                String branchName,
                ReceiptFormat receiptFormat,
                DevicePolicies devicePolicies,
                double defaultVatRate,
                int minLowStockStrips) {
            this.settingsId = settingsId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.branchName = branchName;
            this.receiptFormat = receiptFormat;
            this.devicePolicies = devicePolicies;
            this.defaultVatRate = defaultVatRate;
            this.minLowStockStrips = minLowStockStrips;
        }
    }

    public static final class SettingsOpResult {
        public final UiStatus uiStatus;
        public final BranchSettingsRecord record;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public SettingsOpResult(UiStatus uiStatus, BranchSettingsRecord record, String errorCode, String messageEn, String messageBn) {
            this.uiStatus = uiStatus;
            this.record = record;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static SettingsOpResult success(BranchSettingsRecord record) {
            return new SettingsOpResult(
                    UiStatus.SUCCESS, record, null,
                    "Operating settings loaded successfully.",
                    "ব্রাঞ্চ অপারেটিং সেটিংস সফলভাবে লোড হয়েছে।"
            );
        }

        public static SettingsOpResult error(UiStatus status, String code, String msgEn, String msgBn) {
            return new SettingsOpResult(status, null, code, msgEn, msgBn);
        }
    }

    public SettingsOpResult evaluateAccess(
            String callerRole,
            String callerTenantId,
            String callerBranchId,
            BranchSettingsRecord record,
            boolean isWriteOperation) {

        if (record == null) {
            return SettingsOpResult.error(UiStatus.ERROR, "SETTINGS_NOT_FOUND", "Settings record missing.", "সেটিংস তথ্য পাওয়া যায়নি।");
        }

        // Cross-tenant check
        if (!Objects.equals(callerTenantId, record.tenantId) && !"SUPERADMIN".equalsIgnoreCase(callerRole)) {
            return SettingsOpResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_CROSS_TENANT_VIOLATION",
                    "Cross-tenant access forbidden.",
                    "অন্য টেন্যান্টের সেটিংস অ্যাক্সেস করা যাবে না।"
            );
        }

        boolean isOwner = "PHARMACY_OWNER".equalsIgnoreCase(callerRole) || "SUPERADMIN".equalsIgnoreCase(callerRole);
        boolean isManager = "BRANCH_MANAGER".equalsIgnoreCase(callerRole);

        if (isWriteOperation) {
            if (!isOwner && !isManager) {
                return SettingsOpResult.error(
                        UiStatus.PERMISSION_DENIED,
                        "ERR_PERMISSION_DENIED",
                        "Staff members lack permission to modify branch operating settings.",
                        "স্টাফদের ব্রাঞ্চ সেটিংস পরিবর্তনের অনুমতি নেই।"
                );
            }
            if (isManager && !Objects.equals(callerBranchId, record.branchId)) {
                return SettingsOpResult.error(
                        UiStatus.PERMISSION_DENIED,
                        "ERR_CROSS_BRANCH_VIOLATION",
                        "Branch managers cannot modify foreign branch settings.",
                        "শাখা ব্যবস্থাপক অন্য শাখার সেটিংস পরিবর্তন করতে পারবেন না।"
                );
            }
        }

        return SettingsOpResult.success(record);
    }
}

