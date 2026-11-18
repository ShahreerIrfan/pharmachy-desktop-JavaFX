package com.pharmacy.pos.customers;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Architecture Decision: ADR-0269 (P269).
 * Phase 14: Customers, Suppliers, Dues & Payments.
 *
 * Desktop POS Client for managing customer communication consents
 * and enforcing statutory record retention boundaries.
 */
public class CustomerConsentClient {

    public enum CommunicationChannel {
        SMS_REFILL_REMINDERS,
        SMS_DUE_REMINDERS,
        EMAIL_RECEIPTS,
        PROMOTIONAL_CAMPAIGNS,
        WHATSAPP_NOTIFICATIONS
    }

    public enum ConsentStatus {
        GRANTED,
        REVOKED
    }

    public enum CaptureMethod {
        POS_VERBAL_CONSENT,
        SIGNED_PAPER_FORM,
        SMS_OTP_CONFIRMED,
        DIGITAL_PORTAL
    }

    public static final Set<String> STATUTORY_MANDATORY_CHANNELS;

    static {
        Set<String> set = new HashSet<>();
        set.add("STATUTORY_TAX_INVOICES");
        set.add("STATUTORY_AR_LEDGER");
        set.add("STATUTORY_PRESCRIPTION_AUDIT");
        set.add("STATUTORY_ANTIBIOTIC_REGISTER");
        STATUTORY_MANDATORY_CHANNELS = Collections.unmodifiableSet(set);
    }

    public static class ConsentValidationResult {
        public final boolean isValid;
        public final String errorCode;
        public final String errorMessage;

        public ConsentValidationResult(boolean isValid, String errorCode, String errorMessage) {
            this.isValid = isValid;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public static ConsentValidationResult valid() {
            return new ConsentValidationResult(true, null, null);
        }

        public static ConsentValidationResult error(String code, String message) {
            return new ConsentValidationResult(false, code, message);
        }
    }

    /**
     * Validates whether a requested channel can be opted out of.
     * Rejects attempts to revoke legally mandated statutory record keeping.
     */
    public static ConsentValidationResult validateChannelConsentUpdate(String channel, String status) {
        if (channel == null || channel.trim().isEmpty()) {
            return ConsentValidationResult.error("ERR_CHANNEL_REQUIRED", "Channel is required.");
        }

        String chUpper = channel.trim().toUpperCase();
        if (STATUTORY_MANDATORY_CHANNELS.contains(chUpper)) {
            return ConsentValidationResult.error(
                    "ERR_STATUTORY_RECORD_IMMUTABLE",
                    "Statutory transaction, ledger, and prescription record keeping is legally mandated under Bangladesh law and cannot be opted out of."
            );
        }

        boolean isValidChannel = Arrays.stream(CommunicationChannel.values())
                .anyMatch(c -> c.name().equalsIgnoreCase(chUpper));
        if (!isValidChannel) {
            return ConsentValidationResult.error("ERR_INVALID_CONSENT_CHANNEL", "Unknown communication channel: " + channel);
        }

        if (status == null || status.trim().isEmpty()) {
            return ConsentValidationResult.error("ERR_STATUS_REQUIRED", "Consent status is required.");
        }

        String stUpper = status.trim().toUpperCase();
        boolean isValidStatus = Arrays.stream(ConsentStatus.values())
                .anyMatch(s -> s.name().equalsIgnoreCase(stUpper));
        if (!isValidStatus) {
            return ConsentValidationResult.error("ERR_INVALID_CONSENT_STATUS", "Invalid consent status: " + status);
        }

        return ConsentValidationResult.valid();
    }
}
