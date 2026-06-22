package com.locodrive;

import com.locodrive.server.LocalFileServer;
import com.locodrive.util.ConfigManager;
import com.locodrive.util.NetworkDetector;

import java.io.IOException;
import java.util.Scanner;

/**
 * Headless entry point for the LocoDrive file server.
 * No JavaFX — runs as a pure background HTTP server.
 * Designed to be launched as a sidecar process by Tauri.
 *
 * Communication protocol with Tauri:
 *   stdout: "READY http://ip:port"   → server is up
 *   stdout: "STOPPED"               → server has stopped
 *   stdin:  "STOP"                  → graceful shutdown
 *   stdin:  EOF                     → graceful shutdown (parent died)
 */
public class ServerMain {

    public static void main(String[] args) throws IOException {
        // Load saved config
        AppContext ctx = AppContext.getInstance();
        ConfigManager.getInstance().loadConfig();

        // Auto-select best IP if not configured
        if (ctx.getConfig().getBindAddress().isBlank()) {
            ctx.getConfig().setBindAddress(NetworkDetector.getBestAddress());
        }

        // Start the HTTP server
        LocalFileServer server = new LocalFileServer(ctx.getConfig());
        ctx.setServer(server);
        server.start();

        // Signal to Tauri that we are ready
        String url = "http://" + ctx.getConfig().getBindAddress() + ":" + ctx.getConfig().getPort();
        System.out.println("READY " + url);
        System.out.flush();

        // Register shutdown hook for SIGTERM / app exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            ConfigManager.getInstance().saveConfig();
            System.out.println("STOPPED");
            System.out.flush();
        }));

        // Listen for "STOP" command from Tauri on stdin
        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if ("STOP".equalsIgnoreCase(line)) {
                    System.exit(0); // Triggers shutdown hook
                }
            }
        }
        // EOF reached (Tauri parent process died) — shut down
        System.exit(0);
    }
}
