package com.pharmacy.pos.prescriptions;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * P246: Statutory Antibiotic Dispense Register Client (JavaFX Desktop).
 * Architecture Decision: ADR-0246 (P246).
 */
public class AntibioticRegisterClient {

    public enum RegisterStatus {
        ACTIVE("Active Dispense", "সক্রিয় সরবরাহ"),
        PARTIALLY_RETURNED("Partially Returned", "আংশিক ফেরত"),
        REVERSED("Reversed / Voided", "সম্পূর্ণ বাতিল / প্রত্যাহারকৃত");

        private final String enLabel;
        private final String bnLabel;

        RegisterStatus(String enLabel, String bnLabel) {
            this.enLabel = enLabel;
            this.bnLabel = bnLabel;
        }

        public String getLocalized(String lang) {
            return "bn".equalsIgnoreCase(lang) ? bnLabel : enLabel;
        }
    }

    public static class AntibioticRegisterEntryDto implements Serializable {
        private final String registerEntryId;
        private final String orderNumber;
        private final String prescriptionNumber;
        private final String brandName;
        private final String genericName;
        private final String classificationTier;
        private final int dispensedBaseUnits;
        private final int returnedBaseUnits;
        private final String doctorBmdcNo;
        private final String pharmacistPcbNo;
        private final RegisterStatus status;
        private final String sha256Hash;

        public AntibioticRegisterEntryDto(
                String registerEntryId,
                String orderNumber,
                String prescriptionNumber,
                String brandName,
                String genericName,
                String classificationTier,
                int dispensedBaseUnits,
                int returnedBaseUnits,
                String doctorBmdcNo,
                String pharmacistPcbNo,
                RegisterStatus status,
                String sha256Hash
        ) {
            this.registerEntryId = registerEntryId;
            this.orderNumber = orderNumber;
            this.prescriptionNumber = prescriptionNumber;
            this.brandName = brandName;
            this.genericName = genericName;
            this.classificationTier = classificationTier;
            this.dispensedBaseUnits = dispensedBaseUnits;
            this.returnedBaseUnits = returnedBaseUnits;
            this.doctorBmdcNo = doctorBmdcNo;
            this.pharmacistPcbNo = pharmacistPcbNo;
            this.status = status;
            this.sha256Hash = sha256Hash;
        }

        public String getRegisterEntryId() { return registerEntryId; }
        public String getOrderNumber() { return orderNumber; }
        public String getPrescriptionNumber() { return prescriptionNumber; }
        public String getBrandName() { return brandName; }
        public String getGenericName() { return genericName; }
        public String getClassificationTier() { return classificationTier; }
        public int getDispensedBaseUnits() { return dispensedBaseUnits; }
        public int getReturnedBaseUnits() { return returnedBaseUnits; }
        public int getNetUnits() { return Math.max(0, dispensedBaseUnits - returnedBaseUnits); }
        public String getDoctorBmdcNo() { return doctorBmdcNo; }
        public String getPharmacistPcbNo() { return pharmacistPcbNo; }
        public RegisterStatus getStatus() { return status; }
        public String getSha256Hash() { return sha256Hash; }
    }

    /**
     * Validates client preflight for recording an antibiotic dispense entry.
     */
    public static boolean validateDispensePreflight(
            boolean isAntibiotic,
            int quantity,
            String pharmacistPcbNo,
            String doctorBmdcNo,
            String prescriptionId
    ) {
        if (!isAntibiotic || quantity < 1) return false;
        if (pharmacistPcbNo == null || pharmacistPcbNo.trim().isEmpty()) return false;
        if (doctorBmdcNo == null || doctorBmdcNo.trim().isEmpty()) return false;
        if (prescriptionId == null || prescriptionId.trim().isEmpty()) return false;
        return true;
    }
}

