package com.pharmacy.pos.shell;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;

import java.util.HashMap;
import java.util.Map;

/**
 * AppShellView - macOS-Inspired Desktop Application Shell View.
 * Architecture Decision: ADR-0007, ADR-0008, ADR-0321.
 * Strict zero-emoji policy; uses verified vector SVG assets and Apple typography.
 */
public class AppShellView extends BorderPane {

    private final ShellViewModel viewModel;
    private final Map<ShellRoute, Button> navButtons = new HashMap<>();
    private final Map<ShellRoute, Node> viewRegistry = new HashMap<>();

    private VBox sidebar;
    private StackPane contentStage;
    private Label errorBanner;
    private Label statusLabel;
    private Label syncStatusPill;
    private Label outboxBadge;
    private Button localeToggleButton;

    public AppShellView(ShellViewModel viewModel) {
        this.viewModel = viewModel;
        initializeLayout();
        bindViewModel();
        setupKeyboardShortcuts();
    }

    private void initializeLayout() {
        this.setStyle("-fx-background-color: #F8F9FA; -fx-font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;");

        // Top Header
        this.setTop(buildHeader());

        // Left Sidebar
        this.sidebar = buildSidebar();
        this.setLeft(this.sidebar);

        // Center Content
        this.contentStage = new StackPane();
        this.contentStage.setStyle("-fx-background-color: #FFFFFF; -fx-padding: 16px;");

        VBox centerWrapper = new VBox();
        this.errorBanner = new Label();
        this.errorBanner.setVisible(false);
        this.errorBanner.setManaged(false);
        this.errorBanner.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #991B1B; -fx-padding: 10px 16px; -fx-font-weight: bold; -fx-border-color: #F87171; -fx-border-width: 0 0 1 0;");
        this.errorBanner.setMaxWidth(Double.MAX_VALUE);

        centerWrapper.getChildren().addAll(this.errorBanner, this.contentStage);
        VBox.setVgrow(this.contentStage, Priority.ALWAYS);
        this.setCenter(centerWrapper);

        // Bottom Status Bar
        this.setBottom(buildStatusBar());
    }

