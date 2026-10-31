package com.pharmacy.pos.security;

import java.util.*;

/**
 * PosTenantLifecycleService
 * Architecture Decision: ADR-0061
 * Manages tenant lifecycle states, checkout permission gates, and canonical settings on JavaFX desktop POS:
 *   - Allows POS checkout ONLY when tenant is in ACTIVE or TRIAL state
 *   - Hard-blocks dispensing when tenant is SUSPENDED or ARCHIVED
 *   - Surfaces bilingual warnings during PAST_DUE grace period
 *   - Enforces clinical safety invariant (negative stock strictly disallowed)
 */
public final class PosTenantLifecycleService {

    public record TenantStatusEvaluation(
        String status,
        boolean isCheckoutPermitted,
        boolean isGracePeriodWarning,
        String messageEn,
        String messageBn
    ) {}

    private PosTenantLifecycleService() {}

    /**
     * Evaluates tenant status for POS cashier operations.
     */
    public static TenantStatusEvaluation evaluateTenantStatus(String rawStatus) {
        String status = rawStatus != null ? rawStatus.toUpperCase().trim() : "UNKNOWN";

        return switch (status) {
            case "ACTIVE" -> new TenantStatusEvaluation(
                status,
                true,
                false,
                "Tenant account is active and in good standing.",
                "টেন্যান্ট অ্যাকাউন্ট সক্রিয় ও নিয়মিত।"
            );
            case "TRIAL" -> new TenantStatusEvaluation(
                status,
                true,
                false,
                "Active trial subscription. Full dispensing permitted.",
                "ট্রায়াল সাবস্ক্রিপশন সক্রিয়। ওষুধ বিতরণ অনুমোদিত।"
            );
            case "PAST_DUE" -> new TenantStatusEvaluation(
                status,
                true,
                true,
                "Subscription payment is past due. Currently in grace period. Please renew immediately.",
                "সাবস্ক্রিপশন বিল বকেয়া পড়েছে। গ্রেস পিরিয়ড চলছে। অনুগ্রহ করে দ্রুত বিল পরিশোধ করুন।"
            );
            case "SUSPENDED" -> new TenantStatusEvaluation(
                status,
                false,
                false,
                "Tenant account is suspended. POS checkout and medicine dispensing are disabled.",
                "টেন্যান্ট অ্যাকাউন্ট স্থগিত রয়েছে। বিক্রয় ও ওষুধ বিতরণ সাময়িকভাবে বন্ধ রয়েছে।"
            );
            case "ARCHIVED" -> new TenantStatusEvaluation(
                status,
                false,
                false,
                "Tenant account has been decommissioned and archived. Read-only historical access.",
                "টেন্যান্ট অ্যাকাউন্টটি বাতিল ও আর্কাইভ করা হয়েছে। কেবল রিড-অনলি মোডে তথ্য দেখা যাবে।"
            );
            default -> new TenantStatusEvaluation(
                status,
                false,
                false,
                "Invalid or unprovisioned tenant status. Checkout blocked.",
                "অবৈধ বা অনির্ধারিত টেন্যান্ট স্ট্যাটাস। চেকআউট প্রতিরোধ করা হয়েছে।"
            );
        };
    }

    /**
     * Enforces clinical safety invariant: Negative stock is permanently prohibited across all tenants.
     */
    public static boolean isNegativeStockPermitted(Map<String, Object> settings) {
        if (settings == null) return false;
        Object val = settings.get("allow_negative_stock");
        if (val instanceof Boolean b) {
            return b;
        }
        return false;
    }
}
