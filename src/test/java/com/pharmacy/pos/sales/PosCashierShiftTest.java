package com.pharmacy.pos.sales;

import com.pharmacy.pos.sales.PosSaleDomainClient.ApiResponseResult;
import com.pharmacy.pos.sales.PosSaleDomainClient.ClientOutcome;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosCashierShiftDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosShiftCashMovementDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.UiState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

public class PosCashierShiftTest {

    @Test
    void testShiftLifecycleOpenAndExactClose() {
        PosSaleDomainClient client = new PosSaleDomainClient("http://localhost:8000", (method, path, body, token) -> {
            if ("POST".equals(method) && path.endsWith("/shifts/open/")) {
                return new ApiResponseResult(201, """
                        {
                          "shift_id": "shf-101",
                          "shift_number": "SHF-DHK01-20261116-0001",
                          "device_code": "REG01",
                          "status": "OPEN",
                          "opening_float_bdt": "2000.00",
                          "expected_drawer_cash_bdt": "2000.00",
                          "requires_manager_approval": false,
                          "opened_at": "2026-11-16T08:00:00Z"
                        }
                        """);
            }
            if ("POST".equals(method) && path.contains("/shifts/shf-101/close/")) {
                return new ApiResponseResult(200, """
                        {
                          "shift_id": "shf-101",
                          "shift_number": "SHF-DHK01-20261116-0001",
                          "device_code": "REG01",
                          "status": "CLOSED",
                          "opening_float_bdt": "2000.00",
                          "expected_drawer_cash_bdt": "2000.00",
                          "closing_cash_counted_bdt": "2000.00",
                          "variance_bdt": "0.00",
                          "requires_manager_approval": false,
                          "closed_at": "2026-11-16T16:00:00Z"
                        }
                        """);
            }
            return new ApiResponseResult(404, "{\"error_code\":\"ERR_NOT_FOUND\"}");
        });

        // 1. Open shift
        ClientOutcome<PosCashierShiftDto> openOutcome = client.openShift("br-dhk", "REG01", new BigDecimal("2000.00"), null);
        assertEquals(UiState.READY, openOutcome.uiState);
        assertNotNull(openOutcome.data);
        assertEquals("shf-101", openOutcome.data.shiftId);
        assertEquals("OPEN", openOutcome.data.status);
        assertEquals(new BigDecimal("2000.00"), openOutcome.data.openingFloatBdt);

        // 2. Close shift with exact cash
        ClientOutcome<PosCashierShiftDto> closeOutcome = client.closeShift("shf-101", new BigDecimal("2000.00"), "", null);
        assertEquals(UiState.READY, closeOutcome.uiState);
        assertNotNull(closeOutcome.data);
        assertEquals("CLOSED", closeOutcome.data.status);
        assertEquals(new BigDecimal("0.00"), closeOutcome.data.varianceBdt);
        assertFalse(closeOutcome.data.requiresManagerApproval);
    }

    @Test
    void testShiftPayoutAndVarianceApprovalFlow() {
        PosSaleDomainClient client = new PosSaleDomainClient("http://localhost:8000", (method, path, body, token) -> {
            if ("POST".equals(method) && path.contains("/shifts/shf-102/cash-movement/")) {
                return new ApiResponseResult(201, """
                        {
                          "movement_id": "shm-201",
                          "shift_id": "shf-102",
                          "movement_type": "PAYOUT_EXPENSE",
                          "amount_bdt": "500.00",
                          "reason": "Emergency courier payment",
                          "recipient_or_source": "Pathao Express",
                          "created_at": "2026-11-16T11:00:00Z"
                        }
                        """);
            }
            if ("POST".equals(method) && path.contains("/shifts/shf-102/close/")) {
                return new ApiResponseResult(200, """
                        {
                          "shift_id": "shf-102",
                          "shift_number": "SHF-DHK01-20261116-0002",
                          "status": "PENDING_MANAGER_APPROVAL",
                          "opening_float_bdt": "2000.00",
                          "total_payouts_bdt": "500.00",
                          "expected_drawer_cash_bdt": "1500.00",
                          "closing_cash_counted_bdt": "1450.00",
                          "variance_bdt": "-50.00",
                          "variance_reason": "Missing 50 BDT change note",
                          "requires_manager_approval": true,
                          "closed_at": "2026-11-16T16:00:00Z"
                        }
                        """);
            }
            if ("POST".equals(method) && path.contains("/shifts/shf-102/approve-variance/")) {
                return new ApiResponseResult(200, """
                        {
                          "shift_id": "shf-102",
                          "status": "RECONCILED",
                          "manager_approval_notes": "Manager verified shortage",
                          "reconciled_at": "2026-11-16T16:30:00Z"
                        }
                        """);
            }
            return new ApiResponseResult(404, "{\"error_code\":\"ERR_NOT_FOUND\"}");
        });

        // 1. Record mid-shift payout
        ClientOutcome<PosShiftCashMovementDto> movOutcome = client.recordCashMovement(
                "shf-102", "PAYOUT_EXPENSE", new BigDecimal("500.00"), "Emergency courier payment", "Pathao Express", null
        );
        assertEquals(UiState.READY, movOutcome.uiState);
        assertNotNull(movOutcome.data);
        assertEquals("shm-201", movOutcome.data.movementId);
        assertEquals(new BigDecimal("500.00"), movOutcome.data.amountBdt);

        // 2. Close shift with shortage -> PENDING_MANAGER_APPROVAL
        ClientOutcome<PosCashierShiftDto> closeOutcome = client.closeShift(
                "shf-102", new BigDecimal("1450.00"), "Missing 50 BDT change note", null
        );
        assertEquals(UiState.READY, closeOutcome.uiState);
        assertNotNull(closeOutcome.data);
        assertEquals("PENDING_MANAGER_APPROVAL", closeOutcome.data.status);
        assertEquals(new BigDecimal("-50.00"), closeOutcome.data.varianceBdt);
        assertTrue(closeOutcome.data.requiresManagerApproval);

        // 3. Manager approves variance -> RECONCILED
        ClientOutcome<PosCashierShiftDto> appOutcome = client.approveVariance(
                "shf-102", "Manager verified shortage", null
        );
        assertEquals(UiState.READY, appOutcome.uiState);
        assertEquals("RECONCILED", appOutcome.data.status);
    }
}
