package com.pharmacy.pos.shell;

import java.util.Arrays;
import java.util.List;

/**
 * ShellRoute - Authoritative navigation destinations in POS App Shell.
 * Architecture Decision: ADR-0321 (P321).
 */
public enum ShellRoute {
    DASHBOARD(
            "ROUTE_DASHBOARD",
            "Dashboard",
            "ড্যাশবোর্ড",
            "F1",
            ShellSvgIcons.PATH_DASHBOARD,
            Arrays.asList("CASHIER", "STAFF_PHARMACIST", "BRANCH_MANAGER", "PHARMACY_OWNER")
    ),
    POS_SALES(
            "ROUTE_POS_SALES",
            "Sales & Billing",
            "কাউন্টার ও বিক্রয়",
            "F2",
            ShellSvgIcons.PATH_POS_CART,
            Arrays.asList("CASHIER", "STAFF_PHARMACIST", "BRANCH_MANAGER", "PHARMACY_OWNER")
    ),
    INVENTORY_LEDGER(
            "ROUTE_INVENTORY_LEDGER",
            "Inventory Ledger",
            "মজুদ লেজার",
            "F3",
            ShellSvgIcons.PATH_INVENTORY_BOX,
            Arrays.asList("STAFF_PHARMACIST", "BRANCH_MANAGER", "PHARMACY_OWNER")
    ),
    PRESCRIPTIONS(
            "ROUTE_PRESCRIPTION_VERIFICATION",
            "Prescription Dispensing",
            "প্রেসক্রিপশন বিতরণ",
            "F4",
            ShellSvgIcons.PATH_PRESCRIPTION_PILL,
            Arrays.asList("STAFF_PHARMACIST", "BRANCH_MANAGER", "PHARMACY_OWNER")
    ),
    OFFLINE_SYNC(
            "ROUTE_OFFLINE_SYNC_CENTER",
            "Offline Sync Center",
            "অফলাইন সিঙ্ক সেন্টার",
            "F5",
            ShellSvgIcons.PATH_CLOUD_SYNC,
            Arrays.asList("CASHIER", "STAFF_PHARMACIST", "BRANCH_MANAGER", "PHARMACY_OWNER")
    ),
    DAILY_SHIFT(
            "ROUTE_DAILY_SHIFT",
            "Shift & Cash Drawer",
            "শিফট ও ড্রয়ার",
            "F9",
            ShellSvgIcons.PATH_DAILY_SHIFT,
            Arrays.asList("CASHIER", "STAFF_PHARMACIST", "BRANCH_MANAGER", "PHARMACY_OWNER")
    ),
    SETTINGS(
            "ROUTE_SETTINGS",
            "Manager Settings",
            "ম্যানেজার সেটিংস",
            "F10",
            ShellSvgIcons.PATH_SETTINGS,
            Arrays.asList("BRANCH_MANAGER", "PHARMACY_OWNER")
    );

    private final String routeKey;
    private final String labelEn;
    private final String labelBn;
    private final String shortcutHint;
    private final String iconPath;
    private final List<String> allowedRoles;

    ShellRoute(String routeKey, String labelEn, String labelBn, String shortcutHint, String iconPath, List<String> allowedRoles) {
        this.routeKey = routeKey;
        this.labelEn = labelEn;
        this.labelBn = labelBn;
        this.shortcutHint = shortcutHint;
        this.iconPath = iconPath;
        this.allowedRoles = allowedRoles;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public String getLabel(String locale) {
        if ("bn_BD".equalsIgnoreCase(locale) || "bn".equalsIgnoreCase(locale)) {
            return labelBn;
        }
        return labelEn;
    }

    public String getShortcutHint() {
        return shortcutHint;
    }

    public String getIconPath() {
        return iconPath;
    }

    public boolean isRoleAllowed(String userRole) {
        if (userRole == null) return false;
        return allowedRoles.contains(userRole.trim().toUpperCase());
    }
}
