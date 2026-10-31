package com.pharmacy.pos.security;

import com.pharmacy.pos.models.PosHealthProber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0058: POS Health & Readiness Prober Tests")
class PosHealthProberTest {

    @Test
    @DisplayName("Should detect sensitive leaks in health response bodies")
    void testDetectSensitiveLeak() {
        String cleanJson = "{\"status\":\"READY\",\"checks\":{\"database\":\"UP\",\"redis\":\"UP\"}}";
        assertFalse(PosHealthProber.containsSensitiveLeak(cleanJson));

        String leakedJson1 = "{\"status\":\"UP\",\"database_url\":\"postgres://user:secret_password@10.0.0.1:5432\"}";
        assertTrue(PosHealthProber.containsSensitiveLeak(leakedJson1));

        String leakedJson2 = "{\"status\":\"UP\",\"secret_key\":\"my-super-secret-jwt-key\"}";
        assertTrue(PosHealthProber.containsSensitiveLeak(leakedJson2));
    }

    @Test
    @DisplayName("Should evaluate readiness correctly based on component checks")
    void testReadinessEvaluation() {
        Map<String, String> healthyChecks = Map.of(
                "database", "UP",
                "redis", "UP",
                "storage", "UP"
        );
        assertTrue(PosHealthProber.isReady("READY", healthyChecks));

        Map<String, String> degradedChecks = Map.of(
                "database", "DOWN",
                "redis", "UP"
        );
        assertFalse(PosHealthProber.isReady("NOT_READY", degradedChecks));
    }

    @Test
    @DisplayName("Should create structured health report for POS diagnostics")
    void testCreateHealthReport() {
        PosHealthProber.PosHealthReport report = PosHealthProber.createReport(
                "pharmacy-saas-cloud-api",
                "READY",
                Map.of("database", "UP", "redis", "UP"),
                false
        );

        assertEquals("pharmacy-saas-cloud-api", report.service());
        assertEquals("READY", report.status());
        assertTrue(report.isReady());
        assertFalse(report.isLivenessProbe());
        assertEquals("UP", report.componentChecks().get("database"));
        assertNotNull(report.timestamp());
    }
}
