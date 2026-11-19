package com.pharmacy.pos.offline;

import java.util.*;

/**
 * EventOrderingBarrierManager
 * Architecture Decision: ADR-0316 (P316).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Client-side causal event ordering and dependency barrier manager:
 * 1. Topologically sorts offline events before cloud push so parent events execute before dependents:
 *    - Customer Creation -> Sale
 *    - Goods Receipt (GRN / Batch) -> Sale
 *    - Sale -> Return / Refund
 * 2. Detects circular dependencies and halts malformed outbox batches (ERR_CIRCULAR_DEPENDENCY_BARRIER).
 * 3. Enforces transactional ordering barriers to guarantee zero orphaned records upon cloud replay.
 */
public class EventOrderingBarrierManager {

    public enum DependencyRule {
        CUSTOMER_BEFORE_SALE,
        GRN_BEFORE_SALE,
        SALE_BEFORE_RETURN,
        STRICT_SEQUENCE
    }

    public static final class OfflineEventNode {
        public final String commandId;
        public final String commandType;
        public final int clientSequence;
        public final List<String> dependsOnCommandIds;

        public OfflineEventNode(
                String commandId,
                String commandType,
                int clientSequence,
                List<String> dependsOnCommandIds
        ) {
            this.commandId = Objects.requireNonNull(commandId, "commandId cannot be null");
            this.commandType = Objects.requireNonNull(commandType, "commandType cannot be null");
            this.clientSequence = clientSequence;
            this.dependsOnCommandIds = dependsOnCommandIds != null
                    ? Collections.unmodifiableList(new ArrayList<>(dependsOnCommandIds))
                    : Collections.emptyList();
        }
    }

    public static List<OfflineEventNode> topologicalSort(List<OfflineEventNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, OfflineEventNode> nodeMap = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();

        for (OfflineEventNode n : nodes) {
            nodeMap.put(n.commandId, n);
            inDegree.put(n.commandId, 0);
            adj.put(n.commandId, new ArrayList<>());
        }

        for (OfflineEventNode n : nodes) {
            for (String parentId : n.dependsOnCommandIds) {
                if (nodeMap.containsKey(parentId)) {
                    adj.get(parentId).add(n.commandId);
                    inDegree.put(n.commandId, inDegree.get(n.commandId) + 1);
                }
            }
        }

        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<OfflineEventNode> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            sorted.add(nodeMap.get(curr));

            for (String neighbor : adj.get(curr)) {
                inDegree.put(neighbor, inDegree.get(neighbor) - 1);
                if (inDegree.get(neighbor) == 0) {
                    queue.add(neighbor);
                }
            }
        }

        if (sorted.size() != nodes.size()) {
            throw new IllegalArgumentException("ERR_CIRCULAR_DEPENDENCY_BARRIER: Cycle detected in offline event dependency graph.");
        }

        return sorted;
    }

    public static boolean verifyCausalOrdering(List<OfflineEventNode> sortedNodes) {
        Set<String> observedSoFar = new HashSet<>();

        for (OfflineEventNode n : sortedNodes) {
            for (String dep : n.dependsOnCommandIds) {
                // If dependency is part of the batch, it MUST appear prior to this event
                if (!observedSoFar.contains(dep)) {
                    return false;
                }
            }
            observedSoFar.add(n.commandId);
        }
        return true;
    }
}
