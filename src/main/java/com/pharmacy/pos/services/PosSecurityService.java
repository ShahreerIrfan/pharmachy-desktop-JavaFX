package com.pharmacy.pos.services;

import com.pharmacy.pos.models.TenantSession;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * RBAC authorization and cross-tenant boundary enforcer for POS terminal.
 * Architecture Decision: ADR-0005, ADR-0022 & ADR-0023.
 */
public class PosSecurityService {
    private static final Set<String> CHECKOUT_ROLES = new HashSet<>(
            Arrays.asList("CASHIER", "BRANCH_MANAGER", "PHARMACIST")
    );

    private static final Set<String> ADMIN_MAINTENANCE_ROLES = new HashSet<>(
            Arrays.asList("PLATFORM_SUPER_ADMIN", "DEVOPS_ENGINEER")
    );

    public void assertDeviceEnrolled(TenantSession session) {
        if (!session.isEnrolled()) {
            throw new SecurityException("Security Violation: POS Terminal is not enrolled or cryptographic lease expired (ADR-0005).");
        }
    }

    public void assertCanCheckout(TenantSession session) {
        assertDeviceEnrolled(session);
        if (!CHECKOUT_ROLES.contains(session.getUserRole())) {
            throw new SecurityException(String.format(
                    "Security Violation: Role '%s' is not authorized to finalize cashier checkout.",
                    session.getUserRole()
            ));
        }
    }

    public void assertCanExportDatabase(TenantSession session) {
        assertDeviceEnrolled(session);
        if (!ADMIN_MAINTENANCE_ROLES.contains(session.getUserRole())) {
            throw new SecurityException(String.format(
                    "Security Violation: Role '%s' is strictly forbidden from exporting raw POS databases. Requires PLATFORM_SUPER_ADMIN.",
                    session.getUserRole()
            ));
        }
    }

    public void assertTenantBranchMatch(TenantSession session, String targetTenantId, String targetBranchId) {
        assertDeviceEnrolled(session);
        if (!session.getTenantId().equals(targetTenantId)) {
            throw new SecurityException(String.format(
                    "Cross-Tenant Violation: Active session tenant '%s' cannot access foreign tenant '%s'.",
                    session.getTenantId(), targetTenantId
            ));
        }
        if (!session.getBranchId().equals(targetBranchId)) {
            throw new SecurityException(String.format(
                    "Cross-Branch Violation: Active session branch '%s' cannot access foreign branch '%s'.",
                    session.getBranchId(), targetBranchId
            ));
        }
    }
}

