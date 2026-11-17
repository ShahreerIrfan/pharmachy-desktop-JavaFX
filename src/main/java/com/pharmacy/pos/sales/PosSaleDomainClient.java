package com.pharmacy.pos.sales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmacy.pos.models.CartItem;
import com.pharmacy.pos.models.SaleTransaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * PosSaleDomainClient
 * Architecture Decision: ADR-0221 (P221).
 * Phase 12: POS, Sales Orders, Checkout & Returns.
 *
 * Desktop POS Domain Client wired to /api/v2/pos/*:
 * - Normalized DTOs (PosCustomerDto, PosSaleOrderDto, PosSaleLineDto, PosSaleAllocationDto, PosPaymentAttemptDto).
 * - Uses manual fromMap/toMap serialization to stay 100% JPMS-safe under module-info.java.
 * - Strict BigDecimal financial math (HALF_UP), lifecycle transition matrix, RBAC/branch/tenant/subscription guards,
 *   and explicit UI states (LOADING, READY, EMPTY, ERROR, OFFLINE, PERMISSION_DENIED) in EN & BN.
 */
public class PosSaleDomainClient {

    public enum UiState {
        LOADING("Loading POS sales order...", "পিওএস সেলস অর্ডার লোড হচ্ছে..."),
        READY("POS sales data ready.", "পিওএস সেলস তথ্য প্রস্তুত।"),
        EMPTY("No POS sales orders found.", "কোনো পিওএস সেলস অর্ডার পাওয়া যায়নি।"),
        ERROR("POS validation or server error.", "পিওএস যাচাই বা সার্ভার ত্রুটি ঘটেছে।"),
        OFFLINE("Offline mode: POS sale queued locally pending sync.", "অফলাইন মোড: সিঙ্কের জন্য লোকাল কিউতে সংরক্ষিত হয়েছে।"),
        PERMISSION_DENIED("Permission denied for this POS operation.", "এই পিওএস কাজটি করার অনুমতি আপনার নেই।");

        public final String defaultMessageEn;
        public final String defaultMessageBn;

        UiState(String defaultMessageEn, String defaultMessageBn) {
            this.defaultMessageEn = defaultMessageEn;
            this.defaultMessageBn = defaultMessageBn;
        }
    }

    public enum LifecycleState {
        DRAFT,
        HELD,
        PENDING_RX_REVIEW,
        CONFIRMED,
        PARTIALLY_PAID,
        FINALIZED,
        PARTIALLY_RETURNED,
        FULLY_RETURNED,
        VOIDED,
        REVERSED,
        OFFLINE_PENDING_SYNC,
        SYNC_CONFLICT_REVIEW;

        public static LifecycleState fromApi(String value) {
            if (value == null || value.isBlank()) return DRAFT;
            try {
                return LifecycleState.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return DRAFT;
            }
        }

        public boolean isMutable() {
            return this == DRAFT || this == HELD || this == PENDING_RX_REVIEW;
        }

        public boolean canTransitionTo(LifecycleState target) {
            return switch (this) {
                case DRAFT -> Set.of(HELD, PENDING_RX_REVIEW, CONFIRMED, PARTIALLY_PAID, FINALIZED, VOIDED, OFFLINE_PENDING_SYNC).contains(target);
                case HELD -> Set.of(DRAFT, PENDING_RX_REVIEW, CONFIRMED, PARTIALLY_PAID, FINALIZED, VOIDED).contains(target);
                case PENDING_RX_REVIEW -> Set.of(CONFIRMED, PARTIALLY_PAID, VOIDED).contains(target);
                case CONFIRMED -> Set.of(PARTIALLY_PAID, FINALIZED, VOIDED).contains(target);
                case PARTIALLY_PAID -> Set.of(FINALIZED, VOIDED).contains(target);
                case FINALIZED -> Set.of(PARTIALLY_RETURNED, FULLY_RETURNED, REVERSED).contains(target);
                case PARTIALLY_RETURNED -> Set.of(FULLY_RETURNED, REVERSED).contains(target);
                case OFFLINE_PENDING_SYNC -> Set.of(FINALIZED, SYNC_CONFLICT_REVIEW).contains(target);
                case SYNC_CONFLICT_REVIEW -> Set.of(FINALIZED, REVERSED, VOIDED).contains(target);
                case FULLY_RETURNED, VOIDED, REVERSED -> false;
            };
        }
    }

    public enum PaymentTender {
        CASH, BKASH, NAGAD, CARD, DUE
    }

    @FunctionalInterface
    public interface ApiTransport {
        ApiResponseResult send(String method, String path, String jsonBody, String bearerToken) throws Exception;
    }

    public static final class ApiResponseResult {
        public final int statusCode;
        public final String body;

