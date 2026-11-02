package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.util.*;

/**
 * PosOverridePrecedenceClient
 * Architecture Decision: ADR-0128 (P128).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * JavaFX Desktop client for resolving global medicine values with tenant-specific
 * overrides under explicit precedence, moderation safeguards, and DGDA regulatory non-relaxation.
 */
public class PosOverridePrecedenceClient {

    public enum UiStatus {
        SUCCESS,
        ERROR,
        PERMISSION_DENIED,
        EMPTY,
        OFFLINE
    }

    public static final class FieldProvenance {
        public final Object value;
        public final String source; // TENANT_OVERRIDE, GLOBAL_MASTER, REGULATORY_MANDATE
        public final String rule;

        public FieldProvenance(Object value, String source, String rule) {
            this.value = value;
            this.source = source;
            this.rule = rule;
        }
    }

    public static final class MasterMedicine {
        public final String productId;
        public final String brandName;
        public final String genericName;
        public final String dosageForm;
        public final String strength;
        public final BigDecimal mrpUnit;
        public final BigDecimal costUnit;
        public final boolean isPrescriptionOnly;
        public final boolean isNarcotic;
        public final boolean isAntibiotic;

        public MasterMedicine(
                String productId,
                String brandName,
                String genericName,
                String dosageForm,
                String strength,
                BigDecimal mrpUnit,
                BigDecimal costUnit,
                boolean isPrescriptionOnly,
                boolean isNarcotic,
                boolean isAntibiotic) {
            this.productId = productId;
            this.brandName = brandName;
            this.genericName = genericName;
            this.dosageForm = dosageForm;
            this.strength = strength;
            this.mrpUnit = mrpUnit;
            this.costUnit = costUnit;
            this.isPrescriptionOnly = isPrescriptionOnly;
            this.isNarcotic = isNarcotic;
            this.isAntibiotic = isAntibiotic;
        }
    }

    public static final class TenantOverride {
        public final String configId;
        public final String tenantId;
        public final String productId;
        public String localBrandName;
        public String localSku;
        public BigDecimal customMrpUnit;
        public BigDecimal customCostUnit;
        public boolean isVisible;
        public String shelfLocation;
        public int lowStockThreshold;
        public Boolean requiresPrescriptionOverride;
        public String moderationStatus; // APPROVED, PENDING_REVIEW, REJECTED
        public String moderationReason;

        public TenantOverride(
                String configId,
                String tenantId,
                String productId,
                String localBrandName,
                String localSku,
                BigDecimal customMrpUnit,
                BigDecimal customCostUnit,
                boolean isVisible,
                String shelfLocation,
                int lowStockThreshold,
                Boolean requiresPrescriptionOverride,
                String moderationStatus,
                String moderationReason) {
            this.configId = configId;
            this.tenantId = tenantId;
            this.productId = productId;
            this.localBrandName = localBrandName;
            this.localSku = localSku;
            this.customMrpUnit = customMrpUnit;
            this.customCostUnit = customCostUnit;
            this.isVisible = isVisible;
            this.shelfLocation = shelfLocation;
            this.lowStockThreshold = lowStockThreshold;
            this.requiresPrescriptionOverride = requiresPrescriptionOverride;
            this.moderationStatus = moderationStatus;
            this.moderationReason = moderationReason;
        }
    }

    public static final class ResolvedMedicinePresentation {
        public final String productId;
        public final String tenantId;
        public final String effectiveBrandName;
        public final BigDecimal effectiveMrpUnit;
        public final BigDecimal effectiveCostUnit;
        public final boolean effectiveRequiresPrescription;
        public final boolean effectiveIsVisible;
        public final String effectiveShelfLocation;
        public final int effectiveLowStockThreshold;
        public final boolean isOverridden;
        public final String moderationStatus;
        public final Map<String, FieldProvenance> provenance;

