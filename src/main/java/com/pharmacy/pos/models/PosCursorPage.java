package com.pharmacy.pos.models;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable Cursor-Paginated Page Container for JavaFX Desktop POS.
 * Architecture Decision: ADR-0053 (Deterministic Cursor Pagination & Validated Search).
 */
public record PosCursorPage<T>(
        List<T> items,
        int pageSize,
        boolean hasNext,
        boolean hasPrevious,
        String nextCursor,
        String previousCursor,
        Map<String, String> activeFilters,
        String requestId
) {
    public PosCursorPage {
        items = items != null ? List.copyOf(items) : Collections.emptyList();
        activeFilters = activeFilters != null ? Map.copyOf(activeFilters) : Collections.emptyMap();
        requestId = Objects.requireNonNullElse(requestId, "");
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be strictly positive (>= 1)");
        }
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int count() {
        return items.size();
    }
}

