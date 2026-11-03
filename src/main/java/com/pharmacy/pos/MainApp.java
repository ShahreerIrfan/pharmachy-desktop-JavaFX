package com.pharmacy.pos;

import com.pharmacy.pos.controllers.PosController;
import com.pharmacy.pos.models.TenantSession;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

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

    private static Stage primaryStageInstance;

    public static Stage getPrimaryStage() {
        return primaryStageInstance;
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStageInstance = primaryStage;
        showLoginGate(primaryStage);
    }

    /**
     * Shows the Terminal Sign-In & Shift Activation Gate as the primary entry scene.
     */
    public static void showLoginGate(Stage stage) {
        try {
            URL fxmlUrl = MainApp.class.getResource("/com/pharmacy/pos/views/login_gate.fxml");
            Parent root;
            if (fxmlUrl != null) {
                root = FXMLLoader.load(fxmlUrl);
            } else {
                root = new javafx.scene.layout.VBox();
            }

            Scene scene = new Scene(root, 1280, 800);
            URL cssUrl = MainApp.class.getResource("/com/pharmacy/pos/views/login-gate.css");
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            }

            stage.setTitle(APP_NAME + " v" + APP_VERSION + " - Counter Sign-In & Shift Gate");
            stage.setMinWidth(1024);
            stage.setMinHeight(700);
            stage.setScene(scene);
            stage.show();
        } catch (Exception e) {
            System.err.println("Failed to start JavaFX Login Gate UI: " + e.getMessage());
        }
    }

    /**
     * Transitions from Login Gate into the Main POS Billing & Sales Counter screen.
     */
    public static void showPosScreen(
            Stage stage,
            TenantSession session,
            String staffName,
            String shiftName,
            String openingFloat) {
        try {
            URL fxmlUrl = MainApp.class.getResource("/com/pharmacy/pos/views/main.fxml");
            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            Parent root = loader.load();

            PosController controller = loader.getController();
            if (controller != null) {
                controller.setSessionDetails(session, staffName, shiftName, openingFloat);
            }

            Scene scene = new Scene(root, 1280, 800);
            URL cssUrl = MainApp.class.getResource("/com/pharmacy/pos/styles/tokens.css");
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            }

            stage.setTitle(APP_NAME + " v" + APP_VERSION + " - " + session.getBranchId() + " (" + staffName + ")");
            stage.setScene(scene);
            stage.show();
        } catch (Exception e) {
            System.err.println("Failed to transition to POS Sales UI: " + e.getMessage());
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
