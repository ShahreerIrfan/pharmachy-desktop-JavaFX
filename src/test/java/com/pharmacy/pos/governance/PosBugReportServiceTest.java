package com.pharmacy.pos.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P039: Desktop POS Bug Report & Governance Test Suite")
public class PosBugReportServiceTest {

    @Test
    @DisplayName("Valid Bug Report Construction with Complete Reproduction Details")
    void testValidBugReportConstruction() {
        var report = PosBugReportService.createReport(
            "tenant-dhaka-central",
            "Barcode scanner timeout on cold-chain biologicals",
            "HIGH",
            "1. Scan item 0123456789. 2. Await scanner response. 3. Cart does not register.",
            "Item added to cart with FEFO batch selected",
            "Barcode reader hangs without error alert",
            3
        );

        assertNotNull(report);
        assertEquals("tenant-dhaka-central", report.tenantId);
        assertEquals("HIGH", report.severity);
        assertEquals(3, report.pendingOfflineOutboxCount);

        Map<String, Object> map = report.toMap();
        assertEquals("BUG", map.get("category"));
        assertEquals("DESKTOP", map.get("client_platform"));
        assertEquals("OFFLINE", map.get("connectivity_state"));
        assertTrue(((String) map.get("sanitized_logs")).contains("Java:"));
    }

    @Test
    @DisplayName("Incomplete Reproduction Steps Throws IllegalArgumentException")
    void testIncompleteReproductionThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            PosBugReportService.createReport(
                "tenant-dhaka-central",
                "Barcode failure",
                "MEDIUM",
                "Broken", // Less than 10 characters
                "Should work",
                "Does not work",
                0
            )
        );
    }
}
