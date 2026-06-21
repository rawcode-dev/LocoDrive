package com.locodrive.ui;

import com.locodrive.util.ConfigManager;
import javafx.animation.*;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Step 0 — Welcome Screen.
 * Animated logo entrance + tagline + "Get Started" button.
 */
public class WelcomeController extends BaseStepController implements Initializable {

    @FXML private VBox rootContainer;
    @FXML private Label taglineLabel;

    private static final String[] TAGLINES = {
        "Share files across your home or office network.",
        "No internet required — fast, private, local.",
        "Set up in minutes. No technical knowledge needed."
    };
    private int taglineIndex = 0;
    private Timeline taglineCycler;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        // Entrance animation on first load
        rootContainer.setOpacity(0);
        rootContainer.setTranslateY(30);
        FadeTransition fade = new FadeTransition(Duration.millis(700), rootContainer);
        fade.setFromValue(0); fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(700), rootContainer);
        slide.setFromY(30); slide.setToY(0);
        ParallelTransition intro = new ParallelTransition(fade, slide);
        intro.setDelay(Duration.millis(100));
        intro.play();

        // Cycle taglines
        startTaglineCycler();
    }

    private void startTaglineCycler() {
        if (taglineLabel == null) return;
        taglineLabel.setText(TAGLINES[0]);
        taglineCycler = new Timeline(
            new KeyFrame(Duration.seconds(3), e -> {
                taglineIndex = (taglineIndex + 1) % TAGLINES.length;
                FadeTransition ft = new FadeTransition(Duration.millis(400), taglineLabel);
                ft.setFromValue(1); ft.setToValue(0);
                ft.setOnFinished(ev -> {
                    taglineLabel.setText(TAGLINES[taglineIndex]);
                    FadeTransition fi = new FadeTransition(Duration.millis(400), taglineLabel);
                    fi.setFromValue(0); fi.setToValue(1);
                    fi.play();
                });
                ft.play();
            })
        );
        taglineCycler.setCycleCount(Animation.INDEFINITE);
        taglineCycler.play();
    }

    @FXML
    private void onGetStarted() {
        if (taglineCycler != null) taglineCycler.stop();
        mainController.nextStep();
    }

    @FXML
    private void onLoadConfig() {
        // Load saved config and jump to dashboard if valid
        ConfigManager.getInstance().loadConfig();
        if (com.locodrive.AppContext.getInstance().getConfig().isReady()) {
            mainController.jumpToDashboard();
        } else {
            mainController.nextStep();
        }
    }

    @Override
    public void onEnter() {
        if (taglineCycler != null && taglineCycler.getStatus() != Animation.Status.RUNNING) {
            taglineCycler.play();
        }
    }
}
