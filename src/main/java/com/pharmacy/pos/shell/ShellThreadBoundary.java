package com.pharmacy.pos.shell;

import javafx.application.Platform;

import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * ShellThreadBoundary - Thread boundary manager for JavaFX POS Client.
 * Architecture Decision: ADR-0321 (P321).
 *
 * Guarantees that:
 * 1. All UI state mutations are dispatched on the JavaFX Application Thread.
 * 2. All blocking network, SQLite transactions, and hardware I/O execute on background worker threads.
 */
public class ShellThreadBoundary {

    private static final ExecutorService BACKGROUND_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "javafx-shell-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * Executes the given runnable on the JavaFX Application Thread.
     */
    public static void runOnFxThread(Runnable action) {
        if (action == null) return;
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /**
     * Executes a supplier asynchronously on background worker thread.
     */
    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, BACKGROUND_EXECUTOR);
    }

    /**
     * Executes a runnable asynchronously on background worker thread.
     */
    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return CompletableFuture.runAsync(runnable, BACKGROUND_EXECUTOR);
    }

    /**
     * Shuts down background executor safely.
     */
    public static void shutdown() {
        BACKGROUND_EXECUTOR.shutdown();
    }
}
