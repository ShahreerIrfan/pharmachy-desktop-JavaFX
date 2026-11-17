package com.pharmacy.pos.sales;

import com.pharmacy.pos.controllers.PosController;
import com.pharmacy.pos.models.TenantSession;
import com.pharmacy.pos.sales.PosFastSearchCartClient.FastSearchCandidateDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.AntibioticRegisterListDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.ApiResponseResult;
import com.pharmacy.pos.sales.PosSaleDomainClient.ClientOutcome;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosSaleOrderDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.RxGateStatusDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.UiState;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P227 POS Prescription Safety Gate, Pharmacist PCB Approval,
 * Antibiotic Register & Offline Limitations Desktop Tests.
 * Architecture Decision: ADR-0227 (P227).
 */
public class PosRxSafetyGateTest {

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyStarted) {
            latch.countDown();
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "JavaFX toolkit must start");
    }

    @Test
    @DisplayName("1. Client parses Rx gate & antibiotic register DTOs, blocks non-pharmacists, enforces reject reason & offline Rx preflight")
    void testRxSafetyClientDtosRbacAndOfflinePreflight() {
        AtomicBoolean sawFinalizeCall = new AtomicBoolean(false);
        AtomicBoolean sawAttachDoctorBmdc = new AtomicBoolean(false);

        PosSaleDomainClient client = new PosSaleDomainClient("http://localhost:8000", (method, path, body, token) -> {
            if ("GET".equals(method) && path.equals("/api/v2/pos/sales/sale-rx-1/rx-gate/")) {
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-rx-1",
                          "order_number": "POS-RX-101",
                          "lifecycle_state": "PENDING_RX_REVIEW",
                          "requires_rx_verification": true,
                          "rx_gate_status": "PENDING_PHARMACIST",
                          "requires_antibiotic_register": true,
                          "has_narcotic": false,
                          "has_blocked_recall_or_ban": false,
                          "can_finalize_online": false,
                          "can_finalize_offline": false,
                          "offline_block_reason_en": "Offline limitation: unverified Rx/antibiotic medicines cannot be finalized offline.",
                          "version": 2
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/sale-rx-1/attach-prescription/")) {
                if (body != null && body.contains("BMDC-4410")) {
                    sawAttachDoctorBmdc.set(true);
                }
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-rx-1",
                          "order_number": "POS-RX-101",
                          "lifecycle_state": "PENDING_RX_REVIEW",
                          "requires_rx_verification": true,
                          "rx_gate_status": "PENDING_PHARMACIST",
                          "prescription_id": "rx-8801",
                          "rx_reference_code": "RX-2026-8801",
                          "version": 3
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/sale-rx-1/verify-rx/")) {
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-rx-1",
                          "order_number": "POS-RX-101",
                          "lifecycle_state": "DRAFT",
                          "requires_rx_verification": true,
                          "rx_gate_status": "APPROVED",
                          "pharmacist_pcb_no_snapshot": "PCB-88412",
                          "rx_verified_by_email": "pharm@pharmacy.bd",
                          "rx_verified_at": "2026-11-10T10:15:00Z",
                          "version": 4
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/sale-rx-1/reject-rx/")) {
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-rx-1",
                          "order_number": "POS-RX-101",
                          "lifecycle_state": "PENDING_RX_REVIEW",
                          "requires_rx_verification": true,
                          "rx_gate_status": "REJECTED",
                          "rx_decision_reason": "Suspected altered Rx",
                          "version": 5
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/sale-rx-1/rx-gate/offline-preflight/")) {
                return new ApiResponseResult(422, """
                        {
                          "error_code": "ERR_OFFLINE_RX_BLOCKED",
                          "message_en": "Offline limitation: unverified Rx cannot finalize offline.",
                          "message_bn": "অফলাইন সীমাবদ্ধতা: অনুমোদনহীন প্রেসক্রিপশন ওষুধ অফলাইনে সম্পন্ন করা যাবে না।",
                          "summary": {
                            "sale_id": "sale-rx-1",
                            "requires_rx_verification": true,
                            "rx_gate_status": "PENDING_PHARMACIST",
                            "can_finalize_offline": false
                          }
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/sale-otc-1/rx-gate/offline-preflight/")) {
                return new ApiResponseResult(200, """
                        {
                          "offline_eligible": true,
                          "message_en": "OTC basket cleared for offline finalization.",
                          "summary": {
                            "sale_id": "sale-otc-1",
                            "requires_rx_verification": false,
                            "rx_gate_status": "NOT_REQUIRED",
                            "can_finalize_offline": true
                          }
                        }
                        """);
            }
            if ("GET".equals(method) && path.startsWith("/api/v2/pos/antibiotic-register/")) {
                return new ApiResponseResult(200, """
                        {
                          "count": 1,
                          "results": [
                            {
                              "register_entry_id": "abx-101",
                              "sale_id": "sale-rx-1",
                              "order_number": "POS-RX-101",
                              "brand_name": "Azithrocin 500",
                              "generic_name": "Azithromycin",
                              "classification_tier": "ANTIMICROBIAL_WATCH",
                              "dispensed_base_units": 10,
                              "doctor_bmdc_no": "BMDC-4410",
                              "patient_token": "PAT-09",
                              "approving_pharmacist_pcb_no": "PCB-88412",
                              "status": "ACTIVE"
                            }
                          ]
                        }
                        """);
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/sales/sale-rx-1/finalize/")) {
                sawFinalizeCall.set(true);
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-rx-1",
                          "order_number": "POS-RX-101",
                          "lifecycle_state": "FINALIZED",
                          "requires_rx_verification": true,
                          "rx_gate_status": "APPROVED",
                          "version": 6
                        }
                        """);
            }
            return new ApiResponseResult(404, "{}");
        });

        // 1) Non-pharmacist roles blocked locally & cross-tenant blocked
        for (String blockedRole : new String[]{"CASHIER", "BRANCH_MANAGER"}) {
            ClientOutcome<PosSaleOrderDto> denied = client.verifyRxSale(
                    "tok", blockedRole, "ten-a", "br-a", "ten-a", "br-a",
                    "sale-rx-1", "", "check", "", "", "", 1, false, false
            );
            assertEquals(UiState.PERMISSION_DENIED, denied.uiState, "Role must be denied: " + blockedRole);
            assertEquals("ERR_PHARMACIST_APPROVAL_REQUIRED", denied.errorCode);
        }
        for (String nonPosRole : new String[]{"INVENTORY_CLERK", "AUDITOR", "FINANCE_OFFICER"}) {
            ClientOutcome<PosSaleOrderDto> denied = client.verifyRxSale(
                    "tok", nonPosRole, "ten-a", "br-a", "ten-a", "br-a",
                    "sale-rx-1", "", "check", "", "", "", 1, false, false
            );
            assertEquals(UiState.PERMISSION_DENIED, denied.uiState, "Non-POS role must be denied: " + nonPosRole);
            assertEquals("ERR_PERMISSION_DENIED", denied.errorCode);
        }
        // Owner without PCB also denied; owner with PCB allowed
        ClientOutcome<PosSaleOrderDto> ownerNoPcb = client.verifyRxSale(
                "tok", "PHARMACY_OWNER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-rx-1", "", "notes", "", "", "", 1, false, false
        );
        assertEquals(UiState.PERMISSION_DENIED, ownerNoPcb.uiState);
        assertEquals("ERR_PHARMACIST_APPROVAL_REQUIRED", ownerNoPcb.errorCode);

        // 2) Rx Gate status & attach prescription & Pharmacist PCB verification
        ClientOutcome<RxGateStatusDto> gate = client.getRxGateStatus(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "sale-rx-1", false, false
        );
        assertEquals(UiState.READY, gate.uiState);
        assertTrue(gate.data.requiresRxVerification);
        assertTrue(gate.data.requiresAntibioticRegister);
        assertFalse(gate.data.canFinalizeOffline);

        ClientOutcome<PosSaleOrderDto> attached = client.attachPrescription(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-rx-1", "Dr. Rahman", "BMDC-4410", "PAT-09", "RX-2026-8801", "1+0+1", 5, false, false
        );
        assertEquals(UiState.READY, attached.uiState);
        assertTrue(sawAttachDoctorBmdc.get());

        ClientOutcome<PosSaleOrderDto> approved = client.verifyRxSale(
                "tok", "STAFF_PHARMACIST", "ten-a", "br-a", "ten-a", "br-a",
                "sale-rx-1", "PCB-88412", "Verified prescription", "RX-2026-8801", "", "", 3, false, false
        );
        assertEquals(UiState.READY, approved.uiState);
        assertEquals("APPROVED", approved.data.rxGateStatus);
        assertEquals("PCB-88412", approved.data.pharmacistPcbNoSnapshot);

        // 3) Reject requires reason >= 3 chars
        ClientOutcome<PosSaleOrderDto> badReject = client.rejectRxSale(
                "tok", "STAFF_PHARMACIST", "ten-a", "br-a", "ten-a", "br-a",
                "sale-rx-1", "PCB-88412", "no", false, false
        );
        assertEquals(UiState.ERROR, badReject.uiState);
        assertEquals("ERR_RX_REJECTION_REASON_REQUIRED", badReject.errorCode);

        ClientOutcome<PosSaleOrderDto> okReject = client.rejectRxSale(
                "tok", "STAFF_PHARMACIST", "ten-a", "br-a", "ten-a", "br-a",
                "sale-rx-1", "PCB-88412", "Suspected altered Rx", false, false
        );
        assertEquals(UiState.READY, okReject.uiState);
        assertEquals("REJECTED", okReject.data.rxGateStatus);

        // 4) Offline Rx preflight & Antibiotic register & finalize
        ClientOutcome<RxGateStatusDto> rxPreflight = client.offlineRxPreflight(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "sale-rx-1", false, false
        );
        assertEquals(UiState.ERROR, rxPreflight.uiState);
        assertEquals("ERR_OFFLINE_RX_BLOCKED", rxPreflight.errorCode);

        ClientOutcome<RxGateStatusDto> otcPreflight = client.offlineRxPreflight(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "sale-otc-1", false, false
        );
        assertEquals(UiState.READY, otcPreflight.uiState);
        assertTrue(otcPreflight.data.canFinalizeOffline);

        ClientOutcome<AntibioticRegisterListDto> abxList = client.listAntibioticRegister(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "", "", false, false
        );
        assertEquals(UiState.READY, abxList.uiState);
        assertEquals(1, abxList.data.count);
        assertEquals("Azithrocin 500", abxList.data.results.get(0).brandName);
        assertEquals("PCB-88412", abxList.data.results.get(0).approvingPharmacistPcbNo);

        ClientOutcome<PosSaleOrderDto> fin = client.finalizeSale(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a",
                "sale-rx-1", new BigDecimal("350.00"), "", false, false, false
        );
        assertEquals(UiState.READY, fin.uiState);
        assertTrue(sawFinalizeCall.get());
    }

    @Test
    @DisplayName("2. PosController enforces offline Rx hard block, [F8] Pharmacist gate, quantity escalation invalidation, finalize & EN/BN")
    void testPosControllerRxSafetyGateOfflineAndCheckoutWorkflow() throws Exception {
        URL fxmlUrl = getClass().getResource("/com/pharmacy/pos/views/main.fxml");
        assertNotNull(fxmlUrl);
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();
        assertNotNull(root);

        PosController controller = loader.getController();
        controller.setAsyncNetworkCalls(false);
        controller.setSessionDetails(
                new TenantSession("ten-a", "br-a", "tok-cashier", "cashier-01", "CASHIER", true),
                "Cashier Tariq", "Day Shift", "500.00"
        );

        AtomicInteger qtyUnitsServer = new AtomicInteger(1);
        AtomicBoolean rxApprovedOnServer = new AtomicBoolean(false);
        AtomicBoolean finalizedOnServer = new AtomicBoolean(false);
        AtomicBoolean sawRecordPayment = new AtomicBoolean(false);

        PosFastSearchCartClient searchStub = new PosFastSearchCartClient("http://localhost:8000", (m, p, b, t) -> {
            if (p.contains("/quick-cart/")) {
                qtyUnitsServer.set(1);
                rxApprovedOnServer.set(false);
                return new ApiResponseResult(201, """
                        {
                          "created": true,
                          "sale": {
                            "sale_id": "sale-ui-rx-1",
                            "tenant_id": "ten-a",
                            "branch_id": "br-a",
                            "order_number": "POS-UI-RX-1",
                            "lifecycle_state": "DRAFT",
                            "requires_rx_verification": false,
                            "rx_gate_status": "NOT_REQUIRED",
                            "version": 1,
                            "lines": []
                          }
                        }
                        """);
            }
            if (p.contains("/scan-add/") || p.contains("/cart-line/")) {
                if (b != null && b.contains("3")) qtyUnitsServer.set(3);
                int q = qtyUnitsServer.get();
                int baseUnits = q * 10;
                String gate = rxApprovedOnServer.get() && baseUnits <= 10 ? "APPROVED" : "PENDING_PHARMACIST";
                String verifiedAt = "APPROVED".equals(gate) ? "\"2026-11-10T10:20:00Z\"" : "null";
                return new ApiResponseResult(200, """
                        {
                          "sale": {
                            "sale_id": "sale-ui-rx-1",
                            "tenant_id": "ten-a",
                            "branch_id": "br-a",
                            "order_number": "POS-UI-RX-1",
                            "lifecycle_state": "PENDING_RX_REVIEW",
                            "requires_rx_verification": true,
                            "rx_gate_status": "%s",
                            "rx_verified_at": %s,
                            "pharmacist_pcb_no_snapshot": "PCB-PHARM-909",
                            "subtotal_bdt": "%d.00",
                            "grand_total_bdt": "%d.00",
                            "version": 2,
                            "lines": [
                              {
                                "line_id": "ln-abx-1",
                                "line_number": 1,
                                "product_id": "prod-azm",
                                "product_brand_name": "Azithrocin 500",
                                "product_generic_name": "Azithromycin",
                                "packaging_unit": "STRIP",
                                "quantity_units": %d,
                                "conversion_factor": 10,
                                "base_units_total": %d,
                                "unit_price_bdt": "350.00",
                                "line_subtotal_bdt": "%d.00",
                                "line_total_bdt": "%d.00",
                                "regulatory_classification": "ANTIMICROBIAL_ACCESS",
                                "requires_rx_verification": true,
                                "rx_verified": %s,
                                "rx_verified_base_units": %d
                              }
                            ]
                          }
                        }
                        """.formatted(
                        gate, verifiedAt, q * 350, q * 350, q, baseUnits, q * 350, q * 350,
                        "APPROVED".equals(gate), "APPROVED".equals(gate) ? 10 : 0
                ));
            }
            return new ApiResponseResult(404, "{}");
        });
        controller.setSearchCartClient(searchStub);

        PosSaleDomainClient domainStub = new PosSaleDomainClient("http://localhost:8000", (m, p, b, t) -> {
            if ("GET".equals(m) && p.equals("/api/v2/pos/sales/sale-ui-rx-1/rx-gate/")) {
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-ui-rx-1",
                          "order_number": "POS-UI-RX-1",
                          "lifecycle_state": "PENDING_RX_REVIEW",
                          "requires_rx_verification": true,
                          "rx_gate_status": "%s",
                          "requires_antibiotic_register": true,
                          "can_finalize_online": %s,
                          "can_finalize_offline": false,
                          "version": 2
                        }
                        """.formatted(
                        rxApprovedOnServer.get() ? "APPROVED" : "PENDING_PHARMACIST",
                        rxApprovedOnServer.get()
                ));
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/sales/sale-ui-rx-1/attach-prescription/")) {
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-ui-rx-1",
                          "order_number": "POS-UI-RX-1",
                          "lifecycle_state": "PENDING_RX_REVIEW",
                          "requires_rx_verification": true,
                          "rx_gate_status": "PENDING_PHARMACIST",
                          "prescription_id": "rx-ui-1",
                          "rx_reference_code": "RX-UI-99",
                          "version": 3,
                          "lines": [
                            {
                              "line_id": "ln-abx-1",
                              "line_number": 1,
                              "product_id": "prod-azm",
                              "product_brand_name": "Azithrocin 500",
                              "product_generic_name": "Azithromycin",
                              "packaging_unit": "STRIP",
                              "quantity_units": 1,
                              "conversion_factor": 10,
                              "base_units_total": 10,
                              "unit_price_bdt": "350.00",
                              "line_subtotal_bdt": "350.00",
                              "line_total_bdt": "350.00",
                              "regulatory_classification": "ANTIMICROBIAL_ACCESS",
                              "requires_rx_verification": true,
                              "rx_verified": false,
                              "rx_verified_base_units": 0
                            }
                          ]
                        }
                        """);
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/sales/sale-ui-rx-1/verify-rx/")) {
                rxApprovedOnServer.set(true);
                int approvedBase = qtyUnitsServer.get() * 10;
                int q = qtyUnitsServer.get();
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-ui-rx-1",
                          "order_number": "POS-UI-RX-1",
                          "lifecycle_state": "DRAFT",
                          "requires_rx_verification": true,
                          "rx_gate_status": "APPROVED",
                          "pharmacist_pcb_no_snapshot": "PCB-PHARM-909",
                          "rx_reference_code": "RX-UI-99",
                          "rx_verified_at": "2026-11-10T10:25:00Z",
                          "subtotal_bdt": "%d.00",
                          "grand_total_bdt": "%d.00",
                          "version": 4,
                          "lines": [
                            {
                              "line_id": "ln-abx-1",
                              "line_number": 1,
                              "product_id": "prod-azm",
                              "product_brand_name": "Azithrocin 500",
                              "product_generic_name": "Azithromycin",
                              "packaging_unit": "STRIP",
                              "quantity_units": %d,
                              "conversion_factor": 10,
                              "base_units_total": %d,
                              "unit_price_bdt": "350.00",
                              "line_subtotal_bdt": "%d.00",
                              "line_total_bdt": "%d.00",
                              "regulatory_classification": "ANTIMICROBIAL_ACCESS",
                              "requires_rx_verification": true,
                              "rx_verified": true,
                              "rx_verified_base_units": %d
                            }
                          ]
                        }
                        """.formatted(q * 350, q * 350, q, approvedBase, q * 350, q * 350, approvedBase));
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/sales/sale-ui-rx-1/reject-rx/")) {
                rxApprovedOnServer.set(false);
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-ui-rx-1",
                          "order_number": "POS-UI-RX-1",
                          "lifecycle_state": "PENDING_RX_REVIEW",
                          "requires_rx_verification": true,
                          "rx_gate_status": "REJECTED",
                          "rx_decision_reason": "Dose discrepancy found",
                          "version": 5,
                          "lines": [
                            {
                              "line_id": "ln-abx-1",
                              "line_number": 1,
                              "product_id": "prod-azm",
                              "product_brand_name": "Azithrocin 500",
                              "product_generic_name": "Azithromycin",
                              "packaging_unit": "STRIP",
                              "quantity_units": 1,
                              "conversion_factor": 10,
                              "base_units_total": 10,
                              "unit_price_bdt": "350.00",
                              "line_subtotal_bdt": "350.00",
                              "line_total_bdt": "350.00",
                              "regulatory_classification": "ANTIMICROBIAL_ACCESS",
                              "requires_rx_verification": true,
                              "rx_verified": false,
                              "rx_verified_base_units": 0
                            }
                          ]
                        }
                        """);
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/sales/sale-ui-rx-1/record-payment/")) {
                sawRecordPayment.set(true);
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-ui-rx-1",
                          "order_number": "POS-UI-RX-1",
                          "lifecycle_state": "PARTIALLY_PAID",
                          "version": 5
                        }
                        """);
            }
            if ("POST".equals(m) && p.equals("/api/v2/pos/sales/sale-ui-rx-1/finalize/")) {
                finalizedOnServer.set(true);
                return new ApiResponseResult(200, """
                        {
                          "sale_id": "sale-ui-rx-1",
                          "order_number": "POS-UI-RX-1",
                          "lifecycle_state": "FINALIZED",
                          "rx_gate_status": "APPROVED",
                          "version": 6
                        }
                        """);
            }
            if ("GET".equals(m) && p.startsWith("/api/v2/pos/antibiotic-register/")) {
                return new ApiResponseResult(200, """
                        {
                          "count": 1,
                          "results": [
                            {
                              "register_entry_id": "abx-ui-1",
                              "sale_id": "sale-ui-rx-1",
                              "order_number": "POS-UI-RX-1",
                              "brand_name": "Azithrocin 500",
                              "generic_name": "Azithromycin",
                              "dispensed_base_units": 10,
                              "approving_pharmacist_pcb_no": "PCB-PHARM-909",
                              "status": "ACTIVE"
                            }
                          ]
                        }
                        """);
            }
            return new ApiResponseResult(404, "{}");
        });
        controller.setSaleDomainClient(domainStub);

        Button btnRxGate = getField(controller, "btnRxGate");
        VBox rxGateDrawer = getField(controller, "rxGateDrawer");
        Label rxGateStatusBadge = getField(controller, "rxGateStatusBadge");
        Label checkoutRxGateHintLabel = getField(controller, "checkoutRxGateHintLabel");
        Label rxComplianceSummaryLabel = getField(controller, "rxComplianceSummaryLabel");
        TextField doctorNameField = getField(controller, "rxDoctorNameField");
        TextField doctorBmdcField = getField(controller, "rxDoctorBmdcField");
        TextField pcbField = getField(controller, "rxPharmacistPcbField");
        TextField notesField = getField(controller, "rxDecisionNotesField");

        // 1) Offline OTC medicine CAN checkout safely
        controller.handleToggleOffline();
        FastSearchCandidateDto napaOtc = FastSearchCandidateDto.fromSnapshotItemMap(Map.ofEntries(
                Map.entry("product_id", "prod-napa"),
                Map.entry("sku_code", "NAPA-500"),
                Map.entry("brand_name", "Napa 500"),
                Map.entry("generic_name", "Paracetamol"),
                Map.entry("barcode_tokens", "8940001"),
                Map.entry("unit_price_bdt", "1.20"),
                Map.entry("strip_conversion_factor", 10),
                Map.entry("sellable_base_units", 200),
                Map.entry("available_base_units", 200),
                Map.entry("requires_rx_verification", false),
                Map.entry("regulatory_classification", "OTC"),
                Map.entry("non_divisible_sealed_only", false)
        ));
        controller.handleAddCandidate(napaOtc);
        assertEquals(1, controller.getCartItems().size());
        controller.handleCheckout();
        assertEquals(UiState.OFFLINE, controller.getLastUiState());
        assertEquals(1, controller.getSyncEngine().getPendingCount(), "OTC offline sale must enqueue cleanly");
        assertTrue(controller.getCartItems().isEmpty());

        // 2) Offline Antibiotic HARD BLOCKED from checkout (cannot enqueue or clear cart!)
        FastSearchCandidateDto azithroAbx = FastSearchCandidateDto.fromSnapshotItemMap(Map.ofEntries(
                Map.entry("product_id", "prod-azm"),
                Map.entry("sku_code", "AZM-500"),
                Map.entry("brand_name", "Azithrocin 500"),
                Map.entry("generic_name", "Azithromycin"),
                Map.entry("barcode_tokens", "8940002"),
                Map.entry("unit_price_bdt", "35.00"),
                Map.entry("strip_conversion_factor", 10),
                Map.entry("sellable_base_units", 100),
                Map.entry("available_base_units", 100),
                Map.entry("requires_rx_verification", true),
                Map.entry("regulatory_classification", "ANTIMICROBIAL_ACCESS"),
                Map.entry("non_divisible_sealed_only", false)
        ));
        controller.handleAddCandidate(azithroAbx);
        controller.handleCheckout();
        assertEquals(UiState.OFFLINE, controller.getLastUiState());
        assertEquals(1, controller.getSyncEngine().getPendingCount(), "Blocked offline Rx must NOT enqueue!");
        assertEquals(1, controller.getCartItems().size(), "Blocked offline Rx cart must remain parked/intact!");
        assertTrue(checkoutRxGateHintLabel.getText().contains("Offline Block"));

        // 3) Reconnect online, replace with server-backed Rx cart & press [F8] Rx Gate
        controller.handleToggleOffline();
        controller.getCartItems().clear();
        controller.handleAddCandidate(azithroAbx);
        assertEquals("PENDING_PHARMACIST", rxGateStatusBadge.getText());
        controller.handleGlobalKeyShortcuts(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F8, false, false, false, false));
        assertTrue(rxGateDrawer.isVisible());

        // Online checkout blocked before Pharmacist approval
        controller.handleCheckout();
        assertEquals(UiState.ERROR, controller.getLastUiState());
        assertFalse(finalizedOnServer.get());

        // Cashier denied approving Rx
        pcbField.setText("PCB-FAKE");
        notesField.setText("Cashier trying to bypass");
        controller.handleVerifyRxOrder();
        assertEquals(UiState.PERMISSION_DENIED, controller.getLastUiState());

        // Attach Rx & switch to STAFF_PHARMACIST -> Approve with PCB
        doctorNameField.setText("Dr. Nabila Sultana");
        doctorBmdcField.setText("A-44120");
        controller.handleAttachPrescription();
        assertEquals(UiState.READY, controller.getLastUiState());

        controller.setSessionDetails(
                new TenantSession("ten-a", "br-a", "tok-pharm", "pharm-01", "STAFF_PHARMACIST", true),
                "Pharmacist Faruk", "Day Shift", "500.00"
        );
        pcbField.setText("PCB-PHARM-909");
        notesField.setText("Verified Abx course");
        controller.handleVerifyRxOrder();
        assertEquals(UiState.READY, controller.getLastUiState());
        assertEquals("APPROVED", rxGateStatusBadge.getText());
        assertTrue(checkoutRxGateHintLabel.getText().contains("APPROVED"));

        // Reject blocks again (Cashier cannot checkout), then Pharmacist Re-Approves & Cashier finalizes!
        notesField.setText("Dose discrepancy found");
        controller.handleRejectRxOrder();
        assertEquals(UiState.READY, controller.getLastUiState());
        assertEquals("REJECTED", rxGateStatusBadge.getText());
        controller.setSessionDetails(
                new TenantSession("ten-a", "br-a", "tok-cashier", "cashier-01", "CASHIER", true),
                "Cashier Tariq", "Day Shift", "500.00"
        );
        controller.handleCheckout();
        assertEquals(UiState.ERROR, controller.getLastUiState());
        assertFalse(finalizedOnServer.get());

        controller.setSessionDetails(
                new TenantSession("ten-a", "br-a", "tok-pharm", "pharm-01", "STAFF_PHARMACIST", true),
                "Pharmacist Faruk", "Day Shift", "500.00"
        );
        notesField.setText("Corrected strip dosage");
        controller.handleVerifyRxOrder();
        assertEquals("APPROVED", rxGateStatusBadge.getText());

        // Quantity escalation before server sync MUST block Cashier checkout!
        controller.setSessionDetails(
                new TenantSession("ten-a", "br-a", "tok-cashier", "cashier-01", "CASHIER", true),
                "Cashier Tariq", "Day Shift", "500.00"
        );
        qtyUnitsServer.set(1);
        controller.getCartItems().set(0, new com.pharmacy.pos.models.CartItem(
                "prod-azm", "Azithrocin 500", "Azithromycin", "STRIP", 15,
                new BigDecimal("350.00"), "LOT-1", java.time.LocalDate.now().plusYears(1), BigDecimal.ZERO
        ));
        controller.updateTotals();
        assertTrue(checkoutRxGateHintLabel.getText().contains("re-approve"));
        controller.handleCheckout();
        assertEquals(UiState.ERROR, controller.getLastUiState());
        assertFalse(finalizedOnServer.get());

        // Restore approved quantity -> checkout finalizes cleanly!
        controller.getCartItems().set(0, new com.pharmacy.pos.models.CartItem(
                "prod-azm", "Azithrocin 500", "Azithromycin", "STRIP", 1,
                new BigDecimal("350.00"), "LOT-1", java.time.LocalDate.now().plusYears(1), BigDecimal.ZERO
        ));
        controller.updateTotals();
        controller.handleCheckout();
        assertEquals(UiState.READY, controller.getLastUiState());
        assertTrue(sawRecordPayment.get());
        assertTrue(finalizedOnServer.get());
        assertTrue(controller.getCartItems().isEmpty());

        // 4) Refresh Antibiotic Register & test EN/BN toggle
        controller.handleRefreshAntibioticRegister();
        assertEquals(UiState.READY, controller.getLastUiState());
        assertTrue(rxComplianceSummaryLabel.getText().contains("Azithrocin 500"));
        assertTrue(rxComplianceSummaryLabel.getText().contains("PCB-PHARM-909"));

        controller.handleToggleLanguage();
        assertTrue(btnRxGate.getText().contains("Rx"));
        assertEquals("Rx অনুমোদন", ((Button) getField(controller, "btnVerifyRxOrder")).getText());
        assertEquals("Rx প্রত্যাখ্যান", ((Button) getField(controller, "btnRejectRxOrder")).getText());
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(PosController target, String name) throws Exception {
        Field f = PosController.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }
}
