package com.pharmacy.pos.forms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosDynamicMedicineFormEngineTest
 * Architecture Decision: ADR-0157 (P157).
 * Unit test suite for schema-driven dynamic medicine forms on JavaFX Desktop POS.
 */
public class PosDynamicMedicineFormEngineTest {

    private PosDynamicMedicineFormEngine engine;

    @BeforeEach
    public void setUp() {
        engine = new PosDynamicMedicineFormEngine("TABLET_CAPSULE");
    }

    @Test
    @DisplayName("1. Dosage form switching retains common fields and caches form-specific parameters")
    public void testSwitchDosageFormPreservesCommonFieldsAndCachesSpecifics() {
        // Populate initial TABLET_CAPSULE form
        engine.setFieldValue("brand_name", "Napa Extra");
        engine.setFieldValue("brand_name_bn", "নাপা এক্সট্রা");
        engine.setFieldValue("generic_name", "Paracetamol + Caffeine");
        engine.setFieldValue("dar_number", "012-345-678");
        engine.setFieldValue("cost_unit", "2.10");
        engine.setFieldValue("mrp_unit", "3.00");
        engine.setFieldValue("pieces_per_strip", 10);
        engine.setFieldValue("strips_per_pack", 10);
        engine.setFieldValue("total_pieces_per_pack", 100);

        // Switch to INHALER
        engine.switchDosageForm("INHALER");
        assertEquals("INHALER", engine.getCurrentDosageForm());

        // Common fields must persist
        assertEquals("Napa Extra", engine.getFieldValue("brand_name"));
        assertEquals("নাপা এক্সট্রা", engine.getFieldValue("brand_name_bn"));
        assertEquals("Paracetamol + Caffeine", engine.getFieldValue("generic_name"));
        assertEquals("012-345-678", engine.getFieldValue("dar_number"));
        assertEquals("2.10", engine.getFieldValue("cost_unit"));
        assertEquals("3.00", engine.getFieldValue("mrp_unit"));

        // Tablet-specific fields must not be in active values
        assertNull(engine.getFieldValue("pieces_per_strip"));
        assertNull(engine.getFieldValue("strips_per_pack"));
        assertNull(engine.getFieldValue("total_pieces_per_pack"));

        // Verify cached values in retained cache
        Map<String, Map<String, Object>> cache = engine.getRetainedFormCache();
        assertTrue(cache.containsKey("TABLET_CAPSULE"));
        assertEquals(10, cache.get("TABLET_CAPSULE").get("pieces_per_strip"));

        // Enter inhaler-specific fields
        engine.setFieldValue("dose_per_actuation", "100mcg");
        engine.setFieldValue("actuations_per_device", 200);

        // Switch back to TABLET_CAPSULE
        engine.switchDosageForm("TABLET_CAPSULE");
        assertEquals("TABLET_CAPSULE", engine.getCurrentDosageForm());

        // Common fields still persist
        assertEquals("Napa Extra", engine.getFieldValue("brand_name"));

        // Tablet-specific fields are fully restored from cache!
        assertEquals(10, engine.getFieldValue("pieces_per_strip"));
        assertEquals(10, engine.getFieldValue("strips_per_pack"));
        assertEquals(100, engine.getFieldValue("total_pieces_per_pack"));

        // And INHALER fields are now cached!
        assertTrue(engine.getRetainedFormCache().containsKey("INHALER"));
        assertEquals("100mcg", engine.getRetainedFormCache().get("INHALER").get("dose_per_actuation"));
    }

