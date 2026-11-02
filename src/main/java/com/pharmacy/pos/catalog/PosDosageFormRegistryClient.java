package com.pharmacy.pos.catalog;

import java.util.*;
import java.util.stream.Collectors;

/**
 * PosDosageFormRegistryClient
 * Architecture Decision: ADR-0122 (P122).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * Client-side integration for JavaFX Desktop POS querying the dosage form metadata registry,
 * field groups, packaging unit conversion applicability, and central platform curation guard.
 */
public class PosDosageFormRegistryClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class DosageFormRecord {
        public final String formId;
        public final String formCode;
        public final String nameEn;
        public final String nameBn;
        public final String category;
        public final String defaultRoute;
        public final boolean isStripApplicable;
        public final boolean isVolumeApplicable;
        public final boolean isColdChainApplicable;
        public final int displayOrder;
        public final boolean isActive;

        public DosageFormRecord(
                String formId,
                String formCode,
                String nameEn,
                String nameBn,
                String category,
                String defaultRoute,
                boolean isStripApplicable,
                boolean isVolumeApplicable,
                boolean isColdChainApplicable,
                int displayOrder,
                boolean isActive) {
            this.formId = formId;
            this.formCode = formCode;
            this.nameEn = nameEn;
            this.nameBn = nameBn;
            this.category = category;
            this.defaultRoute = defaultRoute;
            this.isStripApplicable = isStripApplicable;
            this.isVolumeApplicable = isVolumeApplicable;
            this.isColdChainApplicable = isColdChainApplicable;
            this.displayOrder = displayOrder;
            this.isActive = isActive;
        }
    }

    public static final class DosageFormResult {
        public final UiStatus uiStatus;
        public final List<DosageFormRecord> forms;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public DosageFormResult(UiStatus uiStatus, List<DosageFormRecord> forms, String errorCode, String messageEn, String messageBn) {
            this.uiStatus = uiStatus;
            this.forms = forms != null ? forms : Collections.emptyList();
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static DosageFormResult success(List<DosageFormRecord> list) {
            if (list == null || list.isEmpty()) {
                return new DosageFormResult(
                        UiStatus.EMPTY,
                        Collections.emptyList(),
                        null,
                        "No dosage forms registered.",
                        "কোনো ডোসেজ ফরম পাওয়া যায়নি।"
                );
            }
            return new DosageFormResult(
                    UiStatus.SUCCESS,
                    list,
                    null,
                    "Loaded " + list.size() + " dosage form(s).",
                    list.size() + " টি ডোসেজ ফরম লোড হয়েছে।"
            );
        }

        public static DosageFormResult error(UiStatus status, String code, String msgEn, String msgBn) {
            return new DosageFormResult(status, Collections.emptyList(), code, msgEn, msgBn);
        }
    }

    public DosageFormResult filterForms(
            List<DosageFormRecord> sourceList,
            String category,
            Boolean stripApplicableOnly) {

        if (sourceList == null || sourceList.isEmpty()) {
            return DosageFormResult.success(Collections.emptyList());
        }

        List<DosageFormRecord> filtered = sourceList.stream()
                .filter(f -> f.isActive)
                .filter(f -> category == null || category.isEmpty() || category.equalsIgnoreCase(f.category))
                .filter(f -> stripApplicableOnly == null || f.isStripApplicable == stripApplicableOnly)
                .sorted(Comparator.comparingInt(f -> f.displayOrder))
                .collect(Collectors.toList());

        return DosageFormResult.success(filtered);
    }

    public DosageFormResult attemptCreateForm(String callerRole, DosageFormRecord record) {
        if (!"SUPERADMIN".equalsIgnoreCase(callerRole) && !"PLATFORM_OPERATOR".equalsIgnoreCase(callerRole)) {
            return DosageFormResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "PLATFORM_CATALOG_OWNERSHIP_PROTECTED",
                    "Dosage form definitions are centrally managed by platform administrators.",
                    "ডোসেজ ফরম সংজ্ঞা কেন্দ্রীয়ভাবে সংরক্ষিত। কোনো ফার্মেসি এটি পরিবর্তন করতে পারবে না।"
            );
        }
        return DosageFormResult.success(Collections.singletonList(record));
    }
}
