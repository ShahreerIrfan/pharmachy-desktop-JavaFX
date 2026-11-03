package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * PosPackagingHierarchyClient
 * Architecture Decision: ADR-0162 (P162).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Client-side packaging hierarchy tree resolution and effective-dated conversion engine
 * for JavaFX Desktop POS.
 */
public class PosPackagingHierarchyClient {

    public static final class PackagingNode {
        public final String nodeId;
        public final String productId;
        public final String parentUnit;
        public final String childUnit;
        public final int conversionFactor;
        public final int tierLevel;
        public final boolean isBaseLevel;
        public final LocalDate effectiveFrom;
        public final LocalDate effectiveTo;
        public final boolean isActive;

        public PackagingNode(
                String nodeId,
                String productId,
                String parentUnit,
                String childUnit,
                int conversionFactor,
                int tierLevel,
                boolean isBaseLevel,
                LocalDate effectiveFrom,
                LocalDate effectiveTo,
                boolean isActive) {
            this.nodeId = nodeId;
            this.productId = productId;
            this.parentUnit = parentUnit != null ? parentUnit.trim().toUpperCase() : "";
            this.childUnit = childUnit != null ? childUnit.trim().toUpperCase() : "";
            this.conversionFactor = Math.max(1, conversionFactor);
            this.tierLevel = Math.max(1, tierLevel);
            this.isBaseLevel = isBaseLevel;
            this.effectiveFrom = effectiveFrom;
            this.effectiveTo = effectiveTo;
            this.isActive = isActive;
        }

        public boolean isEffectiveAt(LocalDate targetDate) {
            if (!isActive || targetDate == null) {
                return false;
            }
            if (effectiveFrom != null && targetDate.isBefore(effectiveFrom)) {
                return false;
            }
            if (effectiveTo != null && targetDate.isAfter(effectiveTo)) {
                return false;
            }
            return true;
        }
    }

    public static final class HierarchyTree {
        public final String productId;
        public final LocalDate asOfDate;
        public final String baseUnit;
        public final List<PackagingNode> chain;
        public final Map<String, Integer> cumulativeFactors;

        public HierarchyTree(String productId, LocalDate asOfDate, String baseUnit, List<PackagingNode> chain, Map<String, Integer> cumulativeFactors) {
            this.productId = productId;
            this.asOfDate = asOfDate;
            this.baseUnit = baseUnit;
            this.chain = chain != null ? Collections.unmodifiableList(chain) : Collections.emptyList();
            this.cumulativeFactors = cumulativeFactors != null ? Collections.unmodifiableMap(cumulativeFactors) : Collections.emptyMap();
        }
    }

    public static final class HierarchyConversionResult {
        public final boolean success;
        public final String errorCode;
        public final String errorMessageEn;
        public final String errorMessageBn;
        public final BigDecimal inputQuantity;
        public final BigDecimal convertedQuantity;
        public final BigDecimal conversionRatio;
        public final String fromUnit;
        public final String toUnit;
        public final String baseUnit;

        public HierarchyConversionResult(
                boolean success,
                String errorCode,
                String errorMessageEn,
                String errorMessageBn,
                BigDecimal inputQuantity,
                BigDecimal convertedQuantity,
                BigDecimal conversionRatio,
                String fromUnit,
                String toUnit,
                String baseUnit) {
            this.success = success;
            this.errorCode = errorCode;
            this.errorMessageEn = errorMessageEn;
            this.errorMessageBn = errorMessageBn;
            this.inputQuantity = inputQuantity != null ? inputQuantity : BigDecimal.ZERO;
            this.convertedQuantity = convertedQuantity != null ? convertedQuantity : BigDecimal.ZERO;
            this.conversionRatio = conversionRatio != null ? conversionRatio : BigDecimal.ZERO;
            this.fromUnit = fromUnit;
            this.toUnit = toUnit;
            this.baseUnit = baseUnit;
        }

        public static HierarchyConversionResult failure(String errorCode, String en, String bn) {
            return new HierarchyConversionResult(false, errorCode, en, bn, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null);
        }

        public static HierarchyConversionResult success(BigDecimal inputQty, BigDecimal convertedQty, BigDecimal ratio, String from, String to, String base) {
            return new HierarchyConversionResult(true, null, null, null, inputQty, convertedQty, ratio, from, to, base);
        }
    }

    public boolean canConfigurePackaging(String userRole) {
        if (userRole == null || userRole.trim().isEmpty()) {
            return false;
        }
        return !"CASHIER".equalsIgnoreCase(userRole.trim());
    }

    public boolean hasCycle(String parent, String child, List<PackagingNode> nodes) {
        if (parent == null || child == null) {
            return false;
        }
        String p = parent.trim().toUpperCase();
        String c = child.trim().toUpperCase();
        if (p.equals(c)) {
            return true;
        }

        Map<String, Set<String>> graph = new HashMap<>();
        if (nodes != null) {
            for (PackagingNode node : nodes) {
                if (node.isActive) {
                    graph.computeIfAbsent(node.parentUnit, k -> new HashSet<>()).add(node.childUnit);
                }
            }
        }

        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(c);

        while (!queue.isEmpty()) {
            String curr = queue.poll();
            if (p.equals(curr)) {
                return true;
            }
            if (visited.add(curr)) {
                Set<String> next = graph.get(curr);
                if (next != null) {
                    queue.addAll(next);
                }
            }
        }
        return false;
    }

