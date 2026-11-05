package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosPurchaseRequisitionClientTest
 * Architecture Decision: ADR-0184 (P184).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 */
class PosPurchaseRequisitionClientTest {

    private Map<String, PosPurchaseRequisitionClient.PurchaseRequisitionDto> requisitionStore;
    private PosPurchaseRequisitionClient managerClient;
    private PosPurchaseRequisitionClient pharmacistClient;
    private PosPurchaseRequisitionClient cashierClient;
    private PosPurchaseRequisitionClient foreignClient;

    @BeforeEach
    void setUp() {
        requisitionStore = new HashMap<>();

        managerClient = new PosPurchaseRequisitionClient(
                "tenant-alpha", "branch-main", "BRANCH_MANAGER", "user-mgr-1", false, requisitionStore
        );
        pharmacistClient = new PosPurchaseRequisitionClient(
                "tenant-alpha", "branch-main", "STAFF_PHARMACIST", "user-pharm-1", false, requisitionStore
        );
        cashierClient = new PosPurchaseRequisitionClient(
                "tenant-alpha", "branch-main", "CASHIER", "user-csh-1", false, requisitionStore
        );
        foreignClient = new PosPurchaseRequisitionClient(
                "tenant-beta", "branch-north", "BRANCH_MANAGER", "user-mgr-2", false, requisitionStore
        );
    }

    @Test
    @DisplayName("Test 1: Valid requisition drafting, item aggregation, and successful submission")
    void testCreateAndSubmitValidRequisition() {
        List<PosPurchaseRequisitionClient.PurchaseRequisitionItemDto> items = new ArrayList<>();
        items.add(new PosPurchaseRequisitionClient.PurchaseRequisitionItemDto(
                "pri-1", "prod-napa-500", "Napa Extra", "sup-sq", "Square", "BOX", 100, 50,
                new BigDecimal("2.5000"), 10, "Dispensary stock low"
        ));
        items.add(new PosPurchaseRequisitionClient.PurchaseRequisitionItemDto(
                "pri-2", "prod-seclo-20", "Seclo 20mg", "sup-sq", "Square", "STRIP", 10, 100,
                new BigDecimal("5.0000"), 25, "Inpatient floor usage"
        ));

        // Total cost: (50 * 2.50) + (100 * 5.00) = 125.00 + 500.00 = 625.0000
        PosPurchaseRequisitionClient.PurchaseRequisitionDto req = pharmacistClient.createRequisition(
                "DISPENSARY",
                "HIGH",
                new BigDecimal("1000.0000"),
                "Monthly emergency buffer replenishment",
                LocalDate.of(2026, 11, 20),
                items
        );

        assertNotNull(req);
        assertTrue(req.requisitionId.startsWith("req-"));
        assertEquals("tenant-alpha", req.tenantId);
        assertEquals("DISPENSARY", req.department);
        assertEquals("HIGH", req.priority);
        assertEquals("DRAFT", req.status);
        assertEquals(0, req.totalEstimatedCost.compareTo(new BigDecimal("625.0000")));
        assertEquals(2, req.items.size());

        // Submit within budget
        PosPurchaseRequisitionClient.PurchaseRequisitionDto submitted = pharmacistClient.submitRequisition(req.requisitionId, false);
        assertEquals("SUBMITTED", submitted.status);
        assertEquals("user-pharm-1", submitted.submittedById);
        assertNotNull(submitted.submittedAt);
    }

    @Test
    @DisplayName("Test 2: Budget ceiling guard rejects submission when total exceeds allocated budget")
    void testBudgetGuardEnforcementOnSubmission() {
        List<PosPurchaseRequisitionClient.PurchaseRequisitionItemDto> items = List.of(
                new PosPurchaseRequisitionClient.PurchaseRequisitionItemDto(
                        "pri-1", "prod-napa-500", "Napa", null, "", "BOX", 100, 100,
                        new BigDecimal("10.0000"), 0, "" // 1000.0000
                )
        );

        // Budget is 500.00, but cost is 1000.00
        PosPurchaseRequisitionClient.PurchaseRequisitionDto req = pharmacistClient.createRequisition(
                "CENTRAL_STORE",
                "MEDIUM",
                new BigDecimal("500.0000"),
                "Exceeding budget test",
                null,
                items
        );

        // Submitting without override must fail
        IllegalArgumentException exc = assertThrows(IllegalArgumentException.class, () ->
                pharmacistClient.submitRequisition(req.requisitionId, false)
        );
        assertTrue(exc.getMessage().contains("ERR_BUDGET_EXCEEDED"));

        // Submitting with manager override flag succeeds
        PosPurchaseRequisitionClient.PurchaseRequisitionDto overridden = pharmacistClient.submitRequisition(req.requisitionId, true);
        assertEquals("SUBMITTED", overridden.status);
    }

