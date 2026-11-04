package com.pharmacy.pos.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosNonDivisiblePackClientTest
 * Architecture Decision: ADR-0165 (P165).
 * Phase 09: Packaging, Unit Conversion, Batches & Cost Lots.
 *
 * Unit tests for non-divisible pack restrictions, sterile container guardrails,
 * clinical exemption protocols, and FEFO allocation in JavaFX Desktop POS.
 */
public class PosNonDivisiblePackClientTest {

    private PosNonDivisiblePackClient client;
    private PosNonDivisiblePackClient.NonDivisiblePolicy insulinPenPolicy;
    private PosNonDivisiblePackClient.NonDivisiblePolicy sterileVialBoxPolicy;
    private PosNonDivisiblePackClient.NonDivisiblePolicy hospitalSplittablePolicy;

    @BeforeEach
    public void setUp() {
        client = new PosNonDivisiblePackClient();

        // 1. Lantus SoloStar: Pre-filled Insulin Pen, strictly non-divisible
        insulinPenPolicy = new PosNonDivisiblePackClient.NonDivisiblePolicy(
                "ndpp-01",
                "ten-01",
                "br-01",
                "prod-lantus-pen",
                "Lantus SoloStar 100IU/ml Pen",
                "INSULIN_PEN",
                false, // Splitting forbidden
                true,
                1,
                "PEN",
                "Pre-filled sterile insulin cartridge cannot be fractured or broken.",
                Collections.emptyList()
        );

        // 2. Sterile Rocephin Vials: Box of 5 sealed vials, retail splitting forbidden
        sterileVialBoxPolicy = new PosNonDivisiblePackClient.NonDivisiblePolicy(
                "ndpp-02",
                "ten-01",
                "br-01",
                "prod-rocephin-box",
                "Rocephin 1g Sterile Box of 5 Vials",
                "STERILE_VIAL_BOX",
                false, // Splitting forbidden
                true,
                5,
                "VIAL",
                "Sealed sterile box integrity must be maintained for retail sales.",
                Collections.emptyList()
        );

        // 3. Hospital Splittable Ampoules: Splitting permitted ONLY with institutional protocol and Pharmacist sign-off
        hospitalSplittablePolicy = new PosNonDivisiblePackClient.NonDivisiblePolicy(
                "ndpp-03",
                "ten-01",
                "br-01",
                "prod-morph-amp",
                "Morphine 10mg/ml Ampoule Box",
                "STERILE_VIAL_BOX",
                true, // Splitting allowed under rules
                true, // Pharmacist required
                10,
                "AMPOULE",
                "",
                Arrays.asList("HOSPITAL_INPATIENT", "ICU_EMERGENCY_DOSE")
        );
    }

    @Test
    @DisplayName("1. Valid scenario: Dispensing whole indivisible insulin pens is allowed")
    public void testValidWholeUnitDispense() {
        PosNonDivisiblePackClient.ValidationResult res = client.validateDispense(
                insulinPenPolicy,
                new BigDecimal("2.0"),
                false,
                null,
                "CASHIER"
        );

        assertTrue(res.isAllowed);
        assertEquals("INSULIN_PEN", res.containerType);
        assertNull(res.errorCode);
    }

    @Test
    @DisplayName("2. Guardrail: Fractional quantity of an insulin pen (e.g. 1.5 pens) is strictly rejected")
    public void testFractionalInsulinPenRejected() {
        PosNonDivisiblePackClient.ValidationResult res = client.validateDispense(
                insulinPenPolicy,
                new BigDecimal("1.5"),
                false,
                null,
                "STAFF_PHARMACIST"
        );

        assertFalse(res.isAllowed);
        assertEquals("ERR_NON_DIVISIBLE_CONTAINER_SPLIT_PROHIBITED", res.errorCode);
        assertTrue(res.errorMessageEn.contains("Fractional dispensing"));
        assertTrue(res.errorMessageBn.contains("নিষিদ্ধ"));
    }

    @Test
    @DisplayName("3. Guardrail: Attempting to split a sealed box of vials without approved rules is rejected")
    public void testSealedBoxSplitProhibitedWithoutApprovedRule() {
        PosNonDivisiblePackClient.ValidationResult res = client.validateDispense(
                sterileVialBoxPolicy,
                new BigDecimal("1.0"),
                true, // split requested
                null,
                "STAFF_PHARMACIST"
        );

        assertFalse(res.isAllowed);
        assertEquals("ERR_NON_DIVISIBLE_CONTAINER_SPLIT_PROHIBITED", res.errorCode);
        assertTrue(res.errorMessageEn.contains("Breaking sealed container"));
    }

