package com.pharmacy.pos.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosProductMetadataReviewClient
 * Architecture Decision: ADR-0133 (P133).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * JavaFX Desktop POS client for Medicine Metadata Review Lifecycle:
 * Proposed Diff Computation, Dual-Control Approvals (Four-Eyes Principle),
 * Publication to Master Catalog, and Offline Review Cache.
 */
public class PosProductMetadataReviewClient {

    public enum UiStatus {
        SUCCESS,
        ERROR,
        PERMISSION_DENIED,
        EMPTY,
        OFFLINE
    }

    public enum ReviewStatus {
        PENDING_REVIEW,
        APPROVED,
        REJECTED,
        CHANGES_REQUESTED
    }

    public enum ReviewChangeType {
        NEW_PRODUCT_DRAFT,
        METADATA_UPDATE,
        PRICE_REVISION,
        SAFETY_REASSESSMENT
    }

    public static final class FieldDiff {
        public final String field;
        public final Object oldValue;
        public final Object newValue;

        public FieldDiff(String field, Object oldValue, Object newValue) {
            this.field = field;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        @Override
        public String toString() {
            return String.format("[%s: %s -> %s]", field, oldValue, newValue);
        }
    }

    public static final class MetadataReviewRecord {
        public final String reviewId;
        public final String productId;
        public final String productBrandName;
        public final ReviewChangeType changeType;
        public ReviewStatus status;
        public final Map<String, FieldDiff> proposedDiff;
        public final String submitterEmail;
        public final Instant submittedAt;
        public String reviewerEmail;
        public Instant reviewedAt;
        public String reviewNotes;
        public String reviewHash;
        public final Instant createdAt;

        public MetadataReviewRecord(
                String reviewId,
                String productId,
                String productBrandName,
                ReviewChangeType changeType,
                ReviewStatus status,
                Map<String, FieldDiff> proposedDiff,
                String submitterEmail,
                Instant submittedAt,
                String reviewHash) {
            this.reviewId = reviewId;
            this.productId = productId;
            this.productBrandName = productBrandName;
            this.changeType = changeType;
            this.status = status;
            this.proposedDiff = proposedDiff != null ? Collections.unmodifiableMap(new HashMap<>(proposedDiff)) : Collections.emptyMap();
            this.submitterEmail = submitterEmail;
            this.submittedAt = submittedAt;
            this.reviewHash = reviewHash;
            this.createdAt = submittedAt;
            this.reviewNotes = "";
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
            return new ClientResult<>(UiStatus.OFFLINE, data, "STATUS_OFFLINE_CACHE", "Operating in offline cached review mode.", "অফলাইন ক্যাশড রিভিউ মোড সক্রিয়।");
        }

        public static <T> ClientResult<T> permissionDenied(String code, String en, String bn) {
            return new ClientResult<>(UiStatus.PERMISSION_DENIED, null, code, en, bn);
        }

        public static <T> ClientResult<T> error(String code, String en, String bn) {
            return new ClientResult<>(UiStatus.ERROR, null, code, en, bn);
        }
    }

    private final Map<String, MetadataReviewRecord> reviewStore = new ConcurrentHashMap<>();
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

    public ClientResult<MetadataReviewRecord> proposeReview(
            String productId,
            ReviewChangeType changeType,
            Map<String, Object> proposedChanges,
            String submitterEmail,
            String userRole,
            String submissionNotes) {

        if (!isAuthorizedRole(userRole)) {
            return ClientResult.permissionDenied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot propose medicine metadata changes.",
                    "ক্যাশিয়াররা মেডিসিন মেটাডাটা রিভিউ প্রস্তাব করতে পারবেন না।"
            );
        }

        Map<String, Object> product = productCatalogStore.get(productId);
        if (product == null) {
            return ClientResult.error(
                    "ERR_PRODUCT_NOT_FOUND",
                    "Medicine product '" + productId + "' not found in catalog.",
                    "মেডিসিন পণ্যটি ক্যাটালগে পাওয়া যায়নি।"
            );
        }

