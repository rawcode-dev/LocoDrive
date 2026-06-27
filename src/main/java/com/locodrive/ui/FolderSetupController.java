package com.locodrive.ui;

import com.locodrive.AppContext;
import com.locodrive.model.SharedFolder;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Step 2 — Folder Setup.
 * Users add one or more folders to share. Each folder can be marked as guest-accessible.
 */
public class FolderSetupController extends BaseStepController implements Initializable, MainController.Validatable {

    @FXML private TableView<SharedFolder> folderTable;
    @FXML private TableColumn<SharedFolder, String> aliasCol;
    @FXML private TableColumn<SharedFolder, String> pathCol;
    @FXML private TableColumn<SharedFolder, Boolean> guestCol;
    @FXML private TableColumn<SharedFolder, Boolean> uploadCol;
    @FXML private TableColumn<SharedFolder, String> statusCol;
    @FXML private Label statusLabel;
    @FXML private TextField aliasField;

    private final ObservableList<SharedFolder> folders = FXCollections.observableArrayList();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        setupTable();

        // Load existing folders from config (if any)
        List<SharedFolder> existing = AppContext.getInstance().getConfig().getSharedFolders();
        if (!existing.isEmpty()) {
            folders.addAll(existing);
        }
    }

    @Override
    public void onEnter() {
        // Sync from AppContext
        folders.clear();
        folders.addAll(AppContext.getInstance().getConfig().getSharedFolders());
        updateStatus();
    }

    private void setupTable() {
        folderTable.setItems(folders);
        folderTable.setEditable(true);
        folderTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        folderTable.setPlaceholder(new Label("No folders added yet.\nClick 'Add Folder' to get started."));

        // Alias column (editable)
        aliasCol.setCellValueFactory(data ->
            new SimpleStringProperty(data.getValue().getAlias()));
        aliasCol.setCellFactory(TextFieldTableCell.forTableColumn());
        aliasCol.setOnEditCommit(e -> {
            String newAlias = e.getNewValue().trim();
            if (!newAlias.isBlank()) e.getRowValue().setAlias(newAlias);
            folderTable.refresh();
        });

        // Path column
        pathCol.setCellValueFactory(data ->
            new SimpleStringProperty(data.getValue().getDisplayPath()));

        // Guest access column (checkbox)
        guestCol.setCellValueFactory(data -> {
            SharedFolder folder = data.getValue();
            SimpleBooleanProperty prop = new SimpleBooleanProperty(folder.isGuestAccessible());
            prop.addListener((obs, old, val) -> {
                folder.setGuestAccessible(val);
                if (val) {
                    showGuestWarning();
                }
                folderTable.refresh();
            });
            return prop;
        });
        guestCol.setCellFactory(CheckBoxTableCell.forTableColumn(guestCol));
        guestCol.setEditable(true);

        // Upload (allow uploads) column — inverted readOnly
        uploadCol.setCellValueFactory(data -> {
            SharedFolder folder = data.getValue();
            SimpleBooleanProperty prop = new SimpleBooleanProperty(!folder.isReadOnly());
            prop.addListener((obs, old, val) -> {
                folder.setReadOnly(!val);
                folderTable.refresh();
            });
            return prop;
        });
        uploadCol.setCellFactory(CheckBoxTableCell.forTableColumn(uploadCol));
        uploadCol.setEditable(true);

        // Status column
        statusCol.setCellValueFactory(data -> {
            SharedFolder f = data.getValue();
            String status = f.isValid() ? "✅ Valid" : "❌ Path not found";
            return new SimpleStringProperty(status);
        });
    }

    @FXML
    private void onAddFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select a Folder to Share");
        chooser.setInitialDirectory(new File(System.getProperty("user.home")));

        File chosen = chooser.showDialog(AppContext.getInstance().getPrimaryStage());
        if (chosen == null) return;

        // Default alias = folder name
        String baseAlias = chosen.getName();
        // Avoid duplicate aliases
        final String safeBase = baseAlias.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        long count = folders.stream()
            .filter(f -> f.getSafeAlias().equals(safeBase))
            .count();
        String alias = count > 0 ? baseAlias + "-" + (count + 1) : baseAlias;

        SharedFolder folder = new SharedFolder(alias, chosen.getAbsolutePath(), false);
        folders.add(folder);
        AppContext.getInstance().getConfig().getSharedFolders().add(folder);
        folderTable.getSelectionModel().selectLast();
        updateStatus();
    }

    @FXML
    private void onRemoveSelected() {
        SharedFolder selected = folderTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInfo("Please select a folder to remove.");
            return;
        }
        folders.remove(selected);
        AppContext.getInstance().getConfig().getSharedFolders().remove(selected);
        updateStatus();
    }

    @FXML
    private void onBack() { mainController.previousStep(); }

    @FXML
    private void onNext() { mainController.nextStep(); }

    @Override
    public boolean validate() {
        if (folders.isEmpty()) {
            showError("Please add at least one folder to share.");
            return false;
        }

        for (SharedFolder f : folders) {
            if (!f.isValid()) {
                showError("Folder '" + f.getAlias() + "' path does not exist or is not readable:\n" + f.getPath());
                return false;
            }
            if (f.getAlias() == null || f.getAlias().isBlank()) {
                showError("One of your folders is missing a display name.");
                return false;
            }
        }

        // Sync back to config
        AppContext.getInstance().getConfig().setSharedFolders(folders.stream().toList());
        showSuccess("✅ " + folders.size() + " folder(s) configured.");
        return true;
    }

    private void showGuestWarning() {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Guest Access Enabled");
        alert.setHeaderText("Public Access Warning");
        alert.setContentText(
            "Anyone on your local network will be able to browse this folder WITHOUT a password.\n\n"
            + "Make sure you are not sharing sensitive files in this folder.");
        alert.initOwner(AppContext.getInstance().getPrimaryStage());
        alert.showAndWait();
    }

    private void updateStatus() {
        if (statusLabel == null) return;
        if (folders.isEmpty()) {
            showInfo("Add at least one folder to continue.");
        } else {
            long guestCount = folders.stream().filter(SharedFolder::isGuestAccessible).count();
            String msg = folders.size() + " folder(s) added";
            if (guestCount > 0) msg += " • " + guestCount + " public (guest)";
            showSuccess(msg);
        }
    }

    private void showError(String msg) {
        if (statusLabel != null) { statusLabel.setText("❌ " + msg); statusLabel.getStyleClass().removeAll("success-text","info-text"); statusLabel.getStyleClass().add("error-text"); }
    }
    private void showSuccess(String msg) {
        if (statusLabel != null) { statusLabel.setText(msg); statusLabel.getStyleClass().removeAll("error-text","info-text"); statusLabel.getStyleClass().add("success-text"); }
    }
    private void showInfo(String msg) {
        if (statusLabel != null) { statusLabel.setText("ℹ " + msg); statusLabel.getStyleClass().removeAll("error-text","success-text"); statusLabel.getStyleClass().add("info-text"); }
    }
}
