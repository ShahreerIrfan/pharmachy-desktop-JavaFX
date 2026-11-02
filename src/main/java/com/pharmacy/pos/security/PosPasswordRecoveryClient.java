package com.pharmacy.pos.security;

import java.util.*;
import java.util.regex.Pattern;

/**
 * PosPasswordRecoveryClient
 * Architecture Decision: ADR-0114 (P114).
 * Phase 06: Milestone M03 - Offline-First Desktop POS & Owner Visibility.
 *
 * Client-side integration for accessible password setup, reset, token verification,
 * and password complexity feedback for JavaFX Desktop POS.
 */
public class PosPasswordRecoveryClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public static final class PasswordStrengthResult {
        public final boolean isValid;
        public final int score; // 0 to 5
        public final List<String> missingRequirements;

        public PasswordStrengthResult(boolean isValid, int score, List<String> missingRequirements) {
            this.isValid = isValid;
            this.score = score;
            this.missingRequirements = missingRequirements != null ? List.copyOf(missingRequirements) : List.of();
        }
    }

    public static final class RecoveryResponse {
        public final UiStatus uiStatus;
        public final String status;
        public final boolean tokenValid;
        public final Integer expiresInSeconds;
        public final String userEmail;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public RecoveryResponse(
                UiStatus uiStatus,
                String status,
                boolean tokenValid,
                Integer expiresInSeconds,
                String userEmail,
                String errorCode,
                String messageEn,
                String messageBn) {
            this.uiStatus = uiStatus;
            this.status = status;
            this.tokenValid = tokenValid;
            this.expiresInSeconds = expiresInSeconds;
            this.userEmail = userEmail;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static RecoveryResponse dispatched(String msgEn, String msgBn) {
            return new RecoveryResponse(UiStatus.SUCCESS, "DISPATCHED", false, null, null, null, msgEn, msgBn);
        }

        public static RecoveryResponse tokenValid(String email, int expiresSec, String msgEn, String msgBn) {
            return new RecoveryResponse(UiStatus.SUCCESS, "VALID_TOKEN", true, expiresSec, email, null, msgEn, msgBn);
        }

        public static RecoveryResponse success(String msgEn, String msgBn) {
            return new RecoveryResponse(UiStatus.SUCCESS, "SUCCESS", false, null, null, null, msgEn, msgBn);
        }

        public static RecoveryResponse error(UiStatus status, String code, String msgEn, String msgBn) {
            return new RecoveryResponse(status, "ERROR", false, null, null, code, msgEn, msgBn);
        }
    }

    private static final Pattern UPPERCASE_PATTERN = Pattern.compile("[A-Z]");
    private static final Pattern LOWERCASE_PATTERN = Pattern.compile("[a-z]");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d");
    private static final Pattern SPECIAL_PATTERN = Pattern.compile("[\\W_]");

    public PasswordStrengthResult evaluatePasswordStrength(String password) {
        if (password == null) {
            return new PasswordStrengthResult(false, 0, List.of("Password is required"));
        }

        List<String> missing = new ArrayList<>();
        int score = 0;

        if (password.length() >= 8) {
            score++;
        } else {
            missing.add("Must be at least 8 characters");
        }

        if (UPPERCASE_PATTERN.matcher(password).find()) {
            score++;
        } else {
            missing.add("Must contain at least 1 uppercase letter");
        }

        if (LOWERCASE_PATTERN.matcher(password).find()) {
            score++;
        } else {
            missing.add("Must contain at least 1 lowercase letter");
        }

        if (DIGIT_PATTERN.matcher(password).find()) {
            score++;
        } else {
            missing.add("Must contain at least 1 number");
        }

        if (SPECIAL_PATTERN.matcher(password).find()) {
            score++;
        } else {
            missing.add("Must contain at least 1 special character");
        }

        boolean valid = score == 5;
        return new PasswordStrengthResult(valid, score, missing);
    }

    public RecoveryResponse validateInputForResetRequest(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return RecoveryResponse.error(
                    UiStatus.ERROR,
                    "INVALID_PAYLOAD",
                    "Identifier (email or phone) is required.",
                    "ইমেইল বা ফোন নম্বর আবশ্যক।"
            );
        }
        return RecoveryResponse.dispatched(
                "If the account exists, password reset instructions have been dispatched.",
                "অ্যাকাউন্টটি বিদ্যমান থাকলে পাসওয়ার্ড রিসেট নির্দেশাবলী পাঠানো হয়েছে।"
        );
    }

    public RecoveryResponse validateConfirmation(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            return RecoveryResponse.error(
                    UiStatus.ERROR,
                    "INVALID_RESET_TOKEN",
                    "Password reset token is missing.",
                    "রিসেট টোকেন অনুপস্থিত।"
            );
        }

        PasswordStrengthResult strength = evaluatePasswordStrength(newPassword);
        if (!strength.isValid) {
            return RecoveryResponse.error(
                    UiStatus.ERROR,
                    "ERR_WEAK_PASSWORD",
                    String.join(", ", strength.missingRequirements),
                    "পাসওয়ার্ড নীতি পূরণ করেনি।"
            );
        }

        return RecoveryResponse.success(
                "Password has been reset successfully. Please log in with your new credentials.",
                "পাসওয়ার্ড সফলভাবে পরিবর্তিত হয়েছে।"
        );
    }
}

