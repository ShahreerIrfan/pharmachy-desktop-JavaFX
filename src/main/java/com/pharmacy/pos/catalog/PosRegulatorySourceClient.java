package com.pharmacy.pos.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosRegulatorySourceClient
 * Architecture Decision: ADR-0134 (P134).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * JavaFX Desktop POS client for Legal and Manufacturer Regulatory Source Attachment:
 * Classification & Storage Rules, Cold Chain Validation, Review Cycles, and Offline Caching.
 */
public class PosRegulatorySourceClient {

    public enum UiStatus {
        SUCCESS,
        ERROR,
        PERMISSION_DENIED,
        EMPTY,
        OFFLINE
    }

    public enum SourceType {
        DGDA_GAZETTE,
        DGDA_CIRCULAR,
        MANUFACTURER_MONOGRAPH,
        PHARMACOPOEIA,
        WHO_AWARE,
        CLINICAL_GUIDELINE
    }

    public enum RuleTarget {
        CLASSIFICATION,
        STORAGE_CONDITION,
        CLASSIFICATION_AND_STORAGE
    }

    public static final class StorageRuleSpecs {
        public final boolean requiresColdChain;
        public final Double minTempCelsius;
        public final Double maxTempCelsius;
        public final boolean protectFromLight;
        public final Integer maxHumidityPercent;
        public final String handlingInstructions;

        public StorageRuleSpecs(
                boolean requiresColdChain,
                Double minTempCelsius,
                Double maxTempCelsius,
                boolean protectFromLight,
                Integer maxHumidityPercent,
                String handlingInstructions) {
            this.requiresColdChain = requiresColdChain;
            this.minTempCelsius = minTempCelsius;
            this.maxTempCelsius = maxTempCelsius;
            this.protectFromLight = protectFromLight;
            this.maxHumidityPercent = maxHumidityPercent;
            this.handlingInstructions = handlingInstructions != null ? handlingInstructions : "";
        }
    }

    public static final class ClassificationSpecs {
        public final String classificationType;
        public final boolean isAntibiotic;
        public final boolean isNarcotic;
        public final String specialDispensingConditions;

        public ClassificationSpecs(
                String classificationType,
                boolean isAntibiotic,
                boolean isNarcotic,
                String specialDispensingConditions) {
            this.classificationType = classificationType != null ? classificationType : "OTC";
            this.isAntibiotic = isAntibiotic;
            this.isNarcotic = isNarcotic;
            this.specialDispensingConditions = specialDispensingConditions != null ? specialDispensingConditions : "";
        }
    }

    public static final class RegulatorySourceRecord {
        public final String attachmentId;
        public final String productId;
        public final SourceType sourceType;
        public final RuleTarget ruleTarget;
        public final String documentReferenceNumber;
        public final String issuingAuthority;
        public final LocalDate publicationDate;
        public final LocalDate effectiveDate;
        public final int reviewCycleMonths;
        public LocalDate nextReviewDueDate;
        public String sourceUrl;
        public StorageRuleSpecs storageSpecs;
        public ClassificationSpecs classificationSpecs;
        public final int versionNumber;
        public boolean isActive;
        public String immutableSourceHash;
        public String reviewerEmail;
        public Instant reviewedAt;
        public String reviewNotes;
        public final Instant createdAt;

