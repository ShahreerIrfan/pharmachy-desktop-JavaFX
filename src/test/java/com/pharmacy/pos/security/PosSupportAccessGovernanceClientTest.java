package com.pharmacy.pos.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0068: Desktop POS Support Access Governance and Impersonation Prevention Tests")
class PosSupportAccessGovernanceClientTest {

    @Test
    @DisplayName("Direct user impersonation is strictly barred by architecture")
    void testDirectUserImpersonationForbidden() {
        assertFalse(PosSupportAccessGovernanceClient.isDirectUserImpersonationPermitted());
    }

    @Test
    @DisplayName("Clinical dispensing is completely forbidden for all support scopes")
    void testClinicalDispensingBarred() {
        assertFalse(PosSupportAccessGovernanceClient.isClinicalDispensingPermitted("DIAGNOSTIC_READ"));
        assertFalse(PosSupportAccessGovernanceClient.isClinicalDispensingPermitted("BILLING_READ"));
        assertFalse(PosSupportAccessGovernanceClient.isClinicalDispensingPermitted("BREAK_GLASS_REPAIR"));
        assertFalse(PosSupportAccessGovernanceClient.isClinicalDispensingPermitted(null));
    }

    @Test
    @DisplayName("Diagnostic inspection is allowed only for diagnostic and break-glass scopes")
    void testDiagnosticInspectionPermitted() {
        assertTrue(PosSupportAccessGovernanceClient.isDiagnosticInspectionPermitted("DIAGNOSTIC_READ"));
        assertTrue(PosSupportAccessGovernanceClient.isDiagnosticInspectionPermitted("BREAK_GLASS_REPAIR"));
        assertFalse(PosSupportAccessGovernanceClient.isDiagnosticInspectionPermitted("BILLING_READ"));
        assertFalse(PosSupportAccessGovernanceClient.isDiagnosticInspectionPermitted("UNKNOWN_SCOPE"));
    }

    @Test
    @DisplayName("Notice banner evaluation and tone resolution")
    void testNoticeBannerEvaluation() {
        Map<String, Object> activeNotice = Map.of(
                "show_banner", true,
                "tone", "warning",
                "seconds_remaining", 1800
        );
        assertTrue(PosSupportAccessGovernanceClient.shouldDisplayNoticeBanner(activeNotice));
        assertEquals("warning", PosSupportAccessGovernanceClient.resolveNoticeTone(activeNotice));

        Map<String, Object> breakGlassNotice = Map.of(
                "show_banner", true,
                "tone", "danger",
                "seconds_remaining", 900
        );
        assertTrue(PosSupportAccessGovernanceClient.shouldDisplayNoticeBanner(breakGlassNotice));
        assertEquals("danger", PosSupportAccessGovernanceClient.resolveNoticeTone(breakGlassNotice));

        Map<String, Object> inactiveNotice = Map.of(
                "show_banner", false,
                "tone", "neutral"
        );
        assertFalse(PosSupportAccessGovernanceClient.shouldDisplayNoticeBanner(inactiveNotice));
        assertEquals("neutral", PosSupportAccessGovernanceClient.resolveNoticeTone(inactiveNotice));

        assertFalse(PosSupportAccessGovernanceClient.shouldDisplayNoticeBanner(null));
        assertEquals("neutral", PosSupportAccessGovernanceClient.resolveNoticeTone(null));
    }

    @Test
    @DisplayName("Builds active notice URL with identifier validation")
    void testBuildActiveNoticeUrl() {
        assertEquals(
                "/api/v2/tenants/support-access/tenant-alpha/active/",
                PosSupportAccessGovernanceClient.buildActiveNoticeUrl("tenant-alpha")
        );

        assertThrows(IllegalArgumentException.class, () ->
                PosSupportAccessGovernanceClient.buildActiveNoticeUrl(""));
        assertThrows(IllegalArgumentException.class, () ->
                PosSupportAccessGovernanceClient.buildActiveNoticeUrl("ab"));
        assertThrows(IllegalArgumentException.class, () ->
                PosSupportAccessGovernanceClient.buildActiveNoticeUrl("invalid tenant!"));
    }

    @Test
    @DisplayName("Builds emergency revocation URL with validation")
    void testBuildRevokeUrl() {
        assertEquals(
                "/api/v2/tenants/support-access/tenant-alpha/sup-12345/revoke/",
                PosSupportAccessGovernanceClient.buildRevokeUrl("tenant-alpha", "sup-12345")
        );

        assertThrows(IllegalArgumentException.class, () ->
                PosSupportAccessGovernanceClient.buildRevokeUrl("tenant-alpha", "bad id!"));
        assertThrows(IllegalArgumentException.class, () ->
                PosSupportAccessGovernanceClient.buildRevokeUrl("bad tenant!", "sup-12345"));
    }

    @Test
    @DisplayName("Validates authorized ADR-0068 support scopes")
    void testValidScopes() {
        assertTrue(PosSupportAccessGovernanceClient.isValidScope("DIAGNOSTIC_READ"));
        assertTrue(PosSupportAccessGovernanceClient.isValidScope("BILLING_READ"));
        assertTrue(PosSupportAccessGovernanceClient.isValidScope("BREAK_GLASS_REPAIR"));
        assertFalse(PosSupportAccessGovernanceClient.isValidScope("FULL_ADMIN"));
        assertFalse(PosSupportAccessGovernanceClient.isValidScope(null));
    }
}
