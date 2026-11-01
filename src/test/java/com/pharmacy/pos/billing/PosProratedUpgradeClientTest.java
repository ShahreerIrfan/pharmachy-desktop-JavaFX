package com.pharmacy.pos.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P090: JavaFX Desktop POS Prorated Upgrade Client Tests")
public class PosProratedUpgradeClientTest {

    @Test
    @DisplayName("Upgrade eligibility correctly admits higher tiers and monthly-to-annual upgrades")
    void testUpgradeEligibility() {
        // Higher tiers
        assertTrue(PosProratedUpgradeClient.isUpgradeEligible("BASIC", "MONTHLY", "STANDARD", "MONTHLY"));
        assertTrue(PosProratedUpgradeClient.isUpgradeEligible("BASIC", "MONTHLY", "PRO", "MONTHLY"));
        assertTrue(PosProratedUpgradeClient.isUpgradeEligible("STANDARD", "MONTHLY", "PRO", "MONTHLY"));

        // Same tier, monthly to annual
        assertTrue(PosProratedUpgradeClient.isUpgradeEligible("STANDARD", "MONTHLY", "STANDARD", "ANNUAL"));

        // Downgrades rejected
        assertFalse(PosProratedUpgradeClient.isUpgradeEligible("PRO", "MONTHLY", "STANDARD", "MONTHLY"));
        assertFalse(PosProratedUpgradeClient.isUpgradeEligible("STANDARD", "MONTHLY", "BASIC", "MONTHLY"));
        assertFalse(PosProratedUpgradeClient.isUpgradeEligible("STANDARD", "ANNUAL", "STANDARD", "MONTHLY"));

        // Identical rejected
        assertFalse(PosProratedUpgradeClient.isUpgradeEligible("STANDARD", "MONTHLY", "STANDARD", "MONTHLY"));
    }

    @Test
    @DisplayName("Prorated calculations accurately compute unused credit, gross prorated charge, and net payable")
    void testProratedCalculations() {
        // Current: BASIC (1500 BDT/mo), Target: STANDARD (3500 BDT/mo)
        // Total days: 30, Used: 10, Remaining: 20
        BigDecimal currentPrice = new BigDecimal("1500.00");
        BigDecimal targetPrice = new BigDecimal("3500.00");

        PosProratedUpgradeClient.ProratedQuoteResult quote =
                PosProratedUpgradeClient.calculateProratedQuote(
                        "BASIC", "MONTHLY", currentPrice,
                        "STANDARD", "MONTHLY", targetPrice,
                        30, 10
                );

        assertTrue(quote.isValid);
        assertEquals(20, quote.remainingDays);

        // Daily rate: 1500 / 30 = 50.0000
        assertEquals(new BigDecimal("50.0000"), quote.currentDailyRate);

        // Unused credit: 1500 * 20 / 30 = 1000.00
        assertEquals(new BigDecimal("1000.00"), quote.unusedCredit);

        // Prorated gross target: 3500 * 20 / 30 = 2333.33
        assertEquals(new BigDecimal("2333.33"), quote.proratedGrossTarget);

        // Net payable: 2333.33 - 1000.00 = 1333.33
        assertEquals(new BigDecimal("1333.33"), quote.netPayable);
    }

    @Test
    @DisplayName("Monthly to Annual cycle upgrade applies unused monthly credit towards annual fee")
    void testMonthlyToAnnualCycleUpgrade() {
        // Current: STANDARD Monthly (3500 BDT), Target: STANDARD Annual (35000 BDT)
        // Total days: 30, Used: 15, Remaining: 15
        BigDecimal currentPrice = new BigDecimal("3500.00");
        BigDecimal targetPrice = new BigDecimal("35000.00");

        PosProratedUpgradeClient.ProratedQuoteResult quote =
                PosProratedUpgradeClient.calculateProratedQuote(
                        "STANDARD", "MONTHLY", currentPrice,
                        "STANDARD", "ANNUAL", targetPrice,
                        30, 15
                );

        assertTrue(quote.isValid);
        assertEquals(15, quote.remainingDays);

        // Unused credit: 3500 * 15 / 30 = 1750.00
        assertEquals(new BigDecimal("1750.00"), quote.unusedCredit);

        // Target annual fee: 35000.00
        assertEquals(new BigDecimal("35000.00"), quote.proratedGrossTarget);

        // Net payable: 35000.00 - 1750.00 = 33250.00
        assertEquals(new BigDecimal("33250.00"), quote.netPayable);
    }

    @Test
    @DisplayName("Formatting and bilingual localization labels render properly")
    void testFormattingAndLocalization() {
        assertEquals("BDT 1333.33", PosProratedUpgradeClient.formatBdt(new BigDecimal("1333.33")));
        assertEquals("BDT 0.00", PosProratedUpgradeClient.formatBdt(null));

        assertEquals("অব্যবহৃত ক্রেডিট (পূর্ববর্তী প্ল্যান)", PosProratedUpgradeClient.getLocalizedLabel("UNUSED_CREDIT", true));
        assertEquals("Unused Credit (Current Plan)", PosProratedUpgradeClient.getLocalizedLabel("UNUSED_CREDIT", false));
        assertEquals("নতুন ফিচার অবিলম্বে কার্যকর হয়েছে", PosProratedUpgradeClient.getLocalizedLabel("IMMEDIATE_GRANT", true));
    }
}