        public ResolvedMedicinePresentation(
                String productId,
                String tenantId,
                String effectiveBrandName,
                BigDecimal effectiveMrpUnit,
                BigDecimal effectiveCostUnit,
                boolean effectiveRequiresPrescription,
                boolean effectiveIsVisible,
                String effectiveShelfLocation,
                int effectiveLowStockThreshold,
                boolean isOverridden,
                String moderationStatus,
                Map<String, FieldProvenance> provenance) {
            this.productId = productId;
            this.tenantId = tenantId;
            this.effectiveBrandName = effectiveBrandName;
            this.effectiveMrpUnit = effectiveMrpUnit;
            this.effectiveCostUnit = effectiveCostUnit;
            this.effectiveRequiresPrescription = effectiveRequiresPrescription;
            this.effectiveIsVisible = effectiveIsVisible;
            this.effectiveShelfLocation = effectiveShelfLocation;
            this.effectiveLowStockThreshold = effectiveLowStockThreshold;
            this.isOverridden = isOverridden;
            this.moderationStatus = moderationStatus;
            this.provenance = provenance;
        }
    }

    public static final class ClientResult<T> {
        public final UiStatus status;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;
        public final T data;

        public ClientResult(UiStatus status, String errorCode, String messageEn, String messageBn, T data) {
            this.status = status;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
            this.data = data;
        }

        public static <T> ClientResult<T> success(String messageEn, String messageBn, T data) {
            return new ClientResult<>(UiStatus.SUCCESS, null, messageEn, messageBn, data);
        }

        public static <T> ClientResult<T> error(String code, String msgEn, String msgBn) {
            return new ClientResult<>(UiStatus.ERROR, code, msgEn, msgBn, null);
        }

        public static <T> ClientResult<T> denied(String code, String msgEn, String msgBn) {
            return new ClientResult<>(UiStatus.PERMISSION_DENIED, code, msgEn, msgBn, null);
        }

        public static <T> ClientResult<T> offline(String msgEn, String msgBn, T data) {
            return new ClientResult<>(UiStatus.OFFLINE, "OFFLINE_CACHE", msgEn, msgBn, data);
        }
    }

    private final Map<String, MasterMedicine> globalMasters = new HashMap<>();
    private final Map<String, TenantOverride> tenantOverrides = new HashMap<>();
    private final Map<String, ResolvedMedicinePresentation> localCache = new HashMap<>();

    public void registerMaster(MasterMedicine master) {
        globalMasters.put(master.productId, master);
    }

    public void registerOverride(TenantOverride override) {
        String key = override.tenantId + ":" + override.productId;
        tenantOverrides.put(key, override);
    }

