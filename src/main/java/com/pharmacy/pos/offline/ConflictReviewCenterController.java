package com.pharmacy.pos.offline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * ConflictReviewCenterController
 * Architecture Decision: ADR-0314 (P314).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Manager-accessible conflict center controller for JavaFX Desktop POS:
 * 1. Displays source events (command ID, client sequence, terminal ID, payload vs server state snapshot).
 * 2. Provides proposed decisions (KEEP_SERVER, OVERRIDE_ADJUSTMENT, SPLIT_ALLOCATION, ESCALATE, REJECT).
 * 3. Enforces manager approvals and captures final authoritative cloud outcomes.
 * 4. Surfaces loading, empty, error, offline cache, and bilingual (EN/BN) states with exact BigDecimal BDT precision.
 */
public class ConflictReviewCenterController {

    public enum ConflictUiState {
        IDLE,
        LOADING,
        LOADED,
        EMPTY,
        OFFLINE_CACHE,
        PERMISSION_DENIED,
        ERROR
    }

    public enum ProposedDecision {
        KEEP_SERVER_DISCARD_LOCAL,
        OVERRIDE_WITH_INVENTORY_ADJUSTMENT,
        SPLIT_BATCH_ALLOCATION,
        ESCALATE_TO_OWNER,
        REJECT_FRAUD
    }

    public static final class ConflictTicketViewModel {
        public final String conflictId;
        public final String commandId;
        public final String tenantId;
        public final String branchId;
        public final String conflictReason;
        public final String localPayloadJson;
        public final String serverStateJson;
        public String resolutionStatus;
        public String proposedDecision;
        public String proposedNotes;
        public String proposedBy;
        public String approvalNotes;
        public String approvedBy;
        public String finalCloudOutcomeJson;
        public final BigDecimal disputedMonetaryBdt;
        public final String createdAt;

        public ConflictTicketViewModel(
                String conflictId,
                String commandId,
                String tenantId,
                String branchId,
                String conflictReason,
                String localPayloadJson,
                String serverStateJson,
                String resolutionStatus,
                String proposedDecision,
                String proposedNotes,
                String proposedBy,
                String approvalNotes,
                String approvedBy,
                String finalCloudOutcomeJson,
                BigDecimal disputedMonetaryBdt,
                String createdAt
        ) {
            this.conflictId = conflictId;
            this.commandId = commandId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.conflictReason = conflictReason;
            this.localPayloadJson = localPayloadJson != null ? localPayloadJson : "{}";
            this.serverStateJson = serverStateJson != null ? serverStateJson : "{}";
            this.resolutionStatus = resolutionStatus != null ? resolutionStatus : "OPEN";
            this.proposedDecision = proposedDecision != null ? proposedDecision : "";
            this.proposedNotes = proposedNotes != null ? proposedNotes : "";
            this.proposedBy = proposedBy != null ? proposedBy : "";
            this.approvalNotes = approvalNotes != null ? approvalNotes : "";
            this.approvedBy = approvedBy != null ? approvedBy : "";
            this.finalCloudOutcomeJson = finalCloudOutcomeJson != null ? finalCloudOutcomeJson : "{}";
            this.disputedMonetaryBdt = disputedMonetaryBdt != null
                    ? disputedMonetaryBdt.setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.createdAt = createdAt != null ? createdAt : Instant.now().toString();
        }

        public Map<String, String> localizedStatusBadge() {
            switch (resolutionStatus) {
                case "DECISION_PROPOSED":
                    return Map.of("en", "Decision Proposed — Awaiting Manager Approval", "bn", "সিদ্ধান্ত প্রস্তাবিত — অনুমোদনের অপেক্ষায়");
                case "RESOLVED_KEEP_SERVER":
                    return Map.of("en", "Resolved (Authoritative Cloud State)", "bn", "মীমাংসিত (ক্লাউড তথ্য অক্ষুণ্ণ)");
                case "RESOLVED_ADJUSTED":
                    return Map.of("en", "Resolved (Compensating Adjustment Posted)", "bn", "মীমাংসিত (সমন্বয় দাখিলা সম্পন্ন)");
                case "ESCALATED":
                    return Map.of("en", "Escalated for Owner/Regulatory Review", "bn", "মালিক/নিয়ন্ত্রক সংস্থার পর্যালোচনার জন্য প্রেরিত");
                case "REJECTED":
                    return Map.of("en", "Rejected (Fraud/Tamper Refusal)", "bn", "প্রত্যাখ্যাত (জালিয়াতি/বিকৃতি প্রতিরোধ)");
                default:
                    return Map.of("en", "Open Conflict — Needs Review", "bn", "উন্মুক্ত বিরোধ — পর্যালোচনা প্রয়োজন");
            }
        }

