package com.pharmacy.pos.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P038: Desktop POS Client Version & Compatibility Test Suite")
public class ClientVersionServiceTest {

    @Test
    @DisplayName("SemVer 2.0.0 Parsing and Comparison")
    void testSemVerParsingAndComparison() {
        ClientVersionService.SemVer v1 = ClientVersionService.SemVer.parse("1.0.0");
        ClientVersionService.SemVer v2 = ClientVersionService.SemVer.parse("1.2.0");
        ClientVersionService.SemVer v3 = ClientVersionService.SemVer.parse("2.0.0-rc.1");

        assertTrue(v1.compareTo(v2) < 0, "1.0.0 must be less than 1.2.0");
        assertTrue(v2.compareTo(v1) > 0, "1.2.0 must be greater than 1.0.0");
        assertTrue(v2.compareTo(v3) < 0, "1.2.0 must be less than 2.0.0-rc.1");
        assertEquals(v2, ClientVersionService.SemVer.parse("v1.2.0"), "Leading 'v' should be trimmed cleanly");
    }

    @Test
    @DisplayName("Local Compatibility Evaluation: Supported, Deprecated, and Upgrade Required")
    void testCompatibilityEvaluation() {
        // Supported scenario
        var res1 = ClientVersionService.evaluateLocal("1.2.0", "1.0.0", "1.2.0");
        assertEquals(ClientVersionService.CompatibilityStatus.SUPPORTED, res1.status);
        assertFalse(res1.isUpgradeRequired);
        assertTrue(res1.messageBn.contains("সম্পূর্ণ সমর্থিত"));

        // Deprecated scenario (in grace period)
        var res2 = ClientVersionService.evaluateLocal("1.1.0", "1.0.0", "1.2.0");
        assertEquals(ClientVersionService.CompatibilityStatus.DEPRECATED, res2.status);
        assertFalse(res2.isUpgradeRequired);
        assertTrue(res2.messageEn.contains("deprecated"));

        // Upgrade Required scenario (below min supported)
        var res3 = ClientVersionService.evaluateLocal("0.9.0", "1.0.0", "1.2.0");
        assertEquals(ClientVersionService.CompatibilityStatus.UPGRADE_REQUIRED, res3.status);
        assertTrue(res3.isUpgradeRequired);
        assertTrue(res3.messageBn.contains("আপগ্রেড করা আবশ্যক"));
    }

    @Test
    @DisplayName("Malformed SemVer Throws IllegalArgumentException")
    void testMalformedSemVerThrows() {
        assertThrows(IllegalArgumentException.class, () -> ClientVersionService.SemVer.parse("not-a-semver"));
        assertThrows(IllegalArgumentException.class, () -> ClientVersionService.SemVer.parse("1.2"));
        assertThrows(IllegalArgumentException.class, () -> ClientVersionService.SemVer.parse(null));
    }
}
