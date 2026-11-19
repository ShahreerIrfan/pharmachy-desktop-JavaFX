package com.pharmacy.pos.dashboard;

import com.pharmacy.pos.dashboard.PosOwnerDashboardClient.UiStatus;
import com.pharmacy.pos.shell.ShellRoute;
import com.pharmacy.pos.shell.ShellSvgIcons;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

import java.math.BigDecimal;

/**
 * ReferenceDashboardView - macOS-Inspired Operational Pharmacy Dashboard View.
 * Architecture Decision: ADR-0101, ADR-0321, ADR-0322.
 * Phase 17: Milestone P322.
 *
 * Implements:
 * 1. Reference dashboard layout, hierarchy, and operational quick navigation.
 * 2. Dynamic live dates and exact BDT Decimal currency formatting.
 * 3. Restrained Apple design system with zero emojis and real SVG vector icons.
 * 4. Responsive UI states: LOADING, SUCCESS, EMPTY, OFFLINE, ERROR, PERMISSION_DENIED.
 */
public class ReferenceDashboardView extends ScrollPane {

    private final ReferenceDashboardViewModel viewModel;
    private final VBox mainContainer;

    // Header Controls
    private Label branchTitleLabel;
    private Label dateDisplayLabel;
    private Label statusBadge;

    // KPI Metric Labels
    private Label todaySalesLabel;
    private Label orderCountLabel;
    private Label grossProfitLabel;
    private Label grossMarginLabel;
    private Label lowStockLabel;
    private Label pendingRxLabel;

    // State Overlays
    private VBox stateOverlay;
    private Label stateMessageLabel;

    public ReferenceDashboardView(ReferenceDashboardViewModel viewModel) {
        this.viewModel = viewModel;
        this.setFitToWidth(true);
        this.setStyle("-fx-background: #F8F9FA; -fx-background-color: #F8F9FA;");

        this.mainContainer = new VBox(20);
        this.mainContainer.setPadding(new Insets(24));
        this.mainContainer.setMaxWidth(1280);

        buildViewHierarchy();
        bindViewModel();

        this.setContent(this.mainContainer);
    }

    private void buildViewHierarchy() {
        // 1. Header & Operational Status Banner
        this.mainContainer.getChildren().add(buildHeaderBanner());

        // 2. 4-Column KPI Metric Cards Grid
        this.mainContainer.getChildren().add(buildMetricCardsRow());

        // 3. Operational Quick Action Navigation
        this.mainContainer.getChildren().add(buildQuickActionNavigation());

        // 4. Secondary Operational Context (Shift & Regulatory DGDA Tracker)
        this.mainContainer.getChildren().add(buildSecondaryOperationalGrid());

        // 5. State Overlay (for Loading, Empty, Error, Permission-Denied)
        this.stateOverlay = buildStateOverlay();
        this.mainContainer.getChildren().add(this.stateOverlay);
    }

