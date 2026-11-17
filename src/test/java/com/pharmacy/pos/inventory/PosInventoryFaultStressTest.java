package com.pharmacy.pos.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosInventoryFaultStressTest
 * Architecture Decision: ADR-0220 (P220).
 * Phase 11 Capstone: Inventory, Warehouse, Cold Chain & Transfers.
 */
public class PosInventoryFaultStressTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testAuthorizedRemoteConservationAndReadyState() {
        PosInventoryFaultStressClient client = new PosInventoryFaultStressClient(
                "http://localhost:8000",
                (method, path, body, token) -> {
                    assertEquals("POST", method);
                    assertEquals("/api/v2/inventory/qa-stress/verify-conservation/", path);
                    assertEquals("tok-owner", token);
                    String responseJson = """
                            {
                              "run_id": "invqa-001122aabbcc",
                              "tenant_id": "ten-a",
                              "branch_id": "br-a",
                              "test_type": "CONSERVATION_PIPELINE",
                              "status": "PASSED",
                              "total_operations": 6,
                              "successful_operations": 6,
                              "rejected_faults": 1,
                              "initial_stock_units": 240,
                              "final_stock_units": 120,
                              "net_ledger_delta_units": 120,
                              "conservation_verified": true,
                              "branch_isolation_verified": true,
                              "execution_time_ms": 48,
                              "metrics_payload": {"transfer_reconciled_units": 80},
                              "verification_hash": "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
                              "executed_at": "2026-11-09T10:00:00Z"
                            }
                            """;
                    return new PosInventoryFaultStressClient.ApiResponseResult(201, responseJson);
                }
        );

        var outcome = client.triggerRemoteVerification(
                PosInventoryFaultStressClient.StressTestType.CONSERVATION_PIPELINE,
                Map.of("branch", "br-a", "initial_units", 200),
                "tok-owner",
                "PHARMACY_OWNER", "ten-a", "br-a", "ten-a", "br-a",
                false, false
        );

