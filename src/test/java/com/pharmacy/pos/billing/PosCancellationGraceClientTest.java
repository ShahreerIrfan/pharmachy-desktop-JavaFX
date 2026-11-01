package com.pharmacy.pos.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P092: JavaFX Desktop POS Cancellation and Grace Period Client Tests")
public class PosCancellationGraceClientTest {

    @Test
    @DisplayName("Dispensing permission is granted during Active, Trial, and Grace Period, but blocked on Expired and Cancelled")
    void testDispensingPermissions() {
        assertTrue(PosCancellationGraceClient.isDispensingPermitted("ACTIVE"));
        assertTrue(PosCancellationGraceClient.isDispensingPermitted("TRIAL"));
        assertTrue(PosCancellationGraceClient.isDispensingPermitted("GRACE_PERIOD"));

        assertFalse(PosCancellationGraceClient.isDispensingPermitted("EXPIRED"));
        assertFalse(PosCancellationGraceClient.isDispensingPermitted("CANCELLED"));
        assertFalse(PosCancellationGraceClient.isDispensingPermitted("SUSPENDED"));
        assertFalse(PosCancellationGraceClient.isDispensingPermitted(null));
    }

    @Test
    @DisplayName("Read and export capabilities remain permanently safe regardless of subscription status")
    void testReadAndExportSafety() {
        assertTrue(PosCancellationGraceClient.isReadAndExportSafe("ACTIVE"));
        assertTrue(PosCancellationGraceClient.isReadAndExportSafe("GRACE_PERIOD"));
        assertTrue(PosCancellationGraceClient.isReadAndExportSafe("EXPIRED"));
        assertTrue(PosCancellationGraceClient.isReadAndExportSafe("CANCELLED"));
        assertTrue(PosCancellationGraceClient.isReadAndExportSafe("SUSPENDED"));
    }

    @Test
    @DisplayName("Grace period calculation computes exact days remaining and renders bilingual banner")
    void testGracePeriodBanner() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        LocalDate graceEnd = LocalDate.of(2026, 11, 15);

        assertEquals(14, PosCancellationGraceClient.calculateGraceDaysRemaining(graceEnd, today));

        PosCancellationGraceClient.LifecycleState state =
                PosCancellationGraceClient.evaluateLifecycle(
                        "GRACE_PERIOD",
                        LocalDate.of(2026, 10, 31),
                        graceEnd,
                        today,
                        false,
                        false
                );

        assertTrue(state.isInGracePeriod);
        assertTrue(state.canDispenseSales);
        assertTrue(state.canReadHistoricalData);
        assertEquals(14, state.graceDaysRemaining);
        assertTrue(state.headlineEn.contains("14 Days Remaining"));
        assertTrue(state.headlineBn.contains("14 দিন"));
    }

    @Test
    @DisplayName("Expired state blocks sales while preserving historical read access and rendering clear messaging")
    void testExpiredLifecycleState() {
        LocalDate today = LocalDate.of(2026, 11, 20);

        PosCancellationGraceClient.LifecycleState state =
                PosCancellationGraceClient.evaluateLifecycle(
                        "EXPIRED",
                        LocalDate.of(2026, 10, 31),
                        LocalDate.of(2026, 11, 15),
                        today,
                        false,
                        false
                );

        assertFalse(state.isInGracePeriod);
        assertFalse(state.canDispenseSales);
        assertTrue(state.canReadHistoricalData);
        assertTrue(state.canExportTenantData);
        assertEquals("Subscription Expired", state.headlineEn);
        assertEquals("সাবস্ক্রিপশনের মেয়াদ উত্তীর্ণ হয়েছে", state.headlineBn);
    }
}
