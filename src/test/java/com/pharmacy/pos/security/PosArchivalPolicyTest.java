package com.pharmacy.pos.security;

import com.pharmacy.pos.models.PosArchivalPolicyManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-0056: POS Archival and Soft Deletion Policy Tests")
class PosArchivalPolicyTest {

    @Test
    @DisplayName("Should identify immutable financial and clinical record types")
    void testImmutableRecordIdentification() {
        assertTrue(PosArchivalPolicyManager.isImmutableRecord("SALE_INVOICE"));
        assertTrue(PosArchivalPolicyManager.isImmutableRecord("PRESCRIPTION_RECORD"));
        assertTrue(PosArchivalPolicyManager.isImmutableRecord("AUDIT_LOG_ENTRY"));
        assertTrue(PosArchivalPolicyManager.isImmutableRecord("PAYMENT_TRANSACTION"));
        assertFalse(PosArchivalPolicyManager.isImmutableRecord("MEDICINE_CATALOG"));
    }

    @Test
    @DisplayName("Should prohibit destructive physical deletion for all domain records")
    void testDestructiveDeletionProhibited() {
        assertFalse(PosArchivalPolicyManager.canDestructivelyDelete("SALE_INVOICE"));
        assertFalse(PosArchivalPolicyManager.canDestructivelyDelete("MEDICINE_CATALOG"));
    }

    @Test
    @DisplayName("Should throw IllegalStateException when attempting destructive delete on immutable record")
    void testDestructiveDeleteAttemptThrowsException() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> PosArchivalPolicyManager.validateDeletionPermitted("SALE_INVOICE", "INV-2026-101")
        );
        assertTrue(ex.getMessage().contains("Destructive deletion is strictly prohibited"));
    }

    @Test
    @DisplayName("Should allow soft-deletion for mutable catalog items with valid reason")
    void testSoftDeletionForMutableCatalog() {
        PosArchivalPolicyManager.PosArchivedResource record = PosArchivalPolicyManager.createArchivalRecord(
                "MEDICINE_CATALOG",
                "MED-999",
                "usr-pharmacist-01",
                "Discontinued by pharmaceutical manufacturer"
        );

        assertNotNull(record);
        assertEquals("MEDICINE_CATALOG", record.resourceType());
        assertEquals("MED-999", record.resourceId());
        assertTrue(record.isArchived());
        assertNotNull(record.archivedAt());
        assertEquals("usr-pharmacist-01", record.archivedBy());
        assertEquals("Discontinued by pharmaceutical manufacturer", record.archivalReason());
    }

    @Test
    @DisplayName("Should reject archival if reason is less than 5 characters")
    void testRejectsShortArchivalReason() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> PosArchivalPolicyManager.createArchivalRecord(
                        "MEDICINE_CATALOG",
                        "MED-999",
                        "usr-pharmacist-01",
                        "bad"
                )
        );
        assertTrue(ex.getMessage().contains("at least 5 characters"));
    }
}
