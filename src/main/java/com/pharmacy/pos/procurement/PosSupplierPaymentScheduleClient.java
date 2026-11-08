package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * PosSupplierPaymentScheduleClient
 * Architecture Decision: ADR-0193 (P193).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 *
 * Desktop JavaFX client governing Supplier Payment Schedules & AP Ledger Consistency:
 * - Schedules vendor dues, priority queues, and partial installment disbursements.
 * - Posts immutable Accounts Payable debits reducing supplier ledger liability.
 * - Enforces anti-overpayment guards and audited due date rescheduling.
 */
public class PosSupplierPaymentScheduleClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, PaymentScheduleDto> scheduleStore;
    private final Map<String, SupplierLedgerEntryDto> ledgerStore;

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "PROCUREMENT_OFFICER", "INVENTORY_CLERK", "ACCOUNTANT", "AUDITOR"
    );

    public PosSupplierPaymentScheduleClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, PaymentScheduleDto> scheduleStore,
            Map<String, SupplierLedgerEntryDto> ledgerStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.scheduleStore = scheduleStore != null ? scheduleStore : new HashMap<>();
        this.ledgerStore = ledgerStore != null ? ledgerStore : new HashMap<>();
    }

    public static final class SupplierLedgerEntryDto {
        public final String postingId;
        public final String supplierId;
        public final String postingType;
        public final String referenceNumber;
        public final BigDecimal debitAmount;
        public final BigDecimal creditAmount;
        public final BigDecimal balanceAfter;
        public final Instant postedAt;

        public SupplierLedgerEntryDto(String postingId, String supplierId, String postingType, String referenceNumber,
                                      BigDecimal debitAmount, BigDecimal creditAmount, BigDecimal balanceAfter, Instant postedAt) {
            this.postingId = postingId;
            this.supplierId = supplierId;
            this.postingType = postingType;
            this.referenceNumber = referenceNumber;
            this.debitAmount = debitAmount;
            this.creditAmount = creditAmount;
            this.balanceAfter = balanceAfter;
            this.postedAt = postedAt;
        }
    }

    public static final class PaymentInstallmentDto {
        public final String installmentId;
        public final BigDecimal amountPaid;
        public final String paymentMethod;
        public final String paymentReference;
        public final LocalDate transactionDate;
        public final String ledgerEntryId;
        public final String notes;
        public final String disbursedBy;
        public final Instant createdAt;

        public PaymentInstallmentDto(String installmentId, BigDecimal amountPaid, String paymentMethod,
                                     String paymentReference, LocalDate transactionDate, String ledgerEntryId,
                                     String notes, String disbursedBy, Instant createdAt) {
            this.installmentId = installmentId != null ? installmentId : "spi-" + UUID.randomUUID().toString().substring(0, 8);
            this.amountPaid = amountPaid != null ? amountPaid : BigDecimal.ZERO;
            this.paymentMethod = paymentMethod != null ? paymentMethod : "BANK_TRANSFER";
            this.paymentReference = paymentReference != null ? paymentReference : "";
            this.transactionDate = transactionDate != null ? transactionDate : LocalDate.now();
            this.ledgerEntryId = ledgerEntryId;
            this.notes = notes != null ? notes : "";
            this.disbursedBy = disbursedBy;
            this.createdAt = createdAt != null ? createdAt : Instant.now();
        }
    }

    public static final class PaymentScheduleDto {
        public final String scheduleId;
        public final String scheduleNumber;
        public final String tenantId;
        public final String branchId;
        public final String supplierId;
        public final String supplierName;
        public final String supplierInvoiceId;
        public LocalDate dueDate;
        public final BigDecimal plannedAmount;
        public BigDecimal settledAmount;
        public BigDecimal remainingAmount;
        public String status; // SCHEDULED, PARTIALLY_SETTLED, SETTLED, OVERDUE, CANCELLED
        public String priority; // URGENT, HIGH, MEDIUM, LOW
        public final String paymentMethodPreference;
        public final String chequeReference;
        public int rescheduleCount;
        public String lastRescheduleReason;
        public Instant lastRescheduledAt;
        public String notes;
        public final String createdBy;
        public final List<PaymentInstallmentDto> installments;

        public PaymentScheduleDto(String scheduleId, String scheduleNumber, String tenantId, String branchId,
                                  String supplierId, String supplierName, String supplierInvoiceId,
                                  LocalDate dueDate, BigDecimal plannedAmount, BigDecimal settledAmount,
                                  BigDecimal remainingAmount, String status, String priority,
                                  String paymentMethodPreference, String chequeReference,
                                  int rescheduleCount, String lastRescheduleReason, Instant lastRescheduledAt,
                                  String notes, String createdBy, List<PaymentInstallmentDto> installments) {
            this.scheduleId = scheduleId;
            this.scheduleNumber = scheduleNumber;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.supplierId = supplierId;
            this.supplierName = supplierName;
            this.supplierInvoiceId = supplierInvoiceId;
            this.dueDate = dueDate;
            this.plannedAmount = plannedAmount;
            this.settledAmount = settledAmount;
            this.remainingAmount = remainingAmount;
            this.status = status;
            this.priority = priority;
            this.paymentMethodPreference = paymentMethodPreference;
            this.chequeReference = chequeReference;
            this.rescheduleCount = rescheduleCount;
            this.lastRescheduleReason = lastRescheduleReason;
            this.lastRescheduledAt = lastRescheduledAt;
            this.notes = notes;
            this.createdBy = createdBy;
            this.installments = installments != null ? installments : new ArrayList<>();
        }
    }

    private void assertProcurementRole() {
        if ("CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Staff role " + userRole + " cannot manage payment schedules.");
        }
    }

    public PaymentScheduleDto createSchedule(
            String supplierId,
            String supplierName,
            String supplierInvoiceId,
            LocalDate dueDate,
            BigDecimal plannedAmount,
            String priority,
            String methodPref,
            String chequeRef,
            String notes) {
        assertProcurementRole();

        if (supplierId == null || supplierId.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_SUPPLIER: Supplier ID is required.");
        }

        if (dueDate == null) {
            throw new IllegalArgumentException("ERR_MISSING_DUE_DATE: Settlement due date is required.");
        }

        if (plannedAmount == null || plannedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("ERR_INVALID_PLANNED_AMOUNT: Planned amount must be positive.");
        }

        String schedId = "sps-" + UUID.randomUUID().toString().substring(0, 8);
        String schedNum = "SPS-" + System.currentTimeMillis();

        PaymentScheduleDto sps = new PaymentScheduleDto(
                schedId,
                schedNum,
                tenantId,
                branchId,
                supplierId,
                supplierName != null ? supplierName : "",
                supplierInvoiceId,
                dueDate,
                plannedAmount,
                BigDecimal.ZERO,
                plannedAmount,
                "SCHEDULED",
                priority != null ? priority : "MEDIUM",
                methodPref != null ? methodPref : "BANK_TRANSFER",
                chequeRef != null ? chequeRef : "",
                0,
                "",
                null,
                notes != null ? notes : "",
                userId,
                new ArrayList<>()
        );

        scheduleStore.put(schedId, sps);
        return sps;
    }

    public PaymentScheduleDto recordInstallmentPayment(
            String scheduleId,
            BigDecimal amountPaid,
            String paymentMethod,
            String paymentReference,
            String notes) {
        assertProcurementRole();

        PaymentScheduleDto sps = scheduleStore.get(scheduleId);
        if (sps == null) {
            throw new NoSuchElementException("ERR_SCHEDULE_NOT_FOUND: Schedule " + scheduleId + " not found.");
        }

        if ("SETTLED".equals(sps.status) || "CANCELLED".equals(sps.status)) {
            throw new IllegalStateException("ERR_SCHEDULE_NOT_PAYABLE: Cannot disburse payment against schedule in status " + sps.status);
        }

        if (amountPaid == null || amountPaid.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("ERR_INVALID_PAYMENT_AMOUNT: Payment amount must be positive.");
        }

        if (amountPaid.compareTo(sps.remainingAmount) > 0) {
            throw new IllegalArgumentException("ERR_EXCEEDS_SCHEDULED_DUE: Payment amount " + amountPaid + " exceeds remaining due " + sps.remainingAmount);
        }

        if (paymentReference == null || paymentReference.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_PAYMENT_REF: Payment reference voucher is required.");
        }

        // Post AP Disbursement (reduces AP liability)
        SupplierLedgerEntryDto latestEntry = ledgerStore.values().stream()
                .filter(e -> sps.supplierId.equals(e.supplierId))
                .max(Comparator.comparing(e -> e.postedAt))
                .orElse(null);
        BigDecimal prevBalance = latestEntry != null ? latestEntry.balanceAfter : BigDecimal.ZERO;

        BigDecimal newBalance = prevBalance.subtract(amountPaid);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            newBalance = BigDecimal.ZERO;
        }

        String postingId = "sup-pay-" + UUID.randomUUID().toString().substring(0, 8);
        SupplierLedgerEntryDto entry = new SupplierLedgerEntryDto(
                postingId,
                sps.supplierId,
                "PAYMENT",
                paymentReference,
                amountPaid,
                BigDecimal.ZERO,
                newBalance,
                Instant.now()
        );
        ledgerStore.put(postingId, entry);

        PaymentInstallmentDto inst = new PaymentInstallmentDto(
                null, amountPaid, paymentMethod, paymentReference, LocalDate.now(), postingId, notes, userId, Instant.now()
        );
        sps.installments.add(inst);

        sps.settledAmount = sps.settledAmount.add(amountPaid);
        sps.remainingAmount = sps.remainingAmount.subtract(amountPaid);
        if (sps.remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
            sps.remainingAmount = BigDecimal.ZERO;
            sps.status = "SETTLED";
        } else {
            sps.status = "PARTIALLY_SETTLED";
        }

        return sps;
    }

    public PaymentScheduleDto reschedule(String scheduleId, LocalDate newDueDate, String reason) {
        assertProcurementRole();

        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_RESCHEDULE_REASON_REQUIRED: Reschedule justification reason is mandatory.");
        }

        if (newDueDate == null) {
            throw new IllegalArgumentException("ERR_MISSING_DUE_DATE: New due date is required.");
        }

        PaymentScheduleDto sps = scheduleStore.get(scheduleId);
        if (sps == null) {
            throw new NoSuchElementException("ERR_SCHEDULE_NOT_FOUND: Schedule " + scheduleId + " not found.");
        }

        if ("SETTLED".equals(sps.status) || "CANCELLED".equals(sps.status)) {
            throw new IllegalStateException("ERR_CANNOT_RESCHEDULE_CLOSED: Cannot reschedule in status " + sps.status);
        }

        sps.dueDate = newDueDate;
        sps.rescheduleCount++;
        sps.lastRescheduleReason = reason;
        sps.lastRescheduledAt = Instant.now();

        return sps;
    }

    public PaymentScheduleDto cancelSchedule(String scheduleId, String reason) {
        assertProcurementRole();

        PaymentScheduleDto sps = scheduleStore.get(scheduleId);
        if (sps == null) {
            throw new NoSuchElementException("ERR_SCHEDULE_NOT_FOUND: Schedule " + scheduleId + " not found.");
        }

        if (sps.settledAmount.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("ERR_CANNOT_CANCEL_DISBURSED_SCHEDULE: Cannot cancel schedule with settled funds.");
        }

        sps.status = "CANCELLED";
        sps.notes = (sps.notes != null ? sps.notes : "") + " | Cancelled: " + reason;
        return sps;
    }

    public PaymentScheduleDto getSchedule(String scheduleId) {
        PaymentScheduleDto sps = scheduleStore.get(scheduleId);
        if (sps == null) {
            throw new NoSuchElementException("ERR_SCHEDULE_NOT_FOUND: Schedule " + scheduleId + " not found.");
        }
        return sps;
    }

    public List<PaymentScheduleDto> listSchedules(String supplierId, String statusFilter, String priority) {
        List<PaymentScheduleDto> results = new ArrayList<>();
        for (PaymentScheduleDto s : scheduleStore.values()) {
            if (supplierId != null && !supplierId.isEmpty() && !supplierId.equals(s.supplierId)) {
                continue;
            }
            if (statusFilter != null && !statusFilter.isEmpty() && !statusFilter.equalsIgnoreCase(s.status)) {
                continue;
            }
            if (priority != null && !priority.isEmpty() && !priority.equalsIgnoreCase(s.priority)) {
                continue;
            }
            results.add(s);
        }
        return results;
    }
}
