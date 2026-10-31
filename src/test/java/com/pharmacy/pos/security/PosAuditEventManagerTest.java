package com.pharmacy.pos.security;

import com.pharmacy.pos.contracts.PosApiResponseEnvelope.UiState;
import com.pharmacy.pos.models.PosAuditEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P050: Desktop POS Audit Event Foundation & Immutability Tests")
class PosAuditEventManagerTest {

    @Test
    @DisplayName("1. Constructs local immutable audit event with 730-day protected retention")
    void testCreateEventWithProtectedRetention() {
        Map<String, Object> before = Map.of("status", "DRAFT", "total", "100.00");
        Map<String, Object> after = Map.of("status", "FINALIZED", "total", "100.00");

        PosAuditEvent event = PosAuditEventManager.createEvent(
                "tenant-dhanmondi-care",
                "b-dhanmondi-01",
                "usr-cashier-01",
                "CASHIER",
                "terminal-pos-01",
                "SALE_CHECKOUT",
                "SALE_INVOICE",
                "INV-2026-001",
                "SUCCESS",
                before,
                after
        );

        assertNotNull(event);
        assertNotNull(event.entryId());
        assertNotNull(event.timestamp());
        assertEquals("tenant-dhanmondi-care", event.tenantId());
        assertEquals("CASHIER", event.actorRole());
        assertEquals("SALE_CHECKOUT", event.action());
        assertEquals("SALE_INVOICE", event.resourceType());
        assertEquals("DRAFT", event.beforeState().get("status"));
        assertEquals("FINALIZED", event.afterState().get("status"));
        assertNotNull(event.retentionUntil());
        assertTrue(event.isImmutable());
    }

    @Test
    @DisplayName("2. Parses server JSON payload into PosAuditEvent accurately")
    void testParseServerEvent() {
        Map<String, Object> map = new HashMap<>();
        map.put("entry_id", "40f5dc9e-7bd0-429a-9e1e-28956b64d1f5");
        map.put("timestamp", "2026-10-31T08:00:00Z");
        map.put("tenant_id", "tenant-dhanmondi-care");
        map.put("branch_id", "b-dhanmondi-01");
        map.put("user_id", "usr-123");
        map.put("actor_role", "PHARMACY_OWNER");
        map.put("device_id", "dev-manager-01");
        map.put("action", "PRICE_OVERRIDE");
        map.put("resource_type", "MEDICINE_PRODUCT");
        map.put("resource_id", "MED-001");
        map.put("status", "SUCCESS");
        map.put("before_state", Map.of("mrp_unit", "120.00"));
        map.put("after_state", Map.of("mrp_unit", "110.00"));
        map.put("retention_until", "2028-10-31T08:00:00Z");
        map.put("is_immutable", true);

        PosAuditEvent event = PosAuditEventManager.parseEvent(map);

        assertNotNull(event);
        assertEquals("40f5dc9e-7bd0-429a-9e1e-28956b64d1f5", event.entryId());
        assertEquals("PRICE_OVERRIDE", event.action());
        assertEquals("120.00", event.beforeState().get("mrp_unit"));
        assertEquals("110.00", event.afterState().get("mrp_unit"));
        assertTrue(event.isImmutable());
    }

    @Test
    @DisplayName("3. Validates event integrity: valid event passes, empty action/tenant fails")
    void testValidateEventIntegrity() {
        PosAuditEvent validEvent = PosAuditEventManager.createEvent(
                "tenant-dhanmondi-care",
                "b-dhanmondi-01",
                "usr-1",
                "STAFF_PHARMACIST",
                "pos-1",
                "DISPENSE_ANTIBIOTIC",
                "PRESCRIPTION",
                "RX-101",
                "SUCCESS",
                Map.of(),
                Map.of()
        );
        assertTrue(PosAuditEventManager.validateEventIntegrity(validEvent));
        assertFalse(PosAuditEventManager.validateEventIntegrity(null));
    }

    @Test
    @DisplayName("4. Bilingual error message resolution for immutability and permission violations")
    void testBilingualErrorMessageResolution() {
        Map<String, String> immutabilityMsg = PosAuditEventManager.getBilingualErrorMessage("ERR_AUDIT_IMMUTABLE");
        assertNotNull(immutabilityMsg);
        assertTrue(immutabilityMsg.get("en").contains("immutable and append-only"));
        assertTrue(immutabilityMsg.get("bn").contains("অডিট লগ রেকর্ড অপরিবর্তনযোগ্য"));

        Map<String, String> permMsg = PosAuditEventManager.getBilingualErrorMessage("ERR_PERMISSION_DENIED");
        assertNotNull(permMsg);
        assertTrue(permMsg.get("en").contains("Manager or Owner privileges"));
        assertTrue(permMsg.get("bn").contains("ম্যানেজার বা মালিকের অনুমতি"));
    }

    @Test
    @DisplayName("5. Maps immutability and authorization errors to UiState.PERMISSION_DENIED")
    void testResolveUiState() {
        assertEquals(UiState.PERMISSION_DENIED, PosAuditEventManager.resolveUiState("ERR_AUDIT_IMMUTABLE"));
        assertEquals(UiState.PERMISSION_DENIED, PosAuditEventManager.resolveUiState("ERR_CROSS_TENANT_VIOLATION"));
        assertEquals(UiState.PERMISSION_DENIED, PosAuditEventManager.resolveUiState("ERR_PERMISSION_DENIED"));
        assertEquals(UiState.ERROR, PosAuditEventManager.resolveUiState("ERR_NETWORK"));
    }
}