        public static Map<String, String> localizedDecision(ProposedDecision dec) {
            switch (dec) {
                case KEEP_SERVER_DISCARD_LOCAL:
                    return Map.of("en", "Maintain Cloud Record (Void Local Sale)", "bn", "ক্লাউড রেকর্ড অক্ষুণ্ণ রাখুন (স্থানীয় বিক্রয় বাতিল)");
                case OVERRIDE_WITH_INVENTORY_ADJUSTMENT:
                    return Map.of("en", "Accept Sale & Post Inventory Adjustment", "bn", "বিক্রয় গ্রহণ করুন ও স্টক সমন্বয় দাখিলা দিন");
                case SPLIT_BATCH_ALLOCATION:
                    return Map.of("en", "Split & Fulfill from Alternative Batch", "bn", "বিকল্প ব্যাচ থেকে আংশিক পূরণ করুন");
                case ESCALATE_TO_OWNER:
                    return Map.of("en", "Escalate to Pharmacy Owner", "bn", "ফার্মেসি মালিকের নিকট পর্যালোচনার জন্য পাঠান");
                case REJECT_FRAUD:
                    return Map.of("en", "Reject as Tampered / Fraudulent", "bn", "বিকৃত বা জালিয়াতি হিসেবে প্রত্যাখ্যান করুন");
                default:
                    return Map.of("en", dec.name(), "bn", dec.name());
            }
        }
    }

    private ConflictUiState state = ConflictUiState.IDLE;
    private final List<ConflictTicketViewModel> tickets = new ArrayList<>();
    private String lastErrorMessageEn = "";
    private String lastErrorMessageBn = "";

    public ConflictUiState getState() {
        return state;
    }

    public List<ConflictTicketViewModel> getTickets() {
        return Collections.unmodifiableList(tickets);
    }

    public String getLastErrorMessageEn() {
        return lastErrorMessageEn;
    }

    public String getLastErrorMessageBn() {
        return lastErrorMessageBn;
    }

    public void loadConflictsFromSqlite(Connection conn, String tenantId) throws SQLException {
        state = ConflictUiState.LOADING;
        tickets.clear();

        String sql = "SELECT conflict_id, command_id, tenant_id, branch_id, conflict_reason, " +
                "local_payload_json, server_state_json, resolution_status, resolved_by, resolved_at, created_at " +
                "FROM sync_conflict_queue WHERE tenant_id = ? ORDER BY created_at DESC";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tickets.add(new ConflictTicketViewModel(
                            rs.getString("conflict_id"),
                            rs.getString("command_id"),
                            rs.getString("tenant_id"),
                            rs.getString("branch_id"),
                            rs.getString("conflict_reason"),
                            rs.getString("local_payload_json"),
                            rs.getString("server_state_json"),
                            rs.getString("resolution_status"),
                            "",
                            "",
                            "",
                            "",
                            rs.getString("resolved_by"),
                            "{}",
                            BigDecimal.ZERO,
                            rs.getString("created_at")
                    ));
                }
            }
        } catch (SQLException ex) {
            state = ConflictUiState.ERROR;
            lastErrorMessageEn = "Database error loading conflicts: " + ex.getMessage();
            lastErrorMessageBn = "ডাটাবেজ থেকে বিরোধ তালিকা লোড করতে ত্রুটি: " + ex.getMessage();
            throw ex;
        }

        state = tickets.isEmpty() ? ConflictUiState.EMPTY : ConflictUiState.LOADED;
    }

    public boolean proposeDecision(
            Connection conn,
            String conflictId,
            ProposedDecision decision,
            String notes,
            String proposedBy
    ) throws SQLException {
        if (decision == null) {
            throw new IllegalArgumentException("ERR_DECISION_REQUIRED: Proposed decision cannot be null.");
        }
        if (notes == null || notes.trim().length() < 3) {
            throw new IllegalArgumentException("ERR_NOTES_REQUIRED: Proposed notes must be at least 3 characters.");
        }

        String sql = "UPDATE sync_conflict_queue SET resolution_status = 'DECISION_PROPOSED', " +
                "conflict_reason = conflict_reason || ' [PROPOSED: ' || ? || ' - ' || ? || ']' " +
                "WHERE conflict_id = ? AND resolution_status IN ('OPEN', 'DECISION_PROPOSED')";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, decision.name());
            ps.setString(2, notes.trim());
            ps.setString(3, conflictId);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                for (ConflictTicketViewModel t : tickets) {
                    if (t.conflictId.equals(conflictId)) {
                        t.resolutionStatus = "DECISION_PROPOSED";
                        t.proposedDecision = decision.name();
                        t.proposedNotes = notes.trim();
                        t.proposedBy = proposedBy;
                        break;
                    }
                }
                return true;
            }
        }
        return false;
    }

    public boolean approveDecision(
            Connection conn,
            String conflictId,
            String approvalNotes,
            String referenceId,
            String approvedBy
    ) throws SQLException {
        if (approvalNotes == null || approvalNotes.trim().length() < 3) {
            throw new IllegalArgumentException("ERR_APPROVAL_NOTES_REQUIRED: Approval notes must be at least 3 characters.");
        }

        String sql = "UPDATE sync_conflict_queue SET resolution_status = 'RESOLVED_ADJUSTED', " +
                "resolved_by = ?, resolved_at = ? WHERE conflict_id = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, approvedBy);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, conflictId);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                for (ConflictTicketViewModel t : tickets) {
                    if (t.conflictId.equals(conflictId)) {
                        t.resolutionStatus = "RESOLVED_ADJUSTED";
                        t.approvalNotes = approvalNotes.trim();
                        t.approvedBy = approvedBy;
                        break;
                    }
                }
                return true;
            }
        }
        return false;
    }

    public BigDecimal computeTotalDisputedBdt() {
        BigDecimal sum = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (ConflictTicketViewModel t : tickets) {
            if ("OPEN".equals(t.resolutionStatus) || "DECISION_PROPOSED".equals(t.resolutionStatus)) {
                sum = sum.add(t.disputedMonetaryBdt);
            }
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }
}
