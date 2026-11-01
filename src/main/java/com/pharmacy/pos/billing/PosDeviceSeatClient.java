package com.pharmacy.pos.billing;

/**
 * PosDeviceSeatClient
 * Architecture Decision: ADR-0007, ADR-0081, ADR-0082, ADR-0095 & ADR-0096
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P096)
 * Requirement Trace: FR-004, FR-070, FR-071, FR-072 (Authoritative Device Seats & Lease Invalidation)
 *
 * Client-side device seat management, capacity limit checking,
 * and bilingual workstation status banners for JavaFX Desktop POS.
 */
public class PosDeviceSeatClient {

    public static final class DeviceSeatRecord {
        public final String deviceId;
        public final String tenantId;
        public final String branchId;
        public final String branchName;
        public final String deviceName;
        public final String deviceType;
        public final String status; // ACTIVE, REVOKED, SUSPENDED, DECOMMISSIONED
        public final String macAddress;
        public final String ipAddress;
        public final String hardwareFingerprint;
        public final String enrolledAt;
        public final String enrolledBy;
        public final String revokedAt;
        public final String revocationReason;
        public final String lastHeartbeatAt;
        public final String headlineEn;
        public final String headlineBn;
        public final String detailsEn;
        public final String detailsBn;

        public DeviceSeatRecord(
                String deviceId,
                String tenantId,
                String branchId,
                String branchName,
                String deviceName,
                String deviceType,
                String status,
                String macAddress,
                String ipAddress,
                String hardwareFingerprint,
                String enrolledAt,
                String enrolledBy,
                String revokedAt,
                String revocationReason,
                String lastHeartbeatAt,
                String headlineEn,
                String headlineBn,
                String detailsEn,
                String detailsBn) {
            this.deviceId = deviceId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.branchName = branchName;
            this.deviceName = deviceName;
            this.deviceType = deviceType;
            this.status = status;
            this.macAddress = macAddress;
            this.ipAddress = ipAddress;
            this.hardwareFingerprint = hardwareFingerprint;
            this.enrolledAt = enrolledAt;
            this.enrolledBy = enrolledBy;
            this.revokedAt = revokedAt;
            this.revocationReason = revocationReason;
            this.lastHeartbeatAt = lastHeartbeatAt;
            this.headlineEn = headlineEn;
            this.headlineBn = headlineBn;
            this.detailsEn = detailsEn;
            this.detailsBn = detailsBn;
        }

        public boolean isActive() {
            return "ACTIVE".equalsIgnoreCase(status);
        }

        public boolean isRevoked() {
            return "REVOKED".equalsIgnoreCase(status);
        }
    }

    public static final class DeviceSeatsSummary {
        public final String tenantId;
        public final String planTier;
        public final int totalEnrolled;
        public final int activeSeatsUsed;
        public final int maxLimit;
        public final int remaining;
        public final double utilizationPct;
        public final boolean isOverQuota;
        public final String status; // NORMAL, WARNING, LIMIT_REACHED, OVER_QUOTA
        public final String headlineEn;
        public final String headlineBn;
        public final String detailsEn;
        public final String detailsBn;

        public DeviceSeatsSummary(
                String tenantId,
                String planTier,
                int totalEnrolled,
                int activeSeatsUsed,
                int maxLimit,
                int remaining,
                double utilizationPct,
                boolean isOverQuota,
                String status,
                String headlineEn,
                String headlineBn,
                String detailsEn,
                String detailsBn) {
            this.tenantId = tenantId;
            this.planTier = planTier;
            this.totalEnrolled = totalEnrolled;
            this.activeSeatsUsed = activeSeatsUsed;
            this.maxLimit = maxLimit;
            this.remaining = remaining;
            this.utilizationPct = utilizationPct;
            this.isOverQuota = isOverQuota;
            this.status = status;
            this.headlineEn = headlineEn;
            this.headlineBn = headlineBn;
            this.detailsEn = detailsEn;
            this.detailsBn = detailsBn;
        }

        public boolean canEnrollMore() {
            return activeSeatsUsed < maxLimit;
        }

        public boolean isAtCapacity() {
            return activeSeatsUsed >= maxLimit;
        }
    }

    public static boolean canEnrollDevice(int activeSeatsUsed, int maxLimit) {
        return activeSeatsUsed < maxLimit;
    }

    public static DeviceSeatsSummary evaluateSummary(
            String tenantId,
            String planTier,
            int totalEnrolled,
            int activeSeatsUsed,
            int maxLimit) {

        int remaining = Math.max(0, maxLimit - activeSeatsUsed);
        double pct = maxLimit > 0 ? Math.round(((double) activeSeatsUsed / maxLimit) * 1000.0) / 10.0 : 0.0;
        boolean isOver = activeSeatsUsed > maxLimit;

        String status;
        String hlEn;
        String hlBn;
        String dtEn;
        String dtBn;

        if (isOver) {
            status = "OVER_QUOTA";
            hlEn = "Device Seat Quota Exceeded";
            hlBn = "ডিভাইস সীট কোটা অতিক্রম করেছে";
            dtEn = "More active devices than plan allows. Upgrade required.";
            dtBn = "প্ল্যানের চেয়ে অতিরিক্ত ডিভাইস সক্রিয় রয়েছে। আপগ্রেড প্রয়োজন।";
        } else if (activeSeatsUsed >= maxLimit) {
            status = "LIMIT_REACHED";
            hlEn = "Device Seat Capacity Reached";
            hlBn = "ডিভাইস সীট পূর্ণ হয়েছে";
            dtEn = "All allocated device seats are in use.";
            dtBn = "বরাদ্দকৃত সকল ডিভাইস সীট ব্যবহৃত হচ্ছে।";
        } else if (pct >= 80.0) {
            status = "WARNING";
            hlEn = "Device Seats Near Capacity";
            hlBn = "ডিভাইস সীট ধারণক্ষমতার কাছাকাছি";
            dtEn = "Device seat utilization is at or above 80%.";
            dtBn = "ডিভাইস সীট ব্যবহার ৮০% বা তার বেশি।";
        } else {
            status = "NORMAL";
            hlEn = "Device Seats Available";
            hlBn = "ডিভাইস সীট উপলব্ধ রয়েছে";
            dtEn = remaining + " device seat(s) remaining for enrollment.";
            dtBn = "নতুন ডিভাইস যুক্ত করার জন্য " + remaining + " টি সীট খালি রয়েছে।";
        }

        return new DeviceSeatsSummary(
                tenantId,
                planTier != null ? planTier.toUpperCase() : "BASIC",
                totalEnrolled,
                activeSeatsUsed,
                maxLimit,
                remaining,
                pct,
                isOver,
                status,
                hlEn,
                hlBn,
                dtEn,
                dtBn
        );
    }
}