    public boolean hasDateOverlap(String parent, String child, LocalDate from, LocalDate to, List<PackagingNode> nodes, String excludeNodeId) {
        if (nodes == null) return false;
        String p = parent.trim().toUpperCase();
        String c = child.trim().toUpperCase();

        for (PackagingNode n : nodes) {
            if (!n.isActive) continue;
            if (excludeNodeId != null && excludeNodeId.equals(n.nodeId)) continue;
            if (!n.parentUnit.equals(p) || !n.childUnit.equals(c)) continue;

            LocalDate exFrom = n.effectiveFrom;
            LocalDate exTo = n.effectiveTo;
            LocalDate latestStart = (from.isAfter(exFrom)) ? from : exFrom;

            if (to != null && exTo != null) {
                LocalDate earliestEnd = (to.isBefore(exTo)) ? to : exTo;
                if (!latestStart.isAfter(earliestEnd)) return true;
            } else if (to != null) {
                if (!latestStart.isAfter(to)) return true;
            } else if (exTo != null) {
                if (!latestStart.isAfter(exTo)) return true;
            } else {
                return true; // Both open-ended
            }
        }
        return false;
    }

    public HierarchyTree resolvePackagingTree(String productId, List<PackagingNode> nodes, LocalDate asOfDate) {
        LocalDate targetDate = asOfDate != null ? asOfDate : LocalDate.now();
        List<PackagingNode> activeNodes = new ArrayList<>();
        if (nodes != null) {
            for (PackagingNode n : nodes) {
                if (n.productId.equals(productId) && n.isEffectiveAt(targetDate)) {
                    activeNodes.add(n);
                }
            }
        }

        if (activeNodes.isEmpty()) {
            return new HierarchyTree(productId, targetDate, "PIECE", Collections.emptyList(), Collections.emptyMap());
        }

        Map<String, PackagingNode> parentMap = new HashMap<>();
        Set<String> children = new HashSet<>();
        for (PackagingNode n : activeNodes) {
            parentMap.put(n.parentUnit, n);
            children.add(n.childUnit);
        }

        String root = null;
        for (String p : parentMap.keySet()) {
            if (!children.contains(p)) {
                root = p;
                break;
            }
        }
        if (root == null) {
            root = parentMap.keySet().iterator().next();
        }

        List<PackagingNode> chain = new ArrayList<>();
        String curr = root;
        Set<String> visited = new HashSet<>();
        while (parentMap.containsKey(curr) && visited.add(curr)) {
            PackagingNode n = parentMap.get(curr);
            chain.add(n);
            curr = n.childUnit;
        }

        String baseUnit = curr;
        Map<String, Integer> cumulativeFactors = new HashMap<>();
        cumulativeFactors.put(baseUnit, 1);
        int acc = 1;
        for (int i = chain.size() - 1; i >= 0; i--) {
            PackagingNode n = chain.get(i);
            acc *= n.conversionFactor;
            cumulativeFactors.put(n.parentUnit, acc);
        }

        return new HierarchyTree(productId, targetDate, baseUnit, chain, cumulativeFactors);
    }

    public HierarchyConversionResult convertPackagingTier(
            String productId,
            String fromUnit,
            String toUnit,
            BigDecimal quantity,
            List<PackagingNode> nodes,
            LocalDate asOfDate) {

        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return HierarchyConversionResult.failure(
                    "ERR_INVALID_QUANTITY",
                    "Quantity must be strictly positive.",
                    "পরিমাণ অবশ্যই ধনাত্মক হতে হবে।"
            );
        }
        if (fromUnit == null || toUnit == null) {
            return HierarchyConversionResult.failure(
                    "ERR_UNITS_REQUIRED",
                    "from_unit and to_unit are required.",
                    "উভয় ইউনিট প্রদান করা আবশ্যক।"
            );
        }

        String from = fromUnit.trim().toUpperCase();
        String to = toUnit.trim().toUpperCase();
        HierarchyTree tree = resolvePackagingTree(productId, nodes, asOfDate);

        Integer fromFactor = tree.cumulativeFactors.get(from);
        Integer toFactor = tree.cumulativeFactors.get(to);

        if (fromFactor == null || toFactor == null) {
            return HierarchyConversionResult.failure(
                    "ERR_UNLINKED_PACKAGING_TIER",
                    "Units '" + from + "' and/or '" + to + "' are not part of the active packaging tree.",
                    "'" + from + "' অথবা '" + to + "' প্যাকেজিং ট্রির অংশ নয়।"
            );
        }

        BigDecimal fromMult = BigDecimal.valueOf(fromFactor);
        BigDecimal toMult = BigDecimal.valueOf(toFactor);
        BigDecimal baseQty = quantity.multiply(fromMult);
        BigDecimal convertedQty = baseQty.divide(toMult, 4, RoundingMode.HALF_UP);
        BigDecimal ratio = fromMult.divide(toMult, 4, RoundingMode.HALF_UP);

        return HierarchyConversionResult.success(quantity, convertedQty, ratio, from, to, tree.baseUnit);
    }
}