        assertEquals(PosInventoryFaultStressClient.UiState.READY, outcome.uiState);
        assertNotNull(outcome.data);
        assertTrue(outcome.data.conservationVerified);
        assertTrue(outcome.data.branchIsolationVerified);
        assertEquals(120, outcome.data.finalStockUnits);
        assertEquals(80, outcome.data.metricsPayload.get("transfer_reconciled_units"));
        assertTrue(outcome.messageBn.contains("সফলভাবে"));
    }

    @Test
    void testRbacCrossBranchCrossTenantSuspendedAndOfflineStates() {
        PosInventoryFaultStressClient client = new PosInventoryFaultStressClient(
                "http://localhost:8000",
                (method, path, body, token) -> fail("Transport should not be invoked when client guard denies.")
        );

        // 1. Offline mode blocks live QA run cleanly
        var offline = client.triggerRemoteVerification(
                PosInventoryFaultStressClient.StressTestType.HIGH_VOLUME_STRESS,
                Map.of("branch", "br-a"), "tok",
                "PHARMACY_OWNER", "ten-a", "br-a", "ten-a", "br-a",
                false, true
        );
        assertEquals(PosInventoryFaultStressClient.UiState.OFFLINE, offline.uiState);
        assertEquals("ERR_OFFLINE_UNAVAILABLE", offline.errorCode);
        assertTrue(offline.messageBn.contains("অফলাইন"));

        // 2. Cashier denied
        var cashier = client.triggerRemoteVerification(
                PosInventoryFaultStressClient.StressTestType.CONCURRENT_MUTATION,
                Map.of("branch", "br-a"), "tok",
                "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                false, false
        );
        assertEquals(PosInventoryFaultStressClient.UiState.PERMISSION_DENIED, cashier.uiState);
        assertEquals("ERR_PERMISSION_DENIED", cashier.errorCode);
        assertTrue(cashier.messageBn.contains("নিষিদ্ধ"));

        // 3. Branch-bound Pharmacist denied on sibling branch
        var crossBranch = client.triggerRemoteVerification(
                PosInventoryFaultStressClient.StressTestType.FAULT_AND_ISOLATION,
                Map.of("branch", "br-a2"), "tok",
                "STAFF_PHARMACIST", "ten-a", "br-a", "ten-a", "br-a2",
                false, false
        );
        assertEquals(PosInventoryFaultStressClient.UiState.PERMISSION_DENIED, crossBranch.uiState);
        assertEquals("ERR_CROSS_BRANCH_ACCESS_DENIED", crossBranch.errorCode);

        // 4. Suspended tenant denied
        var suspended = client.triggerRemoteVerification(
                PosInventoryFaultStressClient.StressTestType.CONSERVATION_PIPELINE,
                Map.of("branch", "br-a"), "tok",
                "PHARMACY_OWNER", "ten-a", "br-a", "ten-a", "br-a",
                true, false
        );
        assertEquals(PosInventoryFaultStressClient.UiState.PERMISSION_DENIED, suspended.uiState);
        assertEquals("ERR_TENANT_SUSPENDED", suspended.errorCode);

        // 5. Cross-tenant blocked
        var crossTenant = client.triggerRemoteVerification(
                PosInventoryFaultStressClient.StressTestType.CONSERVATION_PIPELINE,
                Map.of("branch", "br-a"), "tok",
                "PHARMACY_OWNER", "ten-b", "br-b", "ten-a", "br-a",
                false, false
        );
        assertEquals(PosInventoryFaultStressClient.UiState.ERROR, crossTenant.uiState);
        assertEquals("ERR_CROSS_TENANT_NOT_FOUND", crossTenant.errorCode);
    }

    @Test
    void testHistoryEmptyAndErrorStates() {
        PosInventoryFaultStressClient emptyClient = new PosInventoryFaultStressClient(
                "http://localhost:8000",
                (method, path, body, token) -> {
                    assertEquals("GET", method);
                    assertEquals("/api/v2/inventory/qa-stress/?branch=br-a", path);
                    return new PosInventoryFaultStressClient.ApiResponseResult(200, "{\"results\": []}");
                }
        );
        var emptyOutcome = emptyClient.fetchRunHistory("br-a", "tok", false);
        assertEquals(PosInventoryFaultStressClient.UiState.EMPTY, emptyOutcome.uiState);
        assertTrue(emptyOutcome.data.isEmpty());

        PosInventoryFaultStressClient listClient = new PosInventoryFaultStressClient(
                "http://localhost:8000",
                (method, path, body, token) -> new PosInventoryFaultStressClient.ApiResponseResult(
                        200,
                        mapper.writeValueAsString(Map.of(
                                "results", List.of(Map.of(
                                        "run_id", "invqa-998877665544",
                                        "tenant_id", "ten-a",
                                        "branch_id", "br-a",
                                        "test_type", "HIGH_VOLUME_STRESS",
                                        "status", "PASSED",
                                        "conservation_verified", true,
                                        "branch_isolation_verified", true
                                ))
                        ))
                )
        );
        var readyOutcome = listClient.fetchRunHistory("br-a", "tok", false);
        assertEquals(PosInventoryFaultStressClient.UiState.READY, readyOutcome.uiState);
        assertEquals(1, readyOutcome.data.size());
        assertEquals("invqa-998877665544", readyOutcome.data.get(0).runId);
    }

    @Test
    void testLocalConcurrencyConservationAndOversellGuard() throws InterruptedException {
        PosInventoryFaultStressClient client = new PosInventoryFaultStressClient("http://localhost:8000");
        var run = client.verifyLocalConcurrencyConservation("ten-a", "br-a", 50, 20, 5);

        assertEquals("PASSED", run.status);
        assertTrue(run.conservationVerified);
        assertEquals(10, run.successfulOperations);
        assertEquals(10, run.rejectedFaults);
        assertEquals(0, run.finalStockUnits);
        assertEquals(64, run.verificationHash.length());
    }
}
