package com.pharmacy.pos.controllers;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Test Suite for World-Class Split-Screen LoginGateController.
 * Architecture Decision: ADR-0007, ADR-0021, ADR-0023 & ADR-0113.
 *
 * Verifies staff avatar selection, tactile PIN-pad input, backspace, clear,
 * language switching, and offline mode toggle.
 */
public class LoginGateControllerTest {

    private LoginGateController controller;

    private Label liveClockLabel;
    private Label liveDateLabel;
    private Label authorityStatusLabel;
    private Label screenHeadingLabel;
    private Label screenSubheadingLabel;
    private Button langEnBtn;
    private Button langBnBtn;
    private Label selectStaffLabel;

    private HBox cardCashier;
    private HBox cardPharmacist;
    private HBox cardManager;

    private Label selectedStaffNameLabel;
    private HBox pinDotsBox;
    private Region dot1;
    private Region dot2;
    private Region dot3;
    private Region dot4;

    private Label shiftInfoPill;
    private Label floatInfoPill;
    private Button offlineModeToggleBtn;
    private Label statusMessageLabel;
    private Button unlockBtn;

    @BeforeAll
    static void initJavaFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException e) {
            // Already initialized
            latch.countDown();
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "JavaFX Toolkit must initialize");
    }

    @BeforeEach
    void setUp() throws Exception {
        controller = new LoginGateController();

        liveClockLabel = new Label();
        liveDateLabel = new Label();
        authorityStatusLabel = new Label();
        screenHeadingLabel = new Label();
        screenSubheadingLabel = new Label();
        langEnBtn = new Button("English");
        langBnBtn = new Button("বাংলা");
        selectStaffLabel = new Label();

        cardCashier = new HBox();
        cardPharmacist = new HBox();
        cardManager = new HBox();

        selectedStaffNameLabel = new Label();
        pinDotsBox = new HBox();
        dot1 = new Region();
        dot2 = new Region();
        dot3 = new Region();
        dot4 = new Region();

        shiftInfoPill = new Label();
        floatInfoPill = new Label();
        offlineModeToggleBtn = new Button();
        statusMessageLabel = new Label();
        unlockBtn = new Button("Unlock");

        setField("liveClockLabel", liveClockLabel);
        setField("liveDateLabel", liveDateLabel);
        setField("authorityStatusLabel", authorityStatusLabel);
        setField("screenHeadingLabel", screenHeadingLabel);
        setField("screenSubheadingLabel", screenSubheadingLabel);
        setField("langEnBtn", langEnBtn);
        setField("langBnBtn", langBnBtn);
        setField("selectStaffLabel", selectStaffLabel);
        setField("cardCashier", cardCashier);
        setField("cardPharmacist", cardPharmacist);
        setField("cardManager", cardManager);
        setField("selectedStaffNameLabel", selectedStaffNameLabel);
        setField("pinDotsBox", pinDotsBox);
        setField("dot1", dot1);
        setField("dot2", dot2);
        setField("dot3", dot3);
        setField("dot4", dot4);
        setField("shiftInfoPill", shiftInfoPill);
        setField("floatInfoPill", floatInfoPill);
        setField("offlineModeToggleBtn", offlineModeToggleBtn);
        setField("statusMessageLabel", statusMessageLabel);
        setField("unlockBtn", unlockBtn);

        controller.initialize();
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field f = LoginGateController.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(controller, value);
    }

    @Test
    @DisplayName("1. Initialization sets default cashier profile and pre-fills 4-digit PIN")
    void testInitialProfileDefaults() {
        assertEquals("cashier-demo", controller.getCurrentStaffId());
        assertEquals("Tanvir Ahmed", controller.getCurrentStaffName());
        assertEquals("CASHIER", controller.getCurrentStaffRole());
        assertEquals(4, controller.getPinLength());
        assertTrue(cardCashier.getStyleClass().contains("staff-tile-active"));
        assertNotNull(liveClockLabel.getText());
        assertNotNull(liveDateLabel.getText());
    }

    @Test
    @DisplayName("2. Selecting Pharmacist avatar switches profile to Dr. Fahim Hasan")
    void testSelectPharmacist() {
        controller.handleSelectPharmacist();

        assertEquals("rx-pharmacist-01", controller.getCurrentStaffId());
        assertEquals("Dr. Fahim Hasan", controller.getCurrentStaffName());
        assertEquals("PHARMACIST", controller.getCurrentStaffRole());
        assertTrue(cardPharmacist.getStyleClass().contains("staff-tile-active"));
        assertFalse(cardCashier.getStyleClass().contains("staff-tile-active"));
        assertTrue(statusMessageLabel.getText().contains("Dr. Fahim Hasan"));
    }

    @Test
    @DisplayName("3. Selecting Manager avatar switches profile to Rafiqul Islam")
    void testSelectManager() {
        controller.handleSelectManager();

        assertEquals("manager-dhaka-01", controller.getCurrentStaffId());
        assertEquals("Rafiqul Islam", controller.getCurrentStaffName());
        assertEquals("BRANCH_MANAGER", controller.getCurrentStaffRole());
        assertTrue(cardManager.getStyleClass().contains("staff-tile-active"));
        assertTrue(statusMessageLabel.getText().contains("Rafiqul Islam"));
    }

    @Test
    @DisplayName("4. Tactile Numpad digits append to PIN")
    void testNumpadKeyInput() {
        controller.handleClearPin();
        assertEquals(0, controller.getPinLength());

        Button btn7 = new Button("7");
        Button btn8 = new Button("8");
        Button btn9 = new Button("9");

        controller.handleNumKey(new ActionEvent(btn7, null));
        controller.handleNumKey(new ActionEvent(btn8, null));
        controller.handleNumKey(new ActionEvent(btn9, null));

        assertEquals(3, controller.getPinLength());
    }

    @Test
    @DisplayName("5. Backspace removes last entered PIN digit")
    void testNumpadBackspace() {
        assertEquals(4, controller.getPinLength());

        controller.handleBackspacePin();
        assertEquals(3, controller.getPinLength());

        controller.handleBackspacePin();
        assertEquals(2, controller.getPinLength());
    }

    @Test
    @DisplayName("6. Clear button resets all PIN dots to empty")
    void testNumpadClear() {
        controller.handleClearPin();
        assertEquals(0, controller.getPinLength());
    }

    @Test
    @DisplayName("7. Language toggle cleanly switches between English and Bangla")
    void testLanguageToggle() {
        assertFalse(controller.isBangla());

        controller.handleSwitchLangBn();
        assertTrue(controller.isBangla());
        assertEquals("কাউন্টার রেজিস্টার আনলক করুন", screenHeadingLabel.getText());

        controller.handleSwitchLangEn();
        assertFalse(controller.isBangla());
        assertEquals("Sign In & Unlock Register", screenHeadingLabel.getText());
    }

    @Test
    @DisplayName("8. Offline mode toggle updates authority status badge")
    void testOfflineModeToggle() {
        assertFalse(controller.isOfflineMode());

        controller.handleToggleOfflineMode();
        assertTrue(controller.isOfflineMode());
        assertEquals("Offline Cached Mode", authorityStatusLabel.getText());

        controller.handleToggleOfflineMode();
        assertFalse(controller.isOfflineMode());
        assertEquals("Cloud Online", authorityStatusLabel.getText());
    }

    @Test
    @DisplayName("9. Incomplete PIN under 4 digits is rejected with alert message")
    void testIncompletePinRejection() {
        controller.handleClearPin();
        Button btn1 = new Button("1");
        controller.handleNumKey(new ActionEvent(btn1, null));
        assertEquals(1, controller.getPinLength());

        controller.handleUnlockCounter();
        assertTrue(statusMessageLabel.getText().contains("complete 4-digit PIN"));
    }
}