        public ApiResponseResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body != null ? body : "";
        }
    }

    public static final class ClientOutcome<T> {
        public final UiState uiState;
        public final T data;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public ClientOutcome(UiState uiState, T data, String errorCode, String messageEn, String messageBn) {
            this.uiState = uiState;
            this.data = data;
            this.errorCode = errorCode != null ? errorCode : "";
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }
    }

    public static final class PosCustomerDto {
        public String customerId = "";
        public String tenantId = "";
        public String primaryBranchId = "";
        public String fullName = "";
        public String phone = "";
        public BigDecimal creditLimitBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal currentDueBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal availableCreditBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public boolean isCreditApproved = false;
        public String creditStatus = "UNAPPROVED";
        public int creditTermsDays = 30;
        public String creditSuspendedReason = "";
        public boolean isArchived = false;

        public static PosCustomerDto fromMap(Map<?, ?> map) {
            PosCustomerDto dto = new PosCustomerDto();
            if (map == null) return dto;
            dto.customerId = str(map.get("customer_id"));
            dto.tenantId = str(map.get("tenant_id"));
            dto.primaryBranchId = str(map.get("primary_branch"));
            dto.fullName = str(map.get("full_name"));
            dto.phone = str(map.get("phone"));
            dto.creditLimitBdt = dec2(map.get("credit_limit_bdt"));
            dto.currentDueBdt = dec2(map.get("current_due_bdt"));
            dto.isCreditApproved = Boolean.TRUE.equals(map.get("is_credit_approved"));
            dto.creditStatus = strOr(map.get("credit_status"), dto.isCreditApproved ? "APPROVED" : "UNAPPROVED");
            dto.creditTermsDays = integer(map.get("credit_terms_days"), 30);
            dto.creditSuspendedReason = str(map.get("credit_suspended_reason"));
            if (map.get("available_credit_bdt") != null) {
                dto.availableCreditBdt = dec2(map.get("available_credit_bdt"));
            } else if (dto.isCreditApproved && "APPROVED".equalsIgnoreCase(dto.creditStatus)) {
                dto.availableCreditBdt = dto.creditLimitBdt.subtract(dto.currentDueBdt)
                        .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            }
            dto.isArchived = Boolean.TRUE.equals(map.get("is_archived"));
            return dto;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("customer_id", customerId);
            out.put("tenant_id", tenantId);
            out.put("primary_branch", primaryBranchId);
            out.put("full_name", fullName);
            out.put("phone", phone);
            out.put("credit_limit_bdt", creditLimitBdt.toPlainString());
            out.put("current_due_bdt", currentDueBdt.toPlainString());
            out.put("available_credit_bdt", availableCreditBdt.toPlainString());
            out.put("is_credit_approved", isCreditApproved);
            out.put("credit_status", creditStatus);
            out.put("credit_terms_days", creditTermsDays);
            out.put("is_archived", isArchived);
            return out;
        }
    }

    public static final class CustomerDueCollectionDto {
        public String collectionId = "";
        public String dueRecordId = "";
        public String customerId = "";
        public String saleId = "";
        public String journalEntryId = "";
        public String debitAccount = "";
        public String creditAccount = "AR_CUSTOMER_DUE";
        public BigDecimal revenueImpactBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public String tenderType = "CASH";
        public BigDecimal amountBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal cashTenderedBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal changeGivenBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public String gatewayReference = "";
        public String verificationStatus = "NOT_REQUIRED";
        public String status = "CAPTURED";
        public String notes = "";
        public String collectedAt = "";

        public static CustomerDueCollectionDto fromMap(Map<?, ?> map) {
            CustomerDueCollectionDto dto = new CustomerDueCollectionDto();
            if (map == null) return dto;
            dto.collectionId = str(map.get("collection_id"));
            dto.dueRecordId = str(map.get("due_record_id"));
            dto.customerId = str(map.get("customer_id"));
            dto.saleId = str(map.get("sale_id"));
            dto.journalEntryId = str(map.get("journal_entry_id"));
            dto.debitAccount = str(map.get("debit_account"));
            dto.creditAccount = strOr(map.get("credit_account"), "AR_CUSTOMER_DUE");
            dto.revenueImpactBdt = dec2(map.get("revenue_impact_bdt"));
            dto.tenderType = strOr(map.get("tender_type"), "CASH");
            dto.amountBdt = dec2(map.get("amount_bdt"));
            dto.cashTenderedBdt = dec2(map.get("cash_tendered_bdt"));
            dto.changeGivenBdt = dec2(map.get("change_given_bdt"));
            dto.gatewayReference = str(map.get("gateway_reference"));
            dto.verificationStatus = strOr(map.get("verification_status"), "NOT_REQUIRED");
            dto.status = strOr(map.get("status"), "CAPTURED");
            dto.notes = str(map.get("notes"));
            dto.collectedAt = str(map.get("collected_at"));
            return dto;
        }
    }

    public static final class CustomerDueRecordDto {
        public String dueId = "";
        public String tenantId = "";
        public String branchId = "";
        public String branchName = "";
        public String customerId = "";
        public String saleId = "";
        public String orderNumber = "";
        public String invoiceId = "";
        public String invoiceNumber = "";
        public String customerName = "";
        public String customerPhone = "";
        public BigDecimal totalDueBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal paidAmountBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal remainingDueBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public String status = "OUTSTANDING";
        public String dueDate = "";
        public String notes = "";
        public List<CustomerDueCollectionDto> collections = new ArrayList<>();

        public static CustomerDueRecordDto fromMap(Map<?, ?> map) {
            CustomerDueRecordDto dto = new CustomerDueRecordDto();
            if (map == null) return dto;
            dto.dueId = str(map.get("due_id"));
            dto.tenantId = str(map.get("tenant_id"));
            dto.branchId = str(map.get("branch_id"));
            dto.branchName = str(map.get("branch_name"));
            dto.customerId = str(map.get("customer_id"));
            dto.saleId = str(map.get("sale_id"));
            dto.orderNumber = str(map.get("order_number"));
            dto.invoiceId = str(map.get("invoice_id"));
            dto.invoiceNumber = str(map.get("invoice_number"));
            dto.customerName = str(map.get("customer_name"));
            dto.customerPhone = str(map.get("customer_phone"));
            dto.totalDueBdt = dec2(map.get("total_due_bdt"));
            dto.paidAmountBdt = dec2(map.get("paid_amount_bdt"));
            dto.remainingDueBdt = dec2(map.get("remaining_due_bdt"));
            dto.status = strOr(map.get("status"), "OUTSTANDING");
            dto.dueDate = str(map.get("due_date"));
            dto.notes = str(map.get("notes"));
            if (map.get("collections") instanceof List<?> rawCols) {
                for (Object item : rawCols) {
                    if (item instanceof Map<?, ?> cm) {
                        dto.collections.add(CustomerDueCollectionDto.fromMap(cm));
                    }
                }
            }
            return dto;
        }
    }

    public static final class DueCollectionOutcomeDto {
        public boolean idempotentReplay = false;
        public CustomerDueCollectionDto collection = new CustomerDueCollectionDto();
        public CustomerDueRecordDto dueRecord = new CustomerDueRecordDto();
        public BigDecimal revenueImpactBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        public static DueCollectionOutcomeDto fromMap(Map<?, ?> map) {
            DueCollectionOutcomeDto dto = new DueCollectionOutcomeDto();
            if (map == null) return dto;
            dto.idempotentReplay = Boolean.TRUE.equals(map.get("idempotent_replay"));
            if (map.get("collection") instanceof Map<?, ?> cm) {
                dto.collection = CustomerDueCollectionDto.fromMap(cm);
            }
            if (map.get("due_record") instanceof Map<?, ?> dm) {
                dto.dueRecord = CustomerDueRecordDto.fromMap(dm);
            }
            dto.revenueImpactBdt = dec2(map.get("revenue_impact_bdt"));
            return dto;
        }
    }

    public static final class PosSaleLineDto {
        public String lineId = "";
        public int lineNumber = 1;
        public String productId = "";
        public String productBrandName = "";
        public String productGenericName = "";
        public String preferredBatchId = "";
        public String preferredBatchNumber = "";
        public String packagingUnit = "TABLET";
        public int quantityUnits = 1;
        public int conversionFactor = 1;
        public int baseUnitsTotal = 1;
        public int returnedBaseUnits = 0;
        public String dosageFormSnapshot = "";
        public String containerPolicyApplied = "STANDARD";
        public String unitConversionSource = "";
        public String containerOverrideApprovedByEmail = "";
        public String containerOverrideReason = "";
        public BigDecimal unitPriceBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public String priceSource = "CANONICAL_MRP";
        public String priceOverrideApprovedByEmail = "";
        public String priceOverrideReason = "";
        public String discountType = "NONE";
        public BigDecimal discountRatePct = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal discountAmountBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public String taxMode = "EXCLUSIVE";
        public BigDecimal taxRatePct = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal netBeforeTaxBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal lineSubtotalBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal lineTaxAmountBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal lineTotalBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal unitCostBdt = null; // Masked/null for Cashier
        public boolean requiresRxVerification = false;
        public boolean rxVerified = false;
        public String regulatoryClassification = "OTC";
        public int classificationVersionSnapshot = 1;
        public int rxVerifiedBaseUnits = 0;

        public static PosSaleLineDto fromMap(Map<?, ?> map) {
            PosSaleLineDto dto = new PosSaleLineDto();
            if (map == null) return dto;
            dto.lineId = str(map.get("line_id"));
            dto.lineNumber = integer(map.get("line_number"), 1);
            dto.productId = str(map.get("product_id"));
            if (dto.productId.isEmpty()) dto.productId = str(map.get("product"));
            dto.productBrandName = str(map.get("product_brand_name"));
            dto.productGenericName = str(map.get("product_generic_name"));
            dto.preferredBatchId = str(map.get("preferred_batch_id"));
            dto.preferredBatchNumber = str(map.get("preferred_batch_number"));
            dto.packagingUnit = strOr(map.get("packaging_unit"), "TABLET");
            dto.quantityUnits = integer(map.get("quantity_units"), 1);
            dto.conversionFactor = integer(map.get("conversion_factor"), 1);
            dto.baseUnitsTotal = integer(map.get("base_units_total"), dto.quantityUnits * dto.conversionFactor);
            dto.returnedBaseUnits = integer(map.get("returned_base_units"), 0);
            dto.dosageFormSnapshot = str(map.get("dosage_form_snapshot"));
            dto.containerPolicyApplied = strOr(map.get("container_policy_applied"), "STANDARD");
            dto.unitConversionSource = str(map.get("unit_conversion_source"));
            dto.containerOverrideApprovedByEmail = str(map.get("container_override_approved_by_email"));
            dto.containerOverrideReason = str(map.get("container_override_reason"));
            dto.unitPriceBdt = dec2(map.get("unit_price_bdt"));
            dto.priceSource = strOr(map.get("price_source"), "CANONICAL_MRP");
            dto.priceOverrideApprovedByEmail = str(map.get("price_override_approved_by_email"));
            dto.priceOverrideReason = str(map.get("price_override_reason"));
            dto.discountType = strOr(map.get("discount_type"), "NONE");
            dto.discountRatePct = dec2(map.get("discount_rate_pct"));
            dto.discountAmountBdt = dec2(map.get("discount_amount_bdt"));
            dto.taxMode = strOr(map.get("tax_mode"), "EXCLUSIVE");
            dto.taxRatePct = dec2(map.get("tax_rate_pct"));
            dto.netBeforeTaxBdt = dec2(map.get("net_before_tax_bdt"));
            dto.lineSubtotalBdt = dec2(map.get("line_subtotal_bdt"));
            dto.lineTaxAmountBdt = dec2(map.get("line_tax_amount_bdt"));
            dto.lineTotalBdt = dec2(map.get("line_total_bdt"));
            dto.unitCostBdt = map.get("unit_cost_bdt") != null ? dec4(map.get("unit_cost_bdt")) : null;
            dto.requiresRxVerification = Boolean.TRUE.equals(map.get("requires_rx_verification"));
            dto.rxVerified = Boolean.TRUE.equals(map.get("rx_verified"));
            dto.regulatoryClassification = strOr(
                    map.get("regulatory_classification"),
                    dto.requiresRxVerification ? "PRESCRIPTION_ONLY" : "OTC"
            );
            dto.classificationVersionSnapshot = integer(map.get("classification_version_snapshot"), 1);
            dto.rxVerifiedBaseUnits = integer(map.get("rx_verified_base_units"), 0);
            return dto;
        }

        public Map<String, Object> toCreateMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("line_number", lineNumber);
            out.put("product", productId);
            if (!preferredBatchId.isEmpty()) out.put("preferred_batch", preferredBatchId);
            out.put("packaging_unit", packagingUnit);
            out.put("quantity_units", quantityUnits);
            out.put("conversion_factor", conversionFactor);
            out.put("unit_price_bdt", unitPriceBdt.toPlainString());
            out.put("discount_amount_bdt", discountAmountBdt.toPlainString());
            out.put("tax_rate_pct", taxRatePct.toPlainString());
            return out;
        }
    }

    public static final class PosSaleAllocationDto {
        public String allocationId = "";
        public String saleLineId = "";
        public String productId = "";
        public String batchId = "";
        public String batchNumber = "";
        public String expiryDate = "";
        public int fefoSequence = 1;
        public String allocationMode = "FEFO_AUTO";
        public String overrideReason = "";
        public String overrideApprovedByEmail = "";
        public int allocatedBaseUnits = 0;
        public int returnedBaseUnits = 0;
        public BigDecimal unitCostBdt = null;
        public BigDecimal totalCostBdt = null;
        public String status = "RESERVED";

        public static PosSaleAllocationDto fromMap(Map<?, ?> map) {
            PosSaleAllocationDto dto = new PosSaleAllocationDto();
            if (map == null) return dto;
            dto.allocationId = str(map.get("allocation_id"));
            dto.saleLineId = str(map.get("sale_line_id"));
            dto.productId = str(map.get("product_id"));
            dto.batchId = str(map.get("batch_id"));
            dto.batchNumber = str(map.get("batch_number"));
            dto.expiryDate = strOr(map.get("expiry_date_snapshot"), str(map.get("expiry_date")));
            dto.fefoSequence = integer(map.get("fefo_sequence"), 1);
            dto.allocationMode = strOr(map.get("allocation_mode"), "FEFO_AUTO");
            dto.overrideReason = str(map.get("override_reason"));
            dto.overrideApprovedByEmail = str(map.get("override_approved_by_email"));
            dto.allocatedBaseUnits = integer(map.get("allocated_base_units"), 0);
            dto.returnedBaseUnits = integer(map.get("returned_base_units"), 0);
            dto.unitCostBdt = map.get("unit_cost_bdt") != null ? dec4(map.get("unit_cost_bdt")) : null;
            dto.totalCostBdt = map.get("total_cost_bdt") != null ? dec4(map.get("total_cost_bdt")) : null;
            dto.status = strOr(map.get("status"), "RESERVED");
            return dto;
        }
    }

    public static final class PosPaymentAttemptDto {
        public String attemptId = "";
        public int attemptNumber = 1;
        public String tenderType = "CASH";
        public BigDecimal amountBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal changeGivenBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal netSettledAmountBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public String status = "CAPTURED";
        public String verificationStatus = "NOT_REQUIRED";
        public String verificationChannel = "";
        public String idempotencyKey = "";
        public String gatewayReference = "";
        public String payerReference = "";
        public String cardLast4 = "";
        public String voidReason = "";

        public static PosPaymentAttemptDto fromMap(Map<?, ?> map) {
            PosPaymentAttemptDto dto = new PosPaymentAttemptDto();
            if (map == null) return dto;
            dto.attemptId = str(map.get("attempt_id"));
            dto.attemptNumber = integer(map.get("attempt_number"), 1);
            dto.tenderType = strOr(map.get("tender_type"), "CASH");
            dto.amountBdt = dec2(map.get("amount_bdt"));
            dto.changeGivenBdt = dec2(map.get("change_given_bdt"));
            dto.netSettledAmountBdt = map.get("net_settled_amount_bdt") != null
                    ? dec2(map.get("net_settled_amount_bdt")) : dto.amountBdt;
            dto.status = strOr(map.get("status"), "CAPTURED");
            dto.verificationStatus = strOr(map.get("verification_status"), "NOT_REQUIRED");
            dto.verificationChannel = str(map.get("verification_channel"));
            dto.idempotencyKey = str(map.get("idempotency_key"));
            dto.gatewayReference = str(map.get("gateway_reference"));
            dto.payerReference = str(map.get("payer_reference"));
            dto.cardLast4 = str(map.get("card_last4"));
            dto.voidReason = str(map.get("void_reason"));
            return dto;
        }
    }

    public static final class SplitPaymentInputDto {
        public String tenderType = "CASH";
        public BigDecimal amountBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public String gatewayReference = "";
        public String payerReference = "";
        public String cardLast4 = "";
        public String verificationStatus = "";
        public String idempotencyKey = "";

        public SplitPaymentInputDto() {}

        public SplitPaymentInputDto(String tenderType, BigDecimal amountBdt, String gatewayReference, String payerReference) {
            this.tenderType = tenderType != null ? tenderType.trim().toUpperCase(Locale.ROOT) : "CASH";
            this.amountBdt = amountBdt != null ? amountBdt.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            this.gatewayReference = gatewayReference != null ? gatewayReference.trim() : "";
            this.payerReference = payerReference != null ? payerReference.trim() : "";
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tender_type", tenderType);
            m.put("amount_bdt", amountBdt.setScale(2, RoundingMode.HALF_UP).toPlainString());
            if (!gatewayReference.isBlank()) m.put("gateway_reference", gatewayReference);
            if (!payerReference.isBlank()) m.put("payer_reference", payerReference);
            if (!cardLast4.isBlank()) m.put("card_last4", cardLast4);
            if (!verificationStatus.isBlank()) m.put("verification_status", verificationStatus);
            if (!idempotencyKey.isBlank()) m.put("idempotency_key", idempotencyKey);
            return m;
        }
    }

    public static final class CashierCloseReconciliationDto {
        public String reconciliationId = "";
        public String branchId = "";
        public String branchName = "";
        public String registerAssignmentId = "";
        public BigDecimal openingFloatBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal grossCashTenderedBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal cashChangeReturnedBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal netCashCollectedBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal cashRefundedBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal expectedDrawerCashBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal countedCashBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal varianceBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal digitalTotalBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal dueIssuedBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal dueCollectedCashBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal dueCollectedDigitalBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public int salesCount = 0;
        public int reversedSalesCount = 0;
        public boolean journalBalanced = true;
        public String status = "BALANCED";
        public Map<String, BigDecimal> netByTender = new LinkedHashMap<>();

        public static CashierCloseReconciliationDto fromMap(Map<?, ?> map) {
            CashierCloseReconciliationDto dto = new CashierCloseReconciliationDto();
            if (map == null) return dto;
            dto.reconciliationId = str(map.get("reconciliation_id"));
            dto.branchId = str(map.get("branch_id"));
            dto.branchName = str(map.get("branch_name"));
            dto.registerAssignmentId = str(map.get("register_assignment_id"));
            dto.openingFloatBdt = dec2(map.get("opening_float_bdt"));
            dto.grossCashTenderedBdt = dec2(map.get("gross_cash_tendered_bdt"));
            dto.cashChangeReturnedBdt = dec2(map.get("cash_change_returned_bdt"));
            dto.netCashCollectedBdt = dec2(map.get("net_cash_collected_bdt"));
            dto.cashRefundedBdt = dec2(map.get("cash_refunded_bdt"));
            dto.expectedDrawerCashBdt = dec2(map.get("expected_drawer_cash_bdt"));
            dto.countedCashBdt = dec2(map.get("counted_cash_bdt"));
            dto.varianceBdt = dec2(map.get("variance_bdt"));
            dto.digitalTotalBdt = dec2(map.get("digital_total_bdt"));
            dto.dueIssuedBdt = dec2(map.get("due_issued_bdt"));
            dto.dueCollectedCashBdt = dec2(map.get("due_collected_cash_bdt"));
            dto.dueCollectedDigitalBdt = dec2(map.get("due_collected_digital_bdt"));
            dto.salesCount = integer(map.get("sales_count"), 0);
            dto.reversedSalesCount = integer(map.get("reversed_sales_count"), 0);
            dto.journalBalanced = !map.containsKey("journal_balanced") || Boolean.TRUE.equals(map.get("journal_balanced"));
            dto.status = strOr(map.get("status"), "BALANCED");
            Object tt = map.get("tender_totals") instanceof Map<?, ?> ? map.get("tender_totals") : map.get("tender_totals_json");
            if (tt instanceof Map<?, ?> tMap) {
                for (Map.Entry<?, ?> e : tMap.entrySet()) {
                    String k = str(e.getKey());
                    if (e.getValue() instanceof Map<?, ?> sub) {
                        dto.netByTender.put(k, dec2(sub.get("net_bdt")));
                    }
                }
            }
            return dto;
        }
    }

    public static final class PosSaleOrderDto {
        public String saleId = "";
        public String tenantId = "";
        public String branchId = "";
        public String orderNumber = "";
        public String idempotencyKey = "";
        public String offlineDeviceNamespace = "";
        public long offlineLocalSequence = 0L;
        public String cashierUserId = "";
        public String customerId = "";
        public String customerName = "Walk-in Customer";
        public String customerPhone = "";
        public String linkedInvoiceId = "";
        public LifecycleState lifecycleState = LifecycleState.DRAFT;
        public String syncStatus = "CLOUD_CONFIRMED";
        public String discountType = "NONE";
        public BigDecimal discountRatePct = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal subtotalBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal lineDiscountTotalBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal headerDiscountAmountBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal discountAmountBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal netBeforeTaxBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public String taxMode = "EXCLUSIVE";
        public BigDecimal taxRatePct = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal taxAmountBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public String roundingMode = "NONE";
        public BigDecimal roundingAdjustmentBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public String fulfillmentMode = "COUNTER_WALKIN";
        public BigDecimal deliveryFeeBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal grandTotalBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal paidAmountBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal dueAmountBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal changeReturnedBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public boolean requiresRxVerification = false;
        public String rxGateStatus = "NOT_REQUIRED";
        public String rxDecisionReason = "";
        public String rxReferenceCode = "";
        public String pharmacistPcbNoSnapshot = "";
        public String rxOfflineBlockedReason = "";
        public String rxSafetyPolicyVersion = "DGDA-POLICY-v2026.1";
        public String fefoAllocationStatus = "PENDING";
        public String rxVerifiedByEmail = "";
        public String rxVerifiedAt = "";
        public String prescriptionId = "";
        public String pricingConfirmedAt = "";
        public String pricingConfirmationHash = "";
        public boolean isPricingConfirmed = false;
        public String currentPricingHash = "";
        public String holdReference = "";
        public String holdNote = "";
        public String heldAt = "";
        public String heldByEmail = "";
        public String holdExpiresAt = "";
        public boolean holdStockReserved = false;
        public String holdReservationExpiresAt = "";
        public String holdSnapshotPricingHash = "";
        public BigDecimal holdSnapshotGrandTotalBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public String resumedAt = "";
        public String resumedByEmail = "";
        public StalenessDiagnosticsDto staleness = new StalenessDiagnosticsDto();
        public int version = 1;
        public List<PosSaleLineDto> lines = new ArrayList<>();
        public List<PosSaleAllocationDto> allocations = new ArrayList<>();
        public List<PosPaymentAttemptDto> paymentAttempts = new ArrayList<>();

        public static PosSaleOrderDto fromMap(Map<?, ?> map) {
            PosSaleOrderDto dto = new PosSaleOrderDto();
            if (map == null) return dto;
            dto.saleId = str(map.get("sale_id"));
            dto.tenantId = str(map.get("tenant_id"));
            dto.branchId = str(map.get("branch_id"));
            dto.orderNumber = str(map.get("order_number"));
            dto.idempotencyKey = str(map.get("idempotency_key"));
            dto.offlineDeviceNamespace = str(map.get("offline_device_namespace"));
            if (map.get("offline_local_sequence") instanceof Number n) {
                dto.offlineLocalSequence = n.longValue();
            }
            dto.cashierUserId = str(map.get("cashier_user_id"));
            dto.customerId = str(map.get("customer_id"));
            dto.customerName = strOr(map.get("customer_name"), "Walk-in Customer");
            dto.customerPhone = str(map.get("customer_phone"));
            dto.linkedInvoiceId = str(map.get("linked_invoice_id"));
            dto.lifecycleState = LifecycleState.fromApi(str(map.get("lifecycle_state")));
            dto.syncStatus = strOr(map.get("sync_status"), "CLOUD_CONFIRMED");
            dto.discountType = strOr(map.get("discount_type"), "NONE");
            dto.discountRatePct = dec2(map.get("discount_rate_pct"));
            dto.subtotalBdt = dec2(map.get("subtotal_bdt"));
            dto.lineDiscountTotalBdt = dec2(map.get("line_discount_total_bdt"));
            dto.headerDiscountAmountBdt = dec2(map.get("header_discount_amount_bdt"));
            dto.discountAmountBdt = dec2(map.get("discount_amount_bdt"));
            dto.netBeforeTaxBdt = dec2(map.get("net_before_tax_bdt"));
            dto.taxMode = strOr(map.get("tax_mode"), "EXCLUSIVE");
            dto.taxRatePct = dec2(map.get("tax_rate_pct"));
            dto.taxAmountBdt = dec2(map.get("tax_amount_bdt"));
            dto.roundingMode = strOr(map.get("rounding_mode"), "NONE");
            dto.roundingAdjustmentBdt = dec2(map.get("rounding_adjustment_bdt"));
            dto.fulfillmentMode = strOr(map.get("fulfillment_mode"), "COUNTER_WALKIN");
            dto.deliveryFeeBdt = dec2(map.get("delivery_fee_bdt"));
            dto.grandTotalBdt = dec2(map.get("grand_total_bdt"));
            dto.paidAmountBdt = dec2(map.get("paid_amount_bdt"));
            dto.dueAmountBdt = dec2(map.get("due_amount_bdt"));
            dto.changeReturnedBdt = dec2(map.get("change_returned_bdt"));
            dto.requiresRxVerification = Boolean.TRUE.equals(map.get("requires_rx_verification"));
            dto.rxGateStatus = strOr(
                    map.get("rx_gate_status"),
                    dto.requiresRxVerification ? "PENDING_PHARMACIST" : "NOT_REQUIRED"
            );
            dto.rxDecisionReason = str(map.get("rx_decision_reason"));
            dto.rxReferenceCode = str(map.get("rx_reference_code"));
            dto.pharmacistPcbNoSnapshot = str(map.get("pharmacist_pcb_no_snapshot"));
            dto.rxOfflineBlockedReason = str(map.get("rx_offline_blocked_reason"));
            dto.rxSafetyPolicyVersion = strOr(map.get("rx_safety_policy_version"), "DGDA-POLICY-v2026.1");
            dto.fefoAllocationStatus = strOr(map.get("fefo_allocation_status"), "PENDING");
            dto.rxVerifiedByEmail = str(map.get("rx_verified_by_email"));
            dto.rxVerifiedAt = str(map.get("rx_verified_at"));
            dto.prescriptionId = str(map.get("prescription_id"));
            dto.pricingConfirmedAt = str(map.get("pricing_confirmed_at"));
            dto.pricingConfirmationHash = str(map.get("pricing_confirmation_hash"));
            dto.isPricingConfirmed = Boolean.TRUE.equals(map.get("is_pricing_confirmed"));
            dto.currentPricingHash = str(map.get("current_pricing_hash"));
            dto.holdReference = str(map.get("hold_reference"));
            dto.holdNote = str(map.get("hold_note"));
            dto.heldAt = str(map.get("held_at"));
            dto.heldByEmail = str(map.get("held_by_email"));
            dto.holdExpiresAt = str(map.get("hold_expires_at"));
            dto.holdStockReserved = Boolean.TRUE.equals(map.get("hold_stock_reserved"));
            dto.holdReservationExpiresAt = str(map.get("hold_reservation_expires_at"));
            dto.holdSnapshotPricingHash = str(map.get("hold_snapshot_pricing_hash"));
            dto.holdSnapshotGrandTotalBdt = map.get("hold_snapshot_grand_total_bdt") != null
                    ? dec2(map.get("hold_snapshot_grand_total_bdt"))
                    : dto.grandTotalBdt;
            dto.resumedAt = str(map.get("resumed_at"));
            dto.resumedByEmail = str(map.get("resumed_by_email"));
            if (map.get("staleness") instanceof Map<?, ?> sm) {
                dto.staleness = StalenessDiagnosticsDto.fromMap(sm);
            }
            dto.version = integer(map.get("version"), 1);

            if (map.get("lines") instanceof List<?> rawLines) {
                for (Object o : rawLines) {
                    if (o instanceof Map<?, ?> lm) dto.lines.add(PosSaleLineDto.fromMap(lm));
                }
            }
            if (map.get("allocations") instanceof List<?> rawAllocs) {
                for (Object o : rawAllocs) {
                    if (o instanceof Map<?, ?> am) dto.allocations.add(PosSaleAllocationDto.fromMap(am));
                }
            }
            if (map.get("payment_attempts") instanceof List<?> rawPays) {
                for (Object o : rawPays) {
                    if (o instanceof Map<?, ?> pm) dto.paymentAttempts.add(PosPaymentAttemptDto.fromMap(pm));
                }
            }
            return dto;
        }

        public SaleTransaction bridgeToLegacySaleTransaction() {
            List<CartItem> cartItems = new ArrayList<>();
            for (PosSaleLineDto line : lines) {
                String unit = switch (line.packagingUnit.toUpperCase(Locale.ROOT)) {
                    case "BOX", "STRIP", "TABLET" -> line.packagingUnit.toUpperCase(Locale.ROOT);
                    default -> "TABLET";
                };
                BigDecimal vatFraction = line.taxRatePct.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
                cartItems.add(new CartItem(
                        line.productId.isEmpty() ? "med-unknown" : line.productId,
                        line.productBrandName.isEmpty() ? "Medicine" : line.productBrandName,
                        line.productGenericName.isEmpty() ? "Generic" : line.productGenericName,
                        unit,
                        Math.max(1, line.quantityUnits),
                        line.unitPriceBdt.max(new BigDecimal("0.01")),
                        line.preferredBatchNumber.isEmpty() ? "FEFO-AUTO" : line.preferredBatchNumber,
                        LocalDate.now().plusYears(1),
                        vatFraction
                ));
            }
            boolean offlinePending = "LOCAL_PENDING_SYNC".equalsIgnoreCase(syncStatus)
                    || lifecycleState == LifecycleState.OFFLINE_PENDING_SYNC;
            return new SaleTransaction(
                    saleId.isEmpty() ? UUID.randomUUID().toString() : saleId,
                    orderNumber.isEmpty() ? "POS-DRAFT" : orderNumber,
                    tenantId.isEmpty() ? "ten-default" : tenantId,
                    branchId.isEmpty() ? "br-default" : branchId,
                    cashierUserId.isEmpty() ? "cashier" : cashierUserId,
                    cartItems,
                    discountAmountBdt,
                    offlinePending,
                    Instant.now()
            );
        }
    }

    private static final Set<String> TENANT_WIDE_ROLES = Set.of("SUPERADMIN", "PHARMACY_OWNER", "FINANCE_OFFICER", "AUDITOR");
    private static final Set<String> POS_WRITE_ROLES = Set.of("SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "STAFF_PHARMACIST", "CASHIER");
    private static final Set<String> POS_READ_ROLES = Set.of("SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "STAFF_PHARMACIST", "CASHIER", "FINANCE_OFFICER", "AUDITOR");
    private static final Set<String> POS_ELEVATED_ROLES = Set.of("SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "STAFF_PHARMACIST");
    private static final Set<String> POS_CUSTOMER_ADMIN_ROLES = Set.of("SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER");

    public static final class PriceDriftItemDto {
        public String lineId = "";
        public String productId = "";
        public String productBrandName = "";
        public String packagingUnit = "";
        public BigDecimal heldUnitPriceBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal liveUnitPriceBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        public static PriceDriftItemDto fromMap(Map<?, ?> map) {
            PriceDriftItemDto dto = new PriceDriftItemDto();
            if (map == null) return dto;
            dto.lineId = str(map.get("line_id"));
            dto.productId = str(map.get("product_id"));
            dto.productBrandName = str(map.get("product_brand_name"));
            dto.packagingUnit = str(map.get("packaging_unit"));
            dto.heldUnitPriceBdt = dec2(map.get("held_unit_price_bdt"));
            dto.liveUnitPriceBdt = dec2(map.get("live_unit_price_bdt"));
            return dto;
        }
    }

    public static final class ShortfallItemDto {
        public String lineId = "";
        public String productId = "";
        public String productBrandName = "";
        public int requiredBaseUnits = 0;
        public int sellableAvailableBaseUnits = 0;
        public int shortfallBaseUnits = 0;
        public boolean unsafeBatchDetected = false;

        public static ShortfallItemDto fromMap(Map<?, ?> map) {
            ShortfallItemDto dto = new ShortfallItemDto();
            if (map == null) return dto;
            dto.lineId = str(map.get("line_id"));
            dto.productId = str(map.get("product_id"));
            dto.productBrandName = str(map.get("product_brand_name"));
            dto.requiredBaseUnits = integer(map.get("required_base_units"), 0);
            dto.sellableAvailableBaseUnits = integer(map.get("sellable_available_base_units"), 0);
            dto.shortfallBaseUnits = integer(map.get("shortfall_base_units"), 0);
            dto.unsafeBatchDetected = Boolean.TRUE.equals(map.get("unsafe_batch_detected"));
            return dto;
        }
    }

    public static final class StalenessDiagnosticsDto {
        public boolean isStale = false;
        public boolean isHoldExpired = false;
        public boolean isReservationExpired = false;
        public boolean hasPriceChange = false;
        public boolean hasStockShortage = false;
        public boolean hasUnsafeBatch = false;
        public List<String> staleReasons = new ArrayList<>();
        public BigDecimal heldGrandTotalBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal liveGrandTotalBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal priceDeltaBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public String heldPricingHash = "";
        public String livePricingHash = "";
        public List<PriceDriftItemDto> priceDriftItems = new ArrayList<>();
        public List<ShortfallItemDto> shortfallItems = new ArrayList<>();
        public String userPromptEn = "";
        public String userPromptBn = "";

        public static StalenessDiagnosticsDto fromMap(Map<?, ?> map) {
            StalenessDiagnosticsDto dto = new StalenessDiagnosticsDto();
            if (map == null) return dto;
            dto.isStale = Boolean.TRUE.equals(map.get("is_stale"));
            dto.isHoldExpired = Boolean.TRUE.equals(map.get("is_hold_expired"));
            dto.isReservationExpired = Boolean.TRUE.equals(map.get("is_reservation_expired"));
            dto.hasPriceChange = Boolean.TRUE.equals(map.get("has_price_change"));
            dto.hasStockShortage = Boolean.TRUE.equals(map.get("has_stock_shortage"));
            dto.hasUnsafeBatch = Boolean.TRUE.equals(map.get("has_unsafe_batch"));
            if (map.get("stale_reasons") instanceof List<?> rawReasons) {
                for (Object r : rawReasons) {
                    if (r != null && !r.toString().isBlank()) dto.staleReasons.add(r.toString());
                }
            }
            dto.heldGrandTotalBdt = dec2(map.get("held_grand_total_bdt"));
            dto.liveGrandTotalBdt = dec2(map.get("live_grand_total_bdt"));
            dto.priceDeltaBdt = dec2(map.get("price_delta_bdt"));
            dto.heldPricingHash = str(map.get("held_pricing_hash"));
            dto.livePricingHash = str(map.get("live_pricing_hash"));
            if (map.get("price_drift_items") instanceof List<?> rawDrift) {
                for (Object item : rawDrift) {
                    if (item instanceof Map<?, ?> m) dto.priceDriftItems.add(PriceDriftItemDto.fromMap(m));
                }
            }
            if (map.get("shortfall_items") instanceof List<?> rawShort) {
                for (Object item : rawShort) {
                    if (item instanceof Map<?, ?> m) dto.shortfallItems.add(ShortfallItemDto.fromMap(m));
                }
            }
            dto.userPromptEn = str(map.get("user_prompt_en"));
            dto.userPromptBn = str(map.get("user_prompt_bn"));
            return dto;
        }
    }

    public static final class HeldCartEntryDto {
        public String saleId = "";
        public String orderNumber = "";
        public String branchId = "";
        public String branchName = "";
        public String customerName = "Walk-in Customer";
        public String customerPhone = "";
        public LifecycleState lifecycleState = LifecycleState.HELD;
        public String holdReference = "";
        public String holdNote = "";
        public String heldByEmail = "";
        public String heldAt = "";
        public String holdExpiresAt = "";
        public boolean holdStockReserved = false;
        public String holdReservationExpiresAt = "";
        public int lineCount = 0;
        public BigDecimal heldGrandTotalBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal liveGrandTotalBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public StalenessDiagnosticsDto staleness = new StalenessDiagnosticsDto();
        public int version = 1;
        public PosSaleOrderDto sale = null;
        public boolean offlineLocalOnly = false;
        public List<CartItem> offlineCartItems = new ArrayList<>();

        public static HeldCartEntryDto fromMap(Map<?, ?> map) {
            HeldCartEntryDto dto = new HeldCartEntryDto();
            if (map == null) return dto;
            dto.saleId = str(map.get("sale_id"));
            dto.orderNumber = str(map.get("order_number"));
            dto.branchId = str(map.get("branch_id"));
            dto.branchName = str(map.get("branch_name"));
            dto.customerName = strOr(map.get("customer_name"), "Walk-in Customer");
            dto.customerPhone = str(map.get("customer_phone"));
            dto.lifecycleState = LifecycleState.fromApi(strOr(map.get("lifecycle_state"), "HELD"));
            dto.holdReference = str(map.get("hold_reference"));
            dto.holdNote = str(map.get("hold_note"));
            dto.heldByEmail = str(map.get("held_by_email"));
            dto.heldAt = str(map.get("held_at"));
            dto.holdExpiresAt = str(map.get("hold_expires_at"));
            dto.holdStockReserved = Boolean.TRUE.equals(map.get("hold_stock_reserved"));
            dto.holdReservationExpiresAt = str(map.get("hold_reservation_expires_at"));
            dto.lineCount = integer(map.get("line_count"), 0);
            dto.heldGrandTotalBdt = dec2(map.get("held_grand_total_bdt"));
            dto.liveGrandTotalBdt = dec2(map.get("live_grand_total_bdt"));
            if (map.get("staleness") instanceof Map<?, ?> sm) {
                dto.staleness = StalenessDiagnosticsDto.fromMap(sm);
            }
            dto.version = integer(map.get("version"), 1);
            if (map.get("sale") instanceof Map<?, ?> saleMap) {
                dto.sale = PosSaleOrderDto.fromMap(saleMap);
                if (dto.lineCount == 0 && !dto.sale.lines.isEmpty()) {
                    dto.lineCount = dto.sale.lines.size();
                }
            }
            return dto;
        }
    }

    public static final class HeldCartListResultDto {
        public int count = 0;
        public int staleCount = 0;
        public int expiredCount = 0;
        public List<HeldCartEntryDto> results = new ArrayList<>();

        public static HeldCartListResultDto fromMap(Map<?, ?> map) {
            HeldCartListResultDto dto = new HeldCartListResultDto();
            if (map == null) return dto;
            dto.count = integer(map.get("count"), 0);
            dto.staleCount = integer(map.get("stale_count"), 0);
            dto.expiredCount = integer(map.get("expired_count"), 0);
            if (map.get("results") instanceof List<?> raw) {
                for (Object item : raw) {
                    if (item instanceof Map<?, ?> rm) dto.results.add(HeldCartEntryDto.fromMap(rm));
                }
            }
            if (dto.count == 0 && !dto.results.isEmpty()) dto.count = dto.results.size();
            return dto;
        }
    }

    public static final class ResumeCartResultDto {
        public String saleId = "";
        public PosSaleOrderDto sale = null;
        public StalenessDiagnosticsDto staleness = new StalenessDiagnosticsDto();
        public boolean requiresConfirmation = false;
    }

    public static final class ExpireStaleResultDto {
        public int expiredCount = 0;
        public int releasedBaseUnits = 0;
        public int voidedCount = 0;
        public List<String> affectedSaleIds = new ArrayList<>();
        public String messageEn = "";
        public String messageBn = "";

        public static ExpireStaleResultDto fromMap(Map<?, ?> map) {
            ExpireStaleResultDto dto = new ExpireStaleResultDto();
            if (map == null) return dto;
            dto.expiredCount = integer(map.get("expired_count"), 0);
            dto.releasedBaseUnits = integer(map.get("released_base_units"), 0);
            dto.voidedCount = integer(map.get("voided_count"), 0);
            if (map.get("affected_sale_ids") instanceof List<?> raw) {
                for (Object id : raw) {
                    if (id != null) dto.affectedSaleIds.add(id.toString());
                }
            }
            dto.messageEn = str(map.get("message_en"));
            dto.messageBn = str(map.get("message_bn"));
            return dto;
        }
    }

    public static final class PosDeliveryOrderDto {
        public String deliveryId = "";
        public String tenantId = "";
        public String branchId = "";
        public String branchName = "";
        public String saleId = "";
        public String orderNumber = "";
        public String saleLifecycleState = "DRAFT";
        public BigDecimal saleGrandTotalBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public String linkedInvoiceId = "";
        public boolean requiresRxVerification = false;
        public String deliveryNumber = "";
        public String trackingCode = "";
        public String fulfillmentMode = "HOME_DELIVERY";
        public String deliveryMethod = "OWN_RIDER";
        public String fulfillmentState = "PREORDER_PENDING";
        public String scheduledWindowStart = "";
        public String scheduledWindowEnd = "";
        public boolean stockReserved = false;
        public String reservationExpiresAt = "";
        public String recipientName = "";
        public String recipientPhone = "";
        public String deliveryAddressLine = "";
        public String deliveryArea = "";
        public String deliveryCity = "Dhaka";
        public String deliveryLandmarkNotes = "";
        public boolean addressPrivacyMasked = true;
        public String addressRedactedAt = "";
        public BigDecimal deliveryFeeBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal codExpectedBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal codCollectedBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public String courierPartnerName = "";
        public String riderName = "";
        public String riderPhone = "";
        public String proofOfDeliveryRef = "";
        public int failedAttemptCount = 0;
        public String lastFailureReason = "";
        public int version = 1;
        public boolean isArchived = false;
        public boolean offlineLocalOnly = false;
        public List<CartItem> offlineCartItems = new ArrayList<>();

        public static PosDeliveryOrderDto fromMap(Map<?, ?> map) {
            PosDeliveryOrderDto dto = new PosDeliveryOrderDto();
            if (map == null) return dto;
            dto.deliveryId = str(map.get("delivery_id"));
            dto.tenantId = str(map.get("tenant_id"));
            dto.branchId = str(map.get("branch_id"));
            dto.branchName = str(map.get("branch_name"));
            dto.saleId = str(map.get("sale_id"));
            dto.orderNumber = str(map.get("order_number"));
            dto.saleLifecycleState = strOr(map.get("sale_lifecycle_state"), "DRAFT");
            dto.saleGrandTotalBdt = dec2(map.get("sale_grand_total_bdt"));
            dto.linkedInvoiceId = str(map.get("linked_invoice_id"));
            dto.requiresRxVerification = Boolean.TRUE.equals(map.get("requires_rx_verification"));
            dto.deliveryNumber = str(map.get("delivery_number"));
            dto.trackingCode = str(map.get("tracking_code"));
            dto.fulfillmentMode = strOr(map.get("fulfillment_mode"), "HOME_DELIVERY");
            dto.deliveryMethod = strOr(map.get("delivery_method"), "OWN_RIDER");
            dto.fulfillmentState = strOr(map.get("fulfillment_state"), "PREORDER_PENDING");
            dto.scheduledWindowStart = str(map.get("scheduled_window_start"));
            dto.scheduledWindowEnd = str(map.get("scheduled_window_end"));
            dto.stockReserved = Boolean.TRUE.equals(map.get("stock_reserved"));
            dto.reservationExpiresAt = str(map.get("reservation_expires_at"));
            dto.recipientName = str(map.get("recipient_name"));
            dto.recipientPhone = str(map.get("recipient_phone"));
            dto.deliveryAddressLine = str(map.get("delivery_address_line"));
            dto.deliveryArea = str(map.get("delivery_area"));
            dto.deliveryCity = strOr(map.get("delivery_city"), "Dhaka");
            dto.deliveryLandmarkNotes = str(map.get("delivery_landmark_notes"));
            dto.addressPrivacyMasked = !Boolean.FALSE.equals(map.get("_address_privacy_masked"));
            dto.addressRedactedAt = str(map.get("address_redacted_at"));
            dto.deliveryFeeBdt = dec2(map.get("delivery_fee_bdt"));
            dto.codExpectedBdt = dec2(map.get("cod_expected_bdt"));
            dto.codCollectedBdt = dec2(map.get("cod_collected_bdt"));
            dto.courierPartnerName = str(map.get("courier_partner_name"));
            dto.riderName = str(map.get("rider_name"));
            dto.riderPhone = str(map.get("rider_phone"));
            dto.proofOfDeliveryRef = str(map.get("proof_of_delivery_ref"));
            dto.failedAttemptCount = integer(map.get("failed_attempt_count"), 0);
            dto.lastFailureReason = str(map.get("last_failure_reason"));
            dto.version = integer(map.get("version"), 1);
            dto.isArchived = Boolean.TRUE.equals(map.get("is_archived"));
            return dto;
        }
    }

    public static final class DeliveryListResultDto {
        public int count = 0;
        public List<PosDeliveryOrderDto> results = new ArrayList<>();

        public static DeliveryListResultDto fromMap(Object parsed) {
            DeliveryListResultDto dto = new DeliveryListResultDto();
            List<?> rawList;
            if (parsed instanceof Map<?, ?> m && m.get("results") instanceof List<?> r) {
                rawList = r;
                dto.count = integer(m.get("count"), r.size());
            } else if (parsed instanceof List<?> l) {
                rawList = l;
                dto.count = l.size();
            } else {
                rawList = Collections.emptyList();
            }
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> rm) {
                    dto.results.add(PosDeliveryOrderDto.fromMap(rm));
                }
            }
            if (dto.count == 0 && !dto.results.isEmpty()) dto.count = dto.results.size();
            return dto;
        }
    }

    public static final class AddressRevealResultDto {
        public String deliveryId = "";
        public String deliveryNumber = "";
        public String recipientName = "";
        public String recipientPhone = "";
        public String deliveryAddressLine = "";
        public String deliveryArea = "";
        public String deliveryCity = "";
        public String deliveryLandmarkNotes = "";
        public String auditId = "";

        public static AddressRevealResultDto fromMap(Map<?, ?> map) {
            AddressRevealResultDto dto = new AddressRevealResultDto();
            if (map == null) return dto;
            dto.deliveryId = str(map.get("delivery_id"));
            dto.deliveryNumber = str(map.get("delivery_number"));
            dto.recipientName = str(map.get("recipient_name"));
            dto.recipientPhone = str(map.get("recipient_phone"));
            dto.deliveryAddressLine = str(map.get("delivery_address_line"));
            dto.deliveryArea = str(map.get("delivery_area"));
            dto.deliveryCity = str(map.get("delivery_city"));
            dto.deliveryLandmarkNotes = str(map.get("delivery_landmark_notes"));
            dto.auditId = str(map.get("audit_id"));
            return dto;
        }
    }

    public static final class RxGateStatusDto {
        public String saleId = "";
        public String orderNumber = "";
        public String lifecycleState = "DRAFT";
        public boolean requiresRxVerification = false;
        public String rxGateStatus = "NOT_REQUIRED";
        public String prescriptionId = "";
        public String doctorName = "";
        public String doctorBmdcNo = "";
        public String patientToken = "";
        public String prescriptionStatus = "";
        public String rxReferenceCode = "";
        public String pharmacistPcbNoSnapshot = "";
        public String rxVerifiedByEmail = "";
        public String rxVerifiedAt = "";
        public String rxDecisionReason = "";
        public boolean requiresAntibioticRegister = false;
        public boolean hasNarcotic = false;
        public boolean hasBlockedRecallOrBan = false;
        public boolean canFinalizeOnline = true;
        public boolean canFinalizeOffline = true;
        public String offlineBlockReasonEn = "";
        public String offlineBlockReasonBn = "";
        public int version = 1;

        public static RxGateStatusDto fromMap(Map<?, ?> map) {
            RxGateStatusDto dto = new RxGateStatusDto();
            if (map == null) return dto;
            dto.saleId = str(map.get("sale_id"));
            dto.orderNumber = str(map.get("order_number"));
            dto.lifecycleState = strOr(map.get("lifecycle_state"), "DRAFT");
            dto.requiresRxVerification = Boolean.TRUE.equals(map.get("requires_rx_verification"));
            dto.rxGateStatus = strOr(map.get("rx_gate_status"), "NOT_REQUIRED");
            dto.prescriptionId = str(map.get("prescription_id"));
            dto.doctorName = str(map.get("doctor_name"));
            dto.doctorBmdcNo = str(map.get("doctor_bmdc_no"));
            dto.patientToken = str(map.get("patient_token"));
            dto.prescriptionStatus = str(map.get("prescription_status"));
            dto.rxReferenceCode = str(map.get("rx_reference_code"));
            dto.pharmacistPcbNoSnapshot = str(map.get("pharmacist_pcb_no_snapshot"));
            dto.rxVerifiedByEmail = str(map.get("rx_verified_by_email"));
            dto.rxVerifiedAt = str(map.get("rx_verified_at"));
            dto.rxDecisionReason = str(map.get("rx_decision_reason"));
            dto.requiresAntibioticRegister = Boolean.TRUE.equals(map.get("requires_antibiotic_register"));
            dto.hasNarcotic = Boolean.TRUE.equals(map.get("has_narcotic"));
            dto.hasBlockedRecallOrBan = Boolean.TRUE.equals(map.get("has_blocked_recall_or_ban"));
            dto.canFinalizeOnline = !map.containsKey("can_finalize_online") || Boolean.TRUE.equals(map.get("can_finalize_online"));
            dto.canFinalizeOffline = !map.containsKey("can_finalize_offline") || Boolean.TRUE.equals(map.get("can_finalize_offline"));
            dto.offlineBlockReasonEn = str(map.get("offline_block_reason_en"));
            dto.offlineBlockReasonBn = str(map.get("offline_block_reason_bn"));
            dto.version = integer(map.get("version"), 1);
            return dto;
        }
    }

    public static final class AntibioticRegisterEntryDto {
        public String registerEntryId = "";
        public String saleId = "";
        public String orderNumber = "";
        public String brandName = "";
        public String genericName = "";
        public String classificationTier = "ANTIMICROBIAL_ACCESS";
        public int dispensedBaseUnits = 0;
        public String doctorBmdcNo = "";
        public String patientToken = "";
        public String approvingPharmacistPcbNo = "";
        public String status = "ACTIVE";

        public static AntibioticRegisterEntryDto fromMap(Map<?, ?> map) {
            AntibioticRegisterEntryDto dto = new AntibioticRegisterEntryDto();
            if (map == null) return dto;
            dto.registerEntryId = str(map.get("register_entry_id"));
            dto.saleId = str(map.get("sale_id"));
            dto.orderNumber = str(map.get("order_number"));
            dto.brandName = str(map.get("brand_name"));
            dto.genericName = str(map.get("generic_name"));
            dto.classificationTier = strOr(map.get("classification_tier"), "ANTIMICROBIAL_ACCESS");
            dto.dispensedBaseUnits = integer(map.get("dispensed_base_units"), 0);
            dto.doctorBmdcNo = str(map.get("doctor_bmdc_no"));
            dto.patientToken = str(map.get("patient_token"));
            dto.approvingPharmacistPcbNo = str(map.get("approving_pharmacist_pcb_no"));
            dto.status = strOr(map.get("status"), "ACTIVE");
            return dto;
        }
    }

    public static final class AntibioticRegisterListDto {
        public int count = 0;
        public List<AntibioticRegisterEntryDto> results = new ArrayList<>();

        public static AntibioticRegisterListDto fromMap(Object parsed) {
            AntibioticRegisterListDto dto = new AntibioticRegisterListDto();
            if (parsed instanceof Map<?, ?> m) {
                dto.count = integer(m.get("count"), 0);
                if (m.get("results") instanceof List<?> lst) {
                    for (Object o : lst) {
                        if (o instanceof Map<?, ?> rm) dto.results.add(AntibioticRegisterEntryDto.fromMap(rm));
                    }
                }
                if (dto.count == 0 && !dto.results.isEmpty()) dto.count = dto.results.size();
            }
            return dto;
        }
    }

    public static final class FefoPlannedLotDto {
        public int fefoSequence = 1;
        public String batchId = "";
        public String batchNumber = "";
        public String expiryDate = "";
        public String purchaseDate = "";
        public String costLotId = "";
        public int allocatedBaseUnits = 0;
        public String allocationMode = "FEFO_AUTO";
        public BigDecimal unitCostBdt = null;
        public BigDecimal totalCostBdt = null;

        public static FefoPlannedLotDto fromMap(Map<?, ?> map) {
            FefoPlannedLotDto dto = new FefoPlannedLotDto();
            if (map == null) return dto;
            dto.fefoSequence = integer(map.get("fefo_sequence"), 1);
            dto.batchId = str(map.get("batch_id"));
            dto.batchNumber = str(map.get("batch_number"));
            dto.expiryDate = str(map.get("expiry_date"));
            dto.purchaseDate = str(map.get("purchase_date"));
            dto.costLotId = str(map.get("cost_lot_id"));
            dto.allocatedBaseUnits = integer(map.get("allocated_base_units"), 0);
            dto.allocationMode = strOr(map.get("allocation_mode"), "FEFO_AUTO");
            dto.unitCostBdt = map.get("unit_cost_bdt") != null ? dec4(map.get("unit_cost_bdt")) : null;
            dto.totalCostBdt = map.get("total_cost_bdt") != null ? dec4(map.get("total_cost_bdt")) : null;
            return dto;
        }
    }

    public static final class FefoBlockedLotDto {
        public String batchId = "";
        public String batchNumber = "";
        public String expiryDate = "";
        public String blockCategory = "";
        public int quantityBaseUnits = 0;
        public String reason = "";

        public static FefoBlockedLotDto fromMap(Map<?, ?> map) {
            FefoBlockedLotDto dto = new FefoBlockedLotDto();
            if (map == null) return dto;
            dto.batchId = str(map.get("batch_id"));
            dto.batchNumber = str(map.get("batch_number"));
            dto.expiryDate = str(map.get("expiry_date"));
            dto.blockCategory = str(map.get("block_category"));
            dto.quantityBaseUnits = integer(map.get("quantity_base_units"), 0);
            dto.reason = str(map.get("reason"));
            return dto;
        }
    }

    public static final class FefoPlanLineDto {
        public String lineId = "";
        public int lineNumber = 1;
        public String productId = "";
        public String brandName = "";
        public String packagingUnit = "TABLET";
        public int requestedBaseUnits = 0;
        public int plannedBaseUnits = 0;
        public int shortBaseUnits = 0;
        public boolean isFulfilled = true;
        public String allocationMode = "FEFO_AUTO";
        public String blockingErrorCode = "";
        public String blockingReasonEn = "";
        public String blockingReasonBn = "";
        public List<FefoPlannedLotDto> plannedLots = new ArrayList<>();
        public List<FefoBlockedLotDto> blockedLots = new ArrayList<>();

        public static FefoPlanLineDto fromMap(Map<?, ?> map) {
            FefoPlanLineDto dto = new FefoPlanLineDto();
            if (map == null) return dto;
            dto.lineId = str(map.get("line_id"));
            dto.lineNumber = integer(map.get("line_number"), 1);
            dto.productId = str(map.get("product_id"));
            dto.brandName = str(map.get("brand_name"));
            dto.packagingUnit = strOr(map.get("packaging_unit"), "TABLET");
            dto.requestedBaseUnits = integer(map.get("requested_base_units"), 0);
            dto.plannedBaseUnits = integer(map.get("planned_base_units"), 0);
            dto.shortBaseUnits = integer(map.get("short_base_units"), 0);
            dto.isFulfilled = Boolean.TRUE.equals(map.get("is_fulfilled"));
            dto.allocationMode = strOr(map.get("allocation_mode"), "FEFO_AUTO");
            dto.blockingErrorCode = str(map.get("blocking_error_code"));
            dto.blockingReasonEn = str(map.get("blocking_reason_en"));
            dto.blockingReasonBn = str(map.get("blocking_reason_bn"));
            if (map.get("planned_lots") instanceof List<?> rawLots) {
                for (Object o : rawLots) {
                    if (o instanceof Map<?, ?> m) dto.plannedLots.add(FefoPlannedLotDto.fromMap(m));
                }
            }
            if (map.get("blocked_lots") instanceof List<?> rawBlk) {
                for (Object o : rawBlk) {
                    if (o instanceof Map<?, ?> m) dto.blockedLots.add(FefoBlockedLotDto.fromMap(m));
                }
            }
            return dto;
        }
    }

    public static final class FefoAllocationPlanDto {
        public String saleId = "";
        public String orderNumber = "";
        public String fefoAllocationStatus = "PENDING";
        public boolean fefoReady = true;
        public String blockingErrorCode = "";
        public String blockingReasonEn = "";
        public String blockingReasonBn = "";
        public boolean hasExpiredBlock = false;
        public boolean hasRecalledBlock = false;
        public boolean hasQuarantinedBlock = false;
        public int totalRequestedBaseUnits = 0;
        public int totalPlannedBaseUnits = 0;
        public BigDecimal totalEstimatedCostBdt = null;
        public int version = 1;
        public List<FefoPlanLineDto> lines = new ArrayList<>();

        public static FefoAllocationPlanDto fromMap(Map<?, ?> map) {
            FefoAllocationPlanDto dto = new FefoAllocationPlanDto();
            if (map == null) return dto;
            dto.saleId = str(map.get("sale_id"));
            dto.orderNumber = str(map.get("order_number"));
            dto.fefoAllocationStatus = strOr(map.get("fefo_allocation_status"), "PENDING");
            dto.fefoReady = !map.containsKey("fefo_ready") || Boolean.TRUE.equals(map.get("fefo_ready"));
            dto.blockingErrorCode = str(map.get("blocking_error_code"));
            dto.blockingReasonEn = str(map.get("blocking_reason_en"));
            dto.blockingReasonBn = str(map.get("blocking_reason_bn"));
            dto.hasExpiredBlock = Boolean.TRUE.equals(map.get("has_expired_block"));
            dto.hasRecalledBlock = Boolean.TRUE.equals(map.get("has_recalled_block"));
            dto.hasQuarantinedBlock = Boolean.TRUE.equals(map.get("has_quarantined_block"));
            dto.totalRequestedBaseUnits = integer(map.get("total_requested_base_units"), 0);
            dto.totalPlannedBaseUnits = integer(map.get("total_planned_base_units"), 0);
            dto.totalEstimatedCostBdt = map.get("total_estimated_cost_bdt") != null
                    ? dec4(map.get("total_estimated_cost_bdt")) : null;
            dto.version = integer(map.get("version"), 1);
            if (map.get("lines") instanceof List<?> rawLines) {
                for (Object o : rawLines) {
                    if (o instanceof Map<?, ?> m) dto.lines.add(FefoPlanLineDto.fromMap(m));
                }
            }
            return dto;
        }
    }

    private final String baseUrl;
    private final ObjectMapper mapper;
    private final ApiTransport transport;
    private UiState currentState = UiState.EMPTY;

    public PosSaleDomainClient(String baseUrl) {
        this(baseUrl, createDefaultHttpTransport(baseUrl));
    }

    public PosSaleDomainClient(String baseUrl, ApiTransport transport) {
        this.baseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "";
        this.transport = transport;
        this.mapper = new ObjectMapper();
    }

    private static ApiTransport createDefaultHttpTransport(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        String root = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "";
        return (method, path, jsonBody, bearerToken) -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(root + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            if (bearerToken != null && !bearerToken.isBlank()) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }
            if ("POST".equalsIgnoreCase(method)) {
                builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody != null ? jsonBody : "{}"));
            } else if ("PATCH".equalsIgnoreCase(method)) {
                builder.method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody != null ? jsonBody : "{}"));
            } else if ("DELETE".equalsIgnoreCase(method)) {
                builder.DELETE();
            } else {
                builder.GET();
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new ApiResponseResult(response.statusCode(), response.body());
        };
    }

    public UiState getCurrentState() {
        return currentState;
    }

    public ClientOutcome<Void> authorizeScope(
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            boolean requireElevated,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        if (isOffline) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_OFFLINE_MODE",
                    UiState.OFFLINE.defaultMessageEn, UiState.OFFLINE.defaultMessageBn
            );
        }
        if (subscriptionSuspended) {
            currentState = UiState.PERMISSION_DENIED;
            return new ClientOutcome<>(
                    UiState.PERMISSION_DENIED, null, "ERR_SUBSCRIPTION_SUSPENDED",
                    "Subscription is suspended. POS operations are locked.",
                    "সাবস্ক্রিপশন স্থগিত আছে। পিওএস কার্যক্রম বন্ধ।"
            );
        }
        String role = actorRole != null ? actorRole.trim().toUpperCase(Locale.ROOT) : "";
        Set<String> allowed = requireElevated ? POS_ELEVATED_ROLES : POS_WRITE_ROLES;
        if (!allowed.contains(role)) {
            currentState = UiState.PERMISSION_DENIED;
            return new ClientOutcome<>(
                    UiState.PERMISSION_DENIED, null, "ERR_PERMISSION_DENIED",
                    "Role '" + role + "' is not authorized for this POS action.",
                    "'" + role + "' রোল-এর জন্য এই পিওএস কাজটি অনুমোদিত নয়।"
            );
        }
        if (!"SUPERADMIN".equals(role) && !Objects.equals(actorTenantId, targetTenantId)) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_CROSS_TENANT_VIOLATION",
                    "Cross-tenant POS operation denied.",
                    "অন্য টেন্যান্টের শাখায় পিওএস অপারেশন নিষিদ্ধ।"
            );
        }
        if (!TENANT_WIDE_ROLES.contains(role) && !Objects.equals(actorBranchId, targetBranchId)) {
            currentState = UiState.PERMISSION_DENIED;
            return new ClientOutcome<>(
                    UiState.PERMISSION_DENIED, null, "ERR_CROSS_BRANCH_ACCESS_DENIED",
                    "Branch-bound staff cannot operate in sibling branch '" + targetBranchId + "'.",
                    "নির্ধারিত শাখার বাইরে পিওএস অপারেশন অনুমোদিত নয়।"
            );
        }
        return new ClientOutcome<>(UiState.READY, null, "", "Authorized", "অনুমোদিত");
    }

    public ClientOutcome<Void> authorizeReadScope(
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        if (isOffline) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_OFFLINE_MODE",
                    UiState.OFFLINE.defaultMessageEn, UiState.OFFLINE.defaultMessageBn
            );
        }
        if (subscriptionSuspended) {
            currentState = UiState.PERMISSION_DENIED;
            return new ClientOutcome<>(
                    UiState.PERMISSION_DENIED, null, "ERR_SUBSCRIPTION_SUSPENDED",
                    "Subscription is suspended. POS inspection is locked.",
                    "সাবস্ক্রিপশন স্থগিত আছে। পিওএস তথ্য দেখা বন্ধ।"
            );
        }
        String role = actorRole != null ? actorRole.trim().toUpperCase(Locale.ROOT) : "";
        if (!POS_READ_ROLES.contains(role)) {
            currentState = UiState.PERMISSION_DENIED;
            return new ClientOutcome<>(
                    UiState.PERMISSION_DENIED, null, "ERR_PERMISSION_DENIED",
                    "Role '" + role + "' cannot view POS held carts.",
                    "'" + role + "' রোল-এর হোল্ড করা কার্ট দেখার অনুমতি নেই।"
            );
        }
        if (!"SUPERADMIN".equals(role) && !Objects.equals(actorTenantId, targetTenantId)) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_CROSS_TENANT_VIOLATION",
                    "Cross-tenant POS operation denied.",
                    "অন্য টেন্যান্টের শাখায় পিওএস অপারেশন নিষিদ্ধ।"
            );
        }
        if (!TENANT_WIDE_ROLES.contains(role) && !Objects.equals(actorBranchId, targetBranchId)) {
            currentState = UiState.PERMISSION_DENIED;
            return new ClientOutcome<>(
                    UiState.PERMISSION_DENIED, null, "ERR_CROSS_BRANCH_ACCESS_DENIED",
                    "Branch-bound staff cannot inspect sibling branch '" + targetBranchId + "'.",
                    "নির্ধারিত শাখার বাইরের হোল্ড কার্ট দেখা অনুমোদিত নয়।"
            );
        }
        return new ClientOutcome<>(UiState.READY, null, "", "Authorized", "অনুমোদিত");
    }

    public static BigDecimal computePreviewGrandTotal(List<PosSaleLineDto> lines, BigDecimal headerFlatDiscountBdt) {
        BigDecimal sum = BigDecimal.ZERO;
        for (PosSaleLineDto line : lines) {
            BigDecimal gross = line.unitPriceBdt.multiply(BigDecimal.valueOf(line.quantityUnits))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal taxable = gross.subtract(line.discountAmountBdt).max(BigDecimal.ZERO);
            BigDecimal tax = taxable.multiply(line.taxRatePct).divide(new BigDecimal("100.00"), 2, RoundingMode.HALF_UP);
            sum = sum.add(taxable).add(tax);
        }
        BigDecimal disc = headerFlatDiscountBdt != null ? headerFlatDiscountBdt : BigDecimal.ZERO;
        return sum.subtract(disc).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    public ClientOutcome<PosSaleOrderDto> createSaleOrder(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String idempotencyKey,
            List<PosSaleLineDto> lines,
            String discountType,
            BigDecimal discountAmountBdt,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        if (lines == null || lines.isEmpty()) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_EMPTY_CART",
                    "Sale order requires at least one line item.",
                    "সেলস অর্ডারে কমপক্ষে একটি আইটেম থাকতে হবে।"
            );
        }

        currentState = UiState.LOADING;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("branch", targetBranchId);
        payload.put("idempotency_key", idempotencyKey != null ? idempotencyKey : "");
        payload.put("discount_type", discountType != null ? discountType : "NONE");
        payload.put("discount_amount_bdt", (discountAmountBdt != null ? discountAmountBdt : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toPlainString());
        List<Map<String, Object>> lineMaps = new ArrayList<>();
        for (PosSaleLineDto line : lines) lineMaps.add(line.toCreateMap());
        payload.put("lines", lineMaps);

        return executeSaleMutation("POST", "/api/v2/pos/sales/", payload, bearerToken,
                "POS sale order created successfully.", "পিওএস সেলস অর্ডার সফলভাবে তৈরি হয়েছে।");
    }

    public ClientOutcome<PosSaleOrderDto> reverseSale(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            String reason,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                true, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        if (reason == null || reason.isBlank()) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_REASON_REQUIRED",
                    "A documented reason is required to reverse a finalized sale.",
                    "চূড়ান্ত সেলস রিভার্স করতে সুনির্দিষ্ট কারণ লিখতে হবে।"
            );
        }
        currentState = UiState.LOADING;
        Map<String, Object> payload = Map.of("reason", reason.trim());
        return executeSaleMutation("POST", "/api/v2/pos/sales/" + saleId + "/reverse/", payload, bearerToken,
                "POS sale reversed with stock restoration.", "পিওএস সেলস রিভার্স এবং স্টক পুনরুদ্ধার সম্পন্ন হয়েছে।");
    }

    public ClientOutcome<PosSaleOrderDto> voidSale(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            String reason,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        if (reason == null || reason.isBlank()) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_REASON_REQUIRED",
                    "A documented reason is required to void a sale order.",
                    "সেলস অর্ডার বাতিল করতে সুনির্দিষ্ট কারণ উল্লেখ করতে হবে।"
            );
        }
        currentState = UiState.LOADING;
        Map<String, Object> payload = Map.of("reason", reason.trim());
        return executeSaleMutation("POST", "/api/v2/pos/sales/" + saleId + "/void/", payload, bearerToken,
                "POS sale voided successfully.", "পিওএস সেলস অর্ডার সফলভাবে বাতিল করা হয়েছে।");
    }

    public ClientOutcome<PosSaleOrderDto> correctSale(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            String reason,
            String correctionType,
            String lineId,
            Integer quantityUnits,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        if (reason == null || reason.isBlank()) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_REASON_REQUIRED",
                    "A documented reason is required to correct a sale order.",
                    "সেলস অর্ডার সংশোধন করতে সুনির্দিষ্ট কারণ উল্লেখ করতে হবে।"
            );
        }
        currentState = UiState.LOADING;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason.trim());
        payload.put("correction_type", correctionType != null ? correctionType : "CART_CORRECTION");
        if (lineId != null) payload.put("line_id", lineId);
        if (quantityUnits != null) payload.put("quantity_units", quantityUnits);
        return executeSaleMutation("POST", "/api/v2/pos/sales/" + saleId + "/correct/", payload, bearerToken,
                "POS sale correction applied successfully.", "পিওএস সেলস সংশোধন সফলভাবে প্রয়োগ করা হয়েছে।");
    }

    public ClientOutcome<PosSaleOrderDto> updateCartPricing(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            Map<String, Object> pricingPayload,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        currentState = UiState.LOADING;
        return executeSaleMutation(
                "POST",
                "/api/v2/pos/sales/" + saleId + "/update-pricing/",
                pricingPayload != null ? pricingPayload : Collections.emptyMap(),
                bearerToken,
                "Cart prices, discount & rounding recalculated by server.",
                "সার্ভার কর্তৃক কার্টের মূল্য, ছাড় ও রাউন্ডিং সফলভাবে হালনাগাদ হয়েছে।"
        );
    }

    public ClientOutcome<PosSaleOrderDto> approveDiscount(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            String discountReason,
            String discountRatePct,
            String discountAmountBdt,
            List<Map<String, Object>> lineOverrides,
            boolean isOffline,
            boolean subscriptionSuspended
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, false
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        if (discountReason == null || discountReason.trim().length() < 3) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_REASON_REQUIRED",
                    "A documented reason (>= 3 chars) is mandatory for discount/price approval.",
                    "ছাড় বা মূল্য অনুমোদনের জন্য কমপক্ষে ৩ অক্ষরের কারণ উল্লেখ করুন।"
            );
        }
        if (isOffline) {
            if (discountRatePct != null && !discountRatePct.isBlank()) {
                try {
                    double rate = Double.parseDouble(discountRatePct.trim());
                    if (rate > 20.0) {
                        currentState = UiState.ERROR;
                        return new ClientOutcome<>(
                                UiState.ERROR, null, "ERR_OFFLINE_DISCOUNT_CEILING_EXCEEDED",
                                "Discounts exceeding 20% cannot be approved offline. Cloud verification required.",
                                "অফলাইনে ২০%-এর বেশি ছাড় অনুমোদন করা যায় না। ক্লাউড সংযোগ প্রয়োজন।"
                        );
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        currentState = UiState.LOADING;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("discount_reason", discountReason.trim());
        if (discountRatePct != null) payload.put("discount_rate_pct", discountRatePct.trim());
        if (discountAmountBdt != null) payload.put("discount_amount_bdt", discountAmountBdt.trim());
        if (lineOverrides != null && !lineOverrides.isEmpty()) payload.put("line_overrides", lineOverrides);
        payload.put("is_offline", isOffline);

        return executeSaleMutation(
                "POST",
                "/api/v2/pos/sales/" + saleId + "/approve-discount/",
                payload,
                bearerToken,
                "Manager discount approval applied successfully.",
                "ব্যবস্থাপক কর্তৃক ছাড় অনুমোদন সফলভাবে প্রয়োগ করা হয়েছে।"
        );
    }

    public ClientOutcome<PosSaleOrderDto> confirmCartPricing(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            BigDecimal expectedGrandTotalBdt,
            String expectedPricingHash,
            Integer expectedVersion,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (expectedGrandTotalBdt != null) {
            payload.put("expected_grand_total_bdt", expectedGrandTotalBdt.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        if (expectedPricingHash != null && !expectedPricingHash.isBlank()) {
            payload.put("expected_pricing_hash", expectedPricingHash.trim());
        }
        if (expectedVersion != null) {
            payload.put("expected_version", expectedVersion);
        }
        currentState = UiState.LOADING;
        return executeSaleMutation(
                "POST",
                "/api/v2/pos/sales/" + saleId + "/confirm-pricing/",
                payload,
                bearerToken,
                "Cart prices & totals confirmed with reproducible server signature.",
                "কার্টের মোট মূল্য সার্ভারের স্বাক্ষরসহ নিশ্চিত হয়েছে।"
        );
    }

    public ClientOutcome<PosSaleOrderDto> holdCart(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            String holdReference,
            String holdNote,
            Integer holdTtlMinutes,
            boolean reserveStock,
            Integer reservationTtlMinutes,
            Integer expectedVersion,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        if (saleId == null || saleId.isBlank()) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_EMPTY_CART",
                    "Active cart ID is missing before holding.",
                    "হোল্ড করার জন্য সক্রিয় কার্ট আইডি পাওয়া যায়নি।"
            );
        }
        if (holdTtlMinutes != null && (holdTtlMinutes < 1 || holdTtlMinutes > 1440)) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_INVALID_HOLD_TTL",
                    "hold_ttl_minutes must be between 1 and 1440 minutes.",
                    "হোল্ডের মেয়াদ ১ থেকে ১৪৪০ মিনিটের মধ্যে হতে হবে।"
            );
        }
        if (reservationTtlMinutes != null && (reservationTtlMinutes < 1 || reservationTtlMinutes > 1440
                || (holdTtlMinutes != null && reservationTtlMinutes > holdTtlMinutes))) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_INVALID_HOLD_TTL",
                    "reservation_ttl_minutes must be within 1..1440 and <= hold_ttl_minutes.",
                    "রিজার্ভেশন মেয়াদ হোল্ডের মোট সময়ের বেশি হতে পারবে না।"
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (holdReference != null && !holdReference.isBlank()) {
            payload.put("hold_reference", holdReference.trim());
        }
        if (holdNote != null && !holdNote.isBlank()) {
            payload.put("hold_note", holdNote.trim());
        }
        if (holdTtlMinutes != null) {
            payload.put("hold_ttl_minutes", holdTtlMinutes);
        }
        payload.put("reserve_stock", reserveStock);
        if (reserveStock && reservationTtlMinutes != null) {
            payload.put("reservation_ttl_minutes", reservationTtlMinutes);
        }
        if (expectedVersion != null) {
            payload.put("expected_version", expectedVersion);
        }
        currentState = UiState.LOADING;
        return executeSaleMutation(
                "POST",
                "/api/v2/pos/sales/" + saleId + "/hold/",
                payload,
                bearerToken,
                "Cart parked safely on hold. Counter is free for next customer.",
                "কার্টটি নিরাপদে হোল্ড করা হয়েছে। পরবর্তী গ্রাহকের জন্য কাউন্টার প্রস্তুত।"
        );
    }

    public ClientOutcome<HeldCartListResultDto> listHeldCarts(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            boolean includeExpired,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeReadScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        currentState = UiState.LOADING;
        StringBuilder sb = new StringBuilder("/api/v2/pos/sales/held/?include_expired=")
                .append(includeExpired ? "true" : "false");
        if (targetBranchId != null && !targetBranchId.isBlank()) {
            sb.append("&branch=").append(targetBranchId.trim());
        }
        try {
            ApiResponseResult res = transport.send("GET", sb.toString(), null, bearerToken);
            if (res.statusCode == 200) {
                Map<?, ?> rawMap = mapper.readValue(res.body, Map.class);
                HeldCartListResultDto dto = HeldCartListResultDto.fromMap(rawMap);
                if (dto.results.isEmpty()) {
                    currentState = UiState.EMPTY;
                    return new ClientOutcome<>(
                            UiState.EMPTY, dto, "",
                            "No held carts currently parked in this branch.",
                            "এই শাখায় কোনো হোল্ড করা কার্ট নেই।"
                    );
                }
                currentState = UiState.READY;
                return new ClientOutcome<>(
                        UiState.READY, dto, "",
                        "Loaded " + dto.count + " held cart(s) (" + dto.staleCount + " stale).",
                        dto.count + "টি হোল্ড কার্ট লোড হয়েছে (" + dto.staleCount + "টি মেয়াদোত্তীর্ণ/সতর্কতা)।"
                );
            } else if (res.statusCode == 403 || res.statusCode == 401) {
                currentState = UiState.PERMISSION_DENIED;
                String code = extractJsonStr(res.body, "error_code", "ERR_PERMISSION_DENIED");
                String msgEn = extractJsonStr(res.body, "message_en", "Permission denied inspecting held carts.");
                String msgBn = extractJsonStr(res.body, "message_bn", "হোল্ড কার্ট তালিকা দেখার অনুমতি নেই।");
                return new ClientOutcome<>(UiState.PERMISSION_DENIED, null, code, msgEn, msgBn);
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_HTTP_" + res.statusCode,
                    extractJsonStr(res.body, "message_en", "Failed to load held carts (HTTP " + res.statusCode + ")."),
                    extractJsonStr(res.body, "message_bn", "হোল্ড কার্ট তালিকা লোড করা যায়নি।")
            );
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable loading held carts: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন। হোল্ড কার্ট লোড করা যাচ্ছে না।"
            );
        }
    }

    public ClientOutcome<ResumeCartResultDto> resumeCart(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            boolean acknowledgeStale,
            boolean repriceToLive,
            boolean reReserveStock,
            boolean allowUnreservedShortage,
            Integer expectedVersion,
            String note,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("acknowledge_stale", acknowledgeStale);
        payload.put("reprice_to_live", repriceToLive);
        payload.put("re_reserve_stock", reReserveStock);
        payload.put("allow_unreserved_shortage", allowUnreservedShortage);
        if (expectedVersion != null) payload.put("expected_version", expectedVersion);
        if (note != null && !note.isBlank()) payload.put("note", note.trim());

        currentState = UiState.LOADING;
        String path = "/api/v2/pos/sales/" + saleId + "/resume/";
        try {
            String json = mapper.writeValueAsString(payload);
            ApiResponseResult res = transport.send("POST", path, json, bearerToken);
            if (res.statusCode == 200 || res.statusCode == 201) {
                Map<?, ?> rawMap = mapper.readValue(res.body, Map.class);
                Object saleObj = rawMap.containsKey("sale") && rawMap.get("sale") instanceof Map<?, ?> s ? s : rawMap;
                PosSaleOrderDto saleDto = PosSaleOrderDto.fromMap((Map<?, ?>) saleObj);
                ResumeCartResultDto out = new ResumeCartResultDto();
                out.saleId = saleDto.saleId;
                out.sale = saleDto;
                out.staleness = rawMap.get("staleness") instanceof Map<?, ?> sm
                        ? StalenessDiagnosticsDto.fromMap(sm)
                        : saleDto.staleness;
                out.requiresConfirmation = false;
                currentState = UiState.READY;
                return new ClientOutcome<>(
                        UiState.READY, out, "",
                        "Held cart resumed cleanly to active counter.",
                        "হোল্ড করা কার্টটি সক্রিয় কাউন্টারে পুনরুদ্ধার হয়েছে।"
                );
            } else if (res.statusCode == 403 || res.statusCode == 401) {
                currentState = UiState.PERMISSION_DENIED;
                String code = extractJsonStr(res.body, "error_code", "ERR_PERMISSION_DENIED");
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED, null, code,
                        extractJsonStr(res.body, "message_en", "Permission denied resuming held cart."),
                        extractJsonStr(res.body, "message_bn", "হোল্ড কার্ট পুনরুদ্ধারের অনুমতি নেই।")
                );
            } else if (res.statusCode == 400 || res.statusCode == 422) {
                currentState = UiState.ERROR;
                Map<?, ?> errMap = parseJsonMap(res.body);
                String code = strOr(errMap.get("error_code"), "ERR_VALIDATION_FAILED");
                String msgEn = strOr(errMap.get("message_en"), "Unable to resume held cart.");
                String msgBn = strOr(errMap.get("message_bn"), "হোল্ড করা কার্ট রিজিউম করা যায়নি।");
                ResumeCartResultDto out = null;
                if ("ERR_HELD_CART_REQUIRES_CONFIRMATION".equals(code)) {
                    out = new ResumeCartResultDto();
                    out.saleId = saleId;
                    out.requiresConfirmation = true;
                    Map<?, ?> diagSource = errMap.get("details") instanceof Map<?, ?> det ? det : errMap;
                    out.staleness = StalenessDiagnosticsDto.fromMap(diagSource);
                }
                return new ClientOutcome<>(UiState.ERROR, out, code, msgEn, msgBn);
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_HTTP_" + res.statusCode,
                    "Unexpected HTTP " + res.statusCode, "সার্ভার ত্রুটি HTTP " + res.statusCode
            );
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable resuming held cart: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন। হোল্ড কার্ট রিজিউম করা যাচ্ছে না।"
            );
        }
    }

    public ClientOutcome<ExpireStaleResultDto> expireStaleHolds(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            boolean voidExpiredHolds,
            String reason,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (targetBranchId != null && !targetBranchId.isBlank()) payload.put("branch", targetBranchId.trim());
        payload.put("void_expired_holds", voidExpiredHolds);
        if (reason != null && !reason.isBlank()) payload.put("reason", reason.trim());

        currentState = UiState.LOADING;
        try {
            String json = mapper.writeValueAsString(payload);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/sales/expire-stale-holds/", json, bearerToken);
            if (res.statusCode == 200) {
                ExpireStaleResultDto dto = ExpireStaleResultDto.fromMap(parseJsonMap(res.body));
                currentState = UiState.READY;
                return new ClientOutcome<>(
                        UiState.READY, dto, "",
                        dto.messageEn.isBlank() ? ("Released " + dto.releasedBaseUnits + " reserved unit(s).") : dto.messageEn,
                        dto.messageBn.isBlank() ? (dto.releasedBaseUnits + "টি রিজার্ভকৃত ইউনিট মুক্ত করা হয়েছে।") : dto.messageBn
                );
            } else if (res.statusCode == 403 || res.statusCode == 401) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED, null,
                        extractJsonStr(res.body, "error_code", "ERR_PERMISSION_DENIED"),
                        extractJsonStr(res.body, "message_en", "Permission denied expiring stale holds."),
                        extractJsonStr(res.body, "message_bn", "মেয়াদোত্তীর্ণ হোল্ড মুক্ত করার অনুমতি নেই।")
                );
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null,
                    extractJsonStr(res.body, "error_code", "ERR_EXPIRE_STALE_FAILED"),
                    extractJsonStr(res.body, "message_en", "Failed to expire stale holds."),
                    extractJsonStr(res.body, "message_bn", "মেয়াদোত্তীর্ণ হোল্ড প্রসেস করা যায়নি।")
            );
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন।"
            );
        }
    }

    public ClientOutcome<PosSaleOrderDto> expireHold(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            boolean voidHold,
            String reason,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("void_hold", voidHold);
        if (reason != null && !reason.isBlank()) payload.put("reason", reason.trim());
        currentState = UiState.LOADING;
        return executeSaleMutation(
                "POST",
                "/api/v2/pos/sales/" + saleId + "/expire-hold/",
                payload,
                bearerToken,
                "Held cart stock reservation released cleanly.",
                "হোল্ড কার্টের স্টক রিজার্ভেশন মুক্ত করা হয়েছে।"
        );
    }

    public ClientOutcome<PosSaleOrderDto> cancelHold(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            String reason,
            Integer expectedVersion,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (reason != null && !reason.isBlank()) payload.put("reason", reason.trim());
        if (expectedVersion != null) payload.put("expected_version", expectedVersion);
        currentState = UiState.LOADING;
        return executeSaleMutation(
                "POST",
                "/api/v2/pos/sales/" + saleId + "/cancel-hold/",
                payload,
                bearerToken,
                "Held cart cancelled and any reservations released.",
                "হোল্ড করা কার্ট বাতিল এবং রিজার্ভেশন মুক্ত করা হয়েছে।"
        );
    }

    public ClientOutcome<PosSaleOrderDto> executeAction(
            String bearerToken,
            String saleId,
            String actionSlug,
            Map<String, Object> payload,
            String successEn,
            String successBn
    ) {
        currentState = UiState.LOADING;
        return executeSaleMutation(
                "POST",
                "/api/v2/pos/sales/" + saleId + "/" + actionSlug + "/",
                payload != null ? payload : Collections.emptyMap(),
                bearerToken,
                successEn,
                successBn
        );
    }

    // -------------------------------------------------------------------------
    // P226: Preorders, Delivery Fulfillment & Audited Address Privacy Controls
    // -------------------------------------------------------------------------

    public ClientOutcome<DeliveryListResultDto> listDeliveryOrders(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String stateFilter,
            String modeFilter,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeReadScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        StringBuilder sb = new StringBuilder("/api/v2/pos/deliveries/");
        List<String> qp = new ArrayList<>();
        if (targetBranchId != null && !targetBranchId.isBlank()) qp.add("branch=" + targetBranchId.trim());
        if (stateFilter != null && !stateFilter.isBlank()) qp.add("fulfillment_state=" + stateFilter.trim().toUpperCase(Locale.ROOT));
        if (modeFilter != null && !modeFilter.isBlank()) qp.add("fulfillment_mode=" + modeFilter.trim().toUpperCase(Locale.ROOT));
        if (!qp.isEmpty()) sb.append("?").append(String.join("&", qp));

        currentState = UiState.LOADING;
        try {
            ApiResponseResult res = transport.send("GET", sb.toString(), null, bearerToken);
            if (res.statusCode == 200) {
                Object parsed = mapper.readValue(res.body, Object.class);
                DeliveryListResultDto dto = DeliveryListResultDto.fromMap(parsed);
                if (dto.results.isEmpty()) {
                    currentState = UiState.EMPTY;
                    return new ClientOutcome<>(
                            UiState.EMPTY, dto, "",
                            "No preorders or deliveries queued in this branch.",
                            "এই শাখায় কোনো প্রি-অর্ডার বা ডেলিভারি অর্ডার নেই।"
                    );
                }
                currentState = UiState.READY;
                return new ClientOutcome<>(
                        UiState.READY, dto, "",
                        "Loaded " + dto.count + " preorder/delivery order(s).",
                        dto.count + "টি প্রি-অর্ডার ও ডেলিভারি অর্ডার লোড হয়েছে।"
                );
            } else if (res.statusCode == 403 || res.statusCode == 401) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED, null,
                        extractJsonStr(res.body, "error_code", "ERR_PERMISSION_DENIED"),
                        extractJsonStr(res.body, "message_en", "Permission denied inspecting branch deliveries."),
                        extractJsonStr(res.body, "message_bn", "ডেলিভারি তালিকা দেখার অনুমতি নেই।")
                );
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_HTTP_" + res.statusCode,
                    extractJsonStr(res.body, "message_en", "Failed to load deliveries (HTTP " + res.statusCode + ")."),
                    extractJsonStr(res.body, "message_bn", "ডেলিভারি অর্ডার লোড করা যায়নি।")
            );
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable loading deliveries: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন। ডেলিভারি লোড করা যাচ্ছে না।"
            );
        }
    }

    public ClientOutcome<PosDeliveryOrderDto> createDeliveryOrder(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            Map<String, Object> payload,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        Map<String, Object> body = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        String mode = strOr(body.get("fulfillment_mode"), "HOME_DELIVERY").trim().toUpperCase(Locale.ROOT);
        String name = str(body.get("recipient_name")).trim();
        String phone = str(body.get("recipient_phone")).trim();
        String addr = str(body.get("delivery_address_line")).trim();
        if (name.isEmpty() || phone.isEmpty()) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_RECIPIENT_REQUIRED",
                    "Recipient name and phone are required for preorder/delivery.",
                    "প্রাপকের নাম এবং মোবাইল নম্বর দেওয়া আবশ্যক।"
            );
        }
        if ("HOME_DELIVERY".equals(mode) && addr.isEmpty()) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_DELIVERY_ADDRESS_REQUIRED",
                    "Delivery street address is required for home delivery.",
                    "হোম ডেলিভারির জন্য প্রাপকের ঠিকানা দেওয়া আবশ্যক।"
            );
        }
        if (!body.containsKey("branch") && targetBranchId != null && !targetBranchId.isBlank()) {
            body.put("branch", targetBranchId.trim());
        }
        currentState = UiState.LOADING;
        return executeDeliveryMutation(
                "POST",
                "/api/v2/pos/deliveries/",
                body,
                bearerToken,
                "Preorder / delivery order created safely.",
                "প্রি-অর্ডার / ডেলিভারি অর্ডার সফলভাবে তৈরি হয়েছে।"
        );
    }

    public ClientOutcome<PosDeliveryOrderDto> updateDeliveryOrder(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String deliveryId,
            Map<String, Object> payload,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        currentState = UiState.LOADING;
        return executeDeliveryMutation(
                "PATCH",
                "/api/v2/pos/deliveries/" + deliveryId + "/",
                payload != null ? payload : Collections.emptyMap(),
                bearerToken,
                "Delivery order updated cleanly.",
                "ডেলিভারি অর্ডার হালনাগাদ হয়েছে।"
        );
    }

    public ClientOutcome<PosDeliveryOrderDto> archiveDeliveryOrder(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String deliveryId,
            String reason,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        currentState = UiState.LOADING;
        String suffix = (reason != null && !reason.isBlank()) ? ("?reason=" + reason.trim().replace(" ", "+")) : "";
        return executeDeliveryMutation(
                "DELETE",
                "/api/v2/pos/deliveries/" + deliveryId + "/" + suffix,
                null,
                bearerToken,
                "Delivery order archived safely.",
                "ডেলিভারি অর্ডার নিরাপদে আর্কাইভ করা হয়েছে।"
        );
    }

    // -------------------------------------------------------------------------
    // P227: POS Prescription Safety Gate, Pharmacist Approval & Antibiotic Register
    // -------------------------------------------------------------------------

    private ClientOutcome<Void> authorizePharmacistApprovalScope(
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String pharmacistPcbNo,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> base = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (base.uiState != UiState.READY) {
            return base;
        }
        String normRole = actorRole != null ? actorRole.trim().toUpperCase(Locale.ROOT) : "";
        boolean isPrimaryPharm = "STAFF_PHARMACIST".equals(normRole) || "SUPERADMIN".equals(normRole);
        boolean isOwnerWithPcb = "PHARMACY_OWNER".equals(normRole) && pharmacistPcbNo != null && !pharmacistPcbNo.trim().isEmpty();
        if (!isPrimaryPharm && !isOwnerWithPcb) {
            currentState = UiState.PERMISSION_DENIED;
            return new ClientOutcome<>(
                    UiState.PERMISSION_DENIED, null, "ERR_PHARMACIST_APPROVAL_REQUIRED",
                    "Only a registered Pharmacist (or Owner with verified PCB registration) may verify or reject Rx orders.",
                    "কেবল নিবন্ধিত ফার্মাসিস্ট (বা PCB সনদসহ মালিক) প্রেসক্রিপশন অনুমোদন/প্রত্যাখ্যান করতে পারবেন।"
            );
        }
        return base;
    }

    public ClientOutcome<RxGateStatusDto> getRxGateStatus(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeReadScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        if (saleId == null || saleId.isBlank()) {
            return new ClientOutcome<>(
                    UiState.EMPTY, null, "ERR_SALE_REQUIRED",
                    "Open or select a sale order first.",
                    "প্রথমে একটি বিক্রয় অর্ডার চালু করুন।"
            );
        }
        try {
            ApiResponseResult res = transport.send("GET", "/api/v2/pos/sales/" + saleId + "/rx-gate/", null, bearerToken);
            if (res.statusCode == 200) {
                RxGateStatusDto dto = RxGateStatusDto.fromMap(parseJsonMap(res.body));
                currentState = UiState.READY;
                return new ClientOutcome<>(UiState.READY, dto, "", "Rx gate diagnostics loaded.", "প্রেসক্রিপশন সেফটি গেট তথ্য লোড হয়েছে।");
            }
            if (res.statusCode == 401 || res.statusCode == 403) {
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED, null,
                        extractJsonStr(res.body, "error_code", "ERR_PERMISSION_DENIED"),
                        extractJsonStr(res.body, "message_en", "Permission denied loading Rx gate status."),
                        extractJsonStr(res.body, "message_bn", "প্রেসক্রিপশন গেট তথ্য দেখার অনুমতি নেই।")
                );
            }
            return new ClientOutcome<>(
                    UiState.ERROR, null,
                    extractJsonStr(res.body, "error_code", "ERR_RX_GATE_READ"),
                    extractJsonStr(res.body, "message_en", "Failed to load Rx safety gate."),
                    extractJsonStr(res.body, "message_bn", "প্রেসক্রিপশন সেফটি গেট লোড করা যায়নি।")
            );
        } catch (Exception ex) {
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন।"
            );
        }
    }

    public ClientOutcome<PosSaleOrderDto> attachPrescription(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            String doctorName,
            String doctorBmdcNo,
            String patientToken,
            String rxReferenceCode,
            String dosageInstructions,
            int durationDays,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        String doc = doctorName != null ? doctorName.trim() : "";
        String bmdc = doctorBmdcNo != null ? doctorBmdcNo.trim() : "";
        if (doc.isEmpty() || bmdc.length() < 3) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_RX_DOCUMENTATION_REQUIRED",
                    "Doctor Name and BMDC Reg. No. (>= 3 chars) are required.",
                    "চিকিৎসকের নাম এবং BMDC নম্বর (কমপক্ষে ৩ অক্ষর) দেওয়া আবশ্যক।"
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("doctor_name", doc);
        payload.put("doctor_bmdc_no", bmdc);
        if (patientToken != null && !patientToken.isBlank()) payload.put("patient_token", patientToken.trim());
        if (rxReferenceCode != null && !rxReferenceCode.isBlank()) payload.put("rx_reference_code", rxReferenceCode.trim());
        if (dosageInstructions != null && !dosageInstructions.isBlank()) payload.put("dosage_instructions", dosageInstructions.trim());
        if (durationDays > 0) payload.put("duration_days", durationDays);
        return executeSaleMutation(
                "POST",
                "/api/v2/pos/sales/" + saleId + "/attach-prescription/",
                payload,
                bearerToken,
                "Prescription attached cleanly (awaiting Pharmacist sign-off).",
                "প্রেসক্রিপশন সংযুক্ত হয়েছে (ফার্মাসিস্ট অনুমোদনের অপেক্ষায়)।"
        );
    }

    public ClientOutcome<PosSaleOrderDto> verifyRxSale(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            String pharmacistPcbNo,
            String notes,
            String rxReferenceCode,
            String doctorName,
            String doctorBmdcNo,
            Integer expectedVersion,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizePharmacistApprovalScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                pharmacistPcbNo, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (pharmacistPcbNo != null && !pharmacistPcbNo.isBlank()) payload.put("pharmacist_pcb_no", pharmacistPcbNo.trim());
        if (notes != null && !notes.isBlank()) payload.put("notes", notes.trim());
        if (rxReferenceCode != null && !rxReferenceCode.isBlank()) payload.put("rx_reference_code", rxReferenceCode.trim());
        if (doctorName != null && !doctorName.isBlank()) payload.put("doctor_name", doctorName.trim());
        if (doctorBmdcNo != null && !doctorBmdcNo.isBlank()) payload.put("doctor_bmdc_no", doctorBmdcNo.trim());
        if (expectedVersion != null) payload.put("expected_version", expectedVersion);
        return executeSaleMutation(
                "POST",
                "/api/v2/pos/sales/" + saleId + "/verify-rx/",
                payload,
                bearerToken,
                "Prescription safety gate APPROVED by registered Pharmacist.",
                "নিবন্ধিত ফার্মাসিস্ট কর্তৃক প্রেসক্রিপশন সেফটি গেট অনুমোদিত হয়েছে।"
        );
    }

    public ClientOutcome<PosSaleOrderDto> rejectRxSale(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            String pharmacistPcbNo,
            String reason,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizePharmacistApprovalScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                pharmacistPcbNo, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        String cleanReason = reason != null ? reason.trim() : "";
        if (cleanReason.length() < 3) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_RX_REJECTION_REASON_REQUIRED",
                    "Reason (>= 3 chars) is mandatory to reject Rx dispensing.",
                    "প্রেসক্রিপশন প্রত্যাখ্যানের জন্য কমপক্ষে ৩ অক্ষরের কারণ উল্লেখ করা আবশ্যক।"
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", cleanReason);
        if (pharmacistPcbNo != null && !pharmacistPcbNo.isBlank()) payload.put("pharmacist_pcb_no", pharmacistPcbNo.trim());
        return executeSaleMutation(
                "POST",
                "/api/v2/pos/sales/" + saleId + "/reject-rx/",
                payload,
                bearerToken,
                "Prescription dispensing REJECTED by Pharmacist.",
                "ফার্মাসিস্ট কর্তৃক প্রেসক্রিপশন বিক্রি প্রত্যাখ্যাত হয়েছে।"
        );
    }

    public ClientOutcome<RxGateStatusDto> offlineRxPreflight(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        try {
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/sales/" + saleId + "/rx-gate/offline-preflight/", "{}", bearerToken);
            Map<?, ?> parsed = parseJsonMap(res.body);
            RxGateStatusDto summary = parsed.get("summary") instanceof Map<?, ?> sm
                    ? RxGateStatusDto.fromMap(sm)
                    : RxGateStatusDto.fromMap(parsed);
            if (res.statusCode == 200) {
                currentState = UiState.READY;
                return new ClientOutcome<>(UiState.READY, summary, "",
                        strOr(parsed.get("message_en"), "Cart cleared for offline checkout."),
                        strOr(parsed.get("message_bn"), "কার্টটি অফলাইন চেকআউটের জন্য উপযোগী।"));
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR,
                    summary,
                    extractJsonStr(res.body, "error_code", "ERR_OFFLINE_RX_BLOCKED"),
                    extractJsonStr(res.body, "message_en", "Offline limitation: unverified Rx cannot finalize offline."),
                    extractJsonStr(res.body, "message_bn", "অফলাইন সীমাবদ্ধতা: অনুমোদনহীন প্রেসক্রিপশন ওষুধ অফলাইনে সম্পন্ন করা যাবে না।")
            );
        } catch (Exception ex) {
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন।"
            );
        }
    }

    public ClientOutcome<AntibioticRegisterListDto> listAntibioticRegister(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleFilter,
            String statusFilter,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeReadScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        try {
            StringBuilder qp = new StringBuilder();
            if (targetBranchId != null && !targetBranchId.isBlank()) qp.append("branch=").append(targetBranchId.trim());
            if (saleFilter != null && !saleFilter.isBlank()) qp.append(qp.isEmpty() ? "" : "&").append("sale=").append(saleFilter.trim());
            if (statusFilter != null && !statusFilter.isBlank()) qp.append(qp.isEmpty() ? "" : "&").append("status=").append(statusFilter.trim());
            String path = "/api/v2/pos/antibiotic-register/" + (qp.isEmpty() ? "" : "?" + qp);
            ApiResponseResult res = transport.send("GET", path, null, bearerToken);
            if (res.statusCode == 200) {
                AntibioticRegisterListDto listDto = AntibioticRegisterListDto.fromMap(mapper.readValue(res.body, Object.class));
                currentState = listDto.results.isEmpty() ? UiState.EMPTY : UiState.READY;
                return new ClientOutcome<>(currentState, listDto, "",
                        "Loaded " + listDto.count + " antibiotic register record(s).",
                        listDto.count + "টি অ্যান্টিবায়োটিক রেজিস্টার তথ্য লোড হয়েছে।");
            }
            return new ClientOutcome<>(
                    res.statusCode == 403 ? UiState.PERMISSION_DENIED : UiState.ERROR,
                    null,
                    extractJsonStr(res.body, "error_code", "ERR_ANTIBIOTIC_REGISTER"),
                    extractJsonStr(res.body, "message_en", "Failed to load antibiotic register."),
                    extractJsonStr(res.body, "message_bn", "অ্যান্টিবায়োটিক রেজিস্টার লোড করা যায়নি।")
            );
        } catch (Exception ex) {
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন।"
            );
        }
    }

    public ClientOutcome<PosSaleOrderDto> recordPaymentAttempt(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            String tenderType,
            BigDecimal amountBdt,
            String gatewayReference,
            String idempotencyKey,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tender_type", tenderType != null && !tenderType.isBlank() ? tenderType.trim().toUpperCase(Locale.ROOT) : "CASH");
        payload.put("amount_bdt", amountBdt != null ? amountBdt.setScale(2, RoundingMode.HALF_UP).toPlainString() : "0.00");
        if (gatewayReference != null && !gatewayReference.isBlank()) payload.put("gateway_reference", gatewayReference.trim());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) payload.put("idempotency_key", idempotencyKey.trim());
        return executeSaleMutation(
                "POST",
                "/api/v2/pos/sales/" + saleId + "/record-payment/",
                payload,
                bearerToken,
                "Payment attempt recorded cleanly.",
                "পেমেন্ট এন্ট্রি সফলভাবে যুক্ত হয়েছে।"
        );
    }

    private static final Set<String> DIGITAL_TENDERS = Set.of(
            "BKASH", "NAGAD", "ROCKET", "UPAY", "CARD", "BANK_TRANSFER"
    );

    public ClientOutcome<PosSaleOrderDto> submitSplitPayments(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            List<SplitPaymentInputDto> splits,
            String batchIdempotencyKey,
            boolean requireVerified,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        if (splits == null || splits.isEmpty()) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_EMPTY_SPLIT_PAYMENTS",
                    "Add at least one tender split leg before posting payments.",
                    "পেমেন্ট সম্পন্ন করার আগে অন্তত একটি টেন্ডার লাইন যোগ করুন।"
            );
        }
        for (SplitPaymentInputDto leg : splits) {
            if (leg.amountBdt == null || leg.amountBdt.compareTo(BigDecimal.ZERO) <= 0) {
                currentState = UiState.ERROR;
                return new ClientOutcome<>(
                        UiState.ERROR, null, "ERR_INVALID_PAYMENT_AMOUNT",
                        "Each split payment amount must be greater than 0.00 BDT.",
                        "প্রতিটি স্প্লিট পেমেন্টের পরিমাণ ০.০০ টাকার বেশি হতে হবে।"
                );
            }
            String tender = leg.tenderType != null ? leg.tenderType.trim().toUpperCase(Locale.ROOT) : "CASH";
            String gwRef = leg.gatewayReference != null ? leg.gatewayReference.trim() : "";
            String ver = leg.verificationStatus != null ? leg.verificationStatus.trim().toUpperCase(Locale.ROOT) : "";
            if ("DUE".equals(tender) && isOffline) {
                currentState = UiState.OFFLINE;
                return new ClientOutcome<>(
                        UiState.OFFLINE, null, "ERR_OFFLINE_CREDIT_UNAVAILABLE",
                        "Offline limitation: customer credit / DUE sales require online ledger & credit limit verification.",
                        "অফলাইন সীমাবদ্ধতা: কাস্টমার বাকি বিক্রয়ের জন্য অনলাইনে ক্রেডিট অনুমোদন ও লিমিট যাচাই আবশ্যক।"
                );
            }
            if (DIGITAL_TENDERS.contains(tender)) {
                if (isOffline && (gwRef.length() < 4 || "PENDING_GATEWAY".equals(ver))) {
                    currentState = UiState.OFFLINE;
                    return new ClientOutcome<>(
                            UiState.OFFLINE, null, "ERR_OFFLINE_DIGITAL_PAYMENT_REQUIRES_REF",
                            "Offline limitation: MFS/Card payments require live gateway or verified terminal TrxID (>= 4 chars).",
                            "অফলাইন সীমাবদ্ধতা: বিকাশ/নগদ/কার্ডের জন্য ন্যূনতম ৪ অক্ষরের ভেরিফায়েড রেফারেন্স আবশ্যক বা ক্যাশ ব্যবহার করুন।"
                    );
                }
                if (!isOffline && requireVerified && !"PENDING_GATEWAY".equals(ver) && gwRef.length() < 4) {
                    currentState = UiState.ERROR;
                    return new ClientOutcome<>(
                            UiState.ERROR, null, "ERR_DIGITAL_TENDER_REFERENCE_REQUIRED",
                            "Verified " + tender + " payment requires gateway TrxID / auth reference (>= 4 chars).",
                            "ভেরিফায়েড " + tender + " পেমেন্টের জন্য ন্যূনতম ৪ অক্ষরের TrxID বা কার্ড রেফারেন্স আবশ্যক।"
                    );
                }
            }
        }
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        List<Map<String, Object>> splitList = new ArrayList<>();
        for (SplitPaymentInputDto leg : splits) {
            splitList.add(leg.toMap());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("splits", splitList);
        payload.put("require_verified", requireVerified);
        if (batchIdempotencyKey != null && !batchIdempotencyKey.isBlank()) {
            payload.put("batch_idempotency_key", batchIdempotencyKey.trim());
        }
        return executeSaleMutation(
                "POST",
                "/api/v2/pos/sales/" + saleId + "/split-payments/",
                payload,
                bearerToken,
                "Split payments posted and reconciled cleanly.",
                "স্প্লিট পেমেন্ট সফলভাবে সংরক্ষিত ও সমন্বয় হয়েছে।"
        );
    }

    public ClientOutcome<PosSaleOrderDto> verifyPaymentAttempt(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            String attemptId,
            String gatewayReference,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attempt_id", attemptId != null ? attemptId.trim() : "");
        payload.put("gateway_reference", gatewayReference != null ? gatewayReference.trim() : "");
        return executeSaleMutation(
                "POST",
                "/api/v2/pos/sales/" + saleId + "/verify-payment-attempt/",
                payload,
                bearerToken,
                "Digital payment reference verified.",
                "ডিজিটাল পেমেন্ট রেফারেন্স ভেরিফাই হয়েছে।"
        );
    }

    public ClientOutcome<PosSaleOrderDto> voidPaymentAttempt(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            String attemptId,
            String reason,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        String cleanReason = reason != null ? reason.trim() : "";
        if (cleanReason.length() < 3) {
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_VOID_REASON_REQUIRED",
                    "Reason (>= 3 chars) is mandatory to void a payment attempt.",
                    "পেমেন্ট এন্ট্রি বাতিলের জন্য কমপক্ষে ৩ অক্ষরের কারণ উল্লেখ করা আবশ্যক।"
            );
        }
        Map<String, Object> payload = Map.of(
                "attempt_id", attemptId != null ? attemptId.trim() : "",
                "reason", cleanReason
        );
        return executeSaleMutation(
                "POST",
                "/api/v2/pos/sales/" + saleId + "/void-payment-attempt/",
                payload,
                bearerToken,
                "Payment attempt voided.",
                "পেমেন্ট এন্ট্রি বাতিল করা হয়েছে।"
        );
    }

    public ClientOutcome<CashierCloseReconciliationDto> getCloseReconciliation(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String registerAssignmentId,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeReadScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        try {
            String path = "/api/v2/pos/sales/close-reconciliation/?branch=" + targetBranchId.trim()
                    + (registerAssignmentId != null && !registerAssignmentId.isBlank()
                    ? "&register_assignment=" + registerAssignmentId.trim() : "");
            ApiResponseResult res = transport.send("GET", path, null, bearerToken);
            if (res.statusCode == 200) {
                CashierCloseReconciliationDto dto = CashierCloseReconciliationDto.fromMap(parseJsonMap(res.body));
                currentState = UiState.READY;
                return new ClientOutcome<>(
                        UiState.READY, dto, "",
                        "Cashier shift tender totals loaded cleanly.",
                        "শিফট ও ক্যাশ ড্রয়ার রিকনসিলিয়েশন লোড হয়েছে।"
                );
            }
            if (res.statusCode == 401 || res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED, null,
                        extractJsonStr(res.body, "error_code", "ERR_PERMISSION_DENIED"),
                        extractJsonStr(res.body, "message_en", "Permission denied inspecting shift reconciliation."),
                        extractJsonStr(res.body, "message_bn", "শিফট রিকনসিলিয়েশন দেখার অনুমতি নেই।")
                );
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null,
                    extractJsonStr(res.body, "error_code", "ERR_HTTP_" + res.statusCode),
                    extractJsonStr(res.body, "message_en", "Failed loading close reconciliation."),
                    extractJsonStr(res.body, "message_bn", "রিকনসিলিয়েশন লোড ব্যর্থ হয়েছে।")
            );
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন।"
            );
        }
    }

    public ClientOutcome<CashierCloseReconciliationDto> submitCloseReconciliation(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String registerAssignmentId,
            BigDecimal countedCashBdt,
            String notes,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        if (countedCashBdt == null || countedCashBdt.compareTo(BigDecimal.ZERO) < 0) {
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_NEGATIVE_COUNTED_CASH",
                    "Counted cash amount must be >= 0.00 BDT.",
                    "গননাকৃত নগদ অর্থ ০.০০ টাকা বা তার বেশি হতে হবে।"
            );
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("branch", targetBranchId.trim());
            if (registerAssignmentId != null && !registerAssignmentId.isBlank()) {
                payload.put("register_assignment", registerAssignmentId.trim());
            }
            payload.put("counted_cash_bdt", countedCashBdt.setScale(2, RoundingMode.HALF_UP).toPlainString());
            if (notes != null && !notes.isBlank()) payload.put("notes", notes.trim());
            String json = mapper.writeValueAsString(payload);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/sales/close-reconciliation/", json, bearerToken);
            if (res.statusCode == 200 || res.statusCode == 201) {
                CashierCloseReconciliationDto dto = CashierCloseReconciliationDto.fromMap(parseJsonMap(res.body));
                currentState = UiState.READY;
                return new ClientOutcome<>(
                        UiState.READY, dto, "",
                        "Cashier drawer closed (" + dto.status + ", variance " + dto.varianceBdt.toPlainString() + " BDT).",
                        "ক্যাশিয়ার শিফট ড্রয়ার ক্লোজ সম্পন্ন (" + dto.status + ", ব্যবধান " + dto.varianceBdt.toPlainString() + " টাকা)।"
                );
            }
            if (res.statusCode == 401 || res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED, null,
                        extractJsonStr(res.body, "error_code", "ERR_PERMISSION_DENIED"),
                        extractJsonStr(res.body, "message_en", "Permission denied closing cashier drawer."),
                        extractJsonStr(res.body, "message_bn", "ক্যাশিয়ার ড্রয়ার বন্ধ করার অনুমতি নেই।")
                );
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null,
                    extractJsonStr(res.body, "error_code", "ERR_CLOSE_RECONCILIATION"),
                    extractJsonStr(res.body, "message_en", "Cashier close validation failed (HTTP " + res.statusCode + ")."),
                    extractJsonStr(res.body, "message_bn", "ক্যাশিয়ার ক্লোজ রিকনসিলিয়েশন ব্যর্থ হয়েছে।")
            );
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable closing drawer: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন।"
            );
        }
    }

    public ClientOutcome<List<PosCustomerDto>> listCustomers(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeReadScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, Collections.emptyList(), auth.errorCode, auth.messageEn, auth.messageBn);
        }
        try {
            String path = "/api/v2/pos/customers/" + (targetBranchId != null && !targetBranchId.isBlank()
                    ? "?branch=" + targetBranchId.trim() : "");
            ApiResponseResult res = transport.send("GET", path, null, bearerToken);
            if (res.statusCode == 200) {
                List<PosCustomerDto> out = new ArrayList<>();
                Object rawList = parseListOrResults(res.body);
                if (rawList instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) out.add(PosCustomerDto.fromMap(m));
                    }
                }
                currentState = out.isEmpty() ? UiState.EMPTY : UiState.READY;
                return new ClientOutcome<>(
                        currentState, out, "",
                        out.isEmpty() ? "No customers found." : "Loaded " + out.size() + " customer(s).",
                        out.isEmpty() ? "কোনো গ্রাহক পাওয়া যায়নি।" : out.size() + " জন গ্রাহকের তথ্য লোড হয়েছে।"
                );
            }
            if (res.statusCode == 401 || res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED, Collections.emptyList(),
                        extractJsonStr(res.body, "error_code", "ERR_PERMISSION_DENIED"),
                        extractJsonStr(res.body, "message_en", "Permission denied listing customers."),
                        extractJsonStr(res.body, "message_bn", "গ্রাহক তালিকা দেখার অনুমতি নেই।")
                );
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, Collections.emptyList(),
                    extractJsonStr(res.body, "error_code", "ERR_HTTP_" + res.statusCode),
                    extractJsonStr(res.body, "message_en", "Failed loading customers."),
                    extractJsonStr(res.body, "message_bn", "গ্রাহক তালিকা লোড ব্যর্থ হয়েছে।")
            );
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, Collections.emptyList(), "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable loading customers: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন।"
            );
        }
    }

    public ClientOutcome<PosCustomerDto> approveCustomerCredit(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String customerId,
            BigDecimal creditLimitBdt,
            int creditTermsDays,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        String role = actorRole != null ? actorRole.trim().toUpperCase(Locale.ROOT) : "";
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        if (!POS_CUSTOMER_ADMIN_ROLES.contains(role)) {
            currentState = UiState.PERMISSION_DENIED;
            return new ClientOutcome<>(
                    UiState.PERMISSION_DENIED, null, "ERR_PERMISSION_DENIED",
                    "Only Pharmacy Owner, Branch Manager, or Finance Officer may approve customer credit.",
                    "শুধুমাত্র মালিক, শাখা ব্যবস্থাপক বা ফাইন্যান্স অফিসার ক্রেডিট অনুমোদন করতে পারেন।"
            );
        }
        if (creditLimitBdt == null || creditLimitBdt.compareTo(BigDecimal.ZERO) <= 0) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_CREDIT_LIMIT_MUST_BE_POSITIVE",
                    "Approved credit_limit_bdt must be > 0.00 BDT.",
                    "অনুমোদিত ক্রেডিট লিমিট ০.০০ টাকার বেশি হতে হবে।"
            );
        }
        try {
            Map<String, Object> payload = Map.of(
                    "credit_limit_bdt", creditLimitBdt.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    "credit_terms_days", Math.max(1, creditTermsDays)
            );
            String json = mapper.writeValueAsString(payload);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/customers/" + customerId + "/approve-credit/", json, bearerToken);
            if (res.statusCode == 200) {
                PosCustomerDto dto = PosCustomerDto.fromMap(parseJsonMap(res.body));
                currentState = UiState.READY;
                return new ClientOutcome<>(
                        UiState.READY, dto, "",
                        "Customer credit approved cleanly.",
                        "গ্রাহকের ক্রেডিট লিমিট অনুমোদিত হয়েছে।"
                );
            }
            if (res.statusCode == 401 || res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED, null,
                        extractJsonStr(res.body, "error_code", "ERR_PERMISSION_DENIED"),
                        extractJsonStr(res.body, "message_en", "Permission denied approving credit."),
                        extractJsonStr(res.body, "message_bn", "ক্রেডিট অনুমোদনের অনুমতি নেই।")
                );
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null,
                    extractJsonStr(res.body, "error_code", "ERR_CREDIT_APPROVAL_FAILED"),
                    extractJsonStr(res.body, "message_en", "Failed approving customer credit."),
                    extractJsonStr(res.body, "message_bn", "ক্রেডিট অনুমোদন ব্যর্থ হয়েছে।")
            );
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable approving credit: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন।"
            );
        }
    }

    public ClientOutcome<PosSaleOrderDto> attachCustomerToSale(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String saleId,
            String customerId,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        return executeSaleMutation(
                "POST",
                "/api/v2/pos/sales/" + saleId + "/attach-customer/",
                Map.of("customer_id", customerId != null ? customerId.trim() : ""),
                bearerToken,
                "Customer attached to POS sale cleanly.",
                "বিক্রয় অর্ডারে গ্রাহক যুক্ত করা হয়েছে।"
        );
    }

    public ClientOutcome<List<CustomerDueRecordDto>> listCustomerDues(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String customerId,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeReadScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, Collections.emptyList(), auth.errorCode, auth.messageEn, auth.messageBn);
        }
        try {
            StringBuilder path = new StringBuilder("/api/v2/pos/customer-dues/?branch=")
                    .append(targetBranchId != null ? targetBranchId.trim() : "");
            if (customerId != null && !customerId.isBlank()) {
                path.append("&customer=").append(customerId.trim());
            }
            ApiResponseResult res = transport.send("GET", path.toString(), null, bearerToken);
            if (res.statusCode == 200) {
                List<CustomerDueRecordDto> out = new ArrayList<>();
                Object rawList = parseListOrResults(res.body);
                if (rawList instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) out.add(CustomerDueRecordDto.fromMap(m));
                    }
                }
                currentState = out.isEmpty() ? UiState.EMPTY : UiState.READY;
                return new ClientOutcome<>(
                        currentState, out, "",
                        out.isEmpty() ? "No open customer dues." : "Loaded " + out.size() + " due record(s).",
                        out.isEmpty() ? "কোনো বাকি পাওনা পাওয়া যায়নি।" : out.size() + "টি বাকি রেকর্ড লোড হয়েছে।"
                );
            }
            if (res.statusCode == 401 || res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED, Collections.emptyList(),
                        extractJsonStr(res.body, "error_code", "ERR_PERMISSION_DENIED"),
                        extractJsonStr(res.body, "message_en", "Permission denied inspecting customer dues."),
                        extractJsonStr(res.body, "message_bn", "বাকি তথ্য দেখার অনুমতি নেই।")
                );
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, Collections.emptyList(),
                    extractJsonStr(res.body, "error_code", "ERR_HTTP_" + res.statusCode),
                    extractJsonStr(res.body, "message_en", "Failed loading customer dues."),
                    extractJsonStr(res.body, "message_bn", "বাকি তালিকা লোড ব্যর্থ হয়েছে।")
            );
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, Collections.emptyList(), "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable loading dues: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন।"
            );
        }
    }

    public ClientOutcome<DueCollectionOutcomeDto> collectCustomerDue(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String dueId,
            String tenderType,
            BigDecimal amountBdt,
            BigDecimal cashTenderedBdt,
            String gatewayReference,
            String idempotencyKey,
            String notes,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        if (isOffline) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_OFFLINE_UNAVAILABLE",
                    "Offline limitation: customer due collection requires online server ledger sync.",
                    "অফলাইন সীমাবদ্ধতা: বাকি আদায়ের জন্য অনলাইন সার্ভার সংযোগ আবশ্যক।"
            );
        }
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, false
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        String tender = tenderType != null ? tenderType.trim().toUpperCase(Locale.ROOT) : "CASH";
        if ("DUE".equals(tender)) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_CANNOT_COLLECT_DUE_WITH_DUE",
                    "Cannot settle a customer due using DUE tender.",
                    "বাকি পরিশোধে DUE মাধ্যম ব্যবহার করা যাবে না।"
            );
        }
        if (amountBdt == null || amountBdt.compareTo(BigDecimal.ZERO) <= 0) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_INVALID_PAYMENT_AMOUNT",
                    "Collection amount must be > 0.00 BDT.",
                    "আদায়ের পরিমাণ ০.০০ টাকার বেশি হতে হবে।"
            );
        }
        String gwRef = gatewayReference != null ? gatewayReference.trim() : "";
        if (DIGITAL_TENDERS.contains(tender) && gwRef.length() < 4) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_DIGITAL_TENDER_REFERENCE_REQUIRED",
                    tender + " due collection requires gateway TrxID / auth reference (>= 4 chars).",
                    tender + " দিয়ে বাকি আদায়ে ন্যূনতম ৪ অক্ষরের TrxID বা কার্ড রেফারেন্স আবশ্যক।"
            );
        }
        if ("CASH".equals(tender) && cashTenderedBdt != null && cashTenderedBdt.compareTo(amountBdt) < 0) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_INVALID_PAYMENT_AMOUNT",
                    "Cash tendered cannot be less than applied collection amount.",
                    "প্রদত্ত নগদ টাকা আদায়যোগ্য টাকার চেয়ে কম হতে পারবে না।"
            );
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tender_type", tender);
            payload.put("amount_bdt", amountBdt.setScale(2, RoundingMode.HALF_UP).toPlainString());
            if ("CASH".equals(tender) && cashTenderedBdt != null) {
                payload.put("cash_tendered_bdt", cashTenderedBdt.setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
            if (!gwRef.isBlank()) payload.put("gateway_reference", gwRef);
            if (idempotencyKey != null && !idempotencyKey.isBlank()) payload.put("idempotency_key", idempotencyKey.trim());
            if (notes != null && !notes.isBlank()) payload.put("notes", notes.trim());
            String json = mapper.writeValueAsString(payload);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/customer-dues/" + dueId + "/collect/", json, bearerToken);
            if (res.statusCode == 200 || res.statusCode == 201) {
                DueCollectionOutcomeDto dto = DueCollectionOutcomeDto.fromMap(parseJsonMap(res.body));
                currentState = UiState.READY;
                return new ClientOutcome<>(
                        UiState.READY, dto, "",
                        "Due collected (" + dto.collection.debitAccount + " -> " + dto.collection.creditAccount + ", Revenue impact 0.00).",
                        "বাকি আদায় সম্পন্ন (" + dto.collection.creditAccount + "-এ সমন্বিত, ডাবল রেভিনিউ ০.০০)।"
                );
            }
            if (res.statusCode == 401 || res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED, null,
                        extractJsonStr(res.body, "error_code", "ERR_PERMISSION_DENIED"),
                        extractJsonStr(res.body, "message_en", "Permission denied collecting customer due."),
                        extractJsonStr(res.body, "message_bn", "বাকি আদায়ের অনুমতি নেই।")
                );
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null,
                    extractJsonStr(res.body, "error_code", "ERR_DUE_COLLECTION_FAILED"),
                    extractJsonStr(res.body, "message_en", "Customer due collection failed (HTTP " + res.statusCode + ")."),
                    extractJsonStr(res.body, "message_bn", "বাকি আদায় ব্যর্থ হয়েছে।")
            );
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable collecting due: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন।"
            );
        }
    }

    private Object parseListOrResults(String body) {
        try {
            if (body == null || body.isBlank()) return Collections.emptyList();
            String trimmed = body.trim();
            if (trimmed.startsWith("[")) {
                return mapper.readValue(trimmed, List.class);
            }
            Map<?, ?> m = mapper.readValue(trimmed, Map.class);
            if (m != null && m.get("results") instanceof List<?> list) {
                return list;
            }
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

        public ClientOutcome<PosSaleOrderDto> finalizeSale(
                String bearerToken,
                String actorRole,
                String actorTenantId,
                String actorBranchId,
                String targetTenantId,
                String targetBranchId,
                String saleId,
                BigDecimal expectedGrandTotalBdt,
                String expectedPricingHash,
                boolean isOfflineSync,
                boolean subscriptionSuspended,
                boolean isOffline
        ) {
            return finalizeSale(
                    bearerToken, actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                    saleId, expectedGrandTotalBdt, expectedPricingHash, isOfflineSync, null, "",
                    subscriptionSuspended, isOffline
            );
        }

        public ClientOutcome<PosSaleOrderDto> finalizeSale(
                String bearerToken,
                String actorRole,
                String actorTenantId,
                String actorBranchId,
                String targetTenantId,
                String targetBranchId,
                String saleId,
                BigDecimal expectedGrandTotalBdt,
                String expectedPricingHash,
                boolean isOfflineSync,
                Map<String, String> batchOverrides,
                String overrideReason,
                boolean subscriptionSuspended,
                boolean isOffline
        ) {
            ClientOutcome<Void> auth = authorizeScope(
                    actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                    false, subscriptionSuspended, isOffline
            );
            if (auth.uiState != UiState.READY) {
                return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            if (expectedGrandTotalBdt != null) payload.put("expected_grand_total_bdt", expectedGrandTotalBdt.setScale(2, RoundingMode.HALF_UP).toPlainString());
            if (expectedPricingHash != null && !expectedPricingHash.isBlank()) payload.put("expected_pricing_hash", expectedPricingHash);
            if (isOfflineSync) payload.put("is_offline_sync", true);
            if (batchOverrides != null && !batchOverrides.isEmpty()) payload.put("batch_overrides", batchOverrides);
            if (overrideReason != null && !overrideReason.isBlank()) payload.put("override_reason", overrideReason.trim());
            return executeSaleMutation(
                    "POST",
                    "/api/v2/pos/sales/" + saleId + "/finalize/",
                    payload,
                    bearerToken,
                    "POS sale finalized and FEFO batches committed cleanly.",
                    "পিওএস বিক্রয় এবং FEFO ব্যাচ বরাদ্দ সফলভাবে সম্পন্ন হয়েছে।"
            );
        }

        public ClientOutcome<FefoAllocationPlanDto> getFefoPlan(
                String bearerToken,
                String actorRole,
                String actorTenantId,
                String actorBranchId,
                String targetTenantId,
                String targetBranchId,
                String saleId,
                boolean subscriptionSuspended,
                boolean isOffline
        ) {
            ClientOutcome<Void> auth = authorizeReadScope(
                    actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                    subscriptionSuspended, isOffline
            );
            if (auth.uiState != UiState.READY) {
                return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
            }
            try {
                ApiResponseResult res = transport.send("GET", "/api/v2/pos/sales/" + saleId + "/fefo-plan/", null, bearerToken);
                if (res.statusCode == 200) {
                    FefoAllocationPlanDto dto = FefoAllocationPlanDto.fromMap(parseJsonMap(res.body));
                    currentState = dto.lines.isEmpty() ? UiState.EMPTY : UiState.READY;
                    return new ClientOutcome<>(currentState, dto, dto.blockingErrorCode,
                            dto.fefoReady ? "FEFO lot allocation plan ready." : strOr(dto.blockingReasonEn, "FEFO lot block detected."),
                            dto.fefoReady ? "FEFO লট বরাদ্দ পরিকল্পনা প্রস্তুত।" : strOr(dto.blockingReasonBn, "FEFO স্টক ব্লক শনাক্ত হয়েছে।"));
                }
                if (res.statusCode == 401 || res.statusCode == 403) {
                    currentState = UiState.PERMISSION_DENIED;
                    return new ClientOutcome<>(UiState.PERMISSION_DENIED, null,
                            extractJsonStr(res.body, "error_code", "ERR_PERMISSION_DENIED"),
                            extractJsonStr(res.body, "message_en", "Permission denied inspecting FEFO plan."),
                            extractJsonStr(res.body, "message_bn", "FEFO পরিকল্পনা দেখার অনুমতি নেই।"));
                }
                currentState = UiState.ERROR;
                return new ClientOutcome<>(UiState.ERROR, null,
                        extractJsonStr(res.body, "error_code", "ERR_HTTP_" + res.statusCode),
                        extractJsonStr(res.body, "message_en", "Failed to load FEFO allocation plan."),
                        extractJsonStr(res.body, "message_bn", "FEFO পরিকল্পনা লোড করা যায়নি।"));
            } catch (Exception ex) {
                currentState = UiState.OFFLINE;
                return new ClientOutcome<>(UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                        "Network unreachable: " + ex.getMessage(),
                        "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন।");
            }
        }

        public ClientOutcome<PosSaleOrderDto> allocateStockFefo(
                String bearerToken,
                String actorRole,
                String actorTenantId,
                String actorBranchId,
                String targetTenantId,
                String targetBranchId,
                String saleId,
                Map<String, String> batchOverrides,
                String overrideReason,
                Integer expectedVersion,
                boolean subscriptionSuspended,
                boolean isOffline
        ) {
            boolean hasOverride = batchOverrides != null && !batchOverrides.isEmpty();
            ClientOutcome<Void> auth = authorizeScope(
                    actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                    false, subscriptionSuspended, isOffline
            );
            if (auth.uiState != UiState.READY) {
                return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
            }
            if (hasOverride) {
                String cleanReason = overrideReason != null ? overrideReason.trim() : "";
                if (!cleanReason.isEmpty() && cleanReason.length() < 3) {
                    return new ClientOutcome<>(
                            UiState.ERROR, null, "ERR_FEFO_OVERRIDE_REASON_REQUIRED",
                            "Override reason (>= 3 chars) is mandatory when bypassing FEFO order.",
                            "FEFO ক্রম পরিবর্তনের জন্য কমপক্ষে ৩ অক্ষরের কারণ উল্লেখ করা আবশ্যক।"
                    );
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            if (hasOverride) payload.put("batch_overrides", batchOverrides);
            if (overrideReason != null && !overrideReason.isBlank()) payload.put("override_reason", overrideReason.trim());
            if (expectedVersion != null) payload.put("expected_version", expectedVersion);
            return executeSaleMutation(
                    "POST",
                    "/api/v2/pos/sales/" + saleId + "/allocate/",
                    payload,
                    bearerToken,
                    "FEFO lots reserved atomically.",
                    "FEFO লটগুলো সফলভাবে রিজার্ভ হয়েছে।"
            );
        }

    public ClientOutcome<PosDeliveryOrderDto> transitionDelivery(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String deliveryId,
            String actionSlug,
            Map<String, Object> payload,
            String successEn,
            String successBn,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        currentState = UiState.LOADING;
        return executeDeliveryMutation(
                "POST",
                "/api/v2/pos/deliveries/" + deliveryId + "/" + actionSlug + "/",
                payload != null ? payload : Collections.emptyMap(),
                bearerToken,
                successEn,
                successBn
        );
    }

    public ClientOutcome<AddressRevealResultDto> revealDeliveryAddress(
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorBranchId,
            String targetTenantId,
            String targetBranchId,
            String deliveryId,
            String reason,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorBranchId, targetTenantId, targetBranchId,
                false, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }
        if (reason == null || reason.trim().length() < 3) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_REASON_REQUIRED",
                    "Reason (>= 3 chars) is mandatory to reveal unmasked delivery address.",
                    "পূর্ণ ঠিকানা দেখতে কমপক্ষে ৩ অক্ষরের কারণ উল্লেখ করুন।"
            );
        }
        currentState = UiState.LOADING;
        Map<String, Object> payload = Map.of("reason", reason.trim());
        try {
            String json = mapper.writeValueAsString(payload);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/deliveries/" + deliveryId + "/reveal-address/", json, bearerToken);
            if (res.statusCode == 200) {
                AddressRevealResultDto dto = AddressRevealResultDto.fromMap(parseJsonMap(res.body));
                currentState = UiState.READY;
                return new ClientOutcome<>(
                        UiState.READY, dto, "",
                        "Operational dispatch address revealed and audited.",
                        "ডিসপ্যাচের জন্য পূর্ণ ঠিকানা প্রদর্শিত ও অডিট হয়েছে।"
                );
            } else if (res.statusCode == 403 || res.statusCode == 401) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED, null,
                        extractJsonStr(res.body, "error_code", "ERR_PERMISSION_DENIED"),
                        extractJsonStr(res.body, "message_en", "Permission denied revealing address."),
                        extractJsonStr(res.body, "message_bn", "পূর্ণ ঠিকানা দেখার অনুমতি নেই।")
                );
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null,
                    extractJsonStr(res.body, "error_code", "ERR_ADDRESS_PRIVACY_LOCKED"),
                    extractJsonStr(res.body, "message_en", "Address privacy locked or invalid request."),
                    extractJsonStr(res.body, "message_bn", "ঠিকানার গোপনীয়তা লক আছে বা অনুরোধটি সঠিক নয়।")
            );
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable revealing address: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন।"
            );
        }
    }

    private ClientOutcome<PosDeliveryOrderDto> executeDeliveryMutation(
            String method,
            String path,
            Map<String, Object> payload,
            String bearerToken,
            String successEn,
            String successBn
    ) {
        try {
            String json = payload != null ? mapper.writeValueAsString(payload) : "";
            ApiResponseResult res = transport.send(method, path, json, bearerToken);
            if (res.statusCode == 200 || res.statusCode == 201) {
                Map<?, ?> rawMap = parseJsonMap(res.body);
                PosDeliveryOrderDto dto = PosDeliveryOrderDto.fromMap(rawMap);
                currentState = UiState.READY;
                return new ClientOutcome<>(UiState.READY, dto, "", successEn, successBn);
            } else if (res.statusCode == 403 || res.statusCode == 401) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED, null,
                        extractJsonStr(res.body, "error_code", "ERR_PERMISSION_DENIED"),
                        extractJsonStr(res.body, "message_en", "Permission denied modifying delivery."),
                        extractJsonStr(res.body, "message_bn", "ডেলিভারি পরিবর্তনের অনুমতি নেই।")
                );
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null,
                    extractJsonStr(res.body, "error_code", "ERR_VALIDATION_FAILED"),
                    extractJsonStr(res.body, "message_en", "Delivery operation failed (HTTP " + res.statusCode + ")."),
                    extractJsonStr(res.body, "message_bn", "ডেলিভারি অপারেশন ব্যর্থ হয়েছে (HTTP " + res.statusCode + ")।")
            );
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন। সার্ভারে পৌঁছানো যাচ্ছে না।"
            );
        }
    }

    public ClientOutcome<List<PosSaleOrderDto>> listSales(String branchId, String bearerToken, boolean isOffline) {
        if (isOffline) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, Collections.emptyList(), "ERR_OFFLINE_UNAVAILABLE",
                    UiState.OFFLINE.defaultMessageEn, UiState.OFFLINE.defaultMessageBn
            );
        }
        currentState = UiState.LOADING;
        String path = "/api/v2/pos/sales/" + (branchId != null && !branchId.isBlank() ? "?branch=" + branchId : "");
        try {
            ApiResponseResult res = transport.send("GET", path, null, bearerToken);
            if (res.statusCode == 200) {
                Object parsed = mapper.readValue(res.body, Object.class);
                List<?> rawList;
                if (parsed instanceof Map<?, ?> m && m.get("results") instanceof List<?> r) {
                    rawList = r;
                } else if (parsed instanceof List<?> l) {
                    rawList = l;
                } else {
                    rawList = Collections.emptyList();
                }
                List<PosSaleOrderDto> orders = new ArrayList<>();
                for (Object item : rawList) {
                    if (item instanceof Map<?, ?> om) orders.add(PosSaleOrderDto.fromMap(om));
                }
                if (orders.isEmpty()) {
                    currentState = UiState.EMPTY;
                    return new ClientOutcome<>(
                            UiState.EMPTY, orders, "",
                            UiState.EMPTY.defaultMessageEn, UiState.EMPTY.defaultMessageBn
                    );
                }
                currentState = UiState.READY;
                return new ClientOutcome<>(
                        UiState.READY, orders, "",
                        "Loaded " + orders.size() + " POS sale order(s).",
                        orders.size() + "টি পিওএস সেলস অর্ডার লোড হয়েছে।"
                );
            } else if (res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED, Collections.emptyList(), "ERR_PERMISSION_DENIED",
                        "Server denied POS list access (HTTP 403).",
                        "সার্ভার পিওএস তালিকা দেখার অনুমতি প্রত্যাখ্যান করেছে (HTTP 403)।"
                );
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, Collections.emptyList(), "ERR_HTTP_" + res.statusCode,
                    "Unexpected HTTP " + res.statusCode, "অপ্রত্যাশিত সার্ভার ত্রুটি HTTP " + res.statusCode
            );
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, Collections.emptyList(), "ERR_NETWORK_UNREACHABLE",
                    "Network unreachable: " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন। সার্ভারে পৌঁছানো যাচ্ছে না।"
            );
        }
    }

    private ClientOutcome<PosSaleOrderDto> executeSaleMutation(
            String method,
            String path,
            Map<String, Object> payload,
            String bearerToken,
            String successEn,
            String successBn
    ) {
        try {
            String json = mapper.writeValueAsString(payload);
            ApiResponseResult res = transport.send(method, path, json, bearerToken);
            if (res.statusCode == 200 || res.statusCode == 201) {
                Map<?, ?> rawMap = mapper.readValue(res.body, Map.class);
                Object saleObj = rawMap.containsKey("sale") && rawMap.get("sale") instanceof Map<?, ?> s ? s : rawMap;
                PosSaleOrderDto dto = PosSaleOrderDto.fromMap((Map<?, ?>) saleObj);
                currentState = UiState.READY;
                return new ClientOutcome<>(UiState.READY, dto, "", successEn, successBn);
            } else if (res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                String code = extractJsonStr(res.body, "error_code", "ERR_PERMISSION_DENIED");
                String msgEn = extractJsonStr(res.body, "message_en", "Server denied POS mutation (HTTP 403): " + res.body);
                String msgBn = extractJsonStr(res.body, "message_bn", "সার্ভার পিওএস কাজের অনুমতি প্রত্যাখ্যান করেছে (HTTP 403)।");
                return new ClientOutcome<>(UiState.PERMISSION_DENIED, null, code, msgEn, msgBn);
            } else if (res.statusCode == 400 || res.statusCode == 405 || res.statusCode == 422) {
                currentState = UiState.ERROR;
                String code = extractJsonStr(res.body, "error_code", "ERR_VALIDATION_FAILED");
                String msgEn = extractJsonStr(res.body, "message_en", "POS validation failed (HTTP " + res.statusCode + "): " + res.body);
                String msgBn = extractJsonStr(res.body, "message_bn", "পিওএস ডেটা যাচাই ব্যর্থ হয়েছে (HTTP " + res.statusCode + ")।");
                return new ClientOutcome<>(UiState.ERROR, null, code, msgEn, msgBn);
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_HTTP_" + res.statusCode,
                    "Unexpected HTTP " + res.statusCode, "সার্ভার ত্রুটি HTTP " + res.statusCode
            );
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                    "Network failure connecting to " + baseUrl + path + ": " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন। সার্ভারে পৌঁছানো যাচ্ছে না।"
            );
        }
    }

    private Map<?, ?> parseJsonMap(String body) {
        try {
            if (body == null || body.isBlank()) return Collections.emptyMap();
            Map<?, ?> parsed = mapper.readValue(body, Map.class);
            return parsed != null ? parsed : Collections.emptyMap();
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private String extractJsonStr(String body, String key, String fallback) {
        try {
            if (body == null || body.isBlank()) return fallback;
            Map<?, ?> m = mapper.readValue(body, Map.class);
            Object v = m.get(key);
            if (v != null && !v.toString().isBlank()) return v.toString();
        } catch (Exception ignored) {}
        return fallback;
    }

    private static String str(Object o) { return o != null ? o.toString() : ""; }
    private static String strOr(Object o, String fallback) { return (o != null && !o.toString().isBlank()) ? o.toString() : fallback; }
    private static int integer(Object o, int fallback) { return o instanceof Number n ? n.intValue() : fallback; }
    private static BigDecimal dec2(Object o) {
        if (o == null || o.toString().isBlank()) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return new BigDecimal(o.toString().trim()).setScale(2, RoundingMode.HALF_UP);
    }
    private static BigDecimal dec4(Object o) {
        if (o == null || o.toString().isBlank()) return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        return new BigDecimal(o.toString().trim()).setScale(4, RoundingMode.HALF_UP);
    }

    // =========================================================================
    // P231: Invoice Number Policy & Sequence Preview DTOs
    // =========================================================================

    public static class InvoiceNumberPolicyDto {
        public String policyId;
        public String tenantId;
        public String branchId;
        public String prefix;
        public String pattern;
        public String resetFrequency;
        public int paddingDigits;
        public String offlinePrefix;
        public boolean allowOfflinePreallocation;
        public boolean isActive;
        public String nextPreview;
    }

    public static class NextInvoiceNumberPreviewDto {
        public String invoiceNumber;
        public String policyId;
        public long sequence;
        public String periodKey;
        public String branchCode;
        public String deviceCode;
        public boolean isReserved;
    }

    public ClientOutcome<NextInvoiceNumberPreviewDto> previewNextInvoiceNumber(String branchId, String deviceNs) {
        return previewNextInvoiceNumber(branchId, deviceNs, null);
    }

    public ClientOutcome<NextInvoiceNumberPreviewDto> previewNextInvoiceNumber(String branchId, String deviceNs, String bearerToken) {
        String query = "?device=" + (deviceNs != null ? deviceNs : "ONLINE");
        if (branchId != null && !branchId.isBlank()) {
            query += "&branch_id=" + branchId;
        }
        try {
            ApiResponseResult res = transport.send("GET", "/api/v2/pos/invoice-policies/preview-next/" + query, "", bearerToken);
            if (res.statusCode == 200) {
                Map<?, ?> rawMap = parseJsonMap(res.body);
                NextInvoiceNumberPreviewDto dto = new NextInvoiceNumberPreviewDto();
                dto.invoiceNumber = str(rawMap.get("invoice_number"));
                dto.policyId = str(rawMap.get("policy_id"));
                dto.sequence = rawMap.get("sequence") instanceof Number n ? n.longValue() : 1L;
                dto.periodKey = str(rawMap.get("period_key"));
                dto.branchCode = str(rawMap.get("branch_code"));
                dto.deviceCode = str(rawMap.get("device_code"));
                dto.isReserved = Boolean.TRUE.equals(rawMap.get("is_reserved"));
                currentState = UiState.READY;
                return new ClientOutcome<>(UiState.READY, dto, "", "Preview loaded", "প্রিভিউ লোড হয়েছে");
            } else if (res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(UiState.PERMISSION_DENIED, null, "ERR_PERMISSION_DENIED", "Permission denied", "অনুমতি নেই");
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(UiState.ERROR, null, "ERR_HTTP_" + res.statusCode, "HTTP " + res.statusCode, "সার্ভার ত্রুটি");
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE", ex.getMessage(), "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন");
        }
    }

    // =========================================================================
    // P232: Receipt Printing & Reprint DTOs and API Calls
    // =========================================================================

    public static class ReceiptPrintResponseDto {
        public String printId = "";
        public String saleId = "";
        public String invoiceNumber = "";
        public String printFormat = "THERMAL_80MM";
        public int reprintCount = 0;
        public boolean isDuplicate = false;
        public String printerStatus = "READY";
        public String watermarkText = "";
        public String reprintReason = "";
        public String rawReceiptText = "";
        public String printedAt = "";
    }

    public ClientOutcome<ReceiptPrintResponseDto> previewReceipt(String saleId, String format) {
        return previewReceipt(saleId, format, null);
    }

    public ClientOutcome<ReceiptPrintResponseDto> previewReceipt(String saleId, String format, String bearerToken) {
        String fmt = format != null ? format : "THERMAL_80MM";
        try {
            ApiResponseResult res = transport.send("GET", "/api/v2/pos/sales/" + saleId + "/receipt-preview/?print_format=" + fmt, "", bearerToken);
            if (res.statusCode == 200) {
                Map<?, ?> raw = parseJsonMap(res.body);
                ReceiptPrintResponseDto dto = new ReceiptPrintResponseDto();
                dto.saleId = str(raw.get("sale_id"));
                dto.invoiceNumber = str(raw.get("invoice_number"));
                dto.printFormat = str(raw.get("print_format"));
                dto.reprintCount = integer(raw.get("reprint_count"), 0);
                dto.isDuplicate = Boolean.TRUE.equals(raw.get("is_duplicate"));
                dto.watermarkText = str(raw.get("watermark_text"));
                dto.rawReceiptText = str(raw.get("raw_receipt_text"));
                currentState = UiState.READY;
                return new ClientOutcome<>(UiState.READY, dto, "", "Receipt preview ready", "রসিদ প্রিভিউ প্রস্তুত");
            } else if (res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(UiState.PERMISSION_DENIED, null, "ERR_PERMISSION_DENIED", "Permission denied", "অনুমতি নেই");
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(UiState.ERROR, null, "ERR_HTTP_" + res.statusCode, "HTTP " + res.statusCode, "সার্ভার ত্রুটি");
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE", ex.getMessage(), "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন");
        }
    }

    public ClientOutcome<ReceiptPrintResponseDto> printReceipt(String saleId, String format, String printerStatus, String reprintReason) {
        return printReceipt(saleId, format, printerStatus, reprintReason, null);
    }

    public ClientOutcome<ReceiptPrintResponseDto> printReceipt(
            String saleId, String format, String printerStatus, String reprintReason, String bearerToken
    ) {
        try {
            Map<String, Object> bodyMap = new LinkedHashMap<>();
            bodyMap.put("print_format", format != null ? format : "THERMAL_80MM");
            bodyMap.put("printer_status", printerStatus != null ? printerStatus : "READY");
            if (reprintReason != null && !reprintReason.isBlank()) {
                bodyMap.put("reprint_reason", reprintReason.trim());
            }
            String json = mapper.writeValueAsString(bodyMap);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/sales/" + saleId + "/print-receipt/", json, bearerToken);
            if (res.statusCode == 201 || res.statusCode == 200) {
                Map<?, ?> raw = parseJsonMap(res.body);
                ReceiptPrintResponseDto dto = new ReceiptPrintResponseDto();
                dto.printId = str(raw.get("print_id"));
                dto.saleId = str(raw.get("sale_id"));
                dto.invoiceNumber = str(raw.get("invoice_number"));
                dto.printFormat = str(raw.get("print_format"));
                dto.reprintCount = integer(raw.get("reprint_count"), 0);
                dto.isDuplicate = Boolean.TRUE.equals(raw.get("is_duplicate"));
                dto.printerStatus = str(raw.get("printer_status"));
                dto.watermarkText = str(raw.get("watermark_text"));
                dto.reprintReason = str(raw.get("reprint_reason"));
                dto.rawReceiptText = str(raw.get("raw_receipt_text"));
                dto.printedAt = str(raw.get("printed_at"));
                currentState = UiState.READY;
                return new ClientOutcome<>(UiState.READY, dto, "", "Receipt printed successfully", "রসিদ সফলভাবে মুদ্রিত হয়েছে");
            } else if (res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(UiState.PERMISSION_DENIED, null, "ERR_PERMISSION_DENIED", "Permission denied", "অনুমতি নেই");
            } else if (res.statusCode == 400 || res.statusCode == 422) {
                currentState = UiState.ERROR;
                String errCode = extractJsonStr(res.body, "error_code", "ERR_VALIDATION_FAILED");
                String reason = extractJsonStr(res.body, "reprint_reason", "Invalid print request.");
                return new ClientOutcome<>(UiState.ERROR, null, errCode, reason, reason);
            }
            currentState = UiState.ERROR;
            return new ClientOutcome<>(UiState.ERROR, null, "ERR_HTTP_" + res.statusCode, "HTTP " + res.statusCode, "সার্ভার ত্রুটি");
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE", ex.getMessage(), "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন");
        }
    }

    // =========================================================================
    // P233: Cashier Shift Open-Close, Cash Drawer & Variance Approval
    // =========================================================================

    public static class PosShiftCashMovementDto {
        public String movementId = "";
        public String shiftId = "";
        public String movementType = "PAYOUT_EXPENSE";
        public BigDecimal amountBdt = BigDecimal.ZERO;
        public String reason = "";
        public String recipientOrSource = "";
        public String createdByEmail = "";
        public String createdAt = "";
    }

    public static class PosCashierShiftDto {
        public String shiftId = "";
        public String shiftNumber = "";
        public String cashierUserId = "";
        public String cashierEmail = "";
        public String cashierName = "";
        public String deviceCode = "DEFAULT";
        public String status = "OPEN";
        public BigDecimal openingFloatBdt = BigDecimal.ZERO;
        public BigDecimal totalCashSalesBdt = BigDecimal.ZERO;
        public BigDecimal totalCardSalesBdt = BigDecimal.ZERO;
        public BigDecimal totalDigitalMfsSalesBdt = BigDecimal.ZERO;
        public BigDecimal totalCreditSalesBdt = BigDecimal.ZERO;
        public BigDecimal totalCashRefundsBdt = BigDecimal.ZERO;
        public BigDecimal totalPayoutsBdt = BigDecimal.ZERO;
        public BigDecimal totalCashInBdt = BigDecimal.ZERO;
        public BigDecimal expectedDrawerCashBdt = BigDecimal.ZERO;
        public BigDecimal closingCashCountedBdt = null;
        public BigDecimal varianceBdt = null;
        public String varianceReason = "";
        public boolean requiresManagerApproval = false;
        public String approvedByEmail = "";
        public String managerApprovalNotes = "";
        public String openedAt = "";
        public String closedAt = "";
        public String reconciledAt = "";
        public List<PosShiftCashMovementDto> cashMovements = new ArrayList<>();
    }

    public ClientOutcome<PosCashierShiftDto> openShift(String branchId, String deviceCode, BigDecimal openingFloat, String bearerToken) {
        try {
            Map<String, Object> bodyMap = new LinkedHashMap<>();
            if (branchId != null) bodyMap.put("branch_id", branchId);
            bodyMap.put("device_code", deviceCode != null ? deviceCode : "DEFAULT");
            bodyMap.put("opening_float_bdt", openingFloat != null ? openingFloat.toPlainString() : "0.00");

            String json = mapper.writeValueAsString(bodyMap);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/shifts/open/", json, bearerToken);
            if (res.statusCode == 201 || res.statusCode == 200) {
                PosCashierShiftDto dto = parseShiftDto(parseJsonMap(res.body));
                currentState = UiState.READY;
                return new ClientOutcome<>(UiState.READY, dto, "", "Shift opened successfully", "ক্যাশিয়ার শিফট সফলভাবে শুরু হয়েছে");
            } else if (res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(UiState.PERMISSION_DENIED, null, "ERR_PERMISSION_DENIED", "Permission denied", "অনুমতি নেই");
            } else {
                currentState = UiState.ERROR;
                String errCode = extractJsonStr(res.body, "error_code", "ERR_SHIFT_OPEN_FAILED");
                String msg = extractJsonStr(res.body, "shift", "Shift open failed.");
                return new ClientOutcome<>(UiState.ERROR, null, errCode, msg, msg);
            }
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE", ex.getMessage(), "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন");
        }
    }

    public ClientOutcome<PosCashierShiftDto> getActiveShift(String bearerToken) {
        try {
            ApiResponseResult res = transport.send("GET", "/api/v2/pos/shifts/active/", "", bearerToken);
            if (res.statusCode == 200) {
                PosCashierShiftDto dto = parseShiftDto(parseJsonMap(res.body));
                currentState = UiState.READY;
                return new ClientOutcome<>(UiState.READY, dto, "", "Active shift loaded", "চলমান শিফট লোড হয়েছে");
            } else if (res.statusCode == 404) {
                currentState = UiState.EMPTY;
                return new ClientOutcome<>(UiState.EMPTY, null, "ERR_NO_ACTIVE_SHIFT", "No active shift", "কোনো চলমান শিফট নেই");
            } else {
                currentState = UiState.ERROR;
                return new ClientOutcome<>(UiState.ERROR, null, "ERR_HTTP_" + res.statusCode, "HTTP " + res.statusCode, "সার্ভার ত্রুটি");
            }
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE", ex.getMessage(), "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন");
        }
    }

    public ClientOutcome<PosShiftCashMovementDto> recordCashMovement(
            String shiftId, String movementType, BigDecimal amount, String reason, String recipient, String bearerToken
    ) {
        try {
            Map<String, Object> bodyMap = new LinkedHashMap<>();
            bodyMap.put("movement_type", movementType);
            bodyMap.put("amount_bdt", amount != null ? amount.toPlainString() : "0.00");
            bodyMap.put("reason", reason);
            if (recipient != null) bodyMap.put("recipient_or_source", recipient);

            String json = mapper.writeValueAsString(bodyMap);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/shifts/" + shiftId + "/cash-movement/", json, bearerToken);
            if (res.statusCode == 201 || res.statusCode == 200) {
                Map<?, ?> raw = parseJsonMap(res.body);
                PosShiftCashMovementDto dto = new PosShiftCashMovementDto();
                dto.movementId = str(raw.get("movement_id"));
                dto.shiftId = str(raw.get("shift_id"));
                dto.movementType = str(raw.get("movement_type"));
                dto.amountBdt = dec2(raw.get("amount_bdt"));
                dto.reason = str(raw.get("reason"));
                dto.recipientOrSource = str(raw.get("recipient_or_source"));
                dto.createdAt = str(raw.get("created_at"));
                currentState = UiState.READY;
                return new ClientOutcome<>(UiState.READY, dto, "", "Cash movement recorded", "ক্যাশ মুভমেন্ট সফলভাবে রেকর্ড হয়েছে");
            } else {
                currentState = UiState.ERROR;
                String errCode = extractJsonStr(res.body, "error_code", "ERR_MOVEMENT_FAILED");
                String msg = extractJsonStr(res.body, "amount_bdt", "Cash movement failed.");
                return new ClientOutcome<>(UiState.ERROR, null, errCode, msg, msg);
            }
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE", ex.getMessage(), "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন");
        }
    }

    public ClientOutcome<PosCashierShiftDto> closeShift(
            String shiftId, BigDecimal closingCashCounted, String varianceReason, String bearerToken
    ) {
        try {
            Map<String, Object> bodyMap = new LinkedHashMap<>();
            bodyMap.put("closing_cash_counted_bdt", closingCashCounted != null ? closingCashCounted.toPlainString() : "0.00");
            if (varianceReason != null) bodyMap.put("variance_reason", varianceReason);

            String json = mapper.writeValueAsString(bodyMap);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/shifts/" + shiftId + "/close/", json, bearerToken);
            if (res.statusCode == 200) {
                PosCashierShiftDto dto = parseShiftDto(parseJsonMap(res.body));
                currentState = UiState.READY;
                return new ClientOutcome<>(UiState.READY, dto, "", "Shift closed", "শিফট সম্পন্ন হয়েছে");
            } else {
                currentState = UiState.ERROR;
                String errCode = extractJsonStr(res.body, "error_code", "ERR_CLOSE_FAILED");
                String msg = extractJsonStr(res.body, "variance_reason", "Shift close failed.");
                return new ClientOutcome<>(UiState.ERROR, null, errCode, msg, msg);
            }
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE", ex.getMessage(), "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন");
        }
    }

    public ClientOutcome<PosCashierShiftDto> approveVariance(String shiftId, String managerNotes, String bearerToken) {
        try {
            Map<String, Object> bodyMap = new LinkedHashMap<>();
            if (managerNotes != null) bodyMap.put("manager_approval_notes", managerNotes);

            String json = mapper.writeValueAsString(bodyMap);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/shifts/" + shiftId + "/approve-variance/", json, bearerToken);
            if (res.statusCode == 200) {
                PosCashierShiftDto dto = parseShiftDto(parseJsonMap(res.body));
                currentState = UiState.READY;
                return new ClientOutcome<>(UiState.READY, dto, "", "Variance approved", "ভ্যারিয়েন্স অনুমোদিত হয়েছে");
            } else if (res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(UiState.PERMISSION_DENIED, null, "ERR_PERMISSION_DENIED", "Permission denied", "অনুমতি নেই");
            } else {
                currentState = UiState.ERROR;
                String errCode = extractJsonStr(res.body, "error_code", "ERR_APPROVAL_FAILED");
                String msg = extractJsonStr(res.body, "user", "Variance approval failed.");
                return new ClientOutcome<>(UiState.ERROR, null, errCode, msg, msg);
            }
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE", ex.getMessage(), "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন");
        }
    }

    private PosCashierShiftDto parseShiftDto(Map<?, ?> raw) {
        PosCashierShiftDto dto = new PosCashierShiftDto();
        dto.shiftId = str(raw.get("shift_id"));
        dto.shiftNumber = str(raw.get("shift_number"));
        dto.cashierUserId = str(raw.get("cashier_user_id"));
        dto.cashierEmail = str(raw.get("cashier_email"));
        dto.cashierName = str(raw.get("cashier_name"));
        dto.deviceCode = str(raw.get("device_code"));
        dto.status = str(raw.get("status"));
        dto.openingFloatBdt = dec2(raw.get("opening_float_bdt"));
        dto.totalCashSalesBdt = dec2(raw.get("total_cash_sales_bdt"));
        dto.totalCardSalesBdt = dec2(raw.get("total_card_sales_bdt"));
        dto.totalDigitalMfsSalesBdt = dec2(raw.get("total_digital_mfs_sales_bdt"));
        dto.totalCreditSalesBdt = dec2(raw.get("total_credit_sales_bdt"));
        dto.totalCashRefundsBdt = dec2(raw.get("total_cash_refunds_bdt"));
        dto.totalPayoutsBdt = dec2(raw.get("total_payouts_bdt"));
        dto.totalCashInBdt = dec2(raw.get("total_cash_in_bdt"));
        dto.expectedDrawerCashBdt = dec2(raw.get("expected_drawer_cash_bdt"));
        dto.closingCashCountedBdt = raw.get("closing_cash_counted_bdt") != null ? dec2(raw.get("closing_cash_counted_bdt")) : null;
        dto.varianceBdt = raw.get("variance_bdt") != null ? dec2(raw.get("variance_bdt")) : null;
        dto.varianceReason = str(raw.get("variance_reason"));
        dto.requiresManagerApproval = Boolean.TRUE.equals(raw.get("requires_manager_approval"));
        dto.approvedByEmail = str(raw.get("approved_by_email"));
        dto.managerApprovalNotes = str(raw.get("manager_approval_notes"));
        dto.openedAt = str(raw.get("opened_at"));
        dto.closedAt = str(raw.get("closed_at"));
        dto.reconciledAt = str(raw.get("reconciled_at"));
        return dto;
    }

    // =========================================================================
    // P234: Sales Refund and Return
    // =========================================================================

    public static class PosSaleReturnLineDto {
        public String returnLineId = "";
        public String saleLineId = "";
        public String productId = "";
        public String productBrandName = "";
        public String batchNumber = "";
        public String packagingUnit = "TABLET";
        public int quantityUnits = 1;
        public BigDecimal refundAmountBdt = BigDecimal.ZERO;
        public String safetyDisposition = "RESTOCKABLE";
        public String dispositionNotes = "";
    }

    public static class PosSaleReturnDto {
        public String returnId = "";
        public String returnNumber = "";
        public String originalSaleId = "";
        public String originalOrderNumber = "";
        public String shiftId = "";
        public String status = "COMPLETED";
        public String refundMethod = "CASH";
        public BigDecimal totalRefundAmountBdt = BigDecimal.ZERO;
        public String reason = "";
        public String customerName = "";
        public String customerPhone = "";
        public String createdAt = "";
        public List<PosSaleReturnLineDto> lines = new ArrayList<>();
    }

    public ClientOutcome<PosSaleReturnDto> processReturn(
            String saleId, List<Map<String, Object>> lines, String refundMethod, String reason, String customerName, String customerPhone, String bearerToken
    ) {
        try {
            Map<String, Object> bodyMap = new LinkedHashMap<>();
            bodyMap.put("sale_id", saleId);
            bodyMap.put("lines", lines != null ? lines : Collections.emptyList());
            bodyMap.put("refund_method", refundMethod != null ? refundMethod : "CASH");
            bodyMap.put("reason", reason);
            if (customerName != null) bodyMap.put("customer_name", customerName);
            if (customerPhone != null) bodyMap.put("customer_phone", customerPhone);

            String json = mapper.writeValueAsString(bodyMap);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/returns/", json, bearerToken);
            if (res.statusCode == 201 || res.statusCode == 200) {
                Map<?, ?> raw = parseJsonMap(res.body);
                PosSaleReturnDto dto = parseSaleReturnDto(raw);
                currentState = UiState.READY;
                return new ClientOutcome<>(UiState.READY, dto, "", "Return processed successfully", "রিটার্ন ও রিফান্ড সফলভাবে সম্পন্ন হয়েছে");
            } else if (res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(UiState.PERMISSION_DENIED, null, "ERR_PERMISSION_DENIED", "Permission denied", "অনুমতি নেই");
            } else {
                currentState = UiState.ERROR;
                String errCode = extractJsonStr(res.body, "error_code", "ERR_RETURN_FAILED");
                String msg = extractJsonStr(res.body, "reason", "Return processing failed.");
                return new ClientOutcome<>(UiState.ERROR, null, errCode, msg, msg);
            }
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE", ex.getMessage(), "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন");
        }
    }

    private PosSaleReturnDto parseSaleReturnDto(Map<?, ?> raw) {
        PosSaleReturnDto dto = new PosSaleReturnDto();
        dto.returnId = str(raw.get("return_id"));
        dto.returnNumber = str(raw.get("return_number"));
        dto.originalSaleId = str(raw.get("original_sale_id"));
        dto.originalOrderNumber = str(raw.get("original_order_number"));
        dto.shiftId = str(raw.get("shift_id"));
        dto.status = str(raw.get("status"));
        dto.refundMethod = str(raw.get("refund_method"));
        dto.totalRefundAmountBdt = dec2(raw.get("total_refund_amount_bdt"));
        dto.reason = str(raw.get("reason"));
        dto.customerName = str(raw.get("customer_name"));
        dto.customerPhone = str(raw.get("customer_phone"));
        dto.createdAt = str(raw.get("created_at"));

        Object rawLines = raw.get("lines");
        if (rawLines instanceof List<?> list) {
            for (Object obj : list) {
                if (obj instanceof Map<?, ?> m) {
                    PosSaleReturnLineDto lineDto = new PosSaleReturnLineDto();
                    lineDto.returnLineId = str(m.get("return_line_id"));
                    lineDto.saleLineId = str(m.get("sale_line_id"));
                    lineDto.productId = str(m.get("product_id"));
                    lineDto.productBrandName = str(m.get("product_brand_name"));
                    lineDto.batchNumber = str(m.get("batch_number"));
                    lineDto.packagingUnit = str(m.get("packaging_unit"));
                    lineDto.quantityUnits = integer(m.get("quantity_units"), 1);
                    lineDto.refundAmountBdt = dec2(m.get("refund_amount_bdt"));
                    lineDto.safetyDisposition = str(m.get("safety_disposition"));
                    lineDto.dispositionNotes = str(m.get("disposition_notes"));
                    dto.lines.add(lineDto);
                }
            }
        }
        return dto;
    }

    public static class PosDailySalesCloseDto {
        public String closeId = "";
        public String closeNumber = "";
        public String tenantId = "";
        public String branchId = "";
        public String closingDate = "";
        public String status = "DRAFT";
        public int salesCount = 0;
        public BigDecimal grossSalesBdt = BigDecimal.ZERO;
        public BigDecimal discountTotalBdt = BigDecimal.ZERO;
        public BigDecimal taxTotalBdt = BigDecimal.ZERO;
        public BigDecimal netSalesBdt = BigDecimal.ZERO;
        public BigDecimal cashSalesBdt = BigDecimal.ZERO;
        public BigDecimal digitalSalesBdt = BigDecimal.ZERO;
        public BigDecimal dueSalesBdt = BigDecimal.ZERO;
        public int returnsCount = 0;
        public BigDecimal returnsTotalBdt = BigDecimal.ZERO;
        public BigDecimal cashRefundsBdt = BigDecimal.ZERO;
        public BigDecimal digitalRefundsBdt = BigDecimal.ZERO;
        public BigDecimal dueCollectionsCashBdt = BigDecimal.ZERO;
        public BigDecimal dueCollectionsDigitalBdt = BigDecimal.ZERO;
        public int shiftsClosedCount = 0;
        public int unclosedShiftsCount = 0;
        public BigDecimal openingFloatTotalBdt = BigDecimal.ZERO;
        public BigDecimal cashPayoutsBdt = BigDecimal.ZERO;
        public BigDecimal expectedDrawerCashBdt = BigDecimal.ZERO;
        public BigDecimal actualDrawerCashBdt = BigDecimal.ZERO;
        public BigDecimal cashVarianceBdt = BigDecimal.ZERO;
        public int ledgerEntriesCount = 0;
        public BigDecimal ledgerDebitTotalBdt = BigDecimal.ZERO;
        public BigDecimal ledgerCreditTotalBdt = BigDecimal.ZERO;
        public boolean isLedgerBalanced = true;
        public String sha256Hash = "";
        public String notes = "";
    }

    public ClientOutcome<PosDailySalesCloseDto> submitDailyClose(String closingDate, BigDecimal actualDrawerCash, String notes, String bearerToken) {
        try {
            Map<String, Object> bodyMap = new LinkedHashMap<>();
            if (closingDate != null && !closingDate.isBlank()) {
                bodyMap.put("closing_date", closingDate);
            }
            bodyMap.put("actual_drawer_cash_bdt", (actualDrawerCash != null ? actualDrawerCash : BigDecimal.ZERO).toPlainString());
            bodyMap.put("notes", notes != null ? notes : "");

            String json = mapper.writeValueAsString(bodyMap);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/daily-close/submit/", json, bearerToken);
            if (res.statusCode == 201 || res.statusCode == 200) {
                Map<?, ?> raw = parseJsonMap(res.body);
                PosDailySalesCloseDto dto = parseDailyCloseDto(raw);
                currentState = UiState.READY;
                return new ClientOutcome<>(UiState.READY, dto, "", "Daily close submitted successfully", "দৈনিক সেলস ক্লোজ দাখিল সফল হয়েছে");
            } else if (res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(UiState.PERMISSION_DENIED, null, "ERR_PERMISSION_DENIED", "Permission denied", "অনুমতি নেই");
            } else {
                currentState = UiState.ERROR;
                String errCode = extractJsonStr(res.body, "error_code", "ERR_DAILY_CLOSE_FAILED");
                String msg = extractJsonStr(res.body, "message_en", "Daily close submission failed.");
                return new ClientOutcome<>(UiState.ERROR, null, errCode, msg, "দৈনিক ক্লোজ দাখিল ব্যর্থ হয়েছে");
            }
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE", ex.getMessage(), "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন");
        }
    }

    public ClientOutcome<PosDailySalesCloseDto> approveDailyClose(String closeId, String managerNotes, String bearerToken) {
        try {
            Map<String, Object> bodyMap = new LinkedHashMap<>();
            bodyMap.put("manager_notes", managerNotes != null ? managerNotes : "");

            String json = mapper.writeValueAsString(bodyMap);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/daily-close/" + closeId + "/approve/", json, bearerToken);
            if (res.statusCode == 200) {
                Map<?, ?> raw = parseJsonMap(res.body);
                PosDailySalesCloseDto dto = parseDailyCloseDto(raw);
                currentState = UiState.READY;
                return new ClientOutcome<>(UiState.READY, dto, "", "Daily close approved and sealed successfully", "দৈনিক সেলস ক্লোজ অনুমোদিত ও সিল করা হয়েছে");
            } else if (res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(UiState.PERMISSION_DENIED, null, "ERR_PERMISSION_DENIED", "Permission denied", "অনুমতি নেই");
            } else {
                currentState = UiState.ERROR;
                String errCode = extractJsonStr(res.body, "error_code", "ERR_APPROVAL_FAILED");
                String msg = extractJsonStr(res.body, "message_en", "Daily close approval failed.");
                return new ClientOutcome<>(UiState.ERROR, null, errCode, msg, "দৈনিক ক্লোজ অনুমোদন ব্যর্থ হয়েছে");
            }
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE", ex.getMessage(), "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন");
        }
    }

    private PosDailySalesCloseDto parseDailyCloseDto(Map<?, ?> raw) {
        PosDailySalesCloseDto dto = new PosDailySalesCloseDto();
        dto.closeId = str(raw.get("close_id"));
        dto.closeNumber = str(raw.get("close_number"));
        dto.tenantId = str(raw.get("tenant_id"));
        dto.branchId = str(raw.get("branch_id"));
        dto.closingDate = str(raw.get("closing_date"));
        dto.status = str(raw.get("status"));
        dto.salesCount = integer(raw.get("sales_count"), 0);
        dto.grossSalesBdt = dec2(raw.get("gross_sales_bdt"));
        dto.discountTotalBdt = dec2(raw.get("discount_total_bdt"));
        dto.taxTotalBdt = dec2(raw.get("tax_total_bdt"));
        dto.netSalesBdt = dec2(raw.get("net_sales_bdt"));
        dto.cashSalesBdt = dec2(raw.get("cash_sales_bdt"));
        dto.digitalSalesBdt = dec2(raw.get("digital_sales_bdt"));
        dto.dueSalesBdt = dec2(raw.get("due_sales_bdt"));
        dto.returnsCount = integer(raw.get("returns_count"), 0);
        dto.returnsTotalBdt = dec2(raw.get("returns_total_bdt"));
        dto.cashRefundsBdt = dec2(raw.get("cash_refunds_bdt"));
        dto.digitalRefundsBdt = dec2(raw.get("digital_refunds_bdt"));
        dto.dueCollectionsCashBdt = dec2(raw.get("due_collections_cash_bdt"));
        dto.dueCollectionsDigitalBdt = dec2(raw.get("due_collections_digital_bdt"));
        dto.shiftsClosedCount = integer(raw.get("shifts_closed_count"), 0);
        dto.unclosedShiftsCount = integer(raw.get("unclosed_shifts_count"), 0);
        dto.openingFloatTotalBdt = dec2(raw.get("opening_float_total_bdt"));
        dto.cashPayoutsBdt = dec2(raw.get("cash_payouts_bdt"));
        dto.expectedDrawerCashBdt = dec2(raw.get("expected_drawer_cash_bdt"));
        dto.actualDrawerCashBdt = dec2(raw.get("actual_drawer_cash_bdt"));
        dto.cashVarianceBdt = dec2(raw.get("cash_variance_bdt"));
        dto.ledgerEntriesCount = integer(raw.get("ledger_entries_count"), 0);
        dto.ledgerDebitTotalBdt = dec2(raw.get("ledger_debit_total_bdt"));
        dto.ledgerCreditTotalBdt = dec2(raw.get("ledger_credit_total_bdt"));
        dto.isLedgerBalanced = Boolean.TRUE.equals(raw.get("is_ledger_balanced"));
        dto.sha256Hash = str(raw.get("sha256_hash"));
        dto.notes = str(raw.get("notes"));
        return dto;
    }
}


