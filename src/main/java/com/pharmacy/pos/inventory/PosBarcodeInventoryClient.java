package com.pharmacy.pos.inventory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosBarcodeInventoryClient
 * Architecture Decision: ADR-0218 (P218).
 * Phase 11: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS Client for Barcode Inventory Workflows:
 * - Receive: Inward scanning and staging guidance.
 * - Put-Away: Batch barcode + location barcode with storage policy enforcement.
 * - Move: Internal relocation between storage shelves/bins.
 * - Count: Physical cycle counts with discrepancy auditing.
 * - Verify: Station barcode lookup with FEFO expiry and quarantine alerts.
 * - Bilingual operator prompts in English and Bengali.
 */
public class PosBarcodeInventoryClient {

    public enum WorkflowType {
        RECEIVE("Inward Receive & Staging", "ইনওয়ার্ড গ্রহণ ও স্টেজিং"),
        PUT_AWAY("Put-Away to Shelf / Bin", "সেলফ/বিনে সংরক্ষণ"),
        MOVE("Internal Relocation", "অভ্যন্তরীণ স্থানান্তর"),
        COUNT("Cycle Count & Audit", "সাইকেল কাউন্ট ও অডিট"),
        VERIFY("Item & Expiry Verification", "আইটেম ও মেয়াদ যাচাই");

        public final String labelEn;
        public final String labelBn;

