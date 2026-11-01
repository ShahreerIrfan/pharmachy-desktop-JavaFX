package com.pharmacy.pos.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * PosManualPaymentClient
 * Architecture Decision: ADR-0007, ADR-0023 & ADR-0088
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P088)
 *
 * Client-side manual payment claim validator, maker-checker separation check,
 * and BDT currency formatter for JavaFX Desktop POS.
 */
public class PosManualPaymentClient {

    public static final List<String> SUPPORTED_PAYMENT_METHODS = Arrays.asList(
        "BKASH", "NAGAD", "ROCKET", "BANK_TRANSFER", "CASH_DEPOSIT"
    );

    public static final class ClaimValidationResult {
        public final boolean isValid;
        public final String errorMessage;

        public ClaimValidationResult(boolean isValid, String errorMessage) {
            this.isValid = isValid;
            this.errorMessage = errorMessage;
        }

        public static ClaimValidationResult valid() {
            return new ClaimValidationResult(true, null);
        }

        public static ClaimValidationResult invalid(String message) {
            return new ClaimValidationResult(false, message);
        }
    }

    /**
     * Validates manual payment claim submission inputs before dispatching to API.
     */
    public static ClaimValidationResult validateClaimSubmission(
        BigDecimal expectedInvoiceTotal,
        BigDecimal claimedAmount,
        String paymentMethod,
        String transactionReference,
        String evidenceUrl
    ) {
        if (expectedInvoiceTotal == null || expectedInvoiceTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return ClaimValidationResult.invalid("Invoice total must be greater than zero.");
        }
        if (claimedAmount == null) {
            return ClaimValidationResult.invalid("Claimed amount is required.");
        }
        if (expectedInvoiceTotal.setScale(2, RoundingMode.HALF_UP).compareTo(claimedAmount.setScale(2, RoundingMode.HALF_UP)) != 0) {
            return ClaimValidationResult.invalid("Claimed amount does not match invoice total.");
        }
        if (paymentMethod == null || !SUPPORTED_PAYMENT_METHODS.contains(paymentMethod.toUpperCase().trim())) {
            return ClaimValidationResult.invalid("Unsupported payment method: " + paymentMethod);
        }
        if (transactionReference == null || transactionReference.trim().length() < 4) {
            return ClaimValidationResult.invalid("Transaction reference must be at least 4 characters.");
        }
        if (evidenceUrl == null || evidenceUrl.trim().length() < 4) {
            return ClaimValidationResult.invalid("Evidence file reference or URL is required.");
        }
        return ClaimValidationResult.valid();
    }

    /**
     * Enforces Maker-Checker separation invariant: A user cannot self-approve their own claim.
     */
    public static boolean canCheckerApprove(String makerUserId, String checkerUserId) {
        if (makerUserId == null || checkerUserId == null) {
            return false;
        }
        return !makerUserId.trim().equalsIgnoreCase(checkerUserId.trim());
    }

    /**
     * Human-friendly bilingual status translation.
     */
    public static String getStatusLabel(String status, boolean bangla) {
        if (status == null) return bangla ? "অজানা" : "UNKNOWN";
        switch (status.toUpperCase().trim()) {
            case "PENDING_VERIFICATION":
                return bangla ? "যাচাইকরণ প্রক্রিয়াধীন" : "Pending Verification";
            case "APPROVED":
                return bangla ? "অনুমোদিত ও সক্রিয়" : "Approved & Active";
            case "REJECTED":
                return bangla ? "প্রত্যাখ্যাত" : "Rejected";
            case "CANCELLED":
                return bangla ? "বাতিলকৃত" : "Cancelled";
            default:
                return status;
        }
    }

    /**
     * Formats currency with BDT symbol.
     */
    public static String formatCurrency(BigDecimal amount) {
        BigDecimal val = amount != null ? amount : BigDecimal.ZERO;
        return "৳ " + val.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
