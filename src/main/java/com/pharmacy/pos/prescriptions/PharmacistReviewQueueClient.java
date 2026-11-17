package com.pharmacy.pos.prescriptions;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * P243: Pharmacist Review Queue & Clinical Decision Client (JavaFX Desktop).
 * Architecture Decision: ADR-0243 (P243).
 */
public class PharmacistReviewQueueClient {

    public enum ReviewDecision {
        VERIFY("Approve & Verify", "অনুমোদন ও যাচাই করুন"),
        REJECT("Reject Prescription", "প্রেসক্রিপশন প্রত্যাখ্যান করুন"),
        REQUEST_CLARIFICATION("Request Clarification", "ব্যাখ্যা বা স্পষ্টীকরণ চান"),
        RESOLVE_CLARIFICATION("Resolve Clarification", "ব্যাখ্যার উত্তর লিপিবদ্ধ করুন");

        private final String enLabel;
        private final String bnLabel;

        ReviewDecision(String enLabel, String bnLabel) {
            this.enLabel = enLabel;
            this.bnLabel = bnLabel;
        }

        public String getLocalized(String lang) {
            return "bn".equalsIgnoreCase(lang) ? bnLabel : enLabel;
        }
    }

    public static class QueueItemDto implements Serializable {
        private final String prescriptionId;
        private final String prescriptionNumber;
        private final String doctorName;
        private final String doctorBmdcNo;
        private final String patientToken;
        private final String status;
        private final boolean hasAntibiotics;
        private final int itemCount;
        private final int attachmentCount;

        public QueueItemDto(
                String prescriptionId,
                String prescriptionNumber,
                String doctorName,
                String doctorBmdcNo,
                String patientToken,
                String status,
                boolean hasAntibiotics,
                int itemCount,
                int attachmentCount
        ) {
            this.prescriptionId = prescriptionId;
            this.prescriptionNumber = prescriptionNumber;
            this.doctorName = doctorName;
            this.doctorBmdcNo = doctorBmdcNo;
            this.patientToken = patientToken;
            this.status = status;
            this.hasAntibiotics = hasAntibiotics;
            this.itemCount = itemCount;
            this.attachmentCount = attachmentCount;
        }

        public String getPrescriptionId() { return prescriptionId; }
        public String getPrescriptionNumber() { return prescriptionNumber; }
        public String getDoctorName() { return doctorName; }
        public String getDoctorBmdcNo() { return doctorBmdcNo; }
        public String getPatientToken() { return patientToken; }
        public String getStatus() { return status; }
        public boolean isHasAntibiotics() { return hasAntibiotics; }
        public int getItemCount() { return itemCount; }
        public int getAttachmentCount() { return attachmentCount; }
        public boolean isPriority() { return hasAntibiotics; }
    }

    public static class ClinicalAuditRecordDto implements Serializable {
        private final String auditId;
        private final String prescriptionId;
        private final String action;
        private final String fromStatus;
        private final String toStatus;
        private final String pharmacistPcbNo;
        private final String clinicalNotes;
        private final String clarificationQuery;
        private final String clarificationResponse;
        private final LocalDateTime createdAt;

        public ClinicalAuditRecordDto(
                String auditId,
                String prescriptionId,
                String action,
                String fromStatus,
                String toStatus,
                String pharmacistPcbNo,
                String clinicalNotes,
                String clarificationQuery,
                String clarificationResponse,
                LocalDateTime createdAt
        ) {
            this.auditId = auditId;
            this.prescriptionId = prescriptionId;
            this.action = action;
            this.fromStatus = fromStatus;
            this.toStatus = toStatus;
            this.pharmacistPcbNo = pharmacistPcbNo;
            this.clinicalNotes = clinicalNotes;
            this.clarificationQuery = clarificationQuery;
            this.clarificationResponse = clarificationResponse;
            this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        }

        public String getAuditId() { return auditId; }
        public String getPrescriptionId() { return prescriptionId; }
        public String getAction() { return action; }
        public String getFromStatus() { return fromStatus; }
        public String getToStatus() { return toStatus; }
        public String getPharmacistPcbNo() { return pharmacistPcbNo; }
        public String getClinicalNotes() { return clinicalNotes; }
        public String getClarificationQuery() { return clarificationQuery; }
        public String getClarificationResponse() { return clarificationResponse; }
        public LocalDateTime getCreatedAt() { return createdAt; }
    }
}

