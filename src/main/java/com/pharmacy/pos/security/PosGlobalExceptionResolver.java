package com.pharmacy.pos.security;

import com.pharmacy.pos.contracts.PosApiResponseEnvelope;
import com.pharmacy.pos.contracts.PosApiResponseEnvelope.UiState;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Architecture Decision: ADR-0014, ADR-0047, ADR-0054
 * Desktop POS Global Exception Resolver:
 * Centralizes exception decoding, mapping canonical machine error codes into
 * UI presentation states, preserving trace IDs, and rendering safe localized English and Bengali feedback.
 */
public class PosGlobalExceptionResolver {

    public record ResolvedErrorInfo(
            UiState uiState,
            String errorCode,
            String requestId,
            int httpStatus,
            boolean isRetryable,
            String recoveryAction,
            String localizedMessage,
            Map<String, List<String>> fieldErrors
    ) {
        public ResolvedErrorInfo {
            Objects.requireNonNull(uiState, "uiState must not be null");
            Objects.requireNonNull(errorCode, "errorCode must not be null");
            Objects.requireNonNull(requestId, "requestId must not be null");
            Objects.requireNonNull(localizedMessage, "localizedMessage must not be null");
            if (fieldErrors == null) {
                fieldErrors = Collections.emptyMap();
            }
        }
    }

    /**
     * Resolves an ADR-0047 / ADR-0054 error envelope into a clean UI presentation state.
     */
    public static ResolvedErrorInfo resolveEnvelope(PosApiResponseEnvelope<?> envelope, String languageCode) {
        if (envelope == null) {
            return fallbackError(500, "ERR_INTERNAL_SERVER_ERROR", "Unknown error", languageCode, "req-unknown");
        }

        String lang = (languageCode != null && languageCode.equalsIgnoreCase("bn")) ? "bn" : "en";
        String code = envelope.getErrorCode() != null ? envelope.getErrorCode() : envelope.getCode();
        if (code == null || code.isBlank()) {
            code = mapHttpStatusToCode(envelope.getHttpStatus());
        }

        UiState uiState = mapCodeToUiState(code, envelope.getHttpStatus());
        String msg = envelope.getLocalizedMessage(lang);
        if (msg == null || msg.isBlank()) {
            msg = getDefaultLocalizedMessage(code, lang);
        }

        String reqId = envelope.getRequestId();
        if (reqId == null || reqId.isBlank()) {
            reqId = "req-unknown";
        }

        return new ResolvedErrorInfo(
                uiState,
                code,
                reqId,
                envelope.getHttpStatus(),
                envelope.isRetryable(),
                envelope.getRecoveryAction() != null ? envelope.getRecoveryAction() : "RETRY_AFTER_BACKOFF",
                msg,
                envelope.getFieldErrors()
        );
    }

    /**
     * Resolves raw HTTP error status and response body when JSON envelope parsing is partial or fallback.
     */
    public static ResolvedErrorInfo resolveHttpStatus(int statusCode, String rawBody, String languageCode, String requestId) {
        String lang = (languageCode != null && languageCode.equalsIgnoreCase("bn")) ? "bn" : "en";
        String code = mapHttpStatusToCode(statusCode);
        UiState uiState = mapCodeToUiState(code, statusCode);
        String msg = getDefaultLocalizedMessage(code, lang);
        boolean retryable = (statusCode == 503 || statusCode == 429);
        String reqId = (requestId != null && !requestId.isBlank()) ? requestId : "req-unknown";

        return new ResolvedErrorInfo(
                uiState,
                code,
                reqId,
                statusCode,
                retryable,
                retryable ? "RETRY_AFTER_BACKOFF" : "CORRECT_INPUT_FIELDS",
                msg,
                Collections.emptyMap()
        );
    }

    public static UiState mapCodeToUiState(String errorCode, int httpStatus) {
        if (errorCode == null) {
            return httpStatus >= 500 ? UiState.ERROR : UiState.ERROR;
        }

        return switch (errorCode) {
            case "ERR_UNAUTHORIZED", "ERR_AUTH_EXPIRED", "ERR_PERMISSION_DENIED", "ERR_CROSS_TENANT_VIOLATION",
                 "ERR_TENANT_INACTIVE", "ERR_SUBSCRIPTION_EXPIRED", "ERR_OFFLINE_LEASE_EXPIRED" ->
                    UiState.PERMISSION_DENIED;
            case "ERR_DATABASE_UNAVAILABLE", "ERR_SYNC_NETWORK_TIMEOUT", "ERR_DB_LOCK_TIMEOUT" ->
                    UiState.OFFLINE;
            default -> UiState.ERROR;
        };
    }

    public static String mapHttpStatusToCode(int httpStatus) {
        return switch (httpStatus) {
            case 400 -> "ERR_BAD_REQUEST";
            case 401 -> "ERR_UNAUTHORIZED";
            case 403 -> "ERR_PERMISSION_DENIED";
            case 404 -> "ERR_RESOURCE_NOT_FOUND";
            case 405 -> "ERR_METHOD_NOT_ALLOWED";
            case 409 -> "ERR_DATABASE_INTEGRITY";
            case 422 -> "ERR_VALIDATION_FAILED";
            case 429 -> "ERR_RATE_LIMIT_EXCEEDED";
            case 503 -> "ERR_DATABASE_UNAVAILABLE";
            default -> "ERR_INTERNAL_SERVER_ERROR";
        };
    }

