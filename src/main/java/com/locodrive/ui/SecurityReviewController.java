package com.locodrive.ui;

import com.locodrive.AppContext;
import com.locodrive.model.ServerConfig;
import com.locodrive.model.SharedFolder;
import com.locodrive.model.User;
import com.locodrive.util.ConfigManager;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Step 4 — Security Review.
 * Shows a summary of all settings and highlights security concerns.
 * User must acknowledge before launching the server.
 */
public class SecurityReviewController extends BaseStepController implements Initializable {

    @FXML private Label networkSummary;
    @FXML private Label folderSummary;
    @FXML private Label userSummary;
    @FXML private Label guestSummary;
    @FXML private VBox warningBox;
    @FXML private Label launchStatus;

    @Override
    public void initialize(URL url, ResourceBundle rb) {}

    @Override
    public void onEnter() {
        refreshSummary();
    }

    private void refreshSummary() {
        ServerConfig cfg = AppContext.getInstance().getConfig();

        // Network summary
        if (networkSummary != null) {
            networkSummary.setText("Server Address: " + cfg.getBindAddress() + ":" + cfg.getPort()
                + "\nURL: " + cfg.getServerUrl());
        }

        // Folder summary
        if (folderSummary != null) {
            StringBuilder sb = new StringBuilder();
            for (SharedFolder f : cfg.getSharedFolders()) {
                sb.append("• ").append(f.getAlias())
                  .append(f.isGuestAccessible() ? "  [PUBLIC — No login needed]" : "  [Login required]")
                  .append("\n  ").append(f.getPath()).append("\n");
            }
            folderSummary.setText(sb.toString().trim());
        }

        // User summary
        if (userSummary != null) {
            StringBuilder sb = new StringBuilder();
            for (User u : cfg.getUsers()) {
                sb.append("• ").append(u.getUsername())
                  .append("  (").append(u.getRole().name()).append(")")
                  .append(u.isEnabled() ? "" : "  [DISABLED]").append("\n");
            }
            userSummary.setText(sb.toString().trim());
        }

        // Guest summary
        boolean hasGuestFolders = cfg.getSharedFolders().stream().anyMatch(SharedFolder::isGuestAccessible);
        if (guestSummary != null) {
            if (hasGuestFolders) {
                long count = cfg.getSharedFolders().stream().filter(SharedFolder::isGuestAccessible).count();
                guestSummary.setText("⚠️  " + count + " folder(s) are publicly accessible without login.");
                guestSummary.getStyleClass().add("warning-text");
            } else {
                guestSummary.setText("✅ All folders require login to access.");
                guestSummary.getStyleClass().removeAll("warning-text");
            }
        }

        // Warning box
        if (warningBox != null) {
            warningBox.getChildren().clear();
            if (hasGuestFolders) addWarning("⚠️  Guest folders expose files to anyone on your network without a password.");
            if (cfg.getPort() < 1024) addWarning("⚠️  Ports below 1024 may require administrator/root privileges.");
            addWarning("ℹ️  This server is LOCAL ONLY. Your files are NOT accessible from the internet.");
            addWarning("ℹ️  Connection uses HTTP (not encrypted). Do not use on untrusted networks.");
        }
    }

    private void addWarning(String text) {
        Label lbl = new Label(text);
        lbl.setWrapText(true);
        lbl.getStyleClass().add("review-warning-item");
        warningBox.getChildren().add(lbl);
    }

    @FXML
    private void onBack() { mainController.previousStep(); }

    @FXML
    private void onLaunch() {
        ServerConfig cfg = AppContext.getInstance().getConfig();
        if (!cfg.isReady()) {
            if (launchStatus != null) {
                launchStatus.setText("❌ Configuration incomplete. Please go back and check all steps.");
                launchStatus.getStyleClass().add("error-text");
            }
            return;
        }

        // Save config to disk
        ConfigManager.getInstance().saveConfig();
        if (launchStatus != null) {
            launchStatus.setText("💾 Configuration saved.");
        }

        // Navigate to dashboard (which will start the server)
        mainController.nextStep();
    }
}
