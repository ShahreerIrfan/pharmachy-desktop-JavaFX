package com.pharmacy.pos.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosEnvironmentSettingsDiagnosticTest
 * Architecture Decision: ADR-0041
 * Verifies Desktop POS environment posture evaluation and TLS requirements.
 */
class PosEnvironmentSettingsDiagnosticTest {

    @Test
    @DisplayName("Localhost evaluates to DEVELOPMENT mode without mandatory HTTPS")
    void testDevelopmentPosture() {
        PosEnvironmentSettingsDiagnostic diagnostic = new PosEnvironmentSettingsDiagnostic();
        PosEnvironmentSettingsDiagnostic.EnvironmentPosture posture =
            diagnostic.evaluatePosture("http://localhost:8000/api/v2");

        assertEquals(PosEnvironmentSettingsDiagnostic.EnvironmentMode.DEVELOPMENT, posture.mode);
        assertFalse(posture.requireHttps, "Development does not mandate HTTPS");
        assertTrue(posture.offlineCacheEnabled, "Offline SQLite cache must be active");
    }

    @Test
    @DisplayName("Cloud URL evaluates to PRODUCTION mode with mandatory HTTPS")
    void testProductionPosture() {
        PosEnvironmentSettingsDiagnostic diagnostic = new PosEnvironmentSettingsDiagnostic();
        PosEnvironmentSettingsDiagnostic.EnvironmentPosture posture =
            diagnostic.evaluatePosture("https://api.pharmacy.bd/api/v2");

        assertEquals(PosEnvironmentSettingsDiagnostic.EnvironmentMode.PRODUCTION, posture.mode);
        assertTrue(posture.requireHttps, "Production strictly mandates HTTPS");
        assertEquals("true", posture.properties.get("enforce_transport_tls"));
    }
}
