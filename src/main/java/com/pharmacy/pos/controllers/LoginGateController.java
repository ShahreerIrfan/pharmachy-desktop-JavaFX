package com.pharmacy.pos.controllers;

import com.pharmacy.pos.MainApp;
import com.pharmacy.pos.models.TenantSession;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Controller for World-Class Split-Screen POS Terminal Gate.
 * Architecture Decision: ADR-0007, ADR-0021, ADR-0023 & ADR-0113.
 *
 * Provides a high-contrast enterprise hero on the left and a tactile touch
 * PIN-pad & staff avatar selector on the right.
 */
public class LoginGateController {

    // Left Column Elements
    @FXML private Label liveClockLabel;
    @FXML private Label liveDateLabel;
    @FXML private Label authorityStatusLabel;

    // Right Column Elements
    @FXML private Label screenHeadingLabel;
    @FXML private Label screenSubheadingLabel;
    @FXML private Button langEnBtn;
    @FXML private Button langBnBtn;
    @FXML private Label selectStaffLabel;

    @FXML private HBox cardCashier;
    @FXML private HBox cardPharmacist;
    @FXML private HBox cardManager;

    @FXML private Label selectedStaffNameLabel;
    @FXML private HBox pinDotsBox;
    @FXML private Region dot1;
    @FXML private Region dot2;
    @FXML private Region dot3;
    @FXML private Region dot4;

    @FXML private Label shiftInfoPill;
    @FXML private Label floatInfoPill;
    @FXML private Button offlineModeToggleBtn;
    @FXML private Label statusMessageLabel;
    @FXML private Button unlockBtn;

    // Internal Terminal State
    private String currentStaffId = "cashier-demo";
    private String currentStaffRole = "CASHIER";
    private String currentStaffName = "Tanvir Ahmed";
    private final StringBuilder pinBuilder = new StringBuilder("1234");
    private boolean isOfflineMode = false;
    private boolean isBangla = false;

