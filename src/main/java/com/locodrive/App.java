package com.locodrive;

import com.locodrive.util.ConfigManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.awt.*;
import java.awt.event.ActionListener;
import java.io.InputStream;
import java.net.URL;
import java.util.Objects;

/**
 * Main JavaFX Application class.
 * Bootstraps the primary stage, loads config, sets up the system tray.
 */
public class App extends Application {

    private static final String APP_TITLE = "LocoDrive";
    private TrayIcon trayIcon;

    @Override
    public void start(Stage primaryStage) {
        try {
            doStart(primaryStage);
        } catch (Exception e) {
            // Print the REAL exception so we can debug it
            System.err.println("=== LocoDrive startup error ===");
            e.printStackTrace(System.err);
            // Show a simple error alert then exit
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.ERROR);
            alert.setTitle("Startup Error");
            alert.setHeaderText("LocoDrive failed to start");
            alert.setContentText(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            alert.showAndWait();
            Platform.exit();
        }
    }

    private void doStart(Stage primaryStage) throws Exception {
        // Store stage reference in context
        AppContext ctx = AppContext.getInstance();
        ctx.setPrimaryStage(primaryStage);

        // Load saved config if it exists
        ConfigManager.getInstance().loadConfig();

        // Load main FXML
        URL fxmlUrl = getClass().getResource("/fxml/MainView.fxml");
        Objects.requireNonNull(fxmlUrl, "MainView.fxml not found on classpath — check resources directory");
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();

        // Apply global stylesheet
        URL cssUrl = getClass().getResource("/css/app.css");
        Scene scene = new Scene(root, 1100, 700);
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        // Stage configuration
        primaryStage.setTitle(APP_TITLE);
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);

        // Try to load app icon (optional — silently skip if missing)
        try {
            InputStream iconStream = getClass().getResourceAsStream("/images/app-icon.png");
            if (iconStream != null) {
                primaryStage.getIcons().add(new Image(iconStream));
            }
        } catch (Exception ignored) {}

        // Setup system tray asynchronously on AWT thread (macOS safe)
        setupSystemTrayAsync(primaryStage);

        // Intercept close → hide to tray instead of exit
        primaryStage.setOnCloseRequest(event -> {
            event.consume();
            boolean trayAvailable = !GraphicsEnvironment.isHeadless()
                && SystemTray.isSupported()
                && trayIcon != null;
            if (trayAvailable) {
                primaryStage.hide();
                try {
                    trayIcon.displayMessage(
                        APP_TITLE,
                        "Server is still running. Right-click the tray icon to exit.",
                        TrayIcon.MessageType.INFO
                    );
                } catch (Exception ignored) {}
            } else {
                handleExit();
            }
        });

        primaryStage.show();
    }

    /**
     * Schedules system tray setup on the AWT Event Dispatch Thread.
     * This is the correct pattern on macOS to avoid deadlocks.
     */
    private void setupSystemTrayAsync(Stage primaryStage) {
        // Guard: headless environments (CI, SSH) don't have a display
        if (GraphicsEnvironment.isHeadless()) return;
        if (!SystemTray.isSupported()) {
            System.out.println("System tray not supported on this platform.");
            return;
        }

        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                // Don't let JavaFX auto-exit when window is hidden
                Platform.setImplicitExit(false);

                java.awt.Image trayImage = createTrayImage();
                PopupMenu popup = new PopupMenu();

                MenuItem showItem = new MenuItem("Show LocoDrive");
                MenuItem stopItem = new MenuItem("Stop Server");
                MenuItem exitItem = new MenuItem("Exit");

                ActionListener showAction = e -> Platform.runLater(() -> {
                    primaryStage.show();
                    primaryStage.toFront();
                });
                ActionListener stopAction = e -> Platform.runLater(() -> {
                    AppContext c = AppContext.getInstance();
                    if (c.getServer() != null) c.getServer().stop();
                });
                ActionListener exitAction = e -> Platform.runLater(this::handleExit);

                showItem.addActionListener(showAction);
                stopItem.addActionListener(stopAction);
                exitItem.addActionListener(exitAction);

                popup.add(showItem);
                popup.add(stopItem);
                popup.addSeparator();
                popup.add(exitItem);

                trayIcon = new TrayIcon(trayImage, APP_TITLE, popup);
                trayIcon.setImageAutoSize(true);
                trayIcon.addActionListener(showAction);

                SystemTray.getSystemTray().add(trayIcon);
                System.out.println("System tray icon added.");

            } catch (Exception e) {
                System.err.println("System tray setup failed (non-fatal): " + e.getMessage());
            }
        });
    }

    /**
     * Creates a simple colored drive icon for the system tray using AWT Graphics2D.
     */
    private java.awt.Image createTrayImage() {
        int size = 64;
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(79, 156, 249));
        g.fillOval(0, 0, size, size);
        g.setColor(java.awt.Color.WHITE);
        g.fillRoundRect(14, 18, 36, 28, 4, 4);
        g.setColor(new java.awt.Color(79, 156, 249));
        g.fillRect(18, 22, 28, 4);
        g.fillRect(18, 30, 28, 4);
        g.fillRect(18, 38, 18, 4);
        g.dispose();
        return img;
    }

    private void handleExit() {
        try {
            AppContext ctx = AppContext.getInstance();
            if (ctx.getServer() != null) ctx.getServer().stop();
            ConfigManager.getInstance().saveConfig();
        } catch (Exception ignored) {}
        try {
            if (trayIcon != null && !GraphicsEnvironment.isHeadless() && SystemTray.isSupported()) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
        } catch (Exception ignored) {}
        Platform.exit();
        System.exit(0);
    }

    @Override
    public void stop() {
        handleExit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
