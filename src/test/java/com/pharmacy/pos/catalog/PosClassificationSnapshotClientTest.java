package com.pharmacy.pos.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PosClassificationSnapshotClientTest {

    private PosClassificationSnapshotClient client;
    private PosClassificationSnapshotClient.MasterMedicine napaProduct;
    private PosClassificationSnapshotClient.MasterMedicine ciproProduct;

    @BeforeEach
    void setUp() {
        client = new PosClassificationSnapshotClient();

        napaProduct = new PosClassificationSnapshotClient.MasterMedicine(
                "prod-napa-500",
                "Napa 500mg",
                "Paracetamol",
                false,
                false,
                false,
                "APPROVED"
        );

        ciproProduct = new PosClassificationSnapshotClient.MasterMedicine(
                "prod-cipro-500",
                "Ciprocin 500mg",
                "Ciprofloxacin",
                true,
                false,
                false,
                "APPROVED"
        );

        client.registerMasterMedicine(napaProduct);
        client.registerMasterMedicine(ciproProduct);
    }

    @Test
    @DisplayName("P130-D1: Superadmin creates initial OTC classification snapshot with SHA-256 hash")
    void testSuperadminCreatesInitialSnapshot() {
        Instant now = Instant.now();
        var result = client.createSnapshot(
                "SUPERADMIN",
                "superadmin@platform.gov.bd",
                "prod-napa-500",
                PosClassificationSnapshotClient.ClassificationType.OTC,
                "DGDA Over-The-Counter Schedule 2024",
                now,
                "Initial regulatory registration as general sale OTC medicine."
        );

        assertEquals(PosClassificationSnapshotClient.UiStatus.SUCCESS, result.status);
        assertNotNull(result.data);
        assertEquals(1, result.data.versionNumber);
        assertEquals(PosClassificationSnapshotClient.ClassificationType.OTC, result.data.classificationType);
        assertTrue(result.data.isCurrent);
        assertFalse(result.data.isDispensingBlocked);
        assertNull(result.data.effectiveTo);
        assertEquals(64, result.data.immutableHash.length());
        assertFalse(napaProduct.isPrescriptionOnly);
    }

    @Test
    @DisplayName("P130-D2: Superseding snapshot (OTC -> POM) sets effectiveTo on preceding snapshot and updates current")
    void testSupersedingSnapshotLifecycle() {
        Instant t1 = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant t2 = Instant.now();

        // 1. Initial OTC snapshot
        var r1 = client.createSnapshot(
                "PLATFORM_OPERATOR",
                "operator@platform.gov.bd",
                "prod-napa-500",
                PosClassificationSnapshotClient.ClassificationType.OTC,
                "Initial OTC Schedule",
                t1,
                "Initial OTC approval."
        );
        assertEquals(PosClassificationSnapshotClient.UiStatus.SUCCESS, r1.status);

        // 2. Reclassified to POM
        var r2 = client.createSnapshot(
                "SUPERADMIN",
                "superadmin@platform.gov.bd",
                "prod-napa-500",
                PosClassificationSnapshotClient.ClassificationType.PRESCRIPTION_ONLY,
                "DGDA POM Directive 2026/08",
                t2,
                "Reclassified to prescription-only due to dosage strength reassessment."
        );
        assertEquals(PosClassificationSnapshotClient.UiStatus.SUCCESS, r2.status);
        assertEquals(2, r2.data.versionNumber);
        assertTrue(r2.data.isCurrent);

        // Check history
        List<PosClassificationSnapshotClient.ClassificationSnapshot> history = client.getSnapshotHistory("prod-napa-500");
        assertEquals(2, history.size());

        // Snapshot 1 must be closed
        PosClassificationSnapshotClient.ClassificationSnapshot s1 = history.stream()
                .filter(s -> s.versionNumber == 1)
                .findFirst()
                .orElseThrow();
        assertFalse(s1.isCurrent);
        assertEquals(t2, s1.effectiveTo);

        // Master medicine updated to POM
        assertTrue(napaProduct.isPrescriptionOnly);
    }

    @Test
    @DisplayName("P130-D3: RECALLED snapshot triggers zero-override POS hard block")
    void testRecalledSnapshotTriggersPosHardBlock() {
        var result = client.createSnapshot(
                "SUPERADMIN",
                "superadmin@dgda.gov.bd",
                "prod-napa-500",
                PosClassificationSnapshotClient.ClassificationType.RECALLED,
                "DGDA Statutory Safety Recall Notice 44/2026",
                Instant.now(),
                "Critical safety alert: Suspected contamination in active raw material batch."
        );

        assertEquals(PosClassificationSnapshotClient.UiStatus.SUCCESS, result.status);
        assertTrue(result.data.isDispensingBlocked);
        assertEquals("RECALLED", napaProduct.curationStatus);

        // Verify POS dispensing is blocked
        boolean isBlocked = client.isDispensingBlocked("prod-napa-500");
        assertTrue(isBlocked, "Recalled product must be hard-blocked at POS checkout!");
    }

    @Test
    @DisplayName("P130-D4: Point-in-time temporal resolution accurately returns historical OTC snapshot for past sales")
    void testPointInTimeTemporalResolution() {
        Instant jan1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant jun1 = Instant.parse("2026-06-01T00:00:00Z");

        // Jan: OTC
        client.createSnapshot(
                "SUPERADMIN",
                "admin@dgda.gov.bd",
                "prod-napa-500",
                PosClassificationSnapshotClient.ClassificationType.OTC,
                "Schedule Jan 2026",
                jan1,
                "OTC status early 2026."
        );

        // Jun: POM
        client.createSnapshot(
                "SUPERADMIN",
                "admin@dgda.gov.bd",
                "prod-napa-500",
                PosClassificationSnapshotClient.ClassificationType.PRESCRIPTION_ONLY,
                "Schedule Jun 2026",
                jun1,
                "Prescription required from June 2026."
        );

        // Sale on March 15: Must resolve to OTC (Version 1)
        Instant march15 = Instant.parse("2026-03-15T10:30:00Z");
        var resMarch = client.getPointInTimeSnapshot("prod-napa-500", march15);
        assertEquals(PosClassificationSnapshotClient.UiStatus.SUCCESS, resMarch.status);
        assertNotNull(resMarch.data);
        assertEquals(1, resMarch.data.versionNumber);
        assertEquals(PosClassificationSnapshotClient.ClassificationType.OTC, resMarch.data.classificationType);

        // Sale on August 1: Must resolve to POM (Version 2)
        Instant aug1 = Instant.parse("2026-08-01T15:00:00Z");
        var resAug = client.getPointInTimeSnapshot("prod-napa-500", aug1);
        assertEquals(PosClassificationSnapshotClient.UiStatus.SUCCESS, resAug.status);
        assertNotNull(resAug.data);
        assertEquals(2, resAug.data.versionNumber);
        assertEquals(PosClassificationSnapshotClient.ClassificationType.PRESCRIPTION_ONLY, resAug.data.classificationType);
    }

    @Test
    @DisplayName("P130-D5: Tenant owner or cashier blocked from creating classification snapshots")
    void testTenantRoleCannotCreateSnapshots() {
        // Attempt by PHARMACY_OWNER
        var rOwner = client.createSnapshot(
                "PHARMACY_OWNER",
                "owner@uttara.com",
                "prod-napa-500",
                PosClassificationSnapshotClient.ClassificationType.OTC,
                "Store Preference",
                Instant.now(),
                "Store owner trying to mark drug as OTC."
        );
        assertEquals(PosClassificationSnapshotClient.UiStatus.PERMISSION_DENIED, rOwner.status);
        assertEquals("PLATFORM_CATALOG_OWNERSHIP_PROTECTED", rOwner.errorCode);
        assertNotNull(rOwner.messageBn);

        // Attempt by CASHIER
        var rCashier = client.createSnapshot(
                "CASHIER",
                "cashier@uttara.com",
                "prod-napa-500",
                PosClassificationSnapshotClient.ClassificationType.OTC,
                "Cashier Attempt",
                Instant.now(),
                "Cashier cannot modify catalog."
        );
        assertEquals(PosClassificationSnapshotClient.UiStatus.PERMISSION_DENIED, rCashier.status);
        assertEquals("PLATFORM_CATALOG_OWNERSHIP_PROTECTED", rCashier.errorCode);
    }

    @Test
    @DisplayName("P130-D6: Offline fallback resolves cached classification snapshot safely")
    void testOfflineFallbackServesCachedSnapshot() {
        // Create initial snapshot while online
        client.createSnapshot(
                "SUPERADMIN",
                "admin@dgda.gov.bd",
                "prod-napa-500",
                PosClassificationSnapshotClient.ClassificationType.OTC,
                "DGDA Schedule 2026",
                Instant.now(),
                "Verified online registration."
        );

        // Simulate network disconnect
        client.setOfflineMode(true);
        assertTrue(client.isOfflineMode());

        var current = client.getCurrentSnapshot("prod-napa-500");
        assertEquals(PosClassificationSnapshotClient.UiStatus.OFFLINE, current.status);
        assertNotNull(current.data);
        assertEquals("prod-napa-500", current.data.productId);
        assertEquals(PosClassificationSnapshotClient.ClassificationType.OTC, current.data.classificationType);
        assertEquals("STATUS_OFFLINE_CACHE", current.errorCode);

        // Unknown product in offline returns empty
        var unknown = client.getCurrentSnapshot("prod-unknown-999");
        assertEquals(PosClassificationSnapshotClient.UiStatus.EMPTY, unknown.status);
    }
}
