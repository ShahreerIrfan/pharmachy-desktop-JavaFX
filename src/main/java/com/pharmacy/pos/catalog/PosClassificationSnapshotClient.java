package com.pharmacy.pos.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * PosClassificationSnapshotClient
 * Architecture Decision: ADR-0130 (P130).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * JavaFX Desktop POS client for Controlled Classification Snapshots, DGDA Regulatory
 * Recall Hard-Blocks, Point-in-Time Temporal Audits, and Master Product Synchronization.
 */
public class PosClassificationSnapshotClient {

    public enum UiStatus {
        SUCCESS,
        ERROR,
        PERMISSION_DENIED,
        EMPTY,
        OFFLINE
    }

    public enum ClassificationType {
        OTC,
        PRESCRIPTION_ONLY,
        NARCOTIC_SCHEDULED,
        ANTIMICROBIAL_ACCESS,
        ANTIMICROBIAL_WATCH,
        ANTIMICROBIAL_RESERVE,
        RECALLED,
        BANNED
    }

    public static final class MasterMedicine {
        public final String productId;
        public final String brandName;
        public final String genericName;
        public boolean isPrescriptionOnly;
        public boolean isNarcotic;
        public boolean isAntibiotic;
        public String curationStatus; // DRAFT, UNDER_REVIEW, APPROVED, RECALLED, SUSPENDED

        public MasterMedicine(
                String productId,
                String brandName,
                String genericName,
                boolean isPrescriptionOnly,
                boolean isNarcotic,
                boolean isAntibiotic,
                String curationStatus) {
            this.productId = productId;
            this.brandName = brandName;
            this.genericName = genericName;
            this.isPrescriptionOnly = isPrescriptionOnly;
            this.isNarcotic = isNarcotic;
            this.isAntibiotic = isAntibiotic;
            this.curationStatus = curationStatus;
        }
    }

    public static final class ClassificationSnapshot {
        public final String snapshotId;
        public final String productId;
        public final int versionNumber;
        public final ClassificationType classificationType;
        public final String regulatorySource;
        public final Instant effectiveFrom;
        public Instant effectiveTo;
        public boolean isCurrent;
        public final boolean isDispensingBlocked;
        public final String reviewerEmail;
        public final Instant reviewedAt;
        public final String reviewNotes;
        public final String immutableHash;
        public final Instant createdAt;

        public ClassificationSnapshot(
                String snapshotId,
                String productId,
                int versionNumber,
                ClassificationType classificationType,
                String regulatorySource,
                Instant effectiveFrom,
                Instant effectiveTo,
                boolean isCurrent,
                boolean isDispensingBlocked,
                String reviewerEmail,
                Instant reviewedAt,
                String reviewNotes,
                String immutableHash,
                Instant createdAt) {
            this.snapshotId = snapshotId;
            this.productId = productId;
            this.versionNumber = versionNumber;
            this.classificationType = classificationType;
            this.regulatorySource = regulatorySource;
            this.effectiveFrom = effectiveFrom;
            this.effectiveTo = effectiveTo;
            this.isCurrent = isCurrent;
            this.isDispensingBlocked = isDispensingBlocked;
            this.reviewerEmail = reviewerEmail;
            this.reviewedAt = reviewedAt;
            this.reviewNotes = reviewNotes;
            this.immutableHash = immutableHash;
            this.createdAt = createdAt;
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

        public static <T> ClientResult<T> offline(T data) {
            return new ClientResult<>(UiStatus.OFFLINE, data, "STATUS_OFFLINE_CACHE", "Operating in offline mode. Snapshot served from local verified cache.", "অফলাইন মোড সক্রিয়। লোকাল ক্যাশ থেকে তথ্য প্রদর্শিত হচ্ছে।");
        }

        public static <T> ClientResult<T> permissionDenied(String code, String en, String bn) {
            return new ClientResult<>(UiStatus.PERMISSION_DENIED, null, code, en, bn);
        }

        public static <T> ClientResult<T> error(String code, String en, String bn) {
            return new ClientResult<>(UiStatus.ERROR, null, code, en, bn);
        }

        public static <T> ClientResult<T> empty(String en, String bn) {
            return new ClientResult<>(UiStatus.EMPTY, null, "ERR_NOT_FOUND", en, bn);
        }
    }

    private final Map<String, MasterMedicine> medicineRegistry = new ConcurrentHashMap<>();
    private final Map<String, List<ClassificationSnapshot>> snapshotStore = new ConcurrentHashMap<>();
    private final Map<String, ClassificationSnapshot> offlineSnapshotCache = new ConcurrentHashMap<>();
    private boolean isOffline = false;

