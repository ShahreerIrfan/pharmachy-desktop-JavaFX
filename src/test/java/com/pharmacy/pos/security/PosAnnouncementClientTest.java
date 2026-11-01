package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0076: JavaFX Desktop POS System Announcements Client Tests")
class PosAnnouncementClientTest {

    @Test
    @DisplayName("Role RBAC: Only SuperAdmin and Platform Operator can manage platform announcements")
    void testPlatformAdminRbac() {
        assertTrue(PosAnnouncementClient.canRoleManageAnnouncements("SUPERADMIN"));
        assertTrue(PosAnnouncementClient.canRoleManageAnnouncements("SUPER_ADMIN"));
        assertTrue(PosAnnouncementClient.canRoleManageAnnouncements("PLATFORM_OPERATOR"));

        assertFalse(PosAnnouncementClient.canRoleManageAnnouncements("PHARMACY_OWNER"));
        assertFalse(PosAnnouncementClient.canRoleManageAnnouncements("BRANCH_MANAGER"));
        assertFalse(PosAnnouncementClient.canRoleManageAnnouncements("CASHIER"));
        assertFalse(PosAnnouncementClient.canRoleManageAnnouncements(null));
    }

    @Test
    @DisplayName("Role RBAC: Authenticated store staff can acknowledge announcements")
    void testAcknowledgeRbac() {
        assertTrue(PosAnnouncementClient.canRoleAcknowledge("PHARMACY_OWNER"));
        assertTrue(PosAnnouncementClient.canRoleAcknowledge("BRANCH_MANAGER"));
        assertTrue(PosAnnouncementClient.canRoleAcknowledge("CASHIER"));
        assertTrue(PosAnnouncementClient.canRoleAcknowledge("STAFF_PHARMACIST"));

        assertFalse(PosAnnouncementClient.canRoleAcknowledge(null));
        assertFalse(PosAnnouncementClient.canRoleAcknowledge("   "));
    }

    @Test
    @DisplayName("Announcement ID syntax: Must match ann- prefix and 8-64 alphanumeric chars")
    void testAnnouncementIdValidation() {
        assertTrue(PosAnnouncementClient.isValidAnnouncementId("ann-a1b2c3d4e5f6"));
        assertTrue(PosAnnouncementClient.isValidAnnouncementId("ann-scheduled-maint-01"));

        assertFalse(PosAnnouncementClient.isValidAnnouncementId("invalid-prefix-12345"));
        assertFalse(PosAnnouncementClient.isValidAnnouncementId("ann-short"));
        assertFalse(PosAnnouncementClient.isValidAnnouncementId(null));
    }

    @Test
    @DisplayName("Priority and Target Type validation")
    void testPriorityAndTargetValidation() {
        assertTrue(PosAnnouncementClient.isValidPriority("INFO"));
        assertTrue(PosAnnouncementClient.isValidPriority("WARNING"));
        assertTrue(PosAnnouncementClient.isValidPriority("CRITICAL"));
        assertTrue(PosAnnouncementClient.isValidPriority("MAINTENANCE"));
        assertFalse(PosAnnouncementClient.isValidPriority("UNKNOWN"));

        assertTrue(PosAnnouncementClient.isValidTargetType("ALL"));
        assertTrue(PosAnnouncementClient.isValidTargetType("TENANT_LIST"));
        assertTrue(PosAnnouncementClient.isValidTargetType("ROLE_LIST"));
        assertTrue(PosAnnouncementClient.isValidTargetType("BRANCH_LIST"));
        assertFalse(PosAnnouncementClient.isValidTargetType("DEVICE_LIST"));
    }

    @Test
    @DisplayName("Lifecycle State Machine: Strict transitions for announcements")
    void testLifecycleTransitions() {
        assertTrue(PosAnnouncementClient.isValidStateTransition("DRAFT", "PUBLISHED"));
        assertTrue(PosAnnouncementClient.isValidStateTransition("DRAFT", "ARCHIVED"));
        assertTrue(PosAnnouncementClient.isValidStateTransition("PUBLISHED", "ARCHIVED"));

        assertFalse(PosAnnouncementClient.isValidStateTransition("ARCHIVED", "PUBLISHED"));
        assertFalse(PosAnnouncementClient.isValidStateTransition("ARCHIVED", "DRAFT"));
    }

    @Test
    @DisplayName("URL Builders: Platform and Tenant URLs accurately formatted")
    void testUrlBuilders() {
        String listUrl = PosAnnouncementClient.buildPlatformAnnouncementsUrl("https://api.pharmacy.bd", null);
        assertEquals("https://api.pharmacy.bd/api/v2/platform/announcements/", listUrl);

        String detailUrl = PosAnnouncementClient.buildPlatformAnnouncementsUrl("https://api.pharmacy.bd", "ann-a1b2c3d4e5f6");
        assertEquals("https://api.pharmacy.bd/api/v2/platform/announcements/ann-a1b2c3d4e5f6/", detailUrl);

        String tenantFeed = PosAnnouncementClient.buildTenantAnnouncementsUrl("https://api.pharmacy.bd");
        assertEquals("https://api.pharmacy.bd/api/v2/tenants/announcements/", tenantFeed);

        String ackUrl = PosAnnouncementClient.buildAcknowledgeUrl("https://api.pharmacy.bd", "ann-a1b2c3d4e5f6");
        assertEquals("https://api.pharmacy.bd/api/v2/tenants/announcements/ann-a1b2c3d4e5f6/acknowledge/", ackUrl);
    }
}
