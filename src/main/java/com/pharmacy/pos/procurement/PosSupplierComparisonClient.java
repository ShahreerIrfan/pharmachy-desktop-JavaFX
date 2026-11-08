package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * PosSupplierComparisonClient
 * Architecture Decision: ADR-0195 (P195).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 *
 * Desktop JavaFX client governing Multi-Supplier Comparison:
 * - Compares suppliers on unit cost, package size, delivery reliability, lead time, and approved terms.
 * - Computes multi-factor composite scores and ranks candidate vendors.
 * - Saves audited comparison evaluations and converts winning quotations to Purchase Orders.
 */
public class PosSupplierComparisonClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, ComparisonEvaluationDto> evaluationStore;
    private final Map<String, PosPurchaseOrderClient.PurchaseOrderDto> poStore;

    private static final Set<String> ALLOWED_PROCUREMENT_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "PROCUREMENT_OFFICER", "INVENTORY_CLERK", "AUDITOR"
    );

    private static final Set<String> ALLOWED_MANAGERIAL_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "PROCUREMENT_OFFICER"
    );

    public PosSupplierComparisonClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, ComparisonEvaluationDto> evaluationStore,
            Map<String, PosPurchaseOrderClient.PurchaseOrderDto> poStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.evaluationStore = evaluationStore != null ? evaluationStore : new HashMap<>();
        this.poStore = poStore != null ? poStore : new HashMap<>();
    }

    public static final class ComparisonLineDto {
        public final String lineId;
        public final String supplierId;
        public final String supplierName;
        public final String packageType;
        public final int packageSize;
        public final BigDecimal packageCostPrice;
        public final BigDecimal unitCostPrice;
        public final BigDecimal mrpUnit;
        public final BigDecimal discountPercentage;
        public final BigDecimal effectiveNetUnitCost;
        public final int minimumOrderQuantity;
        public final int leadTimeDays;
        public final int paymentTermsDays;
        public final BigDecimal deliveryReliabilityScore;
        public final boolean proofOfOriginVerified;
        public int compositeRank;
        public BigDecimal compositeScore;
        public boolean isRecommended;
        public final String notes;

        public ComparisonLineDto(
                String lineId, String supplierId, String supplierName, String packageType,
                int packageSize, BigDecimal packageCostPrice, BigDecimal unitCostPrice,
                BigDecimal mrpUnit, BigDecimal discountPercentage, BigDecimal effectiveNetUnitCost,
                int minimumOrderQuantity, int leadTimeDays, int paymentTermsDays,
                BigDecimal deliveryReliabilityScore, boolean proofOfOriginVerified,
                int compositeRank, BigDecimal compositeScore, boolean isRecommended, String notes) {
            this.lineId = lineId != null ? lineId : "scl-" + UUID.randomUUID().toString().substring(0, 8);
            this.supplierId = supplierId;
            this.supplierName = supplierName != null ? supplierName : "";
            this.packageType = packageType != null ? packageType : "BOX";
            this.packageSize = Math.max(1, packageSize);
            this.packageCostPrice = packageCostPrice != null ? packageCostPrice : BigDecimal.ZERO;
            this.unitCostPrice = unitCostPrice != null ? unitCostPrice : BigDecimal.ZERO;
            this.mrpUnit = mrpUnit != null ? mrpUnit : BigDecimal.ZERO;
            this.discountPercentage = discountPercentage != null ? discountPercentage : BigDecimal.ZERO;
            this.effectiveNetUnitCost = effectiveNetUnitCost != null ? effectiveNetUnitCost : this.unitCostPrice;
            this.minimumOrderQuantity = Math.max(1, minimumOrderQuantity);
            this.leadTimeDays = Math.max(1, leadTimeDays);
            this.paymentTermsDays = Math.max(0, paymentTermsDays);
            this.deliveryReliabilityScore = deliveryReliabilityScore != null ? deliveryReliabilityScore : new BigDecimal("100.00");
            this.proofOfOriginVerified = proofOfOriginVerified;
            this.compositeRank = compositeRank;
            this.compositeScore = compositeScore != null ? compositeScore : BigDecimal.ZERO;
            this.isRecommended = isRecommended;
            this.notes = notes != null ? notes : "";
        }
    }

    public static final class ComparisonEvaluationDto {
        public final String evaluationId;
        public final String evaluationNumber;
        public final String tenantId;
        public final String branchId;
        public final String productId;
        public final String productName;
        public final int targetOrderQuantity;
        public String status; // DRAFT, FINALIZED, ORDER_PLACED, ARCHIVED
        public String selectedSupplierId;
        public String selectedSupplierName;
        public String selectionReason;
        public String evaluatedBy;
        public Instant evaluatedAt;
        public String createdPoId;
        public String notes;
        public final List<ComparisonLineDto> lines;

        public ComparisonEvaluationDto(
                String evaluationId, String evaluationNumber, String tenantId, String branchId,
                String productId, String productName, int targetOrderQuantity, String status,
                String selectedSupplierId, String selectedSupplierName, String selectionReason,
                String evaluatedBy, Instant evaluatedAt, String createdPoId, String notes,
                List<ComparisonLineDto> lines) {
            this.evaluationId = evaluationId;
            this.evaluationNumber = evaluationNumber;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.productId = productId;
            this.productName = productName != null ? productName : "";
            this.targetOrderQuantity = Math.max(1, targetOrderQuantity);
            this.status = status != null ? status : "DRAFT";
            this.selectedSupplierId = selectedSupplierId;
            this.selectedSupplierName = selectedSupplierName;
            this.selectionReason = selectionReason != null ? selectionReason : "";
            this.evaluatedBy = evaluatedBy;
            this.evaluatedAt = evaluatedAt;
            this.createdPoId = createdPoId;
            this.notes = notes != null ? notes : "";
            this.lines = lines != null ? lines : new ArrayList<>();
        }
    }

    private void assertProcurementRole() {
        if ("CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_PROCUREMENT_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Staff role " + userRole + " cannot access supplier comparisons.");
        }
    }

    private void assertManagerialRole() {
        if (!ALLOWED_MANAGERIAL_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Managerial role required for supplier selection and PO creation.");
        }
    }

    public List<ComparisonLineDto> rankAndScoreLines(List<ComparisonLineDto> rawLines) {
        if (rawLines == null || rawLines.isEmpty()) {
            return Collections.emptyList();
        }

        List<ComparisonLineDto> scored = new ArrayList<>(rawLines);

        BigDecimal minCost = scored.stream().map(l -> l.effectiveNetUnitCost).min(Comparator.naturalOrder()).orElse(BigDecimal.ONE);
        int minLt = scored.stream().mapToInt(l -> l.leadTimeDays).min().orElse(1);

        for (ComparisonLineDto line : scored) {
            // Cost score (40%): ratio of minCost / effectiveNetUnitCost * 40
            BigDecimal effC = line.effectiveNetUnitCost.compareTo(BigDecimal.ZERO) > 0 ? line.effectiveNetUnitCost : BigDecimal.ONE;
            BigDecimal costFactor = minCost.divide(effC, 4, RoundingMode.HALF_UP).min(BigDecimal.ONE);
            BigDecimal costScore = costFactor.multiply(new BigDecimal("40.00")).setScale(2, RoundingMode.HALF_UP);

            // Reliability score (30%)
            BigDecimal relScore = line.deliveryReliabilityScore.divide(new BigDecimal("100.00"), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("30.00")).setScale(2, RoundingMode.HALF_UP);

            // Lead time score (20%): ratio of minLt / leadTimeDays * 20
            int lt = Math.max(1, line.leadTimeDays);
            BigDecimal ltFactor = BigDecimal.valueOf(minLt).divide(BigDecimal.valueOf(lt), 4, RoundingMode.HALF_UP).min(BigDecimal.ONE);
            BigDecimal ltScore = ltFactor.multiply(new BigDecimal("20.00")).setScale(2, RoundingMode.HALF_UP);

            // Payment terms score (10%)
            int termCap = Math.min(60, line.paymentTermsDays);
            BigDecimal termsScore = BigDecimal.valueOf(termCap).divide(new BigDecimal("60.00"), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("10.00")).setScale(2, RoundingMode.HALF_UP);

            line.compositeScore = costScore.add(relScore).add(ltScore).add(termsScore);
        }

        scored.sort((a, b) -> {
            int cmp = b.compositeScore.compareTo(a.compositeScore);
            return cmp != 0 ? cmp : a.effectiveNetUnitCost.compareTo(b.effectiveNetUnitCost);
        });

        for (int i = 0; i < scored.size(); i++) {
            ComparisonLineDto l = scored.get(i);
            l.compositeRank = i + 1;
            l.isRecommended = (i == 0);
        }

        return scored;
    }

    public ComparisonEvaluationDto createComparisonEvaluation(
            String productId,
            String productName,
            int targetOrderQuantity,
            List<ComparisonLineDto> rawLines,
            String notes) {
        assertProcurementRole();

        String evalId = "sce-" + UUID.randomUUID().toString().substring(0, 8);
        String evalNum = "SCE-" + System.currentTimeMillis();

        List<ComparisonLineDto> rankedLines = rankAndScoreLines(rawLines);

        ComparisonEvaluationDto eval = new ComparisonEvaluationDto(
                evalId,
                evalNum,
                tenantId,
                branchId,
                productId,
                productName,
                targetOrderQuantity > 0 ? targetOrderQuantity : 100,
                "DRAFT",
                null,
                null,
                "",
                userId,
                Instant.now(),
                null,
                notes,
                rankedLines
        );

        evaluationStore.put(evalId, eval);
        return eval;
    }

    public PosPurchaseOrderClient.PurchaseOrderDto selectSupplierAndCreatePo(
            String evaluationId,
            String selectedSupplierId,
            String selectionReason,
            int orderQuantity) {
        assertManagerialRole();

        ComparisonEvaluationDto eval = evaluationStore.get(evaluationId);
        if (eval == null) {
            throw new NoSuchElementException("ERR_EVALUATION_NOT_FOUND: Evaluation " + evaluationId + " not found.");
        }

        if ("ORDER_PLACED".equals(eval.status)) {
            throw new IllegalStateException("ERR_ORDER_ALREADY_PLACED: Order " + eval.createdPoId + " already placed.");
        }

        ComparisonLineDto selectedLine = eval.lines.stream()
                .filter(l -> l.supplierId.equals(selectedSupplierId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("ERR_SUPPLIER_NOT_IN_COMPARISON: Supplier " + selectedSupplierId + " not evaluated."));

        int qty = orderQuantity > 0 ? orderQuantity : eval.targetOrderQuantity;
        if (qty < selectedLine.minimumOrderQuantity) {
            throw new IllegalArgumentException("ERR_BELOW_MINIMUM_ORDER_QTY: Quantity " + qty + " is below MOQ " + selectedLine.minimumOrderQuantity);
        }

        String poId = "po-" + UUID.randomUUID().toString().substring(0, 8);
        String poNum = "PO-" + System.currentTimeMillis();

        int pkgSize = Math.max(1, selectedLine.packageSize);
        int orderedPackages = (int) Math.ceil((double) qty / pkgSize);
        int actualQty = orderedPackages * pkgSize;

        BigDecimal subtotal = selectedLine.unitCostPrice.multiply(BigDecimal.valueOf(actualQty)).setScale(4, RoundingMode.HALF_UP);
        BigDecimal discountFactor = selectedLine.discountPercentage.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        BigDecimal discountAmount = subtotal.multiply(discountFactor).setScale(4, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.subtract(discountAmount).setScale(4, RoundingMode.HALF_UP);

        PosPurchaseOrderClient.PurchaseOrderItemDto poItem = new PosPurchaseOrderClient.PurchaseOrderItemDto(
                null,
                eval.productId,
                eval.productName,
                selectedLine.packageType,
                pkgSize,
                actualQty,
                0,
                selectedLine.unitCostPrice,
                selectedLine.discountPercentage,
                "Rank #" + selectedLine.compositeRank + " (Score " + selectedLine.compositeScore + ")"
        );

        PosPurchaseOrderClient.PurchaseOrderDto po = new PosPurchaseOrderClient.PurchaseOrderDto(
                poId, poNum, tenantId, branchId, "", selectedSupplierId,
                selectedLine.supplierName, null, "DRAFT", selectedLine.paymentTermsDays, "BDT",
                subtotal, discountAmount, BigDecimal.ZERO, total,
                LocalDate.now().plusDays(selectedLine.leadTimeDays),
                null, null, null, null, null, null, null, null, null,
                "Generated from Evaluation " + eval.evaluationNumber + ". Reason: " + selectionReason,
                true, List.of(poItem), Instant.now()
        );

        poStore.put(poId, po);

        eval.status = "ORDER_PLACED";
        eval.selectedSupplierId = selectedSupplierId;
        eval.selectedSupplierName = selectedLine.supplierName;
        eval.selectionReason = selectionReason != null ? selectionReason : "";
        eval.createdPoId = poId;
        eval.evaluatedBy = userId;
        eval.evaluatedAt = Instant.now();

        return po;
    }

    public ComparisonEvaluationDto getEvaluation(String evaluationId) {
        ComparisonEvaluationDto eval = evaluationStore.get(evaluationId);
        if (eval == null) {
            throw new NoSuchElementException("ERR_EVALUATION_NOT_FOUND: Evaluation " + evaluationId + " not found.");
        }
        return eval;
    }

    public List<ComparisonEvaluationDto> listEvaluations(String statusFilter) {
        List<ComparisonEvaluationDto> results = new ArrayList<>();
        for (ComparisonEvaluationDto e : evaluationStore.values()) {
            if (statusFilter != null && !statusFilter.isEmpty() && !statusFilter.equalsIgnoreCase(e.status)) {
                continue;
            }
            results.add(e);
        }
        return results;
    }
}
