package com.pharmacy.pos.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * PosPlanComparisonClient
 * Architecture Decision: ADR-0015, ADR-0081 & ADR-0083
 * Phase 05: Basic/Standard/Pro Subscription & Billing (P083)
 *
 * JavaFX Desktop POS Plan Comparison, Annual Savings & Checkout Client:
 * - Side-by-side plan comparisons with BDT pricing and annual discount math.
 * - Invariant INV-SAFETY-01: Clinical safety controls are guaranteed non-gated across all tiers.
 * - Quota boundaries (branch, device, staff limits) and downgrade violation checks.
 * - Offline-first fallback comparison matrix with bilingual EN/BN support.
 */
public final class PosPlanComparisonClient {

    public static final Set<String> CLINICAL_SAFETY_FEATURES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            "ANTIBIOTIC_REGISTER",
            "BATCH_EXPIRY_BLOCK",
            "RECALL_QUARANTINE",
            "BANNED_DRUG_RESTRICTION",
            "RX_VERIFICATION"
        ))
    );

    public static final Set<String> VALID_BILLING_CYCLES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("MONTHLY", "ANNUAL"))
    );

    public static final Set<String> VALID_PLAN_CODES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("PLAN_BASIC", "PLAN_STANDARD", "PLAN_PRO"))
    );

    public static class ClinicalSafetyGuarantee {
        private final String key;
        private final String nameEn;
        private final String nameBn;
        private final String descriptionEn;
        private final String descriptionBn;
        private final boolean guaranteed;

        public ClinicalSafetyGuarantee(String key, String nameEn, String nameBn, String descriptionEn, String descriptionBn) {
            this.key = key;
            this.nameEn = nameEn;
            this.nameBn = nameBn;
            this.descriptionEn = descriptionEn;
            this.descriptionBn = descriptionBn;
            this.guaranteed = true;
        }

        public String getKey() { return key; }
        public String getNameEn() { return nameEn; }
        public String getNameBn() { return nameBn; }
        public String getDescriptionEn() { return descriptionEn; }
        public String getDescriptionBn() { return descriptionBn; }
        public boolean isGuaranteed() { return guaranteed; }
    }

    public static class BillingCycleOption {
        private final String cycle;
        private final int durationDays;
        private final String labelEn;
        private final String labelBn;
        private final String savingsLabelEn;
        private final String savingsLabelBn;

        public BillingCycleOption(String cycle, int durationDays, String labelEn, String labelBn, String savingsLabelEn, String savingsLabelBn) {
            this.cycle = cycle;
            this.durationDays = durationDays;
            this.labelEn = labelEn;
            this.labelBn = labelBn;
            this.savingsLabelEn = savingsLabelEn;
            this.savingsLabelBn = savingsLabelBn;
        }

        public String getCycle() { return cycle; }
        public int getDurationDays() { return durationDays; }
        public String getLabelEn() { return labelEn; }
        public String getLabelBn() { return labelBn; }
        public String getSavingsLabelEn() { return savingsLabelEn; }
        public String getSavingsLabelBn() { return savingsLabelBn; }
    }

    public static class PlanComparisonCard {
        private final String planCode;
        private final String tier;
        private final String name;
        private final String descriptionEn;
        private final String descriptionBn;
        private final BigDecimal monthlyPriceBdt;
        private final BigDecimal annualPriceBdt;
        private final BigDecimal effectiveMonthlyBdt;
        private final BigDecimal annualSavingsBdt;
        private final String savingsPercentage;
        private final int maxBranches;
        private final int maxDevicesPerBranch;
        private final int maxTotalDevices;
        private final int maxStaffUsers;
        private final Map<String, Boolean> features;
        private final String badgeEn;
        private final String badgeBn;

        public PlanComparisonCard(
            String planCode,
            String tier,
            String name,
            String descriptionEn,
            String descriptionBn,
            BigDecimal monthlyPriceBdt,
            BigDecimal annualPriceBdt,
            int maxBranches,
            int maxDevicesPerBranch,
            int maxTotalDevices,
            int maxStaffUsers,
            Map<String, Boolean> features,
            String badgeEn,
            String badgeBn
        ) {
            this.planCode = planCode;
            this.tier = tier;
            this.name = name;
            this.descriptionEn = descriptionEn;
            this.descriptionBn = descriptionBn;
            this.monthlyPriceBdt = monthlyPriceBdt;
            this.annualPriceBdt = annualPriceBdt;
            this.maxBranches = maxBranches;
            this.maxDevicesPerBranch = maxDevicesPerBranch;
            this.maxTotalDevices = maxTotalDevices;
            this.maxStaffUsers = maxStaffUsers;
            this.features = Collections.unmodifiableMap(new HashMap<>(features));
            this.badgeEn = badgeEn;
            this.badgeBn = badgeBn;

            BigDecimal twelveMonths = BigDecimal.valueOf(12);
            BigDecimal nominalAnnual = monthlyPriceBdt.multiply(twelveMonths);
            this.annualSavingsBdt = nominalAnnual.subtract(annualPriceBdt).setScale(2, RoundingMode.HALF_UP);
            if (nominalAnnual.compareTo(BigDecimal.ZERO) > 0) {
                this.savingsPercentage = annualSavingsBdt.divide(nominalAnnual, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
            } else {
                this.savingsPercentage = "0.00%";
            }
            this.effectiveMonthlyBdt = annualPriceBdt.divide(twelveMonths, 2, RoundingMode.HALF_UP);
        }

        public String getPlanCode() { return planCode; }
        public String getTier() { return tier; }
        public String getName() { return name; }
        public String getDescriptionEn() { return descriptionEn; }
        public String getDescriptionBn() { return descriptionBn; }
        public BigDecimal getMonthlyPriceBdt() { return monthlyPriceBdt; }
        public BigDecimal getAnnualPriceBdt() { return annualPriceBdt; }
        public BigDecimal getEffectiveMonthlyBdt() { return effectiveMonthlyBdt; }
        public BigDecimal getAnnualSavingsBdt() { return annualSavingsBdt; }
        public String getSavingsPercentage() { return savingsPercentage; }
        public int getMaxBranches() { return maxBranches; }
        public int getMaxDevicesPerBranch() { return maxDevicesPerBranch; }
        public int getMaxTotalDevices() { return maxTotalDevices; }
        public int getMaxStaffUsers() { return maxStaffUsers; }
        public Map<String, Boolean> getFeatures() { return features; }
        public String getBadgeEn() { return badgeEn; }
        public String getBadgeBn() { return badgeBn; }
    }

    public static class QuoteResult {
        private final String tenantId;
        private final String planCode;
        private final String planTier;
        private final String billingCycle;
        private final String currency;
        private final BigDecimal subtotalBdt;
        private final BigDecimal discountBdt;
        private final BigDecimal totalBdt;
        private final BigDecimal effectiveMonthlyBdt;
        private final String savingsPercentage;
        private final int renewalPeriodDays;
        private final LocalDate projectedRenewalDate;
        private final boolean safetyFeaturesGuaranteed;
        private final boolean isCompliant;
        private final List<String> violations;

        public QuoteResult(
            String tenantId,
            String planCode,
            String planTier,
            String billingCycle,
            String currency,
            BigDecimal subtotalBdt,
            BigDecimal discountBdt,
            BigDecimal totalBdt,
            BigDecimal effectiveMonthlyBdt,
            String savingsPercentage,
            int renewalPeriodDays,
            LocalDate projectedRenewalDate,
            boolean isCompliant,
            List<String> violations
        ) {
            this.tenantId = tenantId;
            this.planCode = planCode;
            this.planTier = planTier;
            this.billingCycle = billingCycle;
            this.currency = currency;
            this.subtotalBdt = subtotalBdt;
            this.discountBdt = discountBdt;
            this.totalBdt = totalBdt;
            this.effectiveMonthlyBdt = effectiveMonthlyBdt;
            this.savingsPercentage = savingsPercentage;
            this.renewalPeriodDays = renewalPeriodDays;
            this.projectedRenewalDate = projectedRenewalDate;
            this.safetyFeaturesGuaranteed = true;
            this.isCompliant = isCompliant;
            this.violations = Collections.unmodifiableList(new ArrayList<>(violations));
        }

        public String getTenantId() { return tenantId; }
        public String getPlanCode() { return planCode; }
        public String getPlanTier() { return planTier; }
        public String getBillingCycle() { return billingCycle; }
        public String getCurrency() { return currency; }
        public BigDecimal getSubtotalBdt() { return subtotalBdt; }
        public BigDecimal getDiscountBdt() { return discountBdt; }
        public BigDecimal getTotalBdt() { return totalBdt; }
        public BigDecimal getEffectiveMonthlyBdt() { return effectiveMonthlyBdt; }
        public String getSavingsPercentage() { return savingsPercentage; }
        public int getRenewalPeriodDays() { return renewalPeriodDays; }
        public LocalDate getProjectedRenewalDate() { return projectedRenewalDate; }
        public boolean isSafetyFeaturesGuaranteed() { return safetyFeaturesGuaranteed; }
        public boolean isCompliant() { return isCompliant; }
        public List<String> getViolations() { return violations; }
    }

    private PosPlanComparisonClient() {}

    /**
     * Invariant INV-SAFETY-01: Verifies whether a given feature is universally guaranteed across all plans.
     */
    public static boolean isClinicallyGuaranteed(String featureKey) {
        if (featureKey == null) return false;
        return CLINICAL_SAFETY_FEATURES.contains(featureKey.toUpperCase().trim());
    }

    /**
     * Validates quote request parameters prior to sending to the API.
     */
    public static void validateQuoteRequest(String tenantId, String planCode, String billingCycle) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("Tenant ID is required for plan quote calculation.");
        }
        if (planCode == null || !VALID_PLAN_CODES.contains(planCode.toUpperCase().trim())) {
            throw new IllegalArgumentException("Invalid plan code: " + planCode + ". Allowed: " + VALID_PLAN_CODES);
        }
        if (billingCycle == null || !VALID_BILLING_CYCLES.contains(billingCycle.toUpperCase().trim())) {
            throw new IllegalArgumentException("Invalid billing cycle: " + billingCycle + ". Allowed: " + VALID_BILLING_CYCLES);
        }
    }

    /**
     * Deterministic client-side quote calculation for instant UI rendering and offline mode.
     */
    public static QuoteResult calculateQuote(
        String tenantId,
        String planCode,
        String billingCycle,
        int activeBranches,
        int activeStaff
    ) {
        validateQuoteRequest(tenantId, planCode, billingCycle);

        String cleanCode = planCode.toUpperCase().trim();
        String cleanCycle = billingCycle.toUpperCase().trim();

        PlanComparisonCard card = getComparisonCard(cleanCode);
        BigDecimal monthlyPrice = card.getMonthlyPriceBdt();
        BigDecimal annualPrice = card.getAnnualPriceBdt();

        BigDecimal subtotal;
        BigDecimal discount;
        BigDecimal total;
        BigDecimal effectiveMonthly;
        String savingsPct;
        int durationDays;

        if ("ANNUAL".equals(cleanCycle)) {
            subtotal = monthlyPrice.multiply(BigDecimal.valueOf(12)).setScale(2, RoundingMode.HALF_UP);
            discount = subtotal.subtract(annualPrice).setScale(2, RoundingMode.HALF_UP);
            total = annualPrice.setScale(2, RoundingMode.HALF_UP);
            effectiveMonthly = annualPrice.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
            savingsPct = card.getSavingsPercentage();
            durationDays = 365;
        } else {
            subtotal = monthlyPrice.setScale(2, RoundingMode.HALF_UP);
            discount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            total = monthlyPrice.setScale(2, RoundingMode.HALF_UP);
            effectiveMonthly = monthlyPrice.setScale(2, RoundingMode.HALF_UP);
            savingsPct = "0.00%";
            durationDays = 30;
        }

        LocalDate renewalDate = LocalDate.now().plusDays(durationDays);

        List<String> violations = new ArrayList<>();
        if (activeBranches > card.getMaxBranches()) {
            violations.add("Active branches (" + activeBranches + ") exceed plan limit (" + card.getMaxBranches() + ")");
        }
        if (activeStaff > card.getMaxStaffUsers()) {
            violations.add("Active staff (" + activeStaff + ") exceed plan limit (" + card.getMaxStaffUsers() + ")");
        }

        return new QuoteResult(
            tenantId,
            cleanCode,
            card.getTier(),
            cleanCycle,
            "BDT",
            subtotal,
            discount,
            total,
            effectiveMonthly,
            savingsPct,
            durationDays,
            renewalDate,
            violations.isEmpty(),
            violations
        );
    }

    public static PlanComparisonCard getComparisonCard(String planCode) {
        for (PlanComparisonCard card : getCanonicalComparisonCards()) {
            if (card.getPlanCode().equalsIgnoreCase(planCode)) {
                return card;
            }
        }
        throw new IllegalArgumentException("Unknown plan code: " + planCode);
    }

    public static List<PlanComparisonCard> getCanonicalComparisonCards() {
        Map<String, Boolean> basicFeatures = new HashMap<>();
        basicFeatures.put("ANTIBIOTIC_REGISTER", true);
        basicFeatures.put("BATCH_EXPIRY_BLOCK", true);
        basicFeatures.put("RECALL_QUARANTINE", true);
        basicFeatures.put("BANNED_DRUG_RESTRICTION", true);
        basicFeatures.put("RX_VERIFICATION", true);
        basicFeatures.put("OFFLINE_SYNC", true);
        basicFeatures.put("THERMAL_RECEIPT_PRINT", true);
        basicFeatures.put("MEDICINE_CATALOG", true);
        basicFeatures.put("BASIC_INVENTORY", true);
        basicFeatures.put("PURCHASE_ORDERS", false);
        basicFeatures.put("SUPPLIER_DUES", false);
        basicFeatures.put("CUSTOMER_LEDGER", false);
        basicFeatures.put("FLUTTER_OWNER_APP", false);
        basicFeatures.put("CONSOLIDATED_REPORTING", false);

        Map<String, Boolean> standardFeatures = new HashMap<>(basicFeatures);
        standardFeatures.put("PURCHASE_ORDERS", true);
        standardFeatures.put("SUPPLIER_DUES", true);
        standardFeatures.put("CUSTOMER_LEDGER", true);
        standardFeatures.put("FLUTTER_OWNER_APP", true);
        standardFeatures.put("DAILY_BACKUP", true);

        Map<String, Boolean> proFeatures = new HashMap<>(standardFeatures);
        proFeatures.put("CONSOLIDATED_REPORTING", true);
        proFeatures.put("MULTI_BRANCH_TRANSFER", true);
        proFeatures.put("WEBHOOKS_AND_API", true);
        proFeatures.put("HOURLY_RPO_BACKUP", true);

        return Arrays.asList(
            new PlanComparisonCard(
                "PLAN_BASIC",
                "BASIC",
                "Basic Pharmacy",
                "Essential single-branch pharmacy POS with regulatory compliance and offline sync.",
                "নিয়ন্ত্রক কমপ্লায়েন্স ও অফলাইন সিঙ্ক সহ একক শাখার জন্য বেসিক ফার্মেসি সমাধান।",
                new BigDecimal("1500.00"),
                new BigDecimal("15000.00"),
                1, 1, 1, 3,
                basicFeatures,
                "Starter Pharmacy",
                "স্টার্টার ফার্মেসি"
            ),
            new PlanComparisonCard(
                "PLAN_STANDARD",
                "STANDARD",
                "Standard Pharmacy",
                "Growing pharmacy with multi-branch management, supplier dues, and mobile owner app.",
                "বহু-শাখা ব্যবস্থাপনা, সরবরাহকারী বাকি ও মোবাইল অ্যাপ সহ স্ট্যান্ডার্ড ফার্মেসি সমাধান।",
                new BigDecimal("3500.00"),
                new BigDecimal("35000.00"),
                3, 5, 15, 10,
                standardFeatures,
                "Most Popular",
                "জনপ্রিয় পছন্দ"
            ),
            new PlanComparisonCard(
                "PLAN_PRO",
                "PRO",
                "Pro Enterprise Pharmacy",
                "Enterprise multi-chain pharmacy with consolidated GL, API webhooks, and unlimited scale.",
                "বৃহৎ ফার্মেসি চেইনের জন্য সমন্বিত অডিট লেজার, এপিআই ওয়েবহুক ও উচ্চ ক্ষমতা সম্পন্ন সমাধান।",
                new BigDecimal("7500.00"),
                new BigDecimal("75000.00"),
                50, 20, 250, 100,
                proFeatures,
                "Enterprise Multi-Chain",
                "এন্টারপ্রাইজ মাল্টি-চেইন"
            )
        );
    }

    public static List<ClinicalSafetyGuarantee> getClinicalSafetyGuarantees() {
        return Arrays.asList(
            new ClinicalSafetyGuarantee(
                "ANTIBIOTIC_REGISTER",
                "Antibiotic Dispensing Register",
                "অ্যান্টিবায়োটিক বিতরণ রেজিস্টার",
                "Mandatory red-flagged antibiotic tracking and doctor prescription capture.",
                "বাধ্যতামূলক অ্যান্টিবায়োটিক ট্র্যাকিং ও প্রেসক্রিপশন সংরক্ষণ।"
            ),
            new ClinicalSafetyGuarantee(
                "BATCH_EXPIRY_BLOCK",
                "Batch Expiry POS Blocking",
                "মেয়াদোত্তীর্ণ ওষুধ বিক্রয় রোধ",
                "Automatic point-of-sale blocking for expired batches with FEFO enforcement.",
                "মেয়াদোত্তীর্ণ ওষুধ পয়েন্ট-অব-সেলে বিক্রয় স্বয়ংক্রিয়ভাবে বন্ধ এবং FEFO নীতি প্রয়োগ।"
            ),
            new ClinicalSafetyGuarantee(
                "RECALL_QUARANTINE",
                "DGDA Drug Recall Quarantine",
                "ডিজিডিএ ওষুধ প্রত্যাহার কোয়ারেন্টাইন",
                "Instant quarantine of manufacturer recalled batches preventing sale.",
                "প্রত্যাহারকৃত ব্যাচ অবিলম্বে কোয়ারেন্টাইনে স্থানান্তর ও বিক্রয় রোধ।"
            ),
            new ClinicalSafetyGuarantee(
                "BANNED_DRUG_RESTRICTION",
                "Banned Drug Dispensing Prevention",
                "নিষিদ্ধ ওষুধ বিক্রয় নিয়ন্ত্রণ",
                "Central restriction database blocking sale of regulatory banned medicines.",
                "নিয়ন্ত্রক সংস্থা কর্তৃক নিষিদ্ধ ওষুধের বিক্রয় প্রতিরোধ।"
            ),
            new ClinicalSafetyGuarantee(
                "RX_VERIFICATION",
                "Prescription Only (POM) Verification",
                "প্রেসক্রিপশন নির্ভর ওষুধ যাচাইকরণ",
                "Mandatory registered doctor verification for schedule G/H medicines.",
                "তফসিলি প্রেসক্রিপশন ওষুধের জন্য নিবন্ধিত চিকিৎসকের তথ্য যাচাই।"
            )
        );
    }

    public static List<BillingCycleOption> getBillingCycleOptions() {
        return Arrays.asList(
            new BillingCycleOption(
                "MONTHLY",
                30,
                "Monthly",
                "মাসিক",
                "Standard monthly pricing",
                "সাধারণ মাসিক মূল্য"
            ),
            new BillingCycleOption(
                "ANNUAL",
                365,
                "Annual",
                "বার্ষিক",
                "Save 16.67% (2 Months Free)",
                "১৬.৬৭% সাশ্রয় (২ মাস ফ্রি)"
            )
        );
    }
}