        if (proposedChanges == null || proposedChanges.isEmpty()) {
            return ClientResult.error(
                    "ERR_NO_PROPOSED_CHANGES",
                    "No metadata fields proposed for review.",
                    "রিভিউ করার জন্য কোন ফিল্ড পাওয়া যায়নি।"
            );
        }

        // Compute diff
        Map<String, FieldDiff> diffMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : proposedChanges.entrySet()) {
            String field = entry.getKey();
            Object oldVal = product.get(field);
            Object newVal = entry.getValue();
            diffMap.put(field, new FieldDiff(field, oldVal, newVal));
        }

        String reviewId = "rev-pos-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant now = Instant.now();
        String brandName = (String) product.getOrDefault("brand_name", "");

        String hash = computeReviewHash(reviewId, productId, ReviewStatus.PENDING_REVIEW.name(), now.toString());

        MetadataReviewRecord record = new MetadataReviewRecord(
                reviewId,
                productId,
                brandName,
                changeType != null ? changeType : ReviewChangeType.METADATA_UPDATE,
                ReviewStatus.PENDING_REVIEW,
                diffMap,
                submitterEmail,
                now,
                hash
        );

        reviewStore.put(reviewId, record);
        product.put("curation_status", "UNDER_REVIEW");

        if (isOffline) {
            return ClientResult.offline(record);
        }
        return ClientResult.success(record);
    }

    public ClientResult<MetadataReviewRecord> approveReview(
            String reviewId,
            String reviewerEmail,
            String reviewerRole,
            String reviewNotes) {

        if (!isAuthorizedRole(reviewerRole)) {
            return ClientResult.permissionDenied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot approve medicine metadata reviews.",
                    "ক্যাশিয়াররা মেডিসিন মেটাডাটা রিভিউ অনুমোদন করতে পারবেন না।"
            );
        }

        MetadataReviewRecord review = reviewStore.get(reviewId);
        if (review == null) {
            return ClientResult.error(
                    "ERR_REVIEW_NOT_FOUND",
                    "Review record '" + reviewId + "' not found.",
                    "রিভিউ রেকর্ডটি পাওয়া যায়নি।"
            );
        }

        if (review.status != ReviewStatus.PENDING_REVIEW) {
            return ClientResult.error(
                    "ERR_REVIEW_ALREADY_DECIDED",
                    "Review has already been finalized as " + review.status,
                    "রিভিউটি ইতিমধ্যে নিষ্পন্ন হয়েছে।"
            );
        }

        // Four-Eyes Principle / Dual Control
        boolean isSuperAdmin = reviewerRole != null && reviewerRole.trim().equalsIgnoreCase("SUPERADMIN");
        if (review.submitterEmail != null && review.submitterEmail.equalsIgnoreCase(reviewerEmail) && !isSuperAdmin) {
            return ClientResult.permissionDenied(
                    "ERR_FOUR_EYES_PRINCIPLE_VIOLATION",
                    "Submitter cannot approve their own review proposal under Four-Eyes rule.",
                    "প্রস্তাবকারী নিজে নিজের রিভিউ অনুমোদন করতে পারবেন না।"
            );
        }

        if (reviewNotes == null || reviewNotes.trim().length() < 5) {
            return ClientResult.error(
                    "ERR_INVALID_REVIEW_NOTES",
                    "Review approval justification must be at least 5 characters.",
                    "অনুমোদনের জন্য ন্যূনতম ৫ অক্ষরের নোট প্রদান করতে হবে।"
            );
        }

        // Atomic merge to target product
        Map<String, Object> product = productCatalogStore.get(review.productId);
        if (product != null) {
            for (Map.Entry<String, FieldDiff> diffEntry : review.proposedDiff.entrySet()) {
                product.put(diffEntry.getKey(), diffEntry.getValue().newValue);
            }
            product.put("curation_status", "APPROVED");
            int ver = ((Number) product.getOrDefault("version_number", 1)).intValue();
            product.put("version_number", ver + 1);
        }

        Instant now = Instant.now();
        review.status = ReviewStatus.APPROVED;
        review.reviewerEmail = reviewerEmail;
        review.reviewedAt = now;
        review.reviewNotes = reviewNotes.trim();
        review.reviewHash = computeReviewHash(review.reviewId, review.productId, review.status.name(), now.toString());

        if (isOffline) {
            return ClientResult.offline(review);
        }
        return ClientResult.success(review);
    }

    public ClientResult<MetadataReviewRecord> rejectReview(
            String reviewId,
            String reviewerEmail,
            String reviewerRole,
            String reviewNotes) {

        if (!isAuthorizedRole(reviewerRole)) {
            return ClientResult.permissionDenied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot reject medicine metadata reviews.",
                    "ক্যাশিয়াররা রিভিউ প্রত্যাখ্যান করতে পারবেন না।"
            );
        }

        MetadataReviewRecord review = reviewStore.get(reviewId);
        if (review == null) {
            return ClientResult.error(
                    "ERR_REVIEW_NOT_FOUND",
                    "Review record '" + reviewId + "' not found.",
                    "রিভিউ রেকর্ডটি পাওয়া যায়নি।"
            );
        }

        if (review.status != ReviewStatus.PENDING_REVIEW) {
            return ClientResult.error(
                    "ERR_REVIEW_ALREADY_DECIDED",
                    "Review has already been finalized as " + review.status,
                    "রিভিউটি ইতিমধ্যে নিষ্পন্ন হয়েছে।"
            );
        }

        if (reviewNotes == null || reviewNotes.trim().length() < 5) {
            return ClientResult.error(
                    "ERR_INVALID_REVIEW_NOTES",
                    "Review rejection justification must be at least 5 characters.",
                    "প্রত্যাখ্যানের জন্য ন্যূনতম ৫ অক্ষরের কারণ প্রদান করতে হবে।"
            );
        }

        Instant now = Instant.now();
        review.status = ReviewStatus.REJECTED;
        review.reviewerEmail = reviewerEmail;
        review.reviewedAt = now;
        review.reviewNotes = reviewNotes.trim();
        review.reviewHash = computeReviewHash(review.reviewId, review.productId, review.status.name(), now.toString());

        if (isOffline) {
            return ClientResult.offline(review);
        }
        return ClientResult.success(review);
    }

    public ClientResult<List<MetadataReviewRecord>> getPendingReviews(String userRole) {
        if (!isAuthorizedRole(userRole)) {
            return ClientResult.permissionDenied(
                    "ERR_STAFF_ROLE_DENIED",
                    "Cashiers cannot access the pending metadata review queue.",
                    "ক্যাশিয়াররা অপেক্ষমাণ রিভিউ তালিকা দেখতে পারবেন না।"
            );
        }

        List<MetadataReviewRecord> pending = new ArrayList<>();
        for (MetadataReviewRecord r : reviewStore.values()) {
            if (r.status == ReviewStatus.PENDING_REVIEW) {
                pending.add(r);
            }
        }

        if (pending.isEmpty()) {
            return ClientResult.empty("No pending reviews found.", "কোন অপেক্ষমাণ রিভিউ নেই।");
        }

        if (isOffline) {
            return ClientResult.offline(Collections.unmodifiableList(pending));
        }
        return ClientResult.success(Collections.unmodifiableList(pending));
    }

    public ClientResult<List<MetadataReviewRecord>> getReviewHistory(String productId) {
        List<MetadataReviewRecord> history = new ArrayList<>();
        for (MetadataReviewRecord r : reviewStore.values()) {
            if (r.productId.equals(productId)) {
                history.add(r);
            }
        }

        if (isOffline) {
            return ClientResult.offline(Collections.unmodifiableList(history));
        }
        return ClientResult.success(Collections.unmodifiableList(history));
    }

    private String computeReviewHash(String reviewId, String productId, String status, String timestamp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = String.format("%s:%s:%s:%s", reviewId, productId, status, timestamp);
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}

