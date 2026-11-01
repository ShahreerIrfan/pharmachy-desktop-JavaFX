package com.pharmacy.pos.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;

/**
 * PosPaymentGatewayClient
 * Architecture Decision: ADR-0007, ADR-0023 & ADR-0089
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P089)
 *
 * Client-side payment gateway checkout session handler, signed webhook
 * idempotency resolution, and BDT currency formatter for JavaFX Desktop POS.
 */
public class PosPaymentGatewayClient {

    public static final List<String> SUPPORTED_GATEWAYS = Arrays.asList(
        "BKASH", "NAGAD", "SSLCOMMERZ"
    );

    public static final class CheckoutSessionResult {
        public final boolean isSuccess;
        public final String gatewayName;
        public final String checkoutUrl;
        public final String gatewayPaymentId;
        public final BigDecimal amountBdt;
        public final String errorMessage;

        public CheckoutSessionResult(
            boolean isSuccess,
            String gatewayName,
            String checkoutUrl,
            String gatewayPaymentId,
            BigDecimal amountBdt,
            String errorMessage
        ) {
            this.isSuccess = isSuccess;
            this.gatewayName = gatewayName;
            this.checkoutUrl = checkoutUrl;
            this.gatewayPaymentId = gatewayPaymentId;
            this.amountBdt = amountBdt;
            this.errorMessage = errorMessage;
        }

        public static CheckoutSessionResult success(
            String gatewayName,
            String checkoutUrl,
            String gatewayPaymentId,
            BigDecimal amountBdt
        ) {
            return new CheckoutSessionResult(true, gatewayName, checkoutUrl, gatewayPaymentId, amountBdt, null);
        }

        public static CheckoutSessionResult failure(String message) {
            return new CheckoutSessionResult(false, null, null, null, BigDecimal.ZERO, message);
        }
    }

    /**
     * Validates whether a gateway is supported.
     */
    public static boolean isGatewaySupported(String gatewayName) {
        if (gatewayName == null) return false;
        return SUPPORTED_GATEWAYS.contains(gatewayName.toUpperCase().trim());
    }

    /**
     * Evaluates webhook idempotency and execution status.
     */
    public static boolean isDuplicateEvent(String status) {
        return "DUPLICATE".equalsIgnoreCase(status);
    }

    public static boolean isProcessedEvent(String status) {
        return "PROCESSED".equalsIgnoreCase(status);
    }

    /**
     * Human-friendly bilingual gateway event status translation.
     */
    public static String getEventStatusLabel(String status, boolean bangla) {
        if (status == null) return bangla ? "অজানা" : "UNKNOWN";
        switch (status.toUpperCase().trim()) {
            case "PROCESSED":
                return bangla ? "সফলভাবে প্রক্রিয়াকৃত" : "Processed";
            case "DUPLICATE":
                return bangla ? "অনুলিপি ইভেন্ট (উপেক্ষিত)" : "Duplicate (Ignored)";
            case "FAILED":
                return bangla ? "ব্যর্থ" : "Failed";
            case "SIGNATURE_INVALID":
                return bangla ? "অবৈধ স্বাক্ষর" : "Invalid Signature";
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

