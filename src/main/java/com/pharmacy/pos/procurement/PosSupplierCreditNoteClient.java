package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * PosSupplierCreditNoteClient
 * Architecture Decision: ADR-0192 (P192).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 *
 * Desktop JavaFX client governing Supplier Credit Notes & Audited Reversals:
 * - Records supplier credit notes as immutable finance events with traceable origin (Invoice, Return, GRN, PO).
 * - Offsets and decrements Accounts Payable balance upon posting.
 * - Non-destructive corrections: restores Accounts Payable balance upon audited reversal.
 */
public class PosSupplierCreditNoteClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, SupplierCreditNoteDto> noteStore;
    private final Map<String, SupplierLedgerEntryDto> ledgerStore;

    private static final Set<String> ALLOWED_PROCUREMENT_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "PROCUREMENT_OFFICER", "INVENTORY_CLERK", "AUDITOR"
    );

    private static final Set<String> ALLOWED_REVERSAL_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "AUDITOR"
    );

    public PosSupplierCreditNoteClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, SupplierCreditNoteDto> noteStore,
            Map<String, SupplierLedgerEntryDto> ledgerStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.noteStore = noteStore != null ? noteStore : new HashMap<>();
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

    public static final class SupplierCreditNoteItemDto {
        public final String itemId;
        public final String productId;
        public final String productName;
        public final int quantity;
        public final BigDecimal unitCreditRate;
        public final BigDecimal taxPercentage;
        public final BigDecimal lineTotal;
        public final String description;

        public SupplierCreditNoteItemDto(String itemId, String productId, String productName, int quantity,
                                         BigDecimal unitCreditRate, BigDecimal taxPercentage, BigDecimal lineTotal,
                                         String description) {
            this.itemId = itemId != null ? itemId : "scni-" + UUID.randomUUID().toString().substring(0, 8);
            this.productId = productId;
            this.productName = productName != null ? productName : "";
            this.quantity = quantity;
            this.unitCreditRate = unitCreditRate != null ? unitCreditRate : BigDecimal.ZERO;
            this.taxPercentage = taxPercentage != null ? taxPercentage : BigDecimal.ZERO;
            this.lineTotal = lineTotal != null ? lineTotal : BigDecimal.ZERO;
            this.description = description != null ? description : "";
        }
    }

    public static final class SupplierCreditNoteDto {
        public final String noteId;
        public final String noteNumber;
        public final String supplierCreditNoteRef;
        public final String tenantId;
        public final String branchId;
        public final String supplierId;
        public final String supplierName;
        public String status; // DRAFT, POSTED, REVERSED, CANCELLED
        public final String reason;
        public final String originType;
        public final String originReference;
        public final LocalDate noteDate;
        public final BigDecimal subtotalAmount;
        public final BigDecimal taxAmount;
        public final BigDecimal totalCreditAmount;
        public String debitLedgerEntryId;
        public String reversalLedgerEntryId;
        public String reversalReason;
        public Instant reversalPostedAt;
        public String reversedBy;
        public String notes;
        public Instant postedAt;
        public final String createdBy;
        public String approvedBy;
        public final List<SupplierCreditNoteItemDto> items;

        public SupplierCreditNoteDto(String noteId, String noteNumber, String supplierCreditNoteRef,
                                     String tenantId, String branchId, String supplierId, String supplierName,
                                     String status, String reason, String originType, String originReference,
                                     LocalDate noteDate, BigDecimal subtotalAmount, BigDecimal taxAmount,
                                     BigDecimal totalCreditAmount, String debitLedgerEntryId,
                                     String reversalLedgerEntryId, String reversalReason,
                                     Instant reversalPostedAt, String reversedBy, String notes,
                                     Instant postedAt, String createdBy, String approvedBy,
                                     List<SupplierCreditNoteItemDto> items) {
            this.noteId = noteId;
            this.noteNumber = noteNumber;
            this.supplierCreditNoteRef = supplierCreditNoteRef;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.supplierId = supplierId;
            this.supplierName = supplierName;
            this.status = status;
            this.reason = reason;
            this.originType = originType;
            this.originReference = originReference;
            this.noteDate = noteDate;
            this.subtotalAmount = subtotalAmount;
            this.taxAmount = taxAmount;
            this.totalCreditAmount = totalCreditAmount;
            this.debitLedgerEntryId = debitLedgerEntryId;
            this.reversalLedgerEntryId = reversalLedgerEntryId;
            this.reversalReason = reversalReason;
            this.reversalPostedAt = reversalPostedAt;
            this.reversedBy = reversedBy;
            this.notes = notes;
            this.postedAt = postedAt;
            this.createdBy = createdBy;
            this.approvedBy = approvedBy;
            this.items = items != null ? items : new ArrayList<>();
        }
    }

    private void assertProcurementRole() {
        if ("CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_PROCUREMENT_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Staff role " + userRole + " cannot manage supplier credit notes.");
        }
    }

    private void assertReversalRole() {
        if (!ALLOWED_REVERSAL_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Managerial or Auditor role required for credit note reversal.");
        }
    }

    public SupplierCreditNoteDto createCreditNote(
            String supplierId,
            String supplierName,
            String vendorRef,
            String reason,
            String originType,
            String originReference,
            LocalDate noteDate,
            BigDecimal explicitTotal,
            List<SupplierCreditNoteItemDto> items,
            boolean autoPost) {
        assertProcurementRole();

        if (supplierId == null || supplierId.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_SUPPLIER: Supplier ID is required.");
        }

        if (vendorRef == null || vendorRef.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_VENDOR_REF: Vendor credit note reference is required.");
        }

        // Check duplicate vendor reference
        for (SupplierCreditNoteDto existing : noteStore.values()) {
            if (supplierId.equals(existing.supplierId) && vendorRef.equalsIgnoreCase(existing.supplierCreditNoteRef)) {
                throw new IllegalArgumentException("ERR_DUPLICATE_CREDIT_NOTE_REF: Vendor reference " + vendorRef + " already exists.");
            }
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        List<SupplierCreditNoteItemDto> validatedItems = new ArrayList<>();

        if (items != null && !items.isEmpty()) {
            for (SupplierCreditNoteItemDto item : items) {
                BigDecimal lineSub = item.unitCreditRate.multiply(BigDecimal.valueOf(item.quantity)).setScale(4, RoundingMode.HALF_UP);
                if (lineSub.compareTo(BigDecimal.ZERO) == 0 && item.lineTotal.compareTo(BigDecimal.ZERO) > 0) {
                    lineSub = item.lineTotal;
                }
                BigDecimal lineTax = lineSub.multiply(item.taxPercentage).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                BigDecimal lineTot = lineSub.add(lineTax);

                subtotal = subtotal.add(lineSub);
                tax = tax.add(lineTax);
                total = total.add(lineTot);

                validatedItems.add(new SupplierCreditNoteItemDto(
                        item.itemId, item.productId, item.productName, item.quantity,
                        item.unitCreditRate, item.taxPercentage, lineTot, item.description
                ));
            }
        } else {
            if (explicitTotal == null || explicitTotal.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("ERR_INVALID_CREDIT_AMOUNT: Positive credit amount or line items required.");
            }
            subtotal = explicitTotal;
            total = explicitTotal;
        }

        String noteId = "scn-" + UUID.randomUUID().toString().substring(0, 8);
        String noteNum = "SCN-" + System.currentTimeMillis();

        SupplierCreditNoteDto scn = new SupplierCreditNoteDto(
                noteId,
                noteNum,
                vendorRef,
                tenantId,
                branchId,
                supplierId,
                supplierName != null ? supplierName : "",
                "DRAFT",
                reason != null ? reason : "PRICE_DIFFERENCE",
                originType != null ? originType : "INVOICE",
                originReference != null ? originReference : vendorRef,
                noteDate != null ? noteDate : LocalDate.now(),
                subtotal,
                tax,
                total,
                null,
                null,
                null,
                null,
                null,
                "",
                null,
                userId,
                null,
                validatedItems
        );

        noteStore.put(noteId, scn);

        if (autoPost) {
            return postCreditNote(noteId);
        }

        return scn;
    }

    public SupplierCreditNoteDto postCreditNote(String noteId) {
        assertProcurementRole();

        SupplierCreditNoteDto scn = noteStore.get(noteId);
        if (scn == null) {
            throw new NoSuchElementException("ERR_CREDIT_NOTE_NOT_FOUND: Credit note " + noteId + " not found.");
        }

        if ("POSTED".equals(scn.status)) {
            throw new IllegalStateException("ERR_ALREADY_POSTED: Credit note is already posted to AP.");
        }

        if ("CANCELLED".equals(scn.status) || "REVERSED".equals(scn.status)) {
            throw new IllegalStateException("ERR_INVALID_STATUS: Cannot post credit note in status " + scn.status);
        }

        // Post Debit Note to Supplier Ledger (reduces AP liability)
        SupplierLedgerEntryDto latestEntry = ledgerStore.values().stream()
                .filter(e -> scn.supplierId.equals(e.supplierId))
                .max(Comparator.comparing(e -> e.postedAt))
                .orElse(null);
        BigDecimal prevBalance = latestEntry != null ? latestEntry.balanceAfter : BigDecimal.ZERO;

        BigDecimal newBalance = prevBalance.subtract(scn.totalCreditAmount);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            newBalance = BigDecimal.ZERO;
        }

        String postingId = "sup-post-" + UUID.randomUUID().toString().substring(0, 8);
        SupplierLedgerEntryDto entry = new SupplierLedgerEntryDto(
                postingId,
                scn.supplierId,
                "SUPPLIER_CREDIT_NOTE",
                scn.supplierCreditNoteRef,
                scn.totalCreditAmount,
                BigDecimal.ZERO,
                newBalance,
                Instant.now()
        );
        ledgerStore.put(postingId, entry);

        scn.status = "POSTED";
        scn.debitLedgerEntryId = postingId;
        scn.postedAt = Instant.now();
        scn.approvedBy = userId;

        return scn;
    }

    public SupplierCreditNoteDto reverseCreditNote(String noteId, String reversalReason) {
        assertReversalRole();

        if (reversalReason == null || reversalReason.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_REVERSAL_REASON_REQUIRED: Reversal reason is mandatory for financial audit.");
        }

        SupplierCreditNoteDto scn = noteStore.get(noteId);
        if (scn == null) {
            throw new NoSuchElementException("ERR_CREDIT_NOTE_NOT_FOUND: Credit note " + noteId + " not found.");
        }

        if ("REVERSED".equals(scn.status)) {
            throw new IllegalStateException("ERR_ALREADY_REVERSED: Credit note has already been reversed.");
        }

        if (!"POSTED".equals(scn.status)) {
            throw new IllegalStateException("ERR_CANNOT_REVERSE_UNPOSTED: Only POSTED credit notes can be reversed.");
        }

        // Reversal: restores supplier AP balance (Credit entry)
        SupplierLedgerEntryDto latestEntry = ledgerStore.values().stream()
                .filter(e -> scn.supplierId.equals(e.supplierId))
                .max(Comparator.comparing(e -> e.postedAt))
                .orElse(null);
        BigDecimal prevBalance = latestEntry != null ? latestEntry.balanceAfter : BigDecimal.ZERO;

        BigDecimal newBalance = prevBalance.add(scn.totalCreditAmount);

        String revPostingId = "sup-rev-" + UUID.randomUUID().toString().substring(0, 8);
        SupplierLedgerEntryDto revEntry = new SupplierLedgerEntryDto(
                revPostingId,
                scn.supplierId,
                "CREDIT_NOTE_REVERSAL",
                "REV-" + scn.noteNumber,
                BigDecimal.ZERO,
                scn.totalCreditAmount,
                newBalance,
                Instant.now()
        );
        ledgerStore.put(revPostingId, revEntry);

        scn.status = "REVERSED";
        scn.reversalLedgerEntryId = revPostingId;
        scn.reversalReason = reversalReason;
        scn.reversalPostedAt = Instant.now();
        scn.reversedBy = userId;

        return scn;
    }

    public SupplierCreditNoteDto cancelCreditNote(String noteId, String reason) {
        assertProcurementRole();

        SupplierCreditNoteDto scn = noteStore.get(noteId);
        if (scn == null) {
            throw new NoSuchElementException("ERR_CREDIT_NOTE_NOT_FOUND: Credit note " + noteId + " not found.");
        }

        if (!"DRAFT".equals(scn.status)) {
            throw new IllegalStateException("ERR_CANNOT_CANCEL_POSTED_CREDIT_NOTE: Cannot cancel credit note in status " + scn.status);
        }

        scn.status = "CANCELLED";
        scn.notes = (scn.notes != null ? scn.notes : "") + " | Cancelled: " + reason;
        return scn;
    }

    public SupplierCreditNoteDto getCreditNote(String noteId) {
        SupplierCreditNoteDto scn = noteStore.get(noteId);
        if (scn == null) {
            throw new NoSuchElementException("ERR_CREDIT_NOTE_NOT_FOUND: Credit note " + noteId + " not found.");
        }
        return scn;
    }

    public List<SupplierCreditNoteDto> listCreditNotes(String supplierId, String statusFilter, String originType) {
        List<SupplierCreditNoteDto> results = new ArrayList<>();
        for (SupplierCreditNoteDto n : noteStore.values()) {
            if (supplierId != null && !supplierId.isEmpty() && !supplierId.equals(n.supplierId)) {
                continue;
            }
            if (statusFilter != null && !statusFilter.isEmpty() && !statusFilter.equalsIgnoreCase(n.status)) {
                continue;
            }
            if (originType != null && !originType.isEmpty() && !originType.equalsIgnoreCase(n.originType)) {
                continue;
            }
            results.add(n);
        }
        return results;
    }
}
