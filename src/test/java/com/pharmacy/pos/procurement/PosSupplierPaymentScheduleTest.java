package com.pharmacy.pos.procurement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosSupplierPaymentScheduleTest
 * Architecture Decision: ADR-0193 (P193).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 */
public class PosSupplierPaymentScheduleTest {

    private PosSupplierPaymentScheduleClient managerClient;
    private PosSupplierPaymentScheduleClient cashierClient;

    private Map<String, PosSupplierPaymentScheduleClient.PaymentScheduleDto> scheduleStore;
    private Map<String, PosSupplierPaymentScheduleClient.SupplierLedgerEntryDto> ledgerStore;

    @BeforeEach
    void setUp() {
        scheduleStore = new HashMap<>();
        ledgerStore = new HashMap<>();

        // Seed initial AP balance: ৳80,000
        ledgerStore.put("sup-post-init", new PosSupplierPaymentScheduleClient.SupplierLedgerEntryDto(
                "sup-post-init",
                "sup-square",
                "GRN_INVOICE",
                "INV-001",
                BigDecimal.ZERO,
                new BigDecimal("80000.0000"),
                new BigDecimal("80000.0000"),
                Instant.now()
        ));

        managerClient = new PosSupplierPaymentScheduleClient(
                "tenant-sq",
                "branch-1",
                "BRANCH_MANAGER",
                "usr-mgr-1",
                false,
                scheduleStore,
                ledgerStore
        );

        cashierClient = new PosSupplierPaymentScheduleClient(
                "tenant-sq",
                "branch-1",
                "CASHIER",
                "usr-csh-1",
                false,
                scheduleStore,
                ledgerStore
        );
    }

    @Test
    @DisplayName("P193-01: Create and disburse partial & full installments with AP ledger update")
    void testCreateAndDisburseInstallmentPayments() {
        PosSupplierPaymentScheduleClient.PaymentScheduleDto sps = managerClient.createSchedule(
                "sup-square", "Square Pharma", "inv-1", LocalDate.now().plusDays(14),
                new BigDecimal("50000.0000"), "HIGH", "BANK_TRANSFER", "", "Insulin order settlement"
        );

        assertNotNull(sps);
        assertEquals("SCHEDULED", sps.status);
        assertEquals(new BigDecimal("50000.0000"), sps.plannedAmount);
        assertEquals(new BigDecimal("50000.0000"), sps.remainingAmount);

        // 1st Installment: ৳20,000
        PosSupplierPaymentScheduleClient.PaymentScheduleDto part = managerClient.recordInstallmentPayment(
                sps.scheduleId, new BigDecimal("20000.0000"), "BANK_TRANSFER", "EFT-001", "1st part"
        );
        assertEquals("PARTIALLY_SETTLED", part.status);
        assertEquals(new BigDecimal("20000.0000"), part.settledAmount);
        assertEquals(new BigDecimal("30000.0000"), part.remainingAmount);

        // Check AP ledger: 80,000 - 20,000 = 60,000
        PosSupplierPaymentScheduleClient.SupplierLedgerEntryDto e1 = ledgerStore.get(part.installments.get(0).ledgerEntryId);
        assertEquals("PAYMENT", e1.postingType);
        assertEquals(new BigDecimal("20000.0000"), e1.debitAmount);
        assertEquals(new BigDecimal("60000.0000"), e1.balanceAfter);

        // 2nd Installment: ৳30,000 (final)
        PosSupplierPaymentScheduleClient.PaymentScheduleDto full = managerClient.recordInstallmentPayment(
                sps.scheduleId, new BigDecimal("30000.0000"), "BANK_TRANSFER", "EFT-002", "Final"
        );
        assertEquals("SETTLED", full.status);
        assertEquals(new BigDecimal("50000.0000"), full.settledAmount);
        assertEquals(BigDecimal.ZERO, full.remainingAmount);

        // Check AP ledger: 60,000 - 30,000 = 30,000
        PosSupplierPaymentScheduleClient.SupplierLedgerEntryDto e2 = ledgerStore.get(full.installments.get(1).ledgerEntryId);
        assertEquals(new BigDecimal("30000.0000"), e2.balanceAfter);
    }

