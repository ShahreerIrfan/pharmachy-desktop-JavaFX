package com.pharmacy.pos;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;

/**
 * Enterprise Main Entry Point for Bangladesh Pharmacy Desktop POS Client.
 * Architecture Decision: ADR-0003, ADR-0007, ADR-0021 & ADR-0023.
 */
public class MainApp extends Application {
    public static final String APP_NAME = "Bangladesh Pharmacy Desktop POS";
    public static final String APP_VERSION = "2.0.0";
    public static final String OFFLINE_RECEIPT_WATERMARK_EN = "*** OFFLINE RECEIPT - PENDING CLOUD CONFIRMATION ***";
    public static final String OFFLINE_RECEIPT_WATERMARK_BN = "*** অফলাইন রসিদ - ক্লাউড কনফার্মেশন অপেক্ষমান ***";

    @Override
    public void start(Stage primaryStage) {
        try {
            URL fxmlUrl = getClass().getResource("/com/pharmacy/pos/views/main.fxml");
            Parent root;
            if (fxmlUrl != null) {
                root = FXMLLoader.load(fxmlUrl);
            } else {
                root = new javafx.scene.layout.VBox();
            }

            Scene scene = new Scene(root, 1280, 800);
            URL cssUrl = getClass().getResource("/com/pharmacy/pos/styles/tokens.css");
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            }

            primaryStage.setTitle(APP_NAME + " v" + APP_VERSION);
            primaryStage.setMinWidth(1024);
            primaryStage.setMinHeight(700);
            primaryStage.setScene(scene);
            primaryStage.show();
        } catch (Exception e) {
            System.err.println("Failed to start JavaFX POS UI: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        if (args.length > 0 && "--cli".equals(args[0])) {
            System.out.println("Starting " + APP_NAME + " v" + APP_VERSION + " [CLI Mode]");
        } else {
            try {
                launch(args);
            } catch (UnsupportedOperationException e) {
                // Fallback for headless environments without DISPLAY
                System.out.println("Starting " + APP_NAME + " v" + APP_VERSION + " [Headless Fallback]");
            }
        }
    }
}
