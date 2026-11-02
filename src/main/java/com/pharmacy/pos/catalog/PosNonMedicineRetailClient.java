package com.pharmacy.pos.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosNonMedicineRetailClient
 * Architecture Decision: ADR-0135 (P135).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * JavaFX Desktop POS client for Authorized Non-Medicine Retail Goods:
 * Separation of Dispensing Controls, High-Speed Barcode Checkout, Multi-Tenancy, and Offline Caching.
 */
public class PosNonMedicineRetailClient {

    public enum UiStatus {
        SUCCESS,
        ERROR,
        PERMISSION_DENIED,
        EMPTY,
        OFFLINE
    }

    public enum NonMedicineCategory {
        SURGICAL_WOUND_CARE,
        DIAGNOSTIC_DEVICES,
        PERSONAL_HYGIENE,
        BABY_MOTHER_CARE,
        NUTRITION_SUPPLEMENT,
        HEALTH_ACCESSORIES
    }

    public static final class NonMedicineItemRecord {
        public final String itemId;
        public final String tenantId;
        public String name;
        public String nameBn;
        public NonMedicineCategory category;
        public String brandName;
        public String manufacturerOrDistributor;
        public String barcode;
        public String sku;
        public double mrp;
        public double costPrice;
        public double vatPercent;
        public String unitOfMeasure;
        public boolean requiresColdChain;
        public String storageInstructions;
        public final boolean isMedicine = false;
        public final boolean isPrescriptionOnly = false;
        public final boolean isAntibiotic = false;
        public final boolean isNarcotic = false;
        public boolean isActive;
        public int versionNumber;
        public String immutableHash;
        public final Instant createdAt;

        public NonMedicineItemRecord(
                String itemId,
                String tenantId,
                String name,
                String nameBn,
                NonMedicineCategory category,
                String brandName,
                String manufacturerOrDistributor,
                String barcode,
                String sku,
                double mrp,
                double costPrice,
                double vatPercent,
                String unitOfMeasure,
                boolean requiresColdChain,
                String storageInstructions,
                boolean isActive,
                int versionNumber,
                String immutableHash,
                Instant createdAt) {
            this.itemId = itemId;
            this.tenantId = tenantId != null ? tenantId : "platform";
            this.name = name;
            this.nameBn = nameBn != null ? nameBn : "";
            this.category = category;
            this.brandName = brandName != null ? brandName : "";
            this.manufacturerOrDistributor = manufacturerOrDistributor != null ? manufacturerOrDistributor : "";
            this.barcode = barcode != null ? barcode : "";
            this.sku = sku != null ? sku : "";
            this.mrp = mrp;
            this.costPrice = costPrice;
            this.vatPercent = vatPercent;
            this.unitOfMeasure = unitOfMeasure != null ? unitOfMeasure : "PIECE";
            this.requiresColdChain = requiresColdChain;
            this.storageInstructions = storageInstructions != null ? storageInstructions : "";
            this.isActive = isActive;
            this.versionNumber = versionNumber;
            this.immutableHash = immutableHash;
            this.createdAt = createdAt != null ? createdAt : Instant.now();
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

    private final Map<String, NonMedicineItemRecord> itemStore = new ConcurrentHashMap<>();
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

    public ClientResult<NonMedicineItemRecord> createItem(
            String tenantId,
            String name,
            String nameBn,
            NonMedicineCategory category,
            String brandName,
            String manufacturerOrDistributor,
            String barcode,
            String sku,
            double mrp,
            double costPrice,
            double vatPercent,
            String unitOfMeasure,
            boolean requiresColdChain,
            String storageInstructions,
            boolean attemptPrescriptionFlag,
            boolean attemptAntibioticFlag,
            String userRole) {

        // Regulatory Boundary Guard
        if (attemptPrescriptionFlag || attemptAntibioticFlag) {
            return ClientResult.error(
                    "ERR_NON_MEDICINE_REGULATORY_RESTRICTION",
                    "Non-medicine retail items cannot be marked as prescription-only or antibiotic. Regulatory controls are strictly medicine-specific.",
                    "নন-মেডিসিন রিটেইল পণ্যে প্রেসক্রিপশন বা অ্যান্টিবায়োটিক ফ্ল্যাগ দেওয়া সম্পূর্ণ নিষিদ্ধ।"
            );
        }

        if (!isAuthorizedManager(userRole)) {
            return ClientResult.permissionDenied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot create or mutate non-medicine retail items.",
                    "ক্যাশিয়াররা নতুন রিটেইল পণ্য তৈরি বা পরিবর্তন করতে পারবেন না।"
            );
        }

        if (name == null || name.trim().length() < 2) {
            return ClientResult.error("ERR_INVALID_NAME", "Item name must be at least 2 characters.", "পণ্যের নাম ন্যূনতম ২ অক্ষর হতে হবে।");
        }

        if (mrp <= 0.0) {
            return ClientResult.error("ERR_INVALID_MRP", "MRP must be greater than 0.00.", "এমআরপি অবশ্যই ০.০০ এর বেশি হতে হবে।");
        }

        String effTenant = tenantId != null ? tenantId.trim() : "platform";
        String cleanBarcode = barcode != null ? barcode.trim() : "";

        // Check duplicate barcode within tenant
        if (!cleanBarcode.isEmpty()) {
            for (NonMedicineItemRecord r : itemStore.values()) {
                if (r.tenantId.equals(effTenant) && r.barcode.equalsIgnoreCase(cleanBarcode)) {
                    return ClientResult.error("ERR_DUPLICATE_BARCODE", "Barcode already exists for this tenant.", "এই বারকোডের পণ্য ইতিমধ্যে বিদ্যমান।");
                }
            }
        }

        String itemId = "nmi-pos-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String hash = computeItemHash(itemId, effTenant, category.name(), cleanBarcode, mrp, vatPercent, 1);

        NonMedicineItemRecord record = new NonMedicineItemRecord(
                itemId,
                effTenant,
                name.trim(),
                nameBn,
                category,
                brandName,
                manufacturerOrDistributor,
                cleanBarcode,
                sku,
                mrp,
                costPrice,
                vatPercent,
                unitOfMeasure,
                requiresColdChain,
                storageInstructions,
                true,
                1,
                hash,
                Instant.now()
        );

        itemStore.put(itemId, record);

        if (isOffline) {
            return ClientResult.offline(record);
        }
        return ClientResult.success(record);
    }