    @Test
    @DisplayName("P193-02: Overpayment guard throws ERR_EXCEEDS_SCHEDULED_DUE")
    void testOverpaymentGuardThrowsException() {
        PosSupplierPaymentScheduleClient.PaymentScheduleDto sps = managerClient.createSchedule(
                "sup-square", "Square Pharma", "inv-1", LocalDate.now().plusDays(7),
                new BigDecimal("10000.0000"), "MEDIUM", "BANK_TRANSFER", "", "Due"
        );

        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                managerClient.recordInstallmentPayment(
                        sps.scheduleId, new BigDecimal("15000.0000"), "BANK_TRANSFER", "EFT-OVER", "Too much"
                )
        );
        assertTrue(ex.getMessage().contains("ERR_EXCEEDS_SCHEDULED_DUE"));
    }

    @Test
    @DisplayName("P193-03: Cannot disburse against a SETTLED schedule")
    void testCannotDisburseAgainstSettledSchedule() {
        PosSupplierPaymentScheduleClient.PaymentScheduleDto sps = managerClient.createSchedule(
                "sup-square", "Square Pharma", "inv-1", LocalDate.now().plusDays(7),
                new BigDecimal("5000.0000"), "MEDIUM", "BANK_TRANSFER", "", "Due"
        );
        managerClient.recordInstallmentPayment(sps.scheduleId, new BigDecimal("5000.0000"), "BANK_TRANSFER", "EFT-1", "Done");

        Exception ex = assertThrows(IllegalStateException.class, () ->
                managerClient.recordInstallmentPayment(sps.scheduleId, new BigDecimal("1000.0000"), "BANK_TRANSFER", "EFT-2", "Extra")
        );
        assertTrue(ex.getMessage().contains("ERR_SCHEDULE_NOT_PAYABLE"));
    }

    @Test
    @DisplayName("P193-04: Audited reschedule updates due date and counter")
    void testAuditedReschedule() {
        PosSupplierPaymentScheduleClient.PaymentScheduleDto sps = managerClient.createSchedule(
                "sup-square", "Square Pharma", "inv-1", LocalDate.now().plusDays(7),
                new BigDecimal("20000.0000"), "MEDIUM", "BANK_TRANSFER", "", "Due"
        );

        LocalDate newDate = LocalDate.now().plusDays(21);
        PosSupplierPaymentScheduleClient.PaymentScheduleDto resched = managerClient.reschedule(
                sps.scheduleId, newDate, "Cycle alignment"
        );

        assertEquals(newDate, resched.dueDate);
        assertEquals(1, resched.rescheduleCount);
        assertEquals("Cycle alignment", resched.lastRescheduleReason);
    }

    @Test
    @DisplayName("P193-05: Reschedule without reason throws ERR_RESCHEDULE_REASON_REQUIRED")
    void testRescheduleWithoutReasonThrowsException() {
        PosSupplierPaymentScheduleClient.PaymentScheduleDto sps = managerClient.createSchedule(
                "sup-square", "Square Pharma", "inv-1", LocalDate.now().plusDays(7),
                new BigDecimal("10000.0000"), "MEDIUM", "BANK_TRANSFER", "", "Due"
        );

        Exception ex = assertThrows(IllegalArgumentException.class, () ->
                managerClient.reschedule(sps.scheduleId, LocalDate.now().plusDays(14), "   ")
        );
        assertTrue(ex.getMessage().contains("ERR_RESCHEDULE_REASON_REQUIRED"));
    }

    @Test
    @DisplayName("P193-06: Cannot cancel schedule with disbursed funds")
    void testCannotCancelDisbursedSchedule() {
        PosSupplierPaymentScheduleClient.PaymentScheduleDto sps = managerClient.createSchedule(
                "sup-square", "Square Pharma", "inv-1", LocalDate.now().plusDays(7),
                new BigDecimal("20000.0000"), "MEDIUM", "BANK_TRANSFER", "", "Due"
        );
        managerClient.recordInstallmentPayment(sps.scheduleId, new BigDecimal("5000.0000"), "BANK_TRANSFER", "EFT-1", "Part");

        Exception ex = assertThrows(IllegalStateException.class, () ->
                managerClient.cancelSchedule(sps.scheduleId, "Cancel attempt")
        );
        assertTrue(ex.getMessage().contains("ERR_CANNOT_CANCEL_DISBURSED_SCHEDULE"));
    }

    @Test
    @DisplayName("P193-07: Cashier role is barred with ERR_STAFF_ROLE_DENIED")
    void testCashierRoleForbidden() {
        Exception ex = assertThrows(SecurityException.class, () ->
                cashierClient.createSchedule(
                        "sup-square", "Square Pharma", "inv-1", LocalDate.now().plusDays(7),
                        new BigDecimal("10000.0000"), "MEDIUM", "BANK_TRANSFER", "", "Due"
                )
        );
        assertTrue(ex.getMessage().contains("ERR_STAFF_ROLE_DENIED"));
    }
}
