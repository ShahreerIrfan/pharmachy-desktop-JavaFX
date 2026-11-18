package com.pharmacy.pos.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PosCashBankAccountRegistryClient
 * Architecture Decision: ADR-0274 (P274).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * JavaFX Desktop POS client governing cash registers, bank accounts, and payment clearing
 * accounts with strict branch scoping and role-based access control.
 */
public class PosCashBankAccountRegistryClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final Map<String, FinancialAccountRecord> accountStore;

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER",
            "STAFF_PHARMACIST", "CASHIER", "AUDITOR", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> MANAGE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER",
            "OWNER_ADMIN", "ADMIN"
    );

    public PosCashBankAccountRegistryClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.accountStore = new HashMap<>();
    }

    public boolean canView() {
        return VIEW_ROLES.contains(this.userRole);
    }

    public boolean canManage() {
        return MANAGE_ROLES.contains(this.userRole);
    }

    public FinancialAccountRecord registerAccount(
            String accountType,
            String accountName,
            String accountCode,
            String accountNumber,
            String bankName,
            String branchName,
            String routingNumber,
            String swiftBic,
            BigDecimal openingBalance,
            boolean isDefaultForType,
            String assignedRole,
            String assignedUserId,
            String notes) {

        if (!canManage()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " is not authorized to register financial accounts.");
        }

        if (accountName == null || accountName.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_ACCOUNT_NAME: Account name is mandatory.");
        }

        String normalizedType = (accountType != null ? accountType.trim().toUpperCase() : "CASH_REGISTER");
        if ("BANK_ACCOUNT".equals(normalizedType)) {
            if (accountNumber == null || accountNumber.trim().isEmpty() || bankName == null || bankName.trim().isEmpty()) {
                throw new IllegalArgumentException("ERR_MISSING_BANK_DETAILS: Bank account requires bank name and account number.");
            }
        }

        String finalCode = (accountCode != null && !accountCode.trim().isEmpty())
                ? accountCode.trim()
                : ("ACC-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());

        for (FinancialAccountRecord existing : accountStore.values()) {
            if (finalCode.equalsIgnoreCase(existing.accountCode)) {
                throw new IllegalArgumentException("ERR_DUPLICATE_ACCOUNT_CODE: Account code " + finalCode + " already exists.");
            }
        }

        BigDecimal balance = openingBalance != null ? openingBalance.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        if (isDefaultForType) {
            for (FinancialAccountRecord existing : accountStore.values()) {
                if (existing.accountType.equals(normalizedType) && Objects.equals(existing.branchId, branchId)) {
                    existing.isDefaultForType = false;
                }
            }
        }

        String accountId = "finacc-" + UUID.randomUUID().toString().substring(0, 8);
        FinancialAccountRecord record = new FinancialAccountRecord(
                accountId,
                tenantId,
                branchId,
                normalizedType,
                accountName.trim(),
                finalCode,
                accountNumber != null ? accountNumber.trim() : "",
                bankName != null ? bankName.trim() : "",
                branchName != null ? branchName.trim() : "",
                routingNumber != null ? routingNumber.trim() : "",
                swiftBic != null ? swiftBic.trim() : "",
                "BDT",
                balance,
                balance,
                "ACTIVE",
                isDefaultForType,
                assignedRole != null ? assignedRole.trim() : "",
                assignedUserId != null ? assignedUserId.trim() : "",
                notes != null ? notes.trim() : "",
                true,
                Instant.now().toString()
        );

        accountStore.put(accountId, record);
        return record;
    }

    public FinancialAccountRecord freezeAccount(String accountId, String reason) {
        if (!canManage()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " cannot freeze accounts.");
        }
        FinancialAccountRecord acc = accountStore.get(accountId);
        if (acc == null) {
            throw new NoSuchElementException("ERR_ACCOUNT_NOT_FOUND: Account " + accountId + " not found.");
        }
        if ("CLOSED".equals(acc.status)) {
            throw new IllegalStateException("ERR_ACCOUNT_CLOSED: Cannot freeze a closed account.");
        }
        acc.status = "FROZEN";
        return acc;
    }

    public FinancialAccountRecord unfreezeAccount(String accountId, String reason) {
        if (!canManage()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " cannot unfreeze accounts.");
        }
        FinancialAccountRecord acc = accountStore.get(accountId);
        if (acc == null) {
            throw new NoSuchElementException("ERR_ACCOUNT_NOT_FOUND: Account " + accountId + " not found.");
        }
        if ("CLOSED".equals(acc.status)) {
            throw new IllegalStateException("ERR_ACCOUNT_CLOSED: Cannot unfreeze a closed account.");
        }
        acc.status = "ACTIVE";
        return acc;
    }

    public FinancialAccountRecord closeAccount(String accountId, String reason) {
        if (!canManage()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " cannot close accounts.");
        }
        FinancialAccountRecord acc = accountStore.get(accountId);
        if (acc == null) {
            throw new NoSuchElementException("ERR_ACCOUNT_NOT_FOUND: Account " + accountId + " not found.");
        }
        acc.status = "CLOSED";
        acc.isActive = false;
        acc.isDefaultForType = false;
        return acc;
    }

    public List<FinancialAccountRecord> listAccounts(String filterBranchId, String filterType, String filterStatus) {
        if (!canView()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " is not authorized to view accounts.");
        }

        return accountStore.values().stream()
                .filter(a -> {
                    if ("CASHIER".equals(userRole)) {
                        // Cashiers can only view CASH_REGISTER accounts for their assigned branch
                        if (!"CASH_REGISTER".equals(a.accountType)) return false;
                        if (!branchId.isEmpty() && !branchId.equals(a.branchId)) return false;
                    }
                    if (filterBranchId != null && !filterBranchId.isEmpty() && !filterBranchId.equals(a.branchId)) return false;
                    if (filterType != null && !filterType.isEmpty() && !filterType.equalsIgnoreCase(a.accountType)) return false;
                    if (filterStatus != null && !filterStatus.isEmpty() && !filterStatus.equalsIgnoreCase(a.status)) return false;
                    return true;
                })
                .sorted(Comparator.comparing((FinancialAccountRecord a) -> a.accountType).thenComparing(a -> a.accountName))
                .collect(Collectors.toList());
    }

    public static class FinancialAccountRecord {
        public final String accountId;
        public final String tenantId;
        public final String branchId;
        public final String accountType;
        public String accountName;
        public final String accountCode;
        public String accountNumber;
        public String bankName;
        public String branchName;
        public String routingNumber;
        public String swiftBic;
        public final String currency;
        public final BigDecimal openingBalance;
        public BigDecimal currentBalance;
        public String status;
        public boolean isDefaultForType;
        public String assignedRole;
        public String assignedUserId;
        public String notes;
        public boolean isActive;
        public final String createdAt;

        public FinancialAccountRecord(String accountId, String tenantId, String branchId, String accountType,
                                      String accountName, String accountCode, String accountNumber, String bankName,
                                      String branchName, String routingNumber, String swiftBic, String currency,
                                      BigDecimal openingBalance, BigDecimal currentBalance, String status,
                                      boolean isDefaultForType, String assignedRole, String assignedUserId,
                                      String notes, boolean isActive, String createdAt) {
            this.accountId = accountId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.accountType = accountType;
            this.accountName = accountName;
            this.accountCode = accountCode;
            this.accountNumber = accountNumber;
            this.bankName = bankName;
            this.branchName = branchName;
            this.routingNumber = routingNumber;
            this.swiftBic = swiftBic;
            this.currency = currency;
            this.openingBalance = openingBalance;
            this.currentBalance = currentBalance;
            this.status = status;
            this.isDefaultForType = isDefaultForType;
            this.assignedRole = assignedRole;
            this.assignedUserId = assignedUserId;
            this.notes = notes;
            this.isActive = isActive;
            this.createdAt = createdAt;
        }
    }
}
