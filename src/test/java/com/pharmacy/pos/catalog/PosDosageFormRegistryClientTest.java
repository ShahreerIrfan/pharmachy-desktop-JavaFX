package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PosDosageFormRegistryClientTest
 * Tests P122 Dosage Form Registry desktop client logic.
 */
class PosDosageFormRegistryClientTest {

    private PosDosageFormRegistryClient client;
    private List<PosDosageFormRegistryClient.DosageFormRecord> sampleForms;

    @BeforeEach
    void setUp() {
        client = new PosDosageFormRegistryClient();
        sampleForms = new ArrayList<>();

        sampleForms.add(new PosDosageFormRegistryClient.DosageFormRecord(
                "df-tablet", "TABLET", "Tablet", "ট্যাবলেট",
                "SOLID", "Oral", true, false, false, 10, true
        ));

        sampleForms.add(new PosDosageFormRegistryClient.DosageFormRecord(
                "df-capsule", "CAPSULE", "Capsule", "ক্যাপসুল",
                "SOLID", "Oral", true, false, false, 20, true
        ));

        sampleForms.add(new PosDosageFormRegistryClient.DosageFormRecord(
                "df-syrup", "SYRUP", "Syrup", "সিরাপ",
                "LIQUID", "Oral", false, true, false, 30, true
        ));

        sampleForms.add(new PosDosageFormRegistryClient.DosageFormRecord(
                "df-injection", "INJECTION", "Injection", "ইনজেকশন",
                "INJECTABLE", "Intravenous", false, true, true, 50, true
        ));
    }

    @Test
    @DisplayName("Filter dosage forms where Box-Strip-Tablet conversion is applicable")
    void testFilterStripApplicable() {
        var res = client.filterForms(sampleForms, null, true);
        assertEquals(PosDosageFormRegistryClient.UiStatus.SUCCESS, res.uiStatus);
        assertEquals(2, res.forms.size());
        assertTrue(res.forms.stream().allMatch(f -> f.isStripApplicable));
    }

    @Test
    @DisplayName("Filter dosage forms by category LIQUID")
    void testFilterByCategoryLiquid() {
        var res = client.filterForms(sampleForms, "LIQUID", null);
        assertEquals(PosDosageFormRegistryClient.UiStatus.SUCCESS, res.uiStatus);
        assertEquals(1, res.forms.size());
        assertEquals("SYRUP", res.forms.get(0).formCode);
        assertTrue(res.forms.get(0).isVolumeApplicable);
    }

    @Test
    @DisplayName("Display order sorting is maintained")
    void testDisplayOrderSorting() {
        var res = client.filterForms(sampleForms, null, null);
        assertEquals(4, res.forms.size());
        assertEquals(10, res.forms.get(0).displayOrder);
        assertEquals(50, res.forms.get(3).displayOrder);
    }

    @Test
    @DisplayName("Tenant cashier cannot create dosage form definitions")
    void testCashierCannotCreateDosageForm() {
        var res = client.attemptCreateForm("CASHIER", sampleForms.get(0));
        assertEquals(PosDosageFormRegistryClient.UiStatus.PERMISSION_DENIED, res.uiStatus);
        assertEquals("PLATFORM_CATALOG_OWNERSHIP_PROTECTED", res.errorCode);
    }

    @Test
    @DisplayName("Superadmin can create dosage form definitions")
    void testSuperadminCanCreateDosageForm() {
        var res = client.attemptCreateForm("SUPERADMIN", sampleForms.get(0));
        assertEquals(PosDosageFormRegistryClient.UiStatus.SUCCESS, res.uiStatus);
    }
}
