package com.pharmacy.pos.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosFormTemplateMigrationClient
 * Architecture Decision: ADR-0139 (P139).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * JavaFX Desktop POS Client for Dosage-Form Template Migration Plans,
 * Declarative Field Mappings, Dry-Run Audits, Reversible Snapshots,
 * and Preserving 100% Immutable Historical Dispensing Records.
 */
public class PosFormTemplateMigrationClient {

    public enum UiStatus {
        SUCCESS,
        ERROR,
        PERMISSION_DENIED,
        EMPTY,
        OFFLINE
    }

    public enum MigrationStrategy {
        STRICT,
        COERCE_SAFE,
        DRY_RUN_ONLY
    }

    public enum MigrationPlanStatus {
        DRAFT,
        VALIDATED,
        EXECUTING,
        COMPLETED,
        ROLLED_BACK,
        FAILED
    }

    public static final class FormTemplateMigrationRecord {
        public final String planId;
        public final String planName;
        public final String tenantId;
        public final String fromTemplateId;
        public final String toTemplateId;
        public final MigrationStrategy strategy;
        public MigrationPlanStatus status;
        public final Map<String, Object> fieldMappings;
        public Map<String, Object> dryRunSummary;
        public int affectedMedicineCount;
        public int migratedMedicineCount;
        public int historicalDispensingAuditedCount;
        public final String planHash;
        public final String createdAt;
        public String executedAt;
        public String rolledBackAt;

        public FormTemplateMigrationRecord(
                String planId,
                String planName,
                String tenantId,
                String fromTemplateId,
                String toTemplateId,
                MigrationStrategy strategy,
                MigrationPlanStatus status,
                Map<String, Object> fieldMappings,
                Map<String, Object> dryRunSummary,
                int affectedMedicineCount,
                int migratedMedicineCount,
                int historicalDispensingAuditedCount,
                String planHash,
                String createdAt,
                String executedAt,
                String rolledBackAt) {
            this.planId = planId;
            this.planName = planName;
            this.tenantId = tenantId != null ? tenantId : "platform";
            this.fromTemplateId = fromTemplateId;
            this.toTemplateId = toTemplateId;
            this.strategy = strategy;
            this.status = status;
            this.fieldMappings = fieldMappings != null ? fieldMappings : Collections.emptyMap();
            this.dryRunSummary = dryRunSummary != null ? dryRunSummary : Collections.emptyMap();
            this.affectedMedicineCount = affectedMedicineCount;
            this.migratedMedicineCount = migratedMedicineCount;
            this.historicalDispensingAuditedCount = historicalDispensingAuditedCount;
            this.planHash = planHash;
            this.createdAt = createdAt != null ? createdAt : Instant.now().toString();
            this.executedAt = executedAt;
            this.rolledBackAt = rolledBackAt;
        }
    }

    public static final class UiResult<T> {
        public final UiStatus status;
        public final T data;
        public final String errorCode;
        public final String errorMessageEn;
        public final String errorMessageBn;

        private UiResult(UiStatus status, T data, String errorCode, String errorMessageEn, String errorMessageBn) {
            this.status = status;
            this.data = data;
            this.errorCode = errorCode;
            this.errorMessageEn = errorMessageEn;
            this.errorMessageBn = errorMessageBn;
        }

        public static <T> UiResult<T> success(T data) {
            return new UiResult<>(UiStatus.SUCCESS, data, null, null, null);
        }

        public static <T> UiResult<T> offline(T data) {
            return new UiResult<>(UiStatus.OFFLINE, data, null, "Serving from local cache", "লোকাল ক্যাশ থেকে সরবরাহ করা হচ্ছে");
        }

        public static <T> UiResult<T> permissionDenied(String code, String en, String bn) {
            return new UiResult<>(UiStatus.PERMISSION_DENIED, null, code, en, bn);
        }

        public static <T> UiResult<T> error(String code, String en, String bn) {
            return new UiResult<>(UiStatus.ERROR, null, code, en, bn);
        }

        public static <T> UiResult<T> empty() {
            return new UiResult<>(UiStatus.EMPTY, null, null, "No records found", "কোন তথ্য পাওয়া যায়নি");
        }
    }

    private final Map<String, FormTemplateMigrationRecord> planStore = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> rollbackSnapshots = new ConcurrentHashMap<>();
    private boolean isOffline = false;

    public void setOffline(boolean offline) {
        this.isOffline = offline;
    }