    @Test
    @DisplayName("2. Inline errors generated for missing mandatory fields")
    public void testValidateFormDataMissingMandatoryFields() {
        // Empty form
        List<PosDynamicMedicineFormEngine.InlineValidationError> errors = engine.validateFormData();
        assertFalse(errors.isEmpty());

        boolean hasBrandErr = errors.stream().anyMatch(e -> "brand_name".equals(e.getFieldName()) && "ERR_MANDATORY_FIELD_MISSING".equals(e.getErrorCode()));
        boolean hasGenericErr = errors.stream().anyMatch(e -> "generic_name".equals(e.getFieldName()) && "ERR_MANDATORY_FIELD_MISSING".equals(e.getErrorCode()));
        boolean hasDarErr = errors.stream().anyMatch(e -> "dar_number".equals(e.getFieldName()) && "ERR_MANDATORY_FIELD_MISSING".equals(e.getErrorCode()));
        boolean hasCostErr = errors.stream().anyMatch(e -> "cost_unit".equals(e.getFieldName()) && "ERR_MANDATORY_FIELD_MISSING".equals(e.getErrorCode()));
        boolean hasMrpErr = errors.stream().anyMatch(e -> "mrp_unit".equals(e.getFieldName()) && "ERR_MANDATORY_FIELD_MISSING".equals(e.getErrorCode()));

        assertTrue(hasBrandErr);
        assertTrue(hasGenericErr);
        assertTrue(hasDarErr);
        assertTrue(hasCostErr);
        assertTrue(hasMrpErr);
    }

    @Test
    @DisplayName("3. Financial pricing validation detects negative cost, non-positive MRP, and MRP below cost")
    public void testValidatePricingRules() {
        engine.setFieldValue("brand_name", "Azithromycin 500");
        engine.setFieldValue("generic_name", "Azithromycin");
        engine.setFieldValue("dar_number", "045-889-123");

        // Negative cost
        engine.setFieldValue("cost_unit", "-10.00");
        engine.setFieldValue("mrp_unit", "15.00");
        List<PosDynamicMedicineFormEngine.InlineValidationError> errors = engine.validateFormData();
        assertTrue(errors.stream().anyMatch(e -> "ERR_NEGATIVE_COST".equals(e.getErrorCode())));

        // Zero MRP
        engine.setFieldValue("cost_unit", "10.00");
        engine.setFieldValue("mrp_unit", "0.00");
        errors = engine.validateFormData();
        assertTrue(errors.stream().anyMatch(e -> "ERR_INVALID_MRP".equals(e.getErrorCode())));

        // MRP below cost
        engine.setFieldValue("cost_unit", "20.00");
        engine.setFieldValue("mrp_unit", "18.00");
        errors = engine.validateFormData();
        assertTrue(errors.stream().anyMatch(e -> "ERR_MRP_BELOW_COST".equals(e.getErrorCode())));
    }

    @Test
    @DisplayName("4. Packaging dimension mismatch error")
    public void testPackagingDimensionMismatch() {
        engine.setFieldValue("brand_name", "Seclo 20");
        engine.setFieldValue("generic_name", "Omeprazole");
        engine.setFieldValue("dar_number", "001-222-333");
        engine.setFieldValue("cost_unit", "4.00");
        engine.setFieldValue("mrp_unit", "6.00");

        // Mismatch: 10 * 10 != 80
        engine.setFieldValue("pieces_per_strip", 10);
        engine.setFieldValue("strips_per_pack", 10);
        engine.setFieldValue("total_pieces_per_pack", 80);

        List<PosDynamicMedicineFormEngine.InlineValidationError> errors = engine.validateFormData();
        assertTrue(errors.stream().anyMatch(e -> "ERR_DIMENSION_MISMATCH".equals(e.getErrorCode())));
    }

    @Test
    @DisplayName("5. Storage temperature range ordering error")
    public void testStorageTemperatureRangeOrdering() {
        engine.setFieldValue("brand_name", "Insulin Glargine");
        engine.setFieldValue("generic_name", "Insulin Glargine");
        engine.setFieldValue("dar_number", "007-888-999");
        engine.setFieldValue("cost_unit", "500.00");
        engine.setFieldValue("mrp_unit", "650.00");

        // Min > Max
        engine.setFieldValue("storage_temperature_celsius_min", 25);
        engine.setFieldValue("storage_temperature_celsius_max", 8);

        List<PosDynamicMedicineFormEngine.InlineValidationError> errors = engine.validateFormData();
        assertTrue(errors.stream().anyMatch(e -> "ERR_INVALID_TEMPERATURE_RANGE".equals(e.getErrorCode())));
    }

