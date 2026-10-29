package com.pharmacy.pos.services;

import com.pharmacy.pos.models.SaleTransaction;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Local durable outbox queue manager for offline-first resilience.
 * Architecture Decision: ADR-0003 & ADR-0023.
 */
public class OfflineSyncEngine {
    private final Deque<SaleTransaction> outboxQueue = new ArrayDeque<>();
    private boolean isOnline = true;

    public synchronized void setNetworkOnline(boolean online) {
        this.isOnline = online;
    }

    public synchronized boolean isOnline() {
        return isOnline;
    }

    public synchronized void enqueueTransaction(SaleTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction cannot be null");
        outboxQueue.addLast(transaction);
    }

    public synchronized int getPendingCount() {
        return outboxQueue.size();
    }

    public synchronized List<SaleTransaction> getPendingTransactions() {
        return Collections.unmodifiableList(outboxQueue.stream().collect(Collectors.toList()));
    }

    public synchronized SaleTransaction drainNext() {
        return outboxQueue.pollFirst();
    }
}

