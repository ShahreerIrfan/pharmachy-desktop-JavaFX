package com.pharmacy.pos.prescriptions;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * P241: Prescription Record Client & State Model (JavaFX Desktop).
 * Architecture Decision: ADR-0241 (P241).
 */
public class PrescriptionRecordClient {

    public enum PrescriptionStatus {
        PENDING("Pending Verification", "অনুমোদনের অপেক্ষায়"),
        VERIFIED("Verified by Pharmacist", "ফার্মাসিস্ট দ্বারা যাচাইকৃত"),
        PARTIALLY_DISPENSED("Partially Dispensed", "আংশিক প্রদানকৃত"),
        DISPENSED("Dispensed", "সম্পূর্ণ প্রদানকৃত"),
        REJECTED("Rejected", "প্রত্যাখ্যাত"),
        CANCELLED("Cancelled", "বাতিলকৃত"),
        EXPIRED("Expired", "মেয়াদোত্তীর্ণ");

        private final String enLabel;
        private final String bnLabel;

        PrescriptionStatus(String enLabel, String bnLabel) {
            this.enLabel = enLabel;
            this.bnLabel = bnLabel;
        }

        public String getLocalized(String lang) {
            return "bn".equalsIgnoreCase(lang) ? bnLabel : enLabel;
        }
    }

    public enum UiState {
        LOADING,
        READY,
        EMPTY,
        ERROR,
        OFFLINE,
        PERMISSION_DENIED
    }

    public static class PrescriptionItemDto implements Serializable {
        private final int id;
        private final String productId;
        private final String productBrand;
        private final String dosageInstructions;
        private final int durationDays;
        private final int quantityPrescribed;
        private final int quantityDispensed;
        private final boolean isDispensed;

        public PrescriptionItemDto(
                int id,
                String productId,
                String productBrand,
                String dosageInstructions,
                int durationDays,
                int quantityPrescribed,
                int quantityDispensed,
                boolean isDispensed
        ) {
            this.id = id;
            this.productId = productId;
            this.productBrand = productBrand;
            this.dosageInstructions = dosageInstructions;
            this.durationDays = durationDays;
            this.quantityPrescribed = quantityPrescribed;
            this.quantityDispensed = quantityDispensed;
            this.isDispensed = isDispensed;
        }

        public int getId() { return id; }
        public String getProductId() { return productId; }
        public String getProductBrand() { return productBrand; }
        public String getDosageInstructions() { return dosageInstructions; }
        public int getDurationDays() { return durationDays; }
        public int getQuantityPrescribed() { return quantityPrescribed; }
        public int getQuantityDispensed() { return quantityDispensed; }
        public int getRemainingQuantity() { return Math.max(0, quantityPrescribed - quantityDispensed); }
        public boolean isDispensed() { return isDispensed; }
    }

    public static class PrescriptionDto implements Serializable {
        private final String prescriptionId;
        private final String prescriptionNumber;
        private final String doctorName;
        private final String doctorBmdcNo;
        private final String doctorWorkplace;
        private final String patientToken;
        private final Integer patientAge;
        private final String patientGender;
        private final String diagnosis;
        private final PrescriptionStatus status;
        private final String pharmacistPcbNo;
        private final LocalDate prescriptionDate;
        private final LocalDate expiryDate;
        private final List<PrescriptionItemDto> items;

        public PrescriptionDto(
                String prescriptionId,
                String prescriptionNumber,
                String doctorName,
                String doctorBmdcNo,
                String doctorWorkplace,
                String patientToken,
                Integer patientAge,
                String patientGender,
                String diagnosis,
                PrescriptionStatus status,
                String pharmacistPcbNo,
                LocalDate prescriptionDate,
                LocalDate expiryDate,
                List<PrescriptionItemDto> items
        ) {
            this.prescriptionId = prescriptionId;
            this.prescriptionNumber = prescriptionNumber;
            this.doctorName = doctorName;
            this.doctorBmdcNo = doctorBmdcNo;
            this.doctorWorkplace = doctorWorkplace;
            this.patientToken = patientToken;
            this.patientAge = patientAge;
            this.patientGender = patientGender;
            this.diagnosis = diagnosis;
            this.status = status != null ? status : PrescriptionStatus.PENDING;
            this.pharmacistPcbNo = pharmacistPcbNo;
            this.prescriptionDate = prescriptionDate != null ? prescriptionDate : LocalDate.now();
            this.expiryDate = expiryDate;
            this.items = items != null ? Collections.unmodifiableList(new ArrayList<>(items)) : Collections.emptyList();
        }

        public String getPrescriptionId() { return prescriptionId; }
        public String getPrescriptionNumber() { return prescriptionNumber; }
        public String getDoctorName() { return doctorName; }
        public String getDoctorBmdcNo() { return doctorBmdcNo; }
        public String getDoctorWorkplace() { return doctorWorkplace; }
        public String getPatientToken() { return patientToken; }
        public Integer getPatientAge() { return patientAge; }
        public String getPatientGender() { return patientGender; }
        public String getDiagnosis() { return diagnosis; }
        public PrescriptionStatus getStatus() { return status; }
        public String getPharmacistPcbNo() { return pharmacistPcbNo; }
        public LocalDate getPrescriptionDate() { return prescriptionDate; }
        public LocalDate getExpiryDate() { return expiryDate; }
        public List<PrescriptionItemDto> getItems() { return items; }

        public boolean isExpired() {
            if (expiryDate == null) return false;
            return LocalDate.now().isAfter(expiryDate);
        }

        public boolean canDispense() {
            if (isExpired()) return false;
            return status == PrescriptionStatus.VERIFIED || status == PrescriptionStatus.PARTIALLY_DISPENSED;
        }

        public int getTotalPrescribedUnits() {
            return items.stream().mapToInt(PrescriptionItemDto::getQuantityPrescribed).sum();
        }

        public int getTotalDispensedUnits() {
            return items.stream().mapToInt(PrescriptionItemDto::getQuantityDispensed).sum();
        }
    }

    public static class PrescriptionUiModel {
        private UiState uiState = UiState.READY;
        private PrescriptionDto prescription;
        private String errorMessage = "";

        public UiState getUiState() { return uiState; }
        public void setUiState(UiState uiState) { this.uiState = uiState; }
        public PrescriptionDto getPrescription() { return prescription; }
        public void setPrescription(PrescriptionDto prescription) {
            this.prescription = prescription;
            this.uiState = (prescription == null) ? UiState.EMPTY : UiState.READY;
        }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            this.uiState = UiState.ERROR;
        }
    }
}
