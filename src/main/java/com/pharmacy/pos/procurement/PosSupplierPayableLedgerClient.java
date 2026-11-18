package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * PosSupplierPayableLedgerClient
 * Architecture Decision: ADR-0272 (P272).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * Desktop POS client governing accounts payable (AP) ledger postings,
 * running balance tracking, compensating reversals, and reconciliations.
 */
public class PosSupplierPayableLedgerClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final Map<String, SupplierRecord> supplierStore;
    private final List<LedgerPostingDto> ledgerStore;

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "STAFF_PHARMACIST",
            "INVENTORY_CLERK", "FINANCE_OFFICER", "AUDITOR", "OWNER_ADMIN", "ADMIN", "PROCUREMENT_OFFICER"
    );

    public static final Set<String> POST_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER",
            "INVENTORY_CLERK", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN", "PROCUREMENT_OFFICER"
    );

    public static final Set<String> ADMIN_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "OWNER_ADMIN", "ADMIN"
    );

    public PosSupplierPayableLedgerClient(String tenantId, String branchId, String userRole, String userId) {
        this(tenantId, branchId, userRole, userId, new HashMap<>(), new ArrayList<>());
    }

    public PosSupplierPayableLedgerClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            Map<String, SupplierRecord> supplierStore,
            List<LedgerPostingDto> ledgerStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.supplierStore = supplierStore != null ? supplierStore : new HashMap<>();
        this.ledgerStore = ledgerStore != null ? ledgerStore : new ArrayList<>();
    }

    public boolean canView() {
        return VIEW_ROLES.contains(this.userRole);
    }

    public boolean canPost() {
        return POST_ROLES.contains(this.userRole);
    }

    public boolean canAdmin() {
        return ADMIN_ROLES.contains(this.userRole);
    }

    public LedgerPostingDto postEntry(
            String supplierId,
            String postingType,
            String referenceNumber,
            BigDecimal debitAmount,
            BigDecimal creditAmount,
            String notes,
            String idempotencyKey) {
        if (!canPost()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " is not permitted to post AP ledger entries.");
        }
        if (postingType == null || postingType.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_INVALID_POSTING_TYPE: posting_type is required.");
        }
        if ("ADJUSTMENT".equalsIgnoreCase(postingType) && !canAdmin()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Only owners/managers can post manual audit adjustments.");
        }
        if (referenceNumber == null || referenceNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_REFERENCE_NUMBER: reference_number is required.");
        }

        BigDecimal debit = debitAmount != null ? debitAmount : BigDecimal.ZERO;
        BigDecimal credit = creditAmount != null ? creditAmount : BigDecimal.ZERO;

        if (debit.compareTo(BigDecimal.ZERO) < 0 || credit.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("ERR_INVALID_AMOUNT: Debit and credit amounts cannot be negative.");
        }
        if (debit.compareTo(BigDecimal.ZERO) == 0 && credit.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("ERR_ZERO_AMOUNT: Either debit or credit must be positive.");
        }

        SupplierRecord supplier = supplierStore.get(supplierId);
        if (supplier == null) {
            throw new NoSuchElementException("ERR_SUPPLIER_NOT_FOUND: Supplier " + supplierId + " not found.");
        }
        if (!this.tenantId.equals(supplier.tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cannot post to supplier of another tenant.");
        }

        // Idempotency check
        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            for (LedgerPostingDto existing : ledgerStore) {
                if (existing.notes != null && existing.notes.contains("[IDEMPOTENCY:" + idempotencyKey + "]")) {
                    return existing;
                }
            }
        }

        // Credit increases liability; Debit decreases liability
        BigDecimal newBalance = supplier.currentBalance.add(credit).subtract(debit);
        supplier.currentBalance = newBalance;

        String postingId = "sup-post-" + UUID.randomUUID().toString().substring(0, 12);
        String finalNotes = notes != null ? notes.trim() : "";
        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            finalNotes = (finalNotes + " [IDEMPOTENCY:" + idempotencyKey + "]").trim();
        }

        LedgerPostingDto entry = new LedgerPostingDto(
                postingId,
                this.tenantId,
                supplierId,
                supplier.name,
                this.branchId,
                postingType.toUpperCase(),
                referenceNumber.trim(),
                debit,
                credit,
                newBalance,
                "BDT",
                finalNotes,
                this.userId,
                Instant.now().toString()
        );
        ledgerStore.add(entry);
        return entry;
    }

    public LedgerPostingDto reverseEntry(String supplierId, String postingId, String reason) {
        if (!canAdmin()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Only owners/managers can reverse ledger entries.");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_REVERSAL_REASON: Reason is mandatory for reversal.");
        }

        LedgerPostingDto original = null;
        for (LedgerPostingDto e : ledgerStore) {
            if (e.postingId.equals(postingId)) {
                original = e;
                break;
            }
        }
        if (original == null) {
            throw new NoSuchElementException("ERR_LEDGER_ENTRY_NOT_FOUND: Posting " + postingId + " not found.");
        }

        // Check if already reversed
        String reversalMarker = "[REVERSAL_OF:" + postingId + "]";
        for (LedgerPostingDto e : ledgerStore) {
            if (e.notes != null && e.notes.contains(reversalMarker)) {
                throw new IllegalStateException("ERR_ALREADY_REVERSED: Posting already reversed.");
            }
        }

        SupplierRecord supplier = supplierStore.get(supplierId);
        if (supplier == null) {
            throw new NoSuchElementException("ERR_SUPPLIER_NOT_FOUND: Supplier " + supplierId + " not found.");
        }

        BigDecimal revDebit = original.creditAmount;
        BigDecimal revCredit = original.debitAmount;
        BigDecimal newBalance = supplier.currentBalance.add(revCredit).subtract(revDebit);
        supplier.currentBalance = newBalance;

        String revPostingId = "sup-rev-" + UUID.randomUUID().toString().substring(0, 12);
        String revType = "ADJUSTMENT";
        if ("PAYMENT".equalsIgnoreCase(original.postingType)) {
            revType = "PAYMENT_REVERSAL";
        } else if ("SUPPLIER_CREDIT_NOTE".equalsIgnoreCase(original.postingType)) {
            revType = "CREDIT_NOTE_REVERSAL";
        }

        LedgerPostingDto reversal = new LedgerPostingDto(
                revPostingId,
                this.tenantId,
                supplierId,
                supplier.name,
                this.branchId,
                revType,
                "REV-" + original.referenceNumber,
                revDebit,
                revCredit,
                newBalance,
                "BDT",
                "Reversal of " + postingId + ": " + reason.trim() + " " + reversalMarker,
                this.userId,
                Instant.now().toString()
        );
        ledgerStore.add(reversal);
        return reversal;
    }

    public List<LedgerPostingDto> listEntries(String supplierId) {
        if (!canView()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " cannot view ledger.");
        }
        List<LedgerPostingDto> results = new ArrayList<>();
        for (LedgerPostingDto e : ledgerStore) {
            if (e.tenantId.equals(this.tenantId) && e.supplierId.equals(supplierId)) {
                results.add(e);
            }
        }
        return results;
    }

    public ReconciliationDto reconcile(String supplierId) {
        if (!canView()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " cannot reconcile ledger.");
        }
        SupplierRecord supplier = supplierStore.get(supplierId);
        if (supplier == null) {
            throw new NoSuchElementException("ERR_SUPPLIER_NOT_FOUND: Supplier not found.");
        }

        List<LedgerPostingDto> entries = listEntries(supplierId);
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        for (LedgerPostingDto e : entries) {
            totalDebits = totalDebits.add(e.debitAmount);
            totalCredits = totalCredits.add(e.creditAmount);
        }

        BigDecimal opening = entries.isEmpty() ? supplier.currentBalance : entries.get(0).balanceAfter.subtract(entries.get(0).creditAmount).add(entries.get(0).debitAmount);
        BigDecimal closing = entries.isEmpty() ? supplier.currentBalance : entries.get(entries.size() - 1).balanceAfter;
        BigDecimal calculated = opening.add(totalCredits).subtract(totalDebits);
        boolean isReconciled = calculated.compareTo(closing) == 0 && closing.compareTo(supplier.currentBalance) == 0;

        return new ReconciliationDto(
                supplierId,
                supplier.name,
                this.tenantId,
                opening,
                totalCredits,
                totalDebits,
                calculated,
                supplier.currentBalance,
                isReconciled,
                entries.size()
        );
    }

    public static class SupplierRecord {
        public final String supplierId;
        public final String tenantId;
        public final String name;
        public BigDecimal currentBalance;

        public SupplierRecord(String supplierId, String tenantId, String name, BigDecimal currentBalance) {
            this.supplierId = supplierId;
            this.tenantId = tenantId;
            this.name = name;
            this.currentBalance = currentBalance != null ? currentBalance : BigDecimal.ZERO;
        }
    }

    public static class LedgerPostingDto {
        public final String postingId;
        public final String tenantId;
        public final String supplierId;
        public final String supplierName;
        public final String branchId;
        public final String postingType;
        public final String referenceNumber;
        public final BigDecimal debitAmount;
        public final BigDecimal creditAmount;
        public final BigDecimal balanceAfter;
        public final String currency;
        public final String notes;
        public final String postedById;
        public final String postedAt;

        public LedgerPostingDto(
                String postingId,
                String tenantId,
                String supplierId,
                String supplierName,
                String branchId,
                String postingType,
                String referenceNumber,
                BigDecimal debitAmount,
                BigDecimal creditAmount,
                BigDecimal balanceAfter,
                String currency,
                String notes,
                String postedById,
                String postedAt) {
            this.postingId = postingId;
            this.tenantId = tenantId;
            this.supplierId = supplierId;
            this.supplierName = supplierName;
            this.branchId = branchId;
            this.postingType = postingType;
            this.referenceNumber = referenceNumber;
            this.debitAmount = debitAmount;
            this.creditAmount = creditAmount;
            this.balanceAfter = balanceAfter;
            this.currency = currency;
            this.notes = notes;
            this.postedById = postedById;
            this.postedAt = postedAt;
        }
    }

    public static class ReconciliationDto {
        public final String supplierId;
        public final String supplierName;
        public final String tenantId;
        public final BigDecimal openingBalance;
        public final BigDecimal totalCredits;
        public final BigDecimal totalDebits;
        public final BigDecimal calculatedClosing;
        public final BigDecimal currentBalance;
        public final boolean isReconciled;
        public final int totalPostings;

        public ReconciliationDto(
                String supplierId,
                String supplierName,
                String tenantId,
                BigDecimal openingBalance,
                BigDecimal totalCredits,
                BigDecimal totalDebits,
                BigDecimal calculatedClosing,
                BigDecimal currentBalance,
                boolean isReconciled,
                int totalPostings) {
            this.supplierId = supplierId;
            this.supplierName = supplierName;
            this.tenantId = tenantId;
            this.openingBalance = openingBalance;
            this.totalCredits = totalCredits;
            this.totalDebits = totalDebits;
            this.calculatedClosing = calculatedClosing;
            this.currentBalance = currentBalance;
            this.isReconciled = isReconciled;
            this.totalPostings = totalPostings;
        }
    }
}