    public void registerMasterMedicine(MasterMedicine medicine) {
        if (medicine != null && medicine.productId != null) {
            medicineRegistry.put(medicine.productId, medicine);
        }
    }

    public MasterMedicine getMasterMedicine(String productId) {
        return medicineRegistry.get(productId);
    }

    public void setOfflineMode(boolean offline) {
        this.isOffline = offline;
    }

    public boolean isOfflineMode() {
        return this.isOffline;
    }

    public static String computeImmutableHash(
            String productId,
            int versionNumber,
            ClassificationType type,
            String regulatorySource,
            Instant effectiveFrom) {
        try {
            String raw = String.format("%s|%d|%s|%s|%s",
                    productId,
                    versionNumber,
                    type.name(),
                    regulatorySource.trim(),
                    effectiveFrom.toString());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Creates a new classification snapshot. Only PLATFORM_OPERATOR / SUPERADMIN can perform this action.
     */
    public synchronized ClientResult<ClassificationSnapshot> createSnapshot(
            String actorRole,
            String actorEmail,
            String productId,
            ClassificationType type,
            String regulatorySource,
            Instant effectiveFrom,
            String reviewNotes) {

        // 1. RBAC Guard: Platform Authority
        String cleanRole = actorRole != null ? actorRole.trim().toUpperCase() : "";
        if (!"SUPERADMIN".equals(cleanRole) && !"SUPER_ADMIN".equals(cleanRole) && !"PLATFORM_OPERATOR".equals(cleanRole)) {
            return ClientResult.permissionDenied(
                    "PLATFORM_CATALOG_OWNERSHIP_PROTECTED",
                    "Tenant users cannot mutate global regulatory medicine classification snapshots.",
                    "টেন্যান্ট ব্যবহারকারীরা গ্লোবাল ওষুধ ক্লাসিফিকেশন স্ন্যাপশট পরিবর্তন করতে পারবেন না।"
            );
        }

        // 2. Validate Product Existence
        MasterMedicine product = medicineRegistry.get(productId);
        if (product == null) {
            return ClientResult.error(
                    "ERR_PRODUCT_NOT_FOUND",
                    "Medicine product '" + productId + "' not found in global catalog.",
                    "ওষুধ প্রোডাক্ট '" + productId + "' ক্যাটালগে পাওয়া যায়নি।"
            );
        }

        if (regulatorySource == null || regulatorySource.trim().length() < 3) {
            return ClientResult.error(
                    "ERR_INVALID_REGULATORY_SOURCE",
                    "Regulatory source reference must be at least 3 characters.",
                    "রেগুলেটরি রেফারেন্স নূন্যতম ৩ অক্ষরের হতে হবে।"
            );
        }

        if (reviewNotes == null || reviewNotes.trim().length() < 5) {
            return ClientResult.error(
                    "ERR_INVALID_REVIEW_NOTES",
                    "Review and justification notes must be at least 5 characters.",
                    "পর্যালোচনা নোট নূন্যতম ৫ অক্ষরের হতে হবে।"
            );
        }

        Instant effFrom = (effectiveFrom != null) ? effectiveFrom : Instant.now();
        List<ClassificationSnapshot> history = snapshotStore.computeIfAbsent(productId, k -> new ArrayList<>());

        // 3. Supersede previous active snapshot
        int nextVersion = 1;
        for (ClassificationSnapshot s : history) {
            if (s.isCurrent) {
                s.isCurrent = false;
                s.effectiveTo = effFrom;
            }
            if (s.versionNumber >= nextVersion) {
                nextVersion = s.versionNumber + 1;
            }
        }

        boolean isBlocked = (type == ClassificationType.RECALLED || type == ClassificationType.BANNED);
        String immutableHash = computeImmutableHash(productId, nextVersion, type, regulatorySource, effFrom);

        ClassificationSnapshot snapshot = new ClassificationSnapshot(
                "mcs-" + UUID.randomUUID().toString().substring(0, 8),
                productId,
                nextVersion,
                type,
                regulatorySource.trim(),
                effFrom,
                null,
                true,
                isBlocked,
                actorEmail,
                Instant.now(),
                reviewNotes.trim(),
                immutableHash,
                Instant.now()
        );

        history.add(snapshot);
        offlineSnapshotCache.put(productId, snapshot);

        // 4. Synchronize Master Medicine Flags
        if (type == ClassificationType.RECALLED) {
            product.curationStatus = "RECALLED";
        } else if (type == ClassificationType.BANNED) {
            product.curationStatus = "SUSPENDED";
        } else if (type == ClassificationType.PRESCRIPTION_ONLY) {
            product.isPrescriptionOnly = true;
        } else if (type == ClassificationType.NARCOTIC_SCHEDULED) {
            product.isPrescriptionOnly = true;
            product.isNarcotic = true;
        } else if (type == ClassificationType.ANTIMICROBIAL_ACCESS
                || type == ClassificationType.ANTIMICROBIAL_WATCH
                || type == ClassificationType.ANTIMICROBIAL_RESERVE) {
            product.isPrescriptionOnly = true;
            product.isAntibiotic = true;
        } else if (type == ClassificationType.OTC) {
            product.isPrescriptionOnly = false;
            product.isNarcotic = false;
            product.isAntibiotic = false;
        }

        return ClientResult.success(snapshot);
    }

    /**
     * Resolves currently active classification snapshot.
     */
    public ClientResult<ClassificationSnapshot> getCurrentSnapshot(String productId) {
        if (isOffline) {
            ClassificationSnapshot cached = offlineSnapshotCache.get(productId);
            if (cached != null) {
                return ClientResult.offline(cached);
            }
            return ClientResult.empty(
                    "No cached classification found offline for product '" + productId + "'.",
                    "অফলাইনে '" + productId + "' পণ্যের কোনো ক্যাশ স্ন্যাপশট পাওয়া যায়নি।"
            );
        }

        List<ClassificationSnapshot> history = snapshotStore.get(productId);
        if (history != null) {
            for (ClassificationSnapshot s : history) {
                if (s.isCurrent) {
                    return ClientResult.success(s);
                }
            }
        }

        return ClientResult.empty(
                "No active classification snapshot found for product '" + productId + "'.",
                "'" + productId + "' পণ্যের জন্য কোনো সক্রিয় ক্লাসিফিকেশন স্ন্যাপশট পাওয়া যায়নি।"
        );
    }

    /**
     * Point-in-time temporal resolution: Answers historical regulatory classification for past sales audits.
     */
    public ClientResult<ClassificationSnapshot> getPointInTimeSnapshot(String productId, Instant targetTime) {
        if (targetTime == null) {
            return ClientResult.error(
                    "ERR_INVALID_DATE",
                    "Target date must not be null.",
                    "তারিখ ফাঁকা রাখা যাবে না।"
            );
        }

        List<ClassificationSnapshot> history = snapshotStore.get(productId);
        if (history == null || history.isEmpty()) {
            return ClientResult.empty(
                    "No snapshots recorded for medicine '" + productId + "'.",
                    "ওষুধ '" + productId + "'-এর কোনো স্ন্যাপশট রেকর্ড পাওয়া যায়নি।"
            );
        }

        ClassificationSnapshot resolved = null;
        for (ClassificationSnapshot s : history) {
            boolean afterOrAtFrom = !s.effectiveFrom.isAfter(targetTime);
            boolean beforeTo = (s.effectiveTo == null || s.effectiveTo.isAfter(targetTime));
            if (afterOrAtFrom && beforeTo) {
                if (resolved == null || s.versionNumber > resolved.versionNumber) {
                    resolved = s;
                }
            }
        }

        if (resolved == null) {
            return ClientResult.empty(
                    "No classification snapshot was effective at the requested timestamp.",
                    "অনুরোধকৃত সময়ে কোনো কার্যকর ক্লাসিফিকেশন স্ন্যাপশট পাওয়া যায়নি।"
            );
        }

        return ClientResult.success(resolved);
    }

    /**
     * Hard-block check for POS cashier checkouts.
     */
    public synchronized boolean isDispensingBlocked(String productId) {
        ClientResult<ClassificationSnapshot> current = getCurrentSnapshot(productId);
        if (current.data != null) {
            return current.data.isDispensingBlocked;
        }
        return false;
    }

    /**
     * Full audit history of snapshots.
     */
    public List<ClassificationSnapshot> getSnapshotHistory(String productId) {
        List<ClassificationSnapshot> history = snapshotStore.get(productId);
        if (history == null) {
            return Collections.emptyList();
        }
        return history.stream()
                .sorted((a, b) -> Integer.compare(b.versionNumber, a.versionNumber))
                .collect(Collectors.toList());
    }
}
