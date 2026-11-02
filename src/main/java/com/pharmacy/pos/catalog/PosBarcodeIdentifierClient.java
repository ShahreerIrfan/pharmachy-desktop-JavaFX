package com.pharmacy.pos.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosBarcodeIdentifierClient
 * Architecture Decision: ADR-0136 (P136).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * JavaFX Desktop POS Client for Primary, Secondary, and Alternate Barcode Identifiers:
 * Packaging Hierarchy GTINs, Contextual Uniqueness, and POS Scan Prioritization Resolution.
 */
public class PosBarcodeIdentifierClient {

    public enum UiStatus {
        SUCCESS,
        ERROR,
        PERMISSION_DENIED,
        EMPTY,
        OFFLINE
    }

    public enum IdentifierType {
        PRIMARY_EAN13_GTIN,
        SECONDARY_BOX_GTIN14,
        INTERNAL_STORE_BARCODE,
        GS1_DATAMATRIX_2D,
        NATIONAL_DAR_CODE,
        SUPPLIER_CATALOG_CODE
    }

    public enum PackagingLevel {
        UNIT,
        STRIP,
        BOX,
        SHIPPER_CARTON
    }

    public enum TargetEntityType {
        MEDICINE,
        NON_MEDICINE,
        VARIANT
    }

    public static final class BarcodeRecord {
        public final String identifierId;
        public final String tenantId;
        public final String targetId;
        public final TargetEntityType targetType;
        public final String targetName;
        public final double resolvedMrp;
        public final String barcodeValue;
        public final IdentifierType identifierType;
        public final PackagingLevel packagingLevel;
        public int priorityOrder;
        public final int packQuantityMultiplier;
        public final String description;
        public boolean isActive;
        public int versionNumber;
        public String immutableHash;
        public final Instant createdAt;

        public BarcodeRecord(
                String identifierId,
                String tenantId,
                String targetId,
                TargetEntityType targetType,
                String targetName,
                double resolvedMrp,
                String barcodeValue,
                IdentifierType identifierType,
                PackagingLevel packagingLevel,
                int priorityOrder,
                int packQuantityMultiplier,
                String description,
                boolean isActive,
                int versionNumber,
                String immutableHash,
                Instant createdAt) {
            this.identifierId = identifierId;
            this.tenantId = tenantId != null ? tenantId : "platform";
            this.targetId = targetId;
            this.targetType = targetType;
            this.targetName = targetName != null ? targetName : "";
            this.resolvedMrp = resolvedMrp;
            this.barcodeValue = barcodeValue != null ? barcodeValue : "";
            this.identifierType = identifierType;
            this.packagingLevel = packagingLevel;
            this.priorityOrder = priorityOrder;
            this.packQuantityMultiplier = packQuantityMultiplier;
            this.description = description != null ? description : "";
            this.isActive = isActive;
            this.versionNumber = versionNumber;
            this.immutableHash = immutableHash;
            this.createdAt = createdAt != null ? createdAt : Instant.now();
        }
    }

    public static final class ScanResolution {
        public final boolean matched;
        public final String barcodeValue;
        public final BarcodeRecord identifierRecord;
        public final double resolvedMrp;
        public final PackagingLevel resolvedPackagingLevel;
        public final int packQuantityMultiplier;

