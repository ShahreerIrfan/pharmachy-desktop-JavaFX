package com.pharmacy.pos.approval;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosApprovalClient
 * Architecture Decision: ADR-0108
 * Phase 06: Milestone M03 - Manager and Supervisor Approvals (P108)
 *
 * Client-side integration for JavaFX Desktop POS managing dual-control Maker-Checker
 * supervisor sign-offs across high retail discounts, returns/refunds, stock write-offs,
 * sensitive branch expenses, and prescription clinical escalations.
 */
public class PosApprovalClient {

    public enum UiStatus {
        LOADING,
        SUCCESS,
        EMPTY,
        OFFLINE,
        ERROR,
        PERMISSION_DENIED
    }

    public enum ApprovalType {
        HIGH_DISCOUNT,
        REFUND,
        STOCK_WRITE_OFF,
        SENSITIVE_EXPENSE,
        CLINICAL_ESCALATION
    }

    public enum RequestStatus {
        PENDING,
        APPROVED,
        REJECTED,
        CANCELLED
    }

    public static final class ApprovalRecord {
        public final String requestId;
        public final String tenantId;
        public final String branchId;
        public final ApprovalType approvalType;
        public final RequestStatus status;
        public final String urgency;
        public final String makerUsername;
        public final String checkerUsername;
        public final BigDecimal amountBdt;
        public final BigDecimal discountPercent;
        public final String title;
        public final String reason;
        public final String decisionNotes;
        public final String approvalToken;
        public final String createdAt;
        public final String decisionAt;

        public ApprovalRecord(
                String requestId,
                String tenantId,
                String branchId,
                ApprovalType approvalType,
                RequestStatus status,
                String urgency,
                String makerUsername,
                String checkerUsername,
                BigDecimal amountBdt,
                BigDecimal discountPercent,
                String title,
                String reason,
                String decisionNotes,
                String approvalToken,
                String createdAt,
                String decisionAt
        ) {
            this.requestId = requestId;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.approvalType = approvalType;
            this.status = status;
            this.urgency = urgency;
            this.makerUsername = makerUsername;
            this.checkerUsername = checkerUsername;
            this.amountBdt = amountBdt;
            this.discountPercent = discountPercent;
            this.title = title;
            this.reason = reason;
            this.decisionNotes = decisionNotes;
            this.approvalToken = approvalToken;
            this.createdAt = createdAt;
            this.decisionAt = decisionAt;
        }
    }

    public static final class ClientResult<T> {
        public final UiStatus uiStatus;
        public final T data;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public ClientResult(UiStatus uiStatus, T data, String errorCode, String messageEn, String messageBn) {
            this.uiStatus = uiStatus;
            this.data = data;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public boolean isSuccess() {
            return uiStatus == UiStatus.SUCCESS;
        }
    }

    private final String baseUrl;
    private final Map<String, ApprovalRecord> localCache = new ConcurrentHashMap<>();
    private final Map<String, String> tokenLookup = new ConcurrentHashMap<>();
    private boolean simulatedOffline = false;

    public PosApprovalClient(String baseUrl) {
        this.baseUrl = baseUrl != null ? baseUrl : "http://localhost:8000/api/v2";
    }

    public void setSimulatedOffline(boolean offline) {
        this.simulatedOffline = offline;
    }

    /**
     * Submits a new dual-control approval request (MAKER).
     */
    public ClientResult<ApprovalRecord> submitRequest(
            String tenantId,
            String branchId,
            String makerUsername,
            String makerRole,
            ApprovalType type,
            String title,
            String reason,
            BigDecimal amountBdt,
            BigDecimal discountPercent,
            String urgency
    ) {
        if (title == null || title.trim().length() < 3) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    null,
                    "INVALID_TITLE",
                    "Approval title must be at least 3 characters.",
                    "অনুরোধের শিরোনাম কমপক্ষে ৩ অক্ষরের হতে হবে।"
            );
        }

