package com.pharmacy.pos.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P089: JavaFX Desktop POS Payment Gateway Integration Client Tests")
class PosPaymentGatewayClientTest {

    @Test
    @DisplayName("Gateway support check permits BKASH, NAGAD, SSLCOMMERZ, rejects unknown")
    void gatewaySupportCheck() {
        assertTrue(PosPaymentGatewayClient.isGatewaySupported("BKASH"));
        assertTrue(PosPaymentGatewayClient.isGatewaySupported("bkash"));
        assertTrue(PosPaymentGatewayClient.isGatewaySupported("NAGAD"));
        assertTrue(PosPaymentGatewayClient.isGatewaySupported("SSLCOMMERZ"));

        assertFalse(PosPaymentGatewayClient.isGatewaySupported("STRIPE_USA"));
        assertFalse(PosPaymentGatewayClient.isGatewaySupported(null));
    }

    @Test
    @DisplayName("Checkout session result model builds success and failure states")
    void checkoutSessionModel() {
        PosPaymentGatewayClient.CheckoutSessionResult success = PosPaymentGatewayClient.CheckoutSessionResult.success(
            "BKASH",
            "https://checkout.sandbox.bka.sh/v1.2.0-beta/checkout/pay-1234",
            "pay-1234",
            new BigDecimal("6825.00")
        );

        assertTrue(success.isSuccess);
        assertEquals("BKASH", success.gatewayName);
        assertEquals("pay-1234", success.gatewayPaymentId);
        assertNull(success.errorMessage);

        PosPaymentGatewayClient.CheckoutSessionResult failure = PosPaymentGatewayClient.CheckoutSessionResult.failure(
            "Downstream gateway unavailable"
        );
        assertFalse(failure.isSuccess);
        assertEquals("Downstream gateway unavailable", failure.errorMessage);
    }

    @Test
    @DisplayName("Webhook idempotency predicates recognize PROCESSED and DUPLICATE")
    void idempotencyPredicates() {
        assertTrue(PosPaymentGatewayClient.isDuplicateEvent("DUPLICATE"));
        assertTrue(PosPaymentGatewayClient.isDuplicateEvent("duplicate"));
        assertFalse(PosPaymentGatewayClient.isDuplicateEvent("PROCESSED"));

        assertTrue(PosPaymentGatewayClient.isProcessedEvent("PROCESSED"));
        assertFalse(PosPaymentGatewayClient.isProcessedEvent("DUPLICATE"));
    }

    @Test
    @DisplayName("Bilingual event translation and currency formatting render properly")
    void bilingualAndCurrency() {
        assertEquals("সফলভাবে প্রক্রিয়াকৃত", PosPaymentGatewayClient.getEventStatusLabel("PROCESSED", true));
        assertEquals("Processed", PosPaymentGatewayClient.getEventStatusLabel("PROCESSED", false));
        assertEquals("অনুলিপি ইভেন্ট (উপেক্ষিত)", PosPaymentGatewayClient.getEventStatusLabel("DUPLICATE", true));
        assertEquals("Duplicate (Ignored)", PosPaymentGatewayClient.getEventStatusLabel("DUPLICATE", false));

        assertEquals("৳ 6825.00", PosPaymentGatewayClient.formatCurrency(new BigDecimal("6825.00")));
    }
}