    /**
     * Resolves effective presentation under explicit precedence and moderation rules.
     */
    public ClientResult<ResolvedMedicinePresentation> resolvePrecedence(
            String callerTenantId,
            String targetTenantId,
            String productId,
            boolean isOnline) {
        if (!callerTenantId.equals(targetTenantId)) {
            return ClientResult.denied(
                    "ERR_CROSS_TENANT_ACCESS",
                    "Cross-tenant access violation: Cannot resolve medicine for foreign tenant.",
                    "ভিন্ন ফার্মেসির ওষুধ ওভাররাইড অ্যাক্সেস করা সম্পূর্ণ নিষিদ্ধ।");
        }

        String cacheKey = targetTenantId + ":" + productId;
        MasterMedicine master = globalMasters.get(productId);

        if (!isOnline) {
            ResolvedMedicinePresentation cached = localCache.get(cacheKey);
            if (cached != null) {
                return ClientResult.offline(
                        "Resolved from offline cache.",
                        "অফলাইন ক্যাশ থেকে ডেটা লোড করা হয়েছে।",
                        cached);
            }
            return ClientResult.error("ERR_OFFLINE_NO_CACHE", "No offline cache available.", "কোনো অফলাইন ক্যাশ পাওয়া যায়নি।");
        }

        if (master == null) {
            return ClientResult.error(
                    "ERR_MASTER_NOT_FOUND",
                    "Global master medicine product does not exist.",
                    "মাস্টার ওষুধ পাওয়া যায়নি।");
        }

        TenantOverride override = tenantOverrides.get(cacheKey);
        Map<String, FieldProvenance> prov = new HashMap<>();

        // 1. Brand Name
        String effectiveBrand;
        if (override != null
                && override.localBrandName != null
                && !override.localBrandName.trim().isEmpty()
                && "APPROVED".equals(override.moderationStatus)) {
            effectiveBrand = override.localBrandName.trim();
            prov.put("brand_name", new FieldProvenance(effectiveBrand, "TENANT_OVERRIDE", "ACTIVE_TENANT_OVERRIDE"));
        } else {
            effectiveBrand = master.brandName;
            prov.put("brand_name", new FieldProvenance(effectiveBrand, "GLOBAL_MASTER", "MASTER_DEFAULT"));
        }

        // 2. MRP Precedence & Moderation
        BigDecimal effectiveMrp;
        if (override != null && override.customMrpUnit != null) {
            if ("APPROVED".equals(override.moderationStatus)) {
                effectiveMrp = override.customMrpUnit;
                prov.put("mrp_unit", new FieldProvenance(effectiveMrp, "TENANT_OVERRIDE", "ACTIVE_TENANT_OVERRIDE"));
            } else if ("PENDING_REVIEW".equals(override.moderationStatus)) {
                effectiveMrp = master.mrpUnit;
                prov.put("mrp_unit", new FieldProvenance(effectiveMrp, "GLOBAL_MASTER", "MODERATION_PENDING_FALLBACK"));
            } else {
                effectiveMrp = master.mrpUnit;
                prov.put("mrp_unit", new FieldProvenance(effectiveMrp, "GLOBAL_MASTER", "MODERATION_REJECTED_FALLBACK"));
            }
        } else {
            effectiveMrp = master.mrpUnit;
            prov.put("mrp_unit", new FieldProvenance(effectiveMrp, "GLOBAL_MASTER", "MASTER_DEFAULT"));
        }

        // 3. Cost Precedence
        BigDecimal effectiveCost;
        if (override != null && override.customCostUnit != null && "APPROVED".equals(override.moderationStatus)) {
            effectiveCost = override.customCostUnit;
            prov.put("cost_unit", new FieldProvenance(effectiveCost, "TENANT_OVERRIDE", "ACTIVE_TENANT_OVERRIDE"));
        } else {
            effectiveCost = master.costUnit;
            prov.put("cost_unit", new FieldProvenance(effectiveCost, "GLOBAL_MASTER", "MASTER_DEFAULT"));
        }

        // 4. Regulatory Safety Non-Relaxation
        boolean effectiveRx;
        if (master.isPrescriptionOnly) {
            effectiveRx = true;
            prov.put("is_prescription_required", new FieldProvenance(true, "REGULATORY_MANDATE", "DGDA_POM_MANDATE"));
        } else if (master.isNarcotic) {
            effectiveRx = true;
            prov.put("is_prescription_required", new FieldProvenance(true, "REGULATORY_MANDATE", "DGDA_NARCOTIC_MANDATE"));
        } else if (master.isAntibiotic) {
            effectiveRx = true;
            prov.put("is_prescription_required", new FieldProvenance(true, "REGULATORY_MANDATE", "DGDA_ANTIBIOTIC_MANDATE"));
        } else if (override != null
                && Boolean.TRUE.equals(override.requiresPrescriptionOverride)
                && "APPROVED".equals(override.moderationStatus)) {
            effectiveRx = true;
            prov.put("is_prescription_required", new FieldProvenance(true, "TENANT_OVERRIDE", "ACTIVE_TENANT_OVERRIDE"));
        } else {
            effectiveRx = false;
            prov.put("is_prescription_required", new FieldProvenance(false, "GLOBAL_MASTER", "MASTER_DEFAULT"));
        }

        // 5. Visibility
        boolean effectiveVis = override == null || override.isVisible;
        prov.put("is_visible", new FieldProvenance(effectiveVis, override != null && !override.isVisible ? "TENANT_OVERRIDE" : "GLOBAL_MASTER", "MASTER_DEFAULT"));

        // 6. Shelf location
        String shelf = override != null && override.shelfLocation != null ? override.shelfLocation : "";
        prov.put("shelf_location", new FieldProvenance(shelf, override != null && !shelf.isEmpty() ? "TENANT_OVERRIDE" : "GLOBAL_MASTER", "MASTER_DEFAULT"));

        int threshold = override != null ? override.lowStockThreshold : 10;
        prov.put("low_stock_threshold", new FieldProvenance(threshold, override != null ? "TENANT_OVERRIDE" : "GLOBAL_MASTER", "MASTER_DEFAULT"));

        ResolvedMedicinePresentation result = new ResolvedMedicinePresentation(
                master.productId,
                targetTenantId,
                effectiveBrand,
                effectiveMrp,
                effectiveCost,
                effectiveRx,
                effectiveVis,
                shelf,
                threshold,
                override != null,
                override != null ? override.moderationStatus : "NOT_APPLICABLE",
                prov);

        localCache.put(cacheKey, result);
        return ClientResult.success("Precedence resolved.", "প্রিসিডেন্স সফলভাবে নির্ণয় করা হয়েছে।", result);
    }

