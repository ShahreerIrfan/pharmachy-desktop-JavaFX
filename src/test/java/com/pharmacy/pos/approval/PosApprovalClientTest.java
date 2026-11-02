package com.pharmacy.pos.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosApprovalClientTest
 * Tests Dual-Control Maker-Checker invariants, role permissions, token generation,
 * and offline capabilities for JavaFX POS (ADR-0108 / P108).
 */
public class PosApprovalClientTest {

    private PosApprovalClient client;
    private final String tenantId = "tn-pop-dhk";
    private final String branchA = "br-dhanmondi";
    private final String branchB = "br-gulshan";

    @BeforeEach
    public void setUp() {
        client = new PosApprovalClient("http://localhost:8000/api/v2");
    }

    @Test
    @DisplayName("P108: Cashier submits High Discount approval request successfully")
    public void testSubmitRequest_Success() {
        PosApprovalClient.ClientResult<PosApprovalClient.ApprovalRecord> result = client.submitRequest(
                tenantId,
                branchA,
                "cashier_rahim",
                "CASHIER",
                PosApprovalClient.ApprovalType.HIGH_DISCOUNT,
                "15% Discount on Insulin",
                "Long standing diabetic patient requested quarterly refill discount",
                new BigDecimal("1200.00"),
                new BigDecimal("15.00"),
                "ROUTINE"
        );

        assertTrue(result.isSuccess());
        assertEquals(PosApprovalClient.UiStatus.SUCCESS, result.uiStatus);
        assertNotNull(result.data.requestId);
        assertEquals(PosApprovalClient.RequestStatus.PENDING, result.data.status);
        assertEquals("cashier_rahim", result.data.makerUsername);
        assertNull(result.data.checkerUsername);
    }

    @Test
    @DisplayName("P108: Short title or missing justification is rejected with field errors")
    public void testSubmitRequest_InvalidInputs() {
        PosApprovalClient.ClientResult<PosApprovalClient.ApprovalRecord> shortTitle = client.submitRequest(
                tenantId,
                branchA,
                "cashier_rahim",
                "CASHIER",
                PosApprovalClient.ApprovalType.REFUND,
                "No",
                "Valid operational reason",
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                "ROUTINE"
        );
        assertEquals(PosApprovalClient.UiStatus.ERROR, shortTitle.uiStatus);
        assertEquals("INVALID_TITLE", shortTitle.errorCode);

        PosApprovalClient.ClientResult<PosApprovalClient.ApprovalRecord> shortReason = client.submitRequest(
                tenantId,
                branchA,
                "cashier_rahim",
                "CASHIER",
                PosApprovalClient.ApprovalType.REFUND,
                "Customer Return",
                "Why",
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                "ROUTINE"
        );
        assertEquals(PosApprovalClient.UiStatus.ERROR, shortReason.uiStatus);
        assertEquals("INVALID_REASON", shortReason.errorCode);
    }

    @Test
    @DisplayName("P108: Dual-Control Invariant: Maker cannot approve or sign off their own request")
    public void testApproveRequest_MakerCheckerSelfApprovalBlocked() {
        PosApprovalClient.ClientResult<PosApprovalClient.ApprovalRecord> sub = client.submitRequest(
                tenantId,
                branchA,
                "manager_tariq",
                "BRANCH_MANAGER",
                PosApprovalClient.ApprovalType.STOCK_WRITE_OFF,
                "Broken Syringes Batch 44",
                "Accidentally dropped box during inventory transit",
                new BigDecimal("450.00"),
                BigDecimal.ZERO,
                "URGENT"
        );
        assertTrue(sub.isSuccess());

        // Maker attempts self-approval
        PosApprovalClient.ClientResult<PosApprovalClient.ApprovalRecord> selfApprove = client.approveRequest(
                tenantId,
                sub.data.requestId,
                "manager_tariq", // Same username as maker
                "BRANCH_MANAGER",
                branchA,
                "Self authorized"
        );

        assertEquals(PosApprovalClient.UiStatus.PERMISSION_DENIED, selfApprove.uiStatus);
        assertEquals("ERR_CANNOT_APPROVE_OWN_REQUEST", selfApprove.errorCode);
        assertTrue(selfApprove.messageBn.contains("মেকার-চেকার লঙ্ঘন"));
    }

    @Test
    @DisplayName("P108: Frontline Cashier denied supervisor checker sign-off")
    public void testApproveRequest_RoleAuthorityGuard() {
        PosApprovalClient.ClientResult<PosApprovalClient.ApprovalRecord> sub = client.submitRequest(
                tenantId,
                branchA,
                "cashier_rahim",
                "CASHIER",
                PosApprovalClient.ApprovalType.REFUND,
                "Damaged strip return",
                "Customer returned punctured strip immediately at counter",
                new BigDecimal("80.00"),
                BigDecimal.ZERO,
                "ROUTINE"
        );
        assertTrue(sub.isSuccess());

        // Peer Cashier attempts to approve
        PosApprovalClient.ClientResult<PosApprovalClient.ApprovalRecord> cashierApprove = client.approveRequest(
                tenantId,
                sub.data.requestId,
                "cashier_karim",
                "CASHIER",
                branchA,
                "I agree"
        );

        assertEquals(PosApprovalClient.UiStatus.PERMISSION_DENIED, cashierApprove.uiStatus);
        assertEquals("ERR_INSUFFICIENT_APPROVAL_AUTHORITY", cashierApprove.errorCode);
    }

