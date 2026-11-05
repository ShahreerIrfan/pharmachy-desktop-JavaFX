package com.pharmacy.pos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/**
 * PosLandedCostClient
 * Architecture Decision: ADR-0176 (P176).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Desktop POS client handling landed transport, freight, handling, and cold-chain
 * logistics cost allocation across purchase cost lots with exact penny conservation,
 * GAAP/IAS 2 inventory capitalization, and reversible ledger tracking.
 */
public class PosLandedCostClient {

    public enum ExpenseType {
        TRANSPORT_FREIGHT("Transport & Freight", "পরিবহন ও ভাড়া খরচ"),
        HANDLING_LABOR("Handling & Unloading Labor", "পণ্য খালাস ও হ্যান্ডলিং মজুরি"),
        INSURANCE_TARIFF("Transit Insurance & Tariff", "পরিবহন বীমা ও আমদানি শুল্ক"),
        COLD_CHAIN_LOGISTICS("Cold-Chain Ice/Refrigeration", "কোল্ড-চেইন আইস ও ফ্রিজিং খরচ"),
        REGULATORY_FEE("DGDA / Port Regulatory Clearing Fee", "ডিজিডিএ / পোর্ট ছাড়পত্র ফি"),
        OTHER("Other Landed Incidental", "অন্যান্য প্রত্যক্ষ খরচ");

        public final String labelEn;
        public final String labelBn;