    @Test
    @DisplayName("Test 3: Multi-tier approval and rejection workflow lifecycle")
    void testApprovalAndRejectionLifecycle() {
        List<PosPurchaseRequisitionClient.PurchaseRequisitionItemDto> items = List.of(
                new PosPurchaseRequisitionClient.PurchaseRequisitionItemDto(
                        "pri-1", "prod-ace-500", "Ace", null, "", "BOX", 100, 10,
                        new BigDecimal("2.0000"), 5, ""
                )
        );

        // Req 1: Approved
        PosPurchaseRequisitionClient.PurchaseRequisitionDto req1 = pharmacistClient.createRequisition(
                "OT_PHARMACY", "EMERGENCY", new BigDecimal("500.0000"), "Urgent surgery needs", null, items
        );
        pharmacistClient.submitRequisition(req1.requisitionId, false);

        PosPurchaseRequisitionClient.PurchaseRequisitionDto approved = managerClient.approveRequisition(
                req1.requisitionId, "Approved for urgent OT delivery"
        );
        assertEquals("APPROVED", approved.status);
        assertEquals("user-mgr-1", approved.approvedById);
        assertEquals("Approved for urgent OT delivery", approved.approvalNotes);

        // Cannot cancel already approved requisition
        assertThrows(IllegalStateException.class, () -> pharmacistClient.cancelRequisition(approved.requisitionId));

        // Req 2: Rejected with reason
        PosPurchaseRequisitionClient.PurchaseRequisitionDto req2 = pharmacistClient.createRequisition(
                "INPATIENT", "LOW", new BigDecimal("500.0000"), "General stock", null, items
        );
        pharmacistClient.submitRequisition(req2.requisitionId, false);

        // Missing reason throws error
        assertThrows(IllegalArgumentException.class, () ->
                managerClient.rejectRequisition(req2.requisitionId, "")
        );

        PosPurchaseRequisitionClient.PurchaseRequisitionDto rejected = managerClient.rejectRequisition(
                req2.requisitionId, "Sufficient stock exists in central warehouse"
        );
        assertEquals("REJECTED", rejected.status);
        assertEquals("user-mgr-1", rejected.rejectedById);
        assertEquals("Sufficient stock exists in central warehouse", rejected.rejectionReason);
    }

    @Test
    @DisplayName("Test 4: RBAC permissions enforcement and cross-tenant boundary isolation")
    void testRoleAuthorizationAndCrossTenantIsolation() {
        List<PosPurchaseRequisitionClient.PurchaseRequisitionItemDto> items = List.of(
                new PosPurchaseRequisitionClient.PurchaseRequisitionItemDto(
                        "pri-1", "prod-1", "Prod 1", null, "", "BOX", 100, 10,
                        new BigDecimal("2.0000"), 5, ""
                )
        );

        // Cashier cannot create requisition
        assertThrows(SecurityException.class, () ->
                cashierClient.createRequisition("DISPENSARY", "MEDIUM", BigDecimal.ZERO, "", null, items)
        );

        // Pharmacist creates and submits
        PosPurchaseRequisitionClient.PurchaseRequisitionDto req = pharmacistClient.createRequisition(
                "DISPENSARY", "MEDIUM", BigDecimal.ZERO, "", null, items
        );
        pharmacistClient.submitRequisition(req.requisitionId, false);

        // Staff pharmacist cannot approve (requires manager/owner role)
        assertThrows(SecurityException.class, () ->
                pharmacistClient.approveRequisition(req.requisitionId, "Self approval")
        );

        // Foreign tenant cannot access or approve
        assertThrows(SecurityException.class, () -> foreignClient.getRequisition(req.requisitionId));
        assertThrows(SecurityException.class, () -> foreignClient.approveRequisition(req.requisitionId, ""));
    }
}

