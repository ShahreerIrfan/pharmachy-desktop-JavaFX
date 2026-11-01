package com.pharmacy.pos.security;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-0076: JavaFX Desktop POS System Announcements & Read Receipts Client.
 */
public final class PosAnnouncementClient {

    private static final Pattern ANNOUNCEMENT_ID_PATTERN = Pattern.compile("^ann-[a-z0-9_-]{8,64}$");

    private static final Set<String> PLATFORM_ADMIN_ROLES = Set.of(
            "SUPERADMIN",
            "SUPER_ADMIN",
            "PLATFORM_OPERATOR"
    );

    private static final Set<String> VALID_PRIORITIES = Set.of(
            "INFO",
            "WARNING",
            "CRITICAL",
            "MAINTENANCE"
    );

    private static final Set<String> VALID_TARGET_TYPES = Set.of(
            "ALL",
            "TENANT_LIST",
            "ROLE_LIST",
            "BRANCH_LIST"
    );

    private PosAnnouncementClient() {
        // Utility class
    }

    public static boolean canRoleManageAnnouncements(String role) {
        if (role == null) return false;
        return PLATFORM_ADMIN_ROLES.contains(role.toUpperCase().trim());
    }

    public static boolean canRoleAcknowledge(String role) {
        return role != null && !role.isBlank();
    }

    public static boolean isValidAnnouncementId(String announcementId) {
        if (announcementId == null) return false;
        return ANNOUNCEMENT_ID_PATTERN.matcher(announcementId.trim().toLowerCase()).matches();
    }

    public static boolean isValidPriority(String priority) {
        if (priority == null) return false;
        return VALID_PRIORITIES.contains(priority.toUpperCase().trim());
    }

    public static boolean isValidTargetType(String targetType) {
        if (targetType == null) return false;
        return VALID_TARGET_TYPES.contains(targetType.toUpperCase().trim());
    }

    public static boolean isValidStateTransition(String fromStatus, String toStatus) {
        if (fromStatus == null || toStatus == null) return false;
        String from = fromStatus.toUpperCase().trim();
        String to = toStatus.toUpperCase().trim();
        if (Objects.equals(from, to)) return true;

        return switch (from) {
            case "DRAFT" -> Set.of("PUBLISHED", "ARCHIVED").contains(to);
            case "PUBLISHED" -> "ARCHIVED".equals(to);
            default -> false; // ARCHIVED is terminal
        };
    }

    public static String buildPlatformAnnouncementsUrl(String baseUrl, String announcementId) {
        String cleanBase = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        if (announcementId == null || announcementId.isBlank()) {
            return cleanBase + "/api/v2/platform/announcements/";
        }
        if (!isValidAnnouncementId(announcementId)) {
            throw new IllegalArgumentException("Invalid announcementId: " + announcementId);
        }
        return cleanBase + "/api/v2/platform/announcements/" + announcementId.trim() + "/";
    }

    public static String buildTenantAnnouncementsUrl(String baseUrl) {
        String cleanBase = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        return cleanBase + "/api/v2/tenants/announcements/";
    }

    public static String buildAcknowledgeUrl(String baseUrl, String announcementId) {
        String cleanBase = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        if (!isValidAnnouncementId(announcementId)) {
            throw new IllegalArgumentException("Invalid announcementId: " + announcementId);
        }
        return cleanBase + "/api/v2/tenants/announcements/" + announcementId.trim() + "/acknowledge/";
    }
}
