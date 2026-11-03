package com.pharmacy.pos.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosMedicineDetailsPreviewClient
 * Architecture Decision: ADR-0138 (P138).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * JavaFX Desktop POS Client for Unified Medicine Details Preview:
 * Form, Strength Taxonomy, Administration Route, Packaging Hierarchy,
 * Rx / AWaRe Status, Cold Chain Storage, Variants, and Recall Alerts.
 */
public class PosMedicineDetailsPreviewClient {

    public enum UiStatus {
        SUCCESS,
        ERROR,
        PERMISSION_DENIED,
        EMPTY,
        OFFLINE
    }

    public static final class PackagingTierRecord {
        public final String packagingLevel;
        public final int multiplier;
        public final String barcodeValue;
        public final int priorityOrder;

        public PackagingTierRecord(String packagingLevel, int multiplier, String barcodeValue, int priorityOrder) {
            this.packagingLevel = packagingLevel;
            this.multiplier = multiplier;
            this.barcodeValue = barcodeValue != null ? barcodeValue : "";
            this.priorityOrder = priorityOrder;
        }
    }

    public static final class VariantRecord {
        public final String variantId;
        public final String sku;
        public final String variantName;
        public final String strength;
        public final String packSize;
        public final int packSizeNumeric;
        public final String containerType;
        public final double mrpPack;
        public final double costPack;
        public final String barcode;

        public VariantRecord(
                String variantId,
                String sku,
                String variantName,
                String strength,
                String packSize,
                int packSizeNumeric,
                String containerType,
                double mrpPack,
                double costPack,
                String barcode) {
            this.variantId = variantId;
            this.sku = sku;
            this.variantName = variantName;
            this.strength = strength;
            this.packSize = packSize;
            this.packSizeNumeric = packSizeNumeric;
            this.containerType = containerType;
            this.mrpPack = mrpPack;
            this.costPack = costPack;
            this.barcode = barcode != null ? barcode : "";
        }
    }

    public static final class ActiveRecallRecord {
        public final String noticeNumber;
        public final String regulatoryAuthority;
        public final String lifecycleStatus;
        public final String reason;
        public final String actionRequired;

        public ActiveRecallRecord(
                String noticeNumber,
                String regulatoryAuthority,
                String lifecycleStatus,
                String reason,
                String actionRequired) {
            this.noticeNumber = noticeNumber;
            this.regulatoryAuthority = regulatoryAuthority;
            this.lifecycleStatus = lifecycleStatus;
            this.reason = reason;
            this.actionRequired = actionRequired;
        }
    }

    public static final class MedicinePreviewRecord {
        public final String productId;
        public final String brandName;
        public final String brandNameBn;
        public final String genericName;
        public final String genericNameBn;
        public final String manufacturer;
        public final String dgdaDarNumber;
        public final String curationStatus;

        public final String dosageForm;
        public final String strength;
        public final Double strengthNumeric;
        public final String strengthUnit;
        public final String strengthUnitFamily;
        public final String route;
        public final String primaryBarcode;

        public final double mrpUnit;
        public final Double costUnit; // Masked to null for Cashiers
        public final double effectiveMrpUnit;
        public final boolean hasTenantOverride;
        public final String localBrandName;
        public final String shelfLocation;

        public final boolean isPrescriptionOnly;
        public final boolean isNarcotic;
        public final boolean isAntibiotic;
        public final String antimicrobialClassification;
        public final String rxClassificationLabel;
        public final String rxClassificationLabelBn;

        public final boolean requiresColdChain;
        public final Double minTempCelsius;
        public final Double maxTempCelsius;
        public final String storageInstructions;
        public final String storageInstructionsBn;

        public final List<PackagingTierRecord> packagingHierarchy;
        public final List<VariantRecord> variants;

        public final boolean isRecalled;
        public final boolean isDiscontinued;
        public final boolean isDispensingBlocked;
        public final ActiveRecallRecord activeRecallNotice;
        public final String immutablePreviewHash;

