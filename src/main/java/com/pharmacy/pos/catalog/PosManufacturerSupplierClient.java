package com.pharmacy.pos.catalog;

import java.util.*;
import java.util.stream.Collectors;

/**
 * PosManufacturerSupplierClient
 * Architecture Decision: ADR-0127 (P127).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * Client-side integration for JavaFX Desktop POS managing global manufacturer master catalog
 * and tenant-scoped supplier directory with merge policy and duplicate resolution.
 */
public class PosManufacturerSupplierClient {

    public enum UiStatus {
        SUCCESS,
        ERROR,
        PERMISSION_DENIED,
        EMPTY,
        OFFLINE
    }

    public static final class ManufacturerRecord {
        public final String manufacturerId;
        public final String name;
        public final String nameBn;
        public final String code;
        public final String dgdaLicenseNo;
        public final boolean isActive;
        public String mergedIntoId;

        public ManufacturerRecord(
                String manufacturerId,
                String name,
                String nameBn,
                String code,
                String dgdaLicenseNo,
                boolean isActive,
                String mergedIntoId) {
            this.manufacturerId = manufacturerId;
            this.name = name;
            this.nameBn = nameBn;
            this.code = code;
            this.dgdaLicenseNo = dgdaLicenseNo;
            this.isActive = isActive;
            this.mergedIntoId = mergedIntoId;
        }
    }

    public static final class SupplierRecord {
        public final String supplierId;
        public final String tenantId;
        public final String name;
        public final String contactPerson;
        public final String phone;
        public final String email;
        public final String dlNumber;
        public boolean isActive;
        public String mergedIntoId;

        public SupplierRecord(
                String supplierId,
                String tenantId,
                String name,
                String contactPerson,
                String phone,
                String email,
                String dlNumber,
                boolean isActive,
                String mergedIntoId) {
            this.supplierId = supplierId;
            this.tenantId = tenantId;
            this.name = name;
            this.contactPerson = contactPerson;
            this.phone = phone;
            this.email = email;
            this.dlNumber = dlNumber;
            this.isActive = isActive;
            this.mergedIntoId = mergedIntoId;
        }
    }

    public static final class MergeResult {
        public final UiStatus uiStatus;
        public final boolean isMerged;
        public final String sourceId;
        public final String targetId;
        public final String targetName;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public MergeResult(UiStatus uiStatus, boolean isMerged, String sourceId, String targetId, String targetName, String errorCode, String messageEn, String messageBn) {
            this.uiStatus = uiStatus;
            this.isMerged = isMerged;
            this.sourceId = sourceId;
            this.targetId = targetId;
            this.targetName = targetName;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static MergeResult success(String sourceId, String targetId, String targetName) {
            return new MergeResult(
                    UiStatus.SUCCESS,
                    true,
                    sourceId,
                    targetId,
                    targetName,
                    null,
                    "Merged successfully into " + targetName,
                    targetName + "-এ সফলভাবে মার্জ সম্পন্ন হয়েছে।"
            );
        }

        public static MergeResult error(UiStatus status, String code, String msgEn, String msgBn) {
            return new MergeResult(
                    status,
                    false,
                    "",
                    "",
                    "",
                    code,
                    msgEn,
                    msgBn
            );
        }
    }

    private final Map<String, ManufacturerRecord> manufacturers = new HashMap<>();
    private final Map<String, SupplierRecord> suppliers = new HashMap<>();

    public void registerManufacturer(ManufacturerRecord m) {
        manufacturers.put(m.manufacturerId, m);
    }

    public void registerSupplier(SupplierRecord s) {
        suppliers.put(s.supplierId, s);
    }

    public List<ManufacturerRecord> listActiveManufacturers() {
        return manufacturers.values().stream()
                .filter(m -> m.isActive && m.mergedIntoId == null)
                .collect(Collectors.toList());
    }

    public List<SupplierRecord> listActiveSuppliers(String tenantId) {
        return suppliers.values().stream()
                .filter(s -> s.tenantId.equals(tenantId) && s.isActive && s.mergedIntoId == null)
                .collect(Collectors.toList());
    }

    public MergeResult mergeSuppliers(String tenantId, String callerRole, String sourceId, String targetId, String reason) {
        String role = callerRole != null ? callerRole.toUpperCase() : "";
        if ("CASHIER".equals(role)) {
            return MergeResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashier role is not permitted to merge suppliers.",
                    "ক্যাশিয়ার সরবরাহকারী মার্জ করার অনুমতিপ্রাপ্ত নন।"
            );
        }

        if (sourceId.equals(targetId)) {
            return MergeResult.error(
                    UiStatus.ERROR,
                    "ERR_CANNOT_MERGE_SELF",
                    "Cannot merge a supplier into itself.",
                    "একই সরবরাহকারীকে নিজের সাথে মার্জ করা যাবে না।"
            );
        }

        SupplierRecord source = suppliers.get(sourceId);
        SupplierRecord target = suppliers.get(targetId);

        if (source == null || target == null) {
            return MergeResult.error(UiStatus.ERROR, "ERR_SUPPLIER_NOT_FOUND", "Source or target supplier not found.", "সরবরাহকারী পাওয়া যায়নি।");
        }

        // Strict tenant isolation
        if (!source.tenantId.equals(tenantId) || !target.tenantId.equals(tenantId)) {
            return MergeResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "ERR_CROSS_TENANT_ACCESS",
                    "Cross-tenant supplier merge is strictly forbidden.",
                    "ভিন্ন ফার্মেসির সরবরাহকারী প্রতিষ্ঠান মার্জ করা সম্পূর্ণ নিষিদ্ধ।"
            );
        }

        if (!target.isActive || target.mergedIntoId != null) {
            return MergeResult.error(
                    UiStatus.ERROR,
                    "ERR_TARGET_ALREADY_MERGED",
                    "Target supplier is inactive or already merged.",
                    "লক্ষ্য সরবরাহকারীটি ইতিমধ্যে নিষ্ক্রিয় বা মার্জ করা হয়েছে।"
            );
        }

        // Execute merge
        source.isActive = false;
        source.mergedIntoId = target.supplierId;

        return MergeResult.success(sourceId, targetId, target.name);
    }

    public MergeResult attemptCreateManufacturer(String callerRole, ManufacturerRecord record) {
        String role = callerRole != null ? callerRole.toUpperCase() : "";
        if (!"SUPERADMIN".equals(role) && !"PLATFORM_OPERATOR".equals(role)) {
            return MergeResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "PLATFORM_CATALOG_OWNERSHIP_PROTECTED",
                    "Pharmaceutical manufacturers are centrally curated by platform administrators.",
                    "ঔষধ প্রস্তুতকারক প্রতিষ্ঠানসমূহ কেন্দ্রীয়ভাবে প্ল্যাটফর্ম অ্যাডমিনিস্ট্রেটর দ্বারা পরিচালিত।"
            );
        }
        registerManufacturer(record);
        return MergeResult.success(record.manufacturerId, record.manufacturerId, record.name);
    }
}