    @Test
    @DisplayName("4. Protocol requirement: Splitting allowed container without approved exemption protocol is rejected")
    public void testApprovedSplitRequiresValidExemptionProtocol() {
        PosNonDivisiblePackClient.ValidationResult res = client.validateDispense(
                hospitalSplittablePolicy,
                new BigDecimal("1.0"),
                true, // split requested
                "INVALID_PROTOCOL",
                "STAFF_PHARMACIST"
        );

        assertFalse(res.isAllowed);
        assertEquals("ERR_INVALID_EXEMPTION_PROTOCOL", res.errorCode);
    }

    @Test
    @DisplayName("5. RBAC Guardrail: Cashier cannot unilaterally authorize container splitting")
    public void testCashierCannotAuthorizeSplitting() {
        PosNonDivisiblePackClient.ValidationResult res = client.validateDispense(
                hospitalSplittablePolicy,
                new BigDecimal("1.0"),
                true, // split requested
                "HOSPITAL_INPATIENT",
                "CASHIER" // Cashier role
        );

        assertFalse(res.isAllowed);
        assertEquals("ERR_ROLE_UNAUTHORIZED", res.errorCode);
        assertTrue(res.errorMessageEn.contains("Cashier role cannot authorize"));
    }

    @Test
    @DisplayName("6. Authorized split: Pharmacist with valid clinical protocol can approve container split")
    public void testPharmacistAuthorizedSplittingSucceeds() {
        PosNonDivisiblePackClient.ValidationResult res = client.validateDispense(
                hospitalSplittablePolicy,
                new BigDecimal("1.0"),
                true,
                "HOSPITAL_INPATIENT",
                "STAFF_PHARMACIST"
        );

        assertTrue(res.isAllowed);
        assertNull(res.errorCode);
        assertEquals(10, res.approvedBreakFactor);
    }

    @Test
    @DisplayName("7. FEFO Allocation: Checkouts deduct from earliest unexpired batch")
    public void testFefoBatchAllocation() {
        LocalDate today = LocalDate.of(2026, 11, 4);

        PosNonDivisiblePackClient.BatchStockItem bEarly = new PosNonDivisiblePackClient.BatchStockItem(
                "b-01", "PEN-EARLY", LocalDate.of(2026, 12, 1), 3, false
        );
        PosNonDivisiblePackClient.BatchStockItem bLate = new PosNonDivisiblePackClient.BatchStockItem(
                "b-02", "PEN-LATE", LocalDate.of(2027, 6, 1), 10, false
        );

        PosNonDivisiblePackClient.CheckoutResult checkout = client.executeCheckout(
                insulinPenPolicy,
                Arrays.asList(bLate, bEarly),
                new BigDecimal("4.0"),
                false,
                null,
                "CASHIER",
                new BigDecimal("850.00"),
                today
        );

        assertTrue(checkout.success);
        assertEquals(2, checkout.allocations.size());
        assertEquals("b-01", checkout.allocations.get(0).batchId);
        assertEquals(3, checkout.allocations.get(0).deductedBaseUnits);
        assertEquals(0, bEarly.quantityBaseUnits);

        assertEquals("b-02", checkout.allocations.get(1).batchId);
        assertEquals(1, checkout.allocations.get(1).deductedBaseUnits);
        assertEquals(9, bLate.quantityBaseUnits);
    }

    @Test
    @DisplayName("8. Reversal: Sale reversal re-credits exact deducted integer base units")
    public void testSaleReversalRestoresStock() {
        LocalDate today = LocalDate.of(2026, 11, 4);

        PosNonDivisiblePackClient.BatchStockItem batch = new PosNonDivisiblePackClient.BatchStockItem(
                "b-01", "PEN-01", LocalDate.of(2027, 1, 1), 5, false
        );
        Map<String, PosNonDivisiblePackClient.BatchStockItem> lookup = new HashMap<>();
        lookup.put(batch.batchId, batch);

        PosNonDivisiblePackClient.CheckoutResult checkout = client.executeCheckout(
                insulinPenPolicy,
                Collections.singletonList(batch),
                new BigDecimal("2.0"),
                false,
                null,
                "CASHIER",
                new BigDecimal("850.00"),
                today
        );

        assertTrue(checkout.success);
        assertEquals(3, batch.quantityBaseUnits); // 5 - 2

        boolean reversed = client.reverseSale(checkout, lookup);
        assertTrue(reversed);
        assertEquals(5, batch.quantityBaseUnits); // Restored back to 5
    }
}
