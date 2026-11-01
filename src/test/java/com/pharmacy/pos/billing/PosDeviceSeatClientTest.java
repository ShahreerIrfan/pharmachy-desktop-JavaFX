package com.pharmacy.pos.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P096: JavaFX Desktop POS Device Seats Management Client Tests")
class PosDeviceSeatClientTest {

    @Test
    @DisplayName("canEnrollDevice accurately detects available capacity")
    void testCanEnrollDevice() {
        assertTrue(PosDeviceSeatClient.canEnrollDevice(0, 2));
        assertTrue(PosDeviceSeatClient.canEnrollDevice(1, 2));
        assertFalse(PosDeviceSeatClient.canEnrollDevice(2, 2));
        assertFalse(PosDeviceSeatClient.canEnrollDevice(3, 2));
    }

    @Test
    @DisplayName("evaluateSummary calculates utilization and bilingual banners for NORMAL, WARNING, LIMIT_REACHED, and OVER_QUOTA")
    void testEvaluateSummary() {
        // NORMAL
        var normal = PosDeviceSeatClient.evaluateSummary("tn-01", "STANDARD", 2, 2, 5);
        assertEquals("NORMAL", normal.status);
        assertTrue(normal.canEnrollMore());
        assertFalse(normal.isAtCapacity());
        assertEquals(3, normal.remaining);
        assertEquals(40.0, normal.utilizationPct);
        assertTrue(normal.headlineBn.contains("ডিভাইস সীট উপলব্ধ"));

        // WARNING (4/5 = 80%)
        var warning = PosDeviceSeatClient.evaluateSummary("tn-01", "STANDARD", 4, 4, 5);
        assertEquals("WARNING", warning.status);
        assertTrue(warning.canEnrollMore());
        assertEquals(1, warning.remaining);
        assertEquals(80.0, warning.utilizationPct);
        assertTrue(warning.headlineBn.contains("ধারণক্ষমতার কাছাকাছি"));

        // LIMIT_REACHED (5/5 = 100%)
        var limit = PosDeviceSeatClient.evaluateSummary("tn-01", "STANDARD", 5, 5, 5);
        assertEquals("LIMIT_REACHED", limit.status);
        assertFalse(limit.canEnrollMore());
        assertTrue(limit.isAtCapacity());
        assertEquals(0, limit.remaining);
        assertEquals(100.0, limit.utilizationPct);
        assertTrue(limit.headlineBn.contains("ডিভাইস সীট পূর্ণ"));

        // OVER_QUOTA (6/5 = 120%)
        var over = PosDeviceSeatClient.evaluateSummary("tn-01", "STANDARD", 6, 6, 5);
        assertEquals("OVER_QUOTA", over.status);
        assertTrue(over.isOverQuota);
        assertFalse(over.canEnrollMore());
        assertTrue(over.headlineBn.contains("কোটা অতিক্রম"));
    }

    @Test
    @DisplayName("DeviceSeatRecord accurately reports active and revoked states")
    void testDeviceSeatRecordStateChecks() {
        var activeRecord = new PosDeviceSeatClient.DeviceSeatRecord(
                "dev-01", "tn-01", "br-01", "Main Branch", "Counter 1", "DESKTOP_POS",
                "ACTIVE", "00:1B:44:11:3A:B7", "192.168.1.100", "fp-123",
                "2026-11-01T10:00:00Z", "owner@pharmacy.local", null, "", null,
                "Active", "সক্রিয়", "Details", "বিবরণ"
        );
        assertTrue(activeRecord.isActive());
        assertFalse(activeRecord.isRevoked());

        var revokedRecord = new PosDeviceSeatClient.DeviceSeatRecord(
                "dev-02", "tn-01", "br-01", "Main Branch", "Counter 2", "DESKTOP_POS",
                "REVOKED", "00:1B:44:11:3A:B8", "192.168.1.101", "fp-456",
                "2026-11-01T10:00:00Z", "owner@pharmacy.local", "2026-11-01T12:00:00Z", "Hardware decommissioned", null,
                "Revoked", "বাতিলকৃত", "Details", "বিবরণ"
        );
        assertFalse(revokedRecord.isActive());
        assertTrue(revokedRecord.isRevoked());
    }
}