        public MedicinePreviewRecord(
                String productId,
                String brandName,
                String brandNameBn,
                String genericName,
                String genericNameBn,
                String manufacturer,
                String dgdaDarNumber,
                String curationStatus,
                String dosageForm,
                String strength,
                Double strengthNumeric,
                String strengthUnit,
                String strengthUnitFamily,
                String route,
                String primaryBarcode,
                double mrpUnit,
                Double costUnit,
                double effectiveMrpUnit,
                boolean hasTenantOverride,
                String localBrandName,
                String shelfLocation,
                boolean isPrescriptionOnly,
                boolean isNarcotic,
                boolean isAntibiotic,
                String antimicrobialClassification,
                String rxClassificationLabel,
                String rxClassificationLabelBn,
                boolean requiresColdChain,
                Double minTempCelsius,
                Double maxTempCelsius,
                String storageInstructions,
                String storageInstructionsBn,
                List<PackagingTierRecord> packagingHierarchy,
                List<VariantRecord> variants,
                boolean isRecalled,
                boolean isDiscontinued,
                boolean isDispensingBlocked,
                ActiveRecallRecord activeRecallNotice,
                String immutablePreviewHash) {
            this.productId = productId;
            this.brandName = brandName;
            this.brandNameBn = brandNameBn != null ? brandNameBn : "";
            this.genericName = genericName;
            this.genericNameBn = genericNameBn != null ? genericNameBn : "";
            this.manufacturer = manufacturer;
            this.dgdaDarNumber = dgdaDarNumber != null ? dgdaDarNumber : "";
            this.curationStatus = curationStatus != null ? curationStatus : "APPROVED";
            this.dosageForm = dosageForm;
            this.strength = strength;
            this.strengthNumeric = strengthNumeric;
            this.strengthUnit = strengthUnit != null ? strengthUnit : "mg";
            this.strengthUnitFamily = strengthUnitFamily != null ? strengthUnitFamily : "MASS";
            this.route = route != null ? route : "Oral";
            this.primaryBarcode = primaryBarcode != null ? primaryBarcode : "";
            this.mrpUnit = mrpUnit;
            this.costUnit = costUnit;
            this.effectiveMrpUnit = effectiveMrpUnit;
            this.hasTenantOverride = hasTenantOverride;
            this.localBrandName = localBrandName != null ? localBrandName : "";
            this.shelfLocation = shelfLocation != null ? shelfLocation : "";
            this.isPrescriptionOnly = isPrescriptionOnly;
            this.isNarcotic = isNarcotic;
            this.isAntibiotic = isAntibiotic;
            this.antimicrobialClassification = antimicrobialClassification != null ? antimicrobialClassification : "NONE";
            this.rxClassificationLabel = rxClassificationLabel;
            this.rxClassificationLabelBn = rxClassificationLabelBn;
            this.requiresColdChain = requiresColdChain;
            this.minTempCelsius = minTempCelsius;
            this.maxTempCelsius = maxTempCelsius;
            this.storageInstructions = storageInstructions;
            this.storageInstructionsBn = storageInstructionsBn;
            this.packagingHierarchy = packagingHierarchy != null ? new ArrayList<>(packagingHierarchy) : new ArrayList<>();
            this.variants = variants != null ? new ArrayList<>(variants) : new ArrayList<>();
            this.isRecalled = isRecalled;
            this.isDiscontinued = isDiscontinued;
            this.isDispensingBlocked = isDispensingBlocked;
            this.activeRecallNotice = activeRecallNotice;
            this.immutablePreviewHash = immutablePreviewHash;
        }
    }

    public static final class ClientResult<T> {
        public final UiStatus status;
        public final T data;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        private ClientResult(UiStatus status, T data, String errorCode, String messageEn, String messageBn) {
            this.status = status;
            this.data = data;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static <T> ClientResult<T> success(T data) {
            return new ClientResult<>(UiStatus.SUCCESS, data, null, "Operation completed successfully.", "অপারেশন সফলভাবে সম্পন্ন হয়েছে।");
        }

        public static <T> ClientResult<T> empty(String en, String bn) {
            return new ClientResult<>(UiStatus.EMPTY, null, "EMPTY_RESULT", en, bn);
        }

        public static <T> ClientResult<T> offline(T data) {
            return new ClientResult<>(UiStatus.OFFLINE, data, "STATUS_OFFLINE_CACHE", "Operating in offline cached mode.", "অফলাইন ক্যাশড মোড সক্রিয়।");
        }

        public static <T> ClientResult<T> permissionDenied(String code, String en, String bn) {
            return new ClientResult<>(UiStatus.PERMISSION_DENIED, null, code, en, bn);
        }

        public static <T> ClientResult<T> error(String code, String en, String bn) {
            return new ClientResult<>(UiStatus.ERROR, null, code, en, bn);
        }
    }

    private final Map<String, MedicinePreviewRecord> previewStore = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> overrideStore = new ConcurrentHashMap<>();
    private final Map<String, ActiveRecallRecord> recallStore = new ConcurrentHashMap<>();
    private boolean isOffline = false;

    public void setOffline(boolean offline) {
        this.isOffline = offline;
    }

    public boolean isOffline() {
        return isOffline;
    }

