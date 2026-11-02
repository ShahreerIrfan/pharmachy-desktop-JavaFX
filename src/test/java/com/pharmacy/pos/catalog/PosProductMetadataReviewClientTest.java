package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PosProductMetadataReviewClientTest {

    private PosProductMetadataReviewClient client;
    private final String productId = "med-test-001";

    @BeforeEach
    void setUp() {
        client = new PosProductMetadataReviewClient();
        Map<String, Object> initialAttributes = new HashMap<>();
        initialAttributes.put("brand_name", "Napa Extra");
        initialAttributes.put("generic_name", "Paracetamol + Caffeine");
        initialAttributes.put("dosage_form", "Tablet");
        initialAttributes.put("strength", "500mg+65mg");
        initialAttributes.put("mrp_unit", 2.50);
        initialAttributes.put("curation_status", "DRAFT");
        initialAttributes.put("version_number", 1);
        client.registerProduct(productId, initialAttributes);
    }

    @Test
    @DisplayName("P133-D1: Propose metadata update with diff tracking captures old and new values")
    void testProposeMetadataReviewWithDiffTracking() {
        Map<String, Object> changes = new HashMap<>();
        changes.put("mrp_unit", 3.50);
        changes.put("dosage_form", "Extended Release Tablet");

        var result = client.proposeReview(
                productId,
                PosProductMetadataReviewClient.ReviewChangeType.METADATA_UPDATE,
                changes,
                "pharmacist1@pharmacy.local",
                "STAFF_PHARMACIST",
                "Clinical update per DGDA notification"
        );

        assertEquals(PosProductMetadataReviewClient.UiStatus.SUCCESS, result.status);
        assertNotNull(result.data);
        assertEquals(productId, result.data.productId);
        assertEquals(PosProductMetadataReviewClient.ReviewStatus.PENDING_REVIEW, result.data.status);
        assertEquals("pharmacist1@pharmacy.local", result.data.submitterEmail);

        // Check diff
        Map<String, PosProductMetadataReviewClient.FieldDiff> diff = result.data.proposedDiff;
        assertEquals(2.50, diff.get("mrp_unit").oldValue);
        assertEquals(3.50, diff.get("mrp_unit").newValue);
        assertEquals("Tablet", diff.get("dosage_form").oldValue);
        assertEquals("Extended Release Tablet", diff.get("dosage_form").newValue);

        // Verify product curation status became UNDER_REVIEW
        assertEquals("UNDER_REVIEW", client.getProduct(productId).get("curation_status"));
    }

    @Test
    @DisplayName("P133-D2: Independent pharmacist approves review and diff merges atomically into master catalog")
    void testPharmacistApprovesReviewAndMergesDiffToMaster() {
        Map<String, Object> changes = new HashMap<>();
        changes.put("mrp_unit", 4.00);

        var proposeResult = client.proposeReview(
                productId,
                PosProductMetadataReviewClient.ReviewChangeType.PRICE_REVISION,
                changes,
                "pharmacist1@pharmacy.local",
                "STAFF_PHARMACIST",
                "Wholesale price revision"
        );
        assertEquals(PosProductMetadataReviewClient.UiStatus.SUCCESS, proposeResult.status);
        String reviewId = proposeResult.data.reviewId;

        // Independent Pharmacist 2 approves
        var approveResult = client.approveReview(
                reviewId,
                "pharmacist2@pharmacy.local",
                "STAFF_PHARMACIST",
                "Approved after cross-verifying manufacturer catalog gazette."
        );

        assertEquals(PosProductMetadataReviewClient.UiStatus.SUCCESS, approveResult.status);
        assertEquals(PosProductMetadataReviewClient.ReviewStatus.APPROVED, approveResult.data.status);
        assertEquals("pharmacist2@pharmacy.local", approveResult.data.reviewerEmail);
        assertNotNull(approveResult.data.reviewedAt);

        // Verify master product catalog was updated
        Map<String, Object> product = client.getProduct(productId);
        assertEquals(4.00, product.get("mrp_unit"));
        assertEquals("APPROVED", product.get("curation_status"));
        assertEquals(2, product.get("version_number"));
    }

    @Test
    @DisplayName("P133-D3: Rejection with notes marks status REJECTED and leaves product unchanged")
    void testRejectionWithMandatoryNotesMarksRejected() {
        Map<String, Object> changes = new HashMap<>();
        changes.put("dosage_form", "Syrup");

        var proposeResult = client.proposeReview(
                productId,
                PosProductMetadataReviewClient.ReviewChangeType.METADATA_UPDATE,
                changes,
                "pharmacist1@pharmacy.local",
                "STAFF_PHARMACIST",
                "Proposing form change"
        );
        String reviewId = proposeResult.data.reviewId;

        var rejectResult = client.rejectReview(
                reviewId,
                "pharmacist2@pharmacy.local",
                "STAFF_PHARMACIST",
                "Rejected: Dosage form is strictly solid oral tablet per registered DAR."
        );

        assertEquals(PosProductMetadataReviewClient.UiStatus.SUCCESS, rejectResult.status);
        assertEquals(PosProductMetadataReviewClient.ReviewStatus.REJECTED, rejectResult.data.status);
        assertEquals("pharmacist2@pharmacy.local", rejectResult.data.reviewerEmail);

        // Product unchanged
        Map<String, Object> product = client.getProduct(productId);
        assertEquals("Tablet", product.get("dosage_form"));
    }

    @Test
    @DisplayName("P133-D4: Submitter self-approval blocked by Four-Eyes Principle (Dual Control)")
    void testFourEyesPrincipleBlocksSubmitterSelfApproval() {
        Map<String, Object> changes = new HashMap<>();
        changes.put("mrp_unit", 5.00);

        var proposeResult = client.proposeReview(
                productId,
                PosProductMetadataReviewClient.ReviewChangeType.PRICE_REVISION,
                changes,
                "pharmacist1@pharmacy.local",
                "STAFF_PHARMACIST",
                "Price increase"
        );
        String reviewId = proposeResult.data.reviewId;

        // Submitter attempts self-approval
        var selfApproveResult = client.approveReview(
                reviewId,
                "pharmacist1@pharmacy.local",
                "STAFF_PHARMACIST",
                "Self approving my own proposal."
        );

        assertEquals(PosProductMetadataReviewClient.UiStatus.PERMISSION_DENIED, selfApproveResult.status);
        assertEquals("ERR_FOUR_EYES_PRINCIPLE_VIOLATION", selfApproveResult.errorCode);
    }

    @Test
    @DisplayName("P133-D5: Cashier role blocked from submitting or approving reviews")
    void testCashierRoleDeniedFromSubmittingOrApproving() {
        Map<String, Object> changes = new HashMap<>();
        changes.put("mrp_unit", 6.00);

        // Cashier propose attempt
        var cashierPropose = client.proposeReview(
                productId,
                PosProductMetadataReviewClient.ReviewChangeType.PRICE_REVISION,
                changes,
                "cashier@pharmacy.local",
                "CASHIER",
                "Cashier submission"
        );
        assertEquals(PosProductMetadataReviewClient.UiStatus.PERMISSION_DENIED, cashierPropose.status);
        assertEquals("ERR_STAFF_ROLE_DENIED", cashierPropose.errorCode);

        // Valid submission by pharmacist
        var validPropose = client.proposeReview(
                productId,
                PosProductMetadataReviewClient.ReviewChangeType.PRICE_REVISION,
                changes,
                "pharmacist1@pharmacy.local",
                "STAFF_PHARMACIST",
                "Valid submission"
        );
        String reviewId = validPropose.data.reviewId;

        // Cashier approve attempt
        var cashierApprove = client.approveReview(
                reviewId,
                "cashier@pharmacy.local",
                "CASHIER",
                "Cashier trying to approve"
        );
        assertEquals(PosProductMetadataReviewClient.UiStatus.PERMISSION_DENIED, cashierApprove.status);
        assertEquals("ERR_STAFF_ROLE_DENIED", cashierApprove.errorCode);
    }

    @Test
    @DisplayName("P133-D6: Offline fallback serves cached review history safely")
    void testOfflineFallbackServesCachedReviewHistory() {
        Map<String, Object> changes = new HashMap<>();
        changes.put("dosage_form", "Chewable Tablet");

        var proposeResult = client.proposeReview(
                productId,
                PosProductMetadataReviewClient.ReviewChangeType.METADATA_UPDATE,
                changes,
                "pharmacist1@pharmacy.local",
                "STAFF_PHARMACIST",
                "Form update"
        );
        assertEquals(PosProductMetadataReviewClient.UiStatus.SUCCESS, proposeResult.status);

        // Disconnect to offline mode
        client.setOffline(true);
        assertTrue(client.isOffline());

        var historyResult = client.getReviewHistory(productId);
        assertEquals(PosProductMetadataReviewClient.UiStatus.OFFLINE, historyResult.status);
        assertNotNull(historyResult.data);
        assertEquals(1, historyResult.data.size());
        assertEquals(proposeResult.data.reviewId, historyResult.data.get(0).reviewId);
    }
}

