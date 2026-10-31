package com.pharmacy.pos.security;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JavaFX Desktop POS Client Multi-Factor Authentication Service.
 * Architecture Decision: ADR-0044 (MFA for Privileged Users).
 */
public class PosMfaService {

    private static final Set<String> PRIVILEGED_ROLES = Set.of(
            "SUPERADMIN",
            "PLATFORM_OPERATOR",
            "PHARMACY_OWNER",
            "BRANCH_MANAGER",
            "FINANCE_OFFICER"
    );

    private static final Pattern TOTP_PATTERN = Pattern.compile("^\\d{6}$");
    private static final Pattern RECOVERY_CODE_PATTERN = Pattern.compile("^[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}$");

    public record MfaChallenge(
            boolean mfaRequired,
            String challengeToken,
            int expiresIn,
            String method,
            String userId,
            String email,
            String role,
            int remainingAttempts
    ) {
        public MfaChallenge {
            Objects.requireNonNull(challengeToken, "challengeToken cannot be null");
            Objects.requireNonNull(userId, "userId cannot be null");
            Objects.requireNonNull(email, "email cannot be null");
            Objects.requireNonNull(role, "role cannot be null");
        }

        public boolean isExpired(Instant createdInstant) {
            return Instant.now().isAfter(createdInstant.plusSeconds(expiresIn));
        }
    }

    public record MfaStatus(
            boolean mfaEnabled,
            boolean isMandatory,
            boolean isConfirmed,
            int remainingRecoveryCodes,
            String enrolledAt
    ) {}

    public static boolean isPrivilegedRole(String role) {
        if (role == null) {
            return false;
        }
        return PRIVILEGED_ROLES.contains(role.trim().toUpperCase());
    }

    public static boolean validateTotpFormat(String code) {
        if (code == null) {
            return false;
        }
        return TOTP_PATTERN.matcher(code.trim()).matches();
    }

    public static boolean validateRecoveryCodeFormat(String code) {
        if (code == null) {
            return false;
        }
        return RECOVERY_CODE_PATTERN.matcher(code.trim()).matches();
    }

    public static boolean isLockedOut(int failedAttempts) {
        return failedAttempts >= 5;
    }

    public static MfaChallenge parseChallenge(Map<String, Object> payload) {
        Objects.requireNonNull(payload, "Payload cannot be null");
        boolean required = Boolean.TRUE.equals(payload.get("mfa_required"));
        String challengeToken = (String) payload.get("challenge_token");
        Number expiresInNum = (Number) payload.getOrDefault("expires_in", 300);
        String method = (String) payload.getOrDefault("method", "TOTP");
        String userId = (String) payload.get("user_id");
        String email = (String) payload.get("email");
        String role = (String) payload.get("role");
        Number remainingNum = (Number) payload.getOrDefault("remaining_attempts", 5);

        return new MfaChallenge(
                required,
                challengeToken,
                expiresInNum.intValue(),
                method,
                userId,
                email,
                role,
                remainingNum.intValue()
        );
    }

    public static Map<String, String> getBilingualErrorMessage(String errorCode) {
        Map<String, String> msg = new HashMap<>();
        if ("MFA_LOCKED_OUT".equalsIgnoreCase(errorCode)) {
            msg.put("en", "Too many failed MFA attempts. Account MFA verification locked for 15 minutes.");
            msg.put("bn", "অতিরিক্ত ভুল MFA চেষ্টার কারণে অ্যাকাউন্ট ১৫ মিনিটের জন্য সাময়িকভাবে লক করা হয়েছে।");
        } else if ("INVALID_MFA_CODE".equalsIgnoreCase(errorCode)) {
            msg.put("en", "Invalid MFA verification code.");
            msg.put("bn", "ভুল MFA ভেরিফিকেশন কোড।");
        } else if ("MFA_MANDATORY_FOR_PRIVILEGED_ROLE".equalsIgnoreCase(errorCode)) {
            msg.put("en", "MFA is strictly mandatory for privileged roles and cannot be disabled.");
            msg.put("bn", "প্রিভিলেজড ব্যবহারকারীদের জন্য MFA বাধ্যতামূলক এবং এটি নিষ্ক্রিয় করা যাবে না।");
        } else if ("CROSS_TENANT_MFA_FORBIDDEN".equalsIgnoreCase(errorCode)) {
            msg.put("en", "Cross-tenant MFA challenge exchange is strictly forbidden.");
            msg.put("bn", "ক্রস-টেন্যান্ট MFA ভেরিফিকেশন কঠোরভাবে নিষিদ্ধ।");
        } else {
            msg.put("en", "Authentication failure. Please contact support.");
            msg.put("bn", "অথেনটিকেশন ব্যর্থ হয়েছে। অনুগ্রহ করে সাপোর্টে যোগাযোগ করুন।");
        }
        return Collections.unmodifiableMap(msg);
    }
}