    @FXML
    public void initialize() {
        updateClockAndDate();
        updatePinDotsVisual();
        updateStaffCardStyles();
        updateLanguageLabels();

        // Keyboard Event Listener for fast typing on Numpad
        Platform.runLater(() -> {
            if (unlockBtn != null && unlockBtn.getScene() != null) {
                unlockBtn.getScene().addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyboardInput);
            }
        });
    }

    private void handleKeyboardInput(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code.isDigitKey()) {
            String digit = event.getText();
            if (digit != null && !digit.isEmpty() && pinBuilder.length() < 4) {
                pinBuilder.append(digit);
                updatePinDotsVisual();
            }
        } else if (code == KeyCode.BACK_SPACE) {
            handleBackspacePin();
        } else if (code == KeyCode.ESCAPE) {
            handleClearPin();
        } else if (code == KeyCode.ENTER) {
            handleUnlockCounter();
        }
    }

    private void updateClockAndDate() {
        if (liveClockLabel != null) {
            DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);
            liveClockLabel.setText(LocalTime.now().format(timeFormatter));
        }
        if (liveDateLabel != null) {
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
            liveDateLabel.setText(LocalDate.now().format(dateFormatter));
        }
    }

    @FXML
    public void handleSelectCashier() {
        currentStaffId = "cashier-demo";
        currentStaffRole = "CASHIER";
        currentStaffName = "Tanvir Ahmed";
        pinBuilder.setLength(0);
        pinBuilder.append("1234");
        updateStaffCardStyles();
        updatePinDotsVisual();
        setStatus("Cashier Tanvir Ahmed selected. PIN pre-filled (1234).", false);
    }

    @FXML
    public void handleSelectPharmacist() {
        currentStaffId = "rx-pharmacist-01";
        currentStaffRole = "PHARMACIST";
        currentStaffName = "Dr. Fahim Hasan";
        pinBuilder.setLength(0);
        pinBuilder.append("1234");
        updateStaffCardStyles();
        updatePinDotsVisual();
        setStatus("Pharmacist Dr. Fahim Hasan selected. PIN pre-filled (1234).", false);
    }

    @FXML
    public void handleSelectManager() {
        currentStaffId = "manager-dhaka-01";
        currentStaffRole = "BRANCH_MANAGER";
        currentStaffName = "Rafiqul Islam";
        pinBuilder.setLength(0);
        pinBuilder.append("1234");
        updateStaffCardStyles();
        updatePinDotsVisual();
        setStatus("Branch Manager Rafiqul Islam selected. PIN pre-filled (1234).", false);
    }

    private void updateStaffCardStyles() {
        if (cardCashier != null) {
            cardCashier.getStyleClass().removeAll("staff-tile-active");
            if ("cashier-demo".equals(currentStaffId)) cardCashier.getStyleClass().add("staff-tile-active");
        }
        if (cardPharmacist != null) {
            cardPharmacist.getStyleClass().removeAll("staff-tile-active");
            if ("rx-pharmacist-01".equals(currentStaffId)) cardPharmacist.getStyleClass().add("staff-tile-active");
        }
        if (cardManager != null) {
            cardManager.getStyleClass().removeAll("staff-tile-active");
            if ("manager-dhaka-01".equals(currentStaffId)) cardManager.getStyleClass().add("staff-tile-active");
        }
        if (selectedStaffNameLabel != null) {
            String roleDisplay = isBangla ? getRoleInBangla(currentStaffRole) : currentStaffRole;
            selectedStaffNameLabel.setText((isBangla ? "অপারেটর: " : "Logging in as: ") + currentStaffName + " (" + roleDisplay + ")");
        }
    }

    @FXML
    public void handleNumKey(ActionEvent event) {
        if (event.getSource() instanceof Button btn) {
            String digit = btn.getText();
            if (pinBuilder.length() < 4) {
                pinBuilder.append(digit);
                updatePinDotsVisual();
                if (pinBuilder.length() == 4) {
                    setStatus(isBangla ? "৪-সংখ্যার পিন সম্পন্ন। আনলক করতে Enter চাপুন।" : "4-digit PIN complete. Press Enter to unlock.", false);
                }
            }
        }
    }

    @FXML
    public void handleBackspacePin() {
        if (pinBuilder.length() > 0) {
            pinBuilder.deleteCharAt(pinBuilder.length() - 1);
            updatePinDotsVisual();
        }
    }

    @FXML
    public void handleClearPin() {
        pinBuilder.setLength(0);
        updatePinDotsVisual();
        setStatus(isBangla ? "পিন মুছে ফেলা হয়েছে।" : "PIN cleared. Enter 4 digits.", false);
    }

    private void updatePinDotsVisual() {
        int length = pinBuilder.length();
        setDotFilled(dot1, length >= 1);
        setDotFilled(dot2, length >= 2);
        setDotFilled(dot3, length >= 3);
        setDotFilled(dot4, length >= 4);
    }

    private void setDotFilled(Region dot, boolean filled) {
        if (dot != null) {
            dot.getStyleClass().removeAll("pin-dot-filled");
            if (filled) {
                dot.getStyleClass().add("pin-dot-filled");
            }
        }
    }

    @FXML
    public void handleToggleOfflineMode() {
        isOfflineMode = !isOfflineMode;
        if (authorityStatusLabel != null) {
            if (isOfflineMode) {
                authorityStatusLabel.setText(isBangla ? "অফলাইন ক্যাশ মোড" : "Offline Cached Mode");
                authorityStatusLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #F59E0B;");
            } else {
                authorityStatusLabel.setText(isBangla ? "ক্লাউড অনলাইন" : "Cloud Online");
                authorityStatusLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #4ADE80;");
            }
        }
        if (offlineModeToggleBtn != null) {
            offlineModeToggleBtn.setText(isOfflineMode ? "Offline Mode: ON" : "Offline Mode: OFF");
        }
    }

    @FXML
    public void handleSwitchLangEn() {
        isBangla = false;
        if (langEnBtn != null) langEnBtn.getStyleClass().add("lang-toggle-btn-active");
        if (langBnBtn != null) langBnBtn.getStyleClass().remove("lang-toggle-btn-active");
        updateLanguageLabels();
    }

    @FXML
    public void handleSwitchLangBn() {
        isBangla = true;
        if (langBnBtn != null) langBnBtn.getStyleClass().add("lang-toggle-btn-active");
        if (langEnBtn != null) langEnBtn.getStyleClass().remove("lang-toggle-btn-active");
        updateLanguageLabels();
    }

    private void updateLanguageLabels() {
        if (screenHeadingLabel != null) {
            screenHeadingLabel.setText(isBangla ? "কাউন্টার রেজিস্টার আনলক করুন" : "Sign In & Unlock Register");
        }
        if (screenSubheadingLabel != null) {
            screenSubheadingLabel.setText(isBangla ? "আপনার স্টাফ প্রোফাইল সিলেক্ট করে ৪-সংখ্যার পিন দিন" : "Select your staff profile and enter your 4-digit security PIN");
        }
        if (selectStaffLabel != null) {
            selectStaffLabel.setText(isBangla ? "১. কর্মরত স্টাফ নির্বাচন করুন:" : "1. Select Operating Staff Member:");
        }
        if (shiftInfoPill != null) {
            shiftInfoPill.setText(isBangla ? "শিফট: সকাল (০৮:০০ - ১৬:০০)" : "Shift: Morning (08:00 AM - 04:00 PM)");
        }
        if (floatInfoPill != null) {
            floatInfoPill.setText(isBangla ? "প্রারম্ভিক ক্যাশ: ১,০০০.০০ টাকা" : "Opening Float: BDT 1,000.00");
        }
        if (unlockBtn != null) {
            unlockBtn.setText(isBangla ? "কাউন্টার খুলুন ও শিফট শুরু করুন (Enter)" : "UNLOCK COUNTER & START SHIFT (Enter)");
        }
        updateStaffCardStyles();
    }

    private String getRoleInBangla(String role) {
        return switch (role) {
            case "CASHIER" -> "ক্যাশিয়ার";
            case "PHARMACIST" -> "ফার্মাসিস্ট";
            case "BRANCH_MANAGER" -> "স্টোর ম্যানেজার";
            default -> role;
        };
    }

    @FXML
    public void handleUnlockCounter() {
        if (pinBuilder.length() < 4) {
            setStatus(isBangla ? "অনুগ্রহ করে ৪-সংখ্যার সম্পূর্ণ পিন দিন।" : "Please enter complete 4-digit PIN.", true);
            return;
        }

        TenantSession session = new TenantSession(
                "tenant-dhaka-01",
                "branch-dhanmondi",
                "POS-TER-001",
                currentStaffId,
                currentStaffRole,
                true
        );

        setStatus(isBangla ? "সফলভাবে প্রবেশ করা হচ্ছে..." : "Authenticated successfully. Opening counter...", false);

        if (unlockBtn != null && unlockBtn.getScene() != null) {
            Stage stage = (Stage) unlockBtn.getScene().getWindow();
            Platform.runLater(() -> {
                MainApp.showPosScreen(
                        stage,
                        session,
                        currentStaffName,
                        "Morning Shift (08:00 AM - 04:00 PM)",
                        "1000.00"
                );
            });
        }
    }

    private void setStatus(String message, boolean isError) {
        if (statusMessageLabel != null) {
            statusMessageLabel.setText(message);
            statusMessageLabel.setStyle(isError
                    ? "-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #DC2626;"
                    : "-fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: #15803D;");
        }
    }

    // Helper getters for test verification
    public String getCurrentStaffId() { return currentStaffId; }
    public String getCurrentStaffName() { return currentStaffName; }
    public String getCurrentStaffRole() { return currentStaffRole; }
    public int getPinLength() { return pinBuilder.length(); }
    public boolean isOfflineMode() { return isOfflineMode; }
    public boolean isBangla() { return isBangla; }
}
