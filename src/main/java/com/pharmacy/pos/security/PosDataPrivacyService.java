package com.pharmacy.pos.security;

import com.pharmacy.pos.contracts.PosApiResponseEnvelope.UiState;
import com.pharmacy.pos.models.PosPrivacyRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * JavaFX Desktop POS Data Privacy & Retention Operations Service.
 * Architecture Decision: ADR-0052 (Data Privacy Operations & DGDA Retention Schedule).
 */
public class PosDataPrivacyService {

    public static final Set<String> VALID_REQUEST_TYPES = Set.of(
            "DATA_EXPORT", "DATA_CORRECTION", "DATA_DELETION"
    );

    public static final Set<String> ALLOWED_ASSESSMENT_ROLES = Set.of(
            "PHARMACY_OWNER", "BRANCH_MANAGER", "AUDITOR", "SUPERADMIN"
    );

    /**
     * Validates DSR submission payload before network request.
     */
    public static boolean validateRequestInput(String subjectIdentifier, String requestType) {
        if (subjectIdentifier == null || subjectIdentifier.trim().length() < 3) return false;
        if (requestType == null || !VALID_REQUEST_TYPES.contains(requestType.toUpperCase())) return false;
        return true;
    }

    /**
     * Scoped Role-Based Access Control for assessing privacy requests.
     */
    public static boolean isAuthorizedForPrivacyOperations(String userRole) {
        if (userRole == null) return false;
        return ALLOWED_ASSESSMENT_ROLES.contains(userRole.toUpperCase());
    }

    /**
     * Evaluates statutory retention eligibility for deletion requests.
     * If clinical prescriptions exist within 730 days (2 years), direct physical deletion is blocked.
     */
    public static boolean hasStatutoryRetentionConflict(boolean hasPrescriptionRecords, int daysSincePrescription) {
        return hasPrescriptionRecords && daysSincePrescription < 730;
    }

    /**
     * Parses server response JSON map into PosPrivacyRequest DTO.
     */
    public static PosPrivacyRequest parsePrivacyRequest(Map<String, Object> map) {
        Objects.requireNonNull(map, "Privacy request map cannot be null");

        String requestId = (String) map.get("request_id");
        String tenantId = (String) map.get("tenant_id");
        String branchId = (String) map.get("branch_id");
        String subjectType = (String) map.getOrDefault("subject_type", "PATIENT");
        String subjectIdentifier = (String) map.get("subject_identifier");
        String requestType = (String) map.get("request_type");
        String status = (String) map.getOrDefault("status", "PENDING_ASSESSMENT");
        String reason = (String) map.getOrDefault("reason", "");
        boolean conflict = Boolean.TRUE.equals(map.get("retention_conflict_detected"));
        int horizonDays = map.get("retention_horizon_days") instanceof Number n ? n.intValue() : 730;
        String exportUrl = (String) map.get("export_artifact_url");
        String assessedByEmail = (String) map.get("assessed_by_email");
        String assessedAt = (String) map.get("assessed_at");
        String createdAt = (String) map.get("created_at");

        return new PosPrivacyRequest(
                requestId,
                tenantId,
                branchId,
                subjectType,
                subjectIdentifier,
                requestType,
                status,
                reason,
                conflict,
                horizonDays,
                exportUrl,
                assessedByEmail,
                assessedAt,
                createdAt
        );
    }

    /**
     * Resolves localized bilingual error message conforming to ADR-0014 & ADR-0052.
     */
    public static Map<String, String> getBilingualErrorMessage(String errorCode) {
        Map<String, String> messages = new HashMap<>();
        if ("ERR_PRIVACY_RETENTION_CONFLICT".equalsIgnoreCase(errorCode)) {
            messages.put("en", "Data deletion cannot be executed: Active DGDA statutory retention is in effect. PII anonymization is required.");
            messages.put("bn", "তথ্য মুছে ফেলার অনুরোধ কার্যকর করা যাবে না: ডিজিডিএ রিটেনশন মেয়াদ বলবৎ রয়েছে। শুধুমাত্র পরিচয়হীনকরণ সম্ভব।");
        } else if ("ERR_PERMISSION_DENIED".equalsIgnoreCase(errorCode)) {
            messages.put("en", "Access denied: Data privacy operations require Owner or Manager authority.");
            messages.put("bn", "প্রবেশাধিকার প্রত্যাখ্যাত: ডেটা প্রাইভেসি কার্যক্রম পরিচালনার জন্য মালিক বা ম্যানেজারের অনুমতি প্রয়োজন।");
        } else if ("ERR_CROSS_TENANT_VIOLATION".equalsIgnoreCase(errorCode)) {
            messages.put("en", "Cross-tenant privacy request inspection is strictly forbidden.");
            messages.put("bn", "অন্য ফার্মেসির প্রাইভেসি আবেদন পরিদর্শন কঠোরভাবে নিষিদ্ধ।");
        } else {
            messages.put("en", "Data privacy operation failed. Please verify connection.");
            messages.put("bn", "ডেটা প্রাইভেসি পরিচালনায় ত্রুটি দেখা দিয়েছে। সংযোগ পরীক্ষা করুন।");
        }
        return Collections.unmodifiableMap(messages);
    }

    /**
     * Maps error code to UI State for JavaFX controllers.
     */
    public static UiState resolveUiState(String errorCode) {
        if ("ERR_PERMISSION_DENIED".equalsIgnoreCase(errorCode)
                || "ERR_CROSS_TENANT_VIOLATION".equalsIgnoreCase(errorCode)) {
            return UiState.PERMISSION_DENIED;
        }
        return UiState.ERROR;
    }
}
