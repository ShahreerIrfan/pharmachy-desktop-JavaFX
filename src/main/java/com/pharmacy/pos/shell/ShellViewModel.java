package com.pharmacy.pos.shell;

import javafx.beans.property.*;

import java.util.ArrayList;
import java.util.List;

/**
 * ShellViewModel - Reactive State Manager for JavaFX POS App Shell.
 * Architecture Decision: ADR-0321 (P321).
 */
public class ShellViewModel {

    private final ObjectProperty<ShellRoute> activeRoute = new SimpleObjectProperty<>(ShellRoute.DASHBOARD);
    private final StringProperty locale = new SimpleStringProperty("en_BD");
    private final StringProperty theme = new SimpleStringProperty("MACOS_LIGHT");
    private final BooleanProperty isOnline = new SimpleBooleanProperty(true);
    private final BooleanProperty isSidebarCollapsed = new SimpleBooleanProperty(false);
    private final StringProperty errorMessage = new SimpleStringProperty("");
    private final StringProperty statusMessage = new SimpleStringProperty("System Ready");

    private final StringProperty tenantId = new SimpleStringProperty("");
    private final StringProperty branchId = new SimpleStringProperty("");
    private final StringProperty userId = new SimpleStringProperty("");
    private final StringProperty userRole = new SimpleStringProperty("CASHIER");
    private final StringProperty deviceId = new SimpleStringProperty("");

    private final IntegerProperty pendingOutboxCount = new SimpleIntegerProperty(0);

    public void initSession(String tenantId, String branchId, String userId, String userRole, String deviceId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("Tenant ID must not be empty.");
        }
        if (branchId == null || branchId.trim().isEmpty()) {
            throw new IllegalArgumentException("Branch ID must not be empty.");
        }
        if (deviceId == null || deviceId.trim().isEmpty()) {
            throw new IllegalArgumentException("Device ID must not be empty.");
        }

        this.tenantId.set(tenantId.trim());
        this.branchId.set(branchId.trim());
        this.userId.set(userId != null ? userId.trim() : "");
        this.userRole.set(userRole != null ? userRole.trim().toUpperCase() : "CASHIER");
        this.deviceId.set(deviceId.trim());

        this.statusMessage.set("Workspace initialized: " + this.branchId.get());
    }

    public boolean navigateTo(ShellRoute route) {
        if (route == null) {
            errorMessage.set("Invalid route target.");
            return false;
        }

        String currentRole = userRole.get();
        if (!route.isRoleAllowed(currentRole)) {
            String roleDeniedMsg = isBengali()
                    ? "অনুমতি অস্বীকৃত: '" + currentRole + "' রোলের জন্য এই মডিউলটি উন্মুক্ত নয়।"
                    : "Permission Denied: Role '" + currentRole + "' cannot access " + route.getRouteKey();
            errorMessage.set(roleDeniedMsg);
            return false;
        }

        activeRoute.set(route);
        errorMessage.set("");
        statusMessage.set("Navigated to " + route.getLabel(locale.get()));
        return true;
    }

    public List<ShellRoute> getAccessibleRoutes() {
        String currentRole = userRole.get();
        List<ShellRoute> allowed = new ArrayList<>();
        for (ShellRoute r : ShellRoute.values()) {
            if (r.isRoleAllowed(currentRole)) {
                allowed.add(r);
            }
        }
        return allowed;
    }

    public void toggleLocale() {
        if ("bn_BD".equals(locale.get())) {
            locale.set("en_BD");
        } else {
            locale.set("bn_BD");
        }
        statusMessage.set(isBengali() ? "ভাষা পরিবর্তন সম্পন্ন (বাংলা)" : "Language switched to English");
    }

    public boolean isBengali() {
        return "bn_BD".equals(locale.get()) || "bn".equalsIgnoreCase(locale.get());
    }

    public void toggleSidebar() {
        isSidebarCollapsed.set(!isSidebarCollapsed.get());
    }

    public void setOnlineStatus(boolean online) {
        isOnline.set(online);
        statusMessage.set(online ? "Cloud Connectivity Active" : "Offline Mode - Operating with Local SQLite Outbox");
    }

    public void setPendingOutboxCount(int count) {
        pendingOutboxCount.set(Math.max(0, count));
    }

    public void clearError() {
        errorMessage.set("");
    }

    // Property getters
    public ObjectProperty<ShellRoute> activeRouteProperty() { return activeRoute; }
    public ShellRoute getActiveRoute() { return activeRoute.get(); }

    public StringProperty localeProperty() { return locale; }
    public String getLocale() { return locale.get(); }
    public void setLocale(String loc) { locale.set(loc); }

    public StringProperty themeProperty() { return theme; }
    public String getTheme() { return theme.get(); }

    public BooleanProperty isOnlineProperty() { return isOnline; }
    public boolean isOnline() { return isOnline.get(); }

    public BooleanProperty isSidebarCollapsedProperty() { return isSidebarCollapsed; }
    public boolean isSidebarCollapsed() { return isSidebarCollapsed.get(); }

    public StringProperty errorMessageProperty() { return errorMessage; }
    public String getErrorMessage() { return errorMessage.get(); }

    public StringProperty statusMessageProperty() { return statusMessage; }
    public String getStatusMessage() { return statusMessage.get(); }

    public StringProperty tenantIdProperty() { return tenantId; }
    public String getTenantId() { return tenantId.get(); }

    public StringProperty branchIdProperty() { return branchId; }
    public String getBranchId() { return branchId.get(); }

    public StringProperty userIdProperty() { return userId; }
    public String getUserId() { return userId.get(); }

    public StringProperty userRoleProperty() { return userRole; }
    public String getUserRole() { return userRole.get(); }

    public StringProperty deviceIdProperty() { return deviceId; }
    public String getDeviceId() { return deviceId.get(); }

    public IntegerProperty pendingOutboxCountProperty() { return pendingOutboxCount; }
    public int getPendingOutboxCount() { return pendingOutboxCount.get(); }
}