    @Test
    @DisplayName("6. Formulation-specific validation for INHALER, DROPS, and CREAMS")
    public void testFormulationSpecificValidations() {
        engine.setFieldValue("brand_name", "Salbutamol Inhaler");
        engine.setFieldValue("generic_name", "Salbutamol");
        engine.setFieldValue("dar_number", "010-999-001");
        engine.setFieldValue("cost_unit", "150.00");
        engine.setFieldValue("mrp_unit", "200.00");

        // Switch to INHALER without specific parameters
        engine.switchDosageForm("INHALER");
        List<PosDynamicMedicineFormEngine.InlineValidationError> errors = engine.validateFormData();
        assertTrue(errors.stream().anyMatch(e -> "dose_per_actuation".equals(e.getFieldName())));
        assertTrue(errors.stream().anyMatch(e -> "actuations_per_device".equals(e.getFieldName())));

        // Fill inhaler specifics
        engine.setFieldValue("dose_per_actuation", "100mcg");
        engine.setFieldValue("actuations_per_device", 200);
        errors = engine.validateFormData();
        assertTrue(errors.isEmpty());

        // Switch to DROPS without specific parameters
        engine.switchDosageForm("DROPS");
        errors = engine.validateFormData();
        assertTrue(errors.stream().anyMatch(e -> "volume_ml".equals(e.getFieldName())));
        assertTrue(errors.stream().anyMatch(e -> "drop_category".equals(e.getFieldName())));
    }

    @Test
    @DisplayName("7. RBAC restricts Cashier from mutating or submitting forms")
    public void testRoleBasedAccessControlCashierRestriction() {
        engine.setFieldValue("brand_name", "Napa 500");
        engine.setFieldValue("generic_name", "Paracetamol");
        engine.setFieldValue("dar_number", "001-001-001");
        engine.setFieldValue("cost_unit", "1.00");
        engine.setFieldValue("mrp_unit", "1.50");

        // Cashier submission should be denied
        assertFalse(engine.canMutateForm("CASHIER"));
        boolean submitted = engine.submitForm("CASHIER");
        assertFalse(submitted);
        assertEquals(PosDynamicMedicineFormEngine.UiStatus.PERMISSION_DENIED, engine.getUiStatus());

        // Pharmacist submission should succeed
        assertTrue(engine.canMutateForm("STAFF_PHARMACIST"));
        submitted = engine.submitForm("STAFF_PHARMACIST");
        assertTrue(submitted);
        assertEquals(PosDynamicMedicineFormEngine.UiStatus.SUCCESS, engine.getUiStatus());
    }

    @Test
    @DisplayName("8. Tab sequence resolves sequentially across visible field groups")
    public void testTabSequenceResolution() {
        List<PosDynamicMedicineFormEngine.FormFieldConfig> tabSeq = engine.resolveTabSequence();
        assertNotNull(tabSeq);
        assertFalse(tabSeq.isEmpty());

        for (int i = 0; i < tabSeq.size() - 1; i++) {
            assertTrue(tabSeq.get(i).tabIndex <= tabSeq.get(i + 1).tabIndex,
                    "Tab indices must be monotonically non-decreasing");
        }
    }

    @Test
    @DisplayName("9. UI lifecycle states transition correctly")
    public void testUiLifecycleStates() {
        assertEquals(PosDynamicMedicineFormEngine.UiStatus.EMPTY, engine.getUiStatus());

        engine.setUiStatus(PosDynamicMedicineFormEngine.UiStatus.LOADING);
        assertEquals(PosDynamicMedicineFormEngine.UiStatus.LOADING, engine.getUiStatus());

        engine.setUiStatus(PosDynamicMedicineFormEngine.UiStatus.OFFLINE);
        assertEquals(PosDynamicMedicineFormEngine.UiStatus.OFFLINE, engine.getUiStatus());
    }
}
