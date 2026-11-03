package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PosFormTemplateMigrationClientTest {

    private PosFormTemplateMigrationClient client;
    private final String tenant1 = "tn-dhanmondi-101";
    private final String tenant2 = "tn-gulshan-202";

    @BeforeEach
    void setUp() {
        client = new PosFormTemplateMigrationClient();
    }

    @Test
    @DisplayName("P139-D1: Valid plan creation generates 64-char SHA-256 hash and DRAFT state")
    void testCreatePlanSuccessAndHashVerification() {
        var res = client.createPlan(
                tenant1,
                "Tablet v1.0 to v2.0 POS Upgrade",
                "tmpl-tablet-v1",
                "tmpl-tablet-v2",
                PosFormTemplateMigrationClient.MigrationStrategy.COERCE_SAFE,
                Map.of("scored_tablet", true),
                "STAFF_PHARMACIST",
                "STANDARD",
                false
        );

        assertEquals(PosFormTemplateMigrationClient.UiStatus.SUCCESS, res.status);
        assertNotNull(res.data);
        assertEquals(PosFormTemplateMigrationClient.MigrationPlanStatus.DRAFT, res.data.status);
        assertEquals(64, res.data.planHash.length());
        assertEquals("tmpl-tablet-v1", res.data.fromTemplateId);
        assertEquals("tmpl-tablet-v2", res.data.toTemplateId);
    }

    @Test
    @DisplayName("P139-D2: Dry-run validation audits historical immutability and sets VALIDATED status")
    void testValidateDryRunSimulatedAudit() {
        var created = client.createPlan(
                tenant1,
                "Dry Run Validation Plan",
                "tmpl-tablet-v1",
                "tmpl-tablet-v2",
                PosFormTemplateMigrationClient.MigrationStrategy.COERCE_SAFE,
                Map.of(),
                "PHARMACY_OWNER",
                "STANDARD",
                false
        );
        String planId = created.data.planId;

        var dryRes = client.validateDryRun(planId, "PHARMACY_OWNER", tenant1, 42, 180);
        assertEquals(PosFormTemplateMigrationClient.UiStatus.SUCCESS, dryRes.status);
        assertEquals(PosFormTemplateMigrationClient.MigrationPlanStatus.VALIDATED, dryRes.data.status);
        assertEquals(42, dryRes.data.affectedMedicineCount);
        assertEquals(180, dryRes.data.historicalDispensingAuditedCount);
        assertTrue((Boolean) dryRes.data.dryRunSummary.get("historical_immutability_certified"));
    }

    @Test
    @DisplayName("P139-D3: Execution updates plan to COMPLETED, records execution timestamp and preserves state")
    void testExecutePlanSuccess() {
        var created = client.createPlan(
                tenant1,
                "Execution Test Plan",
                "tmpl-tablet-v1",
                "tmpl-tablet-v2",
                PosFormTemplateMigrationClient.MigrationStrategy.COERCE_SAFE,
                Map.of(),
                "PHARMACY_OWNER",
                "STANDARD",
                false
        );
        String planId = created.data.planId;

        client.validateDryRun(planId, "PHARMACY_OWNER", tenant1, 15, 60);

        var execRes = client.executePlan(planId, "PHARMACY_OWNER", tenant1);
        assertEquals(PosFormTemplateMigrationClient.UiStatus.SUCCESS, execRes.status);
        assertEquals(PosFormTemplateMigrationClient.MigrationPlanStatus.COMPLETED, execRes.data.status);
        assertNotNull(execRes.data.executedAt);
        assertEquals(15, execRes.data.migratedMedicineCount);

        // Cannot re-execute completed plan
        var reExec = client.executePlan(planId, "PHARMACY_OWNER", tenant1);
        assertEquals(PosFormTemplateMigrationClient.UiStatus.ERROR, reExec.status);
        assertEquals("ERR_ALREADY_EXECUTED", reExec.errorCode);
    }

    @Test
    @DisplayName("P139-D4: Rollback safely reverts COMPLETED plan to ROLLED_BACK using stored snapshot")
    void testRollbackPlanSuccess() {
        var created = client.createPlan(
                tenant1,
                "Rollback Test Plan",
                "tmpl-tablet-v1",
                "tmpl-tablet-v2",
                PosFormTemplateMigrationClient.MigrationStrategy.COERCE_SAFE,
                Map.of(),
                "PHARMACY_OWNER",
                "STANDARD",
                false
        );
        String planId = created.data.planId;
        client.validateDryRun(planId, "PHARMACY_OWNER", tenant1, 10, 30);
        client.executePlan(planId, "PHARMACY_OWNER", tenant1);

        var rollbackRes = client.rollbackPlan(planId, "PHARMACY_OWNER", tenant1);
        assertEquals(PosFormTemplateMigrationClient.UiStatus.SUCCESS, rollbackRes.status);
        assertEquals(PosFormTemplateMigrationClient.MigrationPlanStatus.ROLLED_BACK, rollbackRes.data.status);
        assertNotNull(rollbackRes.data.rolledBackAt);
    }

    @Test
    @DisplayName("P139-D5: Cashier role and unentitled Basic plan are blocked with PERMISSION_DENIED")
    void testCashierAndUnentitledSubscriptionDenied() {
        // Cashier denied
        var cashierRes = client.createPlan(
                tenant1,
                "Cashier Attempt",
                "tmpl-tablet-v1",
                "tmpl-tablet-v2",
                PosFormTemplateMigrationClient.MigrationStrategy.COERCE_SAFE,
                Map.of(),
                "CASHIER",
                "STANDARD",
                false
        );
        assertEquals(PosFormTemplateMigrationClient.UiStatus.PERMISSION_DENIED, cashierRes.status);
        assertEquals("ERR_PERMISSION_DENIED", cashierRes.errorCode);

        // Basic subscription unentitled
        var basicRes = client.createPlan(
                tenant1,
                "Basic Attempt",
                "tmpl-tablet-v1",
                "tmpl-tablet-v2",
                PosFormTemplateMigrationClient.MigrationStrategy.COERCE_SAFE,
                Map.of(),
                "PHARMACY_OWNER",
                "BASIC",
                false
        );
        assertEquals(PosFormTemplateMigrationClient.UiStatus.PERMISSION_DENIED, basicRes.status);
        assertEquals("ERR_SUBSCRIPTION_UNENTITLED", basicRes.errorCode);
    }

    @Test
    @DisplayName("P139-D6: Cross-tenant access is blocked and offline query serves from local cache")
    void testCrossTenantAndOfflineSupport() {
        var created = client.createPlan(
                tenant1,
                "Tenant 1 Exclusive Plan",
                "tmpl-tablet-v1",
                "tmpl-tablet-v2",
                PosFormTemplateMigrationClient.MigrationStrategy.COERCE_SAFE,
                Map.of(),
                "PHARMACY_OWNER",
                "STANDARD",
                false
        );
        String planId = created.data.planId;

        // Cross-tenant execution denied
        var crossExec = client.executePlan(planId, "PHARMACY_OWNER", tenant2);
        assertEquals(PosFormTemplateMigrationClient.UiStatus.PERMISSION_DENIED, crossExec.status);
        assertEquals("ERR_CROSS_TENANT_ACCESS", crossExec.errorCode);

        // Offline query serves cached plan
        client.setOffline(true);
        var offlineQuery = client.queryPlan(planId, tenant1);
        assertEquals(PosFormTemplateMigrationClient.UiStatus.OFFLINE, offlineQuery.status);
        assertNotNull(offlineQuery.data);
        assertEquals(planId, offlineQuery.data.planId);
    }
}
