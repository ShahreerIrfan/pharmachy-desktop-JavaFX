package com.pharmacy.pos.roles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PosCustomRoleBuilderClient
 * Architecture Decision: ADR-0103
 * Phase 06: Milestone M03 - Staff Onboarding & Multi-Branch Scopes (P103)
 * Requirement Trace: FR-005, FR-031, FR-049, FR-082
 *
 * Client-side integration for permission-key catalog, custom role builder,
 * role cloning, preview analysis, and protected safety minimums in JavaFX Desktop POS.
 */
public class PosCustomRoleBuilderClient {

    private static final Pattern ROLE_CODE_PATTERN = Pattern.compile("^[A-Z0-9_]{3,64}$");
    private static final Set<String> BLOCKED_PERMISSIONS = Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(
            "system:hard_delete",
            "stock:dispense_expired",
            "auth:bypass_tenant_isolation"
    )));

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        OFFLINE,
        ERROR,
        PERMISSION_DENIED
    }

    public static final class CustomRoleRecord {
        public final String roleId;
        public final String tenantId;
        public final String roleCode;
        public final String displayName;
        public final String description;
        public final String baseRole;
        public final List<String> permissionKeys;
        public final boolean requiresPcbRegistration;
        public final boolean isSystemProtected;
        public final boolean isArchived;
        public final String clonedFromId;

        public CustomRoleRecord(
                String roleId,
                String tenantId,
                String roleCode,
                String displayName,
                String description,
                String baseRole,
                List<String> permissionKeys,
                boolean requiresPcbRegistration,
                boolean isSystemProtected,
                boolean isArchived,
                String clonedFromId) {
            this.roleId = roleId;
            this.tenantId = tenantId;
            this.roleCode = roleCode;
            this.displayName = displayName;
            this.description = description != null ? description : "";
            this.baseRole = baseRole;
            this.permissionKeys = permissionKeys != null
                    ? Collections.unmodifiableList(new ArrayList<>(permissionKeys))
                    : Collections.emptyList();
            this.requiresPcbRegistration = requiresPcbRegistration;
            this.isSystemProtected = isSystemProtected;
            this.isArchived = isArchived;
            this.clonedFromId = clonedFromId;
        }

        public boolean allowsClinicalDispensing() {
            return this.permissionKeys.contains("dispense:rx_antibiotics") && this.requiresPcbRegistration;
        }
    }

    public static final class ValidationResult {
        public final boolean isValid;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;
        public final List<String> sodWarnings;

        public ValidationResult(
                boolean isValid,
                String errorCode,
                String messageEn,
                String messageBn,
                List<String> sodWarnings) {
            this.isValid = isValid;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
            this.sodWarnings = sodWarnings != null
                    ? Collections.unmodifiableList(new ArrayList<>(sodWarnings))
                    : Collections.emptyList();
        }

        public static ValidationResult valid(List<String> warnings) {
            return new ValidationResult(true, null, "Role passes safety minimums.", "রোল নিরাপত্তা শর্ত পূরণ করেছে।", warnings);
        }

        public static ValidationResult invalid(String code, String en, String bn) {
            return new ValidationResult(false, code, en, bn, Collections.emptyList());
        }
    }

    public static final class RoleBuilderViewState {
        public final UiStatus uiStatus;
        public final List<CustomRoleRecord> roles;
        public final String messageEn;
        public final String messageBn;

        public RoleBuilderViewState(
                UiStatus uiStatus,
                List<CustomRoleRecord> roles,
                String messageEn,
                String messageBn) {
            this.uiStatus = uiStatus;
            this.roles = roles != null
                    ? Collections.unmodifiableList(new ArrayList<>(roles))
                    : Collections.emptyList();
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }
    }

    /**
     * Validates proposed role against clinical, regulatory, and financial safety minimums.
     */
    public ValidationResult validateRoleSafety(
            String roleCode,
            String displayName,
            List<String> permissionKeys,
            boolean requiresPcbRegistration) {
        if (roleCode == null || !ROLE_CODE_PATTERN.matcher(roleCode.trim().toUpperCase()).matches()) {
            return ValidationResult.invalid(
                    "ERR_INVALID_ROLE_CODE",
                    "Role code must be 3-64 characters consisting of uppercase letters, numbers, and underscores.",
                    "রোল কোড অবশ্যই ৩-৬৪ অক্ষরের ক্যাপিটাল লেটার, নম্বর ও আন্ডারস্কোর হতে হবে।"
            );
        }

        if (displayName == null || displayName.trim().length() < 2) {
            return ValidationResult.invalid(
                    "ERR_INVALID_DISPLAY_NAME",
                    "Role display name must be at least 2 characters.",
                    "রোলের নাম কমপক্ষে ২ অক্ষরের হতে হবে।"
            );
        }

        if (permissionKeys == null || permissionKeys.isEmpty()) {
            return ValidationResult.invalid(
                    "ERR_EMPTY_PERMISSIONS",
                    "A custom role must have at least one permission key.",
                    "একটি কাস্টম রোলে কমপক্ষে একটি পারমিশন কি থাকতে হবে।"
            );
        }

        for (String key : permissionKeys) {
            if (BLOCKED_PERMISSIONS.contains(key)) {
                return ValidationResult.invalid(
                    "ERR_BLOCKED_PERMISSION",
                    "Absolute danger action '" + key + "' cannot be granted in any custom role.",
                    "বিপজ্জনক অ্যাকশন '" + key + "' কোনো কাস্টম রোলে বরাদ্দ করা যাবে না।"
                );
            }
        }

        if (permissionKeys.contains("dispense:rx_antibiotics") && !requiresPcbRegistration) {
            return ValidationResult.invalid(
                    "ERR_SAFETY_MINIMUM_PCB_REQUIRED",
                    "Prescription antibiotic dispensing ('dispense:rx_antibiotics') requires verified PCB registration ('requires_pcb_registration=True').",
                    "প্রেসক্রিপশন অ্যান্টিবায়োটিক বিতরণের জন্য পিসিবি রেজিস্ট্রেশন বাধ্যতামূলক।"
            );
        }

        List<String> warnings = new ArrayList<>();
        if (permissionKeys.contains("inventory:receive_po") && permissionKeys.contains("inventory:adjust_writeoff")) {
            warnings.add("Role combines purchase lot goods receipt with inventory damage write-off authority.");
        }
        if (permissionKeys.contains("sales:checkout") && permissionKeys.contains("sales:void")) {
            warnings.add("Role combines checkout counter authority with sale void/reversal capability.");
        }

        return ValidationResult.valid(warnings);
    }

    /**
     * Determines whether a caller can mutate or archive a specific role.
     */
    public boolean canModifyRole(CustomRoleRecord role, String callerRole) {
        if (callerRole == null) return false;
        String normalizedCaller = callerRole.trim().toUpperCase();
        boolean isOwner = "PHARMACY_OWNER".equals(normalizedCaller)
                || "TENANT_OWNER".equals(normalizedCaller)
                || "SUPERADMIN".equals(normalizedCaller)
                || "SUPER_ADMIN".equals(normalizedCaller);

        if (!isOwner) return false;
        if (role != null && role.isSystemProtected) return false;
        return true;
    }

    public RoleBuilderViewState createSuccessState(List<CustomRoleRecord> roles) {
        boolean empty = roles == null || roles.isEmpty();
        return new RoleBuilderViewState(
                empty ? UiStatus.EMPTY : UiStatus.SUCCESS,
                roles,
                empty ? "No custom roles configured." : "Custom roles catalog loaded successfully.",
                empty ? "কোন কাস্টম রোল কনফিগার করা নেই।" : "কাস্টম রোল ক্যাটালগ সফলভাবে লোড হয়েছে।"
        );
    }

    public RoleBuilderViewState createOfflineState(List<CustomRoleRecord> cachedRoles) {
        return new RoleBuilderViewState(
                UiStatus.OFFLINE,
                cachedRoles,
                "Working offline. Displaying cached custom role definitions.",
                "অফলাইনে কাজ চলছে। ক্যাশ করা কাস্টম রোল তালিকা প্রদর্শিত হচ্ছে।"
        );
    }

    public RoleBuilderViewState createPermissionDeniedState(String callerRole) {
        return new RoleBuilderViewState(
                UiStatus.PERMISSION_DENIED,
                Collections.emptyList(),
                "Access denied for role '" + callerRole + "'. Only pharmacy owners can configure custom roles.",
                "আপনার রোলে ('" + callerRole + "') কাস্টম রোল পরিবর্তনের অনুমতি নেই। শুধুমাত্র মালিকরা ভূমিকা তৈরি করতে পারেন।"
        );
    }
}