    private boolean isAuthorizedManager(String role) {
        if (role == null) return false;
        String r = role.trim().toUpperCase();
        return r.equals("SUPERADMIN") || r.equals("PLATFORM_OPERATOR") ||
                r.equals("OWNER") || r.equals("PHARMACY_OWNER") ||
                r.equals("MANAGER") || r.equals("BRANCH_MANAGER") ||
                r.equals("PHARMACIST") || r.equals("STAFF_PHARMACIST");
    }

    public void registerMedicine(MedicinePreviewRecord record) {
        previewStore.put(record.productId, record);
    }

    public void setTenantOverride(String tenantId, String productId, double customMrp, String localBrand, String shelf) {
        String key = tenantId + "|" + productId;
        Map<String, Object> map = new HashMap<>();
        map.put("customMrp", customMrp);
        map.put("localBrand", localBrand);
        map.put("shelf", shelf);
        overrideStore.put(key, map);
    }

    public void setRecallNotice(String productId, ActiveRecallRecord recall) {
        recallStore.put(productId, recall);
    }

    public ClientResult<MedicinePreviewRecord> getMedicinePreview(String targetTenantId, String actorTenantId, String productId, String userRole) {
        if (productId == null || productId.trim().isEmpty()) {
            return ClientResult.error("ERR_INVALID_PRODUCT_ID", "Product ID is required.", "ওষুধের আইডি প্রয়োজন।");
        }

        boolean isSuperadmin = userRole != null && userRole.trim().equalsIgnoreCase("SUPERADMIN");

        // Cross-Tenant Guard
        if (targetTenantId != null && !isSuperadmin) {
            if (actorTenantId != null && !targetTenantId.trim().equalsIgnoreCase(actorTenantId.trim())) {
                return ClientResult.permissionDenied(
                        "ERR_CROSS_TENANT_ACCESS",
                        "Cannot query medicine details preview with overrides from another tenant.",
                        "অন্য ফার্মেসির মেটাডাটা সহ প্রিভিউ দেখার অনুমতি নেই।"
                );
            }
        }

        MedicinePreviewRecord base = previewStore.get(productId);
        if (base == null) {
            return ClientResult.error("ERR_PRODUCT_NOT_FOUND", "Medicine product not found.", "ওষুধটি পাওয়া যায়নি।");
        }

        String effTenant = targetTenantId != null ? targetTenantId : actorTenantId;
        double effectiveMrp = base.mrpUnit;
        boolean hasOverride = false;
        String localBrand = base.localBrandName;
        String shelf = base.shelfLocation;

        if (effTenant != null) {
            Map<String, Object> ov = overrideStore.get(effTenant + "|" + productId);
            if (ov != null) {
                hasOverride = true;
                effectiveMrp = (Double) ov.get("customMrp");
                localBrand = (String) ov.get("localBrand");
                shelf = (String) ov.get("shelf");
            }
        }

        // Check recall
        ActiveRecallRecord recall = recallStore.get(productId);
        boolean isRecalled = base.isRecalled || (recall != null && recall.lifecycleStatus.equalsIgnoreCase("RECALLED"));
        boolean isDispensingBlocked = base.isDispensingBlocked || isRecalled;

        // Mask wholesale cost price for Cashier role
        Double visibleCost = isAuthorizedManager(userRole) ? base.costUnit : null;

        String hash = computePreviewHash(base.productId, base.brandName, base.dosageForm, base.strength, effectiveMrp, isDispensingBlocked);

        MedicinePreviewRecord resolved = new MedicinePreviewRecord(
                base.productId,
                base.brandName,
                base.brandNameBn,
                base.genericName,
                base.genericNameBn,
                base.manufacturer,
                base.dgdaDarNumber,
                base.curationStatus,
                base.dosageForm,
                base.strength,
                base.strengthNumeric,
                base.strengthUnit,
                base.strengthUnitFamily,
                base.route,
                base.primaryBarcode,
                base.mrpUnit,
                visibleCost,
                effectiveMrp,
                hasOverride,
                localBrand,
                shelf,
                base.isPrescriptionOnly,
                base.isNarcotic,
                base.isAntibiotic,
                base.antimicrobialClassification,
                base.rxClassificationLabel,
                base.rxClassificationLabelBn,
                base.requiresColdChain,
                base.minTempCelsius,
                base.maxTempCelsius,
                base.storageInstructions,
                base.storageInstructionsBn,
                base.packagingHierarchy,
                base.variants,
                isRecalled,
                base.isDiscontinued,
                isDispensingBlocked,
                recall != null ? recall : base.activeRecallNotice,
                hash
        );

        if (isOffline) {
            return ClientResult.offline(resolved);
        }
        return ClientResult.success(resolved);
    }

    private String computePreviewHash(String id, String brand, String form, String strength, double mrp, boolean blocked) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = String.format("%s|%s|%s|%s|%.2f|%b", id, brand, form, strength, mrp, blocked);
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
