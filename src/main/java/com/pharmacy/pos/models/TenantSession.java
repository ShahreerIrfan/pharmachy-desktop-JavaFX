package com.pharmacy.pos.models;

import java.util.Objects;

/**
 * Encapsulates the active tenant, branch, device registration, and operator session.
 * Architecture Decision: ADR-0005 & ADR-0023.
 */
public class TenantSession {
    private final String tenantId;
    private final String branchId;
    private final String deviceToken;
    private final String userId;
    private final String userRole;
    private final boolean isEnrolled;

    public TenantSession(String tenantId, String branchId, String deviceToken, String userId, String userRole, boolean isEnrolled) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId cannot be null");
        this.branchId = Objects.requireNonNull(branchId, "branchId cannot be null");
        this.deviceToken = Objects.requireNonNull(deviceToken, "deviceToken cannot be null");
        this.userId = Objects.requireNonNull(userId, "userId cannot be null");
        this.userRole = Objects.requireNonNull(userRole, "userRole cannot be null");
        this.isEnrolled = isEnrolled;
    }

    public String getTenantId() { return tenantId; }
    public String getBranchId() { return branchId; }
    public String getDeviceToken() { return deviceToken; }
    public String getUserId() { return userId; }
    public String getUserRole() { return userRole; }
    public boolean isEnrolled() { return isEnrolled; }

    @Override
    public String toString() {
        return "TenantSession{" +
                "tenantId='" + tenantId + '\'' +
                ", branchId='" + branchId + '\'' +
                ", userRole='" + userRole + '\'' +
                ", isEnrolled=" + isEnrolled +
                '}';
    }
}