        public RegulatorySourceRecord(
                String attachmentId,
                String productId,
                SourceType sourceType,
                RuleTarget ruleTarget,
                String documentReferenceNumber,
                String issuingAuthority,
                LocalDate publicationDate,
                LocalDate effectiveDate,
                int reviewCycleMonths,
                LocalDate nextReviewDueDate,
                String sourceUrl,
                StorageRuleSpecs storageSpecs,
                ClassificationSpecs classificationSpecs,
                int versionNumber,
                boolean isActive,
                String immutableSourceHash,
                String reviewerEmail,
                Instant reviewedAt,
                String reviewNotes,
                Instant createdAt) {
            this.attachmentId = attachmentId;
            this.productId = productId;
            this.sourceType = sourceType;
            this.ruleTarget = ruleTarget;
            this.documentReferenceNumber = documentReferenceNumber;
            this.issuingAuthority = issuingAuthority;
            this.publicationDate = publicationDate;
            this.effectiveDate = effectiveDate;
            this.reviewCycleMonths = reviewCycleMonths;
            this.nextReviewDueDate = nextReviewDueDate;
            this.sourceUrl = sourceUrl != null ? sourceUrl : "";
            this.storageSpecs = storageSpecs;
            this.classificationSpecs = classificationSpecs;
            this.versionNumber = versionNumber;
            this.isActive = isActive;
            this.immutableSourceHash = immutableSourceHash;
            this.reviewerEmail = reviewerEmail;
            this.reviewedAt = reviewedAt;
            this.reviewNotes = reviewNotes != null ? reviewNotes : "";
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

    private final Map<String, RegulatorySourceRecord> attachmentStore = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> productCatalogStore = new ConcurrentHashMap<>();
    private boolean isOffline = false;

    public void setOffline(boolean offline) {
        this.isOffline = offline;
    }

    public boolean isOffline() {
        return isOffline;
    }

    public void registerProduct(String productId, Map<String, Object> initialAttributes) {
        productCatalogStore.put(productId, new HashMap<>(initialAttributes));
    }

    public Map<String, Object> getProduct(String productId) {
        Map<String, Object> p = productCatalogStore.get(productId);
        return p != null ? Collections.unmodifiableMap(new HashMap<>(p)) : null;
    }

    private boolean isAuthorizedRole(String role) {
        if (role == null) return false;
        String r = role.trim().toUpperCase();
        return r.equals("SUPERADMIN") || r.equals("PLATFORM_OPERATOR") ||
                r.equals("OWNER") || r.equals("PHARMACY_OWNER") ||
                r.equals("MANAGER") || r.equals("BRANCH_MANAGER") ||
                r.equals("PHARMACIST") || r.equals("STAFF_PHARMACIST");
    }

    public ClientResult<RegulatorySourceRecord> attachSource(
            String productId,
            SourceType sourceType,
            RuleTarget ruleTarget,
            String documentReferenceNumber,
            String issuingAuthority,
            LocalDate effectiveDate,
            LocalDate publicationDate,
            int reviewCycleMonths,
            LocalDate nextReviewDueDate,
            String sourceUrl,
            StorageRuleSpecs storageSpecs,
            ClassificationSpecs classificationSpecs,
            String userRole,
            String reviewerEmail,
            String reviewNotes) {

        if (!isAuthorizedRole(userRole)) {
            return ClientResult.permissionDenied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot attach or mutate regulatory and storage rules.",
                    "ক্যাশিয়াররা রেগুলেটরি বা স্টোরেজ রুল পরিবর্তন করতে পারবেন না।"
            );
        }

        Map<String, Object> product = productCatalogStore.get(productId);
        if (product == null) {
            return ClientResult.error(
                    "ERR_PRODUCT_NOT_FOUND",
                    "Medicine product '" + productId + "' not found.",
                    "মেডিসিন পণ্যটি পাওয়া যায়নি।"
            );
        }

        if (documentReferenceNumber == null || documentReferenceNumber.trim().isEmpty()) {
            return ClientResult.error(
                    "ERR_INVALID_INPUT",
                    "documentReferenceNumber is required.",
                    "ডকুমেন্ট রেফারেন্স নম্বর প্রদান আবশ্যক।"
            );
        }

        if (effectiveDate == null) {
            return ClientResult.error(
                    "ERR_INVALID_INPUT",
                    "effectiveDate is required.",
                    "কার্যকর তারিখ প্রদান আবশ্যক।"
            );
        }

        // Validate Review Date order
        LocalDate dueDate = nextReviewDueDate != null ? nextReviewDueDate : effectiveDate.plusMonths(reviewCycleMonths > 0 ? reviewCycleMonths : 12);
        if (dueDate.isBefore(effectiveDate)) {
            return ClientResult.error(
                    "ERR_INVALID_REVIEW_DATE",
                    "next_review_due_date cannot be earlier than effective_date.",
                    "পরবর্তী রিভিউয়ের তারিখ কার্যকর তারিখের পূর্বে হতে পারবে না।"
            );
        }

        // Validate Storage Rule (Cold Chain)
        if (ruleTarget == RuleTarget.STORAGE_CONDITION || ruleTarget == RuleTarget.CLASSIFICATION_AND_STORAGE) {
            if (storageSpecs != null && storageSpecs.requiresColdChain) {
                if (storageSpecs.minTempCelsius == null || storageSpecs.maxTempCelsius == null) {
                    return ClientResult.error(
                            "ERR_COLD_CHAIN_SPECS_REQUIRED",
                            "Cold chain storage rules require both min_temp_celsius and max_temp_celsius.",
                            "কোল্ড চেইনের জন্য সর্বনিম্ন ও সর্বোচ্চ তাপমাত্রা উভয়ই আবশ্যক।"
                    );
                }
                if (storageSpecs.minTempCelsius >= storageSpecs.maxTempCelsius) {
                    return ClientResult.error(
                            "ERR_COLD_CHAIN_BOUNDARY_VIOLATION",
                            "Cold chain rule violation: min_temp_celsius must be strictly less than max_temp_celsius.",
                            "কোল্ড চেইনে সর্বনিম্ন তাপমাত্রা অবশ্যই সর্বোচ্চ তাপমাত্রার কম হতে হবে।"
                    );
                }

                // Synchronize parent product
                product.put("requires_cold_chain", true);
                product.put("min_temp_celsius", storageSpecs.minTempCelsius);
                product.put("max_temp_celsius", storageSpecs.maxTempCelsius);
            } else {
                product.put("requires_cold_chain", false);
            }
        }

        // Validate Classification Rule
        if (ruleTarget == RuleTarget.CLASSIFICATION || ruleTarget == RuleTarget.CLASSIFICATION_AND_STORAGE) {
            if (classificationSpecs != null) {
                String cType = classificationSpecs.classificationType.trim().toUpperCase();
                if (cType.equals("PRESCRIPTION_ONLY") || cType.equals("POM")) {
                    product.put("is_prescription_only", true);
                }
                if (classificationSpecs.isAntibiotic) {
                    product.put("is_antibiotic", true);
                }
                if (classificationSpecs.isNarcotic) {
                    product.put("is_narcotic", true);
                }
            }
        }

        // Versioning & Deduplication
        int existingMaxVer = 0;
        for (RegulatorySourceRecord r : attachmentStore.values()) {
            if (r.productId.equals(productId) && r.documentReferenceNumber.equalsIgnoreCase(documentReferenceNumber.trim())) {
                if (r.versionNumber > existingMaxVer) {
                    existingMaxVer = r.versionNumber;
                }
            }
        }
        int versionNumber = existingMaxVer + 1;

        String attachmentId = "reg-src-pos-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String hash = computeSourceHash(productId, documentReferenceNumber, versionNumber, sourceType.name(), ruleTarget.name(), effectiveDate.toString());

        RegulatorySourceRecord record = new RegulatorySourceRecord(
                attachmentId,
                productId,
                sourceType,
                ruleTarget,
                documentReferenceNumber.trim(),
                issuingAuthority != null ? issuingAuthority.trim() : "",
                publicationDate,
                effectiveDate,
                reviewCycleMonths,
                dueDate,
                sourceUrl,
                storageSpecs,
                classificationSpecs,
                versionNumber,
                true,
                hash,
                reviewerEmail,
                Instant.now(),
                reviewNotes,
                Instant.now()
        );

        attachmentStore.put(attachmentId, record);
        product.put("regulatory_source", documentReferenceNumber.trim());

        if (isOffline) {
            return ClientResult.offline(record);
        }
        return ClientResult.success(record);
    }