    public ClientResult<NonMedicineItemRecord> updateItem(
            String itemId,
            String actorTenantId,
            String userRole,
            String newName,
            Double newMrp,
            Double newCostPrice,
            boolean attemptPrescriptionFlag) {

        if (attemptPrescriptionFlag) {
            return ClientResult.error(
                    "ERR_NON_MEDICINE_REGULATORY_RESTRICTION",
                    "Non-medicine retail items cannot be marked as prescription-only.",
                    "নন-মেডিসিন রিটেইল পণ্যে প্রেসক্রিপশন ফ্ল্যাগ দেওয়া সম্পূর্ণ নিষিদ্ধ।"
            );
        }

        if (!isAuthorizedManager(userRole)) {
            return ClientResult.permissionDenied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot update retail items.",
                    "ক্যাশিয়াররা রিটেইল পণ্য আপডেট করতে পারবেন না।"
            );
        }

        NonMedicineItemRecord r = itemStore.get(itemId);
        if (r == null) {
            return ClientResult.error("ERR_ITEM_NOT_FOUND", "Retail item not found.", "পণ্যটি পাওয়া যায়নি।");
        }

        boolean isSuperadmin = userRole.trim().equalsIgnoreCase("SUPERADMIN");
        if (!isSuperadmin && !r.tenantId.equals("platform") && !r.tenantId.equals(actorTenantId)) {
            return ClientResult.permissionDenied(
                    "ERR_CROSS_TENANT_ACCESS",
                    "Cannot mutate retail item belonging to another tenant.",
                    "অন্য ফার্মেসির রিটেইল পণ্য পরিবর্তন করা সম্পূর্ণ নিষিদ্ধ।"
            );
        }

        if (newName != null && newName.trim().length() >= 2) {
            r.name = newName.trim();
        }
        if (newMrp != null && newMrp > 0.0) {
            r.mrp = newMrp;
        }
        if (newCostPrice != null && newCostPrice >= 0.0) {
            r.costPrice = newCostPrice;
        }

        r.versionNumber += 1;
        r.immutableHash = computeItemHash(r.itemId, r.tenantId, r.category.name(), r.barcode, r.mrp, r.vatPercent, r.versionNumber);

        if (isOffline) {
            return ClientResult.offline(r);
        }
        return ClientResult.success(r);
    }

    public ClientResult<List<NonMedicineItemRecord>> searchItems(String tenantId, String query, NonMedicineCategory category) {
        List<NonMedicineItemRecord> list = new ArrayList<>();
        String q = query != null ? query.trim().toLowerCase() : "";

        for (NonMedicineItemRecord r : itemStore.values()) {
            if (tenantId != null && !r.tenantId.equals("platform") && !r.tenantId.equals(tenantId)) {
                continue;
            }
            if (category != null && r.category != category) {
                continue;
            }
            if (!q.isEmpty()) {
                boolean match = r.name.toLowerCase().contains(q)
                        || r.barcode.toLowerCase().contains(q)
                        || r.brandName.toLowerCase().contains(q);
                if (!match) continue;
            }
            list.add(r);
        }

        if (list.isEmpty()) {
            return ClientResult.empty("No retail items found.", "কোন রিটেইল পণ্য পাওয়া যায়নি।");
        }

        if (isOffline) {
            return ClientResult.offline(Collections.unmodifiableList(list));
        }
        return ClientResult.success(Collections.unmodifiableList(list));
    }

    public ClientResult<NonMedicineItemRecord> archiveItem(String itemId, String actorTenantId, String userRole) {
        if (!isAuthorizedManager(userRole)) {
            return ClientResult.permissionDenied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot archive retail items.",
                    "ক্যাশিয়াররা রিটেইল পণ্য আর্কাইভ করতে পারবেন না।"
            );
        }

        NonMedicineItemRecord r = itemStore.get(itemId);
        if (r == null) {
            return ClientResult.error("ERR_ITEM_NOT_FOUND", "Retail item not found.", "পণ্যটি পাওয়া যায়নি।");
        }

        boolean isSuperadmin = userRole.trim().equalsIgnoreCase("SUPERADMIN");
        if (!isSuperadmin && !r.tenantId.equals("platform") && !r.tenantId.equals(actorTenantId)) {
            return ClientResult.permissionDenied(
                    "ERR_CROSS_TENANT_ACCESS",
                    "Cannot archive retail item belonging to another tenant.",
                    "অন্য ফার্মেসির রিটেইল পণ্য আর্কাইভ করা নিষিদ্ধ।"
            );
        }

        r.isActive = false;

        if (isOffline) {
            return ClientResult.offline(r);
        }
        return ClientResult.success(r);
    }

    private String computeItemHash(String itemId, String tenantId, String category, String barcode, double mrp, double vat, int version) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = String.format("%s|%s|%s|%s|%.2f|%.2f|%d", itemId, tenantId, category, barcode, mrp, vat, version);
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