        public ScanResolution(
                boolean matched,
                String barcodeValue,
                BarcodeRecord identifierRecord,
                double resolvedMrp,
                PackagingLevel resolvedPackagingLevel,
                int packQuantityMultiplier) {
            this.matched = matched;
            this.barcodeValue = barcodeValue != null ? barcodeValue : "";
            this.identifierRecord = identifierRecord;
            this.resolvedMrp = resolvedMrp;
            this.resolvedPackagingLevel = resolvedPackagingLevel;
            this.packQuantityMultiplier = packQuantityMultiplier;
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

    private final Map<String, BarcodeRecord> store = new ConcurrentHashMap<>();
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

    public ClientResult<BarcodeRecord> registerBarcode(
            String tenantId,
            String targetId,
            TargetEntityType targetType,
            String targetName,
            double resolvedMrp,
            String barcodeValue,
            IdentifierType identifierType,
            PackagingLevel packagingLevel,
            Integer priorityOrder,
            Integer packQuantityMultiplier,
            String description,
            String userRole) {

        if (!isAuthorizedManager(userRole)) {
            return ClientResult.permissionDenied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot register or mutate barcode identifiers.",
                    "ক্যাশিয়াররা নতুন বারকোড নিবন্ধন বা পরিবর্তন করতে পারবেন না।"
            );
        }

        String cleanBarcode = barcodeValue != null ? barcodeValue.trim() : "";
        if (cleanBarcode.length() < 3) {
            return ClientResult.error("ERR_INVALID_BARCODE", "Barcode must be at least 3 characters.", "বারকোড ন্যূনতম ৩ অক্ষর হতে হবে।");
        }

        String effTenant = tenantId != null ? tenantId.trim() : "platform";

        // Enforce contextual uniqueness across active barcodes
        for (BarcodeRecord r : store.values()) {
            if (r.isActive && (r.tenantId.equals("platform") || r.tenantId.equals(effTenant))) {
                if (r.barcodeValue.equalsIgnoreCase(cleanBarcode)) {
                    if (!r.targetId.equals(targetId)) {
                        return ClientResult.error(
                                "ERR_BARCODE_ALREADY_ASSIGNED",
                                "Barcode '" + cleanBarcode + "' is already active and assigned to another item in tenant context.",
                                "বারকোডটি ইতিমধ্যে এই ফার্মেসির অন্য পণ্যের সাথে যুক্ত।"
                        );
                    }
                }
            }
        }

        // Default pack multiplier
        int multiplier;
        if (packQuantityMultiplier != null && packQuantityMultiplier >= 1) {
            multiplier = packQuantityMultiplier;
        } else {
            if (packagingLevel == PackagingLevel.UNIT) multiplier = 1;
            else if (packagingLevel == PackagingLevel.STRIP) multiplier = 10;
            else if (packagingLevel == PackagingLevel.BOX) multiplier = 100;
            else if (packagingLevel == PackagingLevel.SHIPPER_CARTON) multiplier = 1000;
            else multiplier = 1;
        }

        // Default priority order
        int priority;
        if (priorityOrder != null && priorityOrder >= 1) {
            priority = priorityOrder;
        } else {
            if (identifierType == IdentifierType.PRIMARY_EAN13_GTIN || identifierType == IdentifierType.INTERNAL_STORE_BARCODE) {
                priority = 1;
            } else if (packagingLevel == PackagingLevel.STRIP) {
                priority = 2;
            } else if (packagingLevel == PackagingLevel.BOX) {
                priority = 3;
            } else {
                priority = 4;
            }
        }

        String identId = "bar-pos-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String hash = computeHash(identId, effTenant, cleanBarcode, identifierType.name(), packagingLevel.name(), priority, 1);

        BarcodeRecord record = new BarcodeRecord(
                identId,
                effTenant,
                targetId,
                targetType,
                targetName,
                resolvedMrp,
                cleanBarcode,
                identifierType,
                packagingLevel,
                priority,
                multiplier,
                description,
                true,
                1,
                hash,
                Instant.now()
        );

        store.put(identId, record);

        if (isOffline) {
            return ClientResult.offline(record);
        }
        return ClientResult.success(record);
    }

