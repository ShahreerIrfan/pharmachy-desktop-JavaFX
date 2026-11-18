package com.pharmacy.pos.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PosPaymentStatusReconciliationClient
 * Architecture Decision: ADR-0276 (P276).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * JavaFX Desktop POS client governing payment lifecycle status transitions (PENDING, CONFIRMED,
 * FAILED, REFUNDED, REVERSED), provider evidence hashing, and financial reconciliation.
 */
public class PosPaymentStatusReconciliationClient {

    public enum PaymentLifecycleStatus {
        PENDING,
        CONFIRMED,
        FAILED,
        REFUNDED,
        REVERSED
    }

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER",
            "STAFF_PHARMACIST", "CASHIER", "AUDITOR", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> MANAGE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER",
            "OWNER_ADMIN", "ADMIN"
    );

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final Map<String, PaymentStatusRecordDTO> recordStore;

    public PosPaymentStatusReconciliationClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "pos_cashier";
        this.recordStore = new HashMap<>();
    }

    public boolean canView() {
        return VIEW_ROLES.contains(this.userRole);
    }

    public boolean canManage() {
        return MANAGE_ROLES.contains(this.userRole);
    }

    private void assertViewPermission() {
        if (!canView()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " cannot view payment records.");
        }
    }

    private void assertManagePermission() {
        if (!canManage()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers and staff pharmacists cannot perform refunds or reversals.");
        }
    }

    public static String computeEvidenceHash(String recordId, String tenantId, String providerRef, BigDecimal amount, String evidenceJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = recordId + "|" + tenantId + "|" + providerRef + "|" + amount.toPlainString() + "|" + (evidenceJson != null ? evidenceJson : "{}");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate SHA-256 evidence hash", e);
        }
    }

    public PaymentStatusRecordDTO initiatePayment(
            String tenderType,
            BigDecimal amount,
            BigDecimal surcharge,
            String paymentMethodConfigId,
            String financialAccountId,
            String providerName,
            String idempotencyKey,
            String notes) {

        assertViewPermission();

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("ERR_INVALID_PAYMENT_AMOUNT: Amount must be greater than zero.");
        }

        BigDecimal amt = amount.setScale(4, RoundingMode.HALF_UP);
        BigDecimal sur = surcharge != null ? surcharge.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        BigDecimal total = amt.add(sur);

        // Check idempotency replay
        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            for (PaymentStatusRecordDTO existing : recordStore.values()) {
                if (idempotencyKey.equals(existing.idempotencyKey)) {
                    return existing;
                }
            }
        }

        String recordId = "PAY-" + System.currentTimeMillis() + "-" + (recordStore.size() + 1);
        PaymentStatusRecordDTO dto = new PaymentStatusRecordDTO(
                recordId,
                this.tenantId,
                this.branchId,
                paymentMethodConfigId,
                financialAccountId,
                tenderType != null ? tenderType.toUpperCase() : "CASH",
                amt,
                sur,
                total,
                PaymentLifecycleStatus.PENDING,
                providerName != null ? providerName : tenderType,
                "",
                "{}",
                "",
                null,
                null,
                "",
                "",
                BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                "",
                "",
                null,
                "",
                null,
                false,
                null,
                "",
                idempotencyKey != null ? idempotencyKey : "",
                notes != null ? notes : "",
                Instant.now(),
                Instant.now()
        );

        recordStore.put(recordId, dto);
        return dto;
    }

    public PaymentStatusRecordDTO confirmPayment(
            String recordId,
            String providerName,
            String providerReference,
            String evidenceJson) {

        assertViewPermission();

        PaymentStatusRecordDTO record = recordStore.get(recordId);
        if (record == null) {
            throw new NoSuchElementException("ERR_PAYMENT_RECORD_NOT_FOUND: Record " + recordId + " not found.");
        }

        // Idempotency check if already confirmed
        if (record.status == PaymentLifecycleStatus.CONFIRMED) {
            if (providerReference != null && providerReference.equals(record.providerReference)) {
                return record;
            }
        }

        if (record.status != PaymentLifecycleStatus.PENDING) {
            throw new IllegalStateException("ERR_INVALID_PAYMENT_STATE_TRANSITION: Cannot confirm payment from status " + record.status);
        }

        if ((providerReference == null || providerReference.trim().isEmpty()) && !"CASH".equalsIgnoreCase(record.tenderType)) {
            throw new IllegalArgumentException("ERR_MISSING_PROVIDER_REFERENCE: Provider reference required for electronic confirmation.");
        }

        String pRef = providerReference != null ? providerReference.trim() : "CASH-OFFLINE";
        String pName = providerName != null ? providerName.trim() : record.providerName;
        String evHash = computeEvidenceHash(record.recordId, record.tenantId, pRef, record.amountBdt, evidenceJson);

        PaymentStatusRecordDTO updated = new PaymentStatusRecordDTO(
                record.recordId,
                record.tenantId,
                record.branchId,
                record.paymentMethodConfigId,
                record.financialAccountId,
                record.tenderType,
                record.amountBdt,
                record.surchargeBdt,
                record.totalCollectedBdt,
                PaymentLifecycleStatus.CONFIRMED,
                pName,
                pRef,
                evidenceJson != null ? evidenceJson : "{}",
                evHash,
                Instant.now(),
                this.userId,
                "",
                "",
                record.refundAmountBdt,
                record.refundReference,
                record.refundReason,
                record.refundedAt,
                record.reversalReason,
                record.reversedAt,
                record.isReconciled,
                record.reconciledAt,
                record.reconciliationBatchId,
                record.idempotencyKey,
                record.notes,
                record.createdAt,
                Instant.now()
        );

        recordStore.put(recordId, updated);
        return updated;
    }

    public PaymentStatusRecordDTO failPayment(String recordId, String failureCode, String failureReason) {
        assertViewPermission();

        PaymentStatusRecordDTO record = recordStore.get(recordId);
        if (record == null) {
            throw new NoSuchElementException("ERR_PAYMENT_RECORD_NOT_FOUND: Record " + recordId + " not found.");
        }

        if (record.status != PaymentLifecycleStatus.PENDING) {
            throw new IllegalStateException("ERR_INVALID_PAYMENT_STATE_TRANSITION: Cannot fail payment in status " + record.status);
        }

        PaymentStatusRecordDTO updated = new PaymentStatusRecordDTO(
                record.recordId,
                record.tenantId,
                record.branchId,
                record.paymentMethodConfigId,
                record.financialAccountId,
                record.tenderType,
                record.amountBdt,
                record.surchargeBdt,
                record.totalCollectedBdt,
                PaymentLifecycleStatus.FAILED,
                record.providerName,
                record.providerReference,
                record.providerEvidenceJson,
                record.evidenceHash,
                record.verifiedAt,
                record.verifiedById,
                failureCode != null ? failureCode : "GATEWAY_REJECTED",
                failureReason != null ? failureReason : "Provider rejected tender",
                record.refundAmountBdt,
                record.refundReference,
                record.refundReason,
                record.refundedAt,
                record.reversalReason,
                record.reversedAt,
                record.isReconciled,
                record.reconciledAt,
                record.reconciliationBatchId,
                record.idempotencyKey,
                record.notes,
                record.createdAt,
                Instant.now()
        );

        recordStore.put(recordId, updated);
        return updated;
    }

    public PaymentStatusRecordDTO refundPayment(
            String recordId,
            BigDecimal refundAmount,
            String refundReference,
            String refundReason) {

        assertManagePermission();

        PaymentStatusRecordDTO record = recordStore.get(recordId);
        if (record == null) {
            throw new NoSuchElementException("ERR_PAYMENT_RECORD_NOT_FOUND: Record " + recordId + " not found.");
        }

        if (record.status != PaymentLifecycleStatus.CONFIRMED) {
            throw new IllegalStateException("ERR_INVALID_PAYMENT_STATE_TRANSITION: Cannot refund payment in status " + record.status);
        }

        BigDecimal refAmt = refundAmount != null ? refundAmount.setScale(4, RoundingMode.HALF_UP) : record.totalCollectedBdt;
        if (refAmt.compareTo(BigDecimal.ZERO) <= 0 || refAmt.compareTo(record.totalCollectedBdt) > 0) {
            throw new IllegalArgumentException("ERR_INVALID_REFUND_AMOUNT: Refund amount must be > 0 and <= " + record.totalCollectedBdt);
        }

        PaymentStatusRecordDTO updated = new PaymentStatusRecordDTO(
                record.recordId,
                record.tenantId,
                record.branchId,
                record.paymentMethodConfigId,
                record.financialAccountId,
                record.tenderType,
                record.amountBdt,
                record.surchargeBdt,
                record.totalCollectedBdt,
                PaymentLifecycleStatus.REFUNDED,
                record.providerName,
                record.providerReference,
                record.providerEvidenceJson,
                record.evidenceHash,
                record.verifiedAt,
                record.verifiedById,
                record.failureCode,
                record.failureReason,
                refAmt,
                refundReference != null ? refundReference : "",
                refundReason != null ? refundReason : "Customer return",
                Instant.now(),
                record.reversalReason,
                record.reversedAt,
                record.isReconciled,
                record.reconciledAt,
                record.reconciliationBatchId,
                record.idempotencyKey,
                record.notes,
                record.createdAt,
                Instant.now()
        );

        recordStore.put(recordId, updated);
        return updated;
    }

    public PaymentStatusRecordDTO reversePayment(String recordId, String reversalReason) {
        assertManagePermission();

        PaymentStatusRecordDTO record = recordStore.get(recordId);
        if (record == null) {
            throw new NoSuchElementException("ERR_PAYMENT_RECORD_NOT_FOUND: Record " + recordId + " not found.");
        }

        if (record.status != PaymentLifecycleStatus.CONFIRMED) {
            throw new IllegalStateException("ERR_INVALID_PAYMENT_STATE_TRANSITION: Cannot reverse payment in status " + record.status);
        }

        if (reversalReason == null || reversalReason.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_REVERSAL_REASON: Mandatory explanation reason is required for reversal.");
        }

        PaymentStatusRecordDTO updated = new PaymentStatusRecordDTO(
                record.recordId,
                record.tenantId,
                record.branchId,
                record.paymentMethodConfigId,
                record.financialAccountId,
                record.tenderType,
                record.amountBdt,
                record.surchargeBdt,
                record.totalCollectedBdt,
                PaymentLifecycleStatus.REVERSED,
                record.providerName,
                record.providerReference,
                record.providerEvidenceJson,
                record.evidenceHash,
                record.verifiedAt,
                record.verifiedById,
                record.failureCode,
                record.failureReason,
                record.refundAmountBdt,
                record.refundReference,
                record.refundReason,
                record.refundedAt,
                reversalReason.trim(),
                Instant.now(),
                record.isReconciled,
                record.reconciledAt,
                record.reconciliationBatchId,
                record.idempotencyKey,
                record.notes,
                record.createdAt,
                Instant.now()
        );

        recordStore.put(recordId, updated);
        return updated;
    }

    public PaymentStatusRecordDTO reconcilePayment(String recordId, String batchId) {
        assertManagePermission();

        PaymentStatusRecordDTO record = recordStore.get(recordId);
        if (record == null) {
            throw new NoSuchElementException("ERR_PAYMENT_RECORD_NOT_FOUND: Record " + recordId + " not found.");
        }

        if (record.status != PaymentLifecycleStatus.CONFIRMED
                && record.status != PaymentLifecycleStatus.REFUNDED
                && record.status != PaymentLifecycleStatus.REVERSED) {
            throw new IllegalStateException("ERR_UNSETTLED_PAYMENT_RECONCILIATION_BLOCKED: Cannot reconcile payment in status " + record.status);
        }

        String batch = (batchId != null && !batchId.trim().isEmpty()) ? batchId.trim() : "REC-" + System.currentTimeMillis();

        PaymentStatusRecordDTO updated = new PaymentStatusRecordDTO(
                record.recordId,
                record.tenantId,
                record.branchId,
                record.paymentMethodConfigId,
                record.financialAccountId,
                record.tenderType,
                record.amountBdt,
                record.surchargeBdt,
                record.totalCollectedBdt,
                record.status,
                record.providerName,
                record.providerReference,
                record.providerEvidenceJson,
                record.evidenceHash,
                record.verifiedAt,
                record.verifiedById,
                record.failureCode,
                record.failureReason,
                record.refundAmountBdt,
                record.refundReference,
                record.refundReason,
                record.refundedAt,
                record.reversalReason,
                record.reversedAt,
                true,
                Instant.now(),
                batch,
                record.idempotencyKey,
                record.notes,
                record.createdAt,
                Instant.now()
        );

        recordStore.put(recordId, updated);
        return updated;
    }

    public List<PaymentStatusRecordDTO> listPayments(PaymentLifecycleStatus statusFilter, Boolean reconciledFilter) {
        assertViewPermission();
        return recordStore.values().stream()
                .filter(r -> r.tenantId.equals(this.tenantId))
                .filter(r -> statusFilter == null || r.status == statusFilter)
                .filter(r -> reconciledFilter == null || r.isReconciled == reconciledFilter)
                .sorted(Comparator.comparing(PaymentStatusRecordDTO::createdAt).reversed())
                .collect(Collectors.toList());
    }

    public PaymentStatusRecordDTO getPayment(String recordId) {
        assertViewPermission();
        PaymentStatusRecordDTO record = recordStore.get(recordId);
        if (record == null || !record.tenantId.equals(this.tenantId)) {
            throw new NoSuchElementException("ERR_PAYMENT_RECORD_NOT_FOUND: Record " + recordId + " not found.");
        }
        return record;
    }

    public static class PaymentStatusRecordDTO {
        public final String recordId;
        public final String tenantId;
        public final String branchId;
        public final String paymentMethodConfigId;
        public final String financialAccountId;
        public final String tenderType;
        public final BigDecimal amountBdt;
        public final BigDecimal surchargeBdt;
        public final BigDecimal totalCollectedBdt;
        public final PaymentLifecycleStatus status;
        public final String providerName;
        public final String providerReference;
        public final String providerEvidenceJson;
        public final String evidenceHash;
        public final Instant verifiedAt;
        public final String verifiedById;
        public final String failureCode;
        public final String failureReason;
        public final BigDecimal refundAmountBdt;
        public final String refundReference;
        public final String refundReason;
        public final Instant refundedAt;
        public final String reversalReason;
        public final Instant reversedAt;
        public final boolean isReconciled;
        public final Instant reconciledAt;
        public final String reconciliationBatchId;
        public final String idempotencyKey;
        public final String notes;
        public final Instant createdAt;
        public final Instant updatedAt;

        public PaymentStatusRecordDTO(
                String recordId,
                String tenantId,
                String branchId,
                String paymentMethodConfigId,
                String financialAccountId,
                String tenderType,
                BigDecimal amountBdt,
                BigDecimal surchargeBdt,
                BigDecimal totalCollectedBdt,
                PaymentLifecycleStatus status,
                String providerName,
                String providerReference,
                String providerEvidenceJson,
                String evidenceHash,
                Instant verifiedAt,
                String verifiedById,
                String failureCode,
                String failureReason,
                BigDecimal refundAmountBdt,
                String refundReference,
                String refundReason,
                Instant refundedAt,
                String reversalReason,
                Instant reversedAt,
                boolean isReconciled,
                Instant reconciledAt,
                String reconciliationBatchId,
                String idempotencyKey,
                String notes,
                Instant createdAt,
                Instant updatedAt) {
            this.recordId = recordId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.paymentMethodConfigId = paymentMethodConfigId;
            this.financialAccountId = financialAccountId;
            this.tenderType = tenderType;
            this.amountBdt = amountBdt;
            this.surchargeBdt = surchargeBdt;
            this.totalCollectedBdt = totalCollectedBdt;
            this.status = status;
            this.providerName = providerName;
            this.providerReference = providerReference;
            this.providerEvidenceJson = providerEvidenceJson;
            this.evidenceHash = evidenceHash;
            this.verifiedAt = verifiedAt;
            this.verifiedById = verifiedById;
            this.failureCode = failureCode;
            this.failureReason = failureReason;
            this.refundAmountBdt = refundAmountBdt;
            this.refundReference = refundReference;
            this.refundReason = refundReason;
            this.refundedAt = refundedAt;
            this.reversalReason = reversalReason;
            this.reversedAt = reversedAt;
            this.isReconciled = isReconciled;
            this.reconciledAt = reconciledAt;
            this.reconciliationBatchId = reconciliationBatchId;
            this.idempotencyKey = idempotencyKey;
            this.notes = notes;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public Instant createdAt() {
            return this.createdAt;
        }
    }
}
