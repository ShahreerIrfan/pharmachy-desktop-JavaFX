package com.pharmacy.pos.offline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.Instant;
import java.util.concurrent.*;

/**
 * ConnectivityMonitor
 * Architecture Decision: ADR-0309 (P309).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Non-blocking connectivity detector for JavaFX POS terminals:
 * - Executes network probes asynchronously on a daemon executor so the JavaFX Application Thread
 *   is NEVER blocked even during stalled sockets or ISP blackholes.
 * - Classifies connectivity into CONNECTED, DEGRADED, DISCONNECTED, and RECONNECTED.
 * - Persists state and accurate last-sync metadata (joined with `pending_offline_commands` &
 *   `sync_watermarks`) in local SQLite (`device_connectivity_status`) so status survives crashes/restarts.
 * - Never claims authoritative cloud stock while DISCONNECTED or DEGRADED.
 */
public class ConnectivityMonitor {

    public enum State {
        CONNECTED, DEGRADED, DISCONNECTED, RECONNECTED
    }

    public interface ProbeSupplier {
        ProbeOutcome probe() throws Exception;
    }

    public static final class ProbeOutcome {
        public final boolean succeeded;
        public final int latencyMs;
        public final BigDecimal packetLossPct;

        public ProbeOutcome(boolean succeeded, int latencyMs, BigDecimal packetLossPct) {
            this.succeeded = succeeded;
            this.latencyMs = Math.max(0, latencyMs);
            this.packetLossPct = packetLossPct != null ? packetLossPct.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
    }

    public static final class LocalConnectivityStatus {
        public final State state;
        public final boolean cloudStockAuthoritative;
        public final int latencyMs;
        public final int consecutiveFailures;
        public final int pendingOutboxCount;
        public final BigDecimal pendingOutboxBdt;
        public final long lastPullSequence;
        public final long lastPushAckSequence;
        public final String lastSuccessfulSyncAt;
        public final String labelEn;
        public final String labelBn;

        public LocalConnectivityStatus(
                State state,
                boolean cloudStockAuthoritative,
                int latencyMs,
                int consecutiveFailures,
                int pendingOutboxCount,
                BigDecimal pendingOutboxBdt,
                long lastPullSequence,
                long lastPushAckSequence,
                String lastSuccessfulSyncAt,
                String labelEn,
                String labelBn
        ) {
            this.state = state;
            this.cloudStockAuthoritative = cloudStockAuthoritative;
            this.latencyMs = latencyMs;
            this.consecutiveFailures = consecutiveFailures;
            this.pendingOutboxCount = pendingOutboxCount;
            this.pendingOutboxBdt = pendingOutboxBdt;
            this.lastPullSequence = lastPullSequence;
            this.lastPushAckSequence = lastPushAckSequence;
            this.lastSuccessfulSyncAt = lastSuccessfulSyncAt;
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    private static final BigDecimal DEGRADED_LOSS_PCT = new BigDecimal("10.00");

    public static void ensureConnectivityTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                    "CREATE TABLE IF NOT EXISTS device_connectivity_status (" +
                            "singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1), " +
                            "connectivity_state TEXT NOT NULL DEFAULT 'DISCONNECTED', " +
                            "latency_ms INTEGER NOT NULL DEFAULT 0 CHECK (latency_ms >= 0), " +
                            "consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0), " +
                            "degraded_latency_threshold_ms INTEGER NOT NULL DEFAULT 1500, " +
                            "last_successful_sync_at TEXT NOT NULL DEFAULT '', " +
                            "updated_at TEXT NOT NULL)"
            );
            st.execute(
                    "INSERT OR IGNORE INTO device_connectivity_status " +
                            "(singleton_id, connectivity_state, latency_ms, consecutive_failures, degraded_latency_threshold_ms, last_successful_sync_at, updated_at) " +
                            "VALUES (1, 'DISCONNECTED', 0, 0, 1500, '', '" + Instant.now() + "')"
            );
        }
    }

    public static State classify(State prev, ProbeOutcome outcome, int consecutiveFailures, int degradedLatencyMs) {
        if (!outcome.succeeded) {
            return consecutiveFailures >= 2 ? State.DISCONNECTED : State.DEGRADED;
        }
        if (outcome.latencyMs >= degradedLatencyMs || outcome.packetLossPct.compareTo(DEGRADED_LOSS_PCT) >= 0) {
            return State.DEGRADED;
        }
        if (prev == State.DISCONNECTED || prev == State.DEGRADED) {
            return State.RECONNECTED;
        }
        return State.CONNECTED;
    }

