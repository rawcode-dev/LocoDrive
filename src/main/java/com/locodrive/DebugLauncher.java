package com.locodrive;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;

/**
 * Minimal debug launcher — loads MainView.fxml with full error reporting.
 * Run with: mvn javafx:run -Djavafx.mainClass=com.locodrive.DebugLauncher
 */
public class DebugLauncher extends Application {

    @Override
    public void start(Stage stage) {
        System.out.println("=== DebugLauncher: start() called ===");
        try {
            URL url = getClass().getResource("/fxml/MainView.fxml");
            System.out.println("MainView URL: " + url);
            if (url == null) {
                System.err.println("ERROR: MainView.fxml not found on classpath!");
                stage.close();
                return;
            }
            FXMLLoader loader = new FXMLLoader(url);
            System.out.println("Loading FXML...");
            Parent root = loader.load();
            System.out.println("FXML loaded OK. Controller: " + loader.getController());

            // Try CSS
            URL cssUrl = getClass().getResource("/css/app.css");
            System.out.println("CSS URL: " + cssUrl);

            Scene scene = new Scene(root, 1100, 700);
            if (cssUrl != null) scene.getStylesheets().add(cssUrl.toExternalForm());

            stage.setTitle("Debug — LocoDrive");
            stage.setScene(scene);
            stage.show();
            System.out.println("=== Window shown successfully ===");

        } catch (Throwable t) {
            System.err.println("=== STARTUP EXCEPTION ===");
            t.printStackTrace(System.err);
            if (t.getCause() != null) {
                System.err.println("=== CAUSED BY ===");
                t.getCause().printStackTrace(System.err);
            }
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