    private HBox buildHeader() {
        HBox header = new HBox(16);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 16, 10, 16));
        header.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #E5E7EB; -fx-border-width: 0 0 1 0;");

        // Brand / App Name
        Label brandLabel = new Label("Bangladesh Pharmacy POS");
        brandLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #111827;");

        // Branch Badge
        Label branchBadge = new Label();
        branchBadge.textProperty().bind(viewModel.branchIdProperty());
        branchBadge.setStyle("-fx-background-color: #EFF6FF; -fx-text-fill: #1D4ED8; -fx-padding: 4px 8px; -fx-background-radius: 4px; -fx-font-size: 12px; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Online / Offline Status Pill
        this.syncStatusPill = new Label();
        updateSyncPill(viewModel.isOnline());
        viewModel.isOnlineProperty().addListener((obs, oldV, newV) -> updateSyncPill(newV));

        // Locale Toggle Button
        this.localeToggleButton = new Button("EN | বাংলা");
        this.localeToggleButton.setStyle("-fx-background-color: #F3F4F6; -fx-text-fill: #374151; -fx-padding: 5px 12px; -fx-background-radius: 6px; -fx-font-size: 12px; -fx-cursor: hand;");
        this.localeToggleButton.setOnAction(e -> viewModel.toggleLocale());

        // Cashier User Badge
        Label userBadge = new Label();
        userBadge.textProperty().bind(viewModel.userIdProperty().concat(" (").concat(viewModel.userRoleProperty()).concat(")"));
        userBadge.setStyle("-fx-background-color: #F3F4F6; -fx-text-fill: #4B5563; -fx-padding: 4px 8px; -fx-background-radius: 4px; -fx-font-size: 12px;");

        header.getChildren().addAll(brandLabel, branchBadge, spacer, this.syncStatusPill, this.localeToggleButton, userBadge);
        return header;
    }

    private void updateSyncPill(boolean isOnline) {
        if (isOnline) {
            this.syncStatusPill.setText(viewModel.isBengali() ? "ক্লাউড অনলাইন" : "Cloud Online");
            this.syncStatusPill.setStyle("-fx-background-color: #DCFCE7; -fx-text-fill: #15803D; -fx-padding: 4px 10px; -fx-background-radius: 12px; -fx-font-size: 12px; -fx-font-weight: bold;");
        } else {
            this.syncStatusPill.setText(viewModel.isBengali() ? "অফলাইন (SQLite WAL)" : "Offline (SQLite WAL)");
            this.syncStatusPill.setStyle("-fx-background-color: #FEF3C7; -fx-text-fill: #B45309; -fx-padding: 4px 10px; -fx-background-radius: 12px; -fx-font-size: 12px; -fx-font-weight: bold;");
        }
    }

    private VBox buildSidebar() {
        VBox bar = new VBox(8);
        bar.setPadding(new Insets(12, 8, 12, 8));
        bar.setPrefWidth(220);
        bar.setStyle("-fx-background-color: #F3F4F6; -fx-border-color: #E5E7EB; -fx-border-width: 0 1 0 0;");

        refreshNavButtons(bar);

        viewModel.userRoleProperty().addListener((obs, oldV, newV) -> refreshNavButtons(bar));
        viewModel.localeProperty().addListener((obs, oldV, newV) -> updateNavButtonLabels());

        return bar;
    }

    private void refreshNavButtons(VBox bar) {
        bar.getChildren().clear();
        navButtons.clear();

        for (ShellRoute route : viewModel.getAccessibleRoutes()) {
            Button btn = createNavButton(route);
            navButtons.put(route, btn);
            bar.getChildren().add(btn);
        }

        updateActiveButtonState(viewModel.getActiveRoute());
    }

    private Button createNavButton(ShellRoute route) {
        Button btn = new Button();
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.setPadding(new Insets(8, 12, 8, 12));
        btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #374151; -fx-font-size: 13px; -fx-cursor: hand; -fx-background-radius: 6px;");

        SVGPath icon = ShellSvgIcons.createNavIcon(route.getIconPath(), false);
        Label label = new Label(route.getLabel(viewModel.getLocale()));
        label.setStyle("-fx-font-size: 13px; -fx-padding: 0 0 0 8px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label shortcutBadge = new Label(route.getShortcutHint());
        shortcutBadge.setStyle("-fx-background-color: #E5E7EB; -fx-text-fill: #6B7280; -fx-font-size: 10px; -fx-padding: 2px 4px; -fx-background-radius: 3px;");

        HBox content = new HBox(4, icon, label, spacer, shortcutBadge);
        content.setAlignment(Pos.CENTER_LEFT);
        btn.setGraphic(content);

        btn.setOnAction(e -> viewModel.navigateTo(route));
        return btn;
    }

    private void updateNavButtonLabels() {
        for (Map.Entry<ShellRoute, Button> entry : navButtons.entrySet()) {
            ShellRoute route = entry.getKey();
            Button btn = entry.getValue();
            if (btn.getGraphic() instanceof HBox) {
                HBox box = (HBox) btn.getGraphic();
                if (box.getChildren().size() >= 2 && box.getChildren().get(1) instanceof Label) {
                    Label lbl = (Label) box.getChildren().get(1);
                    lbl.setText(route.getLabel(viewModel.getLocale()));
                }
            }
        }
        updateSyncPill(viewModel.isOnline());
    }

    private void updateActiveButtonState(ShellRoute activeRoute) {
        for (Map.Entry<ShellRoute, Button> entry : navButtons.entrySet()) {
            ShellRoute route = entry.getKey();
            Button btn = entry.getValue();
            boolean isActive = route == activeRoute;
            if (isActive) {
                btn.setStyle("-fx-background-color: #E0F2FE; -fx-text-fill: #0369A1; -fx-font-size: 13px; -fx-font-weight: bold; -fx-background-radius: 6px;");
            } else {
                btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #374151; -fx-font-size: 13px; -fx-background-radius: 6px;");
            }
        }
    }

    private HBox buildStatusBar() {
        HBox bar = new HBox(16);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 16, 6, 16));
        bar.setStyle("-fx-background-color: #F3F4F6; -fx-border-color: #E5E7EB; -fx-border-width: 1 0 0 0; -fx-font-size: 12px;");

        this.statusLabel = new Label();
        this.statusLabel.textProperty().bind(viewModel.statusMessageProperty());
        this.statusLabel.setStyle("-fx-text-fill: #4B5563;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        this.outboxBadge = new Label("Outbox: 0");
        this.outboxBadge.setStyle("-fx-background-color: #E5E7EB; -fx-text-fill: #374151; -fx-padding: 2px 6px; -fx-background-radius: 4px;");
        viewModel.pendingOutboxCountProperty().addListener((obs, oldV, newV) -> {
            this.outboxBadge.setText("Outbox: " + newV);
        });

        Label dbIndicator = new Label("SQLite WAL Active");
        dbIndicator.setStyle("-fx-text-fill: #16A34A; -fx-font-weight: bold;");

        bar.getChildren().addAll(this.statusLabel, spacer, this.outboxBadge, dbIndicator);
        return bar;
    }

    private void bindViewModel() {
        viewModel.activeRouteProperty().addListener((obs, oldRoute, newRoute) -> {
            updateActiveButtonState(newRoute);
            renderActiveView(newRoute);
        });

        viewModel.errorMessageProperty().addListener((obs, oldMsg, newMsg) -> {
            if (newMsg != null && !newMsg.trim().isEmpty()) {
                errorBanner.setText(newMsg);
                errorBanner.setVisible(true);
                errorBanner.setManaged(true);
            } else {
                errorBanner.setVisible(false);
                errorBanner.setManaged(false);
            }
        });
    }

    public void registerView(ShellRoute route, Node viewNode) {
        viewRegistry.put(route, viewNode);
        if (viewModel.getActiveRoute() == route) {
            renderActiveView(route);
        }
    }

    private void renderActiveView(ShellRoute route) {
        contentStage.getChildren().clear();
        Node view = viewRegistry.get(route);
        if (view != null) {
            contentStage.getChildren().add(view);
        } else {
            // Default placeholder view for route
            VBox placeholder = new VBox(12);
            placeholder.setAlignment(Pos.CENTER);
            Label title = new Label(route.getLabel(viewModel.getLocale()));
            title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #111827;");
            Label subtitle = new Label("Workspace view loaded for " + route.getRouteKey());
            subtitle.setStyle("-fx-font-size: 14px; -fx-text-fill: #6B7280;");
            placeholder.getChildren().addAll(title, subtitle);
            contentStage.getChildren().add(placeholder);
        }
    }

    private void setupKeyboardShortcuts() {
        this.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            KeyCode code = event.getCode();
            switch (code) {
                case F1 -> viewModel.navigateTo(ShellRoute.DASHBOARD);
                case F2 -> viewModel.navigateTo(ShellRoute.POS_SALES);
                case F3 -> viewModel.navigateTo(ShellRoute.INVENTORY_LEDGER);
                case F4 -> viewModel.navigateTo(ShellRoute.PRESCRIPTIONS);
                case F5 -> viewModel.navigateTo(ShellRoute.OFFLINE_SYNC);
                case F9 -> viewModel.navigateTo(ShellRoute.DAILY_SHIFT);
                case F10 -> viewModel.navigateTo(ShellRoute.SETTINGS);
                default -> {}
            }
        });
    }

    public ShellViewModel getViewModel() {
        return viewModel;
    }
}
