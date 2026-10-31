package com.pharmacy.pos.contracts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P047: Desktop POS API Response Conventions Test Suite")
class PosApiResponseEnvelopeTest {

    @Test
    @DisplayName("1. Success paginated envelope correctly encapsulates metadata and request correlation")
    void testSuccessPaginatedEnvelope() {
        PosApiResponseEnvelope.PaginationMeta pagination = new PosApiResponseEnvelope.PaginationMeta(
                1, 20, 48, 3, true, false, 2, null
        );

        PosApiResponseEnvelope<List<String>> envelope = PosApiResponseEnvelope.success(
                List.of("Napa 500mg", "Seclo 20mg"), "PAGINATED_RESULTS"
        )
                .requestId("req-dhk-pos-001")
                .pagination(pagination)
                .filters(Map.of("search", "Napa", "ordering", "brand_name"))
                .messageEn("Retrieved page 1 of 3 (48 total records).")
                .messageBn("পৃষ্ঠা ১/৩ এর তথ্য সফলভাবে লোড হয়েছে (মোট ৪৮ টি রেকর্ড)।")
                .build();

        assertTrue(envelope.isSuccess());
        assertEquals("PAGINATED_RESULTS", envelope.getCode());
        assertEquals("req-dhk-pos-001", envelope.getRequestId());
        assertEquals(2, envelope.getData().size());
        assertNotNull(envelope.getPagination());
        assertEquals(1, envelope.getPagination().page());
        assertEquals(48, envelope.getPagination().totalCount());
        assertEquals(3, envelope.getPagination().totalPages());
        assertTrue(envelope.getPagination().hasNext());
        assertFalse(envelope.getPagination().hasPrevious());
        assertEquals(2, envelope.getPagination().nextPage());
        assertNull(envelope.getPagination().previousPage());
        assertEquals("Napa", envelope.getFilters().get("search"));
        assertEquals(PosApiResponseEnvelope.UiState.SUCCESS, envelope.deriveUiState(false));
    }

    @Test
    @DisplayName("2. Error envelope encapsulates machine error code, field errors, and recovery action")
    void testErrorEnvelope() {
        PosApiResponseEnvelope<Void> errorEnvelope = PosApiResponseEnvelope.<Void>error(
                "ERR_VALIDATION_FAILED", 422
        )
                .requestId("req-err-422")
                .messageEn("One or more input fields failed validation.")
                .messageBn("এক বা একাধিক তথ্যের মান সঠিক নয়।")
                .recoveryAction("CORRECT_INPUT_FIELDS")
                .fieldErrors(Map.of("quantity", List.of("Quantity must be strictly positive (>0).")))
                .build();

        assertFalse(errorEnvelope.isSuccess());
        assertEquals("ERR_VALIDATION_FAILED", errorEnvelope.getErrorCode());
        assertEquals(422, errorEnvelope.getHttpStatus());
        assertFalse(errorEnvelope.isRetryable());
        assertEquals("CORRECT_INPUT_FIELDS", errorEnvelope.getRecoveryAction());
        assertEquals(1, errorEnvelope.getFieldErrors().size());
        assertTrue(errorEnvelope.getFieldErrors().containsKey("quantity"));
        assertEquals(PosApiResponseEnvelope.UiState.ERROR, errorEnvelope.deriveUiState(false));
    }

    @Test
    @DisplayName("3. Bilingual message extraction returns correct English or Bangla translation")
    void testBilingualLocalization() {
        PosApiResponseEnvelope<String> envelope = PosApiResponseEnvelope.success("OK_DATA", "OK")
                .messageEn("Transaction completed.")
                .messageBn("লেনদেন সম্পন্ন হয়েছে।")
                .build();

        assertEquals("Transaction completed.", envelope.getLocalizedMessage("en"));
        assertEquals("লেনদেন সম্পন্ন হয়েছে।", envelope.getLocalizedMessage("bn"));
        assertEquals("Transaction completed.", envelope.getLocalizedMessage("unknown"));
    }

    @Test
    @DisplayName("4. UI state machine accurately derives OFFLINE, PERMISSION_DENIED, and EMPTY states")
    void testDerivedUiStates() {
        // 4a. Offline state takes precedence
        PosApiResponseEnvelope<String> successEnvelope = PosApiResponseEnvelope.success("DATA", "OK").build();
        assertEquals(PosApiResponseEnvelope.UiState.OFFLINE, successEnvelope.deriveUiState(true));

        // 4b. Permission Denied state on 403
        PosApiResponseEnvelope<Void> forbidden = PosApiResponseEnvelope.<Void>error("ERR_PERMISSION_DENIED", 403).build();
        assertEquals(PosApiResponseEnvelope.UiState.PERMISSION_DENIED, forbidden.deriveUiState(false));

        // 4c. Empty state on zero total count
        PosApiResponseEnvelope.PaginationMeta emptyPagination = new PosApiResponseEnvelope.PaginationMeta(
                1, 20, 0, 0, false, false, null, null
        );
        PosApiResponseEnvelope<List<String>> emptyEnvelope = PosApiResponseEnvelope.success(
                List.<String>of(), "PAGINATED_RESULTS"
        ).pagination(emptyPagination).build();
        assertEquals(PosApiResponseEnvelope.UiState.EMPTY, emptyEnvelope.deriveUiState(false));
    }

    @Test
    @DisplayName("5. PaginationMeta rejects invalid bounds with IllegalArgumentException")
    void testPaginationMetaInvalidBounds() {
        assertThrows(IllegalArgumentException.class, () -> new PosApiResponseEnvelope.PaginationMeta(
                0, 20, 10, 1, false, false, null, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new PosApiResponseEnvelope.PaginationMeta(
                1, 0, 10, 1, false, false, null, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new PosApiResponseEnvelope.PaginationMeta(
                1, 20, -1, 1, false, false, null, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new PosApiResponseEnvelope.PaginationMeta(
                1, 20, 10, -1, false, false, null, null
        ));
    }
}
