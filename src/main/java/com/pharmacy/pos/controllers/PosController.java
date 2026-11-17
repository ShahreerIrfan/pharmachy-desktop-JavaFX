package com.pharmacy.pos.controllers;

import com.pharmacy.pos.MainApp;
import com.pharmacy.pos.models.CartItem;
import com.pharmacy.pos.models.SaleTransaction;
import com.pharmacy.pos.models.TenantSession;
import com.pharmacy.pos.sales.PosFastSearchCartClient;
import com.pharmacy.pos.sales.PosFastSearchCartClient.FastSearchCandidateDto;
import com.pharmacy.pos.sales.PosFastSearchCartClient.FastSearchResponseDto;
import com.pharmacy.pos.sales.PosFastSearchCartClient.PackagingOptionDto;
import com.pharmacy.pos.sales.PosSaleDomainClient;
import com.pharmacy.pos.sales.PosSaleDomainClient.AddressRevealResultDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.ClientOutcome;
import com.pharmacy.pos.sales.PosSaleDomainClient.DeliveryListResultDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.ExpireStaleResultDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.HeldCartEntryDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.HeldCartListResultDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosDeliveryOrderDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosSaleLineDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosSaleOrderDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.ResumeCartResultDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.StalenessDiagnosticsDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.UiState;
import com.pharmacy.pos.services.OfflineSyncEngine;
import com.pharmacy.pos.services.PosSecurityService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for POS Cashier Interface (P222 Keyboard-First Search & Barcode Cart).
 * Architecture Decisions: ADR-0007, ADR-0018, ADR-0023 & ADR-0222.
 *
 * Implements high-density keyboard workflows ([F2], [Enter], [Up/Down], [+/-], [U], [Del], [F9], [F4], [Esc]),
 * prioritized barcode/SKU/EN-BN phonetic lookup backed by PosSkuInventoryReadModel offline fallback,
 * FEFO sellable stock enforcement (excluding expired/quarantined/recalled stock), zero-emoji FontIcons,
 * and strict Decimal monetary precision.
 */
public class PosController {

    private static final class CartLineMetadata {
        FastSearchCandidateDto candidate;
        String backendLineId = "";
        int conversionFactor = 1;
    }

    @FXML private BorderPane rootPane;
    @FXML private Label headerBrandLabel;
    @FXML private Label activeSaleBadge;
    @FXML private Button btnRefreshSnapshot;
    @FXML private Button btnToggleOffline;
    @FXML private Button btnLangToggle;
    @FXML private Label networkStatusLabel;
    @FXML private Label shiftStatusBadge;
    @FXML private Button btnLockCounter;

    @FXML private TextField barcodeInputField;
    @FXML private Button btnAddItem;
    @FXML private Button btnClearSearch;

    @FXML private HBox stateStatusBanner;
    @FXML private Label stateStatusLabel;
    @FXML private Label searchLatencyLabel;
    @FXML private ListView<FastSearchCandidateDto> searchResultsList;

    @FXML private Button btnQtyMinus;
    @FXML private Button btnQtyPlus;
    @FXML private ComboBox<PackagingOptionDto> unitSelectorCombo;
    @FXML private Button btnCycleUnit;
    @FXML private Label selectedLineBaseUnitsLabel;
    @FXML private Button btnRemoveLine;
    @FXML private TextField holdReferenceField;
    @FXML private TextField holdTtlField;
    @FXML private CheckBox reserveHoldStockCheck;
    @FXML private Button btnHoldCart;
    @FXML private Button btnHeldCarts;
    @FXML private Button btnDeliveries;
    @FXML private Label cartSummaryBadge;

    @FXML private HBox staleConfirmationBanner;
    @FXML private Label stalePromptLabel;
    @FXML private Button btnAcknowledgeResume;
    @FXML private Button btnDismissStalePrompt;

    @FXML private VBox heldCartsDrawer;
    @FXML private Label heldDrawerTitleLabel;
    @FXML private Label heldDrawerSummaryLabel;
    @FXML private Button btnRefreshHeldCarts;
    @FXML private Button btnExpireStaleHolds;
    @FXML private Button btnResumeSelectedHeld;
    @FXML private Button btnReleaseSelectedHold;
    @FXML private Button btnCancelSelectedHold;
    @FXML private Button btnCloseHeldDrawer;
    @FXML private ListView<HeldCartEntryDto> heldCartsList;
    @FXML private Label heldCartsEmptyLabel;

    @FXML private VBox deliveryDrawer;
    @FXML private Label deliveryDrawerTitleLabel;
    @FXML private Label deliveryDrawerSummaryLabel;
    @FXML private Button btnRefreshDeliveries;
    @FXML private Button btnCloseDeliveryDrawer;
    @FXML private ComboBox<String> deliveryModeCombo;
    @FXML private TextField deliveryRecipientField;
    @FXML private TextField deliveryPhoneField;
    @FXML private TextField deliveryAddressField;
    @FXML private TextField deliveryAreaField;
    @FXML private TextField deliveryFeeField;
    @FXML private CheckBox reserveDeliveryStockCheck;
    @FXML private Button btnSubmitDeliveryOrder;
    @FXML private ListView<PosDeliveryOrderDto> deliveriesList;
    @FXML private Label deliveriesEmptyLabel;
    @FXML private Label deliveryDetailLabel;
    @FXML private TextField deliveryRiderField;
    @FXML private TextField deliveryPodField;
    @FXML private Button btnPackDelivery;
    @FXML private Button btnDispatchDelivery;
    @FXML private Button btnDeliverOrder;
    @FXML private Button btnRevealDeliveryAddress;
    @FXML private Button btnCancelDelivery;

    @FXML private TableView<CartItem> cartTable;
    @FXML private TableColumn<CartItem, String> brandCol;
    @FXML private TableColumn<CartItem, String> unitCol;
    @FXML private TableColumn<CartItem, Integer> qtyCol;
    @FXML private TableColumn<CartItem, BigDecimal> priceCol;
    @FXML private TableColumn<CartItem, BigDecimal> totalCol;
    @FXML private Label cartEmptyLabel;

    @FXML private Label paymentSummaryTitle;
    @FXML private Label taxModeLabel;
    @FXML private ComboBox<String> discountTypeCombo;
    @FXML private TextField discountValueField;
    @FXML private Button btnApplyPricing;
    @FXML private Label roundingModeTitleLabel;
    @FXML private ComboBox<String> roundingModeCombo;
    @FXML private Label subtotalTitleLabel;
    @FXML private Label discountTitleLabel;
    @FXML private Label vatTitleLabel;
    @FXML private Label roundingTitleLabel;
    @FXML private Label grandTotalTitleLabel;
    @FXML private Label subtotalLabel;
    @FXML private Label discountLabel;
    @FXML private Label vatLabel;
    @FXML private Label roundingLabel;
    @FXML private Label grandTotalLabel;
    @FXML private Label pricingConfirmBadge;
    @FXML private Button btnConfirmPricing;
    @FXML private Label checkoutRxGateHintLabel;
    @FXML private Button checkoutButton;
    @FXML private Button btnRxGate;
    @FXML private VBox rxGateDrawer;
    @FXML private Label rxGateDrawerTitleLabel;
    @FXML private Label rxGateStatusBadge;
    @FXML private Button btnRefreshAntibioticRegister;
    @FXML private Button btnCloseRxDrawer;
    @FXML private TextField rxDoctorNameField;
    @FXML private TextField rxDoctorBmdcField;
    @FXML private TextField rxPatientTokenField;
    @FXML private TextField rxReferenceField;
    @FXML private Button btnAttachPrescription;
    @FXML private TextField rxPharmacistPcbField;
    @FXML private TextField rxDecisionNotesField;
    @FXML private Button btnVerifyRxOrder;
    @FXML private Button btnRejectRxOrder;
    @FXML private Label rxComplianceSummaryLabel;
    @FXML private Button btnFefoLots;
    @FXML private VBox fefoPlanDrawer;
    @FXML private Label fefoPlanDrawerTitleLabel;
    @FXML private Label fefoPlanStatusBadge;
    @FXML private Button btnRefreshFefoPlan;
    @FXML private Button btnCloseFefoDrawer;
    @FXML private TextField fefoOverrideBatchField;
    @FXML private TextField fefoOverrideReasonField;
    @FXML private Button btnReserveFefoLots;
    @FXML private Label fefoPlanSummaryLabel;
    @FXML private Button btnSplitPayments;
    @FXML private VBox splitPaymentDrawer;
    @FXML private Label splitPayDrawerTitleLabel;
    @FXML private Label splitPayStatusBadge;
    @FXML private Button btnRefreshCloseRecon;
    @FXML private Button btnCloseSplitPayDrawer;
    @FXML private ComboBox<String> splitTenderCombo;
    @FXML private TextField splitAmountField;
    @FXML private TextField splitRefField;
    @FXML private TextField splitPayerField;
    @FXML private Button btnAddSplitLeg;
    @FXML private Button btnClearSplitLegs;
    @FXML private Button btnSubmitSplitPayments;
    @FXML private Label splitPaySummaryLabel;
    @FXML private ComboBox<String> splitCustomerCombo;
    @FXML private Button btnRefreshCustomers;
    @FXML private Label splitCustomerCreditBadge;
    @FXML private ComboBox<String> dueRecordCombo;
    @FXML private ComboBox<String> dueCollectTenderCombo;
    @FXML private TextField dueCollectAmountField;
    @FXML private TextField dueCollectRefField;
    @FXML private Button btnRefreshCustomerDues;
    @FXML private Button btnCollectCustomerDue;
    @FXML private TextField closeCountedCashField;
    @FXML private TextField closeVarianceNotesField;
    @FXML private Button btnSubmitCloseRecon;
    @FXML private Label closeReconSummaryLabel;
    @FXML private Label shortcutsTitleLabel;
    @FXML private Label shortcutsLine1Label;
    @FXML private Label shortcutsLine2Label;
    @FXML private Label shortcutsLine3Label;
    @FXML private Label cashierFooterLabel;
    @FXML private Label footerShortcutsLabel;

    private static final class OfflineHeldSnapshot {
        final List<CartItem> items = new ArrayList<>();
        final List<CartLineMetadata> metas = new ArrayList<>();
        String discountType = "NONE";
        BigDecimal discountValue = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        String roundingMode = "NONE";
    }

    private final ObservableList<CartItem> cartItems = FXCollections.observableArrayList();
    private final ObservableList<FastSearchCandidateDto> searchCandidates = FXCollections.observableArrayList();
    private final ObservableList<HeldCartEntryDto> heldCartEntries = FXCollections.observableArrayList();
    private final List<HeldCartEntryDto> offlineHeldCarts = new ArrayList<>();
    private final Map<String, OfflineHeldSnapshot> offlineHeldSnapshots = new java.util.HashMap<>();
    private final ObservableList<PosDeliveryOrderDto> deliveryEntries = FXCollections.observableArrayList();
    private final List<PosDeliveryOrderDto> offlineDeliveryOrders = new ArrayList<>();
    private final Map<CartItem, CartLineMetadata> lineMetadataMap = new IdentityHashMap<>();

    private final PosSecurityService securityService = new PosSecurityService();
    private final OfflineSyncEngine syncEngine = new OfflineSyncEngine();
    private PosFastSearchCartClient searchCartClient = new PosFastSearchCartClient();
    private PosSaleDomainClient saleDomainClient = new PosSaleDomainClient("http://127.0.0.1:8000");

    private TenantSession currentSession = new TenantSession(
            "tenant-demo", "branch-main", "", "cashier-demo", "CASHIER", true
    );
    private PosSaleOrderDto activeServerSale;
    private PosSaleDomainClient.RxGateStatusDto activeRxGateStatus;
    private PosSaleDomainClient.FefoAllocationPlanDto activeFefoPlan;
    private final List<PosSaleDomainClient.SplitPaymentInputDto> stagedSplitPayments = new ArrayList<>();
    private final List<PosSaleDomainClient.PosCustomerDto> cachedCustomers = new ArrayList<>();
    private final List<PosSaleDomainClient.CustomerDueRecordDto> cachedCustomerDues = new ArrayList<>();
    private PosSaleDomainClient.PosCustomerDto activeCustomerDto;
    private PosSaleDomainClient.CashierCloseReconciliationDto lastCloseReconciliation;
    private HeldCartEntryDto pendingStaleResumeEntry;
    private StalenessDiagnosticsDto pendingStaleDiagnostics;
    private int lastKnownServerHeldCount = 0;
    private int lastKnownServerStaleCount = 0;
    private int lastKnownServerDeliveryCount = 0;
    private int lastKnownAbxRegisterCount = 0;
    private String currentStaffName = "Cashier";
    private String currentShiftName = "Active";
    private String currentOpeningFloat = "0.00";
    private boolean isBangla = false;
    private boolean asyncNetworkCalls = true;
    private UiState lastUiState = UiState.EMPTY;
    private String lastStatusEn = "Ready for barcode scan or brand/generic search [F2].";
    private String lastStatusBn = "বারকোড স্ক্যান বা ওষুধের নাম লিখে সার্চ করতে [F2] চাপুন।";
    private boolean updatingUnitCombo = false;
    private boolean updatingPricingCombos = false;
    private String selectedDiscountType = "NONE";
    private BigDecimal requestedDiscountValue = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private String selectedRoundingMode = "NONE";

    @FXML
    public void initialize() {
        applyButtonIcons();
        configureCartTable();
        configureUnitSelectorCombo();
        configurePricingControls();
        configureSearchResultsList();
        configureHeldCartsList();
        configureDeliveriesList();
        configureRxGateControls();
        configureFefoControls();
        configureSplitPaymentControls();
        configureKeyboardWorkflows();
        applyLanguageText();
        updateTotals();
        updateHeldCountBadge();
        updateDeliveryCountBadge();
        updateRxSafetyGateControls();
        updateFefoPlanControls();
        updateSplitPayControls();
        updateNetworkBadge();
        updateStateBanner(UiState.EMPTY, lastStatusEn, lastStatusBn);
    }

    public void setSearchCartClient(PosFastSearchCartClient client) {
        if (client != null) {
            this.searchCartClient = client;
        }
    }

    public void setSaleDomainClient(PosSaleDomainClient client) {
        if (client != null) {
            this.saleDomainClient = client;
        }
    }

    public void setAsyncNetworkCalls(boolean asyncNetworkCalls) {
        this.asyncNetworkCalls = asyncNetworkCalls;
    }

    public OfflineSyncEngine getSyncEngine() {
        return syncEngine;
    }

    public ObservableList<CartItem> getCartItems() {
        return cartItems;
    }

    public ObservableList<FastSearchCandidateDto> getSearchCandidates() {
        return searchCandidates;
    }

    public UiState getLastUiState() {
        return lastUiState;
    }

    public boolean isBangla() {
        return isBangla;
    }

    public PosSaleOrderDto getActiveServerSale() {
        return activeServerSale;
    }

    public ObservableList<HeldCartEntryDto> getHeldCartEntries() {
        return heldCartEntries;
    }

    public List<HeldCartEntryDto> getOfflineHeldCarts() {
        return offlineHeldCarts;
    }

    public StalenessDiagnosticsDto getPendingStaleDiagnostics() {
        return pendingStaleDiagnostics;
    }

    public ObservableList<PosDeliveryOrderDto> getDeliveryEntries() {
        return deliveryEntries;
    }

    public List<PosDeliveryOrderDto> getOfflineDeliveryOrders() {
        return offlineDeliveryOrders;
    }

    public void setSession(TenantSession session) {
        if (session != null) {
            this.currentSession = session;
            this.currentStaffName = session.getUserId();
        }
        applyLanguageText();
    }

    public void setSessionDetails(TenantSession session, String staffName, String shiftName, String openingFloat) {
        if (session != null) {
            this.currentSession = session;
        }
        if (staffName != null && !staffName.isBlank()) {
            this.currentStaffName = staffName;
        }
        if (shiftName != null && !shiftName.isBlank()) {
            this.currentShiftName = shiftName;
        }
        if (openingFloat != null && !openingFloat.isBlank()) {
            this.currentOpeningFloat = openingFloat;
        }
        applyLanguageText();
    }

