package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConnectivityMonitorTest (P309 / ADR-0309)
 * Proves non-blocking async connectivity probing (UI thread never stalls),
 * deterministic CONNECTED / DEGRADED / DISCONNECTED / RECONNECTED transitions,
 * honest cloudStockAuthoritative=false during offline/degraded states, and
 * accurate pending outbox + last-sync reporting persisted across SQLite reopens.
 */
class ConnectivityMonitorTest {

    @Test
    void testNonBlockingProbeTransitionsAndAccurateLastSyncPersistence(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("pos_p309_conn.db");
        String fp = "7".repeat(64);
        ExecutorService bg = Executors.newSingleThreadExecutor();

        try {
            // 1. Verify probeAsync NEVER blocks caller thread even when network stalls 200ms!
            long startNs = System.nanoTime();
            CompletableFuture<ConnectivityMonitor.ProbeOutcome> fut = ConnectivityMonitor.probeAsync(() -> {
                Thread.sleep(200);
                return new ConnectivityMonitor.ProbeOutcome(true, 120, new BigDecimal("0.00"));
            }, bg);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            assertTrue(elapsedMs < 80, "UI caller thread must not block during network probe; took " + elapsedMs + "ms");
            ConnectivityMonitor.ProbeOutcome asyncOutcome = fut.get(3, TimeUnit.SECONDS);
            assertTrue(asyncOutcome.succeeded);

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
                DeviceLocalDatabaseSchema.initializeAndBind(
                        conn, "DEV-CN-01", "ten-cn-1", "br-cn-1", fp, "CONTROLLED_RECONCILIATION");
                try (Statement st = conn.createStatement()) {
                    st.execute("INSERT INTO cached_catalog_medicines VALUES ('med-1','ten-cn-1','Napa','Paracetamol','TABLET','10.00',0,0,1,1,'2026-11-18T00:00:00Z')");
                    st.execute("INSERT INTO cached_branch_stock VALUES ('bat-1','ten-cn-1','br-cn-1','med-1','B1','2028-12-31',25,0,'5.00','ACTIVE',1,'2026-11-18T00:00:00Z')");
                }

                // Initial DISCONNECTED -> RECONNECTED -> CONNECTED
                var s1 = ConnectivityMonitor.recordProbeAndBuildStatus(conn, asyncOutcome, "2026-11-18T11:30:00Z");
                assertEquals(ConnectivityMonitor.State.RECONNECTED, s1.state);
                assertTrue(s1.cloudStockAuthoritative);

                var s2 = ConnectivityMonitor.recordProbeAndBuildStatus(
                        conn, new ConnectivityMonitor.ProbeOutcome(true, 80, new BigDecimal("0.00")), null);
                assertEquals(ConnectivityMonitor.State.CONNECTED, s2.state);

                // Enqueue an offline sale while network degrades & drops
                DeviceLocalDatabaseSchema.enqueueOfflineSaleAtomically(
                        conn, "DEV-CN-01", "ten-cn-1", "br-cn-1", "bat-1", 3,
                        new BigDecimal("30.00"), "idem-cn-off-1", "OFF-CN-1", "{\"qty\":3}");

                // 1st failure -> DEGRADED
                var s3 = ConnectivityMonitor.recordProbeAndBuildStatus(
                        conn, new ConnectivityMonitor.ProbeOutcome(false, 0, new BigDecimal("100.00")), null);
                assertEquals(ConnectivityMonitor.State.DEGRADED, s3.state);
                assertFalse(s3.cloudStockAuthoritative);
                assertEquals(1, s3.pendingOutboxCount);
                assertEquals(new BigDecimal("30.00"), s3.pendingOutboxBdt);

                // 2nd consecutive failure -> DISCONNECTED
                var s4 = ConnectivityMonitor.recordProbeAndBuildStatus(
                        conn, new ConnectivityMonitor.ProbeOutcome(false, 0, new BigDecimal("100.00")), null);
                assertEquals(ConnectivityMonitor.State.DISCONNECTED, s4.state);
                assertFalse(s4.cloudStockAuthoritative);
                assertTrue(s4.labelBn.contains("অফলাইন"));
            }

            // 2. Crash / Restart durability: Reopen SQLite and verify exact state & last_successful_sync_at survived!
            try (Connection reopened = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
                var persisted = ConnectivityMonitor.readCurrentStatus(reopened);
                assertEquals(ConnectivityMonitor.State.DISCONNECTED, persisted.state);
                assertEquals("2026-11-18T11:30:00Z", persisted.lastSuccessfulSyncAt);
                assertEquals(1, persisted.pendingOutboxCount);
                assertEquals(new BigDecimal("30.00"), persisted.pendingOutboxBdt);

                // Reconnect -> RECONNECTED (still not authoritative until pending outbox drains!)
                var s5 = ConnectivityMonitor.recordProbeAndBuildStatus(
                        reopened, new ConnectivityMonitor.ProbeOutcome(true, 95, new BigDecimal("0.00")), null);
                assertEquals(ConnectivityMonitor.State.RECONNECTED, s5.state);
                assertFalse(s5.cloudStockAuthoritative);
            }
        } finally {
            bg.shutdownNow();
        }
    }
}
