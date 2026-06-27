package com.locodrive.ui;

import com.locodrive.AppContext;
import com.locodrive.model.ServerConfig;
import com.locodrive.util.NetworkDetector;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Step 1 — Network Setup.
 * Auto-detects LAN IPs, lets user confirm binding IP and port.
 * Validates port availability before proceeding.
 */
public class NetworkSetupController extends BaseStepController implements Initializable, MainController.Validatable {

    @FXML private ComboBox<String> ipCombo;
    @FXML private TextField portField;
    @FXML private Label statusLabel;
    @FXML private Label warningLabel;
    @FXML private HBox portStatusRow;
    @FXML private Label portStatusIcon;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        portField.setText("8080");

        // Make the dropdown editable so users can type any custom IP
        ipCombo.setEditable(true);

        // Real-time port validation
        portField.textProperty().addListener((obs, old, val) -> checkPort());

        // Warn on port change
        portField.setOnKeyReleased(e -> checkPort());
    }

    @Override
    public void onEnter() {
        ServerConfig config = AppContext.getInstance().getConfig();
        String savedIp = config.getBindAddress();

        // Refresh network interfaces each time we enter this step
        List<String> ips = NetworkDetector.getLanAddresses();
        
        // Inject the saved IP into the list if it's not detected automatically
        if (savedIp != null && !savedIp.isBlank() && !ips.contains(savedIp)) {
            ips.add(0, savedIp);
        }

        ipCombo.setItems(FXCollections.observableArrayList(ips));

        // Pre-select the saved IP if one exists, otherwise fallback to the best detected IP
        if (savedIp != null && !savedIp.isBlank()) {
            ipCombo.setValue(savedIp);
        } else if (!ips.isEmpty()) {
            ipCombo.setValue(NetworkDetector.getBestAddress());
        }

        checkPort();
        updateWarning();
    }

    @FXML
    private void onRefreshIPs() {
        onEnter();
        animateStatus("🔄 Network interfaces refreshed");
    }

    @FXML
    private void onBack() { mainController.previousStep(); }

    @FXML
    private void onNext() { mainController.nextStep(); }

    @Override
    public boolean validate() {
        String ip = ipCombo.getValue();
        String portText = portField.getText().trim();

        if (ip == null || ip.isBlank()) {
            showError("Please select a network IP address.");
            return false;
        }

        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            showError("Port must be a number between 1024 and 65535.");
            return false;
        }

        if (port < 1024 || port > 65535) {
            showError("Port must be between 1024 and 65535.");
            return false;
        }

        if (!NetworkDetector.isPortAvailable(port)) {
            showError("Port " + port + " is already in use. Try another port.");
            return false;
        }

        // Save to config
        ServerConfig config = AppContext.getInstance().getConfig();
        config.setBindAddress(ip);
        config.setPort(port);

        animateStatus("✅ Network configured: " + ip + ":" + port);
        return true;
    }

    private void checkPort() {
        String portText = portField.getText().trim();
        try {
            int port = Integer.parseInt(portText);
            if (port < 1024 || port > 65535) {
                setPortStatus("⚠ Port out of range", "warning");
            } else if (!NetworkDetector.isPortAvailable(port)) {
                setPortStatus("✗ Port in use", "error");
            } else {
                setPortStatus("✓ Port available", "success");
            }
        } catch (NumberFormatException e) {
            setPortStatus("✗ Invalid", "error");
        }
    }

    private void setPortStatus(String text, String styleClass) {
        if (portStatusIcon == null) return;
        portStatusIcon.setText(text);
        portStatusIcon.getStyleClass().removeAll("success", "error", "warning");
        portStatusIcon.getStyleClass().add(styleClass);
    }

    private void updateWarning() {
        if (warningLabel != null) {
            warningLabel.setText("⚠️  Your files will be accessible to all devices on your Wi-Fi or LAN network. "
                + "Do NOT use on public Wi-Fi without proper user accounts.");
        }
    }

    private void showError(String msg) {
        if (statusLabel != null) {
            statusLabel.setText("❌ " + msg);
            statusLabel.getStyleClass().removeAll("success-text", "warning-text");
            statusLabel.getStyleClass().add("error-text");
        }
    }

    private void animateStatus(String msg) {
        if (statusLabel != null) {
            statusLabel.setText(msg);
            statusLabel.getStyleClass().removeAll("error-text", "warning-text");
            statusLabel.getStyleClass().add("success-text");
        }
    }
}
