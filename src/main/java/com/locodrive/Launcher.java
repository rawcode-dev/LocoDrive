package com.locodrive;

/**
 * Launcher entry point — required JavaFX fat-JAR workaround.
 * This class does NOT extend Application, so the JAR manifest
 * can safely point here without triggering the JavaFX class loader issue.
 */
public class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}
