package com.locodrive.ui;

import com.locodrive.AppContext;
import com.locodrive.util.ConfigManager;
import javafx.animation.*;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Master controller for the main window.
 * Manages wizard navigation, step indicator dots, and slide transitions.
 */
public class MainController implements Initializable {

    @FXML private StackPane contentArea;
    @FXML private HBox stepIndicator;
    @FXML private HBox navBar;

    // Step names and their FXML files
    private static final String[] STEP_FXMLS = {
        "/fxml/WelcomeView.fxml",
        "/fxml/NetworkSetupView.fxml",
        "/fxml/FolderSetupView.fxml",
        "/fxml/UserManagementView.fxml",
        "/fxml/SecurityReviewView.fxml",
        "/fxml/DashboardView.fxml"
    };

    private static final String[] STEP_LABELS = {
        "Welcome", "Network", "Folders", "Users", "Review", "Dashboard"
    };

    private int currentStep = 0;
    private final Parent[] cachedViews = new Parent[STEP_FXMLS.length];
    private final Object[] cachedControllers = new Object[STEP_FXMLS.length];
    private Circle[] stepDots;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        buildStepDots();

        // If config already exists, we can start from the dashboard
        boolean hasConfig = ConfigManager.getInstance().configExists()
            && AppContext.getInstance().getConfig().isReady();

        if (hasConfig) {
            showStep(5); // Jump to dashboard
        } else {
            showStep(0);
        }
    }

    // ── Navigation API (called by child controllers) ───────────────────────────
    public void nextStep() {
        if (currentStep < STEP_FXMLS.length - 1) {
            // Ask current controller to validate before moving on
            if (validateCurrentStep()) {
                animateTo(currentStep + 1, true);
            }
        }
    }

    public void previousStep() {
        if (currentStep > 0) {
            animateTo(currentStep - 1, false);
        }
    }

    public void showStep(int step) {
        if (step >= 0 && step < STEP_FXMLS.length) {
            animateTo(step, step >= currentStep);
        }
    }

    public void jumpToDashboard() { showStep(5); }
    public void jumpToWelcome()   { showStep(0); }

    // ── Validation ────────────────────────────────────────────────────────────
    private boolean validateCurrentStep() {
        Object controller = cachedControllers[currentStep];
        if (controller instanceof Validatable v) {
            return v.validate();
        }
        return true;
    }

    // ── Animation & View Loading ──────────────────────────────────────────────
    private void animateTo(int targetStep, boolean forward) {
        Parent targetView = getOrLoadView(targetStep);
        if (targetView == null) return;

        int prevStep = currentStep;
        currentStep = targetStep;
        updateStepDots();

        // Show/hide nav bar (hidden on dashboard)
        navBar.setVisible(targetStep < 5);
        navBar.setManaged(targetStep < 5);
        stepIndicator.setVisible(targetStep > 0 && targetStep < 5);

        if (contentArea.getChildren().isEmpty()) {
            contentArea.getChildren().add(targetView);
            notifyEnter(targetStep);
            return;
        }

        Parent currentView = contentArea.getChildren().isEmpty() ? null
            : (Parent) contentArea.getChildren().get(contentArea.getChildren().size() - 1);

        double width = contentArea.getWidth();
        targetView.setTranslateX(forward ? width : -width);
        contentArea.getChildren().add(targetView);
        notifyEnter(targetStep);

        Timeline timeline = new Timeline(
            new KeyFrame(Duration.millis(300),
                new KeyValue(targetView.translateXProperty(), 0, Interpolator.EASE_BOTH),
                new KeyValue(currentView != null ? currentView.translateXProperty() : targetView.translateXProperty(),
                    forward ? -width : width, Interpolator.EASE_BOTH)
            )
        );
        timeline.setOnFinished(e -> {
            if (currentView != null) contentArea.getChildren().remove(currentView);
        });
        timeline.play();
    }

    private Parent getOrLoadView(int step) {
        if (cachedViews[step] != null) return cachedViews[step];
        try {
            URL fxmlUrl = getClass().getResource(STEP_FXMLS[step]);
            Objects.requireNonNull(fxmlUrl, "FXML not found: " + STEP_FXMLS[step]);
            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            Parent view = loader.load();
            Object controller = loader.getController();

            // Inject the main controller reference
            if (controller instanceof BaseStepController bsc) {
                bsc.setMainController(this);
            }

            cachedViews[step] = view;
            cachedControllers[step] = controller;
            return view;
        } catch (IOException e) {
            System.err.println("Failed to load FXML: " + STEP_FXMLS[step] + " — " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private void notifyEnter(int step) {
        Object controller = cachedControllers[step];
        if (controller instanceof BaseStepController bsc) {
            bsc.onEnter();
        }
    }

    // ── Step Indicator Dots ───────────────────────────────────────────────────
    private void buildStepDots() {
        stepDots = new Circle[5]; // Steps 1–5 (not welcome or dashboard)
        stepIndicator.getChildren().clear();
        for (int i = 0; i < 5; i++) {
            Circle dot = new Circle(5);
            dot.getStyleClass().add("step-dot");
            stepIndicator.getChildren().add(dot);
            stepDots[i] = dot;
        }
        updateStepDots();
    }

    private void updateStepDots() {
        if (stepDots == null) return;
        for (int i = 0; i < stepDots.length; i++) {
            stepDots[i].getStyleClass().removeAll("active", "completed");
            int mappedStep = i + 1; // Step 1=network, 2=folders, etc.
            if (mappedStep < currentStep) {
                stepDots[i].getStyleClass().add("completed");
            } else if (mappedStep == currentStep) {
                stepDots[i].getStyleClass().add("active");
            }
        }
    }

    // ── Interfaces ────────────────────────────────────────────────────────────
    public interface Validatable {
        boolean validate();
    }
}