        ExpenseType(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum AllocationMethod {
        BY_VALUE("Pro-Rata by Purchase Value", "ক্রয়মূল্য অনুপাতে বণ্টন"),
        BY_QUANTITY("Pro-Rata by Quantity", "পরিমাণ অনুপাতে বণ্টন"),
        MANUAL_EQUAL("Equal Split Across Cost Lots", "সমান ভাগে বণ্টন");

        public final String labelEn;
        public final String labelBn;

        AllocationMethod(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum LandedCostStatus {
        DRAFT("Draft Calculation", "খসড়া হিসাব"),
        POSTED("Posted & Capitalized", "পোস্টকৃত ও মূল্যায়িত"),
        REVERSED("Reversed", "বাতিল ও সমন্বয়কৃত");

        public final String labelEn;
        public final String labelBn;

        LandedCostStatus(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public static final class CostLotInput {
        public final String costLotId;
        public final String batchId;
        public final String productId;
        public final String productName;
        public final BigDecimal basePurchaseUnitCost;
        public final int initialQuantity;

        public CostLotInput(
                String costLotId,
                String batchId,
                String productId,
                String productName,
                BigDecimal basePurchaseUnitCost,
                int initialQuantity) {
            this.costLotId = costLotId != null ? costLotId : "";
            this.batchId = batchId != null ? batchId : "";
            this.productId = productId != null ? productId : "";
            this.productName = productName != null ? productName : "";
            this.basePurchaseUnitCost = basePurchaseUnitCost != null ? basePurchaseUnitCost.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.initialQuantity = initialQuantity;
        }

        public BigDecimal getLineValue() {
            return basePurchaseUnitCost.multiply(BigDecimal.valueOf(initialQuantity)).setScale(4, RoundingMode.HALF_UP);
        }
    }

    public static final class LandedCostAllocationLineDto {
        public final String lineId;
        public final String costLotId;
        public final String batchId;
        public final String productId;
        public final String productName;
        public final BigDecimal basePurchaseUnitCost;
        public final int initialQuantity;
        public final BigDecimal lineValue;
        public final BigDecimal allocationFactor;
        public final BigDecimal allocatedExpenseAmount;
        public final BigDecimal landedUnitCost;

        public LandedCostAllocationLineDto(
                String lineId,
                String costLotId,
                String batchId,
                String productId,
                String productName,
                BigDecimal basePurchaseUnitCost,
                int initialQuantity,
                BigDecimal lineValue,
                BigDecimal allocationFactor,
                BigDecimal allocatedExpenseAmount,
                BigDecimal landedUnitCost) {
            this.lineId = lineId != null ? lineId : "";
            this.costLotId = costLotId != null ? costLotId : "";
            this.batchId = batchId != null ? batchId : "";
            this.productId = productId != null ? productId : "";
            this.productName = productName != null ? productName : "";
            this.basePurchaseUnitCost = basePurchaseUnitCost != null ? basePurchaseUnitCost.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.initialQuantity = initialQuantity;
            this.lineValue = lineValue != null ? lineValue.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.allocationFactor = allocationFactor != null ? allocationFactor.setScale(6, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
            this.allocatedExpenseAmount = allocatedExpenseAmount != null ? allocatedExpenseAmount.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.landedUnitCost = landedUnitCost != null ? landedUnitCost.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
    }

    public static final class LandedCostExpenseDto {
        public final String expenseId;
        public final String tenantId;
        public final String branchId;
        public final String grnId;
        public final ExpenseType expenseType;
        public final AllocationMethod allocationMethod;
        public final BigDecimal totalExpenseBdt;
        public final String invoiceOrReference;
        public final String carrierOrVendor;
        public final String notes;
        public LandedCostStatus status;
        public final String recordedBy;
        public final Instant createdAt;
        public Instant reversedAt;
        public String reversedBy;
        public String reversalReason;
        public final List<LandedCostAllocationLineDto> lines;

        public LandedCostExpenseDto(
                String expenseId,
                String tenantId,
                String branchId,
                String grnId,
                ExpenseType expenseType,
                AllocationMethod allocationMethod,
                BigDecimal totalExpenseBdt,
                String invoiceOrReference,
                String carrierOrVendor,
                String notes,
                LandedCostStatus status,
                String recordedBy,
                Instant createdAt,
                Instant reversedAt,
                String reversedBy,
                String reversalReason,
                List<LandedCostAllocationLineDto> lines) {
            this.expenseId = expenseId != null ? expenseId : "";
            this.tenantId = tenantId != null ? tenantId : "";
            this.branchId = branchId != null ? branchId : "";
            this.grnId = grnId != null ? grnId : "";
            this.expenseType = expenseType;
            this.allocationMethod = allocationMethod;
            this.totalExpenseBdt = totalExpenseBdt != null ? totalExpenseBdt.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            this.invoiceOrReference = invoiceOrReference != null ? invoiceOrReference : "";
            this.carrierOrVendor = carrierOrVendor != null ? carrierOrVendor : "";
            this.notes = notes != null ? notes : "";
            this.status = status;
            this.recordedBy = recordedBy != null ? recordedBy : "";
            this.createdAt = createdAt != null ? createdAt : Instant.now();
            this.reversedAt = reversedAt;
            this.reversedBy = reversedBy;
            this.reversalReason = reversalReason;
            this.lines = lines != null ? new ArrayList<>(lines) : new ArrayList<>();
        }
    }

    public static final class ClientResult<T> {
        public final boolean success;
        public final T data;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        private ClientResult(boolean success, T data, String errorCode, String messageEn, String messageBn) {
            this.success = success;
            this.data = data;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static <T> ClientResult<T> ok(T data, String msgEn, String msgBn) {
            return new ClientResult<>(true, data, null, msgEn, msgBn);
        }

        public static <T> ClientResult<T> error(String code, String msgEn, String msgBn) {
            return new ClientResult<>(false, null, code, msgEn, msgBn);
        }
    }

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, LandedCostExpenseDto> expenseStore;

    public PosLandedCostClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline) {
        this(tenantId, branchId, userRole, userId, isOffline, new HashMap<>());
    }

    public PosLandedCostClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, LandedCostExpenseDto> sharedStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "";
        this.userId = userId != null ? userId : "";
        this.isOffline = isOffline;
        this.expenseStore = sharedStore != null ? sharedStore : new HashMap<>();
    }

    private boolean isAuthorized() {
        return "TENANT_ADMIN".equalsIgnoreCase(userRole)
                || "STORE_MANAGER".equalsIgnoreCase(userRole)
                || "PHARMACY_OWNER".equalsIgnoreCase(userRole);
    }

    /**
     * Preview landed cost allocations across cost lots with exact penny conservation
     * without persisting to the database.
     */
    public ClientResult<List<LandedCostAllocationLineDto>> previewAllocations(
            BigDecimal totalExpenseBdt,
            AllocationMethod method,
            List<CostLotInput> lots) {

        if (totalExpenseBdt == null || totalExpenseBdt.compareTo(BigDecimal.ZERO) <= 0) {
            return ClientResult.error("ERR_INVALID_AMOUNT",
                    "Total landed cost expense must be greater than zero.",
                    "মোট ল্যান্ডেড খরচ শূন্যের বেশি হতে হবে।");
        }

        if (lots == null || lots.isEmpty()) {
            return ClientResult.error("ERR_NO_LOTS",
                    "At least one cost lot must be provided for allocation.",
                    "খরচ বণ্টনের জন্য অন্তত একটি কস্ট লট নির্বাচন করতে হবে।");
        }

        BigDecimal scaledTotal = totalExpenseBdt.setScale(4, RoundingMode.HALF_UP);
        List<LandedCostAllocationLineDto> lines = new ArrayList<>();

        if (method == AllocationMethod.BY_QUANTITY) {
            int totalQty = lots.stream().mapToInt(l -> l.initialQuantity).sum();
            if (totalQty <= 0) {
                return ClientResult.error("ERR_ZERO_QUANTITY",
                        "Total initial quantity cannot be zero.",
                        "মোট প্রারম্ভিক পরিমাণ শূন্য হতে পারে না।");
            }

            BigDecimal runningAllocated = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            int maxQtyIdx = 0;
            int maxQty = -1;

            for (int i = 0; i < lots.size(); i++) {
                CostLotInput lot = lots.get(i);
                if (lot.initialQuantity > maxQty) {
                    maxQty = lot.initialQuantity;
                    maxQtyIdx = i;
                }

                BigDecimal factor = BigDecimal.valueOf(lot.initialQuantity)
                        .divide(BigDecimal.valueOf(totalQty), 6, RoundingMode.HALF_UP);

                BigDecimal allocated = scaledTotal.multiply(BigDecimal.valueOf(lot.initialQuantity))
                        .divide(BigDecimal.valueOf(totalQty), 4, RoundingMode.HALF_UP);
                runningAllocated = runningAllocated.add(allocated);

                BigDecimal perUnitAdd = allocated.divide(BigDecimal.valueOf(lot.initialQuantity), 4, RoundingMode.HALF_UP);
                BigDecimal landedUnit = lot.basePurchaseUnitCost.add(perUnitAdd).setScale(4, RoundingMode.HALF_UP);

                lines.add(new LandedCostAllocationLineDto(
                        "line-" + UUID.randomUUID().toString().substring(0, 8),
                        lot.costLotId,
                        lot.batchId,
                        lot.productId,
                        lot.productName,
                        lot.basePurchaseUnitCost,
                        lot.initialQuantity,
                        lot.getLineValue(),
                        factor,
                        allocated,
                        landedUnit
                ));
            }

            // Remainder correction for penny conservation
            BigDecimal remainder = scaledTotal.subtract(runningAllocated);
            if (remainder.compareTo(BigDecimal.ZERO) != 0 && maxQtyIdx < lines.size()) {
                LandedCostAllocationLineDto oldMax = lines.get(maxQtyIdx);
                BigDecimal fixedAlloc = oldMax.allocatedExpenseAmount.add(remainder).setScale(4, RoundingMode.HALF_UP);
                BigDecimal perUnitAdd = fixedAlloc.divide(BigDecimal.valueOf(oldMax.initialQuantity), 4, RoundingMode.HALF_UP);
                BigDecimal fixedLandedUnit = oldMax.basePurchaseUnitCost.add(perUnitAdd).setScale(4, RoundingMode.HALF_UP);

                lines.set(maxQtyIdx, new LandedCostAllocationLineDto(
                        oldMax.lineId,
                        oldMax.costLotId,
                        oldMax.batchId,
                        oldMax.productId,
                        oldMax.productName,
                        oldMax.basePurchaseUnitCost,
                        oldMax.initialQuantity,
                        oldMax.lineValue,
                        oldMax.allocationFactor,
                        fixedAlloc,
                        fixedLandedUnit
                ));
            }

        } else if (method == AllocationMethod.MANUAL_EQUAL) {
            int count = lots.size();
            BigDecimal equalShare = scaledTotal.divide(BigDecimal.valueOf(count), 4, RoundingMode.FLOOR);
            BigDecimal runningAllocated = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

            for (int i = 0; i < count; i++) {
                CostLotInput lot = lots.get(i);
                BigDecimal factor = BigDecimal.ONE.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
                runningAllocated = runningAllocated.add(equalShare);

                BigDecimal perUnitAdd = equalShare.divide(BigDecimal.valueOf(lot.initialQuantity), 4, RoundingMode.HALF_UP);
                BigDecimal landedUnit = lot.basePurchaseUnitCost.add(perUnitAdd).setScale(4, RoundingMode.HALF_UP);

                lines.add(new LandedCostAllocationLineDto(
                        "line-" + UUID.randomUUID().toString().substring(0, 8),
                        lot.costLotId,
                        lot.batchId,
                        lot.productId,
                        lot.productName,
                        lot.basePurchaseUnitCost,
                        lot.initialQuantity,
                        lot.getLineValue(),
                        factor,
                        equalShare,
                        landedUnit
                ));
            }

            BigDecimal remainder = scaledTotal.subtract(runningAllocated);
            if (remainder.compareTo(BigDecimal.ZERO) != 0 && !lines.isEmpty()) {
                LandedCostAllocationLineDto first = lines.get(0);
                BigDecimal fixedAlloc = first.allocatedExpenseAmount.add(remainder).setScale(4, RoundingMode.HALF_UP);
                BigDecimal perUnitAdd = fixedAlloc.divide(BigDecimal.valueOf(first.initialQuantity), 4, RoundingMode.HALF_UP);
                BigDecimal fixedLandedUnit = first.basePurchaseUnitCost.add(perUnitAdd).setScale(4, RoundingMode.HALF_UP);

                lines.set(0, new LandedCostAllocationLineDto(
                        first.lineId,
                        first.costLotId,
                        first.batchId,
                        first.productId,
                        first.productName,
                        first.basePurchaseUnitCost,
                        first.initialQuantity,
                        first.lineValue,
                        first.allocationFactor,
                        fixedAlloc,
                        fixedLandedUnit
                ));
            }

        } else {
            // Default: BY_VALUE
            BigDecimal totalValue = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            for (CostLotInput lot : lots) {
                totalValue = totalValue.add(lot.getLineValue());
            }

            if (totalValue.compareTo(BigDecimal.ZERO) <= 0) {
                return ClientResult.error("ERR_ZERO_PURCHASE_VALUE",
                        "Total purchase value must be greater than zero for value-based allocation.",
                        "মূল্য অনুপাতে বণ্টনের জন্য মোট ক্রয়মূল্য শূন্যের বেশি হতে হবে।");
            }

            BigDecimal runningAllocated = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            int maxValueIdx = 0;
            BigDecimal maxValue = BigDecimal.valueOf(-1);

            for (int i = 0; i < lots.size(); i++) {
                CostLotInput lot = lots.get(i);
                BigDecimal lineVal = lot.getLineValue();
                if (lineVal.compareTo(maxValue) > 0) {
                    maxValue = lineVal;
                    maxValueIdx = i;
                }

                BigDecimal factor = lineVal.divide(totalValue, 6, RoundingMode.HALF_UP);
                BigDecimal allocated = scaledTotal.multiply(lineVal).divide(totalValue, 4, RoundingMode.HALF_UP);
                runningAllocated = runningAllocated.add(allocated);

                BigDecimal perUnitAdd = allocated.divide(BigDecimal.valueOf(lot.initialQuantity), 4, RoundingMode.HALF_UP);
                BigDecimal landedUnit = lot.basePurchaseUnitCost.add(perUnitAdd).setScale(4, RoundingMode.HALF_UP);

                lines.add(new LandedCostAllocationLineDto(
                        "line-" + UUID.randomUUID().toString().substring(0, 8),
                        lot.costLotId,
                        lot.batchId,
                        lot.productId,
                        lot.productName,
                        lot.basePurchaseUnitCost,
                        lot.initialQuantity,
                        lineVal,
                        factor,
                        allocated,
                        landedUnit
                ));
            }

            BigDecimal remainder = scaledTotal.subtract(runningAllocated);
            if (remainder.compareTo(BigDecimal.ZERO) != 0 && maxValueIdx < lines.size()) {
                LandedCostAllocationLineDto oldMax = lines.get(maxValueIdx);
                BigDecimal fixedAlloc = oldMax.allocatedExpenseAmount.add(remainder).setScale(4, RoundingMode.HALF_UP);
                BigDecimal perUnitAdd = fixedAlloc.divide(BigDecimal.valueOf(oldMax.initialQuantity), 4, RoundingMode.HALF_UP);
                BigDecimal fixedLandedUnit = oldMax.basePurchaseUnitCost.add(perUnitAdd).setScale(4, RoundingMode.HALF_UP);

                lines.set(maxValueIdx, new LandedCostAllocationLineDto(
                        oldMax.lineId,
                        oldMax.costLotId,
                        oldMax.batchId,
                        oldMax.productId,
                        oldMax.productName,
                        oldMax.basePurchaseUnitCost,
                        oldMax.initialQuantity,
                        oldMax.lineValue,
                        oldMax.allocationFactor,
                        fixedAlloc,
                        fixedLandedUnit
                ));
            }
        }

        return ClientResult.ok(lines, "Allocations previewed successfully.", "ল্যান্ডেড খরচ বণ্টন সফলভাবে হিসাব করা হয়েছে।");
    }

    /**
     * Create and post a landed cost expense, allocating it across cost lots.
     */
    public ClientResult<LandedCostExpenseDto> createAndPostLandedCost(
            String grnId,
            ExpenseType expenseType,
            AllocationMethod allocationMethod,
            BigDecimal totalExpenseBdt,
            String invoiceOrReference,
            String carrierOrVendor,
            String notes,
            List<CostLotInput> lots) {

        if (!isAuthorized()) {
            return ClientResult.error("ERR_PERMISSION_DENIED",
                    "Cashiers and non-managers cannot post landed cost capitalization.",
                    "ক্যাশিয়ার বা সাধারণ বিক্রয়কর্মীদের ল্যান্ডেড খরচ যুক্ত করার অনুমতি নেই।");
        }

        ClientResult<List<LandedCostAllocationLineDto>> previewRes = previewAllocations(
                totalExpenseBdt, allocationMethod, lots);
        if (!previewRes.success) {
            return ClientResult.error(previewRes.errorCode, previewRes.messageEn, previewRes.messageBn);
        }

        String expenseId = "lc-" + UUID.randomUUID().toString().substring(0, 12);
        LandedCostExpenseDto dto = new LandedCostExpenseDto(
                expenseId,
                tenantId,
                branchId,
                grnId,
                expenseType != null ? expenseType : ExpenseType.TRANSPORT_FREIGHT,
                allocationMethod != null ? allocationMethod : AllocationMethod.BY_VALUE,
                totalExpenseBdt,
                invoiceOrReference,
                carrierOrVendor,
                notes,
                LandedCostStatus.POSTED,
                userId,
                Instant.now(),
                null,
                null,
                null,
                previewRes.data
        );

        expenseStore.put(expenseId, dto);

        return ClientResult.ok(dto,
                "Landed cost posted and capitalized successfully.",
                "ল্যান্ডেড খরচ সফলভাবে পোস্ট এবং কস্ট লটে সমন্বয় করা হয়েছে।");
    }

    /**
     * Reverses an existing landed cost allocation, restoring original base unit costs.
     */
    public ClientResult<LandedCostExpenseDto> reverseLandedCost(
            String expenseId,
            String reason) {

        if (!isAuthorized()) {
            return ClientResult.error("ERR_PERMISSION_DENIED",
                    "Cashiers and non-managers cannot reverse landed cost capitalization.",
                    "ক্যাশিয়ার বা সাধারণ বিক্রয়কর্মীদের ল্যান্ডেড খরচ বাতিল করার অনুমতি নেই।");
        }

        if (reason == null || reason.trim().length() < 5) {
            return ClientResult.error("ERR_REASON_REQUIRED",
                    "A valid reason of at least 5 characters is required for reversal.",
                    "ল্যান্ডেড খরচ বাতিলের জন্য সুস্পষ্ট কারণ উল্লেখ করা আবশ্যক।");
        }

        LandedCostExpenseDto existing = expenseStore.get(expenseId);
        if (existing == null) {
            return ClientResult.error("ERR_NOT_FOUND",
                    "Landed cost expense record not found.",
                    "ল্যান্ডেড খরচের রেকর্ড পাওয়া যায়নি।");
        }

        if (!existing.tenantId.equals(tenantId) || !existing.branchId.equals(branchId)) {
            return ClientResult.error("ERR_CROSS_BRANCH_ACCESS",
                    "Cannot reverse landed cost from another tenant or branch.",
                    "অন্য শাখা বা প্রতিষ্ঠানের ল্যান্ডেড খরচ বাতিল করা নিষিদ্ধ।");
        }

        if (existing.status == LandedCostStatus.REVERSED) {
            return ClientResult.error("ERR_ALREADY_REVERSED",
                    "This landed cost expense has already been reversed.",
                    "এই ল্যান্ডেড খরচটি পূর্বেই বাতিল করা হয়েছে।");
        }

        existing.status = LandedCostStatus.REVERSED;
        existing.reversedAt = Instant.now();
        existing.reversedBy = userId;
        existing.reversalReason = reason;

        return ClientResult.ok(existing,
                "Landed cost expense reversed successfully.",
                "ল্যান্ডেড খরচ সফলভাবে বাতিল করা হয়েছে এবং মূল খরচ পুনর্বহাল করা হয়েছে।");
    }

    public ClientResult<List<LandedCostExpenseDto>> listExpenses() {
        List<LandedCostExpenseDto> list = new ArrayList<>();
        for (LandedCostExpenseDto exp : expenseStore.values()) {
            if (exp.tenantId.equals(tenantId) && exp.branchId.equals(branchId)) {
                list.add(exp);
            }
        }
        return ClientResult.ok(list, "Expenses retrieved.", "খরচ তালিকা প্রাপ্ত হয়েছে।");
    }

    public ClientResult<LandedCostExpenseDto> getExpense(String expenseId) {
        LandedCostExpenseDto exp = expenseStore.get(expenseId);
        if (exp == null || !exp.tenantId.equals(tenantId) || !exp.branchId.equals(branchId)) {
            return ClientResult.error("ERR_NOT_FOUND", "Expense not found.", "খরচ পাওয়া যায়নি।");
        }
        return ClientResult.ok(exp, "Expense found.", "খরচ পাওয়া গেছে।");
    }
}
