package com.pharmacy.pos.controllers;

import com.pharmacy.pos.MainApp;
import com.pharmacy.pos.models.CartItem;
import com.pharmacy.pos.models.SaleTransaction;
import com.pharmacy.pos.models.TenantSession;
import com.pharmacy.pos.services.OfflineSyncEngine;
import com.pharmacy.pos.services.PosSecurityService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Controller for POS Cashier Interface.
 * Implements high-density keyboard workflows, responsive background updates,
 * and offline status indicators (ADR-0007, ADR-0018, ADR-0023).
 */
public class PosController {
    @FXML private TextField barcodeInputField;
    @FXML private TableView<CartItem> cartTable;
    @FXML private TableColumn<CartItem, String> brandCol;
    @FXML private TableColumn<CartItem, String> unitCol;
    @FXML private TableColumn<CartItem, Integer> qtyCol;
    @FXML private TableColumn<CartItem, BigDecimal> priceCol;
    @FXML private TableColumn<CartItem, BigDecimal> totalCol;

    @FXML private Label subtotalLabel;
    @FXML private Label vatLabel;
    @FXML private Label grandTotalLabel;
    @FXML private Label networkStatusLabel;
    @FXML private Label shiftStatusBadge;
    @FXML private Label cashierFooterLabel;
    @FXML private Button checkoutButton;

    private final ObservableList<CartItem> cartItems = FXCollections.observableArrayList();
    private final PosSecurityService securityService = new PosSecurityService();
    private final OfflineSyncEngine syncEngine = new OfflineSyncEngine();
    private TenantSession currentSession;

    @FXML
    public void initialize() {
        if (cartTable != null) {
            brandCol.setCellValueFactory(new PropertyValueFactory<>("brandName"));
            unitCol.setCellValueFactory(new PropertyValueFactory<>("packagingUnit"));
            qtyCol.setCellValueFactory(new PropertyValueFactory<>("quantity"));
            priceCol.setCellValueFactory(new PropertyValueFactory<>("unitPrice"));
            totalCol.setCellValueFactory(new PropertyValueFactory<>("lineTotal"));
            cartTable.setItems(cartItems);
        }
        updateTotals();
    }

    public void setSession(TenantSession session) {
        this.currentSession = session;
        if (cashierFooterLabel != null && session != null) {
            cashierFooterLabel.setText("Cashier: " + session.getUserId() + " (" + session.getUserRole() + ") | Branch: " + session.getBranchId());
        }
    }

    public void setSessionDetails(TenantSession session, String staffName, String shiftName, String openingFloat) {
        this.currentSession = session;
        if (cashierFooterLabel != null && session != null) {
            cashierFooterLabel.setText("Cashier: " + staffName + " (" + session.getUserRole() + ") | Branch: Dhanmondi Central | Shift Float: BDT " + openingFloat);
        }
        if (shiftStatusBadge != null) {
            String shortShift = (shiftName != null && shiftName.contains("("))
                    ? shiftName.substring(0, shiftName.indexOf("(")).trim()
                    : (shiftName != null ? shiftName : "Active");
            shiftStatusBadge.setText("Shift: " + shortShift);
        }
    }

    @FXML
    public void handleLockCounter() {
        if (checkoutButton != null && checkoutButton.getScene() != null) {
            Stage stage = (Stage) checkoutButton.getScene().getWindow();
            MainApp.showLoginGate(stage);
        }
    }

    public void addItem(CartItem item) {
        cartItems.add(item);
        updateTotals();
    }

    public void updateTotals() {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;

        for (CartItem item : cartItems) {
            subtotal = subtotal.add(item.getLineSubtotal());
            vat = vat.add(item.getLineVat());
        }

        BigDecimal grandTotal = subtotal.add(vat);

        if (subtotalLabel != null) subtotalLabel.setText("BDT " + subtotal.toPlainString());
        if (vatLabel != null) vatLabel.setText("BDT " + vat.toPlainString());
        if (grandTotalLabel != null) grandTotalLabel.setText("BDT " + grandTotal.toPlainString());
    }

    @FXML
    public void handleCheckout() {
        if (currentSession != null) {
            securityService.assertCanCheckout(currentSession);
        }

        if (cartItems.isEmpty()) {
            return;
        }

        boolean isOffline = !syncEngine.isOnline();
        SaleTransaction transaction = new SaleTransaction(
                UUID.randomUUID().toString(),
                "INV-" + System.currentTimeMillis(),
                currentSession != null ? currentSession.getTenantId() : "tenant-demo",
                currentSession != null ? currentSession.getBranchId() : "branch-main",
                currentSession != null ? currentSession.getUserId() : "cashier-1",
                cartItems,
                BigDecimal.ZERO,
                isOffline,
                Instant.now()
        );

        if (isOffline) {
            syncEngine.enqueueTransaction(transaction);
        }

        cartItems.clear();
        updateTotals();
    }
}

