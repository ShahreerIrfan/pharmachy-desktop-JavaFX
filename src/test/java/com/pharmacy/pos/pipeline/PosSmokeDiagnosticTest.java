package com.pharmacy.pos.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosSmokeDiagnosticTest
 * Architecture Decision: ADR-0040
 * Unit test for PosSmokeDiagnostic probe.
 */
class PosSmokeDiagnosticTest {

    @Test
    @DisplayName("PosSmokeDiagnostic returns healthy result with system telemetry")
    void testPosSmokeDiagnosticHealth() {
        PosSmokeDiagnostic diagnostic = new PosSmokeDiagnostic();
        PosSmokeDiagnostic.DiagnosticResult result = diagnostic.runDiagnostic();

        assertNotNull(result, "Diagnostic result must not be null");
        assertTrue(result.isHealthy, "Diagnostic should report healthy on active JVM");
        assertNotNull(result.javaVersion, "Java version must not be null");
        assertFalse(result.javaVersion.isEmpty(), "Java version must not be empty");
        assertTrue(result.maxMemoryBytes > 0, "Max memory must be greater than zero");
        assertNotNull(result.operatingSystem, "Operating system must be detected");
        assertNotNull(result.timestamp, "Timestamp must be populated");
        assertEquals("DESKTOP_POS", result.details.get("component"));
    }
}