    public ClientResult<List<RegulatorySourceRecord>> getDueReviews(LocalDate cutoffDate) {
        LocalDate target = cutoffDate != null ? cutoffDate : LocalDate.now();
        List<RegulatorySourceRecord> dueList = new ArrayList<>();
        for (RegulatorySourceRecord r : attachmentStore.values()) {
            if (r.isActive && (r.nextReviewDueDate.isBefore(target) || r.nextReviewDueDate.isEqual(target))) {
                dueList.add(r);
            }
        }

        dueList.sort(Comparator.comparing(r -> r.nextReviewDueDate));

        if (dueList.isEmpty()) {
            return ClientResult.empty("No regulatory sources due for periodic review.", "পুনঃপর্যালোচনার জন্য কোন রেগুলেটরি সোর্স নেই।");
        }

        if (isOffline) {
            return ClientResult.offline(Collections.unmodifiableList(dueList));
        }
        return ClientResult.success(Collections.unmodifiableList(dueList));
    }

    public ClientResult<List<RegulatorySourceRecord>> searchAttachments(String productId, String search, RuleTarget target) {
        List<RegulatorySourceRecord> results = new ArrayList<>();
        String query = search != null ? search.trim().toLowerCase() : "";

        for (RegulatorySourceRecord r : attachmentStore.values()) {
            if (productId != null && !r.productId.equals(productId)) {
                continue;
            }
            if (target != null && r.ruleTarget != target) {
                continue;
            }
            if (!query.isEmpty()) {
                boolean match = r.documentReferenceNumber.toLowerCase().contains(query)
                        || r.issuingAuthority.toLowerCase().contains(query)
                        || r.productId.toLowerCase().contains(query);
                if (!match) continue;
            }
            results.add(r);
        }

        if (isOffline) {
            return ClientResult.offline(Collections.unmodifiableList(results));
        }
        return ClientResult.success(Collections.unmodifiableList(results));
    }

    public ClientResult<RegulatorySourceRecord> archiveAttachment(String attachmentId, String userRole, String reason) {
        if (!isAuthorizedRole(userRole)) {
            return ClientResult.permissionDenied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot archive regulatory source attachments.",
                    "ক্যাশিয়াররা রেগুলেটরি সোর্স আর্কাইভ করতে পারবেন না।"
            );
        }

        RegulatorySourceRecord r = attachmentStore.get(attachmentId);
        if (r == null) {
            return ClientResult.error(
                    "ERR_ATTACHMENT_NOT_FOUND",
                    "Attachment '" + attachmentId + "' not found.",
                    "রেগুলেটরি রেকর্ডটি পাওয়া যায়নি।"
            );
        }

        r.isActive = false;
        r.reviewNotes = (r.reviewNotes + " [Archived: " + (reason != null ? reason : "") + "]").trim();

        if (isOffline) {
            return ClientResult.offline(r);
        }
        return ClientResult.success(r);
    }

    private String computeSourceHash(String productId, String docRef, int version, String sourceType, String ruleTarget, String effectiveDate) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = String.format("%s|%s|%d|%s|%s|%s", productId, docRef, version, sourceType, ruleTarget, effectiveDate);
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
