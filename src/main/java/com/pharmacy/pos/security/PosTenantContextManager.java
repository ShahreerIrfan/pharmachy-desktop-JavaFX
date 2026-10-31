package com.pharmacy.pos.security;

import com.pharmacy.pos.contracts.PosApiResponseEnvelope.UiState;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * JavaFX Desktop POS Tenant Context & Signed Claims Security Manager.
 * Architecture Decision: ADR-0049 (Trusted Tenant Context Resolution Middleware).
 * Resolves tenant and active branch from trusted signed claims; never trusts client-supplied tenant IDs alone.
 */
public class PosTenantContextManager {

    public record PosTenantContext(
            String tenantId,
            String tenantName,
            String subdomain,
            String planTier,
            String branchId,
            String branchName,
            String userId,
            String userRole,
            String resolutionSource,
            boolean isActive
    ) {
        public PosTenantContext {
            Objects.requireNonNull(tenantId, "tenantId cannot be null");
            Objects.requireNonNull(tenantName, "tenantName cannot be null");
            Objects.requireNonNull(subdomain, "subdomain cannot be null");
            Objects.requireNonNull(planTier, "planTier cannot be null");
            Objects.requireNonNull(resolutionSource, "resolutionSource cannot be null");
        }
    }

    private static volatile PosTenantContext activeContext = null;

    public static synchronized void setActiveContext(PosTenantContext context) {
        activeContext = context;
    }

    public static synchronized PosTenantContext getActiveContext() {
        return activeContext;
    }

    public static synchronized void clear() {
        activeContext = null;
    }

    /**
     * Parses server response or token claims map into PosTenantContext.
     */
    public static PosTenantContext parseContext(Map<String, Object> map) {
        Objects.requireNonNull(map, "Context payload cannot be null");

        String tenantId = (String) map.get("tenant_id");
        String tenantName = (String) map.getOrDefault("tenant_name", "Unknown Pharmacy");
        String subdomain = (String) map.getOrDefault("subdomain", "unknown");
        String planTier = (String) map.getOrDefault("plan_tier", "STANDARD");
        String branchId = (String) map.get("branch_id");
        String branchName = (String) map.get("branch_name");
        String userId = (String) map.get("user_id");
        String userRole = (String) map.getOrDefault("user_role", "CASHIER");
        String resolutionSource = (String) map.getOrDefault("resolution_source", "JWT_SIGNED_CLAIMS");
        boolean isActive = Boolean.TRUE.equals(map.getOrDefault("is_active", true));

        return new PosTenantContext(
                tenantId,
                tenantName,
                subdomain,
                planTier,
                branchId,
                branchName,
                userId,
                userRole,
                resolutionSource,
                isActive
        );
    }

    /**
     * Verifies that client action targets the active tenant, preventing IDOR / cross-tenant tampering.
     */
    public static boolean validateTenantIntegrity(PosTenantContext context, String requestedTenantId) {
        if (context == null || requestedTenantId == null) {
            return false;
        }
        return context.tenantId().trim().equalsIgnoreCase(requestedTenantId.trim());
    }

    /**
     * Verifies that branch action belongs strictly to active tenant.
     */
    public static boolean validateBranchIntegrity(PosTenantContext context, String requestedBranchId) {
        if (context == null || requestedBranchId == null) {
            return false;
        }
        if (context.branchId() == null) {
            return true;
        }
        return context.branchId().trim().equalsIgnoreCase(requestedBranchId.trim());
    }

    /**
     * Resolves localized bilingual error message conforming to ADR-0049.
     */
    public static Map<String, String> getBilingualErrorMessage(String errorCode) {
        Map<String, String> messages = new HashMap<>();
        if ("ERR_CROSS_TENANT_VIOLATION".equalsIgnoreCase(errorCode)) {
            messages.put("en", "Cross-tenant access violation: Access to another pharmacy's data is strictly forbidden.");
            messages.put("bn", "অন্য ফার্মেসির তথ্যে প্রবেশাধিকার কঠোরভাবে নিষিদ্ধ। ক্লায়েন্ট প্রদত্ত টেন্যান্ট আইডি অনুমোদিত তথ্যের সাথে মেলেনি।");
        } else if ("ERR_TENANT_INACTIVE".equalsIgnoreCase(errorCode)) {
            messages.put("en", "This pharmacy tenant account is currently inactive or suspended. Please contact platform support.");
            messages.put("bn", "এই ফার্মেসি অ্যাকাউন্টটি বর্তমানে নিষ্ক্রিয় বা স্থগিত রয়েছে। অনুগ্রহ করে প্ল্যাটফর্ম সাপোর্টের সাথে যোগাযোগ করুন।");
        } else if ("ERR_BRANCH_NOT_FOUND".equalsIgnoreCase(errorCode) || "ERR_BRANCH_INACTIVE".equalsIgnoreCase(errorCode)) {
            messages.put("en", "Dispensary branch is inactive or does not belong to your active pharmacy tenant.");
            messages.put("bn", "শাখাটি নিষ্ক্রিয় অথবা এটি আপনার অনুমোদিত ফার্মেসির অন্তর্ভুক্ত নয়।");
        } else {
            messages.put("en", "Tenant authorization failed. Please contact your pharmacy administrator.");
            messages.put("bn", "টেন্যান্ট অনুমোদনে ব্যর্থ হয়েছে। অনুগ্রহ করে আপনার ফার্মেসি পরিচালকের সাথে যোগাযোগ করুন।");
        }
        return Collections.unmodifiableMap(messages);
    }

    /**
     * Resolves the UI State for JavaFX controllers based on tenant context error codes.
     */
    public static UiState resolveUiState(String errorCode) {
        if ("ERR_CROSS_TENANT_VIOLATION".equalsIgnoreCase(errorCode)
                || "ERR_TENANT_INACTIVE".equalsIgnoreCase(errorCode)
                || "ERR_BRANCH_NOT_FOUND".equalsIgnoreCase(errorCode)) {
            return UiState.PERMISSION_DENIED;
        }
        return UiState.ERROR;
    }
}
