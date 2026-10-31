package com.pharmacy.pos.security;

import com.pharmacy.pos.contracts.PosApiResponseEnvelope.UiState;
import com.pharmacy.pos.models.PosCursorPage;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * JavaFX Desktop POS Safe Pagination & Unicode Search Service.
 * Architecture Decision: ADR-0053 (Deterministic Cursor Pagination, Bounded Page Sizes, and Validated Search).
 */
public class PosSearchPaginationService {

    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    public static final Set<String> PROHIBITED_PROBES = Set.of(
            "password", "secret", "token", "hash", "salt", "is_superuser", "is_staff"
    );

    /**
     * Strictly bounds requested page size between 1 and 100.
     * Throws IllegalArgumentException if requested size <= 0.
     */
    public static int boundPageSize(int requestedSize) {
        if (requestedSize < MIN_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size must be strictly positive (>= 1)");
        }
        return Math.min(requestedSize, MAX_PAGE_SIZE);
    }

    /**
     * Normalizes search query using Unicode NFKC, strips zero-width joiners/non-joiners, trims and lowercases.
     */
    public static String normalizeSearchTerm(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        String cleaned = normalized.replace("\u200C", "").replace("\u200D", "");
        return cleaned.trim().toLowerCase();
    }

    /**
     * Validates that all query filter parameters adhere to the declared whitelist
     * and do not contain direct ORM lookups (__) or sensitive keyword probes.
     */
    public static boolean validateFilterParameters(Map<String, String> queryParams, Set<String> allowedFields) {
        if (queryParams == null || queryParams.isEmpty()) return true;

        for (String param : queryParams.keySet()) {
            if (param == null || param.isBlank()) continue;
            String cleanParam = param.trim();

            // 1. Direct ORM lookup probe protection
            if (cleanParam.contains("__")) {
                throw new IllegalArgumentException("Direct ORM lookup probe '" + cleanParam + "' is strictly forbidden.");
            }

            // 2. Sensitive attribute probe protection
            String lower = cleanParam.toLowerCase();
            for (String probe : PROHIBITED_PROBES) {
                if (lower.contains(probe)) {
                    throw new IllegalArgumentException("Querying sensitive attribute '" + cleanParam + "' is strictly forbidden.");
                }
            }

            // 3. Whitelist enforcement
            if (allowedFields != null && !allowedFields.isEmpty() && !allowedFields.contains(cleanParam)) {
                throw new IllegalArgumentException("Unrecognized or forbidden filter parameter '" + cleanParam + "'.");
            }
        }
        return true;
    }

    /**
     * Strictly validates that requested tenant matches trusted session tenant context.
     */
    public static boolean verifyTenantBoundary(String activeTenantId, String requestedTenantId) {
        Objects.requireNonNull(activeTenantId, "activeTenantId cannot be null");
        if (requestedTenantId == null || requestedTenantId.isBlank()) return true;
        if (!activeTenantId.equalsIgnoreCase(requestedTenantId.trim())) {
            throw new SecurityException("ERR_CROSS_TENANT_VIOLATION: Cross-tenant search query strictly forbidden.");
        }
        return true;
    }

    /**
     * Parses server response envelope into generic PosCursorPage.
     */
    @SuppressWarnings("unchecked")
    public static <T> PosCursorPage<T> parseCursorPage(Map<String, Object> envelope) {
        Objects.requireNonNull(envelope, "Response envelope cannot be null");

        String requestId = (String) envelope.getOrDefault("request_id", "");
        List<T> items = (List<T>) envelope.getOrDefault("data", Collections.emptyList());

        Map<String, Object> meta = (Map<String, Object>) envelope.get("meta");
        if (meta == null) {
            return new PosCursorPage<>(items, items.size() > 0 ? items.size() : DEFAULT_PAGE_SIZE,
                    false, false, null, null, Collections.emptyMap(), requestId);
        }

        Map<String, Object> pagination = (Map<String, Object>) meta.get("pagination");
        Map<String, String> filters = (Map<String, String>) meta.getOrDefault("filters", Collections.emptyMap());

        if (pagination == null) {
            return new PosCursorPage<>(items, items.size() > 0 ? items.size() : DEFAULT_PAGE_SIZE,
                    false, false, null, null, filters, requestId);
        }

        int pageSize = ((Number) pagination.getOrDefault("page_size", DEFAULT_PAGE_SIZE)).intValue();
        boolean hasNext = Boolean.TRUE.equals(pagination.get("has_next"));
        boolean hasPrevious = Boolean.TRUE.equals(pagination.get("has_previous"));
        String nextCursor = (String) pagination.get("next_cursor");
        String previousCursor = (String) pagination.get("previous_cursor");

        return new PosCursorPage<>(items, boundPageSize(pageSize), hasNext, hasPrevious, nextCursor, previousCursor, filters, requestId);
    }

    /**
     * Resolves localized bilingual error message conforming to ADR-0014, ADR-0047, and ADR-0053.
     */
    public static Map<String, String> getBilingualErrorMessage(String errorCode) {
        Map<String, String> messages = new HashMap<>();
        if ("ERR_INVALID_CURSOR".equalsIgnoreCase(errorCode)) {
            messages.put("en", "The provided pagination cursor is invalid, expired, or malformed.");
            messages.put("bn", "প্রদত্ত পেজিনেশন কার্সারটি অবৈধ, মেয়াদোত্তীর্ণ বা বিকৃত।");
        } else if ("ERR_VALIDATION_FAILED".equalsIgnoreCase(errorCode)) {
            messages.put("en", "One or more query parameters or filter bounds failed validation.");
            messages.put("bn", "এক বা একাধিক ফিল্টার প্যারামিটার বা পৃষ্ঠার পরিধি সঠিক নয়।");
        } else if ("ERR_CROSS_TENANT_VIOLATION".equalsIgnoreCase(errorCode)) {
            messages.put("en", "Cross-tenant search query is strictly forbidden.");
            messages.put("bn", "অন্য ফার্মেসির তথ্যে অনুসন্ধান কঠোরভাবে নিষিদ্ধ।");
        } else {
            messages.put("en", "Search and pagination operation failed. Please check network connection.");
            messages.put("bn", "অনুসন্ধান ও পেজিনেশন পরিচালনায় ত্রুটি দেখা দিয়েছে। নেটওয়ার্ক সংযোগ পরীক্ষা করুন।");
        }
        return Collections.unmodifiableMap(messages);
    }

    /**
     * Maps error code to JavaFX UI state.
     */
    public static UiState resolveUiState(String errorCode) {
        if ("ERR_CROSS_TENANT_VIOLATION".equalsIgnoreCase(errorCode)
                || "ERR_PERMISSION_DENIED".equalsIgnoreCase(errorCode)) {
            return UiState.PERMISSION_DENIED;
        }
        return UiState.ERROR;
    }
}

