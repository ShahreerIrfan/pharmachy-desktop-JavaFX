package com.pharmacy.pos.prescriptions;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * P245: POS Rx-Item Matching & Substitution Prohibition Client (JavaFX Desktop).
 * Architecture Decision: ADR-0245 (P245).
 */
public class RxItemMatchingClient {

    public enum MatchResultStatus {
        MATCHED("Matched", "অনুমোদিত ও মিলেছে"),
        EXCEEDS_QUANTITY("Exceeds Quantity", "অনুমোদিত পরিমাণের অতিরিক্ত"),
        SUBSTITUTION_PROHIBITED("Substitution Prohibited", "অননুমোদিত ব্র্যান্ড প্রতিস্থাপন নিষিদ্ধ"),
        ALREADY_DISPENSED("Already Dispensed", "ইতিমধ্যে সরবরাহ সম্পন্ন"),
        OTC_EXEMPT("OTC Exempt", "প্রেসক্রিপশন প্রয়োজন নেই");

        private final String enLabel;
        private final String bnLabel;

        MatchResultStatus(String enLabel, String bnLabel) {
            this.enLabel = enLabel;
            this.bnLabel = bnLabel;
        }

        public String getLocalized(String lang) {
            return "bn".equalsIgnoreCase(lang) ? bnLabel : enLabel;
        }
    }

    public static class MatchedLineDto implements Serializable {
        private final String productId;
        private final String productName;
        private final int requestedQuantity;
        private final MatchResultStatus matchStatus;
        private final int remainingQuantity;
        private final String error;

        public MatchedLineDto(
                String productId,
                String productName,
                int requestedQuantity,
                MatchResultStatus matchStatus,
                int remainingQuantity,
                String error
        ) {
            this.productId = productId;
            this.productName = productName;
            this.requestedQuantity = requestedQuantity;
            this.matchStatus = matchStatus;
            this.remainingQuantity = remainingQuantity;
            this.error = error;
        }

        public String getProductId() { return productId; }
        public String getProductName() { return productName; }
        public int getRequestedQuantity() { return requestedQuantity; }
        public MatchResultStatus getMatchStatus() { return matchStatus; }
        public int getRemainingQuantity() { return remainingQuantity; }
        public String getError() { return error; }
        public boolean isSuccess() {
            return matchStatus == MatchResultStatus.MATCHED || matchStatus == MatchResultStatus.OTC_EXEMPT;
        }
    }

    public static class MatchingReportDto implements Serializable {
        private final String prescriptionId;
        private final boolean allMatched;
        private final boolean canProceed;
        private final List<MatchedLineDto> matchedItems;
        private final List<String> errors;

        public MatchingReportDto(
                String prescriptionId,
                boolean allMatched,
                boolean canProceed,
                List<MatchedLineDto> matchedItems,
                List<String> errors
        ) {
            this.prescriptionId = prescriptionId;
            this.allMatched = allMatched;
            this.canProceed = canProceed;
            this.matchedItems = matchedItems != null ? matchedItems : Collections.emptyList();
            this.errors = errors != null ? errors : Collections.emptyList();
        }

        public String getPrescriptionId() { return prescriptionId; }
        public boolean isAllMatched() { return allMatched; }
        public boolean canProceed() { return canProceed; }
        public List<MatchedLineDto> getMatchedItems() { return matchedItems; }
        public List<String> getErrors() { return errors; }
    }

    /**
     * Local client-side matching helper to evaluate cart line vs prescribed item.
     */
    public static MatchedLineDto matchCartLine(
            String prescribedProductId,
            String prescribedBrandName,
            int remainingAuthorizedQty,
            String cartProductId,
            String cartBrandName,
            int cartQuantity,
            boolean isRxRequired
    ) {
        if (!isRxRequired) {
            return new MatchedLineDto(cartProductId, cartBrandName, cartQuantity, MatchResultStatus.OTC_EXEMPT, cartQuantity, null);
        }

        // Strict exact product matching: NO automated substitution
        if (!prescribedProductId.equalsIgnoreCase(cartProductId)) {
            String err = "Automated treatment substitution is prohibited. Prescribed: " + prescribedBrandName + ", Cart: " + cartBrandName;
            return new MatchedLineDto(cartProductId, cartBrandName, cartQuantity, MatchResultStatus.SUBSTITUTION_PROHIBITED, 0, err);
        }

        if (remainingAuthorizedQty <= 0) {
            return new MatchedLineDto(cartProductId, cartBrandName, cartQuantity, MatchResultStatus.ALREADY_DISPENSED, 0, "Item fully dispensed");
        }

        if (cartQuantity > remainingAuthorizedQty) {
            String err = "Requested " + cartQuantity + " exceeds remaining authorized quota " + remainingAuthorizedQty;
            return new MatchedLineDto(cartProductId, cartBrandName, cartQuantity, MatchResultStatus.EXCEEDS_QUANTITY, remainingAuthorizedQty, err);
        }

        return new MatchedLineDto(cartProductId, cartBrandName, cartQuantity, MatchResultStatus.MATCHED, remainingAuthorizedQty - cartQuantity, null);
    }
}

