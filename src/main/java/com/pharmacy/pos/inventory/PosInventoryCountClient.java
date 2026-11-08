package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * PosInventoryCountClient
 * Architecture Decision: ADR-0204 (P204).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Client for physical inventory count sessions, scanner collection,
 * discrepancy calculation, and strict maker-checker segregation of duties.
 */
public class PosInventoryCountClient {

    public enum SessionType {
        FULL_STOCKTAKE("Full Pharmacy Stocktake", "পূর্ণ স্টকটেক"),
        CYCLE_COUNT("Periodic Cycle Count", "পর্যায়ক্রমিক সাইকেল কাউন্ট"),
        CATEGORY_AUDIT("Category / Shelf Audit", "ক্যাটাগরি / তাক নিরীক্ষা"),
        COLD_CHAIN_AUDIT("Cold-Chain Refrigerator Audit", "কোল্ড-চেইন ফ্রিজ নিরীক্ষা"),
        CONTROLLED_DRUG_AUDIT("Controlled Narcotics Audit", "নিয়ন্ত্রিত ওষুধের নিরীক্ষা"),
        SPOT_CHECK("Discrepancy Spot Check", "স্পট চেক");

        public final String labelEn;
        public final String labelBn;

        SessionType(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum SessionStatus {
        DRAFT("Draft Planning", "খসড়া"),
        IN_PROGRESS("Counting in Progress", "গণনা চলছে"),
        SUBMITTED_FOR_REVIEW("Count Complete - Pending Review", "পর্যালোচনার অপেক্ষায়"),
        APPROVED("Approved by Checker", "অনুমোদিত"),
        REJECTED("Rejected by Checker", "প্রত্যাখ্যাত"),
        POSTED("Posted to Stock Ledger", "লেজারে পোস্ট সম্পন্ন"),
        CANCELLED("Cancelled", "বাতিল");

        public final String labelEn;
        public final String labelBn;

        SessionStatus(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum DiscrepancyType {
        MATCH("Match", "সঠিক মিল"),
        SURPLUS("Surplus", "উদ্বৃত্ত"),
        SHORTAGE("Shortage", "ঘাটতি");

        public final String labelEn;
        public final String labelBn;

        DiscrepancyType(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static final class CountItemDto {
        public final String itemId;
        public final String sessionId;
        public final String batchNumber;
        public final int systemQuantity;
        public int countedQuantity;
        public int discrepancyQuantity;
        public final BigDecimal unitCost;
        public BigDecimal costDiscrepancyBdt;
        public DiscrepancyType discrepancyType;
        public String adjustmentLedgerEntryId;

        public CountItemDto(
                String itemId,
                String sessionId,
                String batchNumber,
                int systemQuantity,
                int countedQuantity,
                BigDecimal unitCost
        ) {
            this.itemId = itemId;
            this.sessionId = sessionId;
            this.batchNumber = batchNumber;
            this.systemQuantity = systemQuantity;
            this.countedQuantity = countedQuantity;
            this.unitCost = unitCost;
            recalculate();
        }

        public void updateCount(int newCount) {
            this.countedQuantity = newCount;
            recalculate();
        }

        private void recalculate() {
            this.discrepancyQuantity = this.countedQuantity - this.systemQuantity;
            this.costDiscrepancyBdt = this.unitCost.multiply(BigDecimal.valueOf(this.discrepancyQuantity));
            if (this.discrepancyQuantity > 0) {
                this.discrepancyType = DiscrepancyType.SURPLUS;
            } else if (this.discrepancyQuantity < 0) {
                this.discrepancyType = DiscrepancyType.SHORTAGE;
            } else {
                this.discrepancyType = DiscrepancyType.MATCH;
            }
        }
    }

    public static final class CountSessionDto {
        public final String sessionId;
        public final String tenantId;
        public final String branchId;
        public final String sessionNumber;
        public final SessionType sessionType;
        public SessionStatus status;
        public final String createdByUserId;
        public String countedByUserId;
        public String reviewedByUserId;
        public String checkerNotes;
        public Instant submittedAt;
        public Instant reviewedAt;
        public Instant postedAt;
        public int totalDiscrepancyUnits;
        public BigDecimal totalCostDiscrepancyBdt = BigDecimal.ZERO;
        public int surplusCount;
        public int shortageCount;
        public int matchedCount;
        public final Map<String, CountItemDto> items = new LinkedHashMap<>();

        public CountSessionDto(
                String sessionId,
                String tenantId,
                String branchId,
                String sessionNumber,
                SessionType sessionType,
                String createdByUserId
        ) {
            this.sessionId = sessionId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.sessionNumber = sessionNumber;
            this.sessionType = sessionType;
            this.status = SessionStatus.IN_PROGRESS;
            this.createdByUserId = createdByUserId;
            this.countedByUserId = createdByUserId;
        }

        public void recalculateTotals() {
            int totalDisc = 0;
            BigDecimal costDisc = BigDecimal.ZERO;
            int surplus = 0;
            int shortage = 0;
            int matched = 0;

            for (CountItemDto item : items.values()) {
                totalDisc += item.discrepancyQuantity;
                costDisc = costDisc.add(item.costDiscrepancyBdt);
                if (item.discrepancyType == DiscrepancyType.SURPLUS) surplus++;
                else if (item.discrepancyType == DiscrepancyType.SHORTAGE) shortage++;
                else matched++;
            }

            this.totalDiscrepancyUnits = totalDisc;
            this.totalCostDiscrepancyBdt = costDisc;
            this.surplusCount = surplus;
            this.shortageCount = shortage;
            this.matchedCount = matched;
        }
    }

    private final Map<String, CountSessionDto> sessions = new HashMap<>();

    public CountSessionDto createSession(
            String tenantId,
            String branchId,
            SessionType type,
            String actorRole,
            String actorUserId
    ) {
        if ("CASHIER".equalsIgnoreCase(actorRole)) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers cannot create count sessions.");
        }
        String id = "cnt-" + UUID.randomUUID().toString().substring(0, 8);
        String num = "CNT-" + System.currentTimeMillis();
        CountSessionDto session = new CountSessionDto(id, tenantId, branchId, num, type, actorUserId);
        sessions.put(session.sessionId, session);
        return session;
    }

    public CountItemDto recordScanItem(
            String sessionId,
            String batchNumber,
            int systemQuantity,
            int countedQuantity,
            BigDecimal unitCost,
            String actorRole
    ) {
        if ("CASHIER".equalsIgnoreCase(actorRole)) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Cashiers cannot scan items in count sessions.");
        }
        if (countedQuantity < 0) {
            throw new IllegalArgumentException("ERR_INVALID_QUANTITY: Counted quantity cannot be negative.");
        }

        CountSessionDto session = sessions.get(sessionId);
        if (session == null) {
            throw new NoSuchElementException("ERR_SESSION_NOT_FOUND");
        }
        if (session.status != SessionStatus.DRAFT && session.status != SessionStatus.IN_PROGRESS) {
            throw new IllegalStateException("ERR_INVALID_STATUS: Session is not in progress.");
        }

        CountItemDto item = session.items.get(batchNumber);
        if (item == null) {
            String itemId = "cnti-" + UUID.randomUUID().toString().substring(0, 8);
            item = new CountItemDto(itemId, sessionId, batchNumber, systemQuantity, countedQuantity, unitCost);
            session.items.put(batchNumber, item);
        } else {
            item.updateCount(countedQuantity);
        }

        session.recalculateTotals();
        return item;
    }

    public CountSessionDto submitForReview(String sessionId, String actorRole, String actorUserId) {
        if ("CASHIER".equalsIgnoreCase(actorRole)) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED");
        }
        CountSessionDto session = sessions.get(sessionId);
        if (session == null) throw new NoSuchElementException("ERR_SESSION_NOT_FOUND");

        if (session.items.isEmpty()) {
            throw new IllegalArgumentException("ERR_EMPTY_SESSION: Cannot submit empty count session.");
        }
        if (session.status != SessionStatus.IN_PROGRESS) {
            throw new IllegalStateException("ERR_INVALID_STATUS");
        }

        session.status = SessionStatus.SUBMITTED_FOR_REVIEW;
        session.submittedAt = Instant.now();
        session.countedByUserId = actorUserId;
        return session;
    }

    public CountSessionDto reviewSession(
            String sessionId,
            boolean isApproved,
            String checkerRole,
            String checkerUserId,
            String checkerNotes
    ) {
        if (!checkerRole.matches("(?i)BRANCH_MANAGER|PHARMACY_OWNER|PLATFORM_SUPER_ADMIN")) {
            throw new SecurityException("ERR_CHECKER_ROLE_DENIED: Only Managers or Owners can review sessions.");
        }
        CountSessionDto session = sessions.get(sessionId);
        if (session == null) throw new NoSuchElementException("ERR_SESSION_NOT_FOUND");

        if (session.status != SessionStatus.SUBMITTED_FOR_REVIEW) {
            throw new IllegalStateException("ERR_INVALID_STATUS");
        }

        // Anti-Self-Approval rule
        if (checkerUserId != null && checkerUserId.equals(session.countedByUserId)) {
            throw new IllegalStateException("ERR_MAKER_CHECKER_SELF_APPROVAL: Maker cannot check their own count.");
        }

        session.reviewedByUserId = checkerUserId;
        session.reviewedAt = Instant.now();
        session.checkerNotes = checkerNotes;
        session.status = isApproved ? SessionStatus.APPROVED : SessionStatus.REJECTED;
        return session;
    }

    public Map<String, Object> postAdjustments(String sessionId, String actorRole) {
        CountSessionDto session = sessions.get(sessionId);
        if (session == null) throw new NoSuchElementException("ERR_SESSION_NOT_FOUND");

        if (session.status == SessionStatus.POSTED) {
            throw new IllegalStateException("ERR_ALREADY_POSTED: Session is already posted.");
        }
        if (session.status != SessionStatus.APPROVED) {
            throw new IllegalStateException("ERR_NOT_APPROVED: Session must be approved before posting.");
        }

        int postedCount = 0;
        for (CountItemDto item : session.items.values()) {
            if (item.discrepancyQuantity != 0) {
                item.adjustmentLedgerEntryId = "sled-" + UUID.randomUUID().toString().substring(0, 8);
                postedCount++;
            }
        }

        session.status = SessionStatus.POSTED;
        session.postedAt = Instant.now();

        Map<String, Object> res = new HashMap<>();
        res.put("sessionId", session.sessionId);
        res.put("status", session.status.name());
        res.put("postedAdjustments", postedCount);
        res.put("totalDiscrepancyUnits", session.totalDiscrepancyUnits);
        res.put("costDiscrepancyBdt", session.totalCostDiscrepancyBdt);
        return res;
    }
}
