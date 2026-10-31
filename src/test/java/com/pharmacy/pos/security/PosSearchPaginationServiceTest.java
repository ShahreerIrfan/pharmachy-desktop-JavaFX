package com.pharmacy.pos.security;

import com.pharmacy.pos.contracts.PosApiResponseEnvelope.UiState;
import com.pharmacy.pos.models.PosCursorPage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("P053: Desktop POS Safe Pagination & Unicode Search Tests (ADR-0053)")
public class PosSearchPaginationServiceTest {

    @Test
    @DisplayName("1. Bounded Page Sizes: clamps oversized sizes and rejects non-positive bounds")
    void testBoundedPageSize() {
        assertEquals(20, PosSearchPaginationService.boundPageSize(20));
        assertEquals(1, PosSearchPaginationService.boundPageSize(1));
        assertEquals(100, PosSearchPaginationService.boundPageSize(100));

        // Oversized page size clamped to 100 max
        assertEquals(100, PosSearchPaginationService.boundPageSize(500));
        assertEquals(100, PosSearchPaginationService.boundPageSize(1000000));

        // Zero and negative bounds throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> PosSearchPaginationService.boundPageSize(0));
        assertThrows(IllegalArgumentException.class, () -> PosSearchPaginationService.boundPageSize(-5));
    }

    @Test
    @DisplayName("2. Unicode Search Normalization: strips zero-width characters and normalizes Bengali/English text")
    void testUnicodeSearchNormalization() {
        // Bengali text normalization
        String rawBn = "  নাপা\u200C এক্সট্রা  ";
        String normalizedBn = PosSearchPaginationService.normalizeSearchTerm(rawBn);
        assertEquals("নাপা এক্সট্রা", normalizedBn);

        // English text normalization
        String rawEn = "  Napa Extra 500mg  ";
        String normalizedEn = PosSearchPaginationService.normalizeSearchTerm(rawEn);
        assertEquals("napa extra 500mg", normalizedEn);

        // Null / blank safety
        assertEquals("", PosSearchPaginationService.normalizeSearchTerm(null));
        assertEquals("", PosSearchPaginationService.normalizeSearchTerm("   "));
    }

    @Test
    @DisplayName("3. Filter Parameter Whitelist: permits whitelisted keys and rejects ORM lookups and probes")
    void testFilterParameterValidation() {
        Set<String> allowedFields = Set.of("brand_name", "generic_name", "manufacturer", "search", "cursor", "page_size");

        // Valid whitelisted parameters
        Map<String, String> validParams = Map.of(
                "brand_name", "Napa",
                "search", "নাপা",
                "page_size", "20"
        );
        assertTrue(PosSearchPaginationService.validateFilterParameters(validParams, allowedFields));

        // Prohibited ORM lookup probe (__)
        Map<String, String> ormProbe = Map.of("tenant__secret_key", "compromised");
        IllegalArgumentException exOrm = assertThrows(IllegalArgumentException.class,
                () -> PosSearchPaginationService.validateFilterParameters(ormProbe, allowedFields));
        assertTrue(exOrm.getMessage().contains("Direct ORM lookup probe"));

        // Sensitive keyword probe (password)
        Map<String, String> secretProbe = Map.of("user_password", "leak_test");
        IllegalArgumentException exSecret = assertThrows(IllegalArgumentException.class,
                () -> PosSearchPaginationService.validateFilterParameters(secretProbe, allowedFields));
        assertTrue(exSecret.getMessage().contains("sensitive attribute"));

        // Unwhitelisted arbitrary parameter
        Map<String, String> unwhitelisted = Map.of("unsupported_bogus_field", "val");
        IllegalArgumentException exUnauth = assertThrows(IllegalArgumentException.class,
                () -> PosSearchPaginationService.validateFilterParameters(unwhitelisted, allowedFields));
        assertTrue(exUnauth.getMessage().contains("Unrecognized or forbidden"));
    }

    @Test
    @DisplayName("4. Tenant Boundary Protection: matching tenant succeeds; cross-tenant query is strictly blocked")
    void testTenantBoundaryVerification() {
        String activeTenant = "tenant-dhanmondi-care";

        // Matching tenant succeeds
        assertTrue(PosSearchPaginationService.verifyTenantBoundary(activeTenant, "tenant-dhanmondi-care"));
        assertTrue(PosSearchPaginationService.verifyTenantBoundary(activeTenant, null));
        assertTrue(PosSearchPaginationService.verifyTenantBoundary(activeTenant, "   "));

        // Foreign tenant query strictly blocked
        SecurityException secEx = assertThrows(SecurityException.class,
                () -> PosSearchPaginationService.verifyTenantBoundary(activeTenant, "tenant-uttara-pharma"));
        assertTrue(secEx.getMessage().contains("ERR_CROSS_TENANT_VIOLATION"));
    }

    @Test
    @DisplayName("5. Cursor Envelope Parsing & Bilingual Error Localization")
    void testCursorEnvelopeParsingAndBilingualMessages() {
        Map<String, Object> envelope = Map.of(
                "success", true,
                "code", "CURSOR_PAGINATED_RESULTS",
                "request_id", "req-cursor-test-01",
                "data", List.of(
                        Map.of("product_id", "PROD-001", "brand_name", "Napa 500mg"),
                        Map.of("product_id", "PROD-002", "brand_name", "Ace 500mg")
                ),
                "meta", Map.of(
                        "pagination", Map.of(
                                "type", "cursor",
                                "page_size", 20,
                                "has_next", true,
                                "has_previous", false,
                                "next_cursor", "token_next_page_123"
                        ),
                        "filters", Map.of("search", "নাপা")
                )
        );

        PosCursorPage<Map<String, Object>> page = PosSearchPaginationService.parseCursorPage(envelope);
        assertEquals(2, page.count());
        assertFalse(page.isEmpty());
        assertTrue(page.hasNext());
        assertFalse(page.hasPrevious());
        assertEquals("token_next_page_123", page.nextCursor());
        assertNull(page.previousCursor());
        assertEquals("req-cursor-test-01", page.requestId());
        assertEquals("নাপা", page.activeFilters().get("search"));

        // Bilingual error translation
        Map<String, String> invalidCursorMsg = PosSearchPaginationService.getBilingualErrorMessage("ERR_INVALID_CURSOR");
        assertTrue(invalidCursorMsg.get("en").contains("invalid, expired, or malformed"));
        assertTrue(invalidCursorMsg.get("bn").contains("কার্সারটি অবৈধ"));

        Map<String, String> crossTenantMsg = PosSearchPaginationService.getBilingualErrorMessage("ERR_CROSS_TENANT_VIOLATION");
        assertTrue(crossTenantMsg.get("en").contains("Cross-tenant"));
        assertTrue(crossTenantMsg.get("bn").contains("অনুসন্ধান কঠোরভাবে নিষিদ্ধ"));

        // UI state derivation
        assertEquals(UiState.PERMISSION_DENIED, PosSearchPaginationService.resolveUiState("ERR_CROSS_TENANT_VIOLATION"));
        assertEquals(UiState.ERROR, PosSearchPaginationService.resolveUiState("ERR_INVALID_CURSOR"));
    }
}

