package com.pharmacy.pos.security;

import java.time.Instant;
import java.util.Objects;

/**
 * PosAuthenticationClient
 * Architecture Decision: ADR-0043
 * Client-side authentication session and JWT token management for JavaFX POS.
 */
public class PosAuthenticationClient {

    public static final class AuthTokens {
        public final String accessToken;
        public final String refreshToken;
        public final String tokenType;
        public final long expiresInSeconds;
        public final Instant issuedAt;
        public final PosUserIdentityService.PosUserSession user;

        public AuthTokens(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresInSeconds,
            PosUserIdentityService.PosUserSession user
        ) {
            this.accessToken = Objects.requireNonNull(accessToken, "accessToken required");
            this.refreshToken = Objects.requireNonNull(refreshToken, "refreshToken required");
            this.tokenType = tokenType != null ? tokenType : "Bearer";
            this.expiresInSeconds = expiresInSeconds;
            this.issuedAt = Instant.now();
            this.user = Objects.requireNonNull(user, "user required");
        }

        public boolean isAccessTokenExpired() {
            return Instant.now().isAfter(issuedAt.plusSeconds(expiresInSeconds - 30));
        }
    }

    public static final class AuthError {
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public AuthError(String errorCode, String messageEn, String messageBn) {
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public boolean isClientSafe() {
            return errorCode != null && !errorCode.isEmpty() && messageEn != null && messageBn != null;
        }
    }

    /**
     * Parses a mock/real auth error payload into standard client-safe error.
     */
    public static AuthError parseError(String errorCode, String messageEn, String messageBn) {
        return new AuthError(
            errorCode != null ? errorCode : "AUTH_FAILED",
            messageEn != null ? messageEn : "Authentication failed.",
            messageBn != null ? messageBn : "প্রবেশাধিকার ব্যর্থ হয়েছে।"
        );
    }
}

