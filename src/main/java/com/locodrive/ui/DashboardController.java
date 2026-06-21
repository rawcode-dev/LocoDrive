package com.locodrive.ui;

import com.locodrive.AppContext;
import com.locodrive.model.ServerConfig;
import com.locodrive.server.LocalFileServer;
import com.locodrive.util.QRCodeGenerator;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.awt.Desktop;
import java.net.URI;
import java.net.URL;
import java.util.ResourceBundle;

/**
 * Step 5 — Dashboard.
 * Shows server status, QR code, real-time access log, and Start/Stop control.
 */
public class DashboardController extends BaseStepController implements Initializable {

    @FXML private Label serverUrlLabel;
    @FXML private Label statusBadge;
    @FXML private Label sessionCount;
    @FXML private Label uptimeLabel;
    @FXML private ImageView qrCodeView;
    @FXML private TextArea logArea;
    @FXML private Button startStopBtn;
    @FXML private VBox serverInfoPanel;

    private Timeline uptimeTimer;
    private long serverStartTime;

    @Override
    public void initialize(URL url, ResourceBundle rb) {}

    @Override
    public void onEnter() {
        // Auto-start server if not already running
        if (AppContext.getInstance().getServer() == null
            || !AppContext.getInstance().getServer().isRunning()) {
            startServer();
        } else {
            refreshUI();
        }
    }

    // ── Server Lifecycle ──────────────────────────────────────────────────────
    @FXML
    private void onStartStop() {
        LocalFileServer server = AppContext.getInstance().getServer();
        if (server != null && server.isRunning()) {
            stopServer();
        } else {
            startServer();
        }
    }

    private void startServer() {
        ServerConfig cfg = AppContext.getInstance().getConfig();

        // Run in background thread so UI stays responsive
        Thread serverThread = new Thread(() -> {
            try {
                LocalFileServer server = new LocalFileServer(cfg);
                server.setLogListener(entry -> Platform.runLater(() -> appendLog(entry)));
                server.start();

                AppContext.getInstance().setServer(server);
                serverStartTime = System.currentTimeMillis();

                Platform.runLater(() -> {
                    refreshUI();
                    startUptimeTimer();
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    appendLog("❌ Failed to start server: " + e.getMessage());
                    setStatusStopped();
                    if (startStopBtn != null) startStopBtn.setText("▶ Start Server");
                });
            }
        }, "server-start-thread");
        serverThread.setDaemon(true);
        serverThread.start();

        setStatusStarting();
    }

    private void stopServer() {
        LocalFileServer server = AppContext.getInstance().getServer();
        if (server != null) {
            new Thread(() -> {
                server.stop();
                Platform.runLater(() -> {
                    stopUptimeTimer();
                    setStatusStopped();
                    if (startStopBtn != null) startStopBtn.setText("▶ Start Server");
                    appendLog("🔴 Server stopped.");
                });
            }, "server-stop-thread").start();
        }
    }

    // ── UI Refresh ────────────────────────────────────────────────────────────
    private void refreshUI() {
        ServerConfig cfg = AppContext.getInstance().getConfig();
        String url = cfg.getServerUrl();

        if (serverUrlLabel != null) serverUrlLabel.setText(url);

        // QR Code
        if (qrCodeView != null) {
            javafx.scene.image.Image qr = QRCodeGenerator.generateQR(url, 220);
            if (qr != null) qrCodeView.setImage(qr);
        }

        setStatusRunning();
        if (startStopBtn != null) startStopBtn.setText("⏹ Stop Server");

        // Display existing log entries
        LocalFileServer server = AppContext.getInstance().getServer();
        if (server != null && logArea != null) {
            logArea.clear();
            for (String entry : server.getAccessLog()) {
                logArea.appendText(entry + "\n");
            }
        }
    }

    private void setStatusRunning() {
        if (statusBadge != null) {
            statusBadge.setText("● RUNNING");
            statusBadge.getStyleClass().removeAll("badge-stopped", "badge-starting");
            statusBadge.getStyleClass().add("badge-running");
        }
    }

    private void setStatusStopped() {
        if (statusBadge != null) {
            statusBadge.setText("● STOPPED");
            statusBadge.getStyleClass().removeAll("badge-running", "badge-starting");
            statusBadge.getStyleClass().add("badge-stopped");
        }
    }

    private void setStatusStarting() {
        if (statusBadge != null) {
            statusBadge.setText("◌ STARTING...");
            statusBadge.getStyleClass().removeAll("badge-running", "badge-stopped");
            statusBadge.getStyleClass().add("badge-starting");
        }
    }

    private void appendLog(String line) {
        if (logArea != null) {
            logArea.appendText(line + "\n");
            // Scroll to bottom
            logArea.setScrollTop(Double.MAX_VALUE);
        }
        // Update session count
        LocalFileServer server = AppContext.getInstance().getServer();
        if (server != null && sessionCount != null) {
            sessionCount.setText(String.valueOf(server.getActiveSessionCount()));
        }
    }

    // ── Uptime Timer ──────────────────────────────────────────────────────────
    private void startUptimeTimer() {
        stopUptimeTimer();
        uptimeTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            long elapsed = (System.currentTimeMillis() - serverStartTime) / 1000;
            long h = elapsed / 3600, m = (elapsed % 3600) / 60, s = elapsed % 60;
            if (uptimeLabel != null) {
                uptimeLabel.setText(String.format("%02d:%02d:%02d", h, m, s));
            }
        }));
        uptimeTimer.setCycleCount(Animation.INDEFINITE);
        uptimeTimer.play();
    }

    private void stopUptimeTimer() {
        if (uptimeTimer != null) { uptimeTimer.stop(); uptimeTimer = null; }
        if (uptimeLabel != null) uptimeLabel.setText("00:00:00");
    }

    // ── Actions ───────────────────────────────────────────────────────────────
    @FXML
    private void onOpenBrowser() {
        String url = AppContext.getInstance().getConfig().getServerUrl();
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            appendLog("⚠ Could not open browser: " + e.getMessage());
        }
    }

    @FXML
    private void onClearLog() {
        if (logArea != null) logArea.clear();
    }

    @FXML
    private void onReconfigure() {
        stopServer();
        mainController.jumpToWelcome();
    }
}
