package com.locodrive.ui;

import com.locodrive.AppContext;
import com.locodrive.model.User;
import com.locodrive.util.PasswordUtils;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Step 3 — User Management.
 * Create/remove users and assign roles (Admin, User).
 * At least one Admin is required to proceed.
 */
public class UserManagementController extends BaseStepController implements Initializable, MainController.Validatable {

    // ── Table ─────────────────────────────────────────────────────────────────
    @FXML private TableView<User> userTable;
    @FXML private TableColumn<User, String> usernameCol;
    @FXML private TableColumn<User, String> roleCol;
    @FXML private TableColumn<User, String> statusCol;

    // ── Add User Form ─────────────────────────────────────────────────────────
    @FXML private VBox addUserForm;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private ComboBox<String> roleCombo;
    @FXML private Label strengthLabel;
    @FXML private Label formStatusLabel;

    // ── General ───────────────────────────────────────────────────────────────
    @FXML private Label statusLabel;

    private final ObservableList<User> users = FXCollections.observableArrayList();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        setupTable();
        setupForm();
        users.addAll(AppContext.getInstance().getConfig().getUsers());
    }

    @Override
    public void onEnter() {
        users.clear();
        users.addAll(AppContext.getInstance().getConfig().getUsers());
        updateStatus();
    }

    private void setupTable() {
        userTable.setItems(users);
        userTable.setPlaceholder(new Label("No users yet.\nAdd an Admin user to get started."));
        userTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        usernameCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getUsername()));
        roleCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getRole().name()));
        statusCol.setCellValueFactory(d -> new SimpleStringProperty(
            d.getValue().isEnabled() ? "✅ Active" : "⛔ Disabled"));
    }

    private void setupForm() {
        if (roleCombo != null) {
            roleCombo.setItems(FXCollections.observableArrayList("ADMIN", "USER"));
            roleCombo.setValue("USER");
        }
        // Live password strength indicator
        if (passwordField != null) {
            passwordField.textProperty().addListener((obs, old, val) -> {
                if (strengthLabel == null) return;
                String strength = PasswordUtils.strengthLabel(val);
                strengthLabel.setText("Strength: " + strength);
                strengthLabel.getStyleClass().removeAll("strength-weak", "strength-fair", "strength-strong");
                switch (strength) {
                    case "WEAK" -> strengthLabel.getStyleClass().add("strength-weak");
                    case "FAIR" -> strengthLabel.getStyleClass().add("strength-fair");
                    case "STRONG" -> strengthLabel.getStyleClass().add("strength-strong");
                }
            });
        }
    }

    @FXML
    private void onAddUser() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        String confirm = confirmPasswordField.getText();
        String roleStr = roleCombo.getValue();

        // ── Validations ────────────────────────────────────────────────────────
        if (username.isBlank()) {
            showFormError("Username cannot be empty."); return;
        }
        if (username.length() < 3 || username.length() > 32) {
            showFormError("Username must be 3–32 characters."); return;
        }
        if (!username.matches("[a-zA-Z0-9_.-]+")) {
            showFormError("Username may only contain letters, numbers, _ . -"); return;
        }
        if (users.stream().anyMatch(u -> u.getUsername().equalsIgnoreCase(username))) {
            showFormError("Username '" + username + "' is already taken."); return;
        }
        if (password.length() < PasswordUtils.MIN_PASSWORD_LENGTH) {
            showFormError("Password must be at least " + PasswordUtils.MIN_PASSWORD_LENGTH + " characters."); return;
        }
        if (!password.equals(confirm)) {
            showFormError("Passwords do not match."); return;
        }

        // Warn on weak password (but allow)
        if ("WEAK".equals(PasswordUtils.strengthLabel(password))) {
            Alert warn = new Alert(Alert.AlertType.CONFIRMATION);
            warn.setTitle("Weak Password");
            warn.setHeaderText("This password is considered weak.");
            warn.setContentText("Consider using at least 8 characters with uppercase letters and numbers.\n\nContinue anyway?");
            warn.initOwner(AppContext.getInstance().getPrimaryStage());
            if (warn.showAndWait().map(btn -> btn != ButtonType.OK).orElse(true)) return;
        }

        User.Role role = User.Role.valueOf(roleStr);

        // First Admin warning if adding User role without any admin
        if (role == User.Role.USER && users.stream().noneMatch(User::isAdmin)) {
            Alert warn = new Alert(Alert.AlertType.WARNING);
            warn.setTitle("No Admin User");
            warn.setHeaderText("You need at least one Admin.");
            warn.setContentText("Please create an Admin user first.");
            warn.initOwner(AppContext.getInstance().getPrimaryStage());
            warn.showAndWait();
            roleCombo.setValue("ADMIN");
            return;
        }

        String hashed = PasswordUtils.hash(password);
        User user = new User(username, hashed, role);
        users.add(user);
        AppContext.getInstance().getConfig().getUsers().add(user);

        // Clear form
        usernameField.clear();
        passwordField.clear();
        confirmPasswordField.clear();
        if (strengthLabel != null) { strengthLabel.setText(""); strengthLabel.getStyleClass().removeAll("strength-weak","strength-fair","strength-strong"); }
        if (formStatusLabel != null) formStatusLabel.setText("");

        updateStatus();
    }

    @FXML
    private void onRemoveSelected() {
        User selected = userTable.getSelectionModel().getSelectedItem();
        if (selected == null) { showError("Select a user to remove."); return; }
        if (selected.isAdmin() && users.stream().filter(User::isAdmin).count() <= 1) {
            showError("Cannot remove the only Admin user."); return;
        }
        users.remove(selected);
        AppContext.getInstance().getConfig().getUsers().remove(selected);
        updateStatus();
    }

    @FXML
    private void onToggleDisable() {
        User selected = userTable.getSelectionModel().getSelectedItem();
        if (selected == null) { showError("Select a user first."); return; }
        selected.setEnabled(!selected.isEnabled());
        userTable.refresh();
    }

    @FXML
    private void onBack() { mainController.previousStep(); }

    @FXML
    private void onNext() { mainController.nextStep(); }

    @Override
    public boolean validate() {
        AppContext.getInstance().getConfig().setUsers(users.stream().toList());
        if (!AppContext.getInstance().getConfig().hasAdmin()) {
            showError("You must have at least one Admin user to proceed.");
            return false;
        }
        return true;
    }

    private void updateStatus() {
        long admins = users.stream().filter(User::isAdmin).count();
        long regular = users.stream().filter(u -> !u.isAdmin()).count();
        if (users.isEmpty()) {
            showError("No users added. Add at least one Admin.");
        } else if (admins == 0) {
            showError("No Admin user. At least one Admin required.");
        } else {
            showSuccess(admins + " Admin, " + regular + " User account(s) configured.");
        }
    }

    private void showFormError(String msg) {
        if (formStatusLabel != null) { formStatusLabel.setText("❌ " + msg); formStatusLabel.getStyleClass().removeAll("success-text"); formStatusLabel.getStyleClass().add("error-text"); }
    }
    private void showError(String msg) {
        if (statusLabel != null) { statusLabel.setText("❌ " + msg); statusLabel.getStyleClass().removeAll("success-text"); statusLabel.getStyleClass().add("error-text"); }
    }
    private void showSuccess(String msg) {
        if (statusLabel != null) { statusLabel.setText("✅ " + msg); statusLabel.getStyleClass().removeAll("error-text"); statusLabel.getStyleClass().add("success-text"); }
    }
}