    public ClientResult<ScanResolution> resolveBarcode(String tenantId, String scanCode) {
        String cleanCode = scanCode != null ? scanCode.trim() : "";
        if (cleanCode.isEmpty()) {
            return ClientResult.empty("No barcode scanned.", "কোন বারকোড স্ক্যান করা হয়নি।");
        }

        List<BarcodeRecord> matches = new ArrayList<>();
        for (BarcodeRecord r : store.values()) {
            if (!r.isActive) continue;
            if (tenantId != null && !r.tenantId.equals("platform") && !r.tenantId.equals(tenantId)) {
                continue;
            }
            if (r.barcodeValue.equalsIgnoreCase(cleanCode)) {
                matches.add(r);
            }
        }

        if (matches.isEmpty()) {
            ScanResolution notFound = new ScanResolution(false, cleanCode, null, 0.0, null, 0);
            return ClientResult.empty("Barcode not recognized in catalog.", "বারকোডটি ক্যাটালগে পাওয়া যায়নি।");
        }

        // Scan Prioritization Engine: sort by priorityOrder ASC (1 is highest), then versionNumber DESC
        matches.sort((a, b) -> {
            int cmp = Integer.compare(a.priorityOrder, b.priorityOrder);
            if (cmp != 0) return cmp;
            return Integer.compare(b.versionNumber, a.versionNumber);
        });

        BarcodeRecord best = matches.get(0);
        ScanResolution res = new ScanResolution(
                true,
                cleanCode,
                best,
                best.resolvedMrp,
                best.packagingLevel,
                best.packQuantityMultiplier
        );

        if (isOffline) {
            return ClientResult.offline(res);
        }
        return ClientResult.success(res);
    }

    public ClientResult<BarcodeRecord> updatePriority(String identifierId, String actorTenantId, String userRole, int newPriority) {
        if (!isAuthorizedManager(userRole)) {
            return ClientResult.permissionDenied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot update barcode priority.",
                    "ক্যাশিয়াররা বারকোড অগ্রাধিকার পরিবর্তন করতে পারবেন না।"
            );
        }

        BarcodeRecord r = store.get(identifierId);
        if (r == null) {
            return ClientResult.error("ERR_IDENTIFIER_NOT_FOUND", "Barcode identifier not found.", "বারকোড পাওয়া যায়নি।");
        }

        boolean isSuperadmin = userRole.trim().equalsIgnoreCase("SUPERADMIN");
        if (!isSuperadmin && !r.tenantId.equals("platform") && !r.tenantId.equals(actorTenantId)) {
            return ClientResult.permissionDenied(
                    "ERR_CROSS_TENANT_ACCESS",
                    "Cannot mutate barcode identifier belonging to another tenant.",
                    "অন্য ফার্মেসির বারকোড পরিবর্তন করা নিষিদ্ধ।"
            );
        }

        r.priorityOrder = Math.max(1, newPriority);
        r.versionNumber += 1;
        r.immutableHash = computeHash(r.identifierId, r.tenantId, r.barcodeValue, r.identifierType.name(), r.packagingLevel.name(), r.priorityOrder, r.versionNumber);

        if (isOffline) {
            return ClientResult.offline(r);
        }
        return ClientResult.success(r);
    }

    public ClientResult<BarcodeRecord> archiveBarcode(String identifierId, String actorTenantId, String userRole, String reason) {
        if (!isAuthorizedManager(userRole)) {
            return ClientResult.permissionDenied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot archive barcode identifiers.",
                    "ক্যাশিয়াররা বারকোড আর্কাইভ করতে পারবেন না।"
            );
        }

        BarcodeRecord r = store.get(identifierId);
        if (r == null) {
            return ClientResult.error("ERR_IDENTIFIER_NOT_FOUND", "Barcode identifier not found.", "বারকোড পাওয়া যায়নি।");
        }

        boolean isSuperadmin = userRole.trim().equalsIgnoreCase("SUPERADMIN");
        if (!isSuperadmin && !r.tenantId.equals("platform") && !r.tenantId.equals(actorTenantId)) {
            return ClientResult.permissionDenied(
                    "ERR_CROSS_TENANT_ACCESS",
                    "Cannot archive barcode belonging to another tenant.",
                    "অন্য ফার্মেসির বারকোড আর্কাইভ করা নিষিদ্ধ।"
            );
        }

        r.isActive = false;

        if (isOffline) {
            return ClientResult.offline(r);
        }
        return ClientResult.success(r);
    }

    private String computeHash(String identId, String tenantId, String barcode, String type, String level, int priority, int version) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = String.format("%s|%s|%s|%s|%s|%d|%d", identId, tenantId, barcode, type, level, priority, version);
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
