package com.pharmacy.pos.offline;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * DeviceRevocationGuard
 * Architecture Decision: ADR-0319 (P319).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Implements client-side workstation security lockdown upon device revocation:
 * - Locks POS workstation and rejects any new checkout/sales actions.
 * - Displays clear bilingual security lockdown warnings.
 * - Retains unsynced local outbox commands in immutable forensic mode for owner recovery.
 */
public class DeviceRevocationGuard {

    private boolean workstationLocked = false;
    private String lockdownReason = "";

    public boolean isWorkstationLocked() {
        return workstationLocked;
    }

    public String getLockdownReason() {
        return lockdownReason;
    }

    public String getLockdownBannerMessage() {
        return "[SECURITY LOCKDOWN] This POS terminal has been revoked or reported compromised. " +
                "New sales and protected actions are blocked. Contact the pharmacy owner.\n" +
                "[নিরাপত্তা লকডাউন] এই পিওএস টার্মিনালটি প্রত্যাহার বা বাতিল করা হয়েছে। বিক্রয় ও লেনদেন বন্ধ রয়েছে।";
    }

    /**
     * Enforces lockdown: updates local SQLite lease to REVOKED and locks POS actions.
     */
    public void lockWorkstation(Connection conn, String reason) throws SQLException {
        this.workstationLocked = true;
        this.lockdownReason = reason != null ? reason : "Administrative revocation";

        if (conn != null && !conn.isClosed()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE subscription_leases SET status = 'REVOKED' WHERE status = 'ACTIVE'")) {
                ps.executeUpdate();
            }
        }
    }

    /**
     * Asserts that workstation is active; throws SecurityException if locked.
     */
    public void assertWorkstationOperational() {
        if (workstationLocked) {
            throw new SecurityException("ERR_WORKSTATION_REVOKED: POS workstation is locked due to device revocation. " +
                    "Reason: " + lockdownReason);
        }
    }

    /**
     * Extracts unsynced outbox transactions as evidence payload for cloud recovery dossier.
     */
    public Map<String, Object> extractUnsyncedOutboxEvidence(Connection conn) throws SQLException {
        Map<String, Object> evidence = new HashMap<>();
        List<Map<String, Object>> unsyncedList = new ArrayList<>();

        if (conn != null && !conn.isClosed()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT command_id, idempotency_key, client_sequence, command_type, aggregate_ref, " +
                            "monetary_total_bdt, payload_json, created_at FROM pending_offline_commands " +
                            "WHERE sync_state != 'SYNCED' ORDER BY client_sequence ASC")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("command_id", rs.getString("command_id"));
                        item.put("idempotency_key", rs.getString("idempotency_key"));
                        item.put("client_sequence", rs.getLong("client_sequence"));
                        item.put("command_type", rs.getString("command_type"));
                        item.put("aggregate_ref", rs.getString("aggregate_ref"));
                        item.put("monetary_total_bdt", rs.getString("monetary_total_bdt"));
                        item.put("payload_json", rs.getString("payload_json"));
                        item.put("created_at", rs.getString("created_at"));
                        unsyncedList.add(item);
                    }
                }
            }
        }

        evidence.put("unsynced_command_count", unsyncedList.size());
        evidence.put("commands", unsyncedList);
        evidence.put("extracted_at", java.time.Instant.now().toString());
        return evidence;
    }
}
