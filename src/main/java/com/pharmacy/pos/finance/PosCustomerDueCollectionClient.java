package com.pharmacy.pos.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PosCustomerDueCollectionClient
 * Architecture Decision: ADR-0277 (P277).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * JavaFX Desktop POS client integrating mobile customer due review,
 * collection recording, digital receipts, and ledger audit verification.
 */
public class PosCustomerDueCollectionClient {

    public static final Set<String> AUTHORIZED_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> DENIED_STAFF_ROLES = Set.of(
            "CASHIER", "STAFF_PHARMACIST", "DISPENSER", "INVENTORY_CLERK"
    );

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final Map<String, OutstandingDueDTO> dueStore;
    private final Map<String, DigitalReceiptDTO> collectionStore;

    public PosCustomerDueCollectionClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.dueStore = new HashMap<>();
        this.collectionStore = new HashMap<>();
    }

    public boolean isAuthorized() {
        return AUTHORIZED_ROLES.contains(this.userRole);
    }

    private void assertAuthorization() {
        if (!isAuthorized()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers and staff pharmacists cannot perform mobile owner due collections.");
        }
    }

    public void addDueRecord(OutstandingDueDTO due) {
        if (due != null) {
            dueStore.put(due.dueId, due);
        }
    }

    public List<OutstandingDueDTO> listOutstandingDues(String search, Boolean isOverdue) {
        assertAuthorization();

        LocalDate today = LocalDate.now();
        return dueStore.values().stream()
                .filter(d -> d.tenantId.equals(this.tenantId))
                .filter(d -> "OUTSTANDING".equals(d.status) || "PARTIAL".equals(d.status))
                .filter(d -> d.remainingDueBdt.compareTo(BigDecimal.ZERO) > 0)
                .filter(d -> {
                    if (search == null || search.trim().isEmpty()) return true;
                    String s = search.toLowerCase().trim();
                    return d.customerName.toLowerCase().contains(s)
                            || d.customerPhone.toLowerCase().contains(s)
                            || d.dueId.toLowerCase().contains(s)
                            || d.invoiceNumber.toLowerCase().contains(s);
                })
                .filter(d -> {
                    if (isOverdue == null) return true;
                    boolean od = d.dueDate != null && d.dueDate.isBefore(today);
                    return od == isOverdue;
                })
                .sorted(Comparator.comparing((OutstandingDueDTO d) -> d.dueDate != null ? d.dueDate : LocalDate.MAX))
                .collect(Collectors.toList());
    }

    public DigitalReceiptDTO recordDueCollection(
            String dueId,
            BigDecimal amount,
            String tenderType,
            String gatewayReference,
            String financialAccountId,
            boolean isOffline,
            String idempotencyKey,
            String notes) {

        assertAuthorization();

        if (isOffline) {
            throw new IllegalStateException("ERR_OFFLINE_UNAVAILABLE: Due collection requires online connectivity to verify live AR ledger state.");
        }

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("ERR_INVALID_PAYMENT_AMOUNT: Collection amount must be strictly greater than 0.00 BDT.");
        }

        OutstandingDueDTO due = dueStore.get(dueId);
        if (due == null || !due.tenantId.equals(this.tenantId)) {
            throw new NoSuchElementException("ERR_DUE_RECORD_NOT_FOUND: Due record " + dueId + " not found.");
        }

        if ("SETTLED".equals(due.status) || due.remainingDueBdt.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("ERR_DUE_ALREADY_SETTLED: Due record " + dueId + " is already settled.");
        }

        BigDecimal amt = amount.setScale(2, RoundingMode.HALF_UP);
        if (amt.compareTo(due.remainingDueBdt) > 0) {
            throw new IllegalArgumentException("ERR_DUE_OVERPAYMENT: Collection amount (" + amt + " BDT) exceeds remaining due (" + due.remainingDueBdt + " BDT).");
        }

        String tType = tenderType != null ? tenderType.trim().toUpperCase() : "CASH";
        if ("DUE".equals(tType)) {
            throw new IllegalArgumentException("ERR_INVALID_TENDER_TYPE: Cannot collect customer due balance using DUE tender.");
        }

        if (Set.of("BKASH", "NAGAD", "ROCKET", "UPAY", "CARD", "BANK_TRANSFER").contains(tType)) {
            if (gatewayReference == null || gatewayReference.trim().length() < 4) {
                throw new IllegalArgumentException("ERR_MISSING_GATEWAY_REFERENCE: Digital payment requires a valid gateway transaction reference (min 4 chars).");
            }
        }

        // Apply reduction
        BigDecimal newRemaining = due.remainingDueBdt.subtract(amt).setScale(2, RoundingMode.HALF_UP);
        BigDecimal newPaid = due.paidAmountBdt.add(amt).setScale(2, RoundingMode.HALF_UP);
        String newStatus = newRemaining.compareTo(BigDecimal.ZERO) <= 0 ? "SETTLED" : "PARTIAL";

        OutstandingDueDTO updatedDue = new OutstandingDueDTO(
                due.dueId,
                due.tenantId,
                due.branchId,
                due.branchName,
                due.customerId,
                due.customerName,
                due.customerPhone,
                due.invoiceId,
                due.invoiceNumber,
                due.totalDueBdt,
                newPaid,
                newRemaining,
                newStatus,
                due.dueDate,
                due.notes,
                due.createdAt
        );
        dueStore.put(dueId, updatedDue);

        String collectionId = "MCOL-" + System.currentTimeMillis();
        String receiptNumber = "REC-DUE-" + collectionId.substring(collectionId.length() - 6);

        DigitalReceiptDTO receipt = new DigitalReceiptDTO(
                collectionId,
                receiptNumber,
                this.tenantId,
                due.branchId,
                due.branchName,
                due.dueId,
                due.customerId,
                due.customerName,
                due.customerPhone,
                due.invoiceNumber,
                tType,
                amt,
                newRemaining,
                newStatus,
                gatewayReference != null ? gatewayReference : "",
                this.userId,
                Instant.now(),
                notes != null ? notes : ""
        );

        collectionStore.put(collectionId, receipt);
        return receipt;
    }

    public DigitalReceiptDTO getReceipt(String collectionId) {
        assertAuthorization();
        DigitalReceiptDTO receipt = collectionStore.get(collectionId);
        if (receipt == null || !receipt.tenantId.equals(this.tenantId)) {
            throw new NoSuchElementException("ERR_COLLECTION_NOT_FOUND: Collection " + collectionId + " not found.");
        }
        return receipt;
    }

    public static class OutstandingDueDTO {
        public final String dueId;
        public final String tenantId;
        public final String branchId;
        public final String branchName;
        public final String customerId;
        public final String customerName;
        public final String customerPhone;
        public final String invoiceId;
        public final String invoiceNumber;
        public final BigDecimal totalDueBdt;
        public final BigDecimal paidAmountBdt;
        public final BigDecimal remainingDueBdt;
        public final String status;
        public final LocalDate dueDate;
        public final String notes;
        public final Instant createdAt;

        public OutstandingDueDTO(
                String dueId,
                String tenantId,
                String branchId,
                String branchName,
                String customerId,
                String customerName,
                String customerPhone,
                String invoiceId,
                String invoiceNumber,
                BigDecimal totalDueBdt,
                BigDecimal paidAmountBdt,
                BigDecimal remainingDueBdt,
                String status,
                LocalDate dueDate,
                String notes,
                Instant createdAt) {
            this.dueId = dueId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.branchName = branchName;
            this.customerId = customerId;
            this.customerName = customerName;
            this.customerPhone = customerPhone;
            this.invoiceId = invoiceId;
            this.invoiceNumber = invoiceNumber;
            this.totalDueBdt = totalDueBdt;
            this.paidAmountBdt = paidAmountBdt;
            this.remainingDueBdt = remainingDueBdt;
            this.status = status;
            this.dueDate = dueDate;
            this.notes = notes;
            this.createdAt = createdAt;
        }
    }

    public static class DigitalReceiptDTO {
        public final String collectionId;
        public final String receiptNumber;
        public final String tenantId;
        public final String branchId;
        public final String branchName;
        public final String dueId;
        public final String customerId;
        public final String customerName;
        public final String customerPhone;
        public final String invoiceNumber;
        public final String tenderType;
        public final BigDecimal amountCollectedBdt;
        public final BigDecimal remainingDueBdt;
        public final String dueStatus;
        public final String gatewayReference;
        public final String collectedByName;
        public final Instant collectedAt;
        public final String notes;

        public DigitalReceiptDTO(
                String collectionId,
                String receiptNumber,
                String tenantId,
                String branchId,
                String branchName,
                String dueId,
                String customerId,
                String customerName,
                String customerPhone,
                String invoiceNumber,
                String tenderType,
                BigDecimal amountCollectedBdt,
                BigDecimal remainingDueBdt,
                String dueStatus,
                String gatewayReference,
                String collectedByName,
                Instant collectedAt,
                String notes) {
            this.collectionId = collectionId;
            this.receiptNumber = receiptNumber;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.branchName = branchName;
            this.dueId = dueId;
            this.customerId = customerId;
            this.customerName = customerName;
            this.customerPhone = customerPhone;
            this.invoiceNumber = invoiceNumber;
            this.tenderType = tenderType;
            this.amountCollectedBdt = amountCollectedBdt;
            this.remainingDueBdt = remainingDueBdt;
            this.dueStatus = dueStatus;
            this.gatewayReference = gatewayReference;
            this.collectedByName = collectedByName;
            this.collectedAt = collectedAt;
            this.notes = notes;
        }
    }
}