    public static String getDefaultLocalizedMessage(String errorCode, String languageCode) {
        boolean isBn = languageCode != null && languageCode.equalsIgnoreCase("bn");

        if (errorCode == null) {
            return isBn ? "একটি অপ্রত্যাশিত ত্রুটি ঘটেছে।" : "An unexpected error occurred.";
        }

        return switch (errorCode) {
            case "ERR_AUTH_EXPIRED", "ERR_UNAUTHORIZED" ->
                    isBn ? "আপনার লগইন সেশনের মেয়াদ শেষ হয়েছে। পুনরায় লগইন করুন।"
                            : "Your authentication session has expired. Please log in again.";
            case "ERR_PERMISSION_DENIED" ->
                    isBn ? "এই কাজটি সম্পাদন করার অনুমতি আপনার নেই।"
                            : "You do not have permission to perform this action.";
            case "ERR_CROSS_TENANT_VIOLATION" ->
                    isBn ? "অন্য ফার্মেসির তথ্যে প্রবেশাধিকার কঠোরভাবে নিষিদ্ধ।"
                            : "Access to foreign pharmacy tenant resources is strictly forbidden.";
            case "ERR_SUBSCRIPTION_EXPIRED" ->
                    isBn ? "ফার্মেসির সাবস্ক্রিপশনের মেয়াদ শেষ হয়েছে। অনুগ্রহ করে আপনার প্ল্যান নবায়ন করুন।"
                            : "Pharmacy tenant subscription has expired. Please renew your plan.";
            case "ERR_VALIDATION_FAILED" ->
                    isBn ? "এক বা একাধিক তথ্যের মান সঠিক নয়।"
                            : "One or more input fields failed validation.";
            case "ERR_DATABASE_INTEGRITY" ->
                    isBn ? "একটি ডেটাবেজ ইন্টিগ্রিটি বা ডুপ্লিকেট রেকর্ড দ্বন্দ্ব তৈরি হয়েছে।"
                            : "A database integrity constraint or duplicate record conflict occurred.";
            case "ERR_DATABASE_UNAVAILABLE" ->
                    isBn ? "ডেটাবেজ সেবা সাময়িকভাবে অনুপলব্ধ বা লক করা আছে। কিছুক্ষণ পর পুনরায় চেষ্টা করুন।"
                            : "Database service is temporarily unavailable or locked. Safe to retry.";
            case "ERR_SYNC_NETWORK_TIMEOUT" ->
                    isBn ? "ক্লাউড সিঙ্কের সময় সংযোগ বিচ্ছিন্ন হয়েছে। স্বয়ংক্রিয়ভাবে পুনরায় চেষ্টা করা হচ্ছে।"
                            : "Network connection timed out during cloud sync. Retrying with exponential backoff.";
            case "ERR_BATCH_EXPIRED" ->
                    isBn ? "নির্বাচিত ওষুধের ব্যাচের মেয়াদ উত্তীর্ণ হয়েছে এবং এটি বিক্রি করা নিষিদ্ধ।"
                            : "The selected medicine batch has expired and cannot be dispensed.";
            case "ERR_INSUFFICIENT_STOCK" ->
                    isBn ? "অনুরোধকৃত পরিমাণ বিদ্যমান ব্যাচ স্টকের চেয়ে বেশি।"
                            : "Requested quantity exceeds available batch stock.";
            case "ERR_RESOURCE_NOT_FOUND" ->
                    isBn ? "অনুরোধকৃত রিসোর্সটি খুঁজে পাওয়া যায়নি।"
                            : "The requested resource was not found.";
            case "ERR_RATE_LIMIT_EXCEEDED" ->
                    isBn ? "অনুরোধের সীমা অতিক্রম করেছে। অনুগ্রহ করে কিছুক্ষণ পর পুনরায় চেষ্টা করুন।"
                            : "Request rate limit exceeded. Please retry shortly.";
            default ->
                    isBn ? "একটি অভ্যন্তরীণ সমস্যা দেখা দিয়েছে। অনুগ্রহ করে রিকোয়েস্ট আইডি সহ সাপোর্ট টিমের সাথে যোগাযোগ করুন।"
                            : "An internal server error occurred. Please contact support with the request ID.";
        };
    }

    private static ResolvedErrorInfo fallbackError(int status, String code, String message, String lang, String reqId) {
        boolean isBn = lang != null && lang.equalsIgnoreCase("bn");
        return new ResolvedErrorInfo(
                UiState.ERROR,
                code,
                reqId,
                status,
                false,
                "RETRY_AFTER_BACKOFF",
                isBn ? "একটি অপ্রত্যাশিত ত্রুটি ঘটেছে।" : message,
                Collections.emptyMap()
        );
    }
}
