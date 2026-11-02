package com.pharmacy.pos.catalog;

import java.util.*;
import java.util.stream.Collectors;

/**
 * PosMedicineMasterClient
 * Architecture Decision: ADR-0121 (P121).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * Client-side integration for JavaFX Desktop POS querying the global medicine master,
 * searching by brand/generic/barcode, and enforcing centralized platform curation protection.
 */
public class PosMedicineMasterClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class MedicineRecord {
        public final String productId;
        public final String brandName;
        public final String genericName;
        public final String dosageForm;
        public final String strength;
        public final String manufacturer;
        public final String route;
        public final String barcode;
        public final double mrpUnit;
        public final String dgdaDarNumber;
        public final String curationStatus;
        public final int versionNumber;
        public final String immutableVersionHash;

        public MedicineRecord(
                String productId,
                String brandName,
                String genericName,
                String dosageForm,
                String strength,
                String manufacturer,
                String route,
                String barcode,
                double mrpUnit,
                String dgdaDarNumber,
                String curationStatus,
                int versionNumber,
                String immutableVersionHash) {
            this.productId = productId;
            this.brandName = brandName;
            this.genericName = genericName;
            this.dosageForm = dosageForm;
            this.strength = strength;
            this.manufacturer = manufacturer;
            this.route = route != null ? route : "Oral";
            this.barcode = barcode != null ? barcode : "";
            this.mrpUnit = mrpUnit;
            this.dgdaDarNumber = dgdaDarNumber != null ? dgdaDarNumber : "";
            this.curationStatus = curationStatus != null ? curationStatus : "APPROVED";
            this.versionNumber = versionNumber;
            this.immutableVersionHash = immutableVersionHash;
        }
    }

    public static final class CatalogQueryResult {
        public final UiStatus uiStatus;
        public final List<MedicineRecord> medicines;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public CatalogQueryResult(UiStatus uiStatus, List<MedicineRecord> medicines, String errorCode, String messageEn, String messageBn) {
            this.uiStatus = uiStatus;
            this.medicines = medicines != null ? medicines : Collections.emptyList();
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static CatalogQueryResult success(List<MedicineRecord> list) {
            if (list == null || list.isEmpty()) {
                return new CatalogQueryResult(
                        UiStatus.EMPTY,
                        Collections.emptyList(),
                        null,
                        "No medicines found matching criteria.",
                        "কোনো ওষুধ পাওয়া যায়নি।"
                );
            }
            return new CatalogQueryResult(
                    UiStatus.SUCCESS,
                    list,
                    null,
                    "Found " + list.size() + " medicine(s).",
                    list.size() + " টি ওষুধ পাওয়া গেছে।"
            );
        }

        public static CatalogQueryResult error(UiStatus status, String code, String msgEn, String msgBn) {
            return new CatalogQueryResult(status, Collections.emptyList(), code, msgEn, msgBn);
        }
    }

    /**
     * High-speed barcode scanner & multi-term search across brand, generic, manufacturer, and barcode.
     */
    public CatalogQueryResult search(
            List<MedicineRecord> sourceList,
            String query,
            String filterRoute,
            String filterDosageForm) {

        if (sourceList == null || sourceList.isEmpty()) {
            return CatalogQueryResult.success(Collections.emptyList());
        }

        String q = query != null ? query.trim().toLowerCase() : "";

        List<MedicineRecord> matched = sourceList.stream()
                .filter(m -> "APPROVED".equalsIgnoreCase(m.curationStatus))
                .filter(m -> filterRoute == null || filterRoute.isEmpty() || filterRoute.equalsIgnoreCase(m.route))
                .filter(m -> filterDosageForm == null || filterDosageForm.isEmpty() || filterDosageForm.equalsIgnoreCase(m.dosageForm))
                .filter(m -> {
                    if (q.isEmpty()) return true;
                    // Exact barcode match takes highest priority for handheld barcode scanners
                    if (!m.barcode.isEmpty() && m.barcode.equals(q)) return true;
                    return m.brandName.toLowerCase().contains(q)
                            || m.genericName.toLowerCase().contains(q)
                            || m.manufacturer.toLowerCase().contains(q)
                            || m.dgdaDarNumber.toLowerCase().contains(q);
                })
                .collect(Collectors.toList());

        return CatalogQueryResult.success(matched);
    }

    /**
     * Tenant protection guard: only platform operators can mutate global master records.
     */
    public CatalogQueryResult attemptMutateGlobalMaster(String callerRole, MedicineRecord item) {
        if (!"SUPERADMIN".equalsIgnoreCase(callerRole) && !"PLATFORM_OPERATOR".equalsIgnoreCase(callerRole)) {
            return CatalogQueryResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "PLATFORM_CATALOG_OWNERSHIP_PROTECTED",
                    "Global medicine catalog is centrally protected. Tenants cannot modify master records.",
                    "গ্লোবাল মেডিসিন ক্যাটালগ কেন্দ্রীয়ভাবে সুরক্ষিত। কোনো ফার্মেসি এটি পরিবর্তন বা মুছতে পারবে না।"
            );
        }
        return CatalogQueryResult.success(Collections.singletonList(item));
    }
}