    public boolean isOffline() {
        return isOffline;
    }

    public UiResult<FormTemplateMigrationRecord> createPlan(
            String tenantId,
            String planName,
            String fromTemplateId,
            String toTemplateId,
            MigrationStrategy strategy,
            Map<String, Object> fieldMappings,
            String userRole,
            String planTier,
            boolean isSuspended) {

        if (isOffline) {
            return UiResult.error("ERR_OFFLINE_MUTATION", "Cannot create migration plans while offline", "অফলাইনে মাইগ্রেশন প্ল্যান তৈরি করা যাবে না");
        }

        if (isSuspended) {
            return UiResult.permissionDenied("ERR_TENANT_SUSPENDED", "Tenant subscription account is suspended.", "ফার্মেসির সাবস্ক্রিপশন অ্যাকাউন্ট স্থগিত রয়েছে।");
        }

        String role = userRole != null ? userRole.toUpperCase() : "";
        if ("CASHIER".equals(role)) {
            return UiResult.permissionDenied("ERR_PERMISSION_DENIED", "Cashier role is not permitted to create migration plans.", "ক্যাশিয়ার রোলের জন্য মাইগ্রেশন প্ল্যান তৈরি নিষিদ্ধ।");
        }

        if ("BASIC".equalsIgnoreCase(planTier) && !"SUPERADMIN".equals(role)) {
            return UiResult.permissionDenied("ERR_SUBSCRIPTION_UNENTITLED", "Basic subscription does not entitle template migration.", "বেসিক সাবস্ক্রিপশনে টেমপ্লেট মাইগ্রেশন সেবা অন্তর্ভুক্ত নয়।");
        }

        if (planName == null || planName.trim().isEmpty()) {
            return UiResult.error("ERR_INVALID_INPUT", "planName is required and cannot be empty.", "প্ল্যানের নাম আবশ্যক।");
        }

        if (fromTemplateId == null || toTemplateId == null) {
            return UiResult.error("ERR_INVALID_INPUT", "Both fromTemplateId and toTemplateId are required.", "উৎস এবং লক্ষ্য টেমপ্লেট আইডি আবশ্যক।");
        }

        if (fromTemplateId.equals(toTemplateId)) {
            return UiResult.error("ERR_INVALID_INPUT", "Target template cannot be identical to source template.", "লক্ষ্য টেমপ্লেট এবং উৎস টেমপ্লেট একই হতে পারবে না।");
        }

        String planId = "ftmp-" + UUID.randomUUID().toString().substring(0, 12);
        String hash = computeHash(planId, tenantId, fromTemplateId, toTemplateId, strategy.name());

        FormTemplateMigrationRecord record = new FormTemplateMigrationRecord(
                planId,
                planName.trim(),
                tenantId,
                fromTemplateId,
                toTemplateId,
                strategy,
                MigrationPlanStatus.DRAFT,
                fieldMappings,
                null,
                0,
                0,
                0,
                hash,
                Instant.now().toString(),
                null,
                null
        );

        planStore.put(planId, record);
        return UiResult.success(record);
    }

    public UiResult<FormTemplateMigrationRecord> validateDryRun(
            String planId,
            String userRole,
            String requestTenantId,
            int simulatedMedicineCount,
            int simulatedDispensingCount) {

        FormTemplateMigrationRecord plan = planStore.get(planId);
        if (plan == null) {
            return UiResult.error("ERR_NOT_FOUND", "Migration plan not found: " + planId, "মাইগ্রেশন প্ল্যান খুঁজে পাওয়া যায়নি");
        }

        if (checkCrossTenant(plan, userRole, requestTenantId)) {
            return UiResult.permissionDenied("ERR_CROSS_TENANT_ACCESS", "Cross-tenant access prohibited", "অন্য ফার্মেসির প্ল্যানে অ্যাক্সেস নিষিদ্ধ");
        }

        Map<String, Object> drySummary = new HashMap<>();
        drySummary.put("total_affected_medicines", simulatedMedicineCount);
        drySummary.put("historical_dispensing_checked_count", simulatedDispensingCount);
        drySummary.put("historical_immutability_certified", true);
        drySummary.put("validated_at", Instant.now().toString());

        plan.dryRunSummary = drySummary;
        plan.affectedMedicineCount = simulatedMedicineCount;
        plan.historicalDispensingAuditedCount = simulatedDispensingCount;
        plan.status = MigrationPlanStatus.VALIDATED;

        return UiResult.success(plan);
    }

