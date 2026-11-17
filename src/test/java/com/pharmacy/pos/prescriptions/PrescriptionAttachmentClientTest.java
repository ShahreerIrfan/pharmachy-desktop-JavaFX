package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P242: Prescription Attachment Client Desktop Tests.
 */
public class PrescriptionAttachmentClientTest {

    @Test
    @DisplayName("P242-Desktop-1: Client-side validation for valid and invalid file formats")
    void testFileValidationRules() {
        // 1. Valid JPEG
        var validJpg = PrescriptionAttachmentClient.validateAttachmentFile(
                "rx_dr_nasim.jpg", 150000, "image/jpeg"
        );
        assertTrue(validJpg.isValid());
        assertEquals("", validJpg.getErrorMessage());

        // 2. Valid PDF
        var validPdf = PrescriptionAttachmentClient.validateAttachmentFile(
                "clinical_rx.pdf", 450000, "application/pdf"
        );
        assertTrue(validPdf.isValid());

        // 3. Prohibited Executable
        var invalidExe = PrescriptionAttachmentClient.validateAttachmentFile(
                "malicious_payload.exe", 2048, "application/x-msdownload"
        );
        assertFalse(invalidExe.isValid());
        assertTrue(invalidExe.getErrorMessage().contains("Forbidden file format"));

        // 4. Oversized file (>10 MB)
        var oversized = PrescriptionAttachmentClient.validateAttachmentFile(
                "scan_large.png", 11 * 1024 * 1024, "image/png"
        );
        assertFalse(oversized.isValid());
        assertTrue(oversized.getErrorMessage().contains("10 MB limit"));

        // 5. Zero-byte empty file
        var emptyFile = PrescriptionAttachmentClient.validateAttachmentFile(
                "empty_scan.jpg", 10, "image/jpeg"
        );
        assertFalse(emptyFile.isValid());
        assertTrue(emptyFile.getErrorMessage().contains("minimum 100 bytes"));
    }

    @Test
    @DisplayName("P242-Desktop-2: DTO integrity and statutory retention policy calculation")
    void testAttachmentDtoAndRetention() {
        LocalDate expectedExpiry = LocalDate.now().plusDays(1825);

        PrescriptionAttachmentClient.PrescriptionAttachmentDto dto = new PrescriptionAttachmentClient.PrescriptionAttachmentDto(
                "att-rx-1001",
                "rx-202611-001",
                "rx_scan_page1.jpg",
                "image/jpeg",
                256000,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                PrescriptionAttachmentClient.STATUTORY_RETENTION_DAYS,
                expectedExpiry,
                true,
                false
        );

        assertEquals("att-rx-1001", dto.getAttachmentId());
        assertEquals("rx-202611-001", dto.getPrescriptionId());
        assertEquals(1825, dto.getRetentionDays());
        assertEquals(expectedExpiry, dto.getRetentionExpiresAt());
        assertTrue(dto.isVerifiedByPharmacist());
        assertFalse(dto.isQuarantined());
    }
}