    @Test
    @DisplayName("P108: Branch Manager cannot approve requests for an unassigned branch store")
    public void testApproveRequest_CrossBranchViolation() {
        PosApprovalClient.ClientResult<PosApprovalClient.ApprovalRecord> sub = client.submitRequest(
                tenantId,
                branchA, // Branch A request
                "cashier_rahim",
                "CASHIER",
                PosApprovalClient.ApprovalType.HIGH_DISCOUNT,
                "Special Discount",
                "Patient with cancer treatment card requested compassionate price",
                new BigDecimal("800.00"),
                new BigDecimal("20.00"),
                "URGENT"
        );
        assertTrue(sub.isSuccess());

        // Branch Manager of Branch B attempts to approve
        PosApprovalClient.ClientResult<PosApprovalClient.ApprovalRecord> crossApprove = client.approveRequest(
                tenantId,
                sub.data.requestId,
                "manager_gulshan",
                "BRANCH_MANAGER",
                branchB, // Manager assigned to Branch B
                "Approved from Gulshan"
        );

        assertEquals(PosApprovalClient.UiStatus.PERMISSION_DENIED, crossApprove.uiStatus);
        assertEquals("ERR_CROSS_BRANCH_VIOLATION", crossApprove.errorCode);
    }

    @Test
    @DisplayName("P108: Authorized Checker approves request, generating valid verification token")
    public void testApproveAndVerifyToken_Success() {
        PosApprovalClient.ClientResult<PosApprovalClient.ApprovalRecord> sub = client.submitRequest(
                tenantId,
                branchA,
                "dispenser_hasan",
                "STAFF_PHARMACIST",
                PosApprovalClient.ApprovalType.CLINICAL_ESCALATION,
                "Warfarin and Miconazole Interaction",
                "Severe bleeding risk detected. Prescribing physician notified.",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "CRITICAL"
        );
        assertTrue(sub.isSuccess());

        // Supervising Pharmacist checks and approves
        PosApprovalClient.ClientResult<PosApprovalClient.ApprovalRecord> approved = client.approveRequest(
                tenantId,
                sub.data.requestId,
                "lead_pharmacist_monir",
                "STAFF_PHARMACIST",
                branchA,
                "Prescriber consented to dose adjustment and INR monitoring."
        );

        assertTrue(approved.isSuccess());
        assertEquals(PosApprovalClient.RequestStatus.APPROVED, approved.data.status);
        assertNotNull(approved.data.approvalToken);
        assertTrue(approved.data.approvalToken.startsWith("tok_appr_"));

        // Verify token
        PosApprovalClient.ClientResult<PosApprovalClient.ApprovalRecord> verifyRes = client.verifyToken(
                tenantId,
                approved.data.approvalToken
        );
        assertTrue(verifyRes.isSuccess());
        assertEquals(approved.data.requestId, verifyRes.data.requestId);
    }

    @Test
    @DisplayName("P108: Checker rejects request with mandatory explanation")
    public void testRejectRequest_Success() {
        PosApprovalClient.ClientResult<PosApprovalClient.ApprovalRecord> sub = client.submitRequest(
                tenantId,
                branchA,
                "cashier_rahim",
                "CASHIER",
                PosApprovalClient.ApprovalType.REFUND,
                "No receipt return",
                "Customer claims purchased yesterday without memo",
                new BigDecimal("600.00"),
                BigDecimal.ZERO,
                "ROUTINE"
        );
        assertTrue(sub.isSuccess());

        PosApprovalClient.ClientResult<PosApprovalClient.ApprovalRecord> rej = client.rejectRequest(
                tenantId,
                sub.data.requestId,
                "manager_tariq",
                "BRANCH_MANAGER",
                branchA,
                "No purchase receipt or CCTV verification available. Policy prohibits unverified cash returns."
        );

        assertTrue(rej.isSuccess());
        assertEquals(PosApprovalClient.RequestStatus.REJECTED, rej.data.status);
        assertEquals("manager_tariq", rej.data.checkerUsername);
        assertNull(rej.data.approvalToken);
    }

    @Test
    @DisplayName("P108: Offline POS caches approval requests and lists pending queue")
    public void testOfflineFallback_Queued() {
        client.setSimulatedOffline(true);

        PosApprovalClient.ClientResult<PosApprovalClient.ApprovalRecord> sub = client.submitRequest(
                tenantId,
                branchA,
                "cashier_rahim",
                "CASHIER",
                PosApprovalClient.ApprovalType.HIGH_DISCOUNT,
                "Offline Queue Request",
                "Offline counter authorization request",
                new BigDecimal("300.00"),
                new BigDecimal("10.00"),
                "ROUTINE"
        );

        assertEquals(PosApprovalClient.UiStatus.OFFLINE, sub.uiStatus);
        assertEquals("OFFLINE_QUEUED", sub.errorCode);

        // Retrieve pending requests
        PosApprovalClient.ClientResult<List<PosApprovalClient.ApprovalRecord>> pending = client.getPendingRequests(tenantId, branchA);
        assertTrue(pending.isSuccess());
        assertEquals(1, pending.data.size());
    }
}
