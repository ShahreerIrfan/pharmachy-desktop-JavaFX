package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P256: Recall Dispensing Client Desktop Tests")
class RecallDispensingClientTest {

    @Test
    @DisplayName("Role management authorization gates")
    void testRoleAuthorization() {
        assertTrue(RecallDispensingClient.canManageRecall("SUPERADMIN"));
        assertTrue(RecallDispensingClient.canManageRecall("PHARMACY_OWNER"));
        assertTrue(RecallDispensingClient.canManageRecall("BRANCH_MANAGER"));
        assertTrue(RecallDispensingClient.canManageRecall("STAFF_PHARMACIST"));

        // Cashiers barred from publishing or lifting recalls
        assertFalse(RecallDispensingClient.canManageRecall("CASHIER"));
    }

    @Test
    @DisplayName("Dispensing lock gate blocks recalled or non-active batches")
    void testDispensingLockGate() {
        // Active and un-recalled batch allowed
        assertTrue(RecallDispensingClient.canDispenseBatch(false, "ACTIVE"));

        // Recalled batch strictly blocked
        assertFalse(RecallDispensingClient.canDispenseBatch(true, "ACTIVE"));
        assertFalse(RecallDispensingClient.canDispenseBatch(true, "RECALLED"));

        // Quarantined or expired batch blocked
        assertFalse(RecallDispensingClient.canDispenseBatch(false, "QUARANTINED"));
        assertFalse(RecallDispensingClient.canDispenseBatch(false, "EXPIRED"));
    }

    @Test
    @DisplayName("Preflight validation ensures batch, product, and reason completeness")
    void testPreflightValidation() {
        assertDoesNotThrow(() ->
            RecallDispensingClient.validatePreflight(
                "BATCH-001",
                "Azithromycin 500mg",
                "DGDA Recall Order #991",
                "CLASS_I"
            )
        );

        // Short batch
        assertThrows(IllegalArgumentException.class, () ->
            RecallDispensingClient.validatePreflight(
                "A",
                "Product",
                "Reason",
                "CLASS_I"
            )
        );

        // Empty reason
        assertThrows(IllegalArgumentException.class, () ->
            RecallDispensingClient.validatePreflight(
                "BATCH-001",
                "Product",
                "",
                "CLASS_I"
            )
        );

        // Invalid class
        assertThrows(IllegalArgumentException.class, () ->
            RecallDispensingClient.validatePreflight(
                "BATCH-001",
                "Product",
                "Reason",
                "CLASS_INVALID"
            )
        );
    }

    @Test
    @DisplayName("Localization and historical impact summary formatting")
    void testLocalizationAndFormatting() {
        assertEquals("ক্লাস ১ (মারাত্মক / জীবন বিপন্নকারী ঝুঁকি)",
            RecallDispensingClient.getRecallClassDisplay("CLASS_I", "bn"));
        assertEquals("Class I (Dangerous / Life-threatening)",
            RecallDispensingClient.getRecallClassDisplay("CLASS_I", "en"));

        String summaryEn = RecallDispensingClient.formatHistoricalImpactSummary(
            14, new BigDecimal("150.00"), new BigDecimal("12750.00"), "en"
        );
        assertTrue(summaryEn.contains("14 invoices impacted"));
        assertTrue(summaryEn.contains("150.00 units sold"));
        assertTrue(summaryEn.contains("12750.00"));
        assertTrue(summaryEn.contains("Past sales history unmodified"));

        String summaryBn = RecallDispensingClient.formatHistoricalImpactSummary(
            14, new BigDecimal("150.00"), new BigDecimal("12750.00"), "bn"
        );
        assertTrue(summaryBn.contains("14"));
        assertTrue(summaryBn.contains("অতীত সেলস রেকর্ড অপরিবর্তিত"));
    }
}

