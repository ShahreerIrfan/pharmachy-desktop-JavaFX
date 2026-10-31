package com.pharmacy.pos.security;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-0069: JavaFX Desktop POS Support Ticket Client.
 * Coordinates in-app ticket creation, discussion threads, status chips,
 * attachment validation, and enforces strict internal note confidentiality.
 */
public final class PosSupportTicketClient {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-z0-9_-]{3,64}$");
    public static final long MAX_ATTACHMENT_SIZE_BYTES = 10 * 1024 * 1024; // 10MB

    private static final Set<String> VALID_CATEGORIES = Set.of(
            "BILLING",
            "HARDWARE_PRINTER",
            "SYNC_OFFLINE",
            "INVENTORY_STOCK",
            "REGULATORY_DGDA",
            "GENERAL_SUPPORT"
    );

    private static final Set<String> VALID_PRIORITIES = Set.of(
            "LOW",
            "MEDIUM",
            "HIGH",
            "URGENT"
    );

    private PosSupportTicketClient() {
        // Utility / boundary client class
    }

    /**
     * Resolves status tone conforming to Apple-inspired restrained UI.
     */
    public static String resolveStatusTone(String status) {
        if (status == null) {
            return "neutral";
        }
        return switch (status.toUpperCase().trim()) {
            case "OPEN" -> "info";
            case "IN_PROGRESS" -> "warning";
            case "RESOLVED" -> "success";
            case "WAITING_ON_TENANT", "CLOSED" -> "neutral";
            default -> "neutral";
        };
    }

    /**
     * Confidentiality Invariant: Audits a ticket payload to ensure internal support notes are strictly absent in POS UI.
     */
    @SuppressWarnings("unchecked")
    public static boolean containsInternalNotes(Map<String, Object> ticketPayload) {
        if (ticketPayload == null) {
            return false;
        }
        Object messagesObj = ticketPayload.get("messages");
        if (messagesObj instanceof List<?> messagesList) {
            for (Object item : messagesList) {
                if (item instanceof Map<?, ?> msgMap) {
                    Object isInternal = msgMap.get("is_internal");
                    if (Boolean.TRUE.equals(isInternal)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Validates whether an attachment file size is within safe bounds (<= 10MB).
     */
    public static boolean isValidAttachmentSize(long sizeBytes) {
        return sizeBytes > 0 && sizeBytes <= MAX_ATTACHMENT_SIZE_BYTES;
    }

    /**
     * Validates category string conforming to ADR-0069.
     */
    public static boolean isValidCategory(String category) {
        if (category == null) {
            return false;
        }
        return VALID_CATEGORIES.contains(category.toUpperCase().trim());
    }

    /**
     * Validates priority string conforming to ADR-0069.
     */
    public static boolean isValidPriority(String priority) {
        if (priority == null) {
            return false;
        }
        return VALID_PRIORITIES.contains(priority.toUpperCase().trim());
    }

    /**
     * Builds tenant tickets list endpoint URL.
     */
    public static String buildTenantTicketsUrl(String tenantId) {
        validateId(tenantId, "tenant");
        return "/api/v2/tenants/support/tickets/" + tenantId.trim() + "/";
    }

    /**
     * Builds ticket submission endpoint URL.
     */
    public static String buildCreateTicketUrl(String tenantId) {
        validateId(tenantId, "tenant");
        return "/api/v2/tenants/support/tickets/" + tenantId.trim() + "/create/";
    }

    /**
     * Builds ticket detail endpoint URL.
     */
    public static String buildTicketDetailUrl(String tenantId, String ticketId) {
        validateId(tenantId, "tenant");
        validateId(ticketId, "ticket");
        return "/api/v2/tenants/support/tickets/" + tenantId.trim() + "/" + ticketId.trim() + "/";
    }

    /**
     * Builds ticket reply endpoint URL.
     */
    public static String buildReplyUrl(String tenantId, String ticketId) {
        validateId(tenantId, "tenant");
        validateId(ticketId, "ticket");
        return "/api/v2/tenants/support/tickets/" + tenantId.trim() + "/" + ticketId.trim() + "/reply/";
    }

    /**
     * Builds ticket close endpoint URL.
     */
    public static String buildCloseUrl(String tenantId, String ticketId) {
        validateId(tenantId, "tenant");
        validateId(ticketId, "ticket");
        return "/api/v2/tenants/support/tickets/" + tenantId.trim() + "/" + ticketId.trim() + "/close/";
    }

    private static void validateId(String id, String fieldName) {
        if (id == null || !IDENTIFIER_PATTERN.matcher(id.trim()).matches()) {
            throw new IllegalArgumentException("Invalid " + fieldName + " identifier: '" + id + "'");
        }
    }
}