    private HBox buildHeaderBanner() {
        HBox banner = new HBox(16);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setPadding(new Insets(16, 20, 16, 20));
        banner.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 8px; -fx-border-color: #E5E7EB; -fx-border-radius: 8px;");

        VBox titleGroup = new VBox(4);
        Label mainHeading = new Label(viewModel.isBengali() ? "ফার্মেসি অপারেশনাল ড্যাশবোর্ড" : "Pharmacy Operational Dashboard");
        mainHeading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #111827;");

        this.branchTitleLabel = new Label();
        this.branchTitleLabel.textProperty().bind(viewModel.branchNameProperty().concat(" • ").concat(viewModel.activeShiftNameProperty()));
        this.branchTitleLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #6B7280;");
        titleGroup.getChildren().addAll(mainHeading, this.branchTitleLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        this.dateDisplayLabel = new Label();
        this.dateDisplayLabel.textProperty().bind(viewModel.liveDateStringProperty());
        this.dateDisplayLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #4B5563; -fx-padding: 6px 12px; -fx-background-color: #F3F4F6; -fx-background-radius: 6px;");

        this.statusBadge = new Label();
        updateStatusBadge(viewModel.getUiStatus());

        banner.getChildren().addAll(titleGroup, spacer, this.dateDisplayLabel, this.statusBadge);
        return banner;
    }

    private GridPane buildMetricCardsRow() {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(16);

        for (int i = 0; i < 4; i++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(25);
            grid.getColumnConstraints().add(col);
        }

        // Card 1: Today's Sales
        this.todaySalesLabel = new Label("BDT 0.00");
        this.todaySalesLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #0284C7;");
        this.orderCountLabel = new Label("0 Orders");
        this.orderCountLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #6B7280;");
        Node card1 = createKpiCard(
                viewModel.isBengali() ? "আজকের মোট বিক্রয়" : "Today's Revenue",
                ShellSvgIcons.PATH_POS_CART,
                Color.web("#0284C7"),
                this.todaySalesLabel,
                this.orderCountLabel
        );

        // Card 2: Profitability / Gross Margin
        this.grossProfitLabel = new Label("BDT 0.00");
        this.grossProfitLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #16A34A;");
        this.grossMarginLabel = new Label("0.0% Margin");
        this.grossMarginLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #6B7280;");
        Node card2 = createKpiCard(
                viewModel.isBengali() ? "মোট মুনাফা ও মার্জিন" : "Gross Profit & Margin",
                ShellSvgIcons.PATH_INVENTORY_BOX,
                Color.web("#16A34A"),
                this.grossProfitLabel,
                this.grossMarginLabel
        );

        // Card 3: Inventory Health / Low Stock
        this.lowStockLabel = new Label("0 Items");
        this.lowStockLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #D97706;");
        Label alertSub = new Label(viewModel.isBengali() ? "রি-অর্ডার সতর্কতা" : "Reorder Threshold Alerts");
        alertSub.setStyle("-fx-font-size: 12px; -fx-text-fill: #6B7280;");
        Node card3 = createKpiCard(
                viewModel.isBengali() ? "স্বল্প মজুদ সতর্কতা" : "Low Stock Alerts",
                ShellSvgIcons.PATH_INVENTORY_BOX,
                Color.web("#D97706"),
                this.lowStockLabel,
                alertSub
        );

        // Card 4: Prescription Queue
        this.pendingRxLabel = new Label("0 Pending");
        this.pendingRxLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #9333EA;");
        Label rxSub = new Label(viewModel.isBengali() ? "ফার্মাসিস্ট যাচাই অপেক্ষমান" : "Awaiting Pharmacist Sign-Off");
        rxSub.setStyle("-fx-font-size: 12px; -fx-text-fill: #6B7280;");
        Node card4 = createKpiCard(
                viewModel.isBengali() ? "প্রেসক্রিপশন কিউ" : "Prescription Queue",
                ShellSvgIcons.PATH_PRESCRIPTION_PILL,
                Color.web("#9333EA"),
                this.pendingRxLabel,
                rxSub
        );

        grid.add(card1, 0, 0);
        grid.add(card2, 1, 0);
        grid.add(card3, 2, 0);
        grid.add(card4, 3, 0);
        return grid;
    }

    private Node createKpiCard(String title, String iconPath, Color iconColor, Label valueLabel, Label subLabel) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(16));
        card.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 8px; -fx-border-color: #E5E7EB; -fx-border-radius: 8px;");

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        SVGPath icon = ShellSvgIcons.createIcon(iconPath, iconColor, 0.8);
        Label titleLbl = new Label(title);
        titleLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #4B5563;");
        header.getChildren().addAll(icon, titleLbl);

