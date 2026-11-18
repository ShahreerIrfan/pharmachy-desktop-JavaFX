package com.pharmacy.pos.customers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Architecture Decision: ADR-0268 (P268).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * Desktop POS Client for normalized Bangladesh phone / alias search
 * and controlled duplicate customer merge operations.
 */
public class CustomerSearchMergeClient {

    private static final Pattern NON_DIGIT_PATTERN = Pattern.compile("\\D");
    private static final Set<String> ALLOWED_MERGE_ROLES;

    static {
        Set<String> roles = new HashSet<>();
        roles.add("SUPERADMIN");
        roles.add("PHARMACY_OWNER");
        roles.add("BRANCH_MANAGER");
        roles.add("ADMIN");
        ALLOWED_MERGE_ROLES = Collections.unmodifiableSet(roles);
    }

    public static class NormalizedPhoneResult {
        public final String raw;
        public final String digits;
        public final String local11;
        public final String e164;
        public final boolean isValidBd;
        public final List<String> variants;

        public NormalizedPhoneResult(String raw, String digits, String local11, String e164, boolean isValidBd, List<String> variants) {
            this.raw = raw;
            this.digits = digits;
            this.local11 = local11;
            this.e164 = e164;
            this.isValidBd = isValidBd;
            this.variants = variants;
        }
    }

    public static class CustomerSearchHit {
        public final String customerId;
        public final String fullName;
        public final String phone;
        public final BigDecimal creditLimitBdt;
        public final BigDecimal currentDueBdt;
        public final BigDecimal availableCreditBdt;
        public final boolean isCreditApproved;
        public final boolean isArchived;
        public final boolean isMerged;
        public final String mergedIntoId;
        public final String matchType;
        public final List<String> aliases;
        public final List<String> secondaryPhones;

        public CustomerSearchHit(
                String customerId,
                String fullName,
                String phone,
                BigDecimal creditLimitBdt,
                BigDecimal currentDueBdt,
                BigDecimal availableCreditBdt,
                boolean isCreditApproved,
                boolean isArchived,
                boolean isMerged,
                String mergedIntoId,
                String matchType,
                List<String> aliases,
                List<String> secondaryPhones
        ) {
            this.customerId = customerId;
            this.fullName = fullName;
            this.phone = phone;
            this.creditLimitBdt = creditLimitBdt != null ? creditLimitBdt.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
            this.currentDueBdt = currentDueBdt != null ? currentDueBdt.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
            this.availableCreditBdt = availableCreditBdt != null ? availableCreditBdt.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
            this.isCreditApproved = isCreditApproved;
            this.isArchived = isArchived;
            this.isMerged = isMerged;
            this.mergedIntoId = mergedIntoId;
            this.matchType = matchType;
            this.aliases = aliases != null ? aliases : Collections.emptyList();
            this.secondaryPhones = secondaryPhones != null ? secondaryPhones : Collections.emptyList();
        }
    }

    public static class MergeValidationResult {
        public final boolean isValid;
        public final String errorCode;
        public final String errorMessage;

        public MergeValidationResult(boolean isValid, String errorCode, String errorMessage) {
            this.isValid = isValid;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public static MergeValidationResult valid() {
            return new MergeValidationResult(true, null, null);
        }

        public static MergeValidationResult error(String code, String message) {
            return new MergeValidationResult(false, code, message);
        }
    }

    /**
     * Normalizes Bangladesh phone number input into local 11-digit and E.164 variants.
     */
    public static NormalizedPhoneResult normalizeBdPhone(String rawPhone) {
        if (rawPhone == null) {
            return new NormalizedPhoneResult("", "", "", "", false, Collections.emptyList());
        }
        String raw = rawPhone.trim();
        String digits = NON_DIGIT_PATTERN.matcher(raw).replaceAll("");
        if (digits.isEmpty()) {
            return new NormalizedPhoneResult(raw, "", "", "", false, Collections.emptyList());
        }

        String local11 = "";
        if (digits.startsWith("880") && digits.length() == 13) {
            local11 = "0" + digits.substring(3);
        } else if (digits.startsWith("0") && digits.length() == 11) {
            local11 = digits;
        } else if (digits.startsWith("1") && digits.length() == 10) {
            local11 = "0" + digits;
        } else if (digits.length() == 11 && digits.startsWith("01")) {
            local11 = digits;
        }

        boolean isValidBd = (!local11.isEmpty() && local11.length() == 11 && local11.startsWith("01"));
        String e164 = isValidBd ? "+88" + local11 : "+" + digits;

        Set<String> variantSet = new HashSet<>();
        variantSet.add(raw);
        variantSet.add(digits);
        if (isValidBd) {
            variantSet.add(local11);
            variantSet.add(e164);
            variantSet.add("88" + local11);
            variantSet.add(local11.substring(1));
        }

        List<String> variants = new ArrayList<>(variantSet);
        Collections.sort(variants);
        return new NormalizedPhoneResult(raw, digits, local11, e164, isValidBd, variants);
    }

    /**
     * Validates client-side constraints before sending merge request to API.
     */
    public static MergeValidationResult validateMergeRequest(
            String sourceCustomerId,
            String targetCustomerId,
            String mergeReason,
            String userRole
    ) {
        if (userRole == null || !ALLOWED_MERGE_ROLES.contains(userRole.trim().toUpperCase())) {
            return MergeValidationResult.error(
                    "ERR_PERMISSION_DENIED",
                    "Only Pharmacy Owner or Branch Manager may initiate customer profile merges."
            );
        }

        if (sourceCustomerId == null || sourceCustomerId.trim().isEmpty()) {
            return MergeValidationResult.error("ERR_MERGE_INPUT_INVALID", "sourceCustomerId cannot be empty.");
        }

        if (targetCustomerId == null || targetCustomerId.trim().isEmpty()) {
            return MergeValidationResult.error("ERR_MERGE_INPUT_INVALID", "targetCustomerId cannot be empty.");
        }

        if (Objects.equals(sourceCustomerId.trim(), targetCustomerId.trim())) {
            return MergeValidationResult.error("ERR_SELF_MERGE_FORBIDDEN", "Cannot merge a customer into itself.");
        }

        if (mergeReason == null || mergeReason.trim().length() < 3) {
            return MergeValidationResult.error(
                    "ERR_MERGE_REASON_REQUIRED",
                    "A valid merge reason of at least 3 characters is required."
            );
        }

        return MergeValidationResult.valid();
    }
}
