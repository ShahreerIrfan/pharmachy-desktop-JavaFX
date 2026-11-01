package com.pharmacy.pos.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

public class PosDunningNotificationClientTest {

    @Test
    public void test01DunningAlertUrgencyEvaluation() {
        PosDunningNotificationClient.DunningAlert renewalAlert = new PosDunningNotificationClient.DunningAlert(
                "notif-1",
                "tn-01",
                "inv-01",
                PosDunningNotificationClient.DunningStage.UPCOMING_RENEWAL,
                "DELIVERED",
                "Upcoming Renewal",
                "আসন্ন নবায়ন",
                "Renew soon",
                "শীঘ্রই নবায়ন করুন",
                new BigDecimal("4500.00"),
                false,
                0,
                "2026-11-01T10:00:00Z"
        );
        assertFalse(renewalAlert.isUrgent());
        assertFalse(renewalAlert.isSuppressed());
        assertFalse(renewalAlert.isRecovered());

        PosDunningNotificationClient.DunningAlert failureAlert = new PosDunningNotificationClient.DunningAlert(
                "notif-2",
                "tn-01",
                "inv-01",
                PosDunningNotificationClient.DunningStage.PAYMENT_FAILED,
                "DELIVERED",
                "Payment Failed",
                "পেমেন্ট ব্যর্থ",
                "Failed to renew",
                "নবায়ন ব্যর্থ হয়েছে",
                new BigDecimal("4500.00"),
                false,
                0,
                "2026-11-01T10:00:00Z"
        );
        assertTrue(failureAlert.isUrgent());

        PosDunningNotificationClient.DunningAlert recoveredAlert = new PosDunningNotificationClient.DunningAlert(
                "notif-3",
                "tn-01",
                "inv-01",
                PosDunningNotificationClient.DunningStage.PAYMENT_RECOVERED,
                "DELIVERED",
                "Payment Recovered",
                "পেমেন্ট নিশ্চিত",
                "Recovered",
                "পুনরুদ্ধার",
                new BigDecimal("4500.00"),
                false,
                0,
                "2026-11-01T10:00:00Z"
        );
        assertTrue(recoveredAlert.isRecovered());
        assertFalse(recoveredAlert.isUrgent());
    }

    @Test
    public void test02SuppressedDeDuplicationAlert() {
        PosDunningNotificationClient.DunningAlert suppressedAlert = new PosDunningNotificationClient.DunningAlert(
                "notif-dup",
                "tn-01",
                "inv-01",
                PosDunningNotificationClient.DunningStage.GRACE_PERIOD_ACTIVE,
                "SUPPRESSED",
                "Grace Warning",
                "গ্রেস সতর্কতা",
                "Duplicate",
                "ডুপ্লিকেট",
                new BigDecimal("4500.00"),
                false,
                0,
                "2026-11-01T11:00:00Z"
        );
        assertTrue(suppressedAlert.isSuppressed());
        assertEquals("৳4500.00", PosDunningNotificationClient.formatBdt(suppressedAlert.amountDueBdt));
    }

    @Test
    public void test03PermissionDeniedAndOfflineStates() {
        PosDunningNotificationClient.DunningUiState deniedState =
                PosDunningNotificationClient.createPermissionDeniedState("CASHIER");
        assertEquals(PosDunningNotificationClient.UiStatus.PERMISSION_DENIED, deniedState.status);
        assertTrue(deniedState.errorMessageEn.contains("Access denied for role 'CASHIER'"));
        assertTrue(deniedState.errorMessageBn.contains("ডানিং নোটিফিকেশন দেখার অনুমতি নেই"));

        PosDunningNotificationClient.DunningUiState offlineState =
                PosDunningNotificationClient.createOfflineState();
        assertEquals(PosDunningNotificationClient.UiStatus.OFFLINE, offlineState.status);
        assertTrue(offlineState.errorMessageEn.contains("offline"));
        assertTrue(offlineState.errorMessageBn.contains("অফলাইনে আছে"));
    }
}