        card.getChildren().addAll(header, valueLabel, subLabel);
        return card;
    }

    private VBox buildQuickActionNavigation() {
        VBox box = new VBox(12);
        Label heading = new Label(viewModel.isBengali() ? "অপারেশনাল দ্রুত নেভিগেশন" : "Operational Fast Navigation");
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #111827;");

        HBox actionsRow = new HBox(12);
        actionsRow.getChildren().addAll(
                createActionButton("New Sale / Billing", "নতুন বিক্রয়", "F2", ShellSvgIcons.PATH_POS_CART, () -> viewModel.navigateToRoute(ShellRoute.POS_SALES)),
                createActionButton("Inventory Ledger", "মজুদ লেজার", "F3", ShellSvgIcons.PATH_INVENTORY_BOX, () -> viewModel.navigateToRoute(ShellRoute.INVENTORY_LEDGER)),
                createActionButton("Prescription Verification", "প্রেসক্রিপশন যাচাই", "F4", ShellSvgIcons.PATH_PRESCRIPTION_PILL, () -> viewModel.navigateToRoute(ShellRoute.PRESCRIPTIONS)),
                createActionButton("Sync Health Center", "ক্লাউড সিঙ্ক সেন্টার", "F5", ShellSvgIcons.PATH_CLOUD_SYNC, () -> viewModel.navigateToRoute(ShellRoute.OFFLINE_SYNC)),
                createActionButton("Daily Shift & Drawer", "দৈনিক শিফট ও ক্যাশ", "F9", ShellSvgIcons.PATH_DAILY_SHIFT, () -> viewModel.navigateToRoute(ShellRoute.DAILY_SHIFT))
        );

        box.getChildren().addAll(heading, actionsRow);
        return box;
    }

    private Button createActionButton(String labelEn, String labelBn, String shortcut, String iconPath, Runnable action) {
        Button btn = new Button();
        btn.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #E5E7EB; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-padding: 10px 14px; -fx-cursor: hand;");
        btn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btn, Priority.ALWAYS);

        HBox content = new HBox(8);
        content.setAlignment(Pos.CENTER_LEFT);
        SVGPath icon = ShellSvgIcons.createIcon(iconPath, Color.web("#0284C7"), 0.75);
        Label lbl = new Label(viewModel.isBengali() ? labelBn : labelEn);
        lbl.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #1F2937;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label sc = new Label(shortcut);
        sc.setStyle("-fx-background-color: #F3F4F6; -fx-text-fill: #6B7280; -fx-font-size: 10px; -fx-padding: 2px 6px; -fx-background-radius: 3px; -fx-font-weight: bold;");

        content.getChildren().addAll(icon, lbl, spacer, sc);
        btn.setGraphic(content);
        btn.setOnAction(e -> action.run());
        return btn;
    }

    private GridPane buildSecondaryOperationalGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(16);

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        grid.getColumnConstraints().addAll(col1, col2);

        // Left Card: Shift Drawer & Float Tracker
        VBox drawerCard = new VBox(10);
        drawerCard.setPadding(new Insets(16));
        drawerCard.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #E5E7EB; -fx-border-radius: 8px; -fx-background-radius: 8px;");

        Label drawerTitle = new Label(viewModel.isBengali() ? "ক্যাশ ড্রয়ার ও শিফট স্থিতি" : "Cash Drawer & Shift Status");
        drawerTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #111827;");
        Label floatInfo = new Label();
        floatInfo.textProperty().bind(viewModel.cashDrawerFloatBdtProperty().asString("Opening Cash Float: BDT %.2f"));
        floatInfo.setStyle("-fx-font-size: 13px; -fx-text-fill: #4B5563;");

        Label drawerSub = new Label("Shift Active • Reconciled Drawer Balance Maintained");
        drawerSub.setStyle("-fx-font-size: 11px; -fx-text-fill: #16A34A;");
        drawerCard.getChildren().addAll(drawerTitle, floatInfo, drawerSub);

        // Right Card: DGDA Regulatory Expiry & Antimicrobial Register
        VBox dgdaCard = new VBox(10);
        dgdaCard.setPadding(new Insets(16));
        dgdaCard.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #E5E7EB; -fx-border-radius: 8px; -fx-background-radius: 8px;");

        Label dgdaTitle = new Label(viewModel.isBengali() ? "ডিজিডিএ রেগুলেটরি সতর্কতা" : "DGDA Regulatory & Batch Expiry Tracker");
        dgdaTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #111827;");
        Label expiringInfo = new Label();
        expiringInfo.textProperty().bind(viewModel.expiringBatchesCountProperty().asString("Near-Expiry Batches (30-90 Days): %d"));
        expiringInfo.setStyle("-fx-font-size: 13px; -fx-text-fill: #D97706;");

        Label dgdaSub = new Label("FEFO Batch Discipline Enforced • Antibiotic Register Audited");
        dgdaSub.setStyle("-fx-font-size: 11px; -fx-text-fill: #6B7280;");
        dgdaCard.getChildren().addAll(dgdaTitle, expiringInfo, dgdaSub);

        grid.add(drawerCard, 0, 0);
        grid.add(dgdaCard, 1, 0);
        return grid;
    }

    private VBox buildStateOverlay() {
        VBox overlay = new VBox(12);
        overlay.setAlignment(Pos.CENTER);
        overlay.setPadding(new Insets(32));
        overlay.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 8px; -fx-border-color: #E5E7EB; -fx-border-radius: 8px;");
        overlay.setVisible(false);
        overlay.setManaged(false);

        this.stateMessageLabel = new Label();
        this.stateMessageLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #4B5563;");
        overlay.getChildren().add(this.stateMessageLabel);
        return overlay;
    }

    private void bindViewModel() {
        viewModel.todaySalesBdtProperty().addListener((obs, oldV, newV) -> {
            todaySalesLabel.setText(String.format("BDT %,.2f", newV != null ? newV.doubleValue() : 0.0));
        });

        viewModel.totalOrdersCountProperty().addListener((obs, oldV, newV) -> {
            orderCountLabel.setText(newV + " Orders");
        });

        viewModel.grossProfitBdtProperty().addListener((obs, oldV, newV) -> {
            if (viewModel.isFinancialMetricsMasked()) {
                grossProfitLabel.setText("Active");
                grossMarginLabel.setText("Counter Mode");
            } else {
                grossProfitLabel.setText(String.format("BDT %,.2f", newV != null ? newV.doubleValue() : 0.0));
                grossMarginLabel.setText(viewModel.grossMarginPercentProperty().get() + "% Margin");
            }
        });

        viewModel.lowStockAlertCountProperty().addListener((obs, oldV, newV) -> {
            lowStockLabel.setText(newV + " Items");
        });

        viewModel.pendingRxCountProperty().addListener((obs, oldV, newV) -> {
            pendingRxLabel.setText(newV + " Pending");
        });

        viewModel.uiStatusProperty().addListener((obs, oldS, newS) -> {
            updateStatusBadge(newS);
            renderStateOverlay(newS);
        });
    }

    private void updateStatusBadge(UiStatus status) {
        if (status == null) return;
        switch (status) {
            case SUCCESS -> {
                statusBadge.setText("● Live Operational Authority");
                statusBadge.setStyle("-fx-background-color: #DCFCE7; -fx-text-fill: #15803D; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 4px 10px; -fx-background-radius: 12px;");
            }
            case OFFLINE -> {
                statusBadge.setText("▲ Offline Local Cache (SQLite WAL)");
                statusBadge.setStyle("-fx-background-color: #FEF3C7; -fx-text-fill: #B45309; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 4px 10px; -fx-background-radius: 12px;");
            }
            case LOADING -> {
                statusBadge.setText("◌ Loading Telemetry...");
                statusBadge.setStyle("-fx-background-color: #EFF6FF; -fx-text-fill: #1D4ED8; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 4px 10px; -fx-background-radius: 12px;");
            }
            case EMPTY -> {
                statusBadge.setText("○ No Transactions Today");
                statusBadge.setStyle("-fx-background-color: #F3F4F6; -fx-text-fill: #4B5563; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 4px 10px; -fx-background-radius: 12px;");
            }
            case ERROR -> {
                statusBadge.setText("✕ Telemetry Error");
                statusBadge.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #991B1B; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 4px 10px; -fx-background-radius: 12px;");
            }
            case PERMISSION_DENIED -> {
                statusBadge.setText("🔒 Access Denied");
                statusBadge.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #991B1B; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 4px 10px; -fx-background-radius: 12px;");
            }
        }
    }

    private void renderStateOverlay(UiStatus status) {
        if (status == UiStatus.ERROR || status == UiStatus.PERMISSION_DENIED || status == UiStatus.EMPTY) {
            stateOverlay.setVisible(true);
            stateOverlay.setManaged(true);
            if (status == UiStatus.PERMISSION_DENIED) {
                stateMessageLabel.setText(viewModel.getErrorMessage().isEmpty() ? "Permission Denied: Unauthorized access to executive dashboard." : viewModel.getErrorMessage());
                stateMessageLabel.setStyle("-fx-text-fill: #991B1B; -fx-font-size: 14px; -fx-font-weight: bold;");
            } else if (status == UiStatus.EMPTY) {
                stateMessageLabel.setText("No billing transactions recorded today. Press F2 to begin quick billing.");
                stateMessageLabel.setStyle("-fx-text-fill: #4B5563; -fx-font-size: 14px;");
            } else {
                stateMessageLabel.setText(viewModel.getErrorMessage().isEmpty() ? "Failed to load dashboard metrics." : viewModel.getErrorMessage());
                stateMessageLabel.setStyle("-fx-text-fill: #DC2626; -fx-font-size: 14px; -fx-font-weight: bold;");
            }
        } else {
            stateOverlay.setVisible(false);
            stateOverlay.setManaged(false);
        }
    }

    public ReferenceDashboardViewModel getViewModel() {
        return viewModel;
    }
}
