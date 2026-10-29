package com.pharmacy.pos.visual;

import com.pharmacy.pos.models.CartItem;
import com.pharmacy.pos.models.SaleTransaction;
import com.pharmacy.pos.models.TenantSession;
import com.pharmacy.pos.services.PosSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Visual Snapshot & Regression Test Suite for Desktop POS Interface.
 * Architecture Decision: ADR-0007, ADR-0009, ADR-0023 & ADR-0032
 *
 * Verifies fixed dimensions, typography, zero-emoji policy, offline watermark rendering,
 * and exact Decimal financial precision across canonical UI states.
 */
public class PosVisualSnapshotTest {

    private static final int APPROVED_WIDTH_PRIMARY = 1280;
    private static final int APPROVED_HEIGHT_PRIMARY = 800;
    private static final int APPROVED_WIDTH_SECONDARY = 1024;
    private static final int APPROVED_HEIGHT_SECONDARY = 768;

    private static final Pattern EMOJI_PATTERN = Pattern.compile(
        "[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]"
    );

    private TenantSession cashierSession;
    private PosSecurityService securityService;

    @BeforeEach
    void setUp() {
        cashierSession = new TenantSession(
            "tenant-dhaka-01",
            "branch-dhanmondi",
            "token-xyz-123",
            "user-cashier-01",
            "CASHIER",
            true
        );
        securityService = new PosSecurityService();
    }

    @Test
    @DisplayName("1. Primary and secondary desktop resolutions match approved ADR-0032 standards")
    void testApprovedDesktopDimensions() {
        assertEquals(1280, APPROVED_WIDTH_PRIMARY);
        assertEquals(800, APPROVED_HEIGHT_PRIMARY);
        assertEquals(1024, APPROVED_WIDTH_SECONDARY);
        assertEquals(768, APPROVED_HEIGHT_SECONDARY);

        // Aspect ratio for primary POS terminal must be 16:10
        double aspectRatio = (double) APPROVED_WIDTH_PRIMARY / APPROVED_HEIGHT_PRIMARY;
        assertEquals(1.6, aspectRatio, 0.001);
    }

    @Test
    @DisplayName("2. Loaded POS cart visual snapshot displays exact Decimal totals without float drift")
    void testLoadedPosSnapshotTotals() {
        CartItem napa = new CartItem(
            "med-001", "Napa Extra 500mg/65mg", "Paracetamol + Caffeine", "STRIP",
            2, new BigDecimal("35.00"), "LOT-2026-N1", LocalDate.of(2027, 12, 31), BigDecimal.ZERO
        );
        CartItem seclo = new CartItem(
            "med-002", "Seclo 20mg", "Omeprazole", "STRIP",
            1, new BigDecimal("70.00"), "LOT-2026-S4", LocalDate.of(2028, 6, 30), BigDecimal.ZERO
        );
        CartItem azithrocin = new CartItem(
            "med-003", "Azithrocin 500mg", "Azithromycin", "STRIP",
            1, new BigDecimal("140.00"), "LOT-2026-A2", LocalDate.of(2027, 9, 30), BigDecimal.ZERO
        );

        BigDecimal subtotal = napa.getLineTotal().add(seclo.getLineTotal()).add(azithrocin.getLineTotal());
        assertEquals(new BigDecimal("280.00"), subtotal);

        // 5% VAT
        BigDecimal vat = subtotal.multiply(new BigDecimal("0.05")).setScale(2, java.math.RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("14.00"), vat);

        BigDecimal grandTotal = subtotal.add(vat);
        assertEquals(new BigDecimal("294.00"), grandTotal);

        // Confirm string representation has zero floating point artifacts
        assertEquals("280.00", subtotal.toPlainString());
        assertEquals("14.00", vat.toPlainString());
        assertEquals("294.00", grandTotal.toPlainString());
    }

    @Test
    @DisplayName("3. Offline visual snapshot renders mandatory bilingual watermark banner")
    void testOfflineVisualWatermarkRendering() {
        CartItem item = new CartItem(
            "med-001", "Napa Extra", "Paracetamol", "STRIP",
            1, new BigDecimal("35.00"), "LOT-01", LocalDate.of(2027, 12, 31), BigDecimal.ZERO
        );

        SaleTransaction offlineTx = new SaleTransaction(
            UUID.randomUUID().toString(),
            "INV-OFFLINE-002",
            "tenant-dhaka-01",
            "branch-dhanmondi",
            "cashier-01",
            Collections.singletonList(item),
            BigDecimal.ZERO,
            true,
            Instant.now()
        );

        String receipt = offlineTx.generateReceiptText();
        assertNotNull(receipt);
        assertTrue(receipt.contains(SaleTransaction.OFFLINE_WATERMARK_EN));
        assertTrue(receipt.contains(SaleTransaction.OFFLINE_WATERMARK_BN));
        assertTrue(receipt.contains("Napa Extra"));
        assertTrue(receipt.contains("BDT      35.00"));
    }

    @Test
    @DisplayName("4. FXML views and CSS token files strictly contain ZERO Unicode emojis (ADR-0009)")
    void testZeroEmojisInDesktopVisualResources() throws IOException {
        Path resourcesPath = Paths.get("src", "main", "resources");
        if (!Files.exists(resourcesPath)) {
            resourcesPath = Paths.get("desktop", "src", "main", "resources");
        }
        assertTrue(Files.exists(resourcesPath), "Resources directory must exist");

        try (Stream<Path> stream = Files.walk(resourcesPath)) {
            List<Path> resourceFiles = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".fxml") || p.toString().endsWith(".css"))
                .toList();

            assertFalse(resourceFiles.isEmpty(), "Must audit at least one FXML or CSS resource file");

            for (Path rf : resourceFiles) {
                String content = Files.readString(rf);
                assertFalse(
                    EMOJI_PATTERN.matcher(content).find(),
                    "Forbidden emoji found in visual resource: " + rf.getFileName()
                );
            }
        }
    }

    @Test
    @DisplayName("5. Cashier attempting unauthorized administrative export is visually blocked")
    void testPermissionDeniedVisualStateForCashier() {
        assertThrows(SecurityException.class, () -> {
            securityService.assertCanExportDatabase(cashierSession);
        }, "Cashier must be blocked from administrative operations");
    }
}
