package com.pharmacy.pos.billing;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PosSubscriptionE2eClientTest {

    @Test
    public void test01SupportOverrideRecordEvaluation() {
        PosSubscriptionE2eClient.SupportOverrideRecord emergencyRecord =
                new PosSubscriptionE2eClient.SupportOverrideRecord(
                        "ovr-1",
                        "tn-01",
                        "sub-01",
                        PosSubscriptionE2eClient.OverrideType.EMERGENCY_UNBLOCK,
                        PosSubscriptionE2eClient.OverrideStatus.ACTIVE,
                        3,
                        0,
                        "Emergency medication shortage dispensing override",
                        "DGDA-EMERGENCY-01",
                        "usr-superadmin",
                        "2026-11-01T10:00:00Z",
                        "2026-11-04T10:00:00Z",
                        null,
                        null,
                        null
                );

        assertTrue(emergencyRecord.isActive());
        assertTrue(emergencyRecord.isEmergencyUnblock());
        assertFalse(emergencyRecord.isGraceExtended());
        assertEquals("DGDA-EMERGENCY-01", emergencyRecord.ticketReference);
    }

    @Test
    public void test02SuccessStateWithBadgeRendering() {
        PosSubscriptionE2eClient.SupportOverrideRecord graceRecord =
                new PosSubscriptionE2eClient.SupportOverrideRecord(
                        "ovr-2",
                        "tn-01",
                        "sub-01",
                        PosSubscriptionE2eClient.OverrideType.GRACE_EXTENSION,
                        PosSubscriptionE2eClient.OverrideStatus.ACTIVE,
                        7,
                        0,
                        "Bangladesh bank wire clearance delay",
                        "SUP-98765",
                        "usr-superadmin",
                        "2026-11-01T10:00:00Z",
                        "2026-11-08T10:00:00Z",
                        null,
                        null,
                        null
                );

        PosSubscriptionE2eClient.LifecycleE2eUiState state =
                PosSubscriptionE2eClient.buildSuccessState(List.of(graceRecord));

        assertEquals(PosSubscriptionE2eClient.UiStatus.SUCCESS, state.status);
        assertTrue(state.hasActiveOverride);
        assertNotNull(state.activeOverrideBadgeEn);
        assertTrue(state.activeOverrideBadgeEn.contains("Grace Period Extended by 7 Days"));
        assertTrue(state.activeOverrideBadgeBn.contains("গ্রেস পিরিয়ড 7 দিন বাড়ানো হয়েছে"));

        PosSubscriptionE2eClient.LifecycleE2eUiState emptyState =
                PosSubscriptionE2eClient.buildSuccessState(Collections.emptyList());
        assertEquals(PosSubscriptionE2eClient.UiStatus.EMPTY, emptyState.status);
        assertFalse(emptyState.hasActiveOverride);
    }

    @Test
    public void test03PermissionDeniedAndOfflineStates() {
        PosSubscriptionE2eClient.LifecycleE2eUiState deniedState =
                PosSubscriptionE2eClient.createPermissionDeniedState("CASHIER");
        assertEquals(PosSubscriptionE2eClient.UiStatus.PERMISSION_DENIED, deniedState.status);
        assertTrue(deniedState.errorMessageEn.contains("Access denied for role 'CASHIER'"));
        assertTrue(deniedState.errorMessageBn.contains("অনুমতি নেই"));

        PosSubscriptionE2eClient.LifecycleE2eUiState offlineState =
                PosSubscriptionE2eClient.createOfflineState();
        assertEquals(PosSubscriptionE2eClient.UiStatus.OFFLINE, offlineState.status);
        assertTrue(offlineState.errorMessageEn.contains("offline"));
        assertTrue(offlineState.errorMessageBn.contains("অফলাইনে আছে"));
    }
}
