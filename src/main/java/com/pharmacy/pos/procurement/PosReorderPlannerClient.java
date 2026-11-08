package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * PosReorderPlannerClient
 * Architecture Decision: ADR-0194 (P194).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 *
 * Desktop JavaFX client governing Automatic Reorder Planning:
 * - Recommends replenishment quantities using lead time, safety stock, FEFO exposure, and demand history.
 * - Never auto-orders silently: generates structured proposals requiring explicit human authorization.
 * - Provides managerial approval workflows and formal conversion into real Purchase Orders.
 */
public class PosReorderPlannerClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, ReorderProposalDto> proposalStore;
    private final Map<String, PosPurchaseOrderClient.PurchaseOrderDto> poStore;

    private static final Set<String> ALLOWED_PROCUREMENT_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "PROCUREMENT_OFFICER", "INVENTORY_CLERK", "AUDITOR"
    );

    private static final Set<String> ALLOWED_APPROVAL_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "PROCUREMENT_OFFICER"
    );

    public PosReorderPlannerClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, ReorderProposalDto> proposalStore,
            Map<String, PosPurchaseOrderClient.PurchaseOrderDto> poStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.proposalStore = proposalStore != null ? proposalStore : new HashMap<>();
        this.poStore = poStore != null ? poStore : new HashMap<>();
    }

    public static final class ReorderItemDto {
        public final String itemId;
        public final String productId;
        public final String productName;
        public final String suggestedSupplierId;
        public final String supplierName;
        public final int currentStockUnits;
        public final int pendingIncomingUnits;
        public final int expiringSoonUnits;
        public final int effectiveStockUnits;
        public final BigDecimal averageDailyDemand;
        public final int leadTimeDays;
        public final int safetyStockUnits;
        public final int reorderPointUnits;
        public final int rawRecommendedUnits;
        public int approvedOrderUnits;
        public final String packUnit;
        public final BigDecimal estimatedUnitCost;
        public BigDecimal estimatedLineCost;
        public final String urgency; // CRITICAL, HIGH, MEDIUM, NORMAL
        public final BigDecimal stockoutRiskDays;
        public String conversionPoId;
        public final String rationale;

        public ReorderItemDto(String itemId, String productId, String productName, String suggestedSupplierId,
                              String supplierName, int currentStockUnits, int pendingIncomingUnits,
                              int expiringSoonUnits, int effectiveStockUnits, BigDecimal averageDailyDemand,
                              int leadTimeDays, int safetyStockUnits, int reorderPointUnits,
                              int rawRecommendedUnits, int approvedOrderUnits, String packUnit,
                              BigDecimal estimatedUnitCost, BigDecimal estimatedLineCost, String urgency,
                              BigDecimal stockoutRiskDays, String conversionPoId, String rationale) {
            this.itemId = itemId != null ? itemId : "ropi-" + UUID.randomUUID().toString().substring(0, 8);
            this.productId = productId;
            this.productName = productName != null ? productName : "";
            this.suggestedSupplierId = suggestedSupplierId;
            this.supplierName = supplierName != null ? supplierName : "";
            this.currentStockUnits = currentStockUnits;
            this.pendingIncomingUnits = pendingIncomingUnits;
            this.expiringSoonUnits = expiringSoonUnits;
            this.effectiveStockUnits = effectiveStockUnits;
            this.averageDailyDemand = averageDailyDemand != null ? averageDailyDemand : BigDecimal.ZERO;
            this.leadTimeDays = leadTimeDays;
            this.safetyStockUnits = safetyStockUnits;
            this.reorderPointUnits = reorderPointUnits;
            this.rawRecommendedUnits = rawRecommendedUnits;
            this.approvedOrderUnits = approvedOrderUnits;
            this.packUnit = packUnit != null ? packUnit : "BASE_UNIT";
            this.estimatedUnitCost = estimatedUnitCost != null ? estimatedUnitCost : BigDecimal.ZERO;
            this.estimatedLineCost = estimatedLineCost != null ? estimatedLineCost : BigDecimal.ZERO;
            this.urgency = urgency != null ? urgency : "MEDIUM";
            this.stockoutRiskDays = stockoutRiskDays != null ? stockoutRiskDays : BigDecimal.ZERO;
            this.conversionPoId = conversionPoId;
            this.rationale = rationale != null ? rationale : "";
        }
    }

    public static final class ReorderProposalDto {
        public final String proposalId;
        public final String proposalNumber;
        public final String tenantId;
        public final String branchId;
        public final LocalDate planDate;
        public final int planningHorizonDays;
        public String status; // DRAFT_PROPOSAL, APPROVED, CONVERTED_TO_PO, DISMISSED
        public int totalRecommendedSkus;
        public BigDecimal totalEstimatedCost;
        public String reviewedBy;
        public Instant reviewedAt;
        public String reviewNotes;
        public final List<ReorderItemDto> items;

        public ReorderProposalDto(String proposalId, String proposalNumber, String tenantId, String branchId,
                                  LocalDate planDate, int planningHorizonDays, String status,
                                  int totalRecommendedSkus, BigDecimal totalEstimatedCost,
                                  String reviewedBy, Instant reviewedAt, String reviewNotes,
                                  List<ReorderItemDto> items) {
            this.proposalId = proposalId;
            this.proposalNumber = proposalNumber;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.planDate = planDate;
            this.planningHorizonDays = planningHorizonDays;
            this.status = status;
            this.totalRecommendedSkus = totalRecommendedSkus;
            this.totalEstimatedCost = totalEstimatedCost;
            this.reviewedBy = reviewedBy;
            this.reviewedAt = reviewedAt;
            this.reviewNotes = reviewNotes;
            this.items = items != null ? items : new ArrayList<>();
        }
    }

    private void assertProcurementRole() {
        if ("CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_PROCUREMENT_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Staff role " + userRole + " cannot access reorder planning.");
        }
    }

    private void assertApprovalRole() {
        if (!ALLOWED_APPROVAL_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Approval requires managerial or procurement officer role.");
        }
    }

    public ReorderProposalDto generateReorderPlan(
            int planningHorizonDays,
            List<ReorderItemDto> calculatedRecommendations) {
        assertProcurementRole();

        String propId = "rop-" + UUID.randomUUID().toString().substring(0, 8);
        String propNum = "ROP-" + System.currentTimeMillis();

        BigDecimal totalCost = BigDecimal.ZERO;
        List<ReorderItemDto> validated = new ArrayList<>();

        if (calculatedRecommendations != null) {
            for (ReorderItemDto itm : calculatedRecommendations) {
                totalCost = totalCost.add(itm.estimatedLineCost);
                validated.add(itm);
            }
        }

        ReorderProposalDto prop = new ReorderProposalDto(
                propId,
                propNum,
                tenantId,
                branchId,
                LocalDate.now(),
                planningHorizonDays > 0 ? planningHorizonDays : 14,
                "DRAFT_PROPOSAL",
                validated.size(),
                totalCost,
                null,
                null,
                "",
                validated
        );

        proposalStore.put(propId, prop);
        return prop;
    }

    public ReorderProposalDto approveReorderPlan(
            String proposalId,
            String reviewNotes,
            Map<String, Integer> itemAdjustments) {
        assertApprovalRole();

        ReorderProposalDto prop = proposalStore.get(proposalId);
        if (prop == null) {
            throw new NoSuchElementException("ERR_PROPOSAL_NOT_FOUND: Proposal " + proposalId + " not found.");
        }

        if (!"DRAFT_PROPOSAL".equals(prop.status)) {
            throw new IllegalStateException("ERR_INVALID_STATUS: Cannot approve proposal in status " + prop.status);
        }

        BigDecimal newTotal = BigDecimal.ZERO;
        for (ReorderItemDto item : prop.items) {
            if (itemAdjustments != null && itemAdjustments.containsKey(item.itemId)) {
                item.approvedOrderUnits = Math.max(0, itemAdjustments.get(item.itemId));
                item.estimatedLineCost = item.estimatedUnitCost.multiply(BigDecimal.valueOf(item.approvedOrderUnits)).setScale(4, RoundingMode.HALF_UP);
            }
            newTotal = newTotal.add(item.estimatedLineCost);
        }

        prop.status = "APPROVED";
        prop.reviewedBy = userId;
        prop.reviewedAt = Instant.now();
        prop.reviewNotes = reviewNotes != null ? reviewNotes : "";
        prop.totalEstimatedCost = newTotal;

        return prop;
    }

    public List<String> convertToPurchaseOrders(String proposalId) {
        assertApprovalRole();

        ReorderProposalDto prop = proposalStore.get(proposalId);
        if (prop == null) {
            throw new NoSuchElementException("ERR_PROPOSAL_NOT_FOUND: Proposal " + proposalId + " not found.");
        }

        if (!"APPROVED".equals(prop.status)) {
            throw new IllegalStateException("ERR_PLAN_NOT_APPROVED: Plan must be APPROVED before conversion. Never auto-orders silently.");
        }

        // Group by supplier
        Map<String, List<ReorderItemDto>> grouped = new HashMap<>();
        for (ReorderItemDto item : prop.items) {
            if (item.approvedOrderUnits > 0) {
                grouped.computeIfAbsent(item.suggestedSupplierId != null ? item.suggestedSupplierId : "default", k -> new ArrayList<>()).add(item);
            }
        }

        List<String> createdPoIds = new ArrayList<>();
        for (Map.Entry<String, List<ReorderItemDto>> entry : grouped.entrySet()) {
            String poId = "po-" + UUID.randomUUID().toString().substring(0, 8);
            String poNum = "PO-" + System.currentTimeMillis();

            BigDecimal poTotal = BigDecimal.ZERO;
            List<PosPurchaseOrderClient.PurchaseOrderItemDto> poItems = new ArrayList<>();

            for (ReorderItemDto itm : entry.getValue()) {
                poTotal = poTotal.add(itm.estimatedLineCost);
                itm.conversionPoId = poId;
                poItems.add(new PosPurchaseOrderClient.PurchaseOrderItemDto(
                        null, itm.productId, itm.productName, itm.packUnit, 1,
                        itm.approvedOrderUnits, 0, itm.estimatedUnitCost, BigDecimal.ZERO, ""
                ));
            }

            PosPurchaseOrderClient.PurchaseOrderDto po = new PosPurchaseOrderClient.PurchaseOrderDto(
                    poId, poNum, tenantId, branchId, "", entry.getKey(),
                    entry.getValue().get(0).supplierName, null, "DRAFT", 30, "BDT",
                    poTotal, BigDecimal.ZERO, BigDecimal.ZERO, poTotal, LocalDate.now().plusDays(3),
                    null, null, null, null, null, null, null, null, null,
                    "Auto-generated from " + prop.proposalNumber, true, poItems, Instant.now()
            );

            poStore.put(poId, po);
            createdPoIds.add(poId);
        }

        prop.status = "CONVERTED_TO_PO";
        return createdPoIds;
    }

    public ReorderProposalDto dismissReorderPlan(String proposalId, String reason) {
        assertProcurementRole();

        ReorderProposalDto prop = proposalStore.get(proposalId);
        if (prop == null) {
            throw new NoSuchElementException("ERR_PROPOSAL_NOT_FOUND: Proposal " + proposalId + " not found.");
        }

        if ("CONVERTED_TO_PO".equals(prop.status)) {
            throw new IllegalStateException("ERR_CANNOT_DISMISS_CONVERTED: Cannot dismiss converted proposal.");
        }

        prop.status = "DISMISSED";
        prop.reviewNotes = (prop.reviewNotes != null ? prop.reviewNotes : "") + " | Dismissed: " + reason;
        return prop;
    }

    public ReorderProposalDto getReorderPlan(String proposalId) {
        ReorderProposalDto prop = proposalStore.get(proposalId);
        if (prop == null) {
            throw new NoSuchElementException("ERR_PROPOSAL_NOT_FOUND: Proposal " + proposalId + " not found.");
        }
        return prop;
    }

    public List<ReorderProposalDto> listReorderPlans(String statusFilter) {
        List<ReorderProposalDto> results = new ArrayList<>();
        for (ReorderProposalDto p : proposalStore.values()) {
            if (statusFilter != null && !statusFilter.isEmpty() && !statusFilter.equalsIgnoreCase(p.status)) {
                continue;
            }
            results.add(p);
        }
        return results;
    }
}