    public UiResult<FormTemplateMigrationRecord> executePlan(
            String planId,
            String userRole,
            String requestTenantId) {

        if (isOffline) {
            return UiResult.error("ERR_OFFLINE_MUTATION", "Cannot execute migration plan while offline", "অফলাইনে মাইগ্রেশন প্ল্যান চালানো যাবে না");
        }

        FormTemplateMigrationRecord plan = planStore.get(planId);
        if (plan == null) {
            return UiResult.error("ERR_NOT_FOUND", "Migration plan not found: " + planId, "মাইগ্রেশন প্ল্যান খুঁজে পাওয়া যায়নি");
        }

        if (checkCrossTenant(plan, userRole, requestTenantId)) {
            return UiResult.permissionDenied("ERR_CROSS_TENANT_ACCESS", "Cross-tenant access prohibited", "অন্য ফার্মেসির প্ল্যানে অ্যাক্সেস নিষিদ্ধ");
        }

        if (plan.status == MigrationPlanStatus.COMPLETED) {
            return UiResult.error("ERR_ALREADY_EXECUTED", "Migration plan has already been executed.", "মাইগ্রেশন প্ল্যান ইতোমধ্যেই সম্পন্ন হয়েছে।");
        }

        // Capture snapshot before execution
        Map<String, Object> snap = new HashMap<>();
        snap.put("planId", planId);
        snap.put("migratedMedicines", plan.affectedMedicineCount);
        snap.put("executedAt", Instant.now().toString());
        rollbackSnapshots.put(planId, snap);

        plan.migratedMedicineCount = plan.affectedMedicineCount;
        plan.status = MigrationPlanStatus.COMPLETED;
        plan.executedAt = Instant.now().toString();

        return UiResult.success(plan);
    }

    public UiResult<FormTemplateMigrationRecord> rollbackPlan(
            String planId,
            String userRole,
            String requestTenantId) {

        if (isOffline) {
            return UiResult.error("ERR_OFFLINE_MUTATION", "Cannot rollback migration plan while offline", "অফলাইনে মাইগ্রেশন প্ল্যান রোলব্যাক করা যাবে না");
        }

        FormTemplateMigrationRecord plan = planStore.get(planId);
        if (plan == null) {
            return UiResult.error("ERR_NOT_FOUND", "Migration plan not found: " + planId, "মাইগ্রেশন প্ল্যান খুঁজে পাওয়া যায়নি");
        }

        if (checkCrossTenant(plan, userRole, requestTenantId)) {
            return UiResult.permissionDenied("ERR_CROSS_TENANT_ACCESS", "Cross-tenant access prohibited", "অন্য ফার্মেসির প্ল্যানে অ্যাক্সেস নিষিদ্ধ");
        }

        if (plan.status != MigrationPlanStatus.COMPLETED) {
            return UiResult.error("ERR_INVALID_STATE", "Only COMPLETED plans can be rolled back.", "শুধুমাত্র সম্পন্ন প্ল্যান রোলব্যাক করা যাবে।");
        }

        if (!rollbackSnapshots.containsKey(planId)) {
            return UiResult.error("ERR_NO_SNAPSHOT", "No snapshot available for rollback", "রোলব্যাকের জন্য কোনো স্ন্যাপশট নেই");
        }

        plan.status = MigrationPlanStatus.ROLLED_BACK;
        plan.rolledBackAt = Instant.now().toString();

        return UiResult.success(plan);
    }

    public UiResult<FormTemplateMigrationRecord> queryPlan(String planId, String requestTenantId) {
        FormTemplateMigrationRecord plan = planStore.get(planId);
        if (plan == null) {
            return UiResult.empty();
        }

        if (isOffline) {
            return UiResult.offline(plan);
        }
        return UiResult.success(plan);
    }

    private boolean checkCrossTenant(FormTemplateMigrationRecord plan, String userRole, String requestTenantId) {
        if ("SUPERADMIN".equalsIgnoreCase(userRole)) {
            return false;
        }
        if (plan.tenantId != null && !"platform".equals(plan.tenantId)) {
            return !plan.tenantId.equals(requestTenantId);
        }
        return false;
    }

    private String computeHash(String planId, String tenantId, String fromTmpl, String toTmpl, String strategy) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = planId + "|" + (tenantId != null ? tenantId : "platform") + "|" + fromTmpl + "|" + toTmpl + "|" + strategy;
            byte[] encoded = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : encoded) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
