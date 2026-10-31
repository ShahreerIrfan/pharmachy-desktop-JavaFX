package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0069: JavaFX Desktop POS Support Ticket Client Tests")
class PosSupportTicketClientTest {

    @Test
    @DisplayName("Resolves ticket status tone accurately")
    void testResolveStatusTone() {
        assertEquals("info", PosSupportTicketClient.resolveStatusTone("OPEN"));
        assertEquals("warning", PosSupportTicketClient.resolveStatusTone("IN_PROGRESS"));
        assertEquals("success", PosSupportTicketClient.resolveStatusTone("RESOLVED"));
        assertEquals("neutral", PosSupportTicketClient.resolveStatusTone("WAITING_ON_TENANT"));
        assertEquals("neutral", PosSupportTicketClient.resolveStatusTone("CLOSED"));
        assertEquals("neutral", PosSupportTicketClient.resolveStatusTone(null));
        assertEquals("neutral", PosSupportTicketClient.resolveStatusTone("UNKNOWN"));
    }

    @Test
    @DisplayName("Detects internal notes in ticket payload for store confidentiality audit")
    void testDetectInternalNotes() {
        Map<String, Object> cleanTicket = Map.of(
                "ticket_id", "tkt-001",
                "messages", List.of(
                        Map.of("message_id", "msg-1", "is_internal", false, "message_text", "Public reply")
                )
        );
        assertFalse(PosSupportTicketClient.containsInternalNotes(cleanTicket));

        Map<String, Object> contaminatedTicket = Map.of(
                "ticket_id", "tkt-001",
                "messages", List.of(
                        Map.of("message_id", "msg-1", "is_internal", false, "message_text", "Public reply"),
                        Map.of("message_id", "msg-2", "is_internal", true, "message_text", "Private support note")
                )
        );
        assertTrue(PosSupportTicketClient.containsInternalNotes(contaminatedTicket));

        assertFalse(PosSupportTicketClient.containsInternalNotes(null));
    }

    @Test
    @DisplayName("Validates attachment file size limit (<= 10MB)")
    void testAttachmentSizeValidation() {
        assertTrue(PosSupportTicketClient.isValidAttachmentSize(1024)); // 1KB
        assertTrue(PosSupportTicketClient.isValidAttachmentSize(5 * 1024 * 1024)); // 5MB
        assertTrue(PosSupportTicketClient.isValidAttachmentSize(10 * 1024 * 1024)); // Exactly 10MB

        assertFalse(PosSupportTicketClient.isValidAttachmentSize(0)); // 0 bytes
        assertFalse(PosSupportTicketClient.isValidAttachmentSize(-10)); // Negative
        assertFalse(PosSupportTicketClient.isValidAttachmentSize(11 * 1024 * 1024)); // 11MB exceeds limit
    }

    @Test
    @DisplayName("Validates authorized categories and priorities")
    void testCategoriesAndPriorities() {
        assertTrue(PosSupportTicketClient.isValidCategory("BILLING"));
        assertTrue(PosSupportTicketClient.isValidCategory("HARDWARE_PRINTER"));
        assertTrue(PosSupportTicketClient.isValidCategory("SYNC_OFFLINE"));
        assertFalse(PosSupportTicketClient.isValidCategory("INVALID_CAT"));
        assertFalse(PosSupportTicketClient.isValidCategory(null));

        assertTrue(PosSupportTicketClient.isValidPriority("LOW"));
        assertTrue(PosSupportTicketClient.isValidPriority("MEDIUM"));
        assertTrue(PosSupportTicketClient.isValidPriority("HIGH"));
        assertTrue(PosSupportTicketClient.isValidPriority("URGENT"));
        assertFalse(PosSupportTicketClient.isValidPriority("CRITICAL_BLOCKER"));
        assertFalse(PosSupportTicketClient.isValidPriority(null));
    }

    @Test
    @DisplayName("Builds endpoint URLs with parameter validation")
    void testBuildUrls() {
        assertEquals(
                "/api/v2/tenants/support/tickets/tenant-alpha/",
                PosSupportTicketClient.buildTenantTicketsUrl("tenant-alpha")
        );
        assertEquals(
                "/api/v2/tenants/support/tickets/tenant-alpha/create/",
                PosSupportTicketClient.buildCreateTicketUrl("tenant-alpha")
        );
        assertEquals(
                "/api/v2/tenants/support/tickets/tenant-alpha/tkt-12345/",
                PosSupportTicketClient.buildTicketDetailUrl("tenant-alpha", "tkt-12345")
        );
        assertEquals(
                "/api/v2/tenants/support/tickets/tenant-alpha/tkt-12345/reply/",
                PosSupportTicketClient.buildReplyUrl("tenant-alpha", "tkt-12345")
        );
        assertEquals(
                "/api/v2/tenants/support/tickets/tenant-alpha/tkt-12345/close/",
                PosSupportTicketClient.buildCloseUrl("tenant-alpha", "tkt-12345")
        );

        assertThrows(IllegalArgumentException.class, () ->
                PosSupportTicketClient.buildTenantTicketsUrl("bad id!"));
        assertThrows(IllegalArgumentException.class, () ->
                PosSupportTicketClient.buildTicketDetailUrl("tenant-alpha", ""));
    }
}