    private FontIcon createIcon(Feather icon, int size, String hexColor) {
        try {
            FontIcon fi = new FontIcon(icon);
            fi.setIconSize(size);
            if (hexColor != null && !hexColor.isBlank()) {
                fi.setIconColor(Color.web(hexColor));
            }
            return fi;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void applyButtonIcons() {
        setBtnIcon(btnAddItem, Feather.PLUS, 14, "#FFFFFF");
        setBtnIcon(btnClearSearch, Feather.X_CIRCLE, 14, "#475569");
        setBtnIcon(btnRefreshSnapshot, Feather.REFRESH_CW, 13, "#1E293B");
        setBtnIcon(btnToggleOffline, Feather.WIFI, 13, "#1E293B");
        setBtnIcon(btnLangToggle, Feather.GLOBE, 13, "#1E293B");
        setBtnIcon(btnLockCounter, Feather.LOCK, 13, "#1E293B");
        setBtnIcon(btnQtyMinus, Feather.MINUS, 13, "#1E293B");
        setBtnIcon(btnQtyPlus, Feather.PLUS, 13, "#1E293B");
        setBtnIcon(btnCycleUnit, Feather.PACKAGE, 13, "#1E293B");
        setBtnIcon(btnRemoveLine, Feather.TRASH_2, 13, "#B91C1C");
        setBtnIcon(btnHoldCart, Feather.PAUSE_CIRCLE, 13, "#1E293B");
        setBtnIcon(btnHeldCarts, Feather.LIST, 13, "#0284C7");
        setBtnIcon(btnDeliveries, Feather.TRUCK, 13, "#0F766E");
        setBtnIcon(btnRefreshDeliveries, Feather.REFRESH_CW, 12, "#1E293B");
        setBtnIcon(btnCloseDeliveryDrawer, Feather.X_CIRCLE, 12, "#475569");
        setBtnIcon(btnSubmitDeliveryOrder, Feather.CHECK_CIRCLE, 12, "#FFFFFF");
        setBtnIcon(btnPackDelivery, Feather.PACKAGE, 12, "#1E293B");
        setBtnIcon(btnDispatchDelivery, Feather.SEND, 12, "#0284C7");
        setBtnIcon(btnDeliverOrder, Feather.CHECK_SQUARE, 12, "#FFFFFF");
        setBtnIcon(btnRevealDeliveryAddress, Feather.EYE, 12, "#D97706");
        setBtnIcon(btnCancelDelivery, Feather.TRASH_2, 12, "#B91C1C");
        setBtnIcon(btnRefreshHeldCarts, Feather.REFRESH_CW, 12, "#1E293B");
        setBtnIcon(btnExpireStaleHolds, Feather.CLOCK, 12, "#D97706");
        setBtnIcon(btnResumeSelectedHeld, Feather.PLAY_CIRCLE, 12, "#FFFFFF");
        setBtnIcon(btnReleaseSelectedHold, Feather.ROTATE_CCW, 12, "#1E293B");
        setBtnIcon(btnCancelSelectedHold, Feather.TRASH_2, 12, "#B91C1C");
        setBtnIcon(btnCloseHeldDrawer, Feather.X_CIRCLE, 12, "#475569");
        setBtnIcon(btnAcknowledgeResume, Feather.CHECK_CIRCLE, 12, "#FFFFFF");
        setBtnIcon(btnDismissStalePrompt, Feather.X_CIRCLE, 12, "#475569");
        setBtnIcon(btnApplyPricing, Feather.TAG, 12, "#1E293B");
        setBtnIcon(btnConfirmPricing, Feather.CHECK_SQUARE, 13, "#0284C7");
        setBtnIcon(checkoutButton, Feather.SHOPPING_CART, 15, "#FFFFFF");
        if (barcodeInputField != null) {
            barcodeInputField.setAccessibleText("Barcode scanner and fast medicine search");
        }
    }

    private void setBtnIcon(Button btn, Feather icon, int size, String color) {
        if (btn == null) return;
        FontIcon fi = createIcon(icon, size, color);
        if (fi != null) {
            btn.setGraphic(fi);
            btn.setGraphicTextGap(6.0);
        }
    }

    private void configureCartTable() {
        if (cartTable == null) return;
        if (brandCol != null) brandCol.setCellValueFactory(new PropertyValueFactory<>("brandName"));
        if (unitCol != null) unitCol.setCellValueFactory(new PropertyValueFactory<>("packagingUnit"));
        if (qtyCol != null) qtyCol.setCellValueFactory(new PropertyValueFactory<>("quantity"));
        if (priceCol != null) priceCol.setCellValueFactory(new PropertyValueFactory<>("unitPrice"));
        if (totalCol != null) totalCol.setCellValueFactory(new PropertyValueFactory<>("lineTotal"));
        cartTable.setItems(cartItems);
        cartTable.getSelectionModel().selectedIndexProperty().addListener((obs, oldV, newV) ->
                refreshSelectedLineUnitControls()
        );
    }

    private void configureUnitSelectorCombo() {
        if (unitSelectorCombo == null) return;
        unitSelectorCombo.setCellFactory(cb -> new ListCell<>() {
            @Override
            protected void updateItem(PackagingOptionDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                String baseLbl = isBangla ? item.labelBn : item.labelEn;
                String suffix = item.isAllowed
                        ? (" - BDT " + item.unitPriceBdt.toPlainString())
                        : (item.requiresPharmacistApproval
                            ? (isBangla ? " [ফার্মাসিস্ট]" : " [Pharmacist]")
                            : (isBangla ? " [ব্লকড]" : " [Locked]"));
                setText(baseLbl + suffix);
                FontIcon ic = item.isAllowed
                        ? createIcon(Feather.PACKAGE, 12, "#0284C7")
                        : createIcon(Feather.LOCK, 12, "#DC2626");
                setGraphic(ic);
            }
        });
        unitSelectorCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(PackagingOptionDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(isBangla ? "ইউনিট নির্বাচন" : "Select Unit");
                    return;
                }
                String baseLbl = isBangla ? item.labelBn : item.labelEn;
                setText(baseLbl + " (BDT " + item.unitPriceBdt.toPlainString() + ")");
                setGraphic(createIcon(item.isAllowed ? Feather.PACKAGE : Feather.LOCK, 12,
                        item.isAllowed ? "#0284C7" : "#DC2626"));
            }
        });
    }

    private void configureSearchResultsList() {
        if (searchResultsList == null) return;
        searchResultsList.setItems(searchCandidates);
        searchResultsList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(FastSearchCandidateDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                HBox row = new HBox(10.0);
                row.setAlignment(Pos.CENTER_LEFT);

                VBox left = new VBox(2.0);
                String primaryBrand = isBangla && !item.brandNameBn.isBlank()
                        ? item.brandName + " (" + item.brandNameBn + ")"
                        : item.brandName;
                String titleText = (primaryBrand + " " + item.strength).trim();
                Label title = new Label(titleText);
                title.setStyle("-fx-font-weight: 700; -fx-font-size: 13px; -fx-text-fill: #0F172A;");

                String genText = isBangla && !item.genericNameBn.isBlank()
                        ? item.genericName + " / " + item.genericNameBn
                        : item.genericName;
                String batchInfo = item.earliestBatchNumber.isBlank()
                        ? (isBangla ? "কোনো বিক্রয়যোগ্য ব্যাচ নেই" : "No active FEFO batch")
                        : ("FEFO: " + item.earliestBatchNumber + " | Exp: " + item.earliestExpiryDate
                            + " | Avail: " + item.availableBaseUnits + "u");
                Label sub = new Label((genText.isBlank() ? item.skuCode : genText) + " — " + batchInfo);
                sub.setStyle("-fx-font-size: 11px; -fx-text-fill: #475569;");
                left.getChildren().addAll(title, sub);
                HBox.setHgrow(left, Priority.ALWAYS);

                HBox badges = new HBox(6.0);
                badges.setAlignment(Pos.CENTER_RIGHT);

                if (!item.shelfLocation.isBlank()) {
                    Label shelf = new Label(item.shelfLocation);
                    shelf.getStyleClass().add("badge-pill-shelf");
                    badges.getChildren().add(shelf);
                }
                if (item.requiresRxVerification) {
                    Label rx = new Label("Rx");
                    rx.getStyleClass().add("badge-pill-rx");
                    badges.getChildren().add(rx);
                }
                if (!item.isSellable) {
                    Label blocked = new Label(isBangla ? "ব্লকড" : item.blockReason);
                    blocked.getStyleClass().add("badge-pill-blocked");
                    badges.getChildren().add(blocked);
                }
                if ("OFFLINE_SNAPSHOT".equalsIgnoreCase(item.resolutionSource)) {
                    Label offEst = new Label(isBangla ? "অফলাইন ক্যাশ" : "Offline Est.");
                    offEst.getStyleClass().add("badge-pill-rx");
                    badges.getChildren().add(offEst);
                }
                Label price = new Label("BDT " + item.unitPriceBdt.toPlainString() + " / " + item.resolvedPackagingUnit);
                price.setStyle("-fx-font-weight: 700; -fx-font-size: 12px; -fx-text-fill: #0284C7;");
                badges.getChildren().add(price);

                row.getChildren().addAll(left, badges);
                setGraphic(row);
            }
        });

        searchResultsList.setOnMouseClicked(evt -> {
            if (evt.getClickCount() >= 2) {
                FastSearchCandidateDto sel = searchResultsList.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    handleAddCandidate(sel);
                }
            }
        });
    }

    private void configureHeldCartsList() {
        if (heldCartsList == null) return;
        heldCartsList.setItems(heldCartEntries);
        heldCartsList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(HeldCartEntryDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                HBox row = new HBox(10.0);
                row.setAlignment(Pos.CENTER_LEFT);

                VBox left = new VBox(2.0);
                String primaryTitle = item.holdReference.isBlank()
                        ? item.orderNumber
                        : (item.holdReference + (item.orderNumber.isBlank() ? "" : " (" + item.orderNumber + ")"));
                Label title = new Label(primaryTitle);
                title.setStyle("-fx-font-weight: 700; -fx-font-size: 12px; -fx-text-fill: #0F172A;");

                String totalPart = item.staleness != null && item.staleness.hasPriceChange
                        ? ("BDT " + item.heldGrandTotalBdt.toPlainString() + " -> " + item.liveGrandTotalBdt.toPlainString())
                        : ("BDT " + item.heldGrandTotalBdt.toPlainString());
                String expPart = item.holdExpiresAt.isBlank() ? "" : (" | Exp: " + item.holdExpiresAt);
                String subText = item.lineCount + (isBangla ? " লাইন | " : " lines | ") + totalPart + expPart;
                if (item.staleness != null && item.staleness.isStale) {
                    String prompt = isBangla ? item.staleness.userPromptBn : item.staleness.userPromptEn;
                    if (!prompt.isBlank()) {
                        subText = subText + " — " + prompt;
                    }
                }
                Label sub = new Label(subText);
                sub.setStyle("-fx-font-size: 11px; -fx-text-fill: #475569;");
                sub.setWrapText(true);
                left.getChildren().addAll(title, sub);
                HBox.setHgrow(left, Priority.ALWAYS);

                HBox badges = new HBox(6.0);
                badges.setAlignment(Pos.CENTER_RIGHT);

                if (item.offlineLocalOnly) {
                    Label offLbl = new Label(isBangla ? "অফলাইন লোকাল" : "Offline Local");
                    offLbl.getStyleClass().add("badge-pill-rx");
                    badges.getChildren().add(offLbl);
                }
                if (item.holdStockReserved) {
                    Label resLbl = new Label(isBangla ? "স্টক রিজার্ভড" : "Reserved");
                    resLbl.getStyleClass().add("badge-pill-shelf");
                    badges.getChildren().add(resLbl);
                }
                if (item.staleness != null && item.staleness.isStale) {
                    String reasonBadge = item.staleness.staleReasons.isEmpty()
                            ? (isBangla ? "সতর্কতা" : "Stale")
                            : String.join(", ", item.staleness.staleReasons);
                    Label staleLbl = new Label(reasonBadge);
                    staleLbl.getStyleClass().add("badge-pill-blocked");
                    badges.getChildren().add(staleLbl);
                }

                row.getChildren().addAll(left, badges);
                setGraphic(row);
            }
        });
        heldCartsList.setOnMouseClicked(evt -> {
            if (evt.getClickCount() >= 2) {
                HeldCartEntryDto sel = heldCartsList.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    resumeHeldCart(sel, false);
                }
            }
        });
    }

    private void configureKeyboardWorkflows() {
        if (barcodeInputField != null) {
            barcodeInputField.setOnKeyPressed(this::handleSearchFieldKeys);
            barcodeInputField.setOnKeyReleased(evt -> {
                KeyCode code = evt.getCode();
                if (code == KeyCode.ENTER || code == KeyCode.ESCAPE || code == KeyCode.DOWN
                        || code == KeyCode.UP || code == KeyCode.F2 || code == KeyCode.F4
                        || code == KeyCode.F6 || code == KeyCode.F7 || code == KeyCode.F8
                        || code == KeyCode.F9 || code == KeyCode.F10 || code == KeyCode.F11) {
                    return;
                }
                String txt = barcodeInputField.getText() != null ? barcodeInputField.getText().trim() : "";
                if (txt.length() >= 2) {
                    performSearchAndAutoAdd(txt, false);
                } else if (txt.isEmpty()) {
                    searchCandidates.clear();
                    setResultsDrawerVisible(false);
                }
            });
        }
        if (searchResultsList != null) {
            searchResultsList.setOnKeyPressed(evt -> {
                if (evt.getCode() == KeyCode.ENTER) {
                    FastSearchCandidateDto sel = searchResultsList.getSelectionModel().getSelectedItem();
                    if (sel != null) {
                        handleAddCandidate(sel);
                        evt.consume();
                    }
                } else if (evt.getCode() == KeyCode.ESCAPE) {
                    handleClearSearch();
                    evt.consume();
                }
            });
        }
        if (cartTable != null) {
            cartTable.setOnKeyPressed(this::handleCartTableKeys);
        }
        Platform.runLater(() -> {
            if (rootPane != null && rootPane.getScene() != null) {
                rootPane.getScene().addEventFilter(KeyEvent.KEY_PRESSED, this::handleGlobalKeyShortcuts);
            }
        });
    }

    public void handleGlobalKeyShortcuts(KeyEvent event) {
        if (event == null) return;
        KeyCode code = event.getCode();
        if (code == KeyCode.F2) {
            if (barcodeInputField != null) {
                barcodeInputField.requestFocus();
                barcodeInputField.selectAll();
            }
            event.consume();
        } else if (code == KeyCode.F4) {
            handleCheckout();
            event.consume();
        } else if (code == KeyCode.F9) {
            handleHoldCart();
            event.consume();
        } else if (code == KeyCode.F10) {
            handleToggleHeldDrawer();
            event.consume();
        } else if (code == KeyCode.F6) {
            handleToggleSplitPayDrawer();
            event.consume();
        } else if (code == KeyCode.F7) {
            handleToggleFefoPlanDrawer();
            event.consume();
        } else if (code == KeyCode.F8) {
            handleToggleRxGateDrawer();
            event.consume();
        } else if (code == KeyCode.F11) {
            handleToggleDeliveryDrawer();
            event.consume();
        } else if (code == KeyCode.ESCAPE) {
            handleClearSearch();
            event.consume();
        }
    }

    public void handleSearchFieldKeys(KeyEvent event) {
        if (event == null) return;
        if (event.getCode() == KeyCode.DOWN && searchResultsList != null && !searchCandidates.isEmpty()) {
            searchResultsList.requestFocus();
            if (searchResultsList.getSelectionModel().getSelectedIndex() < 0) {
                searchResultsList.getSelectionModel().selectFirst();
            }
            event.consume();
        } else if (event.getCode() == KeyCode.ESCAPE) {
            handleClearSearch();
            event.consume();
        }
    }

    public void handleCartTableKeys(KeyEvent event) {
        if (event == null) return;
        KeyCode code = event.getCode();
        String ch = event.getText();
        if (code == KeyCode.ADD || code == KeyCode.PLUS || "+".equals(ch) || "=".equals(ch)) {
            handleIncrementSelectedLine();
            event.consume();
        } else if (code == KeyCode.SUBTRACT || code == KeyCode.MINUS || "-".equals(ch)) {
            handleDecrementSelectedLine();
            event.consume();
        } else if (code == KeyCode.U) {
            handleCycleSelectedUnit();
            event.consume();
        } else if (code == KeyCode.DELETE || code == KeyCode.BACK_SPACE) {
            handleRemoveSelectedLine();
            event.consume();
        }
    }

    @FXML
    public void handleScanSubmit() {
        String query = barcodeInputField != null && barcodeInputField.getText() != null
                ? barcodeInputField.getText().trim()
                : "";
        if (query.isEmpty()) {
            updateStateBanner(
                    UiState.ERROR,
                    "Search or barcode input cannot be empty.",
                    "অনুসন্ধান বা বারকোড ইনপুট খালি রাখা যাবে না।"
            );
            return;
        }
        performSearchAndAutoAdd(query, true);
    }

    @FXML
    public void handleAddSelectedOrScan() {
        FastSearchCandidateDto selected = searchResultsList != null
                ? searchResultsList.getSelectionModel().getSelectedItem()
                : null;
        if (selected != null) {
            handleAddCandidate(selected);
            return;
        }
        handleScanSubmit();
    }

    public void performSearchAndAutoAdd(String query, boolean autoAddExactMatch) {
        updateStateBanner(UiState.LOADING, UiState.LOADING.defaultMessageEn, UiState.LOADING.defaultMessageBn);
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        boolean offline = !syncEngine.isOnline();

        Runnable work = () -> {
            ClientOutcome<FastSearchResponseDto> outcome;
            if (offline || token == null || token.isBlank()) {
                FastSearchResponseDto offDto = searchCartClient.searchOfflineSnapshot(branchId, query);
                outcome = new ClientOutcome<>(
                        offDto.results.isEmpty() ? UiState.EMPTY : UiState.OFFLINE,
                        offDto,
                        offDto.results.isEmpty() ? "OFFLINE_EMPTY" : "OFFLINE_SNAPSHOT_ESTIMATE",
                        offDto.results.isEmpty()
                                ? "No match in offline branch snapshot cache."
                                : "Offline mode: showing cached SKU snapshot estimates (cloud stock unverified).",
                        offDto.results.isEmpty()
                                ? "অফলাইন স্ন্যাপশট ক্যাশে কোনো মিল পাওয়া যায়নি।"
                                : "অফলাইন মোড: লোকাল স্ন্যাপশট ক্যাশ থেকে সম্ভাব্য তথ্য দেখানো হচ্ছে।"
                );
            } else {
                outcome = searchCartClient.fastSearch(branchId, query, 15, token);
            }

            Runnable uiApply = () -> applySearchOutcome(outcome, query, autoAddExactMatch);
            if (asyncNetworkCalls) {
                Platform.runLater(uiApply);
            } else {
                uiApply.run();
            }
        };

        if (asyncNetworkCalls) {
            CompletableFuture.runAsync(work);
        } else {
            work.run();
        }
    }

    private void applySearchOutcome(ClientOutcome<FastSearchResponseDto> outcome, String query, boolean autoAddExactMatch) {
        searchCandidates.clear();
        if (outcome.data != null) {
            searchCandidates.addAll(outcome.data.results);
            if (searchLatencyLabel != null) {
                searchLatencyLabel.setText(String.format(Locale.ROOT, "%.2f ms", outcome.data.latencyMs));
            }
        }
        boolean showResults = !searchCandidates.isEmpty();
        setResultsDrawerVisible(showResults);

        if (outcome.uiState == UiState.OFFLINE) {
            syncEngine.setNetworkOnline(false);
            updateNetworkBadge();
        }
        updateStateBanner(outcome.uiState, outcome.messageEn, outcome.messageBn);

        if (autoAddExactMatch && !searchCandidates.isEmpty()) {
            FastSearchCandidateDto top = searchCandidates.get(0);
            boolean exactSource = SetOfExactSources.contains(top.resolutionSource)
                    || top.barcode.equalsIgnoreCase(query)
                    || top.skuCode.equalsIgnoreCase(query)
                    || searchCandidates.size() == 1;
            if (exactSource) {
                handleAddCandidate(top);
            } else if (searchResultsList != null) {
                searchResultsList.getSelectionModel().selectFirst();
            }
        }
    }

    private static final java.util.Set<String> SetOfExactSources = java.util.Set.of(
            "BARCODE_ALTERNATE", "PACKAGING_TIER_GTIN", "VARIANT_SKU_EXACT", "PRODUCT_BARCODE_EXACT", "LOCAL_SKU_EXACT"
    );

    public void handleAddCandidate(FastSearchCandidateDto candidate) {
        if (candidate == null) return;
        if (!candidate.isSellable || candidate.availableBaseUnits <= 0) {
            String reason = candidate.blockReason.isBlank() ? "UNSELLABLE_OR_EXPIRED" : candidate.blockReason;
            updateStateBanner(
                    UiState.ERROR,
                    "Blocked: '" + candidate.brandName + "' cannot be sold (" + reason + ").",
                    "ব্লকড: '" + candidate.brandName + "' বিক্রি করা যাবে না (" + reason + ")।"
            );
            return;
        }

        String pkgUnit = candidate.resolvedPackagingUnit;
        int convFactor = Math.max(1, candidate.resolvedConversionFactor);
        if (candidate.nonDivisibleSealedOnly && "TABLET".equalsIgnoreCase(pkgUnit) && convFactor == 1) {
            pkgUnit = "STRIP";
            for (PackagingOptionDto opt : candidate.packagingOptions) {
                if (!"TABLET".equalsIgnoreCase(opt.packagingUnit)) {
                    pkgUnit = opt.packagingUnit;
                    convFactor = Math.max(1, opt.conversionFactor);
                    break;
                }
            }
        }

        int existingProductBaseUnits = computeCartProductBaseUnits(candidate.productId);
        if (existingProductBaseUnits + convFactor > candidate.availableBaseUnits) {
            updateStateBanner(
                    UiState.ERROR,
                    "Insufficient FEFO stock for '" + candidate.brandName + "' (available: " + candidate.availableBaseUnits + "u).",
                    "'" + candidate.brandName + "'-এর পর্যাপ্ত বিক্রয়যোগ্য FEFO স্টক নেই (মজুদ: " + candidate.availableBaseUnits + "টি)।"
            );
            return;
        }

        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        boolean onlineAuth = syncEngine.isOnline() && token != null && !token.isBlank();
        if (onlineAuth) {
            if (activeServerSale == null || activeServerSale.saleId.isBlank()) {
                ClientOutcome<PosSaleOrderDto> cartOutcome = searchCartClient.openQuickCart(
                        currentSession.getBranchId(), "desktop-pos", token
                );
                if (cartOutcome.uiState == UiState.PERMISSION_DENIED) {
                    updateStateBanner(UiState.PERMISSION_DENIED, cartOutcome.messageEn, cartOutcome.messageBn);
                    return;
                }
                if (cartOutcome.data != null) {
                    activeServerSale = cartOutcome.data;
                }
            }
            if (activeServerSale != null && !activeServerSale.saleId.isBlank()
                    && !activeServerSale.saleId.startsWith("offline-cart-")) {
                ClientOutcome<PosSaleOrderDto> addRes = searchCartClient.scanAdd(
                        activeServerSale.saleId,
                        candidate.barcode.isBlank() ? candidate.skuCode : candidate.barcode,
                        candidate.productId,
                        candidate.variantId,
                        pkgUnit,
                        1,
                        activeServerSale.version,
                        token
                );
                if (addRes.uiState == UiState.PERMISSION_DENIED || addRes.uiState == UiState.ERROR) {
                    updateStateBanner(addRes.uiState, addRes.messageEn, addRes.messageBn);
                    return;
                }
                if (addRes.data != null) {
                    syncCartFromServerOrder(addRes.data, candidate);
                    clearInputKeepReady(candidate);
                    return;
                }
            }
        }

        // Local / offline snapshot cart merge
        mergeCandidateLocally(candidate, pkgUnit, convFactor);
        clearInputKeepReady(candidate);
    }

    private void mergeCandidateLocally(FastSearchCandidateDto candidate, String pkgUnit, int convFactor) {
        int existingIdx = -1;
        for (int i = 0; i < cartItems.size(); i++) {
            CartItem row = cartItems.get(i);
            if (row.getMedicineId().equals(candidate.productId)
                    && row.getPackagingUnit().equalsIgnoreCase(pkgUnit)) {
                existingIdx = i;
                break;
            }
        }
        if (existingIdx >= 0) {
            CartItem existing = cartItems.get(existingIdx);
            CartLineMetadata oldMeta = lineMetadataMap.get(existing);
            CartItem updated = new CartItem(
                    existing.getMedicineId(),
                    existing.getBrandName(),
                    existing.getGenericName(),
                    existing.getPackagingUnit(),
                    existing.getQuantity() + 1,
                    existing.getUnitPrice(),
                    existing.getBatchNumber(),
                    existing.getExpiryDate(),
                    existing.getVatRate()
            );
            CartLineMetadata newMeta = oldMeta != null ? oldMeta : new CartLineMetadata();
            newMeta.candidate = candidate;
            newMeta.conversionFactor = convFactor;
            lineMetadataMap.remove(existing);
            lineMetadataMap.put(updated, newMeta);
            cartItems.set(existingIdx, updated);
            if (cartTable != null) cartTable.getSelectionModel().select(existingIdx);
        } else {
            CartItem added = candidate.toLegacyCartItem(1, pkgUnit, BigDecimal.ZERO);
            CartLineMetadata meta = new CartLineMetadata();
            meta.candidate = candidate;
            meta.conversionFactor = convFactor;
            lineMetadataMap.put(added, meta);
            cartItems.add(added);
            if (cartTable != null) cartTable.getSelectionModel().select(added);
        }
        updateTotals();
    }

    private void clearInputKeepReady(FastSearchCandidateDto candidate) {
        if (barcodeInputField != null) {
            barcodeInputField.clear();
            barcodeInputField.requestFocus();
        }
        searchCandidates.clear();
        setResultsDrawerVisible(false);
        String msgEn = "Added " + candidate.brandName + " (" + candidate.resolvedPackagingUnit + ").";
        String msgBn = candidate.brandName + " কার্টে যুক্ত হয়েছে।";
        updateStateBanner(
                syncEngine.isOnline() ? UiState.READY : UiState.OFFLINE,
                msgEn,
                msgBn
        );
    }

    private void syncCartFromServerOrder(PosSaleOrderDto order, FastSearchCandidateDto latestCandidate) {
        this.activeServerSale = order;
        Map<String, FastSearchCandidateDto> knownCandidates = new java.util.HashMap<>();
        for (CartLineMetadata m : lineMetadataMap.values()) {
            if (m.candidate != null) knownCandidates.put(m.candidate.productId, m.candidate);
        }
        if (latestCandidate != null) knownCandidates.put(latestCandidate.productId, latestCandidate);

        cartItems.clear();
        lineMetadataMap.clear();
        for (PosSaleLineDto line : order.lines) {
            BigDecimal vatRate = line.taxRatePct.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
            CartItem item = new CartItem(
                    line.productId,
                    line.productBrandName.isBlank() ? line.productId : line.productBrandName,
                    line.productGenericName.isBlank() ? "Generic" : line.productGenericName,
                    line.packagingUnit,
                    line.quantityUnits,
                    line.unitPriceBdt,
                    line.preferredBatchNumber.isBlank() ? "FEFO-AUTO" : line.preferredBatchNumber,
                    LocalDate.now().plusYears(1),
                    vatRate
            );
            CartLineMetadata meta = new CartLineMetadata();
            meta.backendLineId = line.lineId;
            meta.conversionFactor = Math.max(1, line.conversionFactor);
            meta.candidate = knownCandidates.get(line.productId);
            lineMetadataMap.put(item, meta);
            cartItems.add(item);
        }
        if (activeSaleBadge != null) {
            activeSaleBadge.setText((order.orderNumber.isBlank() ? "Quick Cart" : order.orderNumber) + " | v" + order.version);
        }
        syncPricingControlsFromOrder(order);
        updateTotals();
    }

    private void configurePricingControls() {
        if (discountTypeCombo != null) {
            updatingPricingCombos = true;
            try {
                discountTypeCombo.getItems().setAll("NONE", "PERCENTAGE", "FLAT_BDT");
                discountTypeCombo.getSelectionModel().select(selectedDiscountType != null ? selectedDiscountType : "NONE");
                discountTypeCombo.setCellFactory(cb -> new ListCell<>() {
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : formatDiscountTypeLabel(item));
                    }
                });
                discountTypeCombo.setButtonCell(new ListCell<>() {
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? formatDiscountTypeLabel("NONE") : formatDiscountTypeLabel(item));
                    }
                });
            } finally {
                updatingPricingCombos = false;
            }
        }
        if (roundingModeCombo != null) {
            updatingPricingCombos = true;
            try {
                roundingModeCombo.getItems().setAll("NONE", "HALF_UP_WHOLE_BDT", "FLOOR_WHOLE_BDT", "NEAREST_FIVE_BDT");
                roundingModeCombo.getSelectionModel().select(selectedRoundingMode != null ? selectedRoundingMode : "NONE");
                roundingModeCombo.setCellFactory(cb -> new ListCell<>() {
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : formatRoundingModeLabel(item));
                    }
                });
                roundingModeCombo.setButtonCell(new ListCell<>() {
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? formatRoundingModeLabel("NONE") : formatRoundingModeLabel(item));
                    }
                });
            } finally {
                updatingPricingCombos = false;
            }
        }
    }

    private String formatDiscountTypeLabel(String code) {
        return switch (code) {
            case "PERCENTAGE" -> isBangla ? "শতাংশ (%)" : "Percent (%)";
            case "FLAT_BDT" -> isBangla ? "নির্দিষ্ট (BDT)" : "Flat BDT";
            default -> isBangla ? "ছাড় নেই" : "No Disc.";
        };
    }

    private String formatRoundingModeLabel(String code) {
        return switch (code) {
            case "HALF_UP_WHOLE_BDT" -> isBangla ? "নিকটতম ১ টাকা" : "Nearest 1 BDT";
            case "FLOOR_WHOLE_BDT" -> isBangla ? "নিম্ন পূর্ণ টাকা" : "Floor 1 BDT";
            case "NEAREST_FIVE_BDT" -> isBangla ? "নিকটতম ৫ টাকা" : "Nearest 5 BDT";
            default -> isBangla ? "রাউন্ডিং নেই" : "Exact Paisa (NONE)";
        };
    }

    private void syncPricingControlsFromOrder(PosSaleOrderDto order) {
        if (order == null) return;
        this.selectedDiscountType = order.discountType != null ? order.discountType : "NONE";
        this.selectedRoundingMode = order.roundingMode != null ? order.roundingMode : "NONE";
        if ("PERCENTAGE".equalsIgnoreCase(this.selectedDiscountType)) {
            this.requestedDiscountValue = order.discountRatePct;
        } else {
            this.requestedDiscountValue = order.headerDiscountAmountBdt;
        }
        updatingPricingCombos = true;
        try {
            if (discountTypeCombo != null) {
                if (discountTypeCombo.getItems().contains(this.selectedDiscountType)) {
                    discountTypeCombo.getSelectionModel().select(this.selectedDiscountType);
                }
            }
            if (roundingModeCombo != null) {
                if (roundingModeCombo.getItems().contains(this.selectedRoundingMode)) {
                    roundingModeCombo.getSelectionModel().select(this.selectedRoundingMode);
                }
            }
            if (discountValueField != null) {
                discountValueField.setText(this.requestedDiscountValue.compareTo(BigDecimal.ZERO) > 0
                        ? this.requestedDiscountValue.toPlainString()
                        : "");
            }
        } finally {
            updatingPricingCombos = false;
        }
    }

    @FXML
    public void handleApplyPricing() {
        if (updatingPricingCombos) return;
        String dType = discountTypeCombo != null && discountTypeCombo.getValue() != null
                ? discountTypeCombo.getValue()
                : selectedDiscountType;
        String rMode = roundingModeCombo != null && roundingModeCombo.getValue() != null
                ? roundingModeCombo.getValue()
                : selectedRoundingMode;
        String rawVal = discountValueField != null && discountValueField.getText() != null
                ? discountValueField.getText().trim()
                : "";
        BigDecimal dVal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (!rawVal.isEmpty()) {
            try {
                dVal = new BigDecimal(rawVal).setScale(2, RoundingMode.HALF_UP);
            } catch (NumberFormatException ex) {
                updateStateBanner(
                        UiState.ERROR,
                        "Invalid discount number format: '" + rawVal + "'.",
                        "ভুল ডিসকাউন্ট সংখ্যা: '" + rawVal + "'।"
                );
                return;
            }
        }
        if (dVal.compareTo(BigDecimal.ZERO) < 0) {
            updateStateBanner(
                    UiState.ERROR,
                    "Discount value cannot be negative.",
                    "ছাড়ের পরিমাণ শূন্যের কম হতে পারবে না।"
            );
            return;
        }
        applyPricingParameters(dType, dVal, rMode);
    }

    public void applyPricingParameters(String discountType, BigDecimal discountValue, String roundingMode) {
        if (cartItems.isEmpty()) {
            updateStateBanner(
                    UiState.EMPTY,
                    "Add at least one medicine to cart before adjusting pricing.",
                    "মূল্য বা ছাড় সমন্বয়ের আগে কার্টে অন্তত একটি ওষুধ যোগ করুন।"
            );
            return;
        }
        String normDiscType = discountType != null ? discountType.trim().toUpperCase(Locale.ROOT) : "NONE";
        String normRoundMode = roundingMode != null ? roundingMode.trim().toUpperCase(Locale.ROOT) : "NONE";
        BigDecimal safeVal = discountValue != null ? discountValue.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";

        if (activeServerSale != null && !activeServerSale.saleId.isBlank()
                && syncEngine.isOnline() && token != null && !token.isBlank()) {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("discount_type", normDiscType);
            if ("PERCENTAGE".equals(normDiscType)) {
                payload.put("discount_rate_pct", safeVal.toPlainString());
            } else if ("FLAT_BDT".equals(normDiscType) || "PROMO_POLICY".equals(normDiscType)) {
                payload.put("header_discount_amount_bdt", safeVal.toPlainString());
            } else {
                payload.put("header_discount_amount_bdt", "0.00");
                payload.put("discount_rate_pct", "0.00");
            }
            payload.put("rounding_mode", normRoundMode);
            payload.put("expected_version", activeServerSale.version);

            ClientOutcome<PosSaleOrderDto> outcome = saleDomainClient.updateCartPricing(
                    token, role, tenantId, branchId, tenantId, branchId,
                    activeServerSale.saleId, payload, false, false
            );
            if (outcome.uiState == UiState.READY && outcome.data != null) {
                syncCartFromServerOrder(outcome.data, null);
                updateStateBanner(UiState.READY, outcome.messageEn, outcome.messageBn);
            } else {
                updateStateBanner(outcome.uiState, outcome.messageEn, outcome.messageBn);
            }
            return;
        }

        // Offline / local preview calculation with Cashier <= 5% concession guard
        BigDecimal localSubtotal = BigDecimal.ZERO;
        for (CartItem item : cartItems) {
            localSubtotal = localSubtotal.add(item.getLineSubtotal());
        }
        localSubtotal = localSubtotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal previewDisc = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if ("PERCENTAGE".equals(normDiscType)) {
            if (safeVal.compareTo(new BigDecimal("100.00")) > 0) {
                updateStateBanner(UiState.ERROR, "Percentage discount cannot exceed 100%.", "শতাংশ ছাড় ১০০%-এর বেশি হতে পারবে না।");
                return;
            }
            previewDisc = localSubtotal.multiply(safeVal).divide(new BigDecimal("100.00"), 2, RoundingMode.HALF_UP);
        } else if ("FLAT_BDT".equals(normDiscType) || "PROMO_POLICY".equals(normDiscType)) {
            if (safeVal.compareTo(localSubtotal) > 0) {
                updateStateBanner(UiState.ERROR, "Discount exceeds subtotal.", "ছাড় উপ-মোটের চেয়ে বেশি হতে পারবে না।");
                return;
            }
            previewDisc = safeVal;
        }
        if ("CASHIER".equalsIgnoreCase(role) && localSubtotal.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal maxAllowed = localSubtotal.multiply(new BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP);
            if (previewDisc.compareTo(maxAllowed) > 0) {
                updateStateBanner(
                        UiState.PERMISSION_DENIED,
                        "Cashiers cannot exceed 5% combined discount without Branch Manager approval.",
                        "ক্যাশিয়ার ম্যানেজারের অনুমোদন ছাড়া ৫%-এর বেশি ছাড় দিতে পারবেন না।"
                );
                return;
            }
        }

        this.selectedDiscountType = normDiscType;
        this.requestedDiscountValue = safeVal;
        this.selectedRoundingMode = normRoundMode;
        updateTotals();
        if (!syncEngine.isOnline()) {
            updateStateBanner(
                    UiState.OFFLINE,
                    "Offline preview totals updated (server confirmation required when online).",
                    "অফলাইন প্রাক্কলিত মূল্য হালনাগাদ হয়েছে (অনলাইনে গেলে সার্ভার নিশ্চিতকরণ আবশ্যক)।"
            );
        } else {
            updateStateBanner(
                    UiState.READY,
                    "Local cart totals recalculated with Decimal rounding.",
                    "কার্টের মোট মূল্য সঠিকভাবে পুনর্গণনা করা হয়েছে।"
            );
        }
    }

    @FXML
    public void handleConfirmPricing() {
        if (cartItems.isEmpty()) {
            updateStateBanner(
                    UiState.EMPTY,
                    "Cannot confirm pricing for an empty cart.",
                    "খালি কার্টের মূল্য নিশ্চিত করা যাবে না।"
            );
            return;
        }
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";

        if (!syncEngine.isOnline() || activeServerSale == null || activeServerSale.saleId.isBlank() || token == null || token.isBlank()) {
            updateStateBanner(
                    UiState.OFFLINE,
                    "Offline mode: pricing cannot receive server confirmation signature while disconnected.",
                    "অফলাইন মোড: সার্ভারের সাথে সংযুক্ত না থাকায় মূল্য নিশ্চিতকরণ স্বাক্ষর নেওয়া সম্ভব নয়।"
            );
            return;
        }

        ClientOutcome<PosSaleOrderDto> outcome = saleDomainClient.confirmCartPricing(
                token, role, tenantId, branchId, tenantId, branchId,
                activeServerSale.saleId,
                activeServerSale.grandTotalBdt,
                activeServerSale.currentPricingHash,
                activeServerSale.version,
                false,
                false
        );
        if (outcome.uiState == UiState.READY && outcome.data != null) {
            this.activeServerSale = outcome.data;
            updateTotals();
            updateStateBanner(UiState.READY, outcome.messageEn, outcome.messageBn);
        } else {
            updateStateBanner(outcome.uiState, outcome.messageEn, outcome.messageBn);
        }
    }

    private BigDecimal computeLocalRoundingAdjustment(BigDecimal preRound, String mode) {
        if (preRound == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return switch (mode != null ? mode : "NONE") {
            case "HALF_UP_WHOLE_BDT" -> preRound.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP).subtract(preRound);
            case "FLOOR_WHOLE_BDT" -> preRound.setScale(0, RoundingMode.FLOOR).setScale(2, RoundingMode.HALF_UP).subtract(preRound);
            case "NEAREST_FIVE_BDT" -> {
                BigDecimal five = new BigDecimal("5.00");
                BigDecimal units = preRound.divide(five, 0, RoundingMode.HALF_UP);
                yield units.multiply(five).setScale(2, RoundingMode.HALF_UP).subtract(preRound);
            }
            default -> BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        };
    }

    @FXML
    public void handleIncrementSelectedLine() {
        mutateSelectedCartQuantity(1);
    }

    @FXML
    public void handleDecrementSelectedLine() {
        mutateSelectedCartQuantity(-1);
    }

    private void mutateSelectedCartQuantity(int delta) {
        int idx = getSelectedCartIndex();
        if (idx < 0) return;
        CartItem current = cartItems.get(idx);
        CartLineMetadata meta = lineMetadataMap.get(current);
        int convFactor = meta != null ? meta.conversionFactor : 1;
        int nextQty = current.getQuantity() + delta;

        if (delta > 0 && meta != null && meta.candidate != null) {
            int currentBase = computeCartProductBaseUnits(current.getMedicineId());
            if (currentBase + (delta * convFactor) > meta.candidate.availableBaseUnits) {
                updateStateBanner(
                        UiState.ERROR,
                        "Cannot exceed available FEFO stock (" + meta.candidate.availableBaseUnits + "u).",
                        "উপলব্ধ FEFO স্টকের (" + meta.candidate.availableBaseUnits + "টি) বেশি বিক্রি করা যাবে না।"
                );
                return;
            }
        }

        if (activeServerSale != null && meta != null && !meta.backendLineId.isBlank()
                && syncEngine.isOnline() && currentSession != null && !currentSession.getDeviceToken().isBlank()) {
            ClientOutcome<PosSaleOrderDto> res = searchCartClient.mutateCartLine(
                    activeServerSale.saleId,
                    meta.backendLineId,
                    delta,
                    null,
                    null,
                    nextQty <= 0,
                    activeServerSale.version,
                    currentSession.getDeviceToken()
            );
            if (res.uiState == UiState.ERROR || res.uiState == UiState.PERMISSION_DENIED) {
                updateStateBanner(res.uiState, res.messageEn, res.messageBn);
                return;
            }
            if (res.data != null) {
                syncCartFromServerOrder(res.data, meta.candidate);
                if (!cartItems.isEmpty() && cartTable != null) {
                    cartTable.getSelectionModel().select(Math.min(idx, cartItems.size() - 1));
                }
                updateStateBanner(res.uiState, res.messageEn, res.messageBn);
                return;
            }
        }

        if (nextQty <= 0) {
            lineMetadataMap.remove(current);
            cartItems.remove(idx);
        } else {
            CartItem updated = new CartItem(
                    current.getMedicineId(),
                    current.getBrandName(),
                    current.getGenericName(),
                    current.getPackagingUnit(),
                    nextQty,
                    current.getUnitPrice(),
                    current.getBatchNumber(),
                    current.getExpiryDate(),
                    current.getVatRate()
            );
            lineMetadataMap.remove(current);
            if (meta != null) lineMetadataMap.put(updated, meta);
            cartItems.set(idx, updated);
            if (cartTable != null) cartTable.getSelectionModel().select(idx);
        }
        updateTotals();
    }

    private List<PackagingOptionDto> buildOptionsForSelectedLine(CartItem current, CartLineMetadata meta) {
        FastSearchCandidateDto cand = meta != null ? meta.candidate : null;
        if (cand != null && !cand.packagingOptions.isEmpty()) {
            return new ArrayList<>(cand.packagingOptions);
        }
        List<PackagingOptionDto> opts = new ArrayList<>();
        PackagingOptionDto t = new PackagingOptionDto();
        t.packagingUnit = "TABLET";
        t.labelEn = "Tablet / Unit";
        t.labelBn = "ট্যাবলেট / পিস";
        t.conversionFactor = 1;
        t.unitPriceBdt = current.getUnitPrice();
        PackagingOptionDto s = new PackagingOptionDto();
        s.packagingUnit = "STRIP";
        s.labelEn = "Strip (10u)";
        s.labelBn = "স্ট্রিপ (১০টি)";
        s.conversionFactor = 10;
        s.unitPriceBdt = current.getUnitPrice().multiply(BigDecimal.TEN).setScale(2, RoundingMode.HALF_UP);
        opts.add(t);
        opts.add(s);
        return opts;
    }

    private void refreshSelectedLineUnitControls() {
        int idx = getSelectedCartIndex();
        if (idx < 0 || idx >= cartItems.size()) {
            if (selectedLineBaseUnitsLabel != null) {
                selectedLineBaseUnitsLabel.setText(isBangla ? "বেস: ০ ইউনিট" : "Base: 0u");
            }
            if (unitSelectorCombo != null) {
                updatingUnitCombo = true;
                try {
                    unitSelectorCombo.getItems().clear();
                    unitSelectorCombo.setDisable(true);
                } finally {
                    updatingUnitCombo = false;
                }
            }
            return;
        }
        CartItem current = cartItems.get(idx);
        CartLineMetadata meta = lineMetadataMap.get(current);
        int factor = meta != null ? Math.max(1, meta.conversionFactor) : 1;
        int baseUnits = current.getQuantity() * factor;
        if (selectedLineBaseUnitsLabel != null) {
            selectedLineBaseUnitsLabel.setText(isBangla
                    ? ("বেস: " + current.getQuantity() + " " + current.getPackagingUnit() + " x " + factor + " = " + baseUnits + "টি")
                    : ("Base: " + current.getQuantity() + " " + current.getPackagingUnit() + " x " + factor + " = " + baseUnits + "u"));
        }
        if (unitSelectorCombo != null) {
            List<PackagingOptionDto> opts = buildOptionsForSelectedLine(current, meta);
            updatingUnitCombo = true;
            try {
                unitSelectorCombo.setDisable(false);
                unitSelectorCombo.getItems().setAll(opts);
                for (PackagingOptionDto opt : opts) {
                    if (opt.packagingUnit.equalsIgnoreCase(current.getPackagingUnit())) {
                        unitSelectorCombo.getSelectionModel().select(opt);
                        break;
                    }
                }
            } finally {
                updatingUnitCombo = false;
            }
        }
    }

    @FXML
    public void handleSelectUnitFromCombo() {
        if (updatingUnitCombo || unitSelectorCombo == null) return;
        int idx = getSelectedCartIndex();
        if (idx < 0) return;
        PackagingOptionDto selected = unitSelectorCombo.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        CartItem current = cartItems.get(idx);
        if (selected.packagingUnit.equalsIgnoreCase(current.getPackagingUnit())) return;
        applyUnitSelectionToSelectedLine(idx, selected, "KEEP_QUANTITY", "");
    }

    @FXML
    public void handleCycleSelectedUnit() {
        int idx = getSelectedCartIndex();
        if (idx < 0) return;
        CartItem current = cartItems.get(idx);
        CartLineMetadata meta = lineMetadataMap.get(current);
        List<PackagingOptionDto> opts = buildOptionsForSelectedLine(current, meta);
        if (opts.isEmpty()) return;

        int curOptIdx = -1;
        for (int i = 0; i < opts.size(); i++) {
            if (opts.get(i).packagingUnit.equalsIgnoreCase(current.getPackagingUnit())) {
                curOptIdx = i;
                break;
            }
        }

        // Prefer next allowed packaging tier; fall back to next immediate tier to surface explicit lock feedback
        PackagingOptionDto nextOpt = null;
        for (int step = 1; step <= opts.size(); step++) {
            PackagingOptionDto candidateOpt = opts.get((curOptIdx + step) % opts.size());
            if (!candidateOpt.packagingUnit.equalsIgnoreCase(current.getPackagingUnit()) && candidateOpt.isAllowed) {
                nextOpt = candidateOpt;
                break;
            }
        }
        if (nextOpt == null) {
            nextOpt = opts.get((curOptIdx + 1) % opts.size());
        }
        applyUnitSelectionToSelectedLine(idx, nextOpt, "KEEP_QUANTITY", "");
    }

    public void applyUnitSelectionToSelectedLine(
            int idx,
            PackagingOptionDto nextOpt,
            String conversionMode,
            String overrideReason
    ) {
        if (idx < 0 || idx >= cartItems.size() || nextOpt == null) return;
        CartItem current = cartItems.get(idx);
        CartLineMetadata meta = lineMetadataMap.get(current);
        FastSearchCandidateDto cand = meta != null ? meta.candidate : null;

        if (!nextOpt.isAllowed) {
            UiState st = nextOpt.requiresPharmacistApproval ? UiState.PERMISSION_DENIED : UiState.ERROR;
            String msgEn = !nextOpt.blockReasonEn.isBlank()
                    ? nextOpt.blockReasonEn
                    : ("Packaging unit '" + nextOpt.packagingUnit + "' is restricted (" + nextOpt.blockErrorCode + ").");
            String msgBn = !nextOpt.blockReasonBn.isBlank()
                    ? nextOpt.blockReasonBn
                    : ("'" + nextOpt.packagingUnit + "' ইউনিট নির্বাচন অনুমোদিত নয়।");
            updateStateBanner(st, msgEn, msgBn);
            refreshSelectedLineUnitControls();
            return;
        }

        if (cand != null && cand.nonDivisibleSealedOnly && "TABLET".equalsIgnoreCase(nextOpt.packagingUnit)) {
            updateStateBanner(
                    UiState.ERROR,
                    "Non-divisible sealed pack policy blocks loose tablet sales.",
                    "সিলড প্যাক নীতির কারণে খোলা ট্যাবলেট বিক্রি নিষিদ্ধ।"
            );
            refreshSelectedLineUnitControls();
            return;
        }

        boolean preserveBase = "CONVERT_PRESERVE_BASE".equalsIgnoreCase(conversionMode);
        int curFactor = meta != null ? Math.max(1, meta.conversionFactor) : 1;
        int curBaseTotal = current.getQuantity() * curFactor;
        int nextFactor = Math.max(1, nextOpt.conversionFactor);
        int nextQty = current.getQuantity();
        if (preserveBase) {
            if (curBaseTotal % nextFactor != 0) {
                updateStateBanner(
                        UiState.ERROR,
                        "Cannot convert " + curBaseTotal + " base units evenly into '" + nextOpt.packagingUnit + "' (x" + nextFactor + ").",
                        curBaseTotal + "টি বেস ইউনিটকে পূর্ণ '" + nextOpt.packagingUnit + "'-এ রূপান্তর করা যাচ্ছে না।"
                );
                refreshSelectedLineUnitControls();
                return;
            }
            nextQty = curBaseTotal / nextFactor;
        }

        int otherBaseUnits = computeCartProductBaseUnits(current.getMedicineId()) - curBaseTotal;
        if (cand != null && (otherBaseUnits + (nextQty * nextFactor)) > cand.availableBaseUnits) {
            updateStateBanner(
                    UiState.ERROR,
                    "Insufficient FEFO stock for unit '" + nextOpt.packagingUnit + "'.",
                    "'" + nextOpt.packagingUnit + "' ইউনিটের জন্য পর্যাপ্ত FEFO স্টক নেই।"
            );
            refreshSelectedLineUnitControls();
            return;
        }

        if (activeServerSale != null && meta != null && !meta.backendLineId.isBlank()
                && syncEngine.isOnline() && currentSession != null && !currentSession.getDeviceToken().isBlank()) {
            ClientOutcome<PosSaleOrderDto> res = searchCartClient.selectCartLineUnit(
                    activeServerSale.saleId,
                    meta.backendLineId,
                    nextOpt.packagingUnit,
                    preserveBase ? null : current.getQuantity(),
                    nextOpt.conversionFactor,
                    conversionMode,
                    activeServerSale.version,
                    overrideReason,
                    currentSession.getDeviceToken()
            );
            if (res.uiState == UiState.ERROR || res.uiState == UiState.PERMISSION_DENIED) {
                updateStateBanner(res.uiState, res.messageEn, res.messageBn);
                refreshSelectedLineUnitControls();
                return;
            }
            if (res.data != null) {
                syncCartFromServerOrder(res.data, cand);
                if (!cartItems.isEmpty() && cartTable != null) {
                    cartTable.getSelectionModel().select(Math.min(idx, cartItems.size() - 1));
                }
                refreshSelectedLineUnitControls();
                updateStateBanner(
                        UiState.READY,
                        "Selected unit " + nextOpt.packagingUnit + " (" + nextFactor + "u = " + (nextQty * nextFactor) + " base).",
                        "ইউনিট পরিবর্তন করে " + nextOpt.packagingUnit + " করা হয়েছে।"
                );
                return;
            }
        }

        CartItem updated = new CartItem(
                current.getMedicineId(),
                current.getBrandName(),
                current.getGenericName(),
                nextOpt.packagingUnit,
                nextQty,
                nextOpt.unitPriceBdt,
                current.getBatchNumber(),
                current.getExpiryDate(),
                current.getVatRate()
        );
        lineMetadataMap.remove(current);
        CartLineMetadata nextMeta = meta != null ? meta : new CartLineMetadata();
        nextMeta.conversionFactor = nextFactor;
        lineMetadataMap.put(updated, nextMeta);
        cartItems.set(idx, updated);
        if (cartTable != null) cartTable.getSelectionModel().select(idx);
        updateTotals();
        refreshSelectedLineUnitControls();
        updateStateBanner(
                UiState.READY,
                "Switched unit to " + nextOpt.packagingUnit + " (" + nextFactor + "u).",
                "ইউনিট পরিবর্তন করে " + nextOpt.packagingUnit + " করা হয়েছে।"
        );
    }

    @FXML
    public void handleRemoveSelectedLine() {
        int idx = getSelectedCartIndex();
        if (idx < 0) return;
        CartItem current = cartItems.get(idx);
        CartLineMetadata meta = lineMetadataMap.get(current);

        if (activeServerSale != null && meta != null && !meta.backendLineId.isBlank()
                && syncEngine.isOnline() && currentSession != null && !currentSession.getDeviceToken().isBlank()) {
            ClientOutcome<PosSaleOrderDto> res = searchCartClient.mutateCartLine(
                    activeServerSale.saleId,
                    meta.backendLineId,
                    null,
                    null,
                    null,
                    true,
                    activeServerSale.version,
                    currentSession.getDeviceToken()
            );
            if (res.data != null) {
                syncCartFromServerOrder(res.data, null);
                updateStateBanner(res.uiState, res.messageEn, res.messageBn);
                return;
            }
        }

        lineMetadataMap.remove(current);
        cartItems.remove(idx);
        updateTotals();
        if (cartItems.isEmpty()) {
            updateStateBanner(UiState.EMPTY, "Cart is empty.", "কার্ট খালি আছে।");
        }
    }

    @FXML
    public void handleHoldCart() {
        if (cartItems.isEmpty()) {
            updateStateBanner(UiState.EMPTY, "Cannot hold an empty cart.", "খালি কার্ট হোল্ড করা যাবে না।");
            return;
        }
        String holdRef = holdReferenceField != null && holdReferenceField.getText() != null
                ? holdReferenceField.getText().trim()
                : "";
        Integer holdTtlMinutes = null;
        if (holdTtlField != null && holdTtlField.getText() != null && !holdTtlField.getText().trim().isEmpty()) {
            String rawTtl = holdTtlField.getText().trim();
            try {
                int parsed = Integer.parseInt(rawTtl);
                if (parsed < 1 || parsed > 1440) {
                    updateStateBanner(
                            UiState.ERROR,
                            "Hold TTL minutes must be between 1 and 1440.",
                            "হোল্ডের মেয়াদ ১ থেকে ১৪৪০ মিনিটের মধ্যে হতে হবে।"
                    );
                    return;
                }
                holdTtlMinutes = parsed;
            } catch (NumberFormatException ex) {
                updateStateBanner(
                        UiState.ERROR,
                        "Hold TTL must be a whole number of minutes (1..1440).",
                        "হোল্ডের মেয়াদ ১ থেকে ১৪৪০-এর মধ্যে পূর্ণসংখ্যায় দিন।"
                );
                return;
            }
        }
        boolean reserveStock = reserveHoldStockCheck != null && reserveHoldStockCheck.isSelected();
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";

        boolean canUseServer = activeServerSale != null
                && !activeServerSale.saleId.isBlank()
                && !activeServerSale.saleId.startsWith("offline-cart-")
                && syncEngine.isOnline()
                && token != null
                && !token.isBlank();

        if (canUseServer) {
            ClientOutcome<PosSaleOrderDto> holdRes = saleDomainClient.holdCart(
                    token,
                    role,
                    tenantId,
                    branchId,
                    tenantId,
                    branchId,
                    activeServerSale.saleId,
                    holdRef,
                    "",
                    holdTtlMinutes,
                    reserveStock,
                    null,
                    activeServerSale.version,
                    false,
                    false
            );
            if (holdRes.uiState != UiState.READY) {
                updateStateBanner(holdRes.uiState, holdRes.messageEn, holdRes.messageBn);
                return;
            }
            if (holdRes.data != null) {
                HeldCartEntryDto parkedEntry = buildHeldEntryFromServerOrder(holdRes.data);
                upsertServerHeldEntry(parkedEntry);
            }
            clearActiveCounterForNextCustomer();
            updateHeldCountBadge();
            updateStateBanner(
                    UiState.READY,
                    holdRes.messageEn.isBlank() ? "Cart placed on hold. Ready for next customer." : holdRes.messageEn,
                    holdRes.messageBn.isBlank() ? "কার্ট হোল্ড করা হয়েছে। পরবর্তী গ্রাহকের জন্য প্রস্তুত।" : holdRes.messageBn
            );
            return;
        }

        // Offline / local fallback hold (honestly marked local-only, stock NOT cloud-reserved)
        String localId = "offline-hold-" + UUID.randomUUID().toString().substring(0, 8);
        String resolvedRef = holdRef.isBlank() ? ("OFFLINE-HOLD-" + (offlineHeldCarts.size() + 1)) : holdRef;
        BigDecimal localGrand = computeCurrentPreviewGrandTotal();
        HeldCartEntryDto offlineEntry = new HeldCartEntryDto();
        offlineEntry.saleId = localId;
        offlineEntry.orderNumber = "LOCAL-" + (offlineHeldCarts.size() + 1);
        offlineEntry.branchId = branchId;
        offlineEntry.holdReference = resolvedRef;
        offlineEntry.heldAt = Instant.now().toString();
        if (holdTtlMinutes != null) {
            offlineEntry.holdExpiresAt = Instant.now().plusSeconds(holdTtlMinutes * 60L).toString();
        }
        offlineEntry.holdStockReserved = false;
        offlineEntry.lineCount = cartItems.size();
        offlineEntry.heldGrandTotalBdt = localGrand;
        offlineEntry.liveGrandTotalBdt = localGrand;
        offlineEntry.offlineLocalOnly = true;

        OfflineHeldSnapshot snap = new OfflineHeldSnapshot();
        snap.discountType = selectedDiscountType;
        snap.discountValue = requestedDiscountValue;
        snap.roundingMode = selectedRoundingMode;
        for (CartItem item : cartItems) {
            CartItem copyItem = new CartItem(
                    item.getMedicineId(),
                    item.getBrandName(),
                    item.getGenericName(),
                    item.getPackagingUnit(),
                    item.getQuantity(),
                    item.getUnitPrice(),
                    item.getBatchNumber(),
                    item.getExpiryDate(),
                    item.getVatRate()
            );
            CartLineMetadata origMeta = lineMetadataMap.get(item);
            CartLineMetadata copyMeta = new CartLineMetadata();
            if (origMeta != null) {
                copyMeta.candidate = origMeta.candidate;
                copyMeta.backendLineId = origMeta.backendLineId;
                copyMeta.conversionFactor = origMeta.conversionFactor;
            }
            snap.items.add(copyItem);
            snap.metas.add(copyMeta);
            offlineEntry.offlineCartItems.add(copyItem);
        }
        offlineHeldSnapshots.put(localId, snap);
        offlineHeldCarts.add(0, offlineEntry);

        clearActiveCounterForNextCustomer();
        syncHeldCartsListWithOffline();
        updateHeldCountBadge();
        if (!syncEngine.isOnline()) {
            updateStateBanner(
                    UiState.OFFLINE,
                    "Offline hold saved locally (" + resolvedRef + "; cloud stock NOT reserved). Counter is ready.",
                    "অফলাইনে কার্টটি লোকালে সংরক্ষিত হয়েছে (" + resolvedRef + "; ক্লাউড স্টক রিজার্ভ নয়)। পরবর্তী গ্রাহকের জন্য প্রস্তুত।"
            );
        } else {
            updateStateBanner(
                    UiState.READY,
                    "Cart placed on hold. Ready for next customer.",
                    "কার্ট হোল্ড করা হয়েছে। পরবর্তী গ্রাহকের জন্য প্রস্তুত।"
            );
        }
    }

    @FXML
    public void handleToggleHeldDrawer() {
        boolean show = heldCartsDrawer == null || !heldCartsDrawer.isVisible();
        setHeldDrawerVisible(show);
        if (show) {
            refreshHeldCartsList();
        }
    }

    @FXML
    public void handleRefreshHeldCarts() {
        setHeldDrawerVisible(true);
        refreshHeldCartsList();
    }

    public void refreshHeldCartsList() {
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";
        boolean onlineAuth = syncEngine.isOnline() && token != null && !token.isBlank();

        if (!onlineAuth) {
            heldCartEntries.setAll(offlineHeldCarts);
            lastKnownServerHeldCount = 0;
            lastKnownServerStaleCount = 0;
            updateHeldCountBadge();
            if (!syncEngine.isOnline()) {
                updateStateBanner(
                        UiState.OFFLINE,
                        offlineHeldCarts.isEmpty()
                                ? "Offline mode: no local held baskets stored."
                                : ("Offline mode: showing " + offlineHeldCarts.size() + " locally parked basket(s)."),
                        offlineHeldCarts.isEmpty()
                                ? "অফলাইন মোড: কোনো লোকাল হোল্ড কার্ট নেই।"
                                : ("অফলাইন মোড: " + offlineHeldCarts.size() + "টি লোকাল হোল্ড কার্ট দেখানো হচ্ছে।")
                );
            } else {
                updateStateBanner(
                        offlineHeldCarts.isEmpty() ? UiState.EMPTY : UiState.READY,
                        offlineHeldCarts.isEmpty() ? "No held baskets in this branch." : (offlineHeldCarts.size() + " held basket(s) ready."),
                        offlineHeldCarts.isEmpty() ? "এই শাখায় কোনো হোল্ড করা কার্ট নেই।" : (offlineHeldCarts.size() + "টি হোল্ড করা কার্ট প্রস্তুত।")
                );
            }
            return;
        }

        updateStateBanner(UiState.LOADING, UiState.LOADING.defaultMessageEn, UiState.LOADING.defaultMessageBn);
        Runnable work = () -> {
            ClientOutcome<HeldCartListResultDto> res = saleDomainClient.listHeldCarts(
                    token, role, tenantId, branchId, tenantId, branchId, true, false, false
            );
            Runnable applyUi = () -> applyHeldListOutcome(res);
            if (asyncNetworkCalls) {
                Platform.runLater(applyUi);
            } else {
                applyUi.run();
            }
        };
        if (asyncNetworkCalls) {
            CompletableFuture.runAsync(work);
        } else {
            work.run();
        }
    }

    private void applyHeldListOutcome(ClientOutcome<HeldCartListResultDto> res) {
        if (res.uiState == UiState.PERMISSION_DENIED) {
            heldCartEntries.setAll(offlineHeldCarts);
            updateHeldCountBadge();
            updateStateBanner(UiState.PERMISSION_DENIED, res.messageEn, res.messageBn);
            return;
        }
        if (res.uiState == UiState.OFFLINE) {
            syncEngine.setNetworkOnline(false);
            updateNetworkBadge();
            heldCartEntries.setAll(offlineHeldCarts);
            updateHeldCountBadge();
            updateStateBanner(UiState.OFFLINE, res.messageEn, res.messageBn);
            return;
        }
        if (res.uiState == UiState.ERROR) {
            updateStateBanner(UiState.ERROR, res.messageEn, res.messageBn);
            return;
        }
        List<HeldCartEntryDto> combined = new ArrayList<>();
        if (res.data != null) {
            combined.addAll(res.data.results);
            lastKnownServerHeldCount = res.data.count;
            lastKnownServerStaleCount = res.data.staleCount;
        } else {
            lastKnownServerHeldCount = 0;
            lastKnownServerStaleCount = 0;
        }
        combined.addAll(offlineHeldCarts);
        heldCartEntries.setAll(combined);
        updateHeldCountBadge();
        UiState finalState = combined.isEmpty() ? UiState.EMPTY : UiState.READY;
        updateStateBanner(finalState, res.messageEn, res.messageBn);
    }

    @FXML
    public void handleExpireStaleHolds() {
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";
        boolean onlineAuth = syncEngine.isOnline() && token != null && !token.isBlank();
        if (!onlineAuth) {
            updateStateBanner(
                    UiState.OFFLINE,
                    "Offline mode: cannot run server reservation expiry sweep while disconnected.",
                    "অফলাইন মোড: সংযোগ বিচ্ছিন্ন থাকায় সার্ভার রিজার্ভেশন এক্সপায়ার সুইপ চালানো যাবে না।"
            );
            return;
        }

        ClientOutcome<ExpireStaleResultDto> res = saleDomainClient.expireStaleHolds(
                token, role, tenantId, branchId, tenantId, branchId, false, "Cashier UI stale hold sweep", false, false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            ClientOutcome<HeldCartListResultDto> refreshRes = saleDomainClient.listHeldCarts(
                    token, role, tenantId, branchId, tenantId, branchId, true, false, false
            );
            if (refreshRes.data != null) {
                List<HeldCartEntryDto> combined = new ArrayList<>(refreshRes.data.results);
                combined.addAll(offlineHeldCarts);
                heldCartEntries.setAll(combined);
                lastKnownServerHeldCount = refreshRes.data.count;
                lastKnownServerStaleCount = refreshRes.data.staleCount;
                updateHeldCountBadge();
            }
            updateStateBanner(UiState.READY, res.messageEn, res.messageBn);
        } else {
            updateStateBanner(res.uiState, res.messageEn, res.messageBn);
        }
    }

    @FXML
    public void handleResumeSelectedHeldCart() {
        HeldCartEntryDto selected = getSelectedHeldEntryOrSingle();
        if (selected == null) {
            updateStateBanner(
                    UiState.EMPTY,
                    "Select a held basket to resume.",
                    "রিজিউম করার জন্য একটি হোল্ড কার্ট নির্বাচন করুন।"
            );
            return;
        }
        resumeHeldCart(selected, false);
    }

    public void resumeHeldCart(HeldCartEntryDto entry, boolean acknowledgeStale) {
        if (entry == null) return;
        if (!cartItems.isEmpty()) {
            updateStateBanner(
                    UiState.ERROR,
                    "Hold or clear the active cart before resuming another held basket.",
                    "অন্য হোল্ড কার্ট রিজিউম করার আগে বর্তমান কার্ট হোল্ড অথবা খালি করুন।"
            );
            return;
        }

        if (entry.offlineLocalOnly) {
            OfflineHeldSnapshot snap = offlineHeldSnapshots.remove(entry.saleId);
            offlineHeldCarts.remove(entry);
            heldCartEntries.remove(entry);
            cartItems.clear();
            lineMetadataMap.clear();
            activeServerSale = null;
            if (snap != null) {
                this.selectedDiscountType = snap.discountType;
                this.requestedDiscountValue = snap.discountValue;
                this.selectedRoundingMode = snap.roundingMode;
                for (int i = 0; i < snap.items.size(); i++) {
                    CartItem it = snap.items.get(i);
                    CartLineMetadata meta = i < snap.metas.size() ? snap.metas.get(i) : new CartLineMetadata();
                    cartItems.add(it);
                    lineMetadataMap.put(it, meta);
                }
            } else {
                for (CartItem it : entry.offlineCartItems) {
                    cartItems.add(it);
                    lineMetadataMap.put(it, new CartLineMetadata());
                }
            }
            clearStaleConfirmationPrompt();
            setHeldDrawerVisible(false);
            configurePricingControls();
            updateTotals();
            updateHeldCountBadge();
            updateStateBanner(
                    syncEngine.isOnline() ? UiState.READY : UiState.OFFLINE,
                    "Resumed locally held basket (" + entry.holdReference + ").",
                    "লোকাল হোল্ড কার্ট পুনরুদ্ধার করা হয়েছে (" + entry.holdReference + ")।"
            );
            return;
        }

        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";
        boolean reReserve = entry.holdStockReserved
                && (entry.staleness == null || !entry.staleness.hasStockShortage)
                && (pendingStaleDiagnostics == null || !pendingStaleDiagnostics.hasStockShortage);

        ClientOutcome<ResumeCartResultDto> res = saleDomainClient.resumeCart(
                token,
                role,
                tenantId,
                branchId,
                tenantId,
                branchId,
                entry.saleId,
                acknowledgeStale,
                true,
                reReserve,
                false,
                entry.version > 0 ? entry.version : null,
                "",
                false,
                !syncEngine.isOnline()
        );

        if (res.data != null && res.data.requiresConfirmation) {
            showStaleConfirmationPrompt(entry, res.data.staleness, res.messageEn, res.messageBn);
            updateStateBanner(UiState.ERROR, res.messageEn, res.messageBn);
            return;
        }
        if (res.uiState == UiState.READY && res.data != null && res.data.sale != null) {
            heldCartEntries.removeIf(e -> Objects.equals(e.saleId, entry.saleId));
            if (lastKnownServerHeldCount > 0) {
                lastKnownServerHeldCount--;
            }
            clearStaleConfirmationPrompt();
            setHeldDrawerVisible(false);
            syncCartFromServerOrder(res.data.sale, null);
            updateHeldCountBadge();
            updateStateBanner(UiState.READY, res.messageEn, res.messageBn);
            return;
        }
        updateStateBanner(res.uiState, res.messageEn, res.messageBn);
    }

    @FXML
    public void handleAcknowledgeStaleResume() {
        if (pendingStaleResumeEntry == null) {
            clearStaleConfirmationPrompt();
            return;
        }
        resumeHeldCart(pendingStaleResumeEntry, true);
    }

    @FXML
    public void handleDismissStalePrompt() {
        clearStaleConfirmationPrompt();
    }

    @FXML
    public void handleReleaseSelectedHold() {
        HeldCartEntryDto selected = getSelectedHeldEntryOrSingle();
        if (selected == null) {
            updateStateBanner(UiState.EMPTY, "Select a held basket first.", "প্রথমে একটি হোল্ড কার্ট নির্বাচন করুন।");
            return;
        }
        if (selected.offlineLocalOnly) {
            updateStateBanner(
                    UiState.OFFLINE,
                    "Offline held basket has no cloud stock reservation to release.",
                    "অফলাইন হোল্ড কার্টে কোনো ক্লাউড স্টক রিজার্ভেশন নেই।"
            );
            return;
        }
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";
        ClientOutcome<PosSaleOrderDto> res = saleDomainClient.expireHold(
                token, role, tenantId, branchId, tenantId, branchId,
                selected.saleId, false, "Operator released reservation cleanly", false, !syncEngine.isOnline()
        );
        if (res.uiState == UiState.READY && res.data != null) {
            HeldCartEntryDto updatedEntry = buildHeldEntryFromServerOrder(res.data);
            upsertServerHeldEntry(updatedEntry);
            updateHeldCountBadge();
            updateStateBanner(UiState.READY, res.messageEn, res.messageBn);
        } else {
            updateStateBanner(res.uiState, res.messageEn, res.messageBn);
        }
    }

    @FXML
    public void handleCancelSelectedHold() {
        HeldCartEntryDto selected = getSelectedHeldEntryOrSingle();
        if (selected == null) {
            updateStateBanner(UiState.EMPTY, "Select a held basket to cancel.", "বাতিল করার জন্য একটি হোল্ড কার্ট নির্বাচন করুন।");
            return;
        }
        if (selected.offlineLocalOnly) {
            offlineHeldSnapshots.remove(selected.saleId);
            offlineHeldCarts.remove(selected);
            heldCartEntries.remove(selected);
            if (pendingStaleResumeEntry != null && Objects.equals(pendingStaleResumeEntry.saleId, selected.saleId)) {
                clearStaleConfirmationPrompt();
            }
            updateHeldCountBadge();
            updateStateBanner(
                    UiState.READY,
                    "Cancelled local held basket (" + selected.holdReference + ").",
                    "লোকাল হোল্ড কার্ট বাতিল করা হয়েছে (" + selected.holdReference + ")।"
            );
            return;
        }
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";
        ClientOutcome<PosSaleOrderDto> res = saleDomainClient.cancelHold(
                token, role, tenantId, branchId, tenantId, branchId,
                selected.saleId, "Cancelled from cashier POS drawer",
                selected.version > 0 ? selected.version : null, false, !syncEngine.isOnline()
        );
        if (res.uiState == UiState.READY) {
            heldCartEntries.removeIf(e -> Objects.equals(e.saleId, selected.saleId));
            if (lastKnownServerHeldCount > 0) {
                lastKnownServerHeldCount--;
            }
            if (pendingStaleResumeEntry != null && Objects.equals(pendingStaleResumeEntry.saleId, selected.saleId)) {
                clearStaleConfirmationPrompt();
            }
            updateHeldCountBadge();
            updateStateBanner(UiState.READY, res.messageEn, res.messageBn);
        } else {
            updateStateBanner(res.uiState, res.messageEn, res.messageBn);
        }
    }

    private HeldCartEntryDto getSelectedHeldEntryOrSingle() {
        if (heldCartEntries.isEmpty()) return null;
        if (heldCartsList != null) {
            HeldCartEntryDto sel = heldCartsList.getSelectionModel().getSelectedItem();
            if (sel != null) return sel;
        }
        return heldCartEntries.get(0);
    }

    private void showStaleConfirmationPrompt(
            HeldCartEntryDto entry,
            StalenessDiagnosticsDto staleness,
            String fallbackEn,
            String fallbackBn
    ) {
        this.pendingStaleResumeEntry = entry;
        this.pendingStaleDiagnostics = staleness;
        refreshStaleBannerText(fallbackEn, fallbackBn);
        if (staleConfirmationBanner != null) {
            staleConfirmationBanner.setVisible(true);
            staleConfirmationBanner.setManaged(true);
        }
    }

    private void refreshStaleBannerText(String fallbackEn, String fallbackBn) {
        if (stalePromptLabel == null) return;
        String text;
        if (pendingStaleDiagnostics != null) {
            String base = isBangla
                    ? (pendingStaleDiagnostics.userPromptBn.isBlank() ? fallbackBn : pendingStaleDiagnostics.userPromptBn)
                    : (pendingStaleDiagnostics.userPromptEn.isBlank() ? fallbackEn : pendingStaleDiagnostics.userPromptEn);
            if (pendingStaleDiagnostics.hasPriceChange) {
                base = base + (isBangla
                        ? (" | পূর্বের মোট: BDT " + pendingStaleDiagnostics.heldGrandTotalBdt.toPlainString()
                            + " -> বর্তমান মোট: BDT " + pendingStaleDiagnostics.liveGrandTotalBdt.toPlainString())
                        : (" | Held: BDT " + pendingStaleDiagnostics.heldGrandTotalBdt.toPlainString()
                            + " -> Live: BDT " + pendingStaleDiagnostics.liveGrandTotalBdt.toPlainString()));
            }
            text = base;
        } else {
            text = isBangla ? fallbackBn : fallbackEn;
        }
        stalePromptLabel.setText(text);
    }

    private void clearStaleConfirmationPrompt() {
        this.pendingStaleResumeEntry = null;
        this.pendingStaleDiagnostics = null;
        if (stalePromptLabel != null) {
            stalePromptLabel.setText("");
        }
        if (staleConfirmationBanner != null) {
            staleConfirmationBanner.setVisible(false);
            staleConfirmationBanner.setManaged(false);
        }
    }

    private void setHeldDrawerVisible(boolean visible) {
        if (heldCartsDrawer != null) {
            heldCartsDrawer.setVisible(visible);
            heldCartsDrawer.setManaged(visible);
        }
    }

    private HeldCartEntryDto buildHeldEntryFromServerOrder(PosSaleOrderDto order) {
        HeldCartEntryDto dto = new HeldCartEntryDto();
        dto.saleId = order.saleId;
        dto.orderNumber = order.orderNumber;
        dto.branchId = order.branchId;
        dto.customerName = order.customerName;
        dto.customerPhone = order.customerPhone;
        dto.lifecycleState = order.lifecycleState;
        dto.holdReference = order.holdReference;
        dto.holdNote = order.holdNote;
        dto.heldAt = order.heldAt;
        dto.heldByEmail = order.heldByEmail;
        dto.holdExpiresAt = order.holdExpiresAt;
        dto.holdStockReserved = order.holdStockReserved;
        dto.holdReservationExpiresAt = order.holdReservationExpiresAt;
        dto.lineCount = order.lines.size();
        dto.heldGrandTotalBdt = order.holdSnapshotGrandTotalBdt;
        dto.liveGrandTotalBdt = order.staleness != null && order.staleness.liveGrandTotalBdt.compareTo(BigDecimal.ZERO) > 0
                ? order.staleness.liveGrandTotalBdt
                : order.grandTotalBdt;
        dto.staleness = order.staleness != null ? order.staleness : new StalenessDiagnosticsDto();
        dto.version = order.version;
        dto.sale = order;
        return dto;
    }

    private void upsertServerHeldEntry(HeldCartEntryDto entry) {
        for (int i = 0; i < heldCartEntries.size(); i++) {
            if (Objects.equals(heldCartEntries.get(i).saleId, entry.saleId)) {
                heldCartEntries.set(i, entry);
                return;
            }
        }
        heldCartEntries.add(0, entry);
        lastKnownServerHeldCount++;
    }

    private void syncHeldCartsListWithOffline() {
        heldCartEntries.removeIf(e -> e.offlineLocalOnly);
        heldCartEntries.addAll(offlineHeldCarts);
    }

    private void updateHeldCountBadge() {
        int serverCount = 0;
        int staleCount = 0;
        for (HeldCartEntryDto e : heldCartEntries) {
            if (!e.offlineLocalOnly) {
                serverCount++;
                if (e.staleness != null && e.staleness.isStale) {
                    staleCount++;
                }
            }
        }
        int total = Math.max(lastKnownServerHeldCount, serverCount) + offlineHeldCarts.size();
        int totalStale = Math.max(lastKnownServerStaleCount, staleCount);
        if (btnHeldCarts != null) {
            btnHeldCarts.setText(isBangla
                    ? ("হোল্ড তালিকা (" + total + ") [F10]")
                    : ("Held (" + total + ") [F10]"));
        }
        if (heldDrawerSummaryLabel != null) {
            heldDrawerSummaryLabel.setText(isBangla
                    ? (total + " হোল্ড | " + totalStale + " সতর্কতা | " + offlineHeldCarts.size() + " অফলাইন")
                    : (total + " held | " + totalStale + " stale | " + offlineHeldCarts.size() + " offline"));
        }
    }

    // -------------------------------------------------------------------------
    // P226: Preorders & Delivery Fulfillment Controls + Address Privacy Drawer
    // -------------------------------------------------------------------------

    private void configureDeliveriesList() {
        if (deliveryModeCombo != null) {
            deliveryModeCombo.setItems(FXCollections.observableArrayList(
                    "HOME_DELIVERY", "PREORDER_PICKUP", "COURIER_PARCEL"
            ));
            deliveryModeCombo.setValue("HOME_DELIVERY");
        }
        setBtnIcon(btnRefreshDeliveries, Feather.REFRESH_CW, 12, "#334155");
        setBtnIcon(btnDeliverOrder, Feather.CHECK_CIRCLE, 12, "#059669");

        if (deliveriesList == null) {
            return;
        }
        deliveriesList.setItems(deliveryEntries);
        deliveriesList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) ->
                updateDeliveryDetailPanel()
        );
        deliveriesList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(PosDeliveryOrderDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                HBox row = new HBox(10.0);
                row.setAlignment(Pos.CENTER_LEFT);

                VBox left = new VBox(2.0);
                String numTitle = item.deliveryNumber.isBlank()
                        ? item.deliveryId
                        : item.deliveryNumber + (item.orderNumber.isBlank() ? "" : " (" + item.orderNumber + ")");
                Label title = new Label(numTitle + " - " + item.recipientName + " | " + item.recipientPhone);
                title.setStyle("-fx-font-weight: 700; -fx-font-size: 12px; -fx-text-fill: #0F172A;");

                String addrPreview = item.deliveryAddressLine.isBlank()
                        ? (item.deliveryArea.isBlank() ? item.deliveryCity : item.deliveryArea + ", " + item.deliveryCity)
                        : (item.deliveryAddressLine + (item.deliveryArea.isBlank() ? "" : ", " + item.deliveryArea));
                String stateDesc = item.fulfillmentMode + " | " + item.fulfillmentState
                        + " | Fee BDT " + item.deliveryFeeBdt.toPlainString()
                        + (item.offlineLocalOnly ? (isBangla ? " | অফলাইন কিউ" : " | Offline Queued") : "");
                Label sub = new Label(addrPreview + " | " + stateDesc);
                sub.setStyle("-fx-font-size: 11px; -fx-text-fill: #475569;");
                left.getChildren().addAll(title, sub);
                HBox.setHgrow(left, Priority.ALWAYS);

                HBox badges = new HBox(6.0);
                badges.setAlignment(Pos.CENTER_RIGHT);

                boolean masked = item.addressPrivacyMasked || !item.addressRedactedAt.isBlank();
                Label privacyBadge = new Label(masked
                        ? (isBangla ? "ঠিকানা গোপন" : "PII Masked")
                        : (isBangla ? "ঠিকানা দৃশ্যমান" : "PII Visible"));
                privacyBadge.setGraphic(createIcon(masked ? Feather.SHIELD : Feather.UNLOCK, 11,
                        masked ? "#D97706" : "#059669"));
                privacyBadge.setStyle(masked
                        ? "-fx-background-color: #FEF3C7; -fx-text-fill: #92400E; -fx-padding: 2 6; -fx-background-radius: 4; -fx-font-size: 10px; -fx-font-weight: 700;"
                        : "-fx-background-color: #DCFCE7; -fx-text-fill: #166534; -fx-padding: 2 6; -fx-background-radius: 4; -fx-font-size: 10px; -fx-font-weight: 700;");
                badges.getChildren().add(privacyBadge);

                Label stateBadge = new Label(item.fulfillmentState);
                stateBadge.setStyle("-fx-background-color: #E0F2FE; -fx-text-fill: #075985; -fx-padding: 2 6; -fx-background-radius: 4; -fx-font-size: 10px; -fx-font-weight: 700;");
                badges.getChildren().add(stateBadge);

                Label totalBadge = new Label("BDT " + item.saleGrandTotalBdt.toPlainString());
                totalBadge.setStyle("-fx-font-weight: 700; -fx-font-size: 12px; -fx-text-fill: #0284C7;");
                badges.getChildren().add(totalBadge);

                row.getChildren().addAll(left, badges);
                setGraphic(row);
                setText(null);
            }
        });
        updateDeliveryDetailPanel();
    }

    @FXML
    public void handleToggleDeliveryDrawer() {
        boolean show = deliveryDrawer == null || !deliveryDrawer.isVisible();
        setDeliveryDrawerVisible(show);
        if (show) {
            refreshDeliveriesList();
        }
    }

    @FXML
    public void handleRefreshDeliveries() {
        refreshDeliveriesList();
    }

    public void refreshDeliveriesList() {
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";
        boolean onlineAuth = syncEngine.isOnline() && token != null && !token.isBlank();

        if (!onlineAuth) {
            deliveryEntries.setAll(offlineDeliveryOrders);
            lastKnownServerDeliveryCount = 0;
            updateDeliveryCountBadge();
            updateDeliveryDetailPanel();
            if (!syncEngine.isOnline()) {
                updateStateBanner(
                        UiState.OFFLINE,
                        offlineDeliveryOrders.isEmpty()
                                ? "Offline mode: no local preorders or deliveries queued."
                                : ("Offline mode: showing " + offlineDeliveryOrders.size() + " locally queued delivery order(s)."),
                        offlineDeliveryOrders.isEmpty()
                                ? "অফলাইন মোড: কোনো লোকাল প্রি-অর্ডার বা ডেলিভারি সংরক্ষিত নেই।"
                                : ("অফলাইন মোড: " + offlineDeliveryOrders.size() + "টি লোকাল প্রি-অর্ডার/ডেলিভারি দেখানো হচ্ছে।")
                );
            } else {
                updateStateBanner(
                        deliveryEntries.isEmpty() ? UiState.EMPTY : UiState.READY,
                        deliveryEntries.isEmpty() ? "No delivery orders loaded." : ("Loaded " + deliveryEntries.size() + " delivery order(s)."),
                        deliveryEntries.isEmpty() ? "কোনো ডেলিভারি অর্ডার পাওয়া যায়নি।" : (deliveryEntries.size() + "টি ডেলিভারি অর্ডার দেখানো হচ্ছে।")
                );
            }
            return;
        }

        updateStateBanner(UiState.LOADING, UiState.LOADING.defaultMessageEn, UiState.LOADING.defaultMessageBn);
        Runnable work = () -> {
            ClientOutcome<DeliveryListResultDto> res = saleDomainClient.listDeliveryOrders(
                    token, role, tenantId, branchId, tenantId, branchId, "", "", false, !syncEngine.isOnline()
            );
            Runnable applyUi = () -> applyDeliveryListOutcome(res);
            if (asyncNetworkCalls) {
                Platform.runLater(applyUi);
            } else {
                applyUi.run();
            }
        };
        if (asyncNetworkCalls) {
            CompletableFuture.runAsync(work);
        } else {
            work.run();
        }
    }

    private void applyDeliveryListOutcome(ClientOutcome<DeliveryListResultDto> res) {
        if (res.uiState == UiState.PERMISSION_DENIED) {
            updateStateBanner(UiState.PERMISSION_DENIED, res.messageEn, res.messageBn);
            return;
        }
        if (res.uiState == UiState.OFFLINE || res.uiState == UiState.ERROR || res.data == null) {
            deliveryEntries.setAll(offlineDeliveryOrders);
            updateDeliveryCountBadge();
            updateDeliveryDetailPanel();
            updateStateBanner(
                    res.uiState == UiState.OFFLINE ? UiState.OFFLINE : UiState.ERROR,
                    res.messageEn,
                    res.messageBn
            );
            return;
        }

        List<PosDeliveryOrderDto> combined = new ArrayList<>(res.data.results);
        combined.addAll(offlineDeliveryOrders);
        deliveryEntries.setAll(combined);
        lastKnownServerDeliveryCount = res.data.count;
        updateDeliveryCountBadge();
        updateDeliveryDetailPanel();
        UiState finalState = combined.isEmpty() ? UiState.EMPTY : UiState.READY;
        updateStateBanner(finalState, res.messageEn, res.messageBn);
    }

    @FXML
    public void handleCreateDeliveryFromCart() {
        boolean hasSale = activeServerSale != null && !activeServerSale.saleId.isBlank()
                && !activeServerSale.saleId.startsWith("offline-cart-");
        if (!hasSale && cartItems.isEmpty()) {
            updateStateBanner(UiState.EMPTY,
                    "Add medicines to cart before creating a preorder or delivery order.",
                    "প্রি-অর্ডার বা ডেলিভারি তৈরি করার আগে কার্টে ওষুধ যুক্ত করুন।");
            return;
        }

        String mode = deliveryModeCombo != null && deliveryModeCombo.getValue() != null
                ? deliveryModeCombo.getValue().trim().toUpperCase(Locale.ROOT)
                : "HOME_DELIVERY";
        String recipientName = deliveryRecipientField != null && deliveryRecipientField.getText() != null
                ? deliveryRecipientField.getText().trim() : "";
        String recipientPhone = deliveryPhoneField != null && deliveryPhoneField.getText() != null
                ? deliveryPhoneField.getText().trim() : "";
        String addressLine = deliveryAddressField != null && deliveryAddressField.getText() != null
                ? deliveryAddressField.getText().trim() : "";
        String area = deliveryAreaField != null && deliveryAreaField.getText() != null
                ? deliveryAreaField.getText().trim() : "";
        String feeRaw = deliveryFeeField != null && deliveryFeeField.getText() != null
                ? deliveryFeeField.getText().trim() : "";
        boolean reserveStock = reserveDeliveryStockCheck == null || reserveDeliveryStockCheck.isSelected();

        if (recipientName.isEmpty() || recipientPhone.isEmpty()) {
            updateStateBanner(UiState.ERROR,
                    "Recipient name and Bangladesh phone number are required.",
                    "প্রাপকের নাম এবং মোবাইল নম্বর দেওয়া আবশ্যক।");
            return;
        }
        if ("HOME_DELIVERY".equals(mode) && addressLine.isEmpty()) {
            updateStateBanner(UiState.ERROR,
                    "Street address is required for Home Delivery.",
                    "হোম ডেলিভারির জন্য পূর্ণ ঠিকানা দেওয়া আবশ্যক।");
            return;
        }

        BigDecimal deliveryFee = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (!feeRaw.isEmpty()) {
            try {
                deliveryFee = new BigDecimal(feeRaw).setScale(2, RoundingMode.HALF_UP);
                if (deliveryFee.compareTo(BigDecimal.ZERO) < 0) {
                    updateStateBanner(UiState.ERROR,
                            "Delivery fee cannot be negative.",
                            "ডেলিভারি ফি ঋণাত্মক হতে পারবে না।");
                    return;
                }
            } catch (NumberFormatException ex) {
                updateStateBanner(UiState.ERROR,
                        "Enter a valid numeric delivery fee in BDT.",
                        "টাকায় সঠিক সংখ্যাসূচক ডেলিভারি ফি লিখুন।");
                return;
            }
        }

        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";

        if (!syncEngine.isOnline()) {
            PosDeliveryOrderDto localDlv = new PosDeliveryOrderDto();
            localDlv.deliveryId = "offline-dlv-" + UUID.randomUUID().toString().substring(0, 8);
            localDlv.deliveryNumber = "OFF-DLV-" + (offlineDeliveryOrders.size() + 1);
            localDlv.tenantId = tenantId;
            localDlv.branchId = branchId;
            localDlv.fulfillmentMode = mode;
            localDlv.deliveryMethod = "PREORDER_PICKUP".equals(mode) ? "BRANCH_PICKUP"
                    : ("COURIER_PARCEL".equals(mode) ? "THIRD_PARTY_COURIER" : "OWN_RIDER");
            localDlv.fulfillmentState = "PREORDER_PENDING";
            localDlv.recipientName = recipientName;
            localDlv.recipientPhone = recipientPhone;
            localDlv.deliveryAddressLine = addressLine;
            localDlv.deliveryArea = area;
            localDlv.deliveryCity = "Dhaka";
            localDlv.deliveryFeeBdt = deliveryFee;
            BigDecimal itemsSum = BigDecimal.ZERO;
            for (CartItem ci : cartItems) {
                itemsSum = itemsSum.add(ci.getLineSubtotal());
            }
            localDlv.saleGrandTotalBdt = itemsSum.add(deliveryFee).setScale(2, RoundingMode.HALF_UP);
            localDlv.addressPrivacyMasked = false;
            localDlv.offlineLocalOnly = true;
            localDlv.offlineCartItems = new ArrayList<>(cartItems);

            offlineDeliveryOrders.add(0, localDlv);
            upsertDeliveryEntry(localDlv);
            clearDeliveryDraftInputs();
            clearActiveCounterForNextCustomer();
            updateDeliveryCountBadge();
            setDeliveryDrawerVisible(true);
            if (deliveriesList != null) {
                deliveriesList.getSelectionModel().select(localDlv);
            }
            updateDeliveryDetailPanel();
            updateStateBanner(
                    UiState.OFFLINE,
                    "Offline preorder/delivery queued locally (" + localDlv.deliveryNumber + "). Will sync when connected.",
                    "অফলাইন প্রি-অর্ডার/ডেলিভারি লোকাল কিউতে সংরক্ষিত হয়েছে (" + localDlv.deliveryNumber + ")।"
            );
            return;
        }

        if (token == null || token.isBlank()) {
            updateStateBanner(
                    UiState.PERMISSION_DENIED,
                    "Active cashier session token is required to submit a server preorder/delivery.",
                    "সার্ভারে প্রি-অর্ডার/ডেলিভারি পাঠাতে সক্রিয় ক্যাশিয়ার টোকেন প্রয়োজন।"
            );
            return;
        }

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("branch", branchId);
        payload.put("fulfillment_mode", mode);
        payload.put("delivery_method", "PREORDER_PICKUP".equals(mode) ? "BRANCH_PICKUP"
                : ("COURIER_PARCEL".equals(mode) ? "THIRD_PARTY_COURIER" : "OWN_RIDER"));
        payload.put("reserve_stock", reserveStock);
        payload.put("recipient_name", recipientName);
        payload.put("recipient_phone", recipientPhone);
        payload.put("delivery_address_line", addressLine);
        if (!area.isEmpty()) {
            payload.put("delivery_area", area);
        }
        payload.put("delivery_fee_bdt", deliveryFee.toPlainString());
        if (hasSale) {
            payload.put("sale_id", activeServerSale.saleId);
        } else {
            List<Map<String, Object>> lineMaps = new ArrayList<>();
            for (CartItem ci : cartItems) {
                Map<String, Object> lm = new java.util.LinkedHashMap<>();
                lm.put("product_id", ci.getMedicineId());
                lm.put("packaging_unit", ci.getPackagingUnit());
                lm.put("quantity_units", ci.getQuantity());
                lineMaps.add(lm);
            }
            payload.put("lines", lineMaps);
        }
        payload.put("idempotency_key", "dlv-pos-" + UUID.randomUUID());

        ClientOutcome<PosDeliveryOrderDto> res = saleDomainClient.createDeliveryOrder(
                token, role, tenantId, branchId, tenantId, branchId, payload, false, false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            lastKnownServerDeliveryCount++;
            upsertDeliveryEntry(res.data);
            clearDeliveryDraftInputs();
            clearActiveCounterForNextCustomer();
            updateDeliveryCountBadge();
            setDeliveryDrawerVisible(true);
            if (deliveriesList != null) {
                deliveriesList.getSelectionModel().select(res.data);
            }
            updateDeliveryDetailPanel();
            updateStateBanner(UiState.READY, res.messageEn, res.messageBn);
        } else {
            updateStateBanner(res.uiState, res.messageEn, res.messageBn);
        }
    }

    @FXML
    public void handlePackSelectedDelivery() {
        executeSelectedDeliveryTransition(
                "pack",
                new java.util.LinkedHashMap<>(),
                "Delivery order marked PACKED and verified.",
                "ডেলিভারি অর্ডার প্যাকড হিসেবে চিহ্নিত হয়েছে।"
        );
    }

    @FXML
    public void handleDispatchSelectedDelivery() {
        String rider = deliveryRiderField != null && deliveryRiderField.getText() != null
                ? deliveryRiderField.getText().trim() : "";
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (!rider.isEmpty()) {
            payload.put("rider_name", rider);
        }
        executeSelectedDeliveryTransition(
                "dispatch",
                payload,
                "Delivery order DISPATCHED.",
                "ডেলিভারি অর্ডার ডিসপ্যাচ করা হয়েছে।"
        );
    }

    @FXML
    public void handleDeliverSelectedOrder() {
        String pod = deliveryPodField != null && deliveryPodField.getText() != null
                ? deliveryPodField.getText().trim() : "";
        PosDeliveryOrderDto selected = getSelectedDeliveryOrSingle();
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (!pod.isEmpty()) {
            payload.put("proof_of_delivery_ref", pod);
        }
        if (selected != null && selected.codExpectedBdt != null && selected.codExpectedBdt.compareTo(BigDecimal.ZERO) > 0) {
            payload.put("cod_collected_bdt", selected.codExpectedBdt.toPlainString());
            payload.put("cod_tender_type", "CASH");
        }
        executeSelectedDeliveryTransition(
                "deliver",
                payload,
                "Delivery order COMPLETED and reconciled.",
                "ডেলিভারি সম্পন্ন এবং রিকনসাইল করা হয়েছে।"
        );
    }

    @FXML
    public void handleCancelSelectedDelivery() {
        PosDeliveryOrderDto selected = getSelectedDeliveryOrSingle();
        if (selected == null) {
            updateStateBanner(UiState.EMPTY, "Select a delivery order first.", "প্রথমে একটি ডেলিভারি অর্ডার নির্বাচন করুন।");
            return;
        }
        if (selected.offlineLocalOnly) {
            offlineDeliveryOrders.removeIf(d -> Objects.equals(d.deliveryId, selected.deliveryId));
            deliveryEntries.removeIf(d -> Objects.equals(d.deliveryId, selected.deliveryId));
            updateDeliveryCountBadge();
            updateDeliveryDetailPanel();
            updateStateBanner(UiState.READY,
                    "Offline queued delivery removed locally.",
                    "অফলাইন কিউ ডেলিভারি অর্ডারটি বাতিল করা হয়েছে।");
            return;
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("reason", "Cancelled from POS delivery drawer");
        executeSelectedDeliveryTransition(
                "cancel",
                payload,
                "Delivery cancelled and reserved stock released.",
                "ডেলিভারি বাতিল করে সংরক্ষিত স্টক মুক্ত করা হয়েছে।"
        );
    }

    @FXML
    public void handleRevealSelectedDeliveryAddress() {
        PosDeliveryOrderDto selected = getSelectedDeliveryOrSingle();
        if (selected == null) {
            updateStateBanner(UiState.EMPTY, "Select a delivery order to reveal address.", "ঠিকানা দেখার জন্য একটি ডেলিভারি নির্বাচন করুন।");
            return;
        }
        if (selected.offlineLocalOnly) {
            selected.addressPrivacyMasked = false;
            if (deliveriesList != null) {
                deliveriesList.refresh();
            }
            updateDeliveryDetailPanel();
            updateStateBanner(UiState.OFFLINE,
                    "Local offline order address displayed on terminal.",
                    "অফলাইন লোকাল অর্ডারের ঠিকানা টার্মিনালে দেখানো হচ্ছে।");
            return;
        }

        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";
        if (!syncEngine.isOnline() || token == null || token.isBlank()) {
            updateStateBanner(UiState.OFFLINE,
                    "Address reveal requires an active authenticated server connection for privacy audit logging.",
                    "গোপনীয়তা অডিট লগিংয়ের জন্য ঠিকানা উন্মোচনে সক্রিয় সার্ভার সংযোগ প্রয়োজন।");
            return;
        }

        ClientOutcome<AddressRevealResultDto> res = saleDomainClient.revealDeliveryAddress(
                token, role, tenantId, branchId, tenantId, branchId,
                selected.deliveryId,
                "Operational dispatch verification at POS terminal",
                false,
                false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            selected.recipientName = res.data.recipientName;
            selected.recipientPhone = res.data.recipientPhone;
            selected.deliveryAddressLine = res.data.deliveryAddressLine;
            selected.deliveryArea = res.data.deliveryArea;
            selected.deliveryCity = res.data.deliveryCity;
            selected.deliveryLandmarkNotes = res.data.deliveryLandmarkNotes;
            selected.addressPrivacyMasked = false;
            if (deliveriesList != null) {
                deliveriesList.refresh();
            }
            updateDeliveryDetailPanel();
            updateStateBanner(UiState.READY,
                    "Unmasked address revealed under privacy audit " + res.data.auditId + ".",
                    "প্রাইভেসি অডিট (" + res.data.auditId + ") সহ পূর্ণ ঠিকানা প্রদর্শিত হয়েছে।");
        } else {
            updateStateBanner(res.uiState, res.messageEn, res.messageBn);
        }
    }

    private void executeSelectedDeliveryTransition(
            String actionSlug,
            Map<String, Object> payload,
            String successEn,
            String successBn
    ) {
        PosDeliveryOrderDto selected = getSelectedDeliveryOrSingle();
        if (selected == null) {
            updateStateBanner(UiState.EMPTY, "Select a delivery order first.", "প্রথমে একটি ডেলিভারি অর্ডার নির্বাচন করুন।");
            return;
        }
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";
        if (!syncEngine.isOnline() || token == null || token.isBlank() || selected.offlineLocalOnly) {
            updateStateBanner(UiState.OFFLINE,
                    "Offline mode: fulfillment state transitions require server connectivity and stock sync.",
                    "অফলাইন মোড: ডেলিভারি অবস্থা পরিবর্তনের জন্য সার্ভার সংযোগ প্রয়োজন।");
            return;
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>(payload);
        if (selected.version > 0 && !body.containsKey("expected_version")) {
            body.put("expected_version", selected.version);
        }
        ClientOutcome<PosDeliveryOrderDto> res = saleDomainClient.transitionDelivery(
                token, role, tenantId, branchId, tenantId, branchId,
                selected.deliveryId, actionSlug, body, successEn, successBn, false, false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            upsertDeliveryEntry(res.data);
            updateDeliveryCountBadge();
            if (deliveriesList != null) {
                deliveriesList.getSelectionModel().select(res.data);
                deliveriesList.refresh();
            }
            updateDeliveryDetailPanel();
            updateStateBanner(UiState.READY, res.messageEn, res.messageBn);
        } else {
            updateStateBanner(res.uiState, res.messageEn, res.messageBn);
        }
    }

    private void setDeliveryDrawerVisible(boolean visible) {
        if (deliveryDrawer != null) {
            deliveryDrawer.setVisible(visible);
            deliveryDrawer.setManaged(visible);
        }
    }

    // -------------------------------------------------------------------------
    // P227: POS Prescription Safety Gate, Pharmacist Clearance & Antibiotic Register
    // -------------------------------------------------------------------------

    private void configureRxGateControls() {
        setBtnIcon(btnRxGate, Feather.SHIELD, 13, "#0F766E");
        setBtnIcon(btnRefreshAntibioticRegister, Feather.BOOK_OPEN, 12, "#334155");
        setBtnIcon(btnCloseRxDrawer, Feather.X_CIRCLE, 12, "#64748B");
        setBtnIcon(btnAttachPrescription, Feather.FILE_TEXT, 12, "#0F766E");
        setBtnIcon(btnVerifyRxOrder, Feather.CHECK_CIRCLE, 12, "#FFFFFF");
        setBtnIcon(btnRejectRxOrder, Feather.X_OCTAGON, 12, "#B42318");
    }

    private void setRxGateDrawerVisible(boolean visible) {
        if (rxGateDrawer != null) {
            rxGateDrawer.setVisible(visible);
            rxGateDrawer.setManaged(visible);
        }
    }

    @FXML
    public void handleToggleRxGateDrawer() {
        boolean show = rxGateDrawer == null || !rxGateDrawer.isVisible();
        setRxGateDrawerVisible(show);
        if (show && activeServerSale != null && !activeServerSale.saleId.isBlank()
                && !activeServerSale.saleId.startsWith("offline-cart-")
                && syncEngine.isOnline() && currentSession != null
                && !currentSession.getDeviceToken().isBlank()) {
            refreshActiveRxGateStatus();
        } else {
            updateRxSafetyGateControls();
        }
    }

    private boolean isOfflineOrderOnly() {
        return activeServerSale == null
                || activeServerSale.saleId.isBlank()
                || activeServerSale.saleId.startsWith("offline-cart-");
    }

    private boolean doesCartRequireRx() {
        if (activeServerSale != null && activeServerSale.requiresRxVerification) {
            return true;
        }
        for (CartLineMetadata meta : lineMetadataMap.values()) {
            if (meta != null && meta.candidate != null) {
                if (meta.candidate.requiresRxVerification) {
                    return true;
                }
                String regClass = meta.candidate.regulatoryClassification == null
                        ? ""
                        : meta.candidate.regulatoryClassification.toUpperCase(Locale.ROOT);
                if (!regClass.isBlank() && !"OTC".equals(regClass)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasBlockedRecallOrBan() {
        if (activeRxGateStatus != null && Objects.equals(activeRxGateStatus.saleId,
                activeServerSale != null ? activeServerSale.saleId : "")
                && activeRxGateStatus.hasBlockedRecallOrBan) {
            return true;
        }
        if (activeServerSale != null) {
            for (PosSaleLineDto line : activeServerSale.lines) {
                String cls = line.regulatoryClassification != null
                        ? line.regulatoryClassification.toUpperCase(Locale.ROOT) : "";
                if ("RECALLED".equals(cls) || "BANNED".equals(cls)) {
                    return true;
                }
            }
        }
        for (CartLineMetadata meta : lineMetadataMap.values()) {
            if (meta != null && meta.candidate != null && meta.candidate.regulatoryClassification != null) {
                String cls = meta.candidate.regulatoryClassification.toUpperCase(Locale.ROOT);
                if ("RECALLED".equals(cls) || "BANNED".equals(cls)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean cartRequiresAntibioticRegister() {
        if (activeRxGateStatus != null && Objects.equals(activeRxGateStatus.saleId,
                activeServerSale != null ? activeServerSale.saleId : "")
                && activeRxGateStatus.requiresAntibioticRegister) {
            return true;
        }
        if (activeServerSale != null) {
            for (PosSaleLineDto line : activeServerSale.lines) {
                String cls = line.regulatoryClassification != null
                        ? line.regulatoryClassification.toUpperCase(Locale.ROOT) : "";
                if (cls.startsWith("ANTIMICROBIAL_")) {
                    return true;
                }
            }
        }
        for (CartLineMetadata meta : lineMetadataMap.values()) {
            if (meta != null && meta.candidate != null && meta.candidate.regulatoryClassification != null) {
                String cls = meta.candidate.regulatoryClassification.toUpperCase(Locale.ROOT);
                if (cls.startsWith("ANTIMICROBIAL_")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasLocalUnverifiedRxScaling() {
        if (activeServerSale == null || !activeServerSale.requiresRxVerification) {
            return false;
        }
        Map<String, Integer> serverApprovedByProduct = new java.util.HashMap<>();
        for (PosSaleDomainClient.PosSaleLineDto line : activeServerSale.lines) {
            if (line.requiresRxVerification) {
                serverApprovedByProduct.merge(line.productId, Math.max(0, line.rxVerifiedBaseUnits), Integer::sum);
            }
        }
        Map<String, Integer> localBaseByProduct = new java.util.HashMap<>();
        for (CartItem ci : cartItems) {
            CartLineMetadata meta = lineMetadataMap.get(ci);
            boolean lineRx = activeServerSale.lines.stream()
                    .anyMatch(l -> Objects.equals(l.productId, ci.getMedicineId()) && l.requiresRxVerification)
                    || (meta != null && meta.candidate != null && meta.candidate.requiresRxVerification);
            if (lineRx) {
                int factor = meta != null ? Math.max(1, meta.conversionFactor) : 1;
                localBaseByProduct.merge(ci.getMedicineId(), Math.max(1, ci.getQuantity()) * factor, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : localBaseByProduct.entrySet()) {
            int approved = serverApprovedByProduct.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() > approved) {
                return true;
            }
        }
        return false;
    }

    private boolean isCartRxClearedForCheckout() {
        if (hasBlockedRecallOrBan()) {
            return false;
        }
        if (!doesCartRequireRx()) {
            return true;
        }
        if (activeServerSale == null) {
            return false;
        }
        return "APPROVED".equalsIgnoreCase(activeServerSale.rxGateStatus)
                && activeServerSale.rxVerifiedAt != null
                && !activeServerSale.rxVerifiedAt.isBlank()
                && !hasLocalUnverifiedRxScaling();
    }

    private void updateRxSafetyGateControls() {
        boolean blocked = hasBlockedRecallOrBan();
        boolean reqRx = doesCartRequireRx();
        boolean localScaling = hasLocalUnverifiedRxScaling();
        boolean approved = isCartRxClearedForCheckout();
        boolean offline = !syncEngine.isOnline();

        String gateStatus;
        if (blocked) {
            gateStatus = "BLOCKED_RECALL_OR_BAN";
        } else if (!reqRx) {
            gateStatus = "NOT_REQUIRED";
        } else if (activeServerSale != null && "REJECTED".equalsIgnoreCase(activeServerSale.rxGateStatus)) {
            gateStatus = "REJECTED";
        } else if (approved) {
            gateStatus = "APPROVED";
        } else {
            gateStatus = "PENDING_PHARMACIST";
        }

        if (btnRxGate != null) {
            if (blocked) {
                btnRxGate.setText(isBangla ? "Rx ব্লকড [F8]" : "Rx BLOCKED [F8]");
            } else if (!reqRx) {
                btnRxGate.setText(isBangla ? "Rx গেট (OTC) [F8]" : "Rx Gate (OTC) [F8]");
            } else if (approved) {
                btnRxGate.setText(isBangla ? "Rx অনুমোদিত [F8]" : "Rx Approved [F8]");
            } else {
                btnRxGate.setText(isBangla ? "Rx অপেক্ষমাণ (!) [F8]" : "Rx Required (!) [F8]");
            }
        }

        if (rxGateStatusBadge != null) {
            rxGateStatusBadge.setText(gateStatus);
            if (approved) {
                rxGateStatusBadge.setStyle("-fx-background-color: #D1E7DD; -fx-text-fill: #0F5132; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-weight: bold;");
            } else if (blocked || "REJECTED".equals(gateStatus)) {
                rxGateStatusBadge.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #991B1B; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-weight: bold;");
            } else {
                rxGateStatusBadge.setStyle("-fx-background-color: #FEF3C7; -fx-text-fill: #92400E; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-weight: bold;");
            }
        }

        if (checkoutRxGateHintLabel != null) {
            if (cartItems.isEmpty()) {
                checkoutRxGateHintLabel.setText(isBangla
                        ? "Rx গেট: কার্ট খালি"
                        : "Rx Gate: Cart empty");
                checkoutRxGateHintLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #475569;");
            } else if (blocked) {
                checkoutRxGateHintLabel.setText(isBangla
                        ? "Rx গেট ব্লকড: প্রত্যাহৃত বা নিষিদ্ধ ওষুধ বিক্রি নিষিদ্ধ।"
                        : "Rx Gate BLOCKED: Recalled/banned item cannot be sold.");
                checkoutRxGateHintLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #991B1B;");
            } else if (!reqRx) {
                checkoutRxGateHintLabel.setText(isBangla
                        ? (offline ? "Rx গেট: NOT_REQUIRED (অফলাইন OTC অনুমোদিত)" : "Rx গেট: NOT_REQUIRED (OTC প্রস্তুত)")
                        : (offline ? "Rx Gate: NOT_REQUIRED (Offline OTC Ready)" : "Rx Gate: NOT_REQUIRED (OTC Ready)"));
                checkoutRxGateHintLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #0F5132;");
            } else if (approved) {
                String pcb = activeServerSale != null && !activeServerSale.pharmacistPcbNoSnapshot.isBlank()
                        ? (" | PCB " + activeServerSale.pharmacistPcbNoSnapshot) : "";
                checkoutRxGateHintLabel.setText(isBangla
                        ? ("Rx অনুমোদিত" + pcb + " (চেকআউট প্রস্তুত)")
                        : ("Rx APPROVED" + pcb + " (Checkout Ready)"));
                checkoutRxGateHintLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #0F5132;");
            } else if (offline) {
                checkoutRxGateHintLabel.setText(isBangla
                        ? "অফলাইন সীমাবদ্ধতা: ফার্মাসিস্ট অনুমোদন ছাড়া Rx চেকআউট ব্লকড ([F9] হোল্ড করুন)।"
                        : "Offline Block: Unverified Rx/Abx blocked offline. Park [F9] & reconnect.");
                checkoutRxGateHintLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #B45309;");
            } else if (localScaling) {
                checkoutRxGateHintLabel.setText(isBangla
                        ? "পরিমাণ বৃদ্ধি পাওয়ায় পুনরায় ফার্মাসিস্ট Rx অনুমোদন প্রয়োজন [F8]।"
                        : "Cart edit invalidated prior Rx clearance; re-approve in Rx Gate [F8].");
                checkoutRxGateHintLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #B45309;");
            } else {
                checkoutRxGateHintLabel.setText(isBangla
                        ? ("Rx গেট: " + gateStatus + " — [F8] ফার্মাসিস্ট অনুমোদন আবশ্যক")
                        : ("Rx Gate: " + gateStatus + " — Pharmacist Approval [F8] Required"));
                checkoutRxGateHintLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #991B1B;");
            }
        }

        if (rxComplianceSummaryLabel != null && !rxComplianceSummaryLabel.getText().contains("Abx Register")) {
            if (!reqRx) {
                rxComplianceSummaryLabel.setText(isBangla
                        ? "OTC কার্ট: প্রেসক্রিপশন গেট প্রযোজ্য নয়।"
                        : "OTC basket: standard checkout ready.");
            } else if (approved && activeServerSale != null) {
                String pcb = activeServerSale.pharmacistPcbNoSnapshot.isBlank() ? "Verified" : activeServerSale.pharmacistPcbNoSnapshot;
                String abxTag = cartRequiresAntibioticRegister()
                        ? (isBangla ? " | অ্যান্টিবায়োটিক রেজিস্টার ফাইনাল সেলের সাথে সংরক্ষিত হবে।" : " | Antibiotic Register posts automatically on finalize.")
                        : "";
                rxComplianceSummaryLabel.setText(isBangla
                        ? ("ফার্মাসিস্ট PCB: " + pcb + " | রেফারেন্স: " + activeServerSale.rxReferenceCode + abxTag)
                        : ("Pharmacist PCB: " + pcb + " | Ref: " + activeServerSale.rxReferenceCode + abxTag));
            } else {
                rxComplianceSummaryLabel.setText(isBangla
                        ? "প্রেসক্রিপশন বা অ্যান্টিবায়োটিক আইটেম রয়েছে। অনুগ্রহ করে চিকিৎসকের BMDC সংযুক্ত করে ফার্মাসিস্ট অনুমোদন নিন।"
                        : "Cart contains Rx / controlled medicine. Attach Doctor BMDC if applicable and approve with Registered Pharmacist PCB.");
            }
        }
    }

    public void refreshActiveRxGateStatus() {
        if (isOfflineOrderOnly() || !syncEngine.isOnline()) {
            updateRxSafetyGateControls();
            return;
        }
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        if (token == null || token.isBlank()) {
            updateRxSafetyGateControls();
            return;
        }
        String role = currentSession.getUserRole();
        String tenantId = currentSession.getTenantId();
        String branchId = currentSession.getBranchId();
        ClientOutcome<PosSaleDomainClient.RxGateStatusDto> res = saleDomainClient.getRxGateStatus(
                token, role, tenantId, branchId, tenantId, branchId, activeServerSale.saleId, false, false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            activeRxGateStatus = res.data;
            activeServerSale.requiresRxVerification = res.data.requiresRxVerification;
            activeServerSale.rxGateStatus = res.data.rxGateStatus;
            activeServerSale.rxDecisionReason = res.data.rxDecisionReason;
            activeServerSale.pharmacistPcbNoSnapshot = res.data.pharmacistPcbNoSnapshot;
            activeServerSale.rxReferenceCode = res.data.rxReferenceCode;
            activeServerSale.rxVerifiedAt = res.data.rxVerifiedAt;
            updateRxSafetyGateControls();
        } else {
            updateStateBanner(res.uiState, res.messageEn, res.messageBn);
        }
    }

    @FXML
    public void handleAttachPrescription() {
        if (cartItems.isEmpty() || activeServerSale == null) {
            updateStateBanner(UiState.EMPTY,
                    "Add medicines to a server-backed cart before attaching a prescription.",
                    "প্রেসক্রিপশন সংযুক্ত করার আগে কার্টে ওষুধ যোগ করুন।");
            return;
        }
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";
        if (!syncEngine.isOnline() || token.isBlank() || isOfflineOrderOnly()) {
            updateStateBanner(UiState.OFFLINE,
                    "Offline limitation: prescription upload/attachment requires active server connectivity.",
                    "অফলাইন সীমাবদ্ধতা: প্রেসক্রিপশন সংযুক্ত করতে সার্ভার সংযোগ প্রয়োজন।");
            return;
        }
        String doctorName = rxDoctorNameField != null && rxDoctorNameField.getText() != null ? rxDoctorNameField.getText().trim() : "";
        String bmdcNo = rxDoctorBmdcField != null && rxDoctorBmdcField.getText() != null ? rxDoctorBmdcField.getText().trim() : "";
        String patientRef = rxPatientTokenField != null && rxPatientTokenField.getText() != null ? rxPatientTokenField.getText().trim() : "";
        String rxRef = rxReferenceField != null && rxReferenceField.getText() != null ? rxReferenceField.getText().trim() : "";

        ClientOutcome<PosSaleOrderDto> res = saleDomainClient.attachPrescription(
                token, role, tenantId, branchId, tenantId, branchId, activeServerSale.saleId,
                doctorName, bmdcNo, patientRef, rxRef, "", 7, false, false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            syncCartFromServerOrder(res.data, null);
            updateStateBanner(UiState.READY, res.messageEn, res.messageBn);
        } else {
            updateStateBanner(res.uiState, res.messageEn, res.messageBn);
        }
    }

    @FXML
    public void handleVerifyRxOrder() {
        if (cartItems.isEmpty() || activeServerSale == null) {
            updateStateBanner(UiState.EMPTY,
                    "Open a cart with Rx medicine before pharmacist verification.",
                    "ফার্মাসিস্ট অনুমোদনের আগে কার্টে ওষুধ যোগ করুন।");
            return;
        }
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";
        if (!syncEngine.isOnline() || token.isBlank() || isOfflineOrderOnly()) {
            updateStateBanner(UiState.OFFLINE,
                    "Offline limitation: Registered Pharmacist Rx approval cannot be performed offline.",
                    "অফলাইন সীমাবদ্ধতা: ফার্মাসিস্ট Rx অনুমোদন অফলাইনে সম্পন্ন করা যাবে না।");
            return;
        }
        String pcb = rxPharmacistPcbField != null && rxPharmacistPcbField.getText() != null ? rxPharmacistPcbField.getText().trim() : "";
        String notes = rxDecisionNotesField != null && rxDecisionNotesField.getText() != null ? rxDecisionNotesField.getText().trim() : "";
        String rxRef = rxReferenceField != null && rxReferenceField.getText() != null ? rxReferenceField.getText().trim() : "";
        String docName = rxDoctorNameField != null && rxDoctorNameField.getText() != null ? rxDoctorNameField.getText().trim() : "";
        String bmdc = rxDoctorBmdcField != null && rxDoctorBmdcField.getText() != null ? rxDoctorBmdcField.getText().trim() : "";
        Integer expectedVer = activeServerSale.version > 0 ? activeServerSale.version : null;

        ClientOutcome<PosSaleOrderDto> res = saleDomainClient.verifyRxSale(
                token, role, tenantId, branchId, tenantId, branchId, activeServerSale.saleId,
                pcb, notes, rxRef, docName, bmdc, expectedVer, false, false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            syncCartFromServerOrder(res.data, null);
            updateStateBanner(UiState.READY, res.messageEn, res.messageBn);
        } else {
            updateStateBanner(res.uiState, res.messageEn, res.messageBn);
        }
    }

    @FXML
    public void handleRejectRxOrder() {
        if (cartItems.isEmpty() || activeServerSale == null) {
            updateStateBanner(UiState.EMPTY,
                    "No active sale cart to reject.",
                    "বাতিল করার মতো কোনো সক্রিয় কার্ট নেই।");
            return;
        }
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";
        if (!syncEngine.isOnline() || token.isBlank() || isOfflineOrderOnly()) {
            updateStateBanner(UiState.OFFLINE,
                    "Offline limitation: Pharmacist Rx rejection sync requires server connection.",
                    "অফলাইন সীমাবদ্ধতা: Rx প্রত্যাখ্যান রেকর্ড করতে সার্ভার সংযোগ প্রয়োজন।");
            return;
        }
        String reason = rxDecisionNotesField != null && rxDecisionNotesField.getText() != null ? rxDecisionNotesField.getText().trim() : "";
        if (reason.length() < 3) {
            updateStateBanner(UiState.ERROR,
                    "Enter at least 3 characters describing why the prescription was rejected.",
                    "প্রেসক্রিপশন বাতিলের অন্তত ৩ অক্ষরের কারণ লিখুন।");
            return;
        }
        String pcb = rxPharmacistPcbField != null && rxPharmacistPcbField.getText() != null ? rxPharmacistPcbField.getText().trim() : "";

        ClientOutcome<PosSaleOrderDto> res = saleDomainClient.rejectRxSale(
                token, role, tenantId, branchId, tenantId, branchId, activeServerSale.saleId,
                pcb, reason, false, false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            syncCartFromServerOrder(res.data, null);
            updateStateBanner(UiState.READY, res.messageEn, res.messageBn);
        } else {
            updateStateBanner(res.uiState, res.messageEn, res.messageBn);
        }
    }

    @FXML
    public void handleRefreshAntibioticRegister() {
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        String role = currentSession != null ? currentSession.getUserRole() : "CASHIER";
        String tenantId = currentSession != null ? currentSession.getTenantId() : "tenant-demo";
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";
        if (!syncEngine.isOnline() || token.isBlank()) {
            updateStateBanner(UiState.OFFLINE,
                    "Offline mode: Antibiotic dispense register requires server query.",
                    "অফলাইন মোড: অ্যান্টিবায়োটিক রেজিস্টার দেখতে অনলাইন সংযোগ প্রয়োজন।");
            return;
        }
        ClientOutcome<PosSaleDomainClient.AntibioticRegisterListDto> res = saleDomainClient.listAntibioticRegister(
                token, role, tenantId, branchId, tenantId, branchId, "", "", false, false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            lastKnownAbxRegisterCount = res.data.count;
            if (rxComplianceSummaryLabel != null) {
                if (res.data.results.isEmpty()) {
                    rxComplianceSummaryLabel.setText(isBangla
                            ? ("অ্যান্টিবায়োটিক রেজিস্টার (" + lastKnownAbxRegisterCount + "): এই শাখায় কোনো এন্ট্রি নেই।")
                            : ("Abx Register (" + lastKnownAbxRegisterCount + "): 0 dispense entries in this branch."));
                } else {
                    PosSaleDomainClient.AntibioticRegisterEntryDto latest = res.data.results.get(0);
                    rxComplianceSummaryLabel.setText(String.format(
                            "Abx Register (%d): %s | %s (%d base u) | PCB %s | %s",
                            lastKnownAbxRegisterCount,
                            latest.orderNumber.isBlank() ? latest.registerEntryId : latest.orderNumber,
                            latest.brandName,
                            latest.dispensedBaseUnits,
                            latest.approvingPharmacistPcbNo,
                            latest.status
                    ));
                }
            }
            updateStateBanner(res.data.results.isEmpty() ? UiState.EMPTY : UiState.READY, res.messageEn, res.messageBn);
        } else {
            updateStateBanner(res.uiState, res.messageEn, res.messageBn);
        }
    }

    // -------------------------------------------------------------------------
    // P228: FEFO Lot Allocation Drawer, Manual Override RBAC & Stock Safety Gate
    // -------------------------------------------------------------------------

    private void configureFefoControls() {
        setBtnIcon(btnFefoLots, Feather.LAYERS, 13, "#0F766E");
        setBtnIcon(btnRefreshFefoPlan, Feather.REFRESH_CW, 12, "#334155");
        setBtnIcon(btnCloseFefoDrawer, Feather.X_CIRCLE, 12, "#64748B");
        setBtnIcon(btnReserveFefoLots, Feather.CHECK_CIRCLE, 12, "#FFFFFF");
    }

    private void setFefoPlanDrawerVisible(boolean visible) {
        if (fefoPlanDrawer != null) {
            fefoPlanDrawer.setVisible(visible);
            fefoPlanDrawer.setManaged(visible);
        }
    }

    @FXML
    public void handleToggleFefoPlanDrawer() {
        boolean show = fefoPlanDrawer == null || !fefoPlanDrawer.isVisible();
        setFefoPlanDrawerVisible(show);
        if (show && !isOfflineOrderOnly() && syncEngine.isOnline()
                && currentSession != null && !currentSession.getDeviceToken().isBlank()) {
            handleRefreshFefoPlan();
        } else {
            updateFefoPlanControls();
        }
    }

    private Map<String, String> buildBatchOverrideMapFromDrawer() {
        if (fefoOverrideBatchField == null || fefoOverrideBatchField.getText() == null
                || fefoOverrideBatchField.getText().isBlank()
                || activeServerSale == null || activeServerSale.lines.isEmpty()) {
            return Collections.emptyMap();
        }
        String batchId = fefoOverrideBatchField.getText().trim();
        int idx = getSelectedCartIndex();
        String targetLineId;
        if (idx >= 0 && idx < activeServerSale.lines.size()) {
            targetLineId = activeServerSale.lines.get(idx).lineId;
        } else {
            targetLineId = activeServerSale.lines.get(0).lineId;
        }
        if (targetLineId == null || targetLineId.isBlank()) {
            return Collections.emptyMap();
        }
        return Map.of(targetLineId, batchId);
    }

    @FXML
    public void handleRefreshFefoPlan() {
        if (cartItems.isEmpty() || activeServerSale == null) {
            updateFefoPlanControls();
            updateStateBanner(UiState.EMPTY,
                    "Add items to inspect live FEFO lot allocation.",
                    "FEFO লট পরিকল্পনা দেখতে কার্টে ওষুধ যোগ করুন।");
            return;
        }
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        if (!syncEngine.isOnline() || isOfflineOrderOnly() || token == null || token.isBlank()) {
            updateFefoPlanControls();
            updateStateBanner(UiState.OFFLINE,
                    "Offline limitation: live FEFO lot breakdown requires cloud sync (OFFLINE_PROVISIONAL_FEFO cached safety checks apply at checkout).",
                    "অফলাইন সীমাবদ্ধতা: লাইভ FEFO লট বিবরণ দেখতে সার্ভার সংযোগ প্রয়োজন (ক্যাশড স্টক নিরাপত্তা সক্রিয়)।");
            return;
        }
        String role = currentSession.getUserRole();
        String tenantId = currentSession.getTenantId();
        String branchId = currentSession.getBranchId();
        ClientOutcome<PosSaleDomainClient.FefoAllocationPlanDto> res = saleDomainClient.getFefoPlan(
                token, role, tenantId, branchId, tenantId, branchId, activeServerSale.saleId, false, false
        );
        if (res.data != null) {
            activeFefoPlan = res.data;
            activeServerSale.fefoAllocationStatus = res.data.fefoAllocationStatus;
            updateFefoPlanControls();
        }
        updateStateBanner(res.uiState, res.messageEn, res.messageBn);
    }

    @FXML
    public void handleReserveFefoLots() {
        if (cartItems.isEmpty() || activeServerSale == null) {
            updateStateBanner(UiState.EMPTY,
                    "Open a cart before reserving FEFO batches.",
                    "FEFO ব্যাচ রিজার্ভ করার আগে কার্টে ওষুধ যোগ করুন।");
            return;
        }
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        if (!syncEngine.isOnline() || isOfflineOrderOnly() || token == null || token.isBlank()) {
            updateStateBanner(UiState.OFFLINE,
                    "Offline limitation: batch locking requires server connectivity.",
                    "অফলাইন সীমাবদ্ধতা: সার্ভার ছাড়া FEFO ব্যাচ রিজার্ভেশন লক করা সম্ভব নয়।");
            return;
        }
        String role = currentSession.getUserRole();
        String tenantId = currentSession.getTenantId();
        String branchId = currentSession.getBranchId();
        Map<String, String> overrides = buildBatchOverrideMapFromDrawer();
        String reason = fefoOverrideReasonField != null && fefoOverrideReasonField.getText() != null
                ? fefoOverrideReasonField.getText().trim() : "";

        ClientOutcome<PosSaleOrderDto> res = saleDomainClient.allocateStockFefo(
                token, role, tenantId, branchId, tenantId, branchId, activeServerSale.saleId,
                overrides, reason, activeServerSale.version, false, false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            syncCartFromServerOrder(res.data, null);
            handleRefreshFefoPlan();
            updateStateBanner(UiState.READY, res.messageEn, res.messageBn);
        } else {
            updateStateBanner(res.uiState, res.messageEn, res.messageBn);
            updateFefoPlanControls();
        }
    }

    private String validateOfflineFefoStockSafety() {
        Map<String, FastSearchCandidateDto> candidateByProduct = new LinkedHashMap<>();
        for (CartItem row : cartItems) {
            CartLineMetadata meta = lineMetadataMap.get(row);
            if (meta != null && meta.candidate != null) {
                candidateByProduct.putIfAbsent(row.getMedicineId(), meta.candidate);
            }
        }
        for (Map.Entry<String, FastSearchCandidateDto> entry : candidateByProduct.entrySet()) {
            String prodId = entry.getKey();
            FastSearchCandidateDto cand = entry.getValue();
            int needed = computeCartProductBaseUnits(prodId);
            boolean hasKnownStockTelemetry = cand.availableBaseUnits > 0
                    || cand.recalledBaseUnits > 0
                    || cand.quarantinedBaseUnits > 0
                    || cand.expiredBaseUnits > 0;
            if (!hasKnownStockTelemetry) {
                continue;
            }
            if (cand.recalledBaseUnits > 0 && cand.availableBaseUnits <= 0) {
                return "ERR_BATCH_RECALLED|Cannot dispense '" + cand.brandName + "': batch is recalled.|'" + cand.brandName + "' প্রত্যাহৃত ব্যাচ হওয়ায় বিক্রি নিষিদ্ধ।";
            }
            if (cand.quarantinedBaseUnits > 0 && cand.availableBaseUnits <= 0) {
                return "ERR_BATCH_QUARANTINED|Cannot dispense '" + cand.brandName + "': stock is quarantined.|'" + cand.brandName + "' কোয়ারেন্টিনড স্টক হওয়ায় বিক্রি নিষিদ্ধ।";
            }
            if (cand.expiredBaseUnits > 0 && cand.availableBaseUnits <= 0) {
                return "ERR_BATCH_EXPIRED|Cannot dispense '" + cand.brandName + "': cached branch stock is expired.|'" + cand.brandName + "' মেয়াদোত্তীর্ণ হওয়ায় বিক্রি নিষিদ্ধ।";
            }
            if (needed > cand.availableBaseUnits) {
                return "ERR_INSUFFICIENT_DISPENSABLE_STOCK|Insufficient safe FEFO stock for '" + cand.brandName
                        + "' (needed " + needed + ", available " + cand.availableBaseUnits + ").|'" + cand.brandName
                        + "' এর পর্যাপ্ত নিরাপদ FEFO মজুদ নেই (প্রয়োজন " + needed + ", আছে " + cand.availableBaseUnits + ")।";
            }
        }
        return null;
    }

    private void updateFefoPlanControls() {
        boolean empty = cartItems.isEmpty();
        boolean offline = !syncEngine.isOnline();
        String statusText = activeServerSale != null && !activeServerSale.fefoAllocationStatus.isBlank()
                ? activeServerSale.fefoAllocationStatus
                : (offline && !empty ? "OFFLINE_PROVISIONAL_FEFO" : "PENDING");
        boolean planReady = true;
        StringBuilder summary = new StringBuilder();

        if (empty) {
            statusText = "PENDING";
            summary.append(isBangla ? "কোনো সক্রিয় FEFO লট পরিকল্পনা লোড হয়নি।" : "No active FEFO plan loaded.");
        } else if (activeFefoPlan != null && Objects.equals(activeFefoPlan.saleId,
                activeServerSale != null ? activeServerSale.saleId : "")) {
            statusText = activeFefoPlan.fefoReady ? activeFefoPlan.fefoAllocationStatus : "BLOCKED";
            planReady = activeFefoPlan.fefoReady;
            for (PosSaleDomainClient.FefoPlanLineDto ln : activeFefoPlan.lines) {
                if (!summary.isEmpty()) summary.append(" | ");
                summary.append(ln.brandName).append(": ");
                if (ln.plannedLots.isEmpty()) {
                    summary.append(isBangla ? "নিরাপদ লট নেই" : "No safe lot");
                } else {
                    for (int i = 0; i < ln.plannedLots.size(); i++) {
                        PosSaleDomainClient.FefoPlannedLotDto lot = ln.plannedLots.get(i);
                        if (i > 0) summary.append(" + ");
                        summary.append(lot.batchNumber).append(" (").append(lot.allocatedBaseUnits)
                                .append("u @ ").append(lot.expiryDate).append(")");
                    }
                }
                if (!ln.blockedLots.isEmpty()) {
                    PosSaleDomainClient.FefoBlockedLotDto blk = ln.blockedLots.get(0);
                    summary.append(" [Blocked: ").append(blk.blockCategory).append(" ").append(blk.batchNumber).append("]");
                }
            }
            if (!activeFefoPlan.fefoReady && !activeFefoPlan.blockingReasonEn.isBlank()) {
                summary.append(" -> ").append(isBangla ? activeFefoPlan.blockingReasonBn : activeFefoPlan.blockingReasonEn);
            }
        } else if (activeServerSale != null && !activeServerSale.allocations.isEmpty()) {
            for (int i = 0; i < activeServerSale.allocations.size(); i++) {
                PosSaleDomainClient.PosSaleAllocationDto al = activeServerSale.allocations.get(i);
                if (i > 0) summary.append(", ");
                summary.append(al.batchNumber.isBlank() ? al.batchId : al.batchNumber)
                        .append(" (").append(al.allocatedBaseUnits).append("u ")
                        .append(al.allocationMode).append(")");
            }
        } else if (offline) {
            statusText = "OFFLINE_PROVISIONAL_FEFO";
            String blk = validateOfflineFefoStockSafety();
            if (blk != null) {
                planReady = false;
                statusText = "BLOCKED";
                String[] parts = blk.split("\\|", 3);
                summary.append(isBangla && parts.length >= 3 ? parts[2] : (parts.length >= 2 ? parts[1] : blk));
            } else {
                summary.append(isBangla
                        ? "অফলাইন প্রোভিশনাল FEFO: নিরাপদ ওটিসি সিঙ্ক হলে সার্ভার চূড়ান্ত লট নির্ধারণ করবে।"
                        : "OFFLINE_PROVISIONAL_FEFO: Safe cached OTC stock checked; final server FEFO occurs on reconnect.");
            }
        } else {
            summary.append(isBangla
                    ? "রিজার্ভ বা চেকআউটের সময় সার্ভার স্বয়ংক্রিয়ভাবে FEFO ও FIFO কস্ট লট বরাদ্দ করবে।"
                    : "Server allocates earliest-expiry safe FEFO lots & FIFO cost slices on Reserve/Checkout.");
        }

        if (fefoPlanStatusBadge != null) {
            fefoPlanStatusBadge.setText(statusText);
            if (!planReady || "BLOCKED".equals(statusText)) {
                fefoPlanStatusBadge.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #991B1B; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-weight: bold;");
            } else if ("COMMITTED".equals(statusText) || "RESERVED".equals(statusText)) {
                fefoPlanStatusBadge.setStyle("-fx-background-color: #D1E7DD; -fx-text-fill: #0F5132; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-weight: bold;");
            } else {
                fefoPlanStatusBadge.setStyle("-fx-background-color: #E0F2FE; -fx-text-fill: #075985; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-weight: bold;");
            }
        }
        if (btnFefoLots != null) {
            if (!planReady) {
                btnFefoLots.setText(isBangla ? "FEFO ব্লক (!) [F7]" : "FEFO Blocked (!) [F7]");
            } else if ("RESERVED".equals(statusText) || "COMMITTED".equals(statusText)) {
                btnFefoLots.setText(isBangla ? "FEFO রিজার্ভড [F7]" : "FEFO Reserved [F7]");
            } else {
                btnFefoLots.setText(isBangla ? "FEFO লট [F7]" : "FEFO Lots [F7]");
            }
        }
        if (fefoPlanSummaryLabel != null) {
            fefoPlanSummaryLabel.setText(summary.toString());
        }
    }

    // -------------------------------------------------------------------------
    // P229: Multi-Tender Split Payments (Cash, bKash, Nagad, Rocket, Upay, Card, Due)
    //       & Audited Cashier Shift Close Reconciliation
    // -------------------------------------------------------------------------

    private void configureSplitPaymentControls() {
        setBtnIcon(btnSplitPayments, Feather.CHECK_SQUARE, 13, "#0F766E");
        setBtnIcon(btnRefreshCloseRecon, Feather.REFRESH_CW, 12, "#334155");
        setBtnIcon(btnCloseSplitPayDrawer, Feather.X_CIRCLE, 12, "#64748B");
        setBtnIcon(btnAddSplitLeg, Feather.PLUS, 12, "#0F766E");
        setBtnIcon(btnClearSplitLegs, Feather.TRASH_2, 12, "#B42318");
        setBtnIcon(btnSubmitSplitPayments, Feather.CHECK_CIRCLE, 12, "#FFFFFF");
        setBtnIcon(btnSubmitCloseRecon, Feather.CHECK_CIRCLE, 12, "#0F766E");
        setBtnIcon(btnRefreshCustomers, Feather.REFRESH_CW, 12, "#334155");
        setBtnIcon(btnRefreshCustomerDues, Feather.BOOK_OPEN, 12, "#334155");
        setBtnIcon(btnCollectCustomerDue, Feather.CHECK_CIRCLE, 12, "#FFFFFF");
        if (splitTenderCombo != null) {
            splitTenderCombo.setItems(FXCollections.observableArrayList(
                    "CASH", "BKASH", "NAGAD", "ROCKET", "UPAY", "CARD", "BANK_TRANSFER", "DUE"
            ));
            splitTenderCombo.getSelectionModel().select("CASH");
        }
        if (dueCollectTenderCombo != null) {
            dueCollectTenderCombo.setItems(FXCollections.observableArrayList(
                    "CASH", "BKASH", "NAGAD", "ROCKET", "UPAY", "CARD", "BANK_TRANSFER"
            ));
            dueCollectTenderCombo.getSelectionModel().select("CASH");
        }
    }

    private void setSplitPayDrawerVisible(boolean visible) {
        if (splitPaymentDrawer != null) {
            splitPaymentDrawer.setVisible(visible);
            splitPaymentDrawer.setManaged(visible);
        }
    }

    @FXML
    public void handleToggleSplitPayDrawer() {
        boolean show = splitPaymentDrawer == null || !splitPaymentDrawer.isVisible();
        setSplitPayDrawerVisible(show);
        updateSplitPayControls();
        if (show && syncEngine.isOnline() && currentSession != null
                && !currentSession.getDeviceToken().isBlank()) {
            handleRefreshCloseReconciliation();
        }
    }

    @FXML
    public void handleAddSplitPaymentLeg() {
        String tender = splitTenderCombo != null && splitTenderCombo.getValue() != null
                ? splitTenderCombo.getValue().trim().toUpperCase(Locale.ROOT)
                : "CASH";
        String amtStr = splitAmountField != null && splitAmountField.getText() != null
                ? splitAmountField.getText().trim() : "";
        BigDecimal amt;
        try {
            amt = new BigDecimal(amtStr).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ex) {
            updateStateBanner(UiState.ERROR,
                    "ERR_INVALID_PAYMENT_AMOUNT: Enter a valid positive decimal BDT amount.",
                    "সঠিক ধনাত্মক টাকা পরিমাণ লিখুন।");
            return;
        }
        if (amt.compareTo(BigDecimal.ZERO) <= 0) {
            updateStateBanner(UiState.ERROR,
                    "ERR_INVALID_PAYMENT_AMOUNT: Split payment amount must exceed 0.00 BDT.",
                    "পেমেন্টের পরিমাণ ০.০০ টাকার বেশি হতে হবে।");
            return;
        }
        String gwRef = splitRefField != null && splitRefField.getText() != null
                ? splitRefField.getText().trim() : "";
        String payerOrCard = splitPayerField != null && splitPayerField.getText() != null
                ? splitPayerField.getText().trim() : "";

        boolean isDigital = !"CASH".equals(tender) && !"DUE".equals(tender);
        if (isDigital && gwRef.length() < 4) {
            updateStateBanner(UiState.ERROR,
                    "ERR_DIGITAL_TENDER_REFERENCE_REQUIRED: Enter " + tender + " TrxID / Card Auth ref (>= 4 chars).",
                    tender + " পেমেন্টের জন্য কমপক্ষে ৪ অক্ষরের TrxID বা কার্ড রেফারেন্স দিন।");
            return;
        }
        if (isDigital && !syncEngine.isOnline() && gwRef.length() < 4) {
            updateStateBanner(UiState.OFFLINE,
                    "ERR_OFFLINE_DIGITAL_PAYMENT_REQUIRES_REF: Digital tenders require gateway online or terminal TrxID.",
                    "অফলাইন সীমাবদ্ধতা: ডিজিটাল পেমেন্টে টার্মিনাল রেফারেন্স আবশ্যক।");
            return;
        }
        if ("DUE".equals(tender)) {
            if (!syncEngine.isOnline()) {
                updateStateBanner(UiState.OFFLINE,
                        "ERR_OFFLINE_CREDIT_UNAVAILABLE: DUE credit sales require online customer approval & limit verification.",
                        "অফলাইন সীমাবদ্ধতা: কাস্টমার বাকি বিক্রয়ের জন্য অনলাইন ক্রেডিট যাচাই আবশ্যক।");
                return;
            }
            if (activeCustomerDto == null) {
                updateStateBanner(UiState.ERROR,
                        "ERR_APPROVED_CUSTOMER_REQUIRED: Select an approved credit customer before adding a DUE leg.",
                        "বাকি বিক্রয়ের আগে অনুমোদিত গ্রাহক নির্বাচন করুন।");
                return;
            }
            if (!activeCustomerDto.isCreditApproved || !"APPROVED".equalsIgnoreCase(activeCustomerDto.creditStatus)) {
                updateStateBanner(UiState.ERROR,
                        "ERR_CUSTOMER_CREDIT_NOT_APPROVED: Selected customer is not approved for DUE credit sales (" + activeCustomerDto.creditStatus + ").",
                        "নির্বাচিত গ্রাহকের ক্রেডিট অনুমোদন নেই।");
                return;
            }
            BigDecimal existingDue = BigDecimal.ZERO;
            for (PosSaleDomainClient.SplitPaymentInputDto s : stagedSplitPayments) {
                if ("DUE".equalsIgnoreCase(s.tenderType)) existingDue = existingDue.add(s.amountBdt);
            }
            if (existingDue.add(amt).compareTo(activeCustomerDto.availableCreditBdt) > 0) {
                updateStateBanner(UiState.ERROR,
                        "ERR_CUSTOMER_CREDIT_LIMIT_EXCEEDED: Exceeds available credit (" + activeCustomerDto.availableCreditBdt.toPlainString() + " BDT).",
                        "উপলব্ধ ক্রেডিট লিমিট (" + activeCustomerDto.availableCreditBdt.toPlainString() + " টাকা) অতিক্রম করেছে।");
                return;
            }
        }

        BigDecimal grandTotal = computeCurrentPreviewGrandTotal();
        if (!"CASH".equals(tender) && !cartItems.isEmpty()) {
            BigDecimal existingNonCash = BigDecimal.ZERO;
            for (PosSaleDomainClient.SplitPaymentInputDto s : stagedSplitPayments) {
                if (!"CASH".equalsIgnoreCase(s.tenderType)) {
                    existingNonCash = existingNonCash.add(s.amountBdt);
                }
            }
            if (existingNonCash.add(amt).compareTo(grandTotal) > 0) {
                updateStateBanner(UiState.ERROR,
                        "ERR_NON_CASH_OVERPAYMENT: Non-cash/Due legs cannot exceed grand total (" + grandTotal.toPlainString() + " BDT).",
                        "নগদ ছাড়া অন্যান্য পেমেন্ট মোট বিল (" + grandTotal.toPlainString() + " টাকা) অতিক্রম করতে পারবে না।");
                return;
            }
        }

        PosSaleDomainClient.SplitPaymentInputDto leg = new PosSaleDomainClient.SplitPaymentInputDto(tender, amt, gwRef, "");
        if ("CARD".equals(tender) && payerOrCard.matches("\\d{4}")) {
            leg.cardLast4 = payerOrCard;
        } else {
            leg.payerReference = payerOrCard;
        }
        leg.idempotencyKey = "sp-" + UUID.randomUUID();
        stagedSplitPayments.add(leg);
        if (splitAmountField != null) splitAmountField.clear();
        if (splitRefField != null) splitRefField.clear();
        if (splitPayerField != null) splitPayerField.clear();
        updateSplitPayControls();
        updateStateBanner(UiState.READY,
                "Added " + tender + " BDT " + amt.toPlainString() + " split leg.",
                tender + " " + amt.toPlainString() + " টাকা পেমেন্ট লাইন যোগ হয়েছে।");
    }

    @FXML
    public void handleClearSplitPaymentLegs() {
        stagedSplitPayments.clear();
        updateSplitPayControls();
        updateStateBanner(UiState.EMPTY,
                "Cleared staged split legs; Checkout will settle exact Cash.",
                "স্প্লিট পেমেন্ট লাইন পরিষ্কার করা হয়েছে।");
    }

    @FXML
    public void handleSubmitSplitPayments() {
        if (cartItems.isEmpty()) {
            updateStateBanner(UiState.EMPTY,
                    "Add items before posting split payments.",
                    "পেমেন্ট পোস্ট করার আগে কার্টে ওষুধ যোগ করুন।");
            return;
        }
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        boolean offline = !syncEngine.isOnline();
        if (offline || isOfflineOrderOnly() || token == null || token.isBlank()) {
            String block = validateOfflineSplitPayments();
            if (block != null) {
                String[] parts = block.split("\\|", 3);
                updateStateBanner(UiState.OFFLINE,
                        parts[0] + ": " + (parts.length >= 2 ? parts[1] : block),
                        parts.length >= 3 ? parts[2] : block);
                return;
            }
            updateSplitPayControls();
            updateStateBanner(UiState.OFFLINE,
                    "Offline split tender validated locally (OFFLINE_PROVISIONAL_PAYMENT); submit Checkout [F4] to queue.",
                    "অফলাইন স্প্লিট পেমেন্ট যাচাই সম্পন্ন; কিউ করতে চেকআউট [F4] চাপুন।");
            return;
        }
        List<PosSaleDomainClient.SplitPaymentInputDto> toSend = new ArrayList<>(stagedSplitPayments);
        if (toSend.isEmpty()) {
            BigDecimal due = computeCurrentPreviewGrandTotal();
            if (due.compareTo(BigDecimal.ZERO) > 0) {
                toSend.add(new PosSaleDomainClient.SplitPaymentInputDto("CASH", due, "", ""));
            }
        }
        String role = currentSession.getUserRole();
        String tenantId = currentSession.getTenantId();
        String branchId = currentSession.getBranchId();
        ClientOutcome<PosSaleOrderDto> res = saleDomainClient.submitSplitPayments(
                token, role, tenantId, branchId, tenantId, branchId,
                activeServerSale.saleId, toSend, "batch-" + UUID.randomUUID(),
                true, false, false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            stagedSplitPayments.clear();
            syncCartFromServerOrder(res.data, null);
            updateSplitPayControls();
        }
        updateStateBanner(res.uiState, res.messageEn, res.messageBn);
    }

    @FXML
    public void handleRefreshCloseReconciliation() {
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        if (!syncEngine.isOnline() || token == null || token.isBlank()) {
            updateSplitPayControls();
            updateStateBanner(UiState.OFFLINE,
                    "Offline limitation: live shift tender reconciliation requires cloud connectivity.",
                    "অফলাইন সীমাবদ্ধতা: শিফট ক্যাশ রিকনসিলিয়েশন দেখতে অনলাইন সংযোগ প্রয়োজন।");
            return;
        }
        String role = currentSession.getUserRole();
        String tenantId = currentSession.getTenantId();
        String branchId = currentSession.getBranchId();
        ClientOutcome<PosSaleDomainClient.CashierCloseReconciliationDto> res = saleDomainClient.getCloseReconciliation(
                token, role, tenantId, branchId, tenantId, branchId, "", false, false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            lastCloseReconciliation = res.data;
            updateSplitPayControls();
        }
        updateStateBanner(res.uiState, res.messageEn, res.messageBn);
    }

    @FXML
    public void handleSubmitCloseReconciliation() {
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        if (!syncEngine.isOnline() || token == null || token.isBlank()) {
            updateStateBanner(UiState.OFFLINE,
                    "Offline limitation: closing register shift requires cloud reconciliation.",
                    "অফলাইন সীমাবদ্ধতা: শিফট ক্লোজ করার জন্য সার্ভার সংযোগ প্রয়োজন।");
            return;
        }
        String rawCounted = closeCountedCashField != null && closeCountedCashField.getText() != null
                ? closeCountedCashField.getText().trim() : "";
        BigDecimal counted;
        try {
            counted = new BigDecimal(rawCounted).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ex) {
            updateStateBanner(UiState.ERROR,
                    "ERR_COUNTED_CASH_REQUIRED: Enter physical counted cash (BDT) before closing drawer.",
                    "ড্রয়ার বন্ধের আগে গননাকৃত নগদ টাকা (BDT) লিখুন।");
            return;
        }
        String notes = closeVarianceNotesField != null && closeVarianceNotesField.getText() != null
                ? closeVarianceNotesField.getText().trim() : "";
        String role = currentSession.getUserRole();
        String tenantId = currentSession.getTenantId();
        String branchId = currentSession.getBranchId();
        ClientOutcome<PosSaleDomainClient.CashierCloseReconciliationDto> res = saleDomainClient.submitCloseReconciliation(
                token, role, tenantId, branchId, tenantId, branchId, "", counted, notes, false, false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            lastCloseReconciliation = res.data;
            if (closeCountedCashField != null) closeCountedCashField.clear();
            if (closeVarianceNotesField != null) closeVarianceNotesField.clear();
            updateSplitPayControls();
        }
        updateStateBanner(res.uiState, res.messageEn, res.messageBn);
    }

    private String validateOfflineSplitPayments() {
        BigDecimal grandTotal = computeCurrentPreviewGrandTotal();
        BigDecimal nonCash = BigDecimal.ZERO;
        for (PosSaleDomainClient.SplitPaymentInputDto s : stagedSplitPayments) {
            String tender = s.tenderType != null ? s.tenderType.toUpperCase(Locale.ROOT) : "CASH";
            if ("DUE".equals(tender)) {
                return "ERR_OFFLINE_CREDIT_UNAVAILABLE|"
                        + "DUE credit sales require online customer credit limit verification; pay Cash while offline.|"
                        + "অফলাইন সীমাবদ্ধতা: কাস্টমার বাকি বিক্রয় অনলাইন ছাড়া অনুমোদিত নয়; ক্যাশ পেমেন্ট করুন।";
            }
            if (!"CASH".equals(tender) && !"DUE".equals(tender)) {
                if (s.gatewayReference == null || s.gatewayReference.trim().length() < 4) {
                    return "ERR_OFFLINE_DIGITAL_PAYMENT_REQUIRES_REF|"
                            + tender + " offline payment requires verified TrxID/auth ref (>= 4 chars) or switch to Cash.|"
                            + tender + " অফলাইন পেমেন্টে ভেরিফায়েড রেফারেন্স আবশ্যক বা ক্যাশ ব্যবহার করুন।";
                }
            }
            if (!"CASH".equals(tender)) {
                nonCash = nonCash.add(s.amountBdt);
            }
        }
        if (nonCash.compareTo(grandTotal) > 0 && !cartItems.isEmpty()) {
            return "ERR_NON_CASH_OVERPAYMENT|Non-cash split legs exceed cart total.|নন-ক্যাশ পেমেন্ট মোট বিলের চেয়ে বেশি হতে পারবে না।";
        }
        return null;
    }

    private void updateSplitPayControls() {
        BigDecimal grandTotal = computeCurrentPreviewGrandTotal();
        BigDecimal stagedSum = BigDecimal.ZERO;
        BigDecimal stagedCash = BigDecimal.ZERO;
        BigDecimal serverPaid = activeServerSale != null
                ? activeServerSale.paidAmountBdt.add(activeServerSale.dueAmountBdt)
                : BigDecimal.ZERO;
        StringBuilder desc = new StringBuilder();

        if (activeServerSale != null && !activeServerSale.paymentAttempts.isEmpty()) {
            for (PosSaleDomainClient.PosPaymentAttemptDto a : activeServerSale.paymentAttempts) {
                if ("VOIDED".equalsIgnoreCase(a.status) || "REFUNDED".equalsIgnoreCase(a.status)) continue;
                if (!desc.isEmpty()) desc.append(" + ");
                desc.append(a.tenderType).append(" ").append(a.amountBdt.toPlainString());
                if (a.changeGivenBdt.compareTo(BigDecimal.ZERO) > 0) {
                    desc.append(" (chg ").append(a.changeGivenBdt.toPlainString()).append(")");
                }
            }
        }
        for (PosSaleDomainClient.SplitPaymentInputDto s : stagedSplitPayments) {
            stagedSum = stagedSum.add(s.amountBdt);
            if ("CASH".equalsIgnoreCase(s.tenderType)) stagedCash = stagedCash.add(s.amountBdt);
            if (!desc.isEmpty()) desc.append(" + ");
            desc.append(s.tenderType).append(" ").append(s.amountBdt.toPlainString());
            if (!s.gatewayReference.isBlank()) desc.append(" [").append(s.gatewayReference).append("]");
        }

        BigDecimal effectiveTotal = serverPaid.add(stagedSum);
        BigDecimal change = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (effectiveTotal.compareTo(grandTotal) > 0 && stagedCash.compareTo(BigDecimal.ZERO) > 0 && grandTotal.compareTo(BigDecimal.ZERO) > 0) {
            change = effectiveTotal.subtract(grandTotal).min(stagedCash).setScale(2, RoundingMode.HALF_UP);
        } else if (activeServerSale != null && activeServerSale.changeReturnedBdt.compareTo(BigDecimal.ZERO) > 0) {
            change = activeServerSale.changeReturnedBdt;
        }
        BigDecimal remaining = grandTotal.subtract(effectiveTotal).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        if (btnSplitPayments != null) {
            int cnt = stagedSplitPayments.size() + (activeServerSale != null ? activeServerSale.paymentAttempts.size() : 0);
            btnSplitPayments.setText(isBangla
                    ? ("স্প্লিট পে (" + cnt + ") [F6]")
                    : ("Split Pay (" + cnt + ") [F6]"));
        }
        if (splitPayStatusBadge != null) {
            if (!stagedSplitPayments.isEmpty()) {
                splitPayStatusBadge.setText("STAGED (" + stagedSplitPayments.size() + ")");
            } else if (serverPaid.compareTo(BigDecimal.ZERO) > 0) {
                splitPayStatusBadge.setText("POSTED");
            } else {
                splitPayStatusBadge.setText("CASH DEFAULT");
            }
        }
        if (splitPaySummaryLabel != null) {
            if (desc.isEmpty()) {
                splitPaySummaryLabel.setText(isBangla
                        ? "কোনো স্প্লিট লাইন যোগ করা হয়নি; চেকআউট সরাসরি ক্যাশ হিসেবে সম্পন্ন হবে।"
                        : "No split legs staged; Checkout settles exact Cash (" + grandTotal.toPlainString() + " BDT).");
            } else {
                splitPaySummaryLabel.setText(String.format(
                        "%s | Tendered: BDT %s | Rem: BDT %s | Change: BDT %s",
                        desc, effectiveTotal.toPlainString(), remaining.toPlainString(), change.toPlainString()
                ));
            }
        }
        if (closeReconSummaryLabel != null) {
            if (lastCloseReconciliation == null) {
                closeReconSummaryLabel.setText(isBangla
                        ? "শিফট মোট হিসাব দেখতে 'Shift Totals' চাপুন।"
                        : "Click Shift Totals to inspect expected drawer cash, bKash/Nagad/Card sums & variance.");
            } else {
                closeReconSummaryLabel.setText(String.format(
                        "Float: %s | Gross Cash: %s | Change: %s | Net Cash: %s | Due Col Cash: %s | Due Col Digital: %s | Refund: %s | Expected Drawer: %s | Digital: %s | %s (Var: %s)",
                        lastCloseReconciliation.openingFloatBdt.toPlainString(),
                        lastCloseReconciliation.grossCashTenderedBdt.toPlainString(),
                        lastCloseReconciliation.cashChangeReturnedBdt.toPlainString(),
                        lastCloseReconciliation.netCashCollectedBdt.toPlainString(),
                        lastCloseReconciliation.dueCollectedCashBdt.toPlainString(),
                        lastCloseReconciliation.dueCollectedDigitalBdt.toPlainString(),
                        lastCloseReconciliation.cashRefundedBdt.toPlainString(),
                        lastCloseReconciliation.expectedDrawerCashBdt.toPlainString(),
                        lastCloseReconciliation.digitalTotalBdt.toPlainString(),
                        lastCloseReconciliation.status,
                        lastCloseReconciliation.varianceBdt.toPlainString()
                ));
            }
        }
        if (splitCustomerCreditBadge != null) {
            if (activeCustomerDto == null) {
                splitCustomerCreditBadge.setText(isBangla ? "ওয়াক-ইন (বাকি নিষ্ক্রিয়)" : "WALK-IN (DUE DISABLED)");
            } else {
                splitCustomerCreditBadge.setText(String.format(
                        "%s | %s | Avail: %s BDT",
                        activeCustomerDto.fullName,
                        activeCustomerDto.creditStatus,
                        activeCustomerDto.availableCreditBdt.toPlainString()
                ));
            }
        }
    }

    @FXML
    public void handleRefreshCustomers() {
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        if (!syncEngine.isOnline() || token == null || token.isBlank()) {
            updateStateBanner(UiState.OFFLINE,
                    "Offline limitation: customer credit directory requires online server sync.",
                    "অফলাইন সীমাবদ্ধতা: ক্রেডিট গ্রাহক তালিকা লোড করতে অনলাইন সংযোগ প্রয়োজন।");
            return;
        }
        ClientOutcome<List<PosSaleDomainClient.PosCustomerDto>> res = saleDomainClient.listCustomers(
                token, currentSession.getUserRole(), currentSession.getTenantId(), currentSession.getBranchId(),
                currentSession.getTenantId(), currentSession.getBranchId(), false, false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            cachedCustomers.clear();
            cachedCustomers.addAll(res.data);
            if (splitCustomerCombo != null) {
                ObservableList<String> labels = FXCollections.observableArrayList();
                for (PosSaleDomainClient.PosCustomerDto c : cachedCustomers) {
                    labels.add(c.customerId + " | " + c.fullName + " (" + c.creditStatus + ", Avail: " + c.availableCreditBdt.toPlainString() + ")");
                }
                splitCustomerCombo.setItems(labels);
            }
        }
        updateStateBanner(res.uiState, res.messageEn, res.messageBn);
    }

    @FXML
    public void handleSelectCustomerForSale() {
        if (splitCustomerCombo == null || splitCustomerCombo.getValue() == null) return;
        String sel = splitCustomerCombo.getValue().trim();
        int sep = sel.indexOf('|');
        String custId = (sep > 0 ? sel.substring(0, sep) : sel).trim();
        PosSaleDomainClient.PosCustomerDto found = null;
        for (PosSaleDomainClient.PosCustomerDto c : cachedCustomers) {
            if (c.customerId.equals(custId)) {
                found = c;
                break;
            }
        }
        if (found == null) return;
        activeCustomerDto = found;
        if (syncEngine.isOnline() && activeServerSale != null && currentSession != null
                && !currentSession.getDeviceToken().isBlank()) {
            ClientOutcome<PosSaleOrderDto> att = saleDomainClient.attachCustomerToSale(
                    currentSession.getDeviceToken(), currentSession.getUserRole(),
                    currentSession.getTenantId(), currentSession.getBranchId(),
                    currentSession.getTenantId(), currentSession.getBranchId(),
                    activeServerSale.saleId, found.customerId, false, false
            );
            if (att.uiState == UiState.READY && att.data != null) {
                activeServerSale = att.data;
            }
        }
        updateSplitPayControls();
        handleRefreshCustomerDues();
    }

    @FXML
    public void handleRefreshCustomerDues() {
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        if (!syncEngine.isOnline() || token == null || token.isBlank()) {
            updateStateBanner(UiState.OFFLINE,
                    "Offline limitation: open due receivables require online server lookup.",
                    "অফলাইন সীমাবদ্ধতা: বকেয়া তালিকা দেখতে অনলাইন সংযোগ প্রয়োজন।");
            return;
        }
        String cid = activeCustomerDto != null ? activeCustomerDto.customerId : "";
        ClientOutcome<List<PosSaleDomainClient.CustomerDueRecordDto>> res = saleDomainClient.listCustomerDues(
                token, currentSession.getUserRole(), currentSession.getTenantId(), currentSession.getBranchId(),
                currentSession.getTenantId(), currentSession.getBranchId(), cid, false, false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            cachedCustomerDues.clear();
            cachedCustomerDues.addAll(res.data);
            if (dueRecordCombo != null) {
                ObservableList<String> items = FXCollections.observableArrayList();
                for (PosSaleDomainClient.CustomerDueRecordDto d : cachedCustomerDues) {
                    if (d.remainingDueBdt.compareTo(BigDecimal.ZERO) > 0) {
                        items.add(d.dueId + " | " + d.customerName + " (Rem: " + d.remainingDueBdt.toPlainString() + " BDT)");
                    }
                }
                dueRecordCombo.setItems(items);
                if (!items.isEmpty()) dueRecordCombo.getSelectionModel().selectFirst();
            }
        }
        updateStateBanner(res.uiState, res.messageEn, res.messageBn);
    }

    @FXML
    public void handleCollectCustomerDue() {
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        if (!syncEngine.isOnline() || token == null || token.isBlank()) {
            updateStateBanner(UiState.OFFLINE,
                    "ERR_OFFLINE_UNAVAILABLE: Due collection requires live server AR sync.",
                    "অফলাইন সীমাবদ্ধতা: বাকি আদায়ের জন্য অনলাইন সার্ভার সংযোগ প্রয়োজন।");
            return;
        }
        if (dueRecordCombo == null || dueRecordCombo.getValue() == null || dueRecordCombo.getValue().isBlank()) {
            updateStateBanner(UiState.EMPTY,
                    "Select an open due record before collecting payment.",
                    "বাকি আদায়ের আগে একটি বকেয়া রেকর্ড নির্বাচন করুন।");
            return;
        }
        String rawDue = dueRecordCombo.getValue().trim();
        int sep = rawDue.indexOf('|');
        String dueId = (sep > 0 ? rawDue.substring(0, sep) : rawDue).trim();
        String tender = dueCollectTenderCombo != null && dueCollectTenderCombo.getValue() != null
                ? dueCollectTenderCombo.getValue().trim().toUpperCase(Locale.ROOT)
                : "CASH";
        String rawAmt = dueCollectAmountField != null && dueCollectAmountField.getText() != null
                ? dueCollectAmountField.getText().trim() : "";
        BigDecimal amt;
        try {
            amt = new BigDecimal(rawAmt).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ex) {
            updateStateBanner(UiState.ERROR,
                    "ERR_INVALID_PAYMENT_AMOUNT: Enter a valid decimal collection amount (BDT).",
                    "সঠিক টাকা পরিমাণ লিখুন।");
            return;
        }
        String gwRef = dueCollectRefField != null && dueCollectRefField.getText() != null
                ? dueCollectRefField.getText().trim() : "";
        ClientOutcome<PosSaleDomainClient.DueCollectionOutcomeDto> res = saleDomainClient.collectCustomerDue(
                token, currentSession.getUserRole(), currentSession.getTenantId(), currentSession.getBranchId(),
                currentSession.getTenantId(), currentSession.getBranchId(),
                dueId, tender, amt, null, gwRef, "dcol-" + UUID.randomUUID(), "POS drawer due collection",
                false, false
        );
        if (res.uiState == UiState.READY && res.data != null) {
            if (dueCollectAmountField != null) dueCollectAmountField.clear();
            if (dueCollectRefField != null) dueCollectRefField.clear();
            if (activeCustomerDto != null && activeCustomerDto.customerId.equals(res.data.dueRecord.customerId)) {
                activeCustomerDto.currentDueBdt = activeCustomerDto.currentDueBdt.subtract(res.data.collection.amountBdt)
                        .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
                activeCustomerDto.availableCreditBdt = activeCustomerDto.creditLimitBdt.subtract(activeCustomerDto.currentDueBdt)
                        .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            }
            handleRefreshCustomerDues();
            handleRefreshCloseReconciliation();
        }
        updateStateBanner(res.uiState, res.messageEn, res.messageBn);
    }

    private void clearDeliveryDraftInputs() {
        if (deliveryRecipientField != null) deliveryRecipientField.clear();
        if (deliveryPhoneField != null) deliveryPhoneField.clear();
        if (deliveryAddressField != null) deliveryAddressField.clear();
        if (deliveryAreaField != null) deliveryAreaField.clear();
        if (deliveryFeeField != null) deliveryFeeField.clear();
    }

    private PosDeliveryOrderDto getSelectedDeliveryOrSingle() {
        if (deliveriesList != null) {
            PosDeliveryOrderDto sel = deliveriesList.getSelectionModel().getSelectedItem();
            if (sel != null) {
                return sel;
            }
        }
        if (deliveryEntries.size() == 1) {
            return deliveryEntries.get(0);
        }
        return null;
    }

    private void upsertDeliveryEntry(PosDeliveryOrderDto dto) {
        if (dto == null) return;
        for (int i = 0; i < deliveryEntries.size(); i++) {
            if (Objects.equals(deliveryEntries.get(i).deliveryId, dto.deliveryId)) {
                deliveryEntries.set(i, dto);
                return;
            }
        }
        deliveryEntries.add(0, dto);
    }

    private void updateDeliveryCountBadge() {
        int serverCount = 0;
        for (PosDeliveryOrderDto item : deliveryEntries) {
            if (!item.offlineLocalOnly) {
                serverCount++;
            }
        }
        int total = Math.max(lastKnownServerDeliveryCount, serverCount) + offlineDeliveryOrders.size();
        boolean hasEntries = !deliveryEntries.isEmpty();
        if (btnDeliveries != null) {
            btnDeliveries.setText(isBangla
                    ? ("ডেলিভারি (" + total + ") [F11]")
                    : ("Deliveries (" + total + ") [F11]"));
        }
        if (deliveryDrawerSummaryLabel != null) {
            deliveryDrawerSummaryLabel.setText(isBangla
                    ? (total + " অর্ডার | " + offlineDeliveryOrders.size() + " অফলাইন")
                    : (total + " queued | " + offlineDeliveryOrders.size() + " offline"));
        }
        if (deliveriesEmptyLabel != null) {
            deliveriesEmptyLabel.setVisible(!hasEntries);
            deliveriesEmptyLabel.setManaged(!hasEntries);
            deliveriesEmptyLabel.setText(isBangla
                    ? "এখনো কোনো প্রি-অর্ডার বা ডেলিভারি অর্ডার নেই।"
                    : "No preorder or delivery orders queued yet.");
        }
    }

    private void updateDeliveryDetailPanel() {
        if (deliveryDetailLabel == null) return;
        PosDeliveryOrderDto sel = getSelectedDeliveryOrSingle();
        if (sel == null) {
            deliveryDetailLabel.setText(isBangla
                    ? "একটি ডেলিভারি অর্ডার নির্বাচন করে ঠিকানা প্রাইভেসি বা স্ট্যাটাস পরিচালনা করুন।"
                    : "Select a preorder or delivery order to manage packing, dispatch, or privacy reveal.");
            return;
        }
        String privacyText = sel.addressPrivacyMasked
                ? (isBangla ? "PII মাস্কড (অডিটেড রিভিল প্রযোজ্য)" : "PII Masked (use Reveal Address for audited dispatch lookup)")
                : (isBangla ? "পূর্ণ ঠিকানা উন্মোচিত" : "Full Address Visible");
        deliveryDetailLabel.setText(String.format(
                "%s | %s | %s (%s) | Address: %s | Area: %s | Fee: BDT %s | %s",
                sel.deliveryNumber.isBlank() ? sel.deliveryId : sel.deliveryNumber,
                sel.fulfillmentState,
                sel.recipientName,
                sel.recipientPhone,
                sel.deliveryAddressLine.isBlank() ? "[Protected / Redacted]" : sel.deliveryAddressLine,
                sel.deliveryArea.isBlank() ? sel.deliveryCity : sel.deliveryArea,
                sel.deliveryFeeBdt.toPlainString(),
                privacyText
        ));
    }

    private void clearActiveCounterForNextCustomer() {
        cartItems.clear();
        lineMetadataMap.clear();
        activeServerSale = null;
        activeRxGateStatus = null;
        activeFefoPlan = null;
        stagedSplitPayments.clear();
        activeCustomerDto = null;
        clearStaleConfirmationPrompt();
        if (dueCollectAmountField != null) dueCollectAmountField.clear();
        if (dueCollectRefField != null) dueCollectRefField.clear();
        if (holdReferenceField != null) holdReferenceField.clear();
        if (holdTtlField != null) holdTtlField.clear();
        if (rxDoctorNameField != null) rxDoctorNameField.clear();
        if (rxDoctorBmdcField != null) rxDoctorBmdcField.clear();
        if (rxPatientTokenField != null) rxPatientTokenField.clear();
        if (rxReferenceField != null) rxReferenceField.clear();
        if (rxDecisionNotesField != null) rxDecisionNotesField.clear();
        if (fefoOverrideBatchField != null) fefoOverrideBatchField.clear();
        if (fefoOverrideReasonField != null) fefoOverrideReasonField.clear();
        if (splitAmountField != null) splitAmountField.clear();
        if (splitRefField != null) splitRefField.clear();
        if (splitPayerField != null) splitPayerField.clear();
        setRxGateDrawerVisible(false);
        setFefoPlanDrawerVisible(false);
        setSplitPayDrawerVisible(false);
        resetPricingState();
        updateTotals();
    }

    private BigDecimal computeCurrentPreviewGrandTotal() {
        if (activeServerSale != null && activeServerSale.lines.size() == cartItems.size() && !cartItems.isEmpty()) {
            return activeServerSale.grandTotalBdt.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal localSub = BigDecimal.ZERO;
        BigDecimal localVat = BigDecimal.ZERO;
        for (CartItem item : cartItems) {
            localSub = localSub.add(item.getLineSubtotal());
            localVat = localVat.add(item.getLineVat());
        }
        BigDecimal subtotal = localSub.setScale(2, RoundingMode.HALF_UP);
        BigDecimal vat = localVat.setScale(2, RoundingMode.HALF_UP);
        BigDecimal discountAmt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if ("PERCENTAGE".equalsIgnoreCase(selectedDiscountType)) {
            discountAmt = subtotal.multiply(requestedDiscountValue)
                    .divide(new BigDecimal("100.00"), 2, RoundingMode.HALF_UP);
        } else if ("FLAT_BDT".equalsIgnoreCase(selectedDiscountType) || "PROMO_POLICY".equalsIgnoreCase(selectedDiscountType)) {
            discountAmt = requestedDiscountValue.min(subtotal).max(BigDecimal.ZERO);
        }
        BigDecimal preRound = subtotal.subtract(discountAmt).add(vat).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal roundingAdj = cartItems.isEmpty()
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : computeLocalRoundingAdjustment(preRound, selectedRoundingMode);
        return preRound.add(roundingAdj).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private void resetPricingState() {
        this.selectedDiscountType = "NONE";
        this.requestedDiscountValue = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.selectedRoundingMode = "NONE";
        updatingPricingCombos = true;
        try {
            if (discountTypeCombo != null) discountTypeCombo.getSelectionModel().select("NONE");
            if (roundingModeCombo != null) roundingModeCombo.getSelectionModel().select("NONE");
            if (discountValueField != null) discountValueField.clear();
        } finally {
            updatingPricingCombos = false;
        }
    }

    @FXML
    public void handleClearSearch() {
        if (barcodeInputField != null) {
            barcodeInputField.clear();
            barcodeInputField.requestFocus();
        }
        searchCandidates.clear();
        setResultsDrawerVisible(false);
        if (searchLatencyLabel != null) {
            searchLatencyLabel.setText("");
        }
        updateStateBanner(
                cartItems.isEmpty() ? UiState.EMPTY : UiState.READY,
                "Ready for barcode scan or brand/generic search [F2].",
                "বারকোড স্ক্যান বা ওষুধের নাম লিখে সার্চ করতে [F2] চাপুন।"
        );
    }

    @FXML
    public void handleSyncOfflineCache() {
        String branchId = currentSession != null ? currentSession.getBranchId() : "branch-main";
        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        ClientOutcome<List<FastSearchCandidateDto>> res = searchCartClient.refreshLocalSnapshotCache(branchId, token);
        updateStateBanner(res.uiState, res.messageEn, res.messageBn);
    }

    @FXML
    public void handleToggleOffline() {
        syncEngine.setNetworkOnline(!syncEngine.isOnline());
        updateNetworkBadge();
        if (!syncEngine.isOnline()) {
            updateStateBanner(UiState.OFFLINE, UiState.OFFLINE.defaultMessageEn, UiState.OFFLINE.defaultMessageBn);
        } else {
            updateStateBanner(
                    UiState.READY,
                    "Online cloud authority restored.",
                    "অনলাইন ক্লাউড সংযোগ পুনরায় সচল হয়েছে।"
            );
        }
    }

    @FXML
    public void handleToggleLanguage() {
        this.isBangla = !this.isBangla;
        applyLanguageText();
        configurePricingControls();
        updateNetworkBadge();
        updateTotals();
        updateStateBanner(lastUiState, lastStatusEn, lastStatusBn);
        if (searchResultsList != null) {
            searchResultsList.refresh();
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
        if (item == null) return;
        cartItems.add(item);
        updateTotals();
    }

    public void updateTotals() {
        int totalUnits = 0;
        for (CartItem item : cartItems) {
            totalUnits += item.getQuantity();
        }

        BigDecimal subtotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal discountAmt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal vat = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal roundingAdj = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal grandTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        String effectiveTaxMode = "EXCLUSIVE";
        BigDecimal effectiveTaxPct = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        boolean confirmed = false;

        if (activeServerSale != null && activeServerSale.lines.size() == cartItems.size() && !cartItems.isEmpty()) {
            subtotal = activeServerSale.subtotalBdt.setScale(2, RoundingMode.HALF_UP);
            discountAmt = activeServerSale.discountAmountBdt.setScale(2, RoundingMode.HALF_UP);
            vat = activeServerSale.taxAmountBdt.setScale(2, RoundingMode.HALF_UP);
            roundingAdj = activeServerSale.roundingAdjustmentBdt.setScale(2, RoundingMode.HALF_UP);
            grandTotal = activeServerSale.grandTotalBdt.setScale(2, RoundingMode.HALF_UP);
            effectiveTaxMode = activeServerSale.taxMode != null ? activeServerSale.taxMode : "EXCLUSIVE";
            effectiveTaxPct = activeServerSale.taxRatePct.setScale(2, RoundingMode.HALF_UP);
            confirmed = activeServerSale.isPricingConfirmed;
        } else {
            BigDecimal localSub = BigDecimal.ZERO;
            BigDecimal localVat = BigDecimal.ZERO;
            for (CartItem item : cartItems) {
                localSub = localSub.add(item.getLineSubtotal());
                localVat = localVat.add(item.getLineVat());
            }
            subtotal = localSub.setScale(2, RoundingMode.HALF_UP);
            vat = localVat.setScale(2, RoundingMode.HALF_UP);
            if ("PERCENTAGE".equalsIgnoreCase(selectedDiscountType)) {
                discountAmt = subtotal.multiply(requestedDiscountValue)
                        .divide(new BigDecimal("100.00"), 2, RoundingMode.HALF_UP);
            } else if ("FLAT_BDT".equalsIgnoreCase(selectedDiscountType) || "PROMO_POLICY".equalsIgnoreCase(selectedDiscountType)) {
                discountAmt = requestedDiscountValue.min(subtotal).max(BigDecimal.ZERO);
            }
            BigDecimal preRound = subtotal.subtract(discountAmt).add(vat).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            roundingAdj = cartItems.isEmpty()
                    ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                    : computeLocalRoundingAdjustment(preRound, selectedRoundingMode);
            grandTotal = preRound.add(roundingAdj).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        }

        if (subtotalLabel != null) subtotalLabel.setText("BDT " + subtotal.toPlainString());
        if (discountLabel != null) discountLabel.setText("BDT " + discountAmt.toPlainString());
        if (vatLabel != null) vatLabel.setText("BDT " + vat.toPlainString());
        if (roundingLabel != null) roundingLabel.setText("BDT " + roundingAdj.toPlainString());
        if (grandTotalLabel != null) grandTotalLabel.setText("BDT " + grandTotal.toPlainString());
        if (taxModeLabel != null) {
            String shortMode = "INCLUSIVE".equalsIgnoreCase(effectiveTaxMode) ? "INCL" : "EXCL";
            taxModeLabel.setText(shortMode + " " + effectiveTaxPct.toPlainString() + "%");
        }
        if (pricingConfirmBadge != null) {
            pricingConfirmBadge.getStyleClass().removeAll("status-badge-online", "status-badge-offline");
            if (confirmed) {
                pricingConfirmBadge.getStyleClass().add("status-badge-online");
                pricingConfirmBadge.setText(isBangla ? "মূল্য নিশ্চিত" : "Server Confirmed");
            } else if (!syncEngine.isOnline()) {
                pricingConfirmBadge.getStyleClass().add("status-badge-offline");
                pricingConfirmBadge.setText(isBangla ? "অফলাইন প্রাক্কলন" : "Offline Preview");
            } else {
                pricingConfirmBadge.getStyleClass().add("status-badge-offline");
                pricingConfirmBadge.setText(isBangla ? "অনিশ্চিত" : "Unconfirmed");
            }
        }
        if (cartSummaryBadge != null) {
            cartSummaryBadge.setText(isBangla
                    ? (cartItems.size() + " লাইন | " + totalUnits + " আইটেম")
                    : (cartItems.size() + " lines | " + totalUnits + " items"));
        }
        if (activeSaleBadge != null && activeServerSale == null) {
            activeSaleBadge.setText(cartItems.isEmpty()
                    ? (isBangla ? "কুইক কার্ট | খালি" : "Quick Cart | Empty")
                    : (isBangla ? "কুইক কার্ট | সক্রিয়" : "Quick Cart | Active"));
        }
        refreshSelectedLineUnitControls();
        updateRxSafetyGateControls();
        updateFefoPlanControls();
        updateSplitPayControls();
    }

    @FXML
    public void handleCheckout() {
        if (currentSession != null) {
            securityService.assertCanCheckout(currentSession);
        }

        if (cartItems.isEmpty()) {
            updateStateBanner(
                    UiState.ERROR,
                    "Cart is empty. Scan at least one sellable item before checkout.",
                    "কার্ট খালি! চেকআউট করার আগে অন্তত একটি বিক্রয়যোগ্য ওষুধ স্ক্যান করুন।"
            );
            return;
        }

        if (hasBlockedRecallOrBan()) {
            updateRxSafetyGateControls();
            updateStateBanner(
                    UiState.ERROR,
                    "ERR_REGULATED_PRODUCT_BLOCKED: Cart contains a DGDA RECALLED or BANNED medicine that cannot be sold.",
                    "কার্টে প্রত্যাহৃত (RECALLED) বা নিষিদ্ধ (BANNED) ওষুধ থাকায় চেকআউট সম্পূর্ণ নিষিদ্ধ।"
            );
            return;
        }

        boolean isOffline = !syncEngine.isOnline();
        boolean reqRx = doesCartRequireRx();
        boolean cleared = isCartRxClearedForCheckout();

        if (reqRx && !cleared) {
            updateRxSafetyGateControls();
            if (isOffline) {
                updateStateBanner(
                        UiState.OFFLINE,
                        "ERR_OFFLINE_RX_BLOCKED: Offline limitation blocks unverified Rx/antibiotic checkout. Park to Hold Queue [F9] and reconnect for Pharmacist approval.",
                        "অফলাইন সীমাবদ্ধতা: ফার্মাসিস্টের অনুমোদন ছাড়া প্রেসক্রিপশন বা অ্যান্টিবায়োটিক ওষুধ অফলাইনে চেকআউট করা যাবে না। [F9] দিয়ে হোল্ড করে অনলাইনে অনুমোদন নিন।"
                );
            } else {
                setRxGateDrawerVisible(true);
                updateStateBanner(
                        UiState.ERROR,
                        hasLocalUnverifiedRxScaling()
                                ? "ERR_PHARMACIST_APPROVAL_REQUIRED: Cart modified after verification; re-approve in Rx Gate [F8]."
                                : "ERR_PHARMACIST_APPROVAL_REQUIRED: Registered Pharmacist Rx approval is required before checkout [F8].",
                        hasLocalUnverifiedRxScaling()
                                ? "কার্ট পরিবর্তিত হওয়ায় পুনরায় ফার্মাসিস্ট Rx অনুমোদন আবশ্যক [F8]।"
                                : "চেকআউটের আগে নিবন্ধিত ফার্মাসিস্টের Rx অনুমোদন আবশ্যক [F8]।"
                );
            }
            return;
        }

        if (isOffline || isOfflineOrderOnly()) {
            String offlineFefoBlock = validateOfflineFefoStockSafety();
            if (offlineFefoBlock != null) {
                updateFefoPlanControls();
                setFefoPlanDrawerVisible(true);
                String[] parts = offlineFefoBlock.split("\\|", 3);
                updateStateBanner(
                        UiState.ERROR,
                        parts[0] + ": " + (parts.length >= 2 ? parts[1] : offlineFefoBlock),
                        parts.length >= 3 ? parts[2] : offlineFefoBlock
                );
                return;
            }
            String offlinePayBlock = validateOfflineSplitPayments();
            if (offlinePayBlock != null) {
                updateSplitPayControls();
                setSplitPayDrawerVisible(true);
                String[] parts = offlinePayBlock.split("\\|", 3);
                updateStateBanner(
                        UiState.OFFLINE,
                        parts[0] + ": " + (parts.length >= 2 ? parts[1] : offlinePayBlock),
                        parts.length >= 3 ? parts[2] : offlinePayBlock
                );
                return;
            }
        }

        String token = currentSession != null ? currentSession.getDeviceToken() : "";
        if (!isOffline && !isOfflineOrderOnly() && token != null && !token.isBlank()) {
            String role = currentSession.getUserRole();
            String tenantId = currentSession.getTenantId();
            String branchId = currentSession.getBranchId();
            BigDecimal payAmt = computeCurrentPreviewGrandTotal();
            BigDecimal alreadyCovered = activeServerSale.paidAmountBdt.add(activeServerSale.dueAmountBdt);
            if (!stagedSplitPayments.isEmpty()) {
                ClientOutcome<PosSaleOrderDto> splitRes = saleDomainClient.submitSplitPayments(
                        token, role, tenantId, branchId, tenantId, branchId, activeServerSale.saleId,
                        stagedSplitPayments, "chk-split-" + UUID.randomUUID(), true, false, false
                );
                if (splitRes.uiState != UiState.READY || splitRes.data == null) {
                    setSplitPayDrawerVisible(true);
                    updateStateBanner(splitRes.uiState, splitRes.messageEn, splitRes.messageBn);
                    return;
                }
                activeServerSale = splitRes.data;
                stagedSplitPayments.clear();
            } else if (payAmt.compareTo(alreadyCovered) > 0) {
                BigDecimal rem = payAmt.subtract(alreadyCovered).setScale(2, RoundingMode.HALF_UP);
                ClientOutcome<PosSaleOrderDto> payRes = saleDomainClient.recordPaymentAttempt(
                        token, role, tenantId, branchId, tenantId, branchId, activeServerSale.saleId,
                        "CASH", rem, "", "pay-pos-" + UUID.randomUUID(), false, false
                );
                if (payRes.uiState != UiState.READY) {
                    updateStateBanner(payRes.uiState, payRes.messageEn, payRes.messageBn);
                    return;
                }
            }
            Map<String, String> batchOverrides = buildBatchOverrideMapFromDrawer();
            String overrideReason = fefoOverrideReasonField != null && fefoOverrideReasonField.getText() != null
                    ? fefoOverrideReasonField.getText().trim() : "";
            ClientOutcome<PosSaleOrderDto> finRes = saleDomainClient.finalizeSale(
                    token, role, tenantId, branchId, tenantId, branchId, activeServerSale.saleId,
                    null, "", false, batchOverrides, overrideReason, false, false
            );
            if (finRes.uiState != UiState.READY || finRes.data == null) {
                setFefoPlanDrawerVisible(true);
                updateStateBanner(finRes.uiState, finRes.messageEn, finRes.messageBn);
                return;
            }
            String ref = finRes.data.orderNumber.isBlank() ? finRes.data.saleId : finRes.data.orderNumber;
            StringBuilder summaryTag = new StringBuilder();
            for (int i = 0; i < finRes.data.paymentAttempts.size(); i++) {
                PosSaleDomainClient.PosPaymentAttemptDto pa = finRes.data.paymentAttempts.get(i);
                if ("VOIDED".equalsIgnoreCase(pa.status)) continue;
                summaryTag.append(summaryTag.isEmpty() ? " | Paid: " : "+")
                        .append(pa.tenderType).append(" ").append(pa.netSettledAmountBdt.toPlainString());
            }
            if (finRes.data.changeReturnedBdt.compareTo(BigDecimal.ZERO) > 0) {
                summaryTag.append(" (Change ").append(finRes.data.changeReturnedBdt.toPlainString()).append(")");
            }
            for (int i = 0; i < finRes.data.allocations.size(); i++) {
                PosSaleDomainClient.PosSaleAllocationDto al = finRes.data.allocations.get(i);
                summaryTag.append(i == 0 ? " | FEFO: " : ", ")
                        .append(al.batchNumber.isBlank() ? al.batchId : al.batchNumber)
                        .append(" x").append(al.allocatedBaseUnits);
            }
            updateStateBanner(
                    UiState.READY,
                    "Sale finalized (" + ref + ")" + summaryTag + ".",
                    "বিক্রয় ও ইনভয়েস সম্পন্ন হয়েছে (" + ref + ")" + summaryTag + "।"
            );
            clearActiveCounterForNextCustomer();
            return;
        }

        SaleTransaction transaction = new SaleTransaction(
                UUID.randomUUID().toString(),
                "INV-" + System.currentTimeMillis(),
                currentSession != null ? currentSession.getTenantId() : "tenant-demo",
                currentSession != null ? currentSession.getBranchId() : "branch-main",
                currentSession != null ? currentSession.getUserId() : "cashier-1",
                new ArrayList<>(cartItems),
                BigDecimal.ZERO,
                isOffline,
                Instant.now()
        );

        if (isOffline) {
            syncEngine.enqueueTransaction(transaction);
            updateStateBanner(
                    UiState.OFFLINE,
                    "Offline OTC sale queued cleanly (" + transaction.getInvoiceNumber() + " | OFFLINE_PROVISIONAL_FEFO).",
                    "অফলাইন OTC বিক্রয় কিউতে সংরক্ষিত হয়েছে (" + transaction.getInvoiceNumber() + " | OFFLINE_PROVISIONAL_FEFO)।"
            );
        } else {
            updateStateBanner(
                    UiState.READY,
                    "Checkout submitted (" + transaction.getInvoiceNumber() + ").",
                    "চেকআউট সম্পন্ন হয়েছে (" + transaction.getInvoiceNumber() + ")।"
            );
        }

        clearActiveCounterForNextCustomer();
    }

    private int getSelectedCartIndex() {
        if (cartItems.isEmpty()) return -1;
        if (cartTable != null) {
            int idx = cartTable.getSelectionModel().getSelectedIndex();
            if (idx >= 0 && idx < cartItems.size()) return idx;
        }
        return cartItems.size() - 1;
    }

    private int computeCartProductBaseUnits(String productId) {
        int total = 0;
        for (CartItem row : cartItems) {
            if (row.getMedicineId().equals(productId)) {
                CartLineMetadata m = lineMetadataMap.get(row);
                int f = m != null ? Math.max(1, m.conversionFactor) : 1;
                total += row.getQuantity() * f;
            }
        }
        return total;
    }

    private void setResultsDrawerVisible(boolean visible) {
        if (searchResultsList != null) {
            searchResultsList.setVisible(visible);
            searchResultsList.setManaged(visible);
        }
    }

    private void updateNetworkBadge() {
        if (networkStatusLabel == null) return;
        boolean online = syncEngine.isOnline();
        networkStatusLabel.getStyleClass().removeAll("status-badge-online", "status-badge-offline");
        if (online) {
            networkStatusLabel.getStyleClass().add("status-badge-online");
            networkStatusLabel.setText(isBangla ? "অনলাইন অথরিটি" : "Online Authority");
            FontIcon ic = createIcon(Feather.WIFI, 12, "#16A34A");
            if (ic != null) networkStatusLabel.setGraphic(ic);
        } else {
            networkStatusLabel.getStyleClass().add("status-badge-offline");
            networkStatusLabel.setText(isBangla ? "অফলাইন মোড (স্ন্যাপশট ক্যাশ)" : "Offline Mode (Snapshot Cache)");
            FontIcon ic = createIcon(Feather.WIFI_OFF, 12, "#D97706");
            if (ic != null) networkStatusLabel.setGraphic(ic);
        }
        if (btnToggleOffline != null) {
            btnToggleOffline.setText(online
                    ? (isBangla ? "অফলাইন সিমুলেশন" : "Simulate Offline")
                    : (isBangla ? "অনলাইনে ফিরুন" : "Go Online"));
        }
    }

    private void updateStateBanner(UiState state, String msgEn, String msgBn) {
        this.lastUiState = state != null ? state : UiState.EMPTY;
        this.lastStatusEn = msgEn != null ? msgEn : this.lastUiState.defaultMessageEn;
        this.lastStatusBn = msgBn != null ? msgBn : this.lastUiState.defaultMessageBn;

        if (stateStatusLabel != null) {
            stateStatusLabel.setText(isBangla ? this.lastStatusBn : this.lastStatusEn);
        }
        if (stateStatusBanner != null) {
            stateStatusBanner.getStyleClass().removeAll(
                    "state-banner-ready", "state-banner-loading", "state-banner-empty",
                    "state-banner-offline", "state-banner-error", "state-banner-denied"
            );
            String cls = switch (this.lastUiState) {
                case READY -> "state-banner-ready";
                case LOADING -> "state-banner-loading";
                case OFFLINE -> "state-banner-offline";
                case ERROR -> "state-banner-error";
                case PERMISSION_DENIED -> "state-banner-denied";
                case EMPTY -> "state-banner-empty";
            };
            stateStatusBanner.getStyleClass().add(cls);
        }
        if (stateStatusLabel != null) {
            Feather ic = switch (this.lastUiState) {
                case READY -> Feather.CHECK_CIRCLE;
                case LOADING -> Feather.REFRESH_CW;
                case OFFLINE -> Feather.WIFI_OFF;
                case PERMISSION_DENIED -> Feather.LOCK;
                case ERROR -> Feather.ALERT_TRIANGLE;
                case EMPTY -> Feather.SEARCH;
            };
            String hex = switch (this.lastUiState) {
                case READY -> "#16A34A";
                case LOADING -> "#0284C7";
                case OFFLINE -> "#D97706";
                case ERROR, PERMISSION_DENIED -> "#DC2626";
                case EMPTY -> "#64748B";
            };
            FontIcon fi = createIcon(ic, 14, hex);
            if (fi != null) stateStatusLabel.setGraphic(fi);
        }
    }

    private void applyLanguageText() {
        if (headerBrandLabel != null) {
            headerBrandLabel.setText(isBangla ? "বাংলাদেশ ফার্মেসি ERP ও পিওএস" : "Bangladesh Pharmacy ERP & POS");
        }
        if (btnLangToggle != null) {
            btnLangToggle.setText(isBangla ? "English" : "বাংলা");
        }
        if (btnRefreshSnapshot != null) {
            btnRefreshSnapshot.setText(isBangla ? "SKU ক্যাশ সিঙ্ক" : "Sync SKU Cache");
        }
        if (btnLockCounter != null) {
            btnLockCounter.setText(isBangla ? "কাউন্টার লক" : "Lock Counter");
        }
        if (barcodeInputField != null) {
            barcodeInputField.setPromptText(isBangla
                    ? "বারকোড, GTIN, SKU বা ওষুধের নাম (বাংলা/ইংরেজি) লিখুন (F2)..."
                    : "Scan Barcode, GTIN, SKU or Search Generic / Brand EN-BN (F2)...");
        }
        if (btnAddItem != null) btnAddItem.setText(isBangla ? "যুক্ত করুন (Enter)" : "Add Item (Enter)");
        if (btnClearSearch != null) btnClearSearch.setText(isBangla ? "পরিষ্কার (Esc)" : "Clear (Esc)");
        if (btnQtyMinus != null) btnQtyMinus.setText(isBangla ? "- কমান" : "- Qty");
        if (btnQtyPlus != null) btnQtyPlus.setText(isBangla ? "+ বাড়ান" : "+ Qty");
        if (btnCycleUnit != null) btnCycleUnit.setText(isBangla ? "ইউনিট [U]" : "Unit [U]: Tab/Strip/Box");
        if (unitSelectorCombo != null) {
            unitSelectorCombo.setPromptText(isBangla ? "ইউনিট নির্বাচন" : "Select Unit");
            refreshSelectedLineUnitControls();
        }
        if (btnRemoveLine != null) btnRemoveLine.setText(isBangla ? "মুছুন [Del]" : "Remove [Del]");
        if (btnHoldCart != null) btnHoldCart.setText(isBangla ? "হোল্ড [F9]" : "Hold [F9]");
        if (holdReferenceField != null) {
            holdReferenceField.setPromptText(isBangla ? "হোল্ড রেফারেন্স (ঐচ্ছিক)" : "Hold Ref (Optional)");
        }
        if (holdTtlField != null) {
            holdTtlField.setPromptText(isBangla ? "মেয়াদ মিনিট (৩০)" : "TTL min (30)");
        }
        if (reserveHoldStockCheck != null) {
            reserveHoldStockCheck.setText(isBangla ? "FEFO স্টক রিজার্ভ" : "Reserve FEFO Stock");
        }
        if (heldDrawerTitleLabel != null) {
            heldDrawerTitleLabel.setText(isBangla ? "হোল্ড করা কার্ট ও রিজার্ভেশন" : "Held Carts & Reservations");
        }
        if (btnRefreshHeldCarts != null) btnRefreshHeldCarts.setText(isBangla ? "রিফ্রেশ" : "Refresh");
        if (btnExpireStaleHolds != null) btnExpireStaleHolds.setText(isBangla ? "মেয়াদ শেষ মুক্ত করুন" : "Expire Stale");
        if (btnResumeSelectedHeld != null) btnResumeSelectedHeld.setText(isBangla ? "রিজিউম" : "Resume");
        if (btnReleaseSelectedHold != null) btnReleaseSelectedHold.setText(isBangla ? "রিজার্ভ মুক্ত" : "Release Res.");
        if (btnCancelSelectedHold != null) btnCancelSelectedHold.setText(isBangla ? "হোল্ড বাতিল" : "Cancel Hold");
        if (btnCloseHeldDrawer != null) btnCloseHeldDrawer.setText(isBangla ? "বন্ধ" : "Close");
        if (heldCartsEmptyLabel != null) {
            heldCartsEmptyLabel.setText(isBangla ? "এই শাখায় কোনো হোল্ড করা কার্ট নেই।" : "No held baskets in this branch.");
        }
        if (btnAcknowledgeResume != null) btnAcknowledgeResume.setText(isBangla ? "নিশ্চিত করে রিজিউম করুন" : "Confirm & Resume");
        if (btnDismissStalePrompt != null) btnDismissStalePrompt.setText(isBangla ? "বাতিল" : "Dismiss");
        if (shortcutsLine3Label != null) {
            shortcutsLine3Label.setText(isBangla
                    ? "[F6] স্প্লিট পে | [F7] FEFO লট | [F8] Rx গেট | [F9] হোল্ড | [F10] তালিকা | [F11] ডেলিভারি | [F4] চেকআউট"
                    : "[F6] Split Pay | [F7] FEFO Lots | [F8] Rx Gate | [F9] Hold Cart | [F10] Held | [F11] Deliveries | [F4] Checkout");
        }
        if (footerShortcutsLabel != null) {
            footerShortcutsLabel.setText(isBangla
                    ? "শর্টকাট: [F2] স্ক্যান | [Enter] যুক্ত | [U] ইউনিট | [F6] পেমেন্ট | [F7] FEFO | [F8] Rx | [F9] হোল্ড | [F10] তালিকা | [F11] ডেলিভারি | [F4] চেকআউট"
                    : "Shortcuts: [F2] Scan | [Enter] Add | [U] Unit | [F6] Split Pay | [F7] FEFO | [F8] Rx | [F9] Hold | [F10] Held | [F11] Delivery | [F4] Checkout");
        }
        if (splitPayDrawerTitleLabel != null) {
            splitPayDrawerTitleLabel.setText(isBangla ? "স্প্লিট পেমেন্ট ও ক্যাশিয়ার ক্লোজ রিকনসিলিয়েশন [F6]" : "Split Payments & Cashier Close Reconciliation [F6]");
        }
        if (btnRefreshCloseRecon != null) btnRefreshCloseRecon.setText(isBangla ? "শিফট হিসাব" : "Shift Totals");
        if (btnCloseSplitPayDrawer != null) btnCloseSplitPayDrawer.setText(isBangla ? "বন্ধ" : "Close");
        if (splitAmountField != null) splitAmountField.setPromptText(isBangla ? "টাকা (BDT)" : "Amount (BDT)");
        if (splitRefField != null) splitRefField.setPromptText(isBangla ? "TrxID / কার্ড রেফারেন্স" : "TrxID / Card Auth Ref");
        if (splitPayerField != null) splitPayerField.setPromptText(isBangla ? "গ্রাহক / কার্ড ৪ সংখ্যা" : "Payer / Last 4");
        if (btnAddSplitLeg != null) btnAddSplitLeg.setText(isBangla ? "+ স্প্লিট যোগ" : "+ Add Split");
        if (btnClearSplitLegs != null) btnClearSplitLegs.setText(isBangla ? "পরিষ্কার" : "Clear");
        if (btnSubmitSplitPayments != null) btnSubmitSplitPayments.setText(isBangla ? "পেমেন্ট পোস্ট" : "Post Splits");
        if (closeCountedCashField != null) closeCountedCashField.setPromptText(isBangla ? "হাতে গনা ক্যাশ (BDT)" : "Counted Cash (BDT)");
        if (closeVarianceNotesField != null) closeVarianceNotesField.setPromptText(isBangla ? "ব্যবধান থাকলে কারণ লিখুন" : "Variance Notes (if Cash != Expected)");
        if (btnSubmitCloseRecon != null) btnSubmitCloseRecon.setText(isBangla ? "ড্রয়ার ক্লোজ করুন" : "Close Shift Drawer");
        if (btnRefreshCustomers != null) btnRefreshCustomers.setText(isBangla ? "গ্রাহক লোড" : "Customers");
        if (btnRefreshCustomerDues != null) btnRefreshCustomerDues.setText(isBangla ? "বাকি তালিকা" : "Dues");
        if (btnCollectCustomerDue != null) btnCollectCustomerDue.setText(isBangla ? "বাকি আদায়" : "Collect Due");
        if (dueCollectAmountField != null) dueCollectAmountField.setPromptText(isBangla ? "আদায় (BDT)" : "Collect (BDT)");
        if (dueCollectRefField != null) dueCollectRefField.setPromptText(isBangla ? "TrxID (MFS/কার্ড)" : "TrxID (if MFS/Card)");
        updateSplitPayControls();
        if (fefoPlanDrawerTitleLabel != null) {
            fefoPlanDrawerTitleLabel.setText(isBangla ? "FEFO লট বরাদ্দ ও স্টক নিরাপত্তা যাচাই [F7]" : "FEFO Lot Allocation & Safety Check [F7]");
        }
        if (btnRefreshFefoPlan != null) btnRefreshFefoPlan.setText(isBangla ? "প্ল্যান রিফ্রেশ" : "Refresh Plan");
        if (btnCloseFefoDrawer != null) btnCloseFefoDrawer.setText(isBangla ? "বন্ধ" : "Close");
        if (fefoOverrideBatchField != null) fefoOverrideBatchField.setPromptText(isBangla ? "ঐচ্ছিক ওভাররাইড ব্যাচ আইডি (ফার্মাসিস্ট/ম্যানেজার)" : "Optional Override Batch ID (Pharmacist/Manager)");
        if (fefoOverrideReasonField != null) fefoOverrideReasonField.setPromptText(isBangla ? "FEFO পরিবর্তনের কারণ (>= ৩ অক্ষর)" : "Override Reason (>= 3 chars if bypassing FEFO)");
        if (btnReserveFefoLots != null) btnReserveFefoLots.setText(isBangla ? "FEFO লট রিজার্ভ করুন" : "Reserve FEFO Lots");
        updateFefoPlanControls();
        if (deliveryDrawerTitleLabel != null) {
            deliveryDrawerTitleLabel.setText(isBangla ? "প্রি-অর্ডার ও ডেলিভারি ব্যবস্থাপনা" : "Preorders & Delivery Fulfillment");
        }
        if (btnRefreshDeliveries != null) btnRefreshDeliveries.setText(isBangla ? "রিফ্রেশ" : "Refresh");
        if (btnCloseDeliveryDrawer != null) btnCloseDeliveryDrawer.setText(isBangla ? "বন্ধ" : "Close");
        if (deliveryRecipientField != null) deliveryRecipientField.setPromptText(isBangla ? "প্রাপকের নাম" : "Recipient Name");
        if (deliveryPhoneField != null) deliveryPhoneField.setPromptText(isBangla ? "ফোন (017...)" : "Phone (017...)");
        if (deliveryAddressField != null) deliveryAddressField.setPromptText(isBangla ? "বাসা/সড়ক ঠিকানা (হোম ডেলিভারি)" : "Street Address (Home Delivery)");
        if (deliveryAreaField != null) deliveryAreaField.setPromptText(isBangla ? "এলাকা" : "Area");
        if (deliveryFeeField != null) deliveryFeeField.setPromptText(isBangla ? "ফি (টাকা)" : "Fee BDT");
        if (reserveDeliveryStockCheck != null) reserveDeliveryStockCheck.setText(isBangla ? "স্টক সংরক্ষণ" : "Reserve Stock");
        if (btnSubmitDeliveryOrder != null) btnSubmitDeliveryOrder.setText(isBangla ? "অর্ডার তৈরি" : "Create Order");
        if (deliveryRiderField != null) deliveryRiderField.setPromptText(isBangla ? "রাইডার / কুরিয়ার" : "Rider / Courier Partner");
        if (deliveryPodField != null) deliveryPodField.setPromptText(isBangla ? "POD / রিসিপ্ট রেফ" : "POD OTP / Receipt Ref");
        if (btnPackDelivery != null) btnPackDelivery.setText(isBangla ? "প্যাক" : "Pack");
        if (btnDispatchDelivery != null) btnDispatchDelivery.setText(isBangla ? "ডিসপ্যাচ" : "Dispatch");
        if (btnDeliverOrder != null) btnDeliverOrder.setText(isBangla ? "ডেলিভারি সম্পন্ন" : "Mark Delivered");
        if (btnRevealDeliveryAddress != null) btnRevealDeliveryAddress.setText(isBangla ? "ঠিকানা দেখুন" : "Reveal Address");
        if (btnCancelDelivery != null) btnCancelDelivery.setText(isBangla ? "বাতিল" : "Cancel");
        updateHeldCountBadge();
        updateDeliveryCountBadge();
        if (deliveriesList != null) {
            deliveriesList.refresh();
        }
        updateDeliveryDetailPanel();
        if (staleConfirmationBanner != null && staleConfirmationBanner.isVisible()) {
            refreshStaleBannerText("", "");
        }
        if (heldCartsList != null) {
            heldCartsList.refresh();
        }
        if (cartEmptyLabel != null) {
            cartEmptyLabel.setText(isBangla
                    ? "কার্ট খালি। বারকোড স্ক্যান করতে [F2] চাপুন বা ওষুধের নাম লিখুন।"
                    : "Cart is empty. Press [F2] to scan barcode or type medicine name.");
        }
        if (paymentSummaryTitle != null) paymentSummaryTitle.setText(isBangla ? "পেমেন্ট সারসংক্ষেপ" : "Payment Summary");
        if (roundingModeTitleLabel != null) roundingModeTitleLabel.setText(isBangla ? "রাউন্ডিং:" : "Rounding:");
        if (btnApplyPricing != null) btnApplyPricing.setText(isBangla ? "প্রয়োগ" : "Apply");
        if (discountTitleLabel != null) discountTitleLabel.setText(isBangla ? "ছাড়:" : "Discount:");
        if (subtotalTitleLabel != null) subtotalTitleLabel.setText(isBangla ? "উপ-মোট:" : "Subtotal:");
        if (vatTitleLabel != null) vatTitleLabel.setText(isBangla ? "ভ্যাট (৫% / অব্যাহতি):" : "VAT (5% / Exempt):");
        if (roundingTitleLabel != null) roundingTitleLabel.setText(isBangla ? "রাউন্ডিং সমন্বয়:" : "Rounding Adj:");
        if (grandTotalTitleLabel != null) grandTotalTitleLabel.setText(isBangla ? "সর্বমোট:" : "Grand Total:");
        if (btnConfirmPricing != null) btnConfirmPricing.setText(isBangla ? "মূল্য নিশ্চিত করুন" : "Confirm Prices");
        if (checkoutButton != null) checkoutButton.setText(isBangla ? "চেকআউট সম্পন্ন করুন (F4)" : "Complete Checkout (F4)");
        if (rxGateDrawerTitleLabel != null) {
            rxGateDrawerTitleLabel.setText(isBangla
                    ? "প্রেসক্রিপশন সেফটি গেট ও ফার্মাসিস্ট অনুমোদন [F8]"
                    : "Prescription Safety Gate & Pharmacist Clearance [F8]");
        }
        if (btnRefreshAntibioticRegister != null) {
            btnRefreshAntibioticRegister.setText(isBangla ? "Abx রেজিস্টার" : "Abx Register");
        }
        if (btnCloseRxDrawer != null) btnCloseRxDrawer.setText(isBangla ? "বন্ধ" : "Close");
        if (rxDoctorNameField != null) rxDoctorNameField.setPromptText(isBangla ? "চিকিৎসকের পূর্ণ নাম" : "Doctor Full Name");
        if (rxDoctorBmdcField != null) rxDoctorBmdcField.setPromptText(isBangla ? "BMDC রেজি (যেমন A-44120)" : "BMDC Reg (e.g. A-44120)");
        if (rxPatientTokenField != null) rxPatientTokenField.setPromptText(isBangla ? "রোগীর রেফারেন্স" : "Patient Ref");
        if (rxReferenceField != null) rxReferenceField.setPromptText(isBangla ? "Rx রেফারেন্স / নং" : "Rx Reference / No.");
        if (btnAttachPrescription != null) btnAttachPrescription.setText(isBangla ? "Rx সংযুক্ত করুন" : "Attach Rx");
        if (rxPharmacistPcbField != null) rxPharmacistPcbField.setPromptText(isBangla ? "PCB রেজি (যেমন PCB-12345)" : "PCB Reg No. (e.g. PCB-12345)");
        if (rxDecisionNotesField != null) rxDecisionNotesField.setPromptText(isBangla ? "ফার্মাসিস্ট যাচাই / বাতিলের কারণ" : "Pharmacist Verification / Rejection Reason");
        if (btnVerifyRxOrder != null) btnVerifyRxOrder.setText(isBangla ? "Rx অনুমোদন" : "Approve Rx");
        if (btnRejectRxOrder != null) btnRejectRxOrder.setText(isBangla ? "Rx প্রত্যাখ্যান" : "Reject Rx");
        updateRxSafetyGateControls();
        if (shortcutsTitleLabel != null) shortcutsTitleLabel.setText(isBangla ? "কীবোর্ড শর্টকাট" : "Keyboard-First Shortcuts");
        if (brandCol != null) brandCol.setText(isBangla ? "ওষুধ / ব্র্যান্ড" : "Medicine / Brand");
        if (unitCol != null) unitCol.setText(isBangla ? "প্যাকেজিং ইউনিট" : "Packaging Unit");
        if (qtyCol != null) qtyCol.setText(isBangla ? "পরিমাণ" : "Qty");
        if (priceCol != null) priceCol.setText(isBangla ? "একক মূল্য (BDT)" : "Unit Price (BDT)");
        if (totalCol != null) totalCol.setText(isBangla ? "মোট (BDT)" : "Total (BDT)");
        if (shiftStatusBadge != null) {
            String shortShift = currentShiftName.contains("(")
                    ? currentShiftName.substring(0, currentShiftName.indexOf("(")).trim()
                    : currentShiftName;
            shiftStatusBadge.setText((isBangla ? "শিফট: " : "Shift: ") + shortShift);
        }
        if (cashierFooterLabel != null && currentSession != null) {
            cashierFooterLabel.setText(
                    (isBangla ? "ক্যাশিয়ার: " : "Cashier: ") + currentStaffName
                            + " (" + currentSession.getUserRole() + ") | "
                            + (isBangla ? "শাখা: " : "Branch: ") + currentSession.getBranchId()
                            + " | Float: BDT " + currentOpeningFloat
            );
        }
    }
}
