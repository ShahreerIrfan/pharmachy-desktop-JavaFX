package com.pharmacy.pos.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P088: JavaFX Desktop POS Manual Payment Verification Client Tests")
class PosManualPaymentClientTest {

    @Test
    @DisplayName("Valid manual payment submission passes validation")
    void validSubmissionPasses() {
        BigDecimal invoiceTotal = new BigDecimal("6825.00");
        BigDecimal claimedAmount = new BigDecimal("6825.00");

        PosManualPaymentClient.ClaimValidationResult result = PosManualPaymentClient.validateClaimSubmission(
            invoiceTotal,
            claimedAmount,
            "BKASH",
            "BKASH-TRX-1234",
            "https://storage.pharmasaas.internal/evidence/slip1.png"
        );

        assertTrue(result.isValid);
        assertNull(result.errorMessage);
    }

    @Test
    @DisplayName("Amount mismatch rejects claim submission")
    void amountMismatchRejected() {
        BigDecimal invoiceTotal = new BigDecimal("6825.00");
        BigDecimal claimedAmount = new BigDecimal("5000.00");

        PosManualPaymentClient.ClaimValidationResult result = PosManualPaymentClient.validateClaimSubmission(
            invoiceTotal,
            claimedAmount,
            "BKASH",
            "BKASH-TRX-1234",
            "https://storage.pharmasaas.internal/evidence/slip1.png"
        );

        assertFalse(result.isValid);
        assertTrue(result.errorMessage.contains("Claimed amount does not match"));
    }

    @Test
    @DisplayName("Unsupported payment method or short ref rejects submission")
    void invalidMethodOrShortRefRejected() {
        BigDecimal invoiceTotal = new BigDecimal("2500.00");

        PosManualPaymentClient.ClaimValidationResult badMethod = PosManualPaymentClient.validateClaimSubmission(
            invoiceTotal,
            invoiceTotal,
            "CRYPTO_BITCOIN",
            "TX-9999",
            "https://slip.url"
        );
        assertFalse(badMethod.isValid);
        assertTrue(badMethod.errorMessage.contains("Unsupported payment method"));

        PosManualPaymentClient.ClaimValidationResult shortRef = PosManualPaymentClient.validateClaimSubmission(
            invoiceTotal,
            invoiceTotal,
            "NAGAD",
            "TX",
            "https://slip.url"
        );
        assertFalse(shortRef.isValid);
        assertTrue(shortRef.errorMessage.contains("at least 4 characters"));
    }

    @Test
    @DisplayName("Maker-Checker invariant: Prohibits self-approval, permits distinct checker")
    void makerCheckerSeparationEnforced() {
        String makerId = "usr-maker-99";
        String sameCheckerId = "usr-maker-99";
        String distinctCheckerId = "usr-admin-checker-01";

        assertFalse(PosManualPaymentClient.canCheckerApprove(makerId, sameCheckerId));
        assertTrue(PosManualPaymentClient.canCheckerApprove(makerId, distinctCheckerId));
        assertFalse(PosManualPaymentClient.canCheckerApprove(null, distinctCheckerId));
    }

    @Test
    @DisplayName("Bilingual status translation and BDT currency formatting render correctly")
    void translationAndFormatting() {
        assertEquals("যাচাইকরণ প্রক্রিয়াধীন", PosManualPaymentClient.getStatusLabel("PENDING_VERIFICATION", true));
        assertEquals("Pending Verification", PosManualPaymentClient.getStatusLabel("PENDING_VERIFICATION", false));
        assertEquals("অনুমোদিত ও সক্রিয়", PosManualPaymentClient.getStatusLabel("APPROVED", true));
        assertEquals("Approved & Active", PosManualPaymentClient.getStatusLabel("APPROVED", false));

        assertEquals("৳ 6825.00", PosManualPaymentClient.formatCurrency(new BigDecimal("6825.00")));
    }
}
