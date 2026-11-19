package com.pharmacy.pos.shell;

import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/**
 * ShellSvgIcons - Apple macOS-inspired vector SVG icons.
 * Architecture Decision: ADR-0007, ADR-0008, ADR-0321.
 * Strict zero-emoji policy; uses clean, accessible SVG geometry.
 */
public class ShellSvgIcons {

    public static final String PATH_DASHBOARD = "M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z";
    public static final String PATH_POS_CART = "M7 18c-1.1 0-1.99.9-1.99 2S5.9 22 7 22s2-.9 2-2-.9-2-2-2zM1 2v2h2l3.6 7.59-1.35 2.45c-.16.28-.25.61-.25.96 0 1.1.9 2 2 2h12v-2H7.42c-.14 0-.25-.11-.25-.25l.03-.12.9-1.63h7.45c.75 0 1.41-.41 1.75-1.03l3.58-6.49c.08-.14.12-.31.12-.48 0-.55-.45-1-1-1H5.21l-.94-2H1zm16 16c-1.1 0-1.99.9-1.99 2s.89 2 1.99 2 2-.9 2-2-.9-2-2-2z";
    public static final String PATH_INVENTORY_BOX = "M20 7l-8-4-8 4 8 4 8-4zm0 2.24l-8 4-8-4V17l8 4 8-4V9.24z";
    public static final String PATH_PRESCRIPTION_PILL = "M6 3h12v2H6zm11 3H7c-1.1 0-2 .9-2 2v11c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-1 8h-3v3h-2v-3H8v-2h3V9h2v3h3v2z";
    public static final String PATH_CLOUD_SYNC = "M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM17 13l-5 5-5-5h3V9h4v4h3z";
    public static final String PATH_DAILY_SHIFT = "M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-2 10h-4v4h-2v-4H7v-2h4V7h2v4h4v2z";
    public static final String PATH_SETTINGS = "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z";
    public static final String PATH_CHEVRON_LEFT = "M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z";
    public static final String PATH_CHEVRON_RIGHT = "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z";

    public static SVGPath createIcon(String pathData, Color color, double scale) {
        SVGPath svg = new SVGPath();
        svg.setContent(pathData);
        svg.setFill(color != null ? color : Color.web("#4B5563"));
        svg.setScaleX(scale);
        svg.setScaleY(scale);
        return svg;
    }

    public static SVGPath createNavIcon(String pathData, boolean isActive) {
        Color color = isActive ? Color.web("#0284C7") : Color.web("#6B7280");
        return createIcon(pathData, color, 0.85);
    }
}
