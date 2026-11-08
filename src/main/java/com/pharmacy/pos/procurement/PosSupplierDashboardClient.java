package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * PosSupplierDashboardClient
 * Architecture Decision: ADR-0199 (P199).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 *
 * Desktop JavaFX client for Supplier Operations Dashboard:
 * - Aggregates open orders, overdue deliveries, due balances, and low-stock sourcing opportunities.
 * - Generates auditable snapshots with SHA-256 cryptographic hashes.
 * - Restricts Cashier access with role verification.
 */
public class PosSupplierDashboardClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, PosSupplierDashboardSnapshotDto> snapshotStore;

    private static final Set<String> ALLOWED_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "PROCUREMENT_OFFICER", "INVENTORY_CLERK", "AUDITOR"
    );

    public PosSupplierDashboardClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, PosSupplierDashboardSnapshotDto> snapshotStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.snapshotStore = snapshotStore != null ? snapshotStore : new HashMap<>();
    }

    public static final class PosOpenOrderItem {
        public final String poId;
        public final String poNumber;
        public final String supplierName;
        public final String status;
        public final BigDecimal totalAmount;

        public PosOpenOrderItem(String poId, String poNumber, String supplierName, String status, BigDecimal totalAmount) {
            this.poId = poId != null ? poId : "";
            this.poNumber = poNumber != null ? poNumber : "";
            this.supplierName = supplierName != null ? supplierName : "";
            this.status = status != null ? status : "";
            this.totalAmount = totalAmount != null ? totalAmount : BigDecimal.ZERO;
        }
    }

    public static final class PosOverdueDeliveryItem {
        public final String poId;
        public final String poNumber;
        public final String supplierName;
        public final String expectedDeliveryDate;
        public final int daysOverdue;
        public final BigDecimal totalAmount;

        public PosOverdueDeliveryItem(String poId, String poNumber, String supplierName,
                                      String expectedDeliveryDate, int daysOverdue, BigDecimal totalAmount) {
            this.poId = poId != null ? poId : "";
            this.poNumber = poNumber != null ? poNumber : "";
            this.supplierName = supplierName != null ? supplierName : "";
            this.expectedDeliveryDate = expectedDeliveryDate != null ? expectedDeliveryDate : "";
            this.daysOverdue = daysOverdue;
            this.totalAmount = totalAmount != null ? totalAmount : BigDecimal.ZERO;
        }
    }

    public static final class PosSupplierDueItem {
        public final String supplierId;
        public final String supplierName;
        public final int paymentTermsDays;
        public final BigDecimal currentBalance;
        public final boolean isOverdue;

        public PosSupplierDueItem(String supplierId, String supplierName, int paymentTermsDays,
                                  BigDecimal currentBalance, boolean isOverdue) {
            this.supplierId = supplierId != null ? supplierId : "";
            this.supplierName = supplierName != null ? supplierName : "";
            this.paymentTermsDays = paymentTermsDays;
            this.currentBalance = currentBalance != null ? currentBalance : BigDecimal.ZERO;
            this.isOverdue = isOverdue;
        }
    }

    public static final class PosLowStockSourcingItem {
        public final String productId;
        public final String brandName;
        public final int currentStock;
        public final int recommendedReorderQty;
        public final String preferredSupplierName;
        public final BigDecimal quotedUnitCost;
        public final BigDecimal estimatedCost;

        public PosLowStockSourcingItem(String productId, String brandName, int currentStock,
                                       int recommendedReorderQty, String preferredSupplierName,
                                       BigDecimal quotedUnitCost, BigDecimal estimatedCost) {
            this.productId = productId != null ? productId : "";
            this.brandName = brandName != null ? brandName : "";
            this.currentStock = currentStock;
            this.recommendedReorderQty = recommendedReorderQty;
            this.preferredSupplierName = preferredSupplierName != null ? preferredSupplierName : "";
            this.quotedUnitCost = quotedUnitCost != null ? quotedUnitCost : BigDecimal.ZERO;
            this.estimatedCost = estimatedCost != null ? estimatedCost : BigDecimal.ZERO;
        }
    }

    public static final class PosSupplierDashboardSummary {
        public final int openOrdersCount;
        public final BigDecimal openOrdersValue;
        public final List<PosOpenOrderItem> openOrders;

        public final int overdueDeliveriesCount;
        public final BigDecimal overdueDeliveriesValue;
        public final List<PosOverdueDeliveryItem> overdueDeliveries;

        public final BigDecimal totalDueBalance;
        public final List<PosSupplierDueItem> supplierDues;

        public final int lowStockOpportunitiesCount;
        public final BigDecimal estimatedReorderCost;
        public final List<PosLowStockSourcingItem> lowStockItems;

        public final int activeSuppliersCount;

        public PosSupplierDashboardSummary(
                int openOrdersCount, BigDecimal openOrdersValue, List<PosOpenOrderItem> openOrders,
                int overdueDeliveriesCount, BigDecimal overdueDeliveriesValue, List<PosOverdueDeliveryItem> overdueDeliveries,
                BigDecimal totalDueBalance, List<PosSupplierDueItem> supplierDues,
                int lowStockOpportunitiesCount, BigDecimal estimatedReorderCost, List<PosLowStockSourcingItem> lowStockItems,
                int activeSuppliersCount) {
            this.openOrdersCount = openOrdersCount;
            this.openOrdersValue = openOrdersValue != null ? openOrdersValue : BigDecimal.ZERO;
            this.openOrders = openOrders != null ? Collections.unmodifiableList(new ArrayList<>(openOrders)) : Collections.emptyList();
            this.overdueDeliveriesCount = overdueDeliveriesCount;
            this.overdueDeliveriesValue = overdueDeliveriesValue != null ? overdueDeliveriesValue : BigDecimal.ZERO;
            this.overdueDeliveries = overdueDeliveries != null ? Collections.unmodifiableList(new ArrayList<>(overdueDeliveries)) : Collections.emptyList();
            this.totalDueBalance = totalDueBalance != null ? totalDueBalance : BigDecimal.ZERO;
            this.supplierDues = supplierDues != null ? Collections.unmodifiableList(new ArrayList<>(supplierDues)) : Collections.emptyList();
            this.lowStockOpportunitiesCount = lowStockOpportunitiesCount;
            this.estimatedReorderCost = estimatedReorderCost != null ? estimatedReorderCost : BigDecimal.ZERO;
            this.lowStockItems = lowStockItems != null ? Collections.unmodifiableList(new ArrayList<>(lowStockItems)) : Collections.emptyList();
            this.activeSuppliersCount = activeSuppliersCount;
        }
    }

    public static final class PosSupplierDashboardSnapshotDto {
        public final String snapshotId;
        public final String tenantId;
        public final String branchId;
        public final PosSupplierDashboardSummary summary;
        public final String immutableVerificationHash;
        public final String notes;
        public final String generatedBy;
        public final Instant generatedAt;

        public PosSupplierDashboardSnapshotDto(
                String snapshotId,
                String tenantId,
                String branchId,
                PosSupplierDashboardSummary summary,
                String immutableVerificationHash,
                String notes,
                String generatedBy,
                Instant generatedAt) {
            this.snapshotId = snapshotId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.summary = summary;
            this.immutableVerificationHash = immutableVerificationHash;
            this.notes = notes != null ? notes : "";
            this.generatedBy = generatedBy;
            this.generatedAt = generatedAt != null ? generatedAt : Instant.now();
        }
    }

    private void assertRolePermission() {
        if ("CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Staff role " + userRole + " is not authorized to view supplier operations dashboard.");
        }
    }

    public static String computeVerificationHash(
            String snapshotId,
            String tenantId,
            int openOrdersCount,
            BigDecimal openOrdersValue,
            int overdueDeliveriesCount,
            BigDecimal totalDueBalance,
            int lowStockCount) {
        String raw = snapshotId + "|" + tenantId + "|" + openOrdersCount + "|" + openOrdersValue.toPlainString() + "|" +
                overdueDeliveriesCount + "|" + totalDueBalance.toPlainString() + "|" + lowStockCount;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public PosSupplierDashboardSummary compileSummary(
            List<PosOpenOrderItem> orders,
            List<PosOverdueDeliveryItem> overdue,
            List<PosSupplierDueItem> dues,
            List<PosLowStockSourcingItem> lowStock,
            int activeSuppliersCount) {

        assertRolePermission();

        BigDecimal openVal = BigDecimal.ZERO;
        if (orders != null) {
            for (PosOpenOrderItem o : orders) {
                openVal = openVal.add(o.totalAmount);
            }
        }

        BigDecimal overdueVal = BigDecimal.ZERO;
        if (overdue != null) {
            for (PosOverdueDeliveryItem d : overdue) {
                overdueVal = overdueVal.add(d.totalAmount);
            }
        }

        BigDecimal dueBal = BigDecimal.ZERO;
        if (dues != null) {
            for (PosSupplierDueItem s : dues) {
                dueBal = dueBal.add(s.currentBalance);
            }
        }

        BigDecimal restockCost = BigDecimal.ZERO;
        if (lowStock != null) {
            for (PosLowStockSourcingItem l : lowStock) {
                restockCost = restockCost.add(l.estimatedCost);
            }
        }

        return new PosSupplierDashboardSummary(
                orders != null ? orders.size() : 0,
                openVal,
                orders,
                overdue != null ? overdue.size() : 0,
                overdueVal,
                overdue,
                dueBal,
                dues,
                lowStock != null ? lowStock.size() : 0,
                restockCost,
                lowStock,
                activeSuppliersCount
        );
    }

    public PosSupplierDashboardSnapshotDto createSnapshot(PosSupplierDashboardSummary summary, String notes) {
        assertRolePermission();

        if (summary == null) {
            throw new IllegalArgumentException("ERR_INVALID_INPUT: summary is required.");
        }

        String snapId = "sdash-" + (snapshotStore.size() + 1) + "-" + System.currentTimeMillis();
        String hash = computeVerificationHash(
                snapId,
                tenantId,
                summary.openOrdersCount,
                summary.openOrdersValue,
                summary.overdueDeliveriesCount,
                summary.totalDueBalance,
                summary.lowStockOpportunitiesCount
        );

        PosSupplierDashboardSnapshotDto snap = new PosSupplierDashboardSnapshotDto(
                snapId,
                tenantId,
                branchId,
                summary,
                hash,
                notes,
                userId,
                Instant.now()
        );

        snapshotStore.put(snapId, snap);
        return snap;
    }

    public PosSupplierDashboardSnapshotDto getSnapshot(String snapshotId) {
        PosSupplierDashboardSnapshotDto snap = snapshotStore.get(snapshotId);
        if (snap == null) {
            throw new NoSuchElementException("ERR_SNAPSHOT_NOT_FOUND: Snapshot " + snapshotId + " not found.");
        }
        return snap;
    }

    public List<PosSupplierDashboardSnapshotDto> listSnapshots() {
        List<PosSupplierDashboardSnapshotDto> list = new ArrayList<>(snapshotStore.values());
        list.sort((a, b) -> b.generatedAt.compareTo(a.generatedAt));
        return list;
    }
}

