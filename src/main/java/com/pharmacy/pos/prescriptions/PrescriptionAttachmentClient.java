package com.pharmacy.pos.prescriptions;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Set;

/**
 * P242: Prescription Attachment Client & Upload Verification (JavaFX Desktop).
 * Architecture Decision: ADR-0242 (P242).
 */
public class PrescriptionAttachmentClient {

    public static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "application/pdf"
    );

    public static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".webp", ".pdf"
    );

    public static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    public static final long MIN_FILE_SIZE_BYTES = 100; // 100 Bytes
    public static final int STATUTORY_RETENTION_DAYS = 1825; // 5 Years

    public enum UploadState {
        IDLE,
        UPLOADING,
        SUCCESS,
        REJECTED_SECURITY,
        ERROR
    }

    public static class FileValidationResult {
        private final boolean isValid;
        private final String errorMessage;

        public FileValidationResult(boolean isValid, String errorMessage) {
            this.isValid = isValid;
            this.errorMessage = errorMessage;
        }

        public boolean isValid() { return isValid; }
        public String getErrorMessage() { return errorMessage; }

        public static FileValidationResult ok() {
            return new FileValidationResult(true, "");
        }

        public static FileValidationResult fail(String msg) {
            return new FileValidationResult(false, msg);
        }
    }

    public static FileValidationResult validateAttachmentFile(String filename, long fileSizeBytes, String mimeType) {
        if (filename == null || filename.isBlank()) {
            return FileValidationResult.fail("Filename is required.");
        }

        String lowerName = filename.toLowerCase();
        boolean validExt = ALLOWED_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
        if (!validExt) {
            return FileValidationResult.fail("Forbidden file format. Allowed: JPG, PNG, WEBP, PDF.");
        }

        if (fileSizeBytes < MIN_FILE_SIZE_BYTES) {
            return FileValidationResult.fail("File is empty or corrupted (minimum 100 bytes required).");
        }

        if (fileSizeBytes > MAX_FILE_SIZE_BYTES) {
            return FileValidationResult.fail("File size exceeds 10 MB limit.");
        }

        if (mimeType != null && !ALLOWED_MIME_TYPES.contains(mimeType.toLowerCase())) {
            return FileValidationResult.fail("MIME type '" + mimeType + "' is not permitted.");
        }

        return FileValidationResult.ok();
    }

    public static class PrescriptionAttachmentDto implements Serializable {
        private final String attachmentId;
        private final String prescriptionId;
        private final String fileName;
        private final String mimeType;
        private final long fileSizeBytes;
        private final String checksumSha256;
        private final int retentionDays;
        private final LocalDate retentionExpiresAt;
        private final boolean isVerifiedByPharmacist;
        private final boolean isQuarantined;

        public PrescriptionAttachmentDto(
                String attachmentId,
                String prescriptionId,
                String fileName,
                String mimeType,
                long fileSizeBytes,
                String checksumSha256,
                int retentionDays,
                LocalDate retentionExpiresAt,
                boolean isVerifiedByPharmacist,
                boolean isQuarantined
        ) {
            this.attachmentId = attachmentId;
            this.prescriptionId = prescriptionId;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.fileSizeBytes = fileSizeBytes;
            this.checksumSha256 = checksumSha256;
            this.retentionDays = retentionDays;
            this.retentionExpiresAt = retentionExpiresAt != null ? retentionExpiresAt : LocalDate.now().plusDays(retentionDays);
            this.isVerifiedByPharmacist = isVerifiedByPharmacist;
            this.isQuarantined = isQuarantined;
        }

        public String getAttachmentId() { return attachmentId; }
        public String getPrescriptionId() { return prescriptionId; }
        public String getFileName() { return fileName; }
        public String getMimeType() { return mimeType; }
        public long getFileSizeBytes() { return fileSizeBytes; }
        public String getChecksumSha256() { return checksumSha256; }
        public int getRetentionDays() { return retentionDays; }
        public LocalDate getRetentionExpiresAt() { return retentionExpiresAt; }
        public boolean isVerifiedByPharmacist() { return isVerifiedByPharmacist; }
        public boolean isQuarantined() { return isQuarantined; }
    }
}
