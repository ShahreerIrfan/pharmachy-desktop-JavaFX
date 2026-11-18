package com.pharmacy.pos.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PosPaymentMethodRegistryClient
 * Architecture Decision: ADR-0275 (P275).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * JavaFX Desktop POS client governing payment method configuration (Cash, bKash, Nagad, Cards),
 * surcharge rules, tender validation, and secret protection invariants.
 */
public class PosPaymentMethodRegistryClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;

    private final Map<String, PaymentMethodConfigRecord> configStore;

    public static final Set<String> VIEW_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER",
            "STAFF_PHARMACIST", "CASHIER", "AUDITOR", "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> MANAGE_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER",
            "OWNER_ADMIN", "ADMIN"
    );

    public static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "secret", "api_key", "apikey", "password", "pin", "mpin", "cvv", "cvc", "private_key"
    );

    public PosPaymentMethodRegistryClient(String tenantId, String branchId, String userRole, String userId) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole.toUpperCase() : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.configStore = new HashMap<>();
    }

    public boolean canView() {
        return VIEW_ROLES.contains(this.userRole);
    }

    public boolean canManage() {
        return MANAGE_ROLES.contains(this.userRole);
    }

    public void checkNoSensitiveSecrets(Map<String, Object> payload) {
        if (payload == null) return;
        for (String k : payload.keySet()) {
            String lower = k.toLowerCase().trim();
            if ("vault_secret_reference".equals(lower)) continue;
            for (String kw : SENSITIVE_KEYWORDS) {
                if (lower.contains(kw) && payload.get(k) != null) {
                    throw new IllegalArgumentException("ERR_SENSITIVE_SECRET_EXPOSURE_BLOCKED: Storage of sensitive payment secrets is strictly prohibited.");
                }
            }
        }
    }

    public PaymentMethodConfigRecord configurePaymentMethod(
            String methodType,
            String displayName,
            int displayOrder,
            boolean isEnabled,
            String clearingAccountId,
            boolean requiresReference,
            String referenceLabel,
            String referencePattern,
            BigDecimal surchargePct,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            boolean allowOffline,
            String vaultSecretReference,
            Map<String, Object> optionalSecretsCheck) {

        if (!canManage()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " cannot configure payment methods.");
        }

        checkNoSensitiveSecrets(optionalSecretsCheck);

        if (methodType == null || methodType.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_INVALID_PAYMENT_METHOD: Method type is required.");
        }

        String normType = methodType.trim().toUpperCase();
        String name = (displayName != null && !displayName.trim().isEmpty()) ? displayName.trim() : normType;

        BigDecimal minAmt = minAmount != null ? minAmount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal maxAmt = maxAmount != null ? maxAmount.setScale(2, RoundingMode.HALF_UP) : new BigDecimal("500000.00");
        if (minAmt.compareTo(maxAmt) > 0) {
            throw new IllegalArgumentException("ERR_INVALID_TRANSACTION_LIMITS: Min amount cannot exceed max amount.");
        }

        BigDecimal feePct = surchargePct != null ? surchargePct.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        // Match existing config
        PaymentMethodConfigRecord existing = null;
        for (PaymentMethodConfigRecord c : configStore.values()) {
            if (c.methodType.equals(normType) && Objects.equals(c.branchId, branchId)) {
                existing = c;
                break;
            }
        }

        String configId = existing != null ? existing.configId : ("pmcfg-" + UUID.randomUUID().toString().substring(0, 8));
        PaymentMethodConfigRecord record = new PaymentMethodConfigRecord(
                configId,
                tenantId,
                branchId,
                normType,
                name,
                displayOrder,
                isEnabled,
                clearingAccountId != null ? clearingAccountId : "",
                requiresReference,
                referenceLabel != null ? referenceLabel : "",
                referencePattern != null ? referencePattern : "",
                feePct,
                minAmt,
                maxAmt,
                allowOffline,
                vaultSecretReference != null ? vaultSecretReference : "",
                Instant.now().toString()
        );

        configStore.put(configId, record);
        return record;
    }

    public PaymentMethodConfigRecord togglePaymentMethod(String configId, boolean isEnabled) {
        if (!canManage()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " cannot toggle payment methods.");
        }
        PaymentMethodConfigRecord cfg = configStore.get(configId);
        if (cfg == null) {
            throw new NoSuchElementException("ERR_CONFIG_NOT_FOUND: Payment config " + configId + " not found.");
        }
        cfg.isEnabled = isEnabled;
        return cfg;
    }

    public TenderValidationResult validateTender(String configId, BigDecimal amount, String reference, boolean isOffline) {
        if (!canView()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " is not authorized to validate tender.");
        }
        PaymentMethodConfigRecord cfg = configStore.get(configId);
        if (cfg == null) {
            throw new NoSuchElementException("ERR_CONFIG_NOT_FOUND: Payment config " + configId + " not found.");
        }

        if (!cfg.isEnabled) {
            throw new IllegalStateException("ERR_PAYMENT_METHOD_DISABLED: Payment method " + cfg.displayName + " is disabled.");
        }

        if (isOffline && !cfg.allowOffline) {
            throw new IllegalStateException("ERR_OFFLINE_PAYMENT_NOT_ALLOWED: Payment method " + cfg.displayName + " cannot be used offline.");
        }

        BigDecimal amt = amount != null ? amount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (amt.compareTo(cfg.minTransactionBdt) < 0 || amt.compareTo(cfg.maxTransactionBdt) > 0) {
            throw new IllegalArgumentException("ERR_TRANSACTION_AMOUNT_OUT_OF_BOUNDS: Amount " + amt + " is outside permitted bounds.");
        }

        String refClean = reference != null ? reference.trim() : "";
        if (cfg.requiresReference && refClean.isEmpty()) {
            throw new IllegalArgumentException("ERR_MISSING_TRANSACTION_REFERENCE: Transaction reference is required for " + cfg.displayName);
        }

        if (cfg.requiresReference && !cfg.referencePattern.isEmpty() && !refClean.isEmpty()) {
            if (!Pattern.matches(cfg.referencePattern, refClean)) {
                throw new IllegalArgumentException("ERR_INVALID_REFERENCE_FORMAT: Reference does not match pattern " + cfg.referencePattern);
            }
        }

        BigDecimal surcharge = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (cfg.customerSurchargePercentage.compareTo(BigDecimal.ZERO) > 0) {
            surcharge = amt.multiply(cfg.customerSurchargePercentage).divide(new BigDecimal("100.00"), 2, RoundingMode.HALF_UP);
        }
        BigDecimal totalPayable = amt.add(surcharge);

        return new TenderValidationResult(true, cfg.methodType, cfg.displayName, amt, surcharge, totalPayable, refClean);
    }

    public List<PaymentMethodConfigRecord> listPaymentMethods(String filterBranchId, boolean onlyEnabled) {
        if (!canView()) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Role " + userRole + " cannot list payment methods.");
        }

        return configStore.values().stream()
                .filter(c -> {
                    if (filterBranchId != null && !filterBranchId.isEmpty() && !filterBranchId.equals(c.branchId) && !c.branchId.isEmpty()) {
                        return false;
                    }
                    if (onlyEnabled && !c.isEnabled) {
                        return false;
                    }
                    return true;
                })
                .sorted(Comparator.comparingInt((PaymentMethodConfigRecord c) -> c.displayOrder).thenComparing(c -> c.displayName))
                .collect(Collectors.toList());
    }

    public static class PaymentMethodConfigRecord {
        public final String configId;
        public final String tenantId;
        public final String branchId;
        public final String methodType;
        public String displayName;
        public int displayOrder;
        public boolean isEnabled;
        public String clearingAccountId;
        public boolean requiresReference;
        public String referenceLabel;
        public String referencePattern;
        public BigDecimal customerSurchargePercentage;
        public BigDecimal minTransactionBdt;
        public BigDecimal maxTransactionBdt;
        public boolean allowOffline;
        public String vaultSecretReference;
        public final String createdAt;

        public PaymentMethodConfigRecord(String configId, String tenantId, String branchId, String methodType,
                                         String displayName, int displayOrder, boolean isEnabled,
                                         String clearingAccountId, boolean requiresReference,
                                         String referenceLabel, String referencePattern,
                                         BigDecimal customerSurchargePercentage,
                                         BigDecimal minTransactionBdt, BigDecimal maxTransactionBdt,
                                         boolean allowOffline, String vaultSecretReference, String createdAt) {
            this.configId = configId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.methodType = methodType;
            this.displayName = displayName;
            this.displayOrder = displayOrder;
            this.isEnabled = isEnabled;
            this.clearingAccountId = clearingAccountId;
            this.requiresReference = requiresReference;
            this.referenceLabel = referenceLabel;
            this.referencePattern = referencePattern;
            this.customerSurchargePercentage = customerSurchargePercentage;
            this.minTransactionBdt = minTransactionBdt;
            this.maxTransactionBdt = maxTransactionBdt;
            this.allowOffline = allowOffline;
            this.vaultSecretReference = vaultSecretReference;
            this.createdAt = createdAt;
        }
    }

    public static class TenderValidationResult {
        public final boolean isValid;
        public final String methodType;
        public final String displayName;
        public final BigDecimal amountBdt;
        public final BigDecimal surchargeBdt;
        public final BigDecimal totalPayableBdt;
        public final String reference;

        public TenderValidationResult(boolean isValid, String methodType, String displayName,
                                      BigDecimal amountBdt, BigDecimal surchargeBdt,
                                      BigDecimal totalPayableBdt, String reference) {
            this.isValid = isValid;
            this.methodType = methodType;
            this.displayName = displayName;
            this.amountBdt = amountBdt;
            this.surchargeBdt = surchargeBdt;
            this.totalPayableBdt = totalPayableBdt;
            this.reference = reference;
        }
    }
}