        WorkflowType(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static class ScanResult {
        private final WorkflowType workflowType;
        private final String productName;
        private final String batchNumber;
        private final String sourceLocation;
        private final String destinationLocation;
        private final int expectedQuantity;
        private final int scannedQuantity;
        private final int discrepancy;
        private final String operatorPromptEn;
        private final String operatorPromptBn;
        private final boolean isSuccess;
        private final String errorCode;

        public ScanResult(
                WorkflowType workflowType,
                String productName,
                String batchNumber,
                String sourceLocation,
                String destinationLocation,
                int expectedQuantity,
                int scannedQuantity,
                String operatorPromptEn,
                String operatorPromptBn,
                boolean isSuccess,
                String errorCode
        ) {
            this.workflowType = workflowType;
            this.productName = productName != null ? productName : "";
            this.batchNumber = batchNumber != null ? batchNumber : "";
            this.sourceLocation = sourceLocation != null ? sourceLocation : "";
            this.destinationLocation = destinationLocation != null ? destinationLocation : "";
            this.expectedQuantity = expectedQuantity;
            this.scannedQuantity = scannedQuantity;
            this.discrepancy = scannedQuantity - expectedQuantity;
            this.operatorPromptEn = operatorPromptEn;
            this.operatorPromptBn = operatorPromptBn;
            this.isSuccess = isSuccess;
            this.errorCode = errorCode != null ? errorCode : "";
        }

        public WorkflowType getWorkflowType() { return workflowType; }
        public String getProductName() { return productName; }
        public String getBatchNumber() { return batchNumber; }
        public String getSourceLocation() { return sourceLocation; }
        public String getDestinationLocation() { return destinationLocation; }
        public int getExpectedQuantity() { return expectedQuantity; }
        public int getScannedQuantity() { return scannedQuantity; }
        public int getDiscrepancy() { return discrepancy; }
        public String getOperatorPromptEn() { return operatorPromptEn; }
        public String getOperatorPromptBn() { return operatorPromptBn; }
        public boolean isSuccess() { return isSuccess; }
        public String getErrorCode() { return errorCode; }
    }

    private final Map<String, String> batchLocations = new ConcurrentHashMap<>();

    public ScanResult receive(String productName, String barcode, boolean isColdChain) {
        String promptEn;
        String promptBn;
        if (isColdChain) {
            promptEn = "Cold-Chain item '" + productName + "' received. Immediately transfer to refrigerated storage (2°C - 8°C).";
            promptBn = "কোল্ড-চেইন আইটেম '" + productName + "' গৃহীত হয়েছে। অবিলম্বে রেফ্রিজারেটরে (২°-৮° সে.) স্থানান্তর করুন।";
        } else {
            promptEn = "Item '" + productName + "' received. Scan destination storage shelf or bin barcode.";
            promptBn = "আইটেম '" + productName + "' গৃহীত হয়েছে। স্টোরেজ সেলফ বা বিন বারকোড স্ক্যান করুন।";
        }

        return new ScanResult(
                WorkflowType.RECEIVE,
                productName,
                "",
                "",
                "",
                0,
                1,
                promptEn,
                promptBn,
                true,
                ""
        );
    }

    public ScanResult putAway(
            String batchNumber,
            String destinationLocation,
            boolean isColdChain,
            boolean enforceColdChain
    ) {
        String dest = destinationLocation != null ? destinationLocation.trim().toUpperCase() : "";
        boolean isFridge = dest.contains("FRIDGE") || dest.contains("COLD") || dest.contains("CHILL");

        if (isColdChain && enforceColdChain && !isFridge) {
            String promptEn = "Storage policy violation: Cold-chain batch '" + batchNumber + "' cannot be placed in ambient zone '" + dest + "'. Scan refrigerator location.";
            String promptBn = "স্টোরেজ নীতি লঙ্ঘন: কোল্ড-চেইন ব্যাচ '" + batchNumber + "' সাধারণ স্থান '" + dest + "'-এ রাখা যাবে না। রেফ্রিজারেটর লোকেশন স্ক্যান করুন।";
            return new ScanResult(
                    WorkflowType.PUT_AWAY,
                    "",
                    batchNumber,
                    "",
                    dest,
                    0,
                    0,
                    promptEn,
                    promptBn,
                    false,
                    "ERR_STORAGE_POLICY_VIOLATION"
            );
        }

        batchLocations.put(batchNumber, dest);
        String promptEn = "Batch '" + batchNumber + "' placed in '" + dest + "' successfully.";
        String promptBn = "ব্যাচ '" + batchNumber + "' সফলভাবে '" + dest + "' লোকেশনে সংরক্ষণ করা হয়েছে।";

        return new ScanResult(
                WorkflowType.PUT_AWAY,
                "",
                batchNumber,
                "",
                dest,
                0,
                0,
                promptEn,
                promptBn,
                true,
                ""
        );
    }

    public ScanResult move(String batchNumber, String fromLocation, String toLocation) {
        String dest = toLocation != null ? toLocation.trim().toUpperCase() : "";
        batchLocations.put(batchNumber, dest);

        String promptEn = "Relocation complete: Batch '" + batchNumber + "' moved from '" + fromLocation + "' to '" + dest + "'.";
        String promptBn = "স্থানান্তর সম্পন্ন: ব্যাচ '" + batchNumber + "' '" + fromLocation + "' থেকে '" + dest + "'-এ স্থানান্তরিত হয়েছে।";

        return new ScanResult(
                WorkflowType.MOVE,
                "",
                batchNumber,
                fromLocation,
                dest,
                0,
                0,
                promptEn,
                promptBn,
                true,
                ""
        );
    }

    public ScanResult count(String batchNumber, String location, int countedQty, int systemQty) {
        int discrepancy = countedQty - systemQty;
        String promptEn;
        String promptBn;

        if (discrepancy == 0) {
            promptEn = "Count matched: " + countedQty + " units verified for batch '" + batchNumber + "' (Zero discrepancy).";
            promptBn = "গণনা মিলেছে: ব্যাচ '" + batchNumber + "'-এর জন্য " + countedQty + " ইউনিট নিশ্চিত (কোনো পার্থক্য নেই)।";
        } else {
            promptEn = "Discrepancy detected: Counted " + countedQty + " vs System " + systemQty + " (" + (discrepancy > 0 ? "+" : "") + discrepancy + " units). Logged for review.";
            promptBn = "পার্থক্য শনাক্ত: গণনা " + countedQty + " বনাম সিস্টেম " + systemQty + " (" + (discrepancy > 0 ? "+" : "") + discrepancy + " ইউনিট)। পর্যালোচনার জন্য রেকর্ড করা হয়েছে।";
        }

        return new ScanResult(
                WorkflowType.COUNT,
                "",
                batchNumber,
                location,
                "",
                systemQty,
                countedQty,
                promptEn,
                promptBn,
                true,
                ""
        );
    }

    public ScanResult verify(String productName, String batchNumber, int quantity, boolean isExpired, boolean isQuarantined) {
        String promptEn;
        String promptBn;

        if (isExpired) {
            promptEn = "EXPIRED LOT ALERT: Batch '" + batchNumber + "' is expired. DO NOT DISPENSE.";
            promptBn = "মেয়াদোত্তীর্ণ ব্যাচ সতর্কতা: ব্যাচ '" + batchNumber + "'-এর মেয়াদ শেষ। বিক্রি করবেন না।";
        } else if (isQuarantined) {
            promptEn = "QUARANTINE ALERT: Batch '" + batchNumber + "' is held in quarantine.";
            promptBn = "কোয়ারেন্টাইন সতর্কতা: ব্যাচ '" + batchNumber + "' কোয়ারেন্টাইনে রয়েছে।";
        } else {
            promptEn = "Verified: " + productName + " (Batch: " + batchNumber + ", Qty: " + quantity + ").";
            promptBn = "যাচাইকৃত: " + productName + " (ব্যাচ: " + batchNumber + ", পরিমাণ: " + quantity + ")।";
        }

        return new ScanResult(
                WorkflowType.VERIFY,
                productName,
                batchNumber,
                batchLocations.getOrDefault(batchNumber, ""),
                "",
                quantity,
                quantity,
                promptEn,
                promptBn,
                true,
                ""
        );
    }
}