    /**
     * Runs a connectivity probe strictly off the UI thread with zero UI blocking.
     */
    public static CompletableFuture<ProbeOutcome> probeAsync(ProbeSupplier supplier, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.probe();
            } catch (Exception ex) {
                return new ProbeOutcome(false, 0, new BigDecimal("100.00"));
            }
        }, executor);
    }

    public static LocalConnectivityStatus recordProbeAndBuildStatus(
            Connection conn,
            ProbeOutcome outcome,
            String syncedAtOverrideIso
    ) throws SQLException {
        ensureConnectivityTable(conn);
        SyncCursorCheckpointManager.ensureWatermarkTable(conn);

        State prev = State.DISCONNECTED;
        int prevFailures = 0;
        int degradedThresh = 1500;
        String lastSyncAt = "";

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT connectivity_state, consecutive_failures, degraded_latency_threshold_ms, last_successful_sync_at " +
                        "FROM device_connectivity_status WHERE singleton_id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                prev = State.valueOf(rs.getString(1));
                prevFailures = rs.getInt(2);
                degradedThresh = rs.getInt(3);
                lastSyncAt = rs.getString(4);
            }
        }

        int nextFailures = outcome.succeeded ? 0 : (prevFailures + 1);
        State nextState = classify(prev, outcome, nextFailures, degradedThresh);
        if (syncedAtOverrideIso != null && !syncedAtOverrideIso.isBlank()) {
            lastSyncAt = syncedAtOverrideIso;
        }

        try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE device_connectivity_status SET connectivity_state = ?, latency_ms = ?, " +
                        "consecutive_failures = ?, last_successful_sync_at = ?, updated_at = ? WHERE singleton_id = 1")) {
            upd.setString(1, nextState.name());
            upd.setInt(2, outcome.latencyMs);
            upd.setInt(3, nextFailures);
            upd.setString(4, lastSyncAt);
            upd.setString(5, Instant.now().toString());
            upd.executeUpdate();
        }

        return readCurrentStatus(conn);
    }

    public static LocalConnectivityStatus readCurrentStatus(Connection conn) throws SQLException {
        ensureConnectivityTable(conn);
        SyncCursorCheckpointManager.WatermarkSnapshot wm = SyncCursorCheckpointManager.readWatermarks(conn);

        State state = State.DISCONNECTED;
        int latencyMs = 0;
        int failures = 0;
        String lastSyncAt = "";
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT connectivity_state, latency_ms, consecutive_failures, last_successful_sync_at " +
                        "FROM device_connectivity_status WHERE singleton_id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                state = State.valueOf(rs.getString(1));
                latencyMs = rs.getInt(2);
                failures = rs.getInt(3);
                lastSyncAt = rs.getString(4);
            }
        }

        int pendingCount = 0;
        BigDecimal pendingBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT monetary_total_bdt FROM pending_offline_commands WHERE sync_state != 'SYNCED'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                pendingCount++;
                pendingBdt = pendingBdt.add(new BigDecimal(rs.getString(1)));
            }
        }
        pendingBdt = pendingBdt.setScale(2, RoundingMode.HALF_UP);

        boolean authoritative = (state == State.CONNECTED || state == State.RECONNECTED) && pendingCount == 0;
        String labelEn;
        String labelBn;
        switch (state) {
            case DISCONNECTED -> {
                labelEn = "Offline — Local Queue Active (" + pendingCount + " pending)";
                labelBn = "অফলাইন — লোকাল কিউ সক্রিয় (" + pendingCount + "টি পেন্ডিং)";
            }
            case DEGRADED -> {
                labelEn = "Degraded Network — Sync Delayed (" + pendingCount + " pending)";
                labelBn = "দুর্বল নেটওয়ার্ক — সিঙ্ক বিলম্বিত (" + pendingCount + "টি পেন্ডিং)";
            }
            case RECONNECTED -> {
                labelEn = "Reconnected — Catch-up Sync Ready (" + pendingCount + " pending)";
                labelBn = "পুনরায় সংযুক্ত — সিঙ্ক প্রস্তুত (" + pendingCount + "টি পেন্ডিং)";
            }
            default -> {
                labelEn = "Connected — Cloud Sync Healthy";
                labelBn = "সংযুক্ত — ক্লাউড সিঙ্ক স্বাভাবিক";
            }
        }

        return new LocalConnectivityStatus(
                state, authoritative, latencyMs, failures, pendingCount, pendingBdt,
                wm.pullAppliedSequenceNo, wm.pushAckedSequenceNo, lastSyncAt, labelEn, labelBn
        );
    }
}
