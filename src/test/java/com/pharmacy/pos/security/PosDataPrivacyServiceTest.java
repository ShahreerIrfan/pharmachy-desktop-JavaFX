package com.pharmacy.pos.security;

import static org.junit.jupiter.api.Assertions.*;

import com.pharmacy.pos.contracts.PosApiResponseEnvelope.UiState;
import com.pharmacy.pos.models.PosPrivacyRequest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("P052: Desktop POS Data Privacy & Retention Operations Tests")
public class PosDataPrivacyServiceTest {

    @Test
    @DisplayName("1. Validates DSR input: accepts valid identifiers and rejects malformed inputs")
    void testValidateRequestInput() {
        assertTrue(PosDataPrivacyService.validateRequestInput("PT-99881", "DATA_EXPORT"));
        assertTrue(PosDataPrivacyService.validateRequestInput("+8801711223344", "DATA_CORRECTION"));
        assertTrue(PosDataPrivacyService.validateRequestInput("PT-DEL-001", "DATA_DELETION"));

        // Malformed or short identifier
        assertFalse(PosDataPrivacyService.validateRequestInput("AB", "DATA_EXPORT"));
        assertFalse(PosDataPrivacyService.validateRequestInput("", "DATA_EXPORT"));
        assertFalse(PosDataPrivacyService.validateRequestInput(null, "DATA_EXPORT"));

        // Invalid request type
        assertFalse(PosDataPrivacyService.validateRequestInput("PT-99881", "UNSUPPORTED_TYPE"));
        assertFalse(PosDataPrivacyService.validateRequestInput("PT-99881", ""));
    }

    @Test
    @DisplayName("2. Role authorization allows Owner/Manager/Auditor and denies Cashier")
    void testRoleAuthorization() {
        assertTrue(PosDataPrivacyService.isAuthorizedForPrivacyOperations("PHARMACY_OWNER"));
        assertTrue(PosDataPrivacyService.isAuthorizedForPrivacyOperations("BRANCH_MANAGER"));
        assertTrue(PosDataPrivacyService.isAuthorizedForPrivacyOperations("AUDITOR"));
        assertTrue(PosDataPrivacyService.isAuthorizedForPrivacyOperations("SUPERADMIN"));

        // Cashiers strictly blocked from assessing privacy requests
        assertFalse(PosDataPrivacyService.isAuthorizedForPrivacyOperations("CASHIER"));
        assertFalse(PosDataPrivacyService.isAuthorizedForPrivacyOperations("GUEST"));
        assertFalse(PosDataPrivacyService.isAuthorizedForPrivacyOperations(null));
    }

    @Test
    @DisplayName("3. Statutory retention conflict correctly flags active 730-day prescriptions")
    void testRetentionConflictEvaluation() {
        // Active prescription within 730 days -> conflict active
        assertTrue(PosDataPrivacyService.hasStatutoryRetentionConflict(true, 100));
        assertTrue(PosDataPrivacyService.hasStatutoryRetentionConflict(true, 729));

        // Prescriptions older than 730 days -> no conflict
        assertFalse(PosDataPrivacyService.hasStatutoryRetentionConflict(true, 731));
        assertFalse(PosDataPrivacyService.hasStatutoryRetentionConflict(false, 50));
    }

    @Test
    @DisplayName("4. Server response payload parses into PosPrivacyRequest DTO")
    void testParsePrivacyRequest() {
        Map<String, Object> map = new HashMap<>();
        map.put("request_id", "450e8400-e29b-41d4-a716-446655440001");
        map.put("tenant_id", "tenant-dhanmondi-test");
        map.put("branch_id", "b-01");
        map.put("subject_type", "PATIENT");
        map.put("subject_identifier", "PT-12345");
        map.put("request_type", "DATA_DELETION");
        map.put("status", "ANONYMIZED");
        map.put("reason", "Right to erasure request");
        map.put("retention_conflict_detected", true);
        map.put("retention_horizon_days", 730);
        map.put("export_artifact_url", "");
        map.put("assessed_by_email", "owner@dhanmondi.com");
        map.put("assessed_at", "2026-10-31T14:30:00Z");
        map.put("created_at", "2026-10-31T14:00:00Z");

        PosPrivacyRequest req = PosDataPrivacyService.parsePrivacyRequest(map);
        assertNotNull(req);
        assertEquals("PT-12345", req.subjectIdentifier());
        assertEquals("DATA_DELETION", req.requestType());
        assertEquals("ANONYMIZED", req.status());
        assertTrue(req.hasRetentionHold());
        assertEquals(730, req.retentionHorizonDays());
    }

    @Test
    @DisplayName("5. Error codes map to expected UiState and bilingual messages")
    void testErrorStateAndLocalization() {
        assertEquals(UiState.PERMISSION_DENIED, PosDataPrivacyService.resolveUiState("ERR_PERMISSION_DENIED"));
        assertEquals(UiState.PERMISSION_DENIED, PosDataPrivacyService.resolveUiState("ERR_CROSS_TENANT_VIOLATION"));
        assertEquals(UiState.ERROR, PosDataPrivacyService.resolveUiState("ERR_PRIVACY_RETENTION_CONFLICT"));

        Map<String, String> conflictMsg = PosDataPrivacyService.getBilingualErrorMessage("ERR_PRIVACY_RETENTION_CONFLICT");
        assertTrue(conflictMsg.get("en").contains("statutory retention"));
        assertTrue(conflictMsg.get("bn").contains("রিটেনশন মেয়াদ"));

        Map<String, String> deniedMsg = PosDataPrivacyService.getBilingualErrorMessage("ERR_PERMISSION_DENIED");
        assertTrue(deniedMsg.get("en").contains("Access denied"));
        assertTrue(deniedMsg.get("bn").contains("প্রবেশাধিকার প্রত্যাখ্যাত"));
    }
}