        if (reason == null || reason.trim().length() < 4) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    null,
                    "INVALID_REASON",
                    "Detailed operational justification (minimum 4 characters) is mandatory.",
                    "অনুরোধের কারণ ও বিবরণ (কমপক্ষে ৪ অক্ষর) বাধ্যতামূলক।"
            );
        }

        BigDecimal safeAmount = amountBdt != null ? amountBdt : BigDecimal.ZERO;
        BigDecimal safeDiscount = discountPercent != null ? discountPercent : BigDecimal.ZERO;

        if (safeAmount.compareTo(BigDecimal.ZERO) < 0) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    null,
                    "INVALID_AMOUNT",
                    "Amount BDT cannot be negative.",
                    "টাকার পরিমাণ নেতিবাচক হতে পারবে না।"
            );
        }

        String reqId = "appr-" + UUID.randomUUID().toString().substring(0, 12);
        ApprovalRecord rec = new ApprovalRecord(
                reqId,
                tenantId,
                branchId,
                type,
                RequestStatus.PENDING,
                urgency != null ? urgency : "ROUTINE",
                makerUsername,
                null,
                safeAmount,
                safeDiscount,
                title.trim(),
                reason.trim(),
                null,
                null,
                Instant.now().toString(),
                null
        );

        localCache.put(reqId, rec);

        if (simulatedOffline) {
            return new ClientResult<>(
                    UiStatus.OFFLINE,
                    rec,
                    "OFFLINE_QUEUED",
                    "POS is offline. Approval request cached locally in outbox.",
                    "পিওএস অফলাইনে আছে। অনুরোধটি লোকাল ক্যাশে সংরক্ষণ করা হয়েছে।"
            );
        }

        return new ClientResult<>(
                UiStatus.SUCCESS,
                rec,
                null,
                "Approval request submitted successfully.",
                "অনুমোদনের অনুরোধ সফলভাবে প্রেরণ করা হয়েছে।"
        );
    }

    /**
     * Approves a pending request (CHECKER). Enforces Maker != Checker and Role boundaries.
     */
    public ClientResult<ApprovalRecord> approveRequest(
            String tenantId,
            String requestId,
            String checkerUsername,
            String checkerRole,
            String checkerBranchId,
            String decisionNotes
    ) {
        ApprovalRecord rec = localCache.get(requestId);
        if (rec == null) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    null,
                    "ERR_NOT_FOUND",
                    "Approval request not found.",
                    "অনুমোদনের অনুরোধ খুঁজে পাওয়া যায়নি।"
            );
        }

        if (rec.status != RequestStatus.PENDING) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    rec,
                    "ERR_NOT_PENDING",
                    "Request is already " + rec.status,
                    "অনুরোধটি ইতিমধ্যে " + rec.status + " অবস্থায় আছে।"
            );
        }

        // 1. Dual-Control Separation: Maker != Checker
        if (checkerUsername != null && checkerUsername.equalsIgnoreCase(rec.makerUsername)) {
            return new ClientResult<>(
                    UiStatus.PERMISSION_DENIED,
                    null,
                    "ERR_CANNOT_APPROVE_OWN_REQUEST",
                    "Maker-Checker violation: You cannot approve your own request.",
                    "মেকার-চেকার লঙ্ঘন: আপনি নিজের তৈরি করা অনুরোধ নিজে অনুমোদন করতে পারবেন না।"
            );
        }

        // 2. Role Authority Check
        String role = checkerRole != null ? checkerRole.toUpperCase() : "";
        if ("CASHIER".equals(role) || "INVENTORY_CLERK".equals(role) || "AUDITOR".equals(role)) {
            return new ClientResult<>(
                    UiStatus.PERMISSION_DENIED,
                    null,
                    "ERR_INSUFFICIENT_APPROVAL_AUTHORITY",
                    "Frontline staff cannot act as supervisor checker.",
                    "ফ্রন্টলাইন স্টাফদের অনুমোদন প্রদানের ক্ষমতা নেই।"
            );
        }

        // Clinical escalation authority
        if (rec.approvalType == ApprovalType.CLINICAL_ESCALATION) {
            if (!"STAFF_PHARMACIST".equals(role) && !"BRANCH_MANAGER".equals(role)
                    && !"PHARMACY_OWNER".equals(role) && !"SUPERADMIN".equals(role)) {
                return new ClientResult<>(
                        UiStatus.PERMISSION_DENIED,
                        null,
                        "ERR_CLINICAL_AUTHORITY_REQUIRED",
                        "Clinical escalation sign-offs require a licensed Supervising Pharmacist or Manager.",
                        "ক্লিনিক্যাল এস্কেলেশন অনুমোদনের জন্য লাইসেন্সধারী ফার্মাসিস্ট বা ম্যানেজারের অনুমোদন প্রয়োজন।"
                );
            }
        }

        // Branch Manager cross-branch restriction
        if ("BRANCH_MANAGER".equals(role) && checkerBranchId != null && !checkerBranchId.equals(rec.branchId)) {
            return new ClientResult<>(
                    UiStatus.PERMISSION_DENIED,
                    null,
                    "ERR_CROSS_BRANCH_VIOLATION",
                    "Branch Manager cannot approve requests originating from another branch store.",
                    "ব্রাঞ্চ ম্যানেজার অন্য ব্রাঞ্চের অনুরোধ অনুমোদন করতে পারেন না।"
            );
        }

        String token = "tok_appr_" + UUID.randomUUID().toString().replace("-", "");
        ApprovalRecord approved = new ApprovalRecord(
                rec.requestId,
                rec.tenantId,
                rec.branchId,
                rec.approvalType,
                RequestStatus.APPROVED,
                rec.urgency,
                rec.makerUsername,
                checkerUsername,
                rec.amountBdt,
                rec.discountPercent,
                rec.title,
                rec.reason,
                decisionNotes != null ? decisionNotes.trim() : "",
                token,
                rec.createdAt,
                Instant.now().toString()
        );

        localCache.put(requestId, approved);
        tokenLookup.put(token, requestId);

        return new ClientResult<>(
                UiStatus.SUCCESS,
                approved,
                null,
                "Request approved. Authorization token generated.",
                "অনুরোধ অনুমোদিত হয়েছে। অনুমোদন টোকেন তৈরি সম্পন্ন।"
        );
    }

    /**
     * Rejects a pending request (CHECKER). Enforces Maker != Checker.
     */
    public ClientResult<ApprovalRecord> rejectRequest(
            String tenantId,
            String requestId,
            String checkerUsername,
            String checkerRole,
            String checkerBranchId,
            String rejectionReason
    ) {
        ApprovalRecord rec = localCache.get(requestId);
        if (rec == null) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    null,
                    "ERR_NOT_FOUND",
                    "Approval request not found.",
                    "অনুমোদনের অনুরোধ খুঁজে পাওয়া যায়নি।"
            );
        }

        if (rec.status != RequestStatus.PENDING) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    rec,
                    "ERR_NOT_PENDING",
                    "Request is already " + rec.status,
                    "অনুরোধটি ইতিমধ্যে " + rec.status + " অবস্থায় আছে।"
            );
        }

        if (rejectionReason == null || rejectionReason.trim().length() < 4) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    null,
                    "REJECTION_REASON_REQUIRED",
                    "Detailed rejection reason is mandatory.",
                    "বাতিল করার কারণ উল্লেখ করা বাধ্যতামূলক।"
            );
        }

        if (checkerUsername != null && checkerUsername.equalsIgnoreCase(rec.makerUsername)) {
            return new ClientResult<>(
                    UiStatus.PERMISSION_DENIED,
                    null,
                    "ERR_CANNOT_APPROVE_OWN_REQUEST",
                    "Maker-Checker violation: You cannot reject your own request as a checker.",
                    "মেকার-চেকার লঙ্ঘন: আপনি নিজের অনুরোধ নিজে বাতিল করতে পারবেন না।"
            );
        }

        ApprovalRecord rejected = new ApprovalRecord(
                rec.requestId,
                rec.tenantId,
                rec.branchId,
                rec.approvalType,
                RequestStatus.REJECTED,
                rec.urgency,
                rec.makerUsername,
                checkerUsername,
                rec.amountBdt,
                rec.discountPercent,
                rec.title,
                rec.reason,
                rejectionReason.trim(),
                null,
                rec.createdAt,
                Instant.now().toString()
        );

        localCache.put(requestId, rejected);

        return new ClientResult<>(
                UiStatus.SUCCESS,
                rejected,
                null,
                "Request rejected with feedback.",
                "অনুরোধ বাতিল করা হয়েছে।"
        );
    }

    /**
     * Verifies validity of an authorization approval token.
     */
    public ClientResult<ApprovalRecord> verifyToken(String tenantId, String approvalToken) {
        if (approvalToken == null || approvalToken.trim().isEmpty()) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    null,
                    "EMPTY_TOKEN",
                    "Token cannot be empty.",
                    "টোকেন ফাঁকা হতে পারবে না।"
            );
        }

        String reqId = tokenLookup.get(approvalToken.trim());
        if (reqId == null) {
            return new ClientResult<>(
                    UiStatus.ERROR,
                    null,
                    "INVALID_TOKEN",
                    "Approval token is invalid or expired.",
                    "অনুমোদন টোকেন সঠিক নয় বা মেয়াদোত্তীর্ণ।"
            );
        }

        ApprovalRecord rec = localCache.get(reqId);
        if (rec != null && rec.status == RequestStatus.APPROVED) {
            return new ClientResult<>(
                    UiStatus.SUCCESS,
                    rec,
                    null,
                    "Token verified successfully.",
                    "টোকেন সফলভাবে যাচাই করা হয়েছে।"
            );
        }

        return new ClientResult<>(
                UiStatus.ERROR,
                null,
                "INVALID_TOKEN",
                "Approval token is invalid or expired.",
                "অনুমোদন টোকেন সঠিক নয় বা মেয়াদোত্তীর্ণ।"
        );
    }

    /**
     * Lists all pending approval requests for a branch.
     */
    public ClientResult<List<ApprovalRecord>> getPendingRequests(String tenantId, String branchId) {
        List<ApprovalRecord> list = new ArrayList<>();
        for (ApprovalRecord r : localCache.values()) {
            if (r.tenantId.equals(tenantId) && (branchId == null || r.branchId.equals(branchId))) {
                if (r.status == RequestStatus.PENDING) {
                    list.add(r);
                }
            }
        }

        if (list.isEmpty()) {
            return new ClientResult<>(
                    UiStatus.EMPTY,
                    Collections.emptyList(),
                    null,
                    "No pending approval requests.",
                    "কোনো পেন্ডিং অনুমোদনের অনুরোধ নেই।"
            );
        }

        return new ClientResult<>(
                UiStatus.SUCCESS,
                list,
                null,
                "Fetched " + list.size() + " pending requests.",
                list.size() + "টি পেন্ডিং অনুরোধ পাওয়া গেছে।"
        );
    }
}
