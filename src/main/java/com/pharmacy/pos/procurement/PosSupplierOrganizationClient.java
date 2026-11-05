package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/**
 * PosSupplierOrganizationClient
 * Architecture Decision: ADR-0181 (P181).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 *
 * Desktop POS client governing supplier organizations, legal credentials,
 * NBR TIN/BIN, wholesale drug license, AP ledger postings, and authentic origin verification.
 */
public class PosSupplierOrganizationClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, SupplierDto> supplierStore;
    private final List<SupplierLedgerEntryDto> ledgerStore;

    private static final Set<String> ALLOWED_PROCUREMENT_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "PROCUREMENT_OFFICER", "AUDITOR"
    );

    public PosSupplierOrganizationClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, SupplierDto> supplierStore,
            List<SupplierLedgerEntryDto> ledgerStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.supplierStore = supplierStore != null ? supplierStore : new HashMap<>();
        this.ledgerStore = ledgerStore != null ? ledgerStore : new ArrayList<>();
    }

    public static final class SupplierDto {
        public final String supplierId;
        public final String tenantId;
        public String name;
        public String nameBn;
        public String legalName;
        public String code;
        public String contactPerson;
        public String phone;
        public String email;
        public String address;
        public String region;
        public String district;
        public String dlNumber;
        public String tinNumber;
        public String binNumber;
        public String tradeLicenseNumber;
        public String status;
        public int paymentTermsDays;
        public BigDecimal creditLimit;
        public BigDecimal currentBalance;
        public String bankName;
        public String bankAccountName;
        public String bankAccountNo;
        public String bankRoutingNumber;
        public int leadTimeDays;
        public boolean proofOfOriginVerified;
        public String proofOfOriginNotes;
        public boolean isActive;
        public final Instant createdAt;

        public SupplierDto(
                String supplierId,
                String tenantId,
                String name,
                String nameBn,
                String legalName,
                String code,
                String contactPerson,
                String phone,
                String email,
                String address,
                String region,
                String district,
                String dlNumber,
                String tinNumber,
                String binNumber,
                String tradeLicenseNumber,
                String status,
                int paymentTermsDays,
                BigDecimal creditLimit,
                BigDecimal currentBalance,
                String bankName,
                String bankAccountName,
                String bankAccountNo,
                String bankRoutingNumber,
                int leadTimeDays,
                boolean proofOfOriginVerified,
                String proofOfOriginNotes,
                boolean isActive,
                Instant createdAt) {
            this.supplierId = supplierId;
            this.tenantId = tenantId;
            this.name = name;
            this.nameBn = nameBn;
            this.legalName = legalName;
            this.code = code;
            this.contactPerson = contactPerson;
            this.phone = phone;
            this.email = email;
            this.address = address;
            this.region = region;
            this.district = district;
            this.dlNumber = dlNumber;
            this.tinNumber = tinNumber;
            this.binNumber = binNumber;
            this.tradeLicenseNumber = tradeLicenseNumber;
            this.status = status;
            this.paymentTermsDays = paymentTermsDays;
            this.creditLimit = creditLimit != null ? creditLimit.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.currentBalance = currentBalance != null ? currentBalance.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.bankName = bankName;
            this.bankAccountName = bankAccountName;
            this.bankAccountNo = bankAccountNo;
            this.bankRoutingNumber = bankRoutingNumber;
            this.leadTimeDays = leadTimeDays;
            this.proofOfOriginVerified = proofOfOriginVerified;
            this.proofOfOriginNotes = proofOfOriginNotes;
            this.isActive = isActive;
            this.createdAt = createdAt != null ? createdAt : Instant.now();
        }
    }

    public static final class SupplierLedgerEntryDto {
        public final String postingId;
        public final String tenantId;
        public final String supplierId;
        public final String branchId;
        public final String postingType;
        public final String referenceNumber;
        public final BigDecimal debitAmount;
        public final BigDecimal creditAmount;
        public final BigDecimal balanceAfter;
        public final String currency;
        public final String notes;
        public final Instant postedAt;

        public SupplierLedgerEntryDto(
                String postingId,
                String tenantId,
                String supplierId,
                String branchId,
                String postingType,
                String referenceNumber,
                BigDecimal debitAmount,
                BigDecimal creditAmount,
                BigDecimal balanceAfter,
                String currency,
                String notes,
                Instant postedAt) {
            this.postingId = postingId;
            this.tenantId = tenantId;
            this.supplierId = supplierId;
            this.branchId = branchId;
            this.postingType = postingType;
            this.referenceNumber = referenceNumber;
            this.debitAmount = debitAmount.setScale(4, RoundingMode.HALF_UP);
            this.creditAmount = creditAmount.setScale(4, RoundingMode.HALF_UP);
            this.balanceAfter = balanceAfter.setScale(4, RoundingMode.HALF_UP);
            this.currency = currency;
            this.notes = notes;
            this.postedAt = postedAt != null ? postedAt : Instant.now();
        }
    }

    private void assertProcurementRole() {
        if ("CASHIER".equalsIgnoreCase(this.userRole) || !ALLOWED_PROCUREMENT_ROLES.contains(this.userRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers and unauthorized staff cannot manage suppliers.");
        }
    }

    public SupplierDto createSupplier(
            String name,
            String legalName,
            String phone,
            String email,
            String region,
            String dlNumber,
            String binNumber,
            String tinNumber,
            String tradeLicenseNumber,
            int paymentTermsDays,
            BigDecimal creditLimit) {
        assertProcurementRole();

        if (name == null || name.trim().length() < 2) {
            throw new IllegalArgumentException("ERR_MISSING_SUPPLIER_NAME: Supplier name is required (min 2 chars).");
        }

        for (SupplierDto s : supplierStore.values()) {
            if (this.tenantId.equals(s.tenantId) && s.isActive && s.name.equalsIgnoreCase(name.trim())) {
                throw new IllegalStateException("ERR_DUPLICATE_SUPPLIER_NAME: Active supplier with this name already exists.");
            }
        }

        if (creditLimit != null && creditLimit.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("ERR_INVALID_CREDIT_LIMIT: Credit limit cannot be negative.");
        }

        String supplierId = "sup-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        SupplierDto dto = new SupplierDto(
                supplierId,
                this.tenantId,
                name.trim(),
                "",
                legalName != null ? legalName.trim() : name.trim(),
                "SUP-" + System.currentTimeMillis() % 100000,
                "",
                phone != null ? phone.trim() : "",
                email != null ? email.trim() : "",
                "",
                region != null ? region.toUpperCase() : "DHAKA",
                "",
                dlNumber != null ? dlNumber.trim() : "",
                tinNumber != null ? tinNumber.trim() : "",
                binNumber != null ? binNumber.trim() : "",
                tradeLicenseNumber != null ? tradeLicenseNumber.trim() : "",
                "ACTIVE",
                Math.max(0, paymentTermsDays),
                creditLimit != null ? creditLimit : BigDecimal.ZERO,
                BigDecimal.ZERO,
                "",
                "",
                "",
                "",
                2,
                false,
                "",
                true,
                Instant.now()
        );

        supplierStore.put(dto.supplierId, dto);
        return dto;
    }

    public SupplierDto getSupplier(String supplierId) {
        SupplierDto s = supplierStore.get(supplierId);
        if (s == null) {
            throw new NoSuchElementException("ERR_SUPPLIER_NOT_FOUND: Supplier not found: " + supplierId);
        }
        if (!this.tenantId.equals(s.tenantId)) {
            throw new SecurityException("ERR_CROSS_TENANT_ACCESS: Cross-tenant supplier access forbidden.");
        }
        return s;
    }

    public List<SupplierDto> listSuppliers() {
        List<SupplierDto> list = new ArrayList<>();
        for (SupplierDto s : supplierStore.values()) {
            if (this.tenantId.equals(s.tenantId)) {
                list.add(s);
            }
        }
        list.sort(Comparator.comparing(a -> a.name));
        return list;
    }

    public SupplierLedgerEntryDto postApLedgerEntry(
            String supplierId,
            String postingType,
            String referenceNumber,
            BigDecimal debitAmount,
            BigDecimal creditAmount,
            String notes) {
        assertProcurementRole();

        SupplierDto s = getSupplier(supplierId);
        if ("BLACKLISTED".equalsIgnoreCase(s.status)) {
            throw new IllegalStateException("ERR_SUPPLIER_BLACKLISTED: Cannot post transactions for blacklisted suppliers.");
        }

        BigDecimal debit = debitAmount != null ? debitAmount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        BigDecimal credit = creditAmount != null ? creditAmount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        if (debit.compareTo(BigDecimal.ZERO) < 0 || credit.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("ERR_INVALID_AMOUNT: Debit and credit amounts must be non-negative.");
        }

        // Credit increases payable (GRN), Debit decreases payable (Payment / Return)
        BigDecimal delta = credit.subtract(debit);
        BigDecimal newBalance = s.currentBalance.add(delta).setScale(4, RoundingMode.HALF_UP);

        String postingId = "sup-post-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        SupplierLedgerEntryDto entry = new SupplierLedgerEntryDto(
                postingId,
                this.tenantId,
                supplierId,
                this.branchId,
                postingType != null ? postingType.toUpperCase() : "GRN_INVOICE",
                referenceNumber != null ? referenceNumber.trim() : "",
                debit,
                credit,
                newBalance,
                "BDT",
                notes != null ? notes.trim() : "",
                Instant.now()
        );

        s.currentBalance = newBalance;
        ledgerStore.add(entry);
        return entry;
    }

    public SupplierDto verifyProofOfOrigin(String supplierId, String verificationNotes) {
        assertProcurementRole();
        SupplierDto s = getSupplier(supplierId);
        s.proofOfOriginVerified = true;
        s.proofOfOriginNotes = verificationNotes != null ? verificationNotes.trim() : "Verified Authentic Depot";
        return s;
    }

    public boolean isOffline() {
        return isOffline;
    }
}

