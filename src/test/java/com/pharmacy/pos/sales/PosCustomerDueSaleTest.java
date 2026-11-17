package com.pharmacy.pos.sales;

import com.pharmacy.pos.sales.PosSaleDomainClient.ApiResponseResult;
import com.pharmacy.pos.sales.PosSaleDomainClient.ClientOutcome;
import com.pharmacy.pos.sales.PosSaleDomainClient.CustomerDueRecordDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.DueCollectionOutcomeDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosCustomerDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.SplitPaymentInputDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.UiState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PosCustomerDueSaleTest {

    @Test
    void customerDueApprovalRbacCreditSaleCollectionWithoutDoubleRevenueAndOfflineGuards() {
        PosSaleDomainClient client = new PosSaleDomainClient("http://localhost:8000", (method, path, body, token) -> {
            if ("GET".equals(method) && path.startsWith("/api/v2/pos/customers/")) {
                return new ApiResponseResult(200, """
                        {"count":1,"results":[{
                          "customer_id":"cust-1","tenant_id":"ten-a","primary_branch":"br-a",
                          "full_name":"Rafiqul Islam","phone":"01711002233",
                          "credit_limit_bdt":"1000.00","current_due_bdt":"200.00",
                          "available_credit_bdt":"800.00","is_credit_approved":true,
                          "credit_status":"APPROVED","credit_terms_days":15
                        }]}""");
            }
            if ("POST".equals(method) && path.endsWith("/approve-credit/")) {
                return new ApiResponseResult(200, """
                        {"customer_id":"cust-1","tenant_id":"ten-a","primary_branch":"br-a",
                         "full_name":"Rafiqul Islam","credit_limit_bdt":"1500.00","current_due_bdt":"200.00",
                         "available_credit_bdt":"1300.00","is_credit_approved":true,"credit_status":"APPROVED","credit_terms_days":30}""");
            }
            if ("GET".equals(method) && path.startsWith("/api/v2/pos/customer-dues/")) {
                return new ApiResponseResult(200, """
                        {"count":1,"results":[{
                          "due_id":"due-1","tenant_id":"ten-a","branch_id":"br-a","customer_id":"cust-1",
                          "sale_id":"sale-d1","invoice_id":"inv-1","customer_name":"Rafiqul Islam",
                          "total_due_bdt":"200.00","paid_amount_bdt":"0.00","remaining_due_bdt":"200.00",
                          "status":"OUTSTANDING","due_date":"2026-11-25","collections":[]
                        }]}""");
            }
            if ("POST".equals(method) && path.equals("/api/v2/pos/customer-dues/due-1/collect/")) {
                return new ApiResponseResult(201, """
                        {
                          "created":true,
                          "idempotent_replay":false,
                          "revenue_impact_bdt":"0.00",
                          "collection":{
                            "collection_id":"col-1","due_record_id":"due-1","customer_id":"cust-1",
                            "debit_account":"BKASH_CLEARING","credit_account":"AR_CUSTOMER_DUE",
                            "revenue_impact_bdt":"0.00","tender_type":"BKASH","amount_bdt":"150.00",
                            "cash_tendered_bdt":"0.00","change_given_bdt":"0.00","gateway_reference":"BK-DUE-88",
                            "verification_status":"VERIFIED","status":"CAPTURED"
                          },
                          "due_record":{
                            "due_id":"due-1","tenant_id":"ten-a","branch_id":"br-a","customer_id":"cust-1",
                            "total_due_bdt":"200.00","paid_amount_bdt":"150.00","remaining_due_bdt":"50.00",
                            "status":"PARTIAL"
                          }
                        }""");
            }
            return new ApiResponseResult(404, "{}");
        });

        // 1. Offline DUE blocked
        ClientOutcome<PosSaleDomainClient.PosSaleOrderDto> offlineDue = client.submitSplitPayments(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "sale-d1",
                List.of(new SplitPaymentInputDto("DUE", new BigDecimal("50.00"), "", "")),
                "k-off", true, false, true
        );
        assertEquals(UiState.OFFLINE, offlineDue.uiState);
        assertEquals("ERR_OFFLINE_CREDIT_UNAVAILABLE", offlineDue.errorCode);

        // 2. Cashier denied approveCustomerCredit locally; Manager succeeds
        ClientOutcome<PosCustomerDto> cashApprove = client.approveCustomerCredit(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "cust-1",
                new BigDecimal("1500.00"), 30, false, false
        );
        assertEquals(UiState.PERMISSION_DENIED, cashApprove.uiState);

        ClientOutcome<PosCustomerDto> mgrApprove = client.approveCustomerCredit(
                "tok", "BRANCH_MANAGER", "ten-a", "br-a", "ten-a", "br-a", "cust-1",
                new BigDecimal("1500.00"), 30, false, false
        );
        assertEquals(UiState.READY, mgrApprove.uiState);
        assertEquals(new BigDecimal("1300.00"), mgrApprove.data.availableCreditBdt);

        // 3. List customers & dues
        ClientOutcome<List<PosCustomerDto>> custs = client.listCustomers(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", false, false
        );
        assertEquals(UiState.READY, custs.uiState);
        assertEquals("APPROVED", custs.data.get(0).creditStatus);

        ClientOutcome<List<CustomerDueRecordDto>> dues = client.listCustomerDues(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "cust-1", false, false
        );
        assertEquals(UiState.READY, dues.uiState);
        assertEquals(new BigDecimal("200.00"), dues.data.get(0).remainingDueBdt);

        // 4. Reject invalid due collection: DUE tender, missing bKash TrxID, offline collection
        ClientOutcome<DueCollectionOutcomeDto> dueWithDue = client.collectCustomerDue(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "due-1",
                "DUE", new BigDecimal("50.00"), null, "", "k1", "", false, false
        );
        assertEquals(UiState.ERROR, dueWithDue.uiState);
        assertEquals("ERR_CANNOT_COLLECT_DUE_WITH_DUE", dueWithDue.errorCode);

        ClientOutcome<DueCollectionOutcomeDto> bkashNoRef = client.collectCustomerDue(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "due-1",
                "BKASH", new BigDecimal("50.00"), null, "", "k2", "", false, false
        );
        assertEquals(UiState.ERROR, bkashNoRef.uiState);
        assertEquals("ERR_DIGITAL_TENDER_REFERENCE_REQUIRED", bkashNoRef.errorCode);

        ClientOutcome<DueCollectionOutcomeDto> offCol = client.collectCustomerDue(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "due-1",
                "CASH", new BigDecimal("50.00"), null, "", "k3", "", false, true
        );
        assertEquals(UiState.OFFLINE, offCol.uiState);

        // 5. Verified bKash due collection posts AR_CUSTOMER_DUE with 0.00 double revenue
        ClientOutcome<DueCollectionOutcomeDto> colOk = client.collectCustomerDue(
                "tok", "CASHIER", "ten-a", "br-a", "ten-a", "br-a", "due-1",
                "BKASH", new BigDecimal("150.00"), null, "BK-DUE-88", "k4", "Installment", false, false
        );
        assertEquals(UiState.READY, colOk.uiState);
        assertEquals("AR_CUSTOMER_DUE", colOk.data.collection.creditAccount);
        assertEquals(new BigDecimal("0.00"), colOk.data.revenueImpactBdt);
        assertEquals(new BigDecimal("50.00"), colOk.data.dueRecord.remainingDueBdt);
        assertEquals("PARTIAL", colOk.data.dueRecord.status);
    }
}
