package com.pharmacy.pos.catalog;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PosMedicineVariantClient
 * Architecture Decision: ADR-0126 (P126).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * Client-side integration for JavaFX Desktop POS managing distinct commercial medicine presentations (SKUs),
 * pack size quantities, containers, barcode lookup, and regulatory classification inheritance.
 */
public class PosMedicineVariantClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class MedicineVariantRecord {
        public final String variantId;
        public final String productId;
        public final String sku;
        public final String variantName;
        public final String brandName;
        public final String genericName;
        public final String dosageForm;
        public final String manufacturer;
        public final String strength;
        public final String packSize;
        public final int packSizeNumeric;
        public final String route;
        public final String containerType;
        public final String barcode;
        public final BigDecimal mrpUnit;
        public final BigDecimal costUnit;
        public final BigDecimal mrpPack;
        public final BigDecimal costPack;
        public final boolean isPrescriptionRequired;
        public final boolean isNarcotic;
        public final boolean isAntibiotic;
        public final boolean isColdChain;
        public final boolean isActive;

        public MedicineVariantRecord(
                String variantId,
                String productId,
                String sku,
                String variantName,
                String brandName,
                String genericName,
                String dosageForm,
                String manufacturer,
                String strength,
                String packSize,
                int packSizeNumeric,
                String route,
                String containerType,
                String barcode,
                BigDecimal mrpUnit,
                BigDecimal costUnit,
                BigDecimal mrpPack,
                BigDecimal costPack,
                boolean isPrescriptionRequired,
                boolean isNarcotic,
                boolean isAntibiotic,
                boolean isColdChain,
                boolean isActive) {
            this.variantId = variantId;
            this.productId = productId;
            this.sku = sku;
            this.variantName = variantName;
            this.brandName = brandName;
            this.genericName = genericName;
            this.dosageForm = dosageForm;
            this.manufacturer = manufacturer;
            this.strength = strength;
            this.packSize = packSize;
            this.packSizeNumeric = packSizeNumeric;
            this.route = route;
            this.containerType = containerType;
            this.barcode = barcode;
            this.mrpUnit = mrpUnit;
            this.costUnit = costUnit;
            this.mrpPack = mrpPack;
            this.costPack = costPack;
            this.isPrescriptionRequired = isPrescriptionRequired;
            this.isNarcotic = isNarcotic;
            this.isAntibiotic = isAntibiotic;
            this.isColdChain = isColdChain;
            this.isActive = isActive;
        }
    }

    public static final class VariantResult {
        public final UiStatus uiStatus;
        public final List<MedicineVariantRecord> variants;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public VariantResult(UiStatus uiStatus, List<MedicineVariantRecord> variants, String errorCode, String messageEn, String messageBn) {
            this.uiStatus = uiStatus;
            this.variants = variants != null ? variants : Collections.emptyList();
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static VariantResult success(List<MedicineVariantRecord> list) {
            if (list == null || list.isEmpty()) {
                return new VariantResult(
                        UiStatus.EMPTY,
                        Collections.emptyList(),
                        null,
                        "No medicine variants found.",
                        "কোনো মেডিসিন ভ্যারিয়েন্ট পাওয়া যায়নি।"
                );
            }
            return new VariantResult(
                    UiStatus.SUCCESS,
                    list,
                    null,
                    "Loaded " + list.size() + " variant(s).",
                    list.size() + " টি ভ্যারিয়েন্ট লোড হয়েছে।"
            );
        }

        public static VariantResult error(UiStatus status, String code, String msgEn, String msgBn) {
            return new VariantResult(status, Collections.emptyList(), code, msgEn, msgBn);
        }
    }

    private final Map<String, MedicineVariantRecord> variantCache = new HashMap<>();

    public void registerVariant(MedicineVariantRecord record) {
        variantCache.put(record.variantId, record);
    }

    public VariantResult lookupByBarcode(String barcode) {
        if (barcode == null || barcode.trim().isEmpty()) {
            return VariantResult.error(UiStatus.ERROR, "ERR_INVALID_BARCODE", "Barcode cannot be empty.", "বারকোড খালি হতে পারে না।");
        }
        String clean = barcode.trim();
        List<MedicineVariantRecord> matches = variantCache.values().stream()
                .filter(v -> clean.equalsIgnoreCase(v.barcode))
                .collect(Collectors.toList());

        return VariantResult.success(matches);
    }

    public VariantResult lookupBySku(String sku) {
        if (sku == null || sku.trim().isEmpty()) {
            return VariantResult.error(UiStatus.ERROR, "ERR_INVALID_SKU", "SKU cannot be empty.", "SKU খালি হতে পারে না।");
        }
        String clean = sku.trim();
        List<MedicineVariantRecord> matches = variantCache.values().stream()
                .filter(v -> clean.equalsIgnoreCase(v.sku))
                .collect(Collectors.toList());

        return VariantResult.success(matches);
    }

    public VariantResult searchVariants(String query) {
        if (query == null || query.trim().isEmpty()) {
            return VariantResult.success(new ArrayList<>(variantCache.values()));
        }
        String q = query.trim().toLowerCase();
        List<MedicineVariantRecord> matches = variantCache.values().stream()
                .filter(v -> v.variantName.toLowerCase().contains(q)
                        || v.sku.toLowerCase().contains(q)
                        || v.barcode.toLowerCase().contains(q)
                        || v.brandName.toLowerCase().contains(q)
                        || v.genericName.toLowerCase().contains(q))
                .collect(Collectors.toList());

        return VariantResult.success(matches);
    }

    public VariantResult attemptCreateVariant(String callerRole, MedicineVariantRecord record) {
        String role = callerRole != null ? callerRole.toUpperCase() : "";
        if (!"SUPERADMIN".equals(role) && !"PLATFORM_OPERATOR".equals(role)) {
            return VariantResult.error(
                    UiStatus.PERMISSION_DENIED,
                    "PLATFORM_CATALOG_OWNERSHIP_PROTECTED",
                    "Medicine variants and master catalog entries are centrally curated by platform administrators.",
                    "ঔষধ ভ্যারিয়েন্ট ও ক্যাটালগ এন্ট্রি কেন্দ্রীয়ভাবে প্ল্যাটফর্ম অ্যাডমিনিস্ট্রেটর দ্বারা পরিচালিত।"
            );
        }
        registerVariant(record);
        return VariantResult.success(Collections.singletonList(record));
    }
}