    /**
     * Proposes a new or updated tenant override with anti-gouging moderation & role guard.
     */
    public ClientResult<ResolvedMedicinePresentation> proposeOverride(
            String callerTenantId,
            String targetTenantId,
            String userRole,
            String productId,
            String localBrandName,
            BigDecimal customMrp,
            Boolean requiresPrescriptionOverride) {
        // RBAC Check
        if ("CASHIER".equalsIgnoreCase(userRole)) {
            return ClientResult.denied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot mutate tenant medicine overrides.",
                    "ক্যাশিয়াররা ওষুধ ওভাররাইড পরিবর্তন করতে পারবেন না।");
        }

        if (!callerTenantId.equals(targetTenantId)) {
            return ClientResult.denied(
                    "ERR_CROSS_TENANT_ACCESS",
                    "Cross-tenant access violation.",
                    "ভিন্ন ফার্মেসির ওষুধ ওভাররাইড অ্যাক্সেস করা সম্পূর্ণ নিষিদ্ধ।");
        }

        MasterMedicine master = globalMasters.get(productId);
        if (master == null) {
            return ClientResult.error("ERR_MASTER_NOT_FOUND", "Master product not found.", "মাস্টার ওষুধ পাওয়া যায়নি।");
        }

        // Regulatory Invariant Check
        if (Boolean.FALSE.equals(requiresPrescriptionOverride) && (master.isPrescriptionOnly || master.isNarcotic || master.isAntibiotic)) {
            return ClientResult.error(
                    "ERR_REGULATORY_RELAXATION_FORBIDDEN",
                    "Cannot relax DGDA prescription mandate on a regulated drug.",
                    "প্রেসক্রিপশন ওষুধের বিধিনিষেধ শিথিল করা আইনত নিষিদ্ধ।");
        }

        // Anti-Gouging Check (+50% ceiling)
        String moderationStatus = "APPROVED";
        String moderationReason = "Auto-approved within standard threshold.";
        if (customMrp != null) {
            BigDecimal maxAllowed = master.mrpUnit.multiply(new BigDecimal("1.50"));
            if (customMrp.compareTo(maxAllowed) > 0) {
                moderationStatus = "PENDING_REVIEW";
                moderationReason = "Price exceeds +50% threshold. Awaiting compliance moderation.";
            }
        }

        String key = targetTenantId + ":" + productId;
        TenantOverride override = new TenantOverride(
                "tmc-" + UUID.randomUUID().toString().substring(0, 8),
                targetTenantId,
                productId,
                localBrandName,
                "",
                customMrp,
                null,
                true,
                "",
                10,
                requiresPrescriptionOverride,
                moderationStatus,
                moderationReason);
        tenantOverrides.put(key, override);

        return resolvePrecedence(callerTenantId, targetTenantId, productId, true);
    }
}
