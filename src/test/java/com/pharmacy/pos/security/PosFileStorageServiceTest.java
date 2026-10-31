package com.pharmacy.pos.security;

import static org.junit.jupiter.api.Assertions.*;

import com.pharmacy.pos.contracts.PosApiResponseEnvelope.UiState;
import com.pharmacy.pos.models.PosStoredAttachment;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("P051: Desktop POS Private File Storage & Antivirus Policy Tests")
public class PosFileStorageServiceTest {

    @Test
    @DisplayName("1. Client pre-upload validation approves valid medical images/docs and rejects executables")
    void testPreUploadValidation() {
        assertTrue(PosFileStorageService.validateUpload("prescription_scan.jpg", 1024 * 500));
        assertTrue(PosFileStorageService.validateUpload("invoice_2026.pdf", 1024 * 1024));
        assertTrue(PosFileStorageService.validateUpload("dgda_license.png", 1024 * 800));

        // Rejects dangerous executables and scripts
        assertFalse(PosFileStorageService.validateUpload("payload.exe", 1024));
        assertFalse(PosFileStorageService.validateUpload("install.bat", 512));
        assertFalse(PosFileStorageService.validateUpload("script.ps1", 2048));
        assertFalse(PosFileStorageService.validateUpload("shell.php", 1024));

        // Rejects oversized or zero-byte files
        assertFalse(PosFileStorageService.validateUpload("large_file.pdf", 20 * 1024 * 1024));
        assertFalse(PosFileStorageService.validateUpload("empty.pdf", 0));
        assertFalse(PosFileStorageService.validateUpload("", 1024));
    }

    @Test
    @DisplayName("2. Scoped Category RBAC enforces Zero-PHI protection against Cashier and SuperAdmin")
    void testScopedCategoryRbac() {
        // Prescription Category
        assertTrue(PosFileStorageService.isAuthorizedForCategory("PHARMACY_OWNER", "PRESCRIPTION_IMAGE"));
        assertTrue(PosFileStorageService.isAuthorizedForCategory("BRANCH_MANAGER", "PRESCRIPTION_IMAGE"));
        assertTrue(PosFileStorageService.isAuthorizedForCategory("STAFF_PHARMACIST", "PRESCRIPTION_IMAGE"));
        assertTrue(PosFileStorageService.isAuthorizedForCategory("AUDITOR", "PRESCRIPTION_IMAGE"));

        // Zero-PHI enforcement: Cashier & SuperAdmin blocked from prescriptions
        assertFalse(PosFileStorageService.isAuthorizedForCategory("CASHIER", "PRESCRIPTION_IMAGE"));
        assertFalse(PosFileStorageService.isAuthorizedForCategory("SUPERADMIN", "PRESCRIPTION_IMAGE"));
        assertFalse(PosFileStorageService.isAuthorizedForCategory("PLATFORM_OPERATOR", "PRESCRIPTION_IMAGE"));

        // Sales Invoices
        assertTrue(PosFileStorageService.isAuthorizedForCategory("CASHIER", "SALES_INVOICE_PDF"));
        assertTrue(PosFileStorageService.isAuthorizedForCategory("FINANCE_OFFICER", "SALES_INVOICE_PDF"));
    }

    @Test
    @DisplayName("3. Expired signed download URLs are accurately identified")
    void testUrlExpirationEvaluation() {
        String pastExpiry = Instant.now().minus(5, ChronoUnit.MINUTES).toString();
        String futureExpiry = Instant.now().plus(15, ChronoUnit.MINUTES).toString();

        assertTrue(PosFileStorageService.isUrlExpired(pastExpiry), "Past expiry must be evaluated as expired");
        assertFalse(PosFileStorageService.isUrlExpired(futureExpiry), "Future expiry must be evaluated as valid");
        assertTrue(PosFileStorageService.isUrlExpired(null), "Null expiry must be treated as expired");
        assertTrue(PosFileStorageService.isUrlExpired("invalid-date-string"), "Malformed expiry must be expired");
    }

    @Test
    @DisplayName("4. Server response payload parses into PosStoredAttachment DTO")
    void testParseStoredAttachment() {
        Map<String, Object> map = new HashMap<>();
        map.put("file_id", "550e8400-e29b-41d4-a716-446655440000");
        map.put("tenant_id", "tenant-dhanmondi-test");
        map.put("branch_id", "b-01");
        map.put("file_name", "rx-slip.pdf");
        map.put("category", "PRESCRIPTION_IMAGE");
        map.put("mime_type", "application/pdf");
        map.put("file_size_bytes", 154200L);
        map.put("checksum_sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        map.put("scan_status", "CLEAN");
        map.put("is_quarantined", false);
        map.put("signed_download_url", "https://api.pharmacy.bd/storage/download/123/?token=abc");
        map.put("expires_at", "2026-10-31T15:00:00Z");
        map.put("created_at", "2026-10-31T14:00:00Z");

        PosStoredAttachment attachment = PosFileStorageService.parseStoredAttachment(map);
        assertNotNull(attachment);
        assertEquals("rx-slip.pdf", attachment.fileName());
        assertEquals("application/pdf", attachment.mimeType());
        assertTrue(attachment.isClean());
        assertFalse(attachment.isQuarantined());
    }

    @Test
    @DisplayName("5. Error codes map to expected UiState and bilingual messages")
    void testErrorStateAndLocalization() {
        assertEquals(UiState.PERMISSION_DENIED, PosFileStorageService.resolveUiState("ERR_PERMISSION_DENIED"));
        assertEquals(UiState.PERMISSION_DENIED, PosFileStorageService.resolveUiState("ERR_CROSS_TENANT_VIOLATION"));
        assertEquals(UiState.PERMISSION_DENIED, PosFileStorageService.resolveUiState("ERR_FILE_QUARANTINED"));
        assertEquals(UiState.ERROR, PosFileStorageService.resolveUiState("ERR_STORAGE_URL_EXPIRED"));

        Map<String, String> msgQuarantine = PosFileStorageService.getBilingualErrorMessage("ERR_FILE_QUARANTINED");
        assertTrue(msgQuarantine.get("en").contains("Antivirus policy"));
        assertTrue(msgQuarantine.get("bn").contains("কোয়ারেন্টাইন"));

        Map<String, String> msgExpired = PosFileStorageService.getBilingualErrorMessage("ERR_STORAGE_URL_EXPIRED");
        assertTrue(msgExpired.get("en").contains("expired"));
        assertTrue(msgExpired.get("bn").contains("মেয়াদ শেষ"));
    }
}

