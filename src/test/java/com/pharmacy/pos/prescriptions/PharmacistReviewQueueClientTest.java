package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P243: Pharmacist Review Queue Desktop Tests.
 */
public class PharmacistReviewQueueClientTest {

    @Test
    @DisplayName("P243-Desktop-1: Bilingual decision actions localization")
    void testDecisionActionLocalization() {
        assertEquals("Approve & Verify", PharmacistReviewQueueClient.ReviewDecision.VERIFY.getLocalized("en"));
        assertEquals("অনুমোদন ও যাচাই করুন", PharmacistReviewQueueClient.ReviewDecision.VERIFY.getLocalized("bn"));

        assertEquals("Reject Prescription", PharmacistReviewQueueClient.ReviewDecision.REJECT.getLocalized("en"));
        assertEquals("প্রেসক্রিপশন প্রত্যাখ্যান করুন", PharmacistReviewQueueClient.ReviewDecision.REJECT.getLocalized("bn"));

        assertEquals("Request Clarification", PharmacistReviewQueueClient.ReviewDecision.REQUEST_CLARIFICATION.getLocalized("en"));
        assertEquals("ব্যাখ্যা বা স্পষ্টীকরণ চান", PharmacistReviewQueueClient.ReviewDecision.REQUEST_CLARIFICATION.getLocalized("bn"));

        assertEquals("Resolve Clarification", PharmacistReviewQueueClient.ReviewDecision.RESOLVE_CLARIFICATION.getLocalized("en"));
        assertEquals("ব্যাখ্যার উত্তর লিপিবদ্ধ করুন", PharmacistReviewQueueClient.ReviewDecision.RESOLVE_CLARIFICATION.getLocalized("bn"));
    }

    @Test
    @DisplayName("P243-Desktop-2: Queue priority, item counts, and audit trail DTO mapping")
    void testQueueItemAndAuditTrailDto() {
        // Priority prescription containing antibiotics
        var priorityItem = new PharmacistReviewQueueClient.QueueItemDto(
                "rx-101", "RX-202611-101", "Dr. A. K. Azad", "BMDC-44211",
                "PT-PRIORITY", "PENDING", true, 2, 1
        );
        assertTrue(priorityItem.isPriority());
        assertEquals("PENDING", priorityItem.getStatus());
        assertEquals(2, priorityItem.getItemCount());
        assertEquals(1, priorityItem.getAttachmentCount());

        // Routine non-antibiotic item
        var routineItem = new PharmacistReviewQueueClient.QueueItemDto(
                "rx-102", "RX-202611-102", "Dr. A. K. Azad", "BMDC-44211",
                "PT-ROUTINE", "PENDING", false, 1, 0
        );
        assertFalse(routineItem.isPriority());

        // Audit Trail DTO
        var audit = new PharmacistReviewQueueClient.ClinicalAuditRecordDto(
                "pva-9901", "rx-101", "REQUEST_CLARIFICATION",
                "PENDING", "CLARIFICATION_REQUESTED", "PCB-99214",
                "Dosage frequency ambiguous", "Confirm if 500mg TDS or BD for child", "",
                LocalDateTime.now()
        );
        assertEquals("pva-9901", audit.getAuditId());
        assertEquals("REQUEST_CLARIFICATION", audit.getAction());
        assertEquals("CLARIFICATION_REQUESTED", audit.getToStatus());
        assertEquals("PCB-99214", audit.getPharmacistPcbNo());
    }
}

